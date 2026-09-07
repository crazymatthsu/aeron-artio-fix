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
import org.agrona.concurrent.ManyToOneConcurrentLinkedQueue;
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
 * <h2>Directories</h2>
 * Each runtime gets its own Aeron directory, archive directory and Artio log directory, all under
 * {@link FixEngineConfig#baseDirectory()} with a unique suffix, so several runtimes can share a
 * JVM. {@link #close()} removes them unless
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

    private final FixMessageView view = new FixMessageView();
    private final ManyToOneConcurrentLinkedQueue<Command> commands = new ManyToOneConcurrentLinkedQueue<>();
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
        this.runtimeId = config.name() + '-' + config.mode().name().toLowerCase() + '-' +
            INSTANCE_COUNTER.incrementAndGet();
        this.runtimeDirectory = config.baseDirectory().resolve("artio-" + runtimeId);
        this.aeronDirectory = config.aeronDirectory() != null ?
            config.aeronDirectory() : runtimeDirectory.resolve("aeron");
        this.archiveDirectory = runtimeDirectory.resolve("archive");
        this.logFileDirectory = config.logFileDir() != null ?
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
            AgentRunner.startOnThread(agentRunner);

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
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(config.deleteDirectoriesOnClose())
            .errorHandler(this::onAgentError);

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

    private EngineConfiguration engineConfiguration()
    {
        final EngineConfiguration configuration = new EngineConfiguration()
            .libraryAeronChannel(IPC_CHANNEL)
            .logFileDir(logFileDirectory.toString())
            .deleteLogFileDirOnStart(true);

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

    private void initiateSession(final IdleStrategy idleStrategy)
    {
        final SessionConfiguration sessionConfiguration = SessionConfiguration.builder()
            .address(config.host(), config.port())
            .senderCompId(config.senderCompId())
            .targetCompId(config.targetCompId())
            .fixDictionary(config.fixVersion().dictionary())
            .resetSeqNum(config.resetSeqNumsOnLogon())
            .timeoutInMs(config.logonTimeoutMs())
            .build();

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
        while (!session.isActive())
        {
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

    private void enqueue(final Command command)
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

    /** @return the directory holding this runtime's Aeron, archive and Artio log directories. */
    public Path directory()
    {
        return runtimeDirectory;
    }

    /** @return true between a successful {@link #start()} and {@link #close()}. */
    public boolean isRunning()
    {
        return started.get() && !closed.get();
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
     */
    @Override
    public void close()
    {
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
        final File directory = runtimeDirectory.toFile();
        if (directory.exists())
        {
            IoUtil.delete(directory, true);
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

            Command command;
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
            // Runs on this thread, which is the only one allowed to close the library.
            CloseHelper.quietClose(library);
            Command command;
            while ((command = commands.poll()) != null)
            {
                command.fail(new IllegalStateException(runtimeId + " is shutting down"));
            }
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
            return CONTINUE;
        }

        @Override
        public void onSessionStart(final Session session)
        {
            LOGGER.debug("{}: session {} (re)started", runtimeId, session.id());
        }

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
    }

    // ------------------------------------------------------------------ commands

    private abstract static class Command
    {
        abstract void fail(Throwable error);
    }

    private static final class SendCommand extends Command
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

    private static final class SubmitCommand extends Command
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
