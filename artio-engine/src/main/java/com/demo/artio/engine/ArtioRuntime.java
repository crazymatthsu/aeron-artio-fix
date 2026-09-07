package com.demo.artio.engine;

import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.LibraryConnectHandler;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

/**
 * An embedded Artio FIX engine: media driver, Aeron archive, {@code FixEngine}, {@code FixLibrary}
 * and the single thread that polls the library, all owned by one {@link AutoCloseable}.
 *
 * <h2>Topology</h2>
 * <pre>
 *   caller thread                library poll thread (AgentRunner)
 *        |                                  |
 *   start()/close()                library.poll(10) ---> SessionHandler ---> FixMessageSink
 *   send(Encoder) --[queue]------> Session.trySend(encoder)
 *        |                                  |
 *   ArchivingMediaDriver  <---- IPC ----  FixEngine (framer, indexer, monitoring threads)
 * </pre>
 *
 * <h2>Threading rules</h2>
 * Artio's {@code FixLibrary} and every {@code Session} it owns belong to the poll thread and only
 * to the poll thread. This class enforces that: {@link #send(Encoder)} and
 * {@link #submit(Consumer)} put work on a queue that the poll thread drains, and return a future.
 * The {@code Session} objects handed out by {@link #sessions()} are safe to <em>identify</em> from
 * another thread but must not be written to from one.
 *
 * <h2>Reconnect</h2>
 * An {@link EngineMode#INITIATOR} whose session disconnects re-initiates it from the poll thread,
 * waiting {@link FixEngineConfig#reconnectInitialBackoffMs()} before the first attempt and doubling
 * up to {@link FixEngineConfig#reconnectMaxBackoffMs()} after every failure; the back-off resets
 * once the session is logged on again. Each attempt is announced through
 * {@link SessionListener#onReconnectAttempt(int, long)}. Nothing is attempted when
 * {@link FixEngineConfig#reconnectEnabled()} is false, for an acceptor (whose counterparty does the
 * connecting), or once {@link #close()} has begun. {@link #isRunning()} stays true throughout, so
 * {@link #isSessionActive()} is what tells a caller whether a {@link #send(Encoder)} can succeed
 * right now.
 *
 * <h2>Directories</h2>
 * Each runtime gets its own Aeron directory, archive directory and Artio log directory, all under
 * {@link FixEngineConfig#baseDirectory()}/{@code artio-<runtimeId>}, where the id is
 * {@code <name>-<mode>-<pid>-<counter>}: unique per JVM and per process, so runtimes can share a
 * JVM or only a machine. Derived directories are wiped on start (anything there is a crash
 * leftover). An explicit {@link FixEngineConfig#aeronDirectory()} or
 * {@link FixEngineConfig#logFileDir()} is never wiped on start: {@link #start()} fails if another
 * media driver is live in the Aeron directory, and Artio reuses what it finds in the log directory.
 * {@link #close()} removes every directory, explicit ones included, unless
 * {@link FixEngineConfig#deleteDirectoriesOnClose()} is false.
 *
 * <h2>JVM flags</h2>
 * Agrona 2.x reaches {@code jdk.internal.misc.Unsafe}, so every JVM that runs this class needs:
 * <pre>
 * --add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
 * --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
 * </pre>
 */
public final class ArtioRuntime implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtioRuntime.class);

    /** How many Aeron fragments one {@code library.poll} may consume. */
    private static final int POLL_FRAGMENT_LIMIT = 10;

    /** How long close() parks between checks while it waits for the sessions to go away. */
    private static final long SHUTDOWN_PARK_NS = 100_000L;

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    private final FixEngineConfig config;
    private final FixMessageSink sink;
    private final SessionListener listener;
    private final String runtimeId;
    private final Path runtimeDirectory;
    private final Path aeronDirectory;
    private final Path archiveDirectory;
    private final Path logFileDirectory;
    /** True when the caller named the directory rather than letting this runtime derive it. */
    private final boolean aeronDirectoryIsExplicit;
    private final boolean logFileDirIsExplicit;

    private final FixMessageView view = new FixMessageView();
    private final CommandQueue commands = new CommandQueue();
    /** Poll-thread-only: drives the back-off and the re-initiate after an initiator's disconnect. */
    private final Reconnector reconnector = new Reconnector();
    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** The archive's IPC control channel, read off the concluded {@link Archive.Context}. */
    private String archiveControlRequestChannel;
    private int archiveControlRequestStreamId;

    private volatile ArchivingMediaDriver mediaDriver;
    private volatile FixEngine engine;
    private volatile FixLibrary library;
    private volatile AgentRunner agentRunner;
    /** The library poll thread, once the {@link AgentRunner} is on it; close() must not run there. */
    private volatile Thread pollThread;
    private volatile boolean shutdownRequested;
    private volatile long logonLatencyNs = -1;

    /**
     * Builds a runtime. Nothing is started until {@link #start()}.
     *
     * @param config   the validated configuration.
     * @param sink     where received messages go.
     * @param listener session lifecycle callbacks; may be null for none.
     */
    public ArtioRuntime(final FixEngineConfig config, final FixMessageSink sink, final SessionListener listener)
    {
        this.config = Objects.requireNonNull(config, "config");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.listener = listener == null ? new SessionListener()
        {
        } : listener;
        // <name>-<mode>-<pid>-<counter>: the counter separates runtimes in one JVM, the pid
        // separates JVMs on one machine. Without the pid two processes with the same name (a
        // parallel test worker, a second instance of the same application) derive the same Aeron
        // directory and wipe each other's live media driver on start.
        this.runtimeId = config.name() + '-' + config.mode().name().toLowerCase() + '-' +
            ProcessHandle.current().pid() + '-' + INSTANCE_COUNTER.incrementAndGet();
        this.runtimeDirectory = config.baseDirectory().resolve("artio-" + runtimeId);
        this.aeronDirectoryIsExplicit = config.aeronDirectory() != null;
        this.aeronDirectory = aeronDirectoryIsExplicit ?
            config.aeronDirectory() : runtimeDirectory.resolve("aeron");
        this.archiveDirectory = runtimeDirectory.resolve("archive");
        this.logFileDirIsExplicit = config.logFileDir() != null;
        this.logFileDirectory = logFileDirIsExplicit ?
            config.logFileDir() : runtimeDirectory.resolve("logs");
    }

    /**
     * Builds a runtime with no session listener.
     *
     * @param config the validated configuration.
     * @param sink   where received messages go.
     */
    public ArtioRuntime(final FixEngineConfig config, final FixMessageSink sink)
    {
        this(config, sink, null);
    }

    /**
     * Builds and starts a runtime in one step.
     *
     * @param config   the validated configuration.
     * @param sink     where received messages go.
     * @param listener session lifecycle callbacks; may be null.
     * @return the started runtime.
     */
    public static ArtioRuntime launch(
        final FixEngineConfig config, final FixMessageSink sink, final SessionListener listener)
    {
        final ArtioRuntime runtime = new ArtioRuntime(config, sink, listener);
        runtime.start();
        return runtime;
    }

    /**
     * Starts the media driver, the archive, the engine and the library, and then:
     * <ul>
     *   <li>for an {@link EngineMode#ACCEPTOR}, returns once the port is bound - which in
     *       Artio's sole-library mode happens as part of the library connecting, so a counterparty
     *       can connect the moment this returns;</li>
     *   <li>for an {@link EngineMode#INITIATOR}, initiates the session and returns once it is
     *       active, i.e. the logon exchange has completed.</li>
     * </ul>
     * Start-up polls the library on the calling thread; the {@link AgentRunner} takes over only
     * once everything is up, so exactly one thread ever polls.
     *
     * @throws IllegalStateException if already started, or if start-up fails or times out. On
     *                               failure everything already started is closed again.
     */
    public void start()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException(runtimeId + " has already been started");
        }

        final IdleStrategy startupIdle = config.idleStrategy().create();
        try
        {
            createDirectories();
            mediaDriver = launchMediaDriver();
            engine = FixEngine.launch(engineConfiguration());
            library = FixLibrary.connect(libraryConfiguration());

            awaitLibraryConnected(startupIdle);
            if (config.mode() == EngineMode.INITIATOR)
            {
                initiateSession(startupIdle);
            }

            agentRunner = new AgentRunner(config.idleStrategy().create(), this::onAgentError, null, new PollAgent());
            pollThread = AgentRunner.startOnThread(agentRunner);

            LOGGER.info("{} started: mode={} {} {}:{} {}->{} dir={}",
                runtimeId, config.mode(), config.fixVersion().beginString(), config.host(), config.port(),
                config.senderCompId(), config.targetCompId(), runtimeDirectory);
        }
        catch (final RuntimeException | Error e)
        {
            closeQuietlyAfterFailedStart();
            throw e;
        }
    }

    // ------------------------------------------------------------------ start-up

    private void createDirectories()
    {
        IoUtil.ensureDirectoryExists(runtimeDirectory.toFile(), "runtime");
        IoUtil.ensureDirectoryExists(logFileDirectory.toFile(), "artio log");
    }

    private ArchivingMediaDriver launchMediaDriver()
    {
        final MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirectory.toString())
            .threadingMode(ThreadingMode.SHARED)
            // A derived directory is ours alone (pid and counter are in its name), so anything
            // already there is a leftover from a crash and can go. An explicit one may belong to a
            // live driver in another process: never wipe it blindly. With dirDeleteOnStart(false)
            // Aeron reads the existing CnC file and refuses to start over an active driver, and
            // only then clears what a dead one left behind; the check below just says so in words.
            .dirDeleteOnStart(deletesAeronDirectoryOnStart())
            .dirDeleteOnShutdown(config.deleteDirectoriesOnClose())
            .errorHandler(this::onAgentError);
        if (aeronDirectoryIsExplicit)
        {
            failIfAnotherDriverIsLive(driverContext);
        }

        // Recording events are a separate UDP multicast-ish stream nothing here consumes, and the
        // archive's remote control channel would bind a second fixed port - both would collide the
        // moment a second runtime started in the same JVM. Artio talks to its archive over the
        // local IPC control channel instead, inside this runtime's own Aeron directory.
        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirectory.toString())
            .archiveDirectoryName(archiveDirectory.toString())
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(true)
            .recordingEventsEnabled(false)
            .controlChannelEnabled(false)
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            // With the remote control channel disabled, the archive's own client (used for
            // replication and catalog work) has to be told to answer over IPC too.
            .archiveClientContext(new AeronArchive.Context().controlResponseChannel(IPC_CHANNEL))
            .errorHandler(this::onAgentError);

        archiveControlRequestChannel = archiveContext.localControlChannel();
        archiveControlRequestStreamId = archiveContext.localControlStreamId();

        return ArchivingMediaDriver.launch(driverContext, archiveContext);
    }

    private void failIfAnotherDriverIsLive(final MediaDriver.Context driverContext)
    {
        final File directory = aeronDirectory.toFile();
        if (CommonContext.isDriverActive(directory, driverContext.driverTimeoutMs(), LOGGER::debug))
        {
            throw new IllegalStateException(
                runtimeId + ": another media driver is live in the explicit aeronDirectory " + directory +
                    "; give each runtime its own directory, or leave aeronDirectory unset to derive one");
        }
    }

    /**
     * @return true if start-up wipes whatever is in the Aeron directory, which is only safe for a
     * directory nobody else can be using: a derived one.
     */
    boolean deletesAeronDirectoryOnStart()
    {
        return !aeronDirectoryIsExplicit;
    }

    /**
     * @return true if start-up wipes the Artio log directory. A derived one holds nothing worth
     * keeping; an explicit one is reused, sequence numbers and message log included.
     */
    boolean deletesLogFileDirOnStart()
    {
        return !logFileDirIsExplicit;
    }

    private EngineConfiguration engineConfiguration()
    {
        final EngineConfiguration configuration = new EngineConfiguration()
            .libraryAeronChannel(IPC_CHANNEL)
            .logFileDir(logFileDirectory.toString())
            .deleteLogFileDirOnStart(deletesLogFileDirOnStart());

        configuration.agentNamePrefix(runtimeId + '-');
        configuration.monitoringFile(runtimeDirectory.resolve("engineCounters").toString());
        configuration.replyTimeoutInMs(config.replyTimeoutMs());
        configuration.defaultHeartbeatIntervalInS(config.heartbeatIntervalSec());

        if (config.mode() == EngineMode.ACCEPTOR)
        {
            configuration
                // bindTo also sets bindAtStartup(true); with SOLE_LIBRARY the actual bind is
                // deferred until the library connects, which is what makes start() deterministic.
                .bindTo(config.host(), config.port())
                .initialAcceptedSessionOwner(InitialAcceptedSessionOwner.SOLE_LIBRARY)
                // Without this an inbound FIX 4.2 logon is parsed with Artio's bundled FIX 4.4
                // session dictionary and rejected on BeginString.
                .acceptorfixDictionary(config.fixVersion().dictionary())
                .authenticationStrategy(config.authenticationStrategy());
        }

        configuration.aeronContext()
            .aeronDirectoryName(aeronDirectory.toString())
            .errorHandler(this::onAgentError);

        configuration.aeronArchiveContext()
            .aeronDirectoryName(aeronDirectory.toString())
            .controlRequestChannel(archiveControlRequestChannel)
            .controlRequestStreamId(archiveControlRequestStreamId)
            .controlResponseChannel(CommonContext.IPC_CHANNEL);

        return configuration;
    }

    private LibraryConfiguration libraryConfiguration()
    {
        final LibraryConfiguration configuration = new LibraryConfiguration()
            .libraryAeronChannels(List.of(IPC_CHANNEL))
            .libraryName(runtimeId)
            .libraryIdleStrategy(config.idleStrategy().create())
            .sessionAcquireHandler((session, acquiredInfo) -> acquire(session))
            .sessionExistsHandler((lib, sessionId, localCompId, localSubId, localLocationId,
                remoteCompId, remoteSubId, remoteLocationId, logonSeqNum, logonSeqIndex) ->
                LOGGER.debug("{} session exists: id={} {}->{}", runtimeId, sessionId, remoteCompId, localCompId))
            .libraryConnectHandler(new LibraryConnectHandler()
            {
                public void onConnect(final FixLibrary connected)
                {
                    LOGGER.debug("{} library {} connected to engine", runtimeId, connected.libraryId());
                }

                public void onDisconnect(final FixLibrary disconnected)
                {
                    LOGGER.debug("{} library disconnected from engine", runtimeId);
                }
            })
            .replyTimeoutInMs(config.replyTimeoutMs());

        if (config.libraryId() != FixEngineConfig.AUTO_LIBRARY_ID)
        {
            configuration.libraryId(config.libraryId());
        }

        configuration.agentNamePrefix(runtimeId + '-');
        configuration.monitoringFile(runtimeDirectory.resolve("libraryCounters").toString());
        configuration.defaultHeartbeatIntervalInS(config.heartbeatIntervalSec());
        configuration.aeronContext()
            .aeronDirectoryName(aeronDirectory.toString())
            .errorHandler(this::onAgentError);

        return configuration;
    }

    private void awaitLibraryConnected(final IdleStrategy idleStrategy)
    {
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.logonTimeoutMs());
        while (!library.isConnected())
        {
            if (System.nanoTime() > deadlineNs)
            {
                throw new IllegalStateException(
                    runtimeId + ": FixLibrary did not connect to the engine within " +
                        config.logonTimeoutMs() + "ms");
            }
            idleStrategy.idle(library.poll(POLL_FRAGMENT_LIMIT));
        }
        LOGGER.debug("{} library connected, id={}", runtimeId, library.libraryId());
    }

    /** @return the request {@code library.initiate(...)} takes, on start-up and on every reconnect. */
    private SessionConfiguration sessionConfiguration()
    {
        return SessionConfiguration.builder()
            .address(config.host(), config.port())
            .senderCompId(config.senderCompId())
            .targetCompId(config.targetCompId())
            .fixDictionary(config.fixVersion().dictionary())
            .resetSeqNum(config.resetSeqNumsOnLogon())
            .timeoutInMs(config.logonTimeoutMs())
            .build();
    }

    private void initiateSession(final IdleStrategy idleStrategy)
    {
        final SessionConfiguration sessionConfiguration = sessionConfiguration();

        final long startedAtNs = System.nanoTime();
        final long deadlineNs = startedAtNs + TimeUnit.MILLISECONDS.toNanos(config.logonTimeoutMs());

        Reply<Session> reply = library.initiate(sessionConfiguration);
        while (reply == null)
        {
            // initiate() returns null when the request could not be enqueued; retry on the duty cycle.
            checkDeadline(deadlineNs, "enqueue the initiate request");
            idleStrategy.idle(library.poll(POLL_FRAGMENT_LIMIT));
            reply = library.initiate(sessionConfiguration);
        }

        while (reply.isExecuting())
        {
            checkDeadline(deadlineNs, "initiate a session to " + config.host() + ':' + config.port());
            idleStrategy.idle(library.poll(POLL_FRAGMENT_LIMIT));
        }
        if (!reply.hasCompleted())
        {
            throw new IllegalStateException(
                runtimeId + ": could not initiate a session to " + config.host() + ':' + config.port() +
                    " (" + reply.state() + ')', reply.error());
        }

        final Session session = reply.resultIfPresent();
        LOGGER.debug("{} initiate reply completed; session {} is {}", runtimeId, session.id(), session.state());
        while (!session.isActive())
        {
            // The reply completes once TCP is up, before the logon exchange. If the counterparty
            // then rejects the logon or drops the connection, the session goes DISCONNECTED and
            // will never become active: say so now rather than after the whole logon timeout.
            final SessionState state = session.state();
            if (state == SessionState.DISCONNECTED || state == SessionState.DISABLED)
            {
                throw new IllegalStateException(
                    runtimeId + ": " + config.host() + ':' + config.port() +
                        " disconnected before the logon completed (session state " + state +
                        "); the counterparty rejected the logon or dropped the connection");
            }
            checkDeadline(deadlineNs, "complete the logon exchange");
            idleStrategy.idle(library.poll(POLL_FRAGMENT_LIMIT));
        }
        logonLatencyNs = System.nanoTime() - startedAtNs;
        LOGGER.info("{} logged on to {}:{} in {}ms",
            runtimeId, config.host(), config.port(),
            TimeUnit.NANOSECONDS.toMillis(logonLatencyNs));
    }

    private void checkDeadline(final long deadlineNs, final String what)
    {
        if (System.nanoTime() > deadlineNs)
        {
            throw new IllegalStateException(
                runtimeId + ": timed out after " + config.logonTimeoutMs() + "ms trying to " + what);
        }
    }

    // ------------------------------------------------------------------ sessions

    private SessionHandler acquire(final Session session)
    {
        final RuntimeSessionHandler handler = new RuntimeSessionHandler(session);
        sessions.addIfAbsent(session);
        LOGGER.info("{} acquired {}", runtimeId, handler.key());
        listener.onSessionAcquired(handler.key());
        return handler;
    }

    /** @return the sessions this runtime currently owns; a snapshot, safe to iterate. */
    public List<Session> sessions()
    {
        return List.copyOf(sessions);
    }

    /**
     * @return the first (usually only) session this runtime owns, or empty if none is up. Handy
     * for the one-session-per-runtime case that tests and the demo use.
     */
    public Optional<Session> session()
    {
        return Optional.ofNullable(firstSession());
    }

    /** @return the first tracked session, or null. Iterating avoids racing a concurrent removal. */
    private Session firstSession()
    {
        for (final Session session : sessions)
        {
            return session;
        }
        return null;
    }

    /** @return the identities of the sessions this runtime currently owns. */
    public List<SessionKey> sessionKeys()
    {
        final List<SessionKey> keys = new ArrayList<>();
        for (final Session session : sessions)
        {
            keys.add(sessionKeyOf(session));
        }
        return keys;
    }

    private SessionKey sessionKeyOf(final Session session)
    {
        final CompositeKey compositeKey = session.compositeKey();
        final String local = compositeKey != null ? compositeKey.localCompId() : config.senderCompId();
        final String remote = compositeKey != null ? compositeKey.remoteCompId() : config.targetCompId();
        String beginString;
        try
        {
            beginString = session.beginString();
        }
        catch (final RuntimeException e)
        {
            beginString = config.fixVersion().beginString();
        }
        return new SessionKey(session.id(), local, remote, beginString, session.isAcceptor());
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends an encoded message on the runtime's first session, from the poll thread.
     *
     * <p>The encoder is <strong>not</strong> copied: do not touch it again until the returned
     * future completes. Artio back-pressure is retried on the poll thread until
     * {@link FixEngineConfig#replyTimeoutMs()} has passed.
     *
     * <p>The future completes exceptionally, with an {@link IllegalStateException}, when there is
     * no session, when the session is not {@code ACTIVE} (still logging on, logging out,
     * disconnected), when Artio refuses the message with a position that a retry cannot cure, when
     * back-pressure outlasts the reply timeout, or when the runtime is closed before the message
     * was written. A completed future means the message was written to the session's log.
     *
     * @param encoder a generated Artio encoder with its body already set. The session fills in the
     *                header (CompIDs, sequence number, sending time).
     * @return the Aeron position the message was written at.
     */
    public CompletableFuture<Long> send(final Encoder encoder)
    {
        return send(-1, encoder);
    }

    /**
     * Sends an encoded message on one specific session.
     *
     * @param sessionId Artio's surrogate session id, or -1 for the runtime's first session.
     * @param encoder   a generated Artio encoder with its body already set.
     * @return the Aeron position the message was written at.
     */
    public CompletableFuture<Long> send(final long sessionId, final Encoder encoder)
    {
        Objects.requireNonNull(encoder, "encoder");
        final SendCommand command = new SendCommand(
            sessionId, encoder, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.replyTimeoutMs()));
        enqueue(command);
        return command.future;
    }

    /**
     * Convenience wrapper around {@link #send(Encoder)} for callers that just want it to have
     * happened.
     *
     * @param encoder a generated Artio encoder.
     * @param timeout how long to wait for the poll thread to write the message.
     * @return the Aeron position the message was written at.
     * @throws IllegalStateException if the send failed or timed out.
     */
    public long sendAndAwait(final Encoder encoder, final Duration timeout)
    {
        try
        {
            return send(encoder).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(runtimeId + ": interrupted while sending", e);
        }
        catch (final ExecutionException | TimeoutException e)
        {
            throw new IllegalStateException(runtimeId + ": could not send message", e);
        }
    }

    /**
     * Runs an action on the library poll thread, which is the only thread allowed to touch the
     * {@code FixLibrary} and its sessions. This is the escape hatch for anything this class does
     * not wrap.
     *
     * @param action the action, given the live library.
     * @return a future that completes when the action has run, or completes exceptionally if it
     * threw.
     */
    public CompletableFuture<Void> submit(final Consumer<FixLibrary> action)
    {
        Objects.requireNonNull(action, "action");
        final SubmitCommand command = new SubmitCommand(action);
        enqueue(command);
        return command.future;
    }

    private void enqueue(final CommandQueue.Command command)
    {
        if (closed.get())
        {
            command.fail(new IllegalStateException(runtimeId + " is closed"));
            return;
        }
        if (agentRunner == null)
        {
            command.fail(new IllegalStateException(runtimeId + " has not been started"));
            return;
        }
        // The checks above are a courtesy, not the guarantee: a caller can pass them and then be
        // overtaken by close(). CommandQueue is what makes sure a command offered after the poll
        // thread's final drain is still failed rather than left with a future that never completes.
        commands.offer(command);
    }

    // ------------------------------------------------------------------ accessors

    /** @return the unique id of this runtime, which also prefixes its Artio thread names. */
    public String runtimeId()
    {
        return runtimeId;
    }

    /** @return the configuration this runtime was built from. */
    public FixEngineConfig config()
    {
        return config;
    }

    /** @return the directory holding this runtime's archive and, unless explicit, its Aeron and Artio log directories. */
    public Path directory()
    {
        return runtimeDirectory;
    }

    /** @return the Aeron directory in use: the explicit one, or {@code directory()/aeron}. */
    public Path aeronDirectory()
    {
        return aeronDirectory;
    }

    /** @return the Artio log directory in use: the explicit one, or {@code directory()/logs}. */
    public Path logFileDir()
    {
        return logFileDirectory;
    }

    /** @return true between a successful {@link #start()} and {@link #close()}. */
    public boolean isRunning()
    {
        return started.get() && !closed.get();
    }

    /**
     * @return true if at least one session this runtime owns is {@code ACTIVE}, i.e. logged on and
     * able to send. False while an initiator is between a disconnect and a successful reconnect,
     * and for an acceptor nobody is connected to. A snapshot: the poll thread may change it the
     * moment this returns.
     */
    public boolean isSessionActive()
    {
        for (final Session session : sessions)
        {
            if (session.isActive())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return how long the logon exchange took, measured from {@code library.initiate(...)} to the
     * session going active. Only an {@link EngineMode#INITIATOR} measures this; an acceptor's logon
     * is driven by the counterparty.
     */
    public Optional<Duration> logonLatency()
    {
        final long latency = logonLatencyNs;
        return latency < 0 ? Optional.empty() : Optional.of(Duration.ofNanos(latency));
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Logs out and disconnects every session, stops the poll thread, then closes the library, the
     * engine and the media driver in that order, and finally deletes this runtime's directories.
     *
     * <p>Idempotent, and safe to call on a runtime whose {@link #start()} failed part way through.
     *
     * <p><strong>Not from the poll thread.</strong> Every {@link FixMessageSink} and
     * {@link SessionListener} callback, and every {@link #submit(Consumer)} action, runs on the
     * library poll thread, and close() waits for that thread: for its sessions to log out and then
     * for it to stop. Called from there it would wait for itself, so it throws instead and the
     * runtime stays up. A sink that wants to stop the runtime hands the call to another thread
     * (an executor, a shutdown latch the main thread waits on).
     *
     * @throws IllegalStateException if called on the library poll thread.
     */
    @Override
    public void close()
    {
        final Thread poller = pollThread;
        if (poller != null && Thread.currentThread() == poller)
        {
            throw new IllegalStateException(
                runtimeId + ": close() was called on the library poll thread (" + poller.getName() +
                    "), which it would have to wait for; call it from another thread");
        }
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        if (!started.get())
        {
            return;
        }

        try
        {
            gracefullyLogout();
        }
        catch (final RuntimeException e)
        {
            LOGGER.warn("{}: logout before shutdown failed", runtimeId, e);
        }

        // Stops the poll thread; AgentRunner calls PollAgent.onClose() on that thread, which is
        // where the FixLibrary is closed - the library must not be closed from any other thread.
        CloseHelper.quietClose(agentRunner);
        if (agentRunner == null)
        {
            CloseHelper.quietClose(library);
        }
        CloseHelper.quietClose(engine);
        CloseHelper.quietClose(mediaDriver);
        deleteDirectories();
        LOGGER.info("{} closed", runtimeId);
    }

    private void gracefullyLogout()
    {
        if (agentRunner == null || sessions.isEmpty())
        {
            return;
        }
        shutdownRequested = true;
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownTimeoutMs());
        while (!sessions.isEmpty() && System.nanoTime() < deadlineNs)
        {
            LockSupport.parkNanos(SHUTDOWN_PARK_NS);
        }
        if (!sessions.isEmpty())
        {
            LOGGER.warn("{}: {} session(s) had not disconnected after {}ms; closing anyway",
                runtimeId, sessions.size(), config.shutdownTimeoutMs());
        }
    }

    private void closeQuietlyAfterFailedStart()
    {
        closed.set(true);
        CloseHelper.quietClose(agentRunner);
        if (agentRunner == null)
        {
            CloseHelper.quietClose(library);
        }
        CloseHelper.quietClose(engine);
        CloseHelper.quietClose(mediaDriver);
        deleteDirectories();
    }

    private void deleteDirectories()
    {
        if (!config.deleteDirectoriesOnClose())
        {
            return;
        }
        deleteIfPresent(runtimeDirectory);
        // An explicit directory is only removed once this runtime actually took it over: a start
        // that failed because another driver or engine was live in there must leave theirs alone.
        if (aeronDirectoryIsExplicit && mediaDriver != null)
        {
            deleteIfPresent(aeronDirectory);
        }
        if (logFileDirIsExplicit && engine != null)
        {
            deleteIfPresent(logFileDirectory);
        }
    }

    private static void deleteIfPresent(final Path path)
    {
        final File directory = path.toFile();
        if (directory.exists())
        {
            IoUtil.delete(directory, true);
        }
    }

    /**
     * Runs a listener callback on the poll thread, swallowing anything it throws: a listener that
     * blows up must not take the poll thread, and with it the whole runtime, down with it.
     *
     * @param notification the callback.
     */
    private void notifyListener(final Runnable notification)
    {
        try
        {
            notification.run();
        }
        catch (final RuntimeException e)
        {
            LOGGER.error("{}: session listener threw", runtimeId, e);
        }
    }

    private void onAgentError(final Throwable error)
    {
        LOGGER.error("{}: agent error", runtimeId, error);
        try
        {
            listener.onError(error);
        }
        catch (final RuntimeException e)
        {
            LOGGER.error("{}: session listener threw from onError", runtimeId, e);
        }
    }

    // ------------------------------------------------------------------ the poll thread

    /**
     * The only thread that touches the {@code FixLibrary}. Polls the library, drains the command
     * queue, and during shutdown walks the sessions asking them to log out.
     */
    private final class PollAgent implements Agent
    {
        private final LongHashSet logoutRequested = new LongHashSet();
        private SendCommand pendingSend;

        @Override
        public int doWork()
        {
            int work = library.poll(POLL_FRAGMENT_LIMIT);
            work += drainCommands();
            if (shutdownRequested)
            {
                work += requestLogouts();
            }
            else
            {
                // The reconnect back-off is a deadline this loop checks, not a timer thread: the
                // library and its sessions stay the property of this one thread.
                work += reconnector.doWork();
            }
            return work;
        }

        private int drainCommands()
        {
            int work = 0;
            if (pendingSend != null)
            {
                if (!attemptSend(pendingSend))
                {
                    return work;
                }
                pendingSend = null;
                work++;
            }

            CommandQueue.Command command;
            while ((command = commands.poll()) != null)
            {
                work++;
                if (command instanceof SendCommand send)
                {
                    if (!attemptSend(send))
                    {
                        pendingSend = send;
                        return work;
                    }
                }
                else
                {
                    ((SubmitCommand)command).run(library);
                }
            }
            return work;
        }

        private boolean attemptSend(final SendCommand command)
        {
            final Session target = command.sessionId < 0 ?
                firstSession() : findSession(command.sessionId);
            if (target == null)
            {
                command.fail(new IllegalStateException(
                    runtimeId + ": no session " +
                        (command.sessionId < 0 ? "is active" : "with id " + command.sessionId)));
                return true;
            }
            // Session.trySend does not look at the session state: it encodes and writes whatever
            // it is given, logon or not, logging out or not. A message written while the session
            // is not ACTIVE is either never sent or sent into a dying connection, and the caller
            // would be told a position as if it had gone. Refuse it here instead.
            final SessionState state = target.state();
            if (state != SessionState.ACTIVE)
            {
                command.fail(new IllegalStateException(
                    runtimeId + ": session " + target.id() + " is " + state + ", not ACTIVE"));
                return true;
            }
            final long position;
            try
            {
                position = target.trySend(command.encoder);
            }
            catch (final RuntimeException e)
            {
                command.fail(e);
                return true;
            }
            if (Pressure.isBackPressured(position))
            {
                if (System.nanoTime() > command.deadlineNs)
                {
                    command.fail(new IllegalStateException(
                        runtimeId + ": send back-pressured for longer than " + config.replyTimeoutMs() + "ms"));
                    return true;
                }
                return false;
            }
            // Pressure.isBackPressured covers only BACK_PRESSURED (-2) and ADMIN_ACTION (-3), the two
            // that a retry can cure. Every other negative position - NOT_CONNECTED (-1), CLOSED (-4),
            // MAX_POSITION_EXCEEDED (-5) - means the message was not written and will not be.
            if (position < 0)
            {
                command.fail(new IllegalStateException(
                    runtimeId + ": session " + target.id() + " did not accept the message (position " +
                        position + ')'));
                return true;
            }
            command.future.complete(position);
            return true;
        }

        private Session findSession(final long sessionId)
        {
            for (final Session session : sessions)
            {
                if (session.id() == sessionId)
                {
                    return session;
                }
            }
            return null;
        }

        private int requestLogouts()
        {
            int work = 0;
            for (final Session session : sessions)
            {
                if (session.state() == SessionState.DISCONNECTED)
                {
                    continue;
                }
                if (logoutRequested.contains(session.id()))
                {
                    continue;
                }
                if (!Pressure.isBackPressured(session.logoutAndDisconnect()))
                {
                    logoutRequested.add(session.id());
                    work++;
                }
            }
            return work;
        }

        @Override
        public void onClose()
        {
            // Runs on this thread, which is the only one allowed to close the library. Stop the
            // queue first: from here on every command, queued or still being offered, is failed.
            final IllegalStateException shuttingDown = new IllegalStateException(runtimeId + " is shutting down");
            commands.stop(shuttingDown);
            if (pendingSend != null)
            {
                // Held outside the queue while it waited on back-pressure; it has a caller too.
                pendingSend.fail(shuttingDown);
                pendingSend = null;
            }
            CloseHelper.quietClose(library);
        }

        @Override
        public String roleName()
        {
            return runtimeId + "-library";
        }
    }

    // ------------------------------------------------------------------ session handler

    /**
     * Artio's per-session callback. Fills the shared {@link FixMessageView} and hands it to the
     * sink; the view is cleared again before returning so a sink that kept a reference sees an
     * empty view rather than someone else's message.
     */
    private final class RuntimeSessionHandler implements SessionHandler
    {
        private final Session session;
        private SessionKey key;
        private boolean keyIsFromSession;

        private RuntimeSessionHandler(final Session session)
        {
            this.session = session;
            refreshKey();
        }

        private void refreshKey()
        {
            key = sessionKeyOf(session);
            keyIsFromSession = session.compositeKey() != null;
        }

        private SessionKey key()
        {
            if (!keyIsFromSession)
            {
                refreshKey();
            }
            return key;
        }

        @Override
        public Action onMessage(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final int libraryId,
            final Session session,
            final int sequenceIndex,
            final long messageType,
            final long timestampInNs,
            final long position,
            final OnMessageInfo messageInfo)
        {
            final SessionKey sessionKey = key();
            try
            {
                // Artio's SessionParser has already run, so lastReceivedMsgSeqNum() is this
                // message's MsgSeqNum(34).
                view.wrap(
                    buffer, offset, length, messageType, session.lastReceivedMsgSeqNum(), sequenceIndex,
                    timestampInNs, libraryId, messageInfo.isValid(), sessionKey);
                sink.onMessage(view);
            }
            catch (final RuntimeException | Error e)
            {
                onAgentError(e);
            }
            finally
            {
                view.unwrap();
            }

            if (messageType == FixMessageView.LOGON)
            {
                notifyListener(() -> listener.onLogon(sessionKey));
            }
            else if (messageType == FixMessageView.LOGOUT)
            {
                notifyListener(() -> listener.onLogout(sessionKey));
            }
            return CONTINUE;
        }

        @Override
        public void onTimeout(final int libraryId, final Session session)
        {
            LOGGER.warn("{}: session {} timed out on this library", runtimeId, session.id());
            sessions.remove(session);
            notifyListener(() -> listener.onTimeout(key()));
        }

        @Override
        public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
        {
            LOGGER.warn("{}: session {} is {}a slow consumer", runtimeId, session.id(), hasBecomeSlow ? "" : "no longer ");
        }

        @Override
        public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
        {
            final SessionKey sessionKey = key();
            sessions.remove(session);
            LOGGER.info("{}: {} disconnected: {}", runtimeId, sessionKey, reason);
            notifyListener(() -> listener.onDisconnect(sessionKey, reason.name()));
            // Runs on the poll thread, so the reconnect state needs no synchronisation. A disconnect
            // this runtime asked for (close(), which sets shutdownRequested) schedules nothing.
            reconnector.onDisconnected();
            return CONTINUE;
        }

        @Override
        public void onSessionStart(final Session session)
        {
            LOGGER.debug("{}: session {} (re)started", runtimeId, session.id());
        }

    }

    // ------------------------------------------------------------------ reconnect

    /**
     * Re-initiates an {@link EngineMode#INITIATOR}'s session after a disconnect the runtime did not
     * ask for, with an exponential back-off between
     * {@link FixEngineConfig#reconnectInitialBackoffMs()} and
     * {@link FixEngineConfig#reconnectMaxBackoffMs()}.
     *
     * <p>Every field here belongs to the library poll thread and only to it:
     * {@link #onDisconnected()} is called from the session handler, which Artio invokes inside
     * {@code library.poll}, and {@link #doWork()} is another step of the same duty cycle. The
     * back-off is a deadline the poll loop looks at, not a timer thread - this class starts no
     * threads and takes no locks.
     */
    private final class Reconnector
    {
        /** True when a re-initiate is waiting for its back-off to elapse. */
        private boolean scheduled;
        /** Attempts since the last successful logon; 0 while the session is healthy. */
        private int attempt;
        /** The back-off used for the scheduled attempt; 0 means "start from the initial one". */
        private long backoffMs;
        private long dueAtNs;
        /** The in-flight {@code library.initiate(...)}, polled to completion by {@link #doWork()}. */
        private Reply<Session> reply;

        /** Called on the poll thread when a session went away. Schedules the first attempt. */
        void onDisconnected()
        {
            if (!reconnectPossible() || scheduled || reply != null)
            {
                return;
            }
            schedule();
            LOGGER.info("{}: session lost; reconnecting to {}:{} in {}ms",
                runtimeId, config.host(), config.port(), backoffMs);
        }

        int doWork()
        {
            if (attempt != 0 && isSessionActive())
            {
                // Logged on again: the next disconnect starts from the initial back-off rather
                // than from wherever this run of failures left off.
                attempt = 0;
                backoffMs = 0;
            }
            if (reply != null)
            {
                return pollReply();
            }
            if (!scheduled)
            {
                return 0;
            }
            if (!reconnectPossible())
            {
                scheduled = false;
                return 0;
            }
            if (System.nanoTime() < dueAtNs)
            {
                return 0;
            }

            scheduled = false;
            attempt++;
            final int thisAttempt = attempt;
            final long waitedMs = backoffMs;
            LOGGER.info("{}: reconnect attempt {} to {}:{} after {}ms",
                runtimeId, thisAttempt, config.host(), config.port(), waitedMs);
            notifyListener(() -> listener.onReconnectAttempt(thisAttempt, waitedMs));
            reply = library.initiate(sessionConfiguration());
            if (reply == null)
            {
                // The library could not even enqueue the request; back off and try again.
                schedule();
            }
            return 1;
        }

        private int pollReply()
        {
            if (reply.isExecuting())
            {
                return 0;
            }
            final Reply<Session> completed = reply;
            reply = null;
            if (completed.hasCompleted())
            {
                // TCP is up and the logon is on its way; the back-off is reset once the session
                // actually goes ACTIVE, so a counterparty that accepts connections and then
                // refuses every logon still gets backed off.
                LOGGER.info("{}: reconnect attempt {} connected to {}:{}, session {}",
                    runtimeId, attempt, config.host(), config.port(), completed.resultIfPresent().id());
            }
            else if (reconnectPossible())
            {
                schedule();
                LOGGER.warn("{}: reconnect attempt {} failed ({}); next attempt in {}ms",
                    runtimeId, attempt, completed.state(), backoffMs, completed.error());
            }
            return 1;
        }

        private void schedule()
        {
            backoffMs = backoffMs == 0 ?
                config.reconnectInitialBackoffMs() :
                Math.min(config.reconnectMaxBackoffMs(), backoffMs * 2);
            dueAtNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMs);
            scheduled = true;
        }

        private boolean reconnectPossible()
        {
            return config.mode() == EngineMode.INITIATOR && config.reconnectEnabled() &&
                !shutdownRequested && !closed.get();
        }
    }

    // ------------------------------------------------------------------ commands

    private static final class SendCommand extends CommandQueue.Command
    {
        private final long sessionId;
        private final Encoder encoder;
        private final long deadlineNs;
        private final CompletableFuture<Long> future = new CompletableFuture<>();

        private SendCommand(final long sessionId, final Encoder encoder, final long deadlineNs)
        {
            this.sessionId = sessionId;
            this.encoder = encoder;
            this.deadlineNs = deadlineNs;
        }

        @Override
        void fail(final Throwable error)
        {
            future.completeExceptionally(error);
        }
    }

    private static final class SubmitCommand extends CommandQueue.Command
    {
        private final Consumer<FixLibrary> action;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private SubmitCommand(final Consumer<FixLibrary> action)
        {
            this.action = action;
        }

        void run(final FixLibrary library)
        {
            try
            {
                action.accept(library);
                future.complete(null);
            }
            catch (final RuntimeException | Error e)
            {
                future.completeExceptionally(e);
            }
        }

        @Override
        void fail(final Throwable error)
        {
            future.completeExceptionally(error);
        }
    }
}
