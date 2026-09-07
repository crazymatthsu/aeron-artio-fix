package com.demo.artio.engine;

import uk.co.real_logic.artio.validation.AuthenticationStrategy;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Everything {@link ArtioRuntime} needs to start. Immutable, validated on construction, built
 * through {@link #builder(EngineMode)}.
 *
 * <p>Directories: {@link #aeronDirectory()} and {@link #logFileDir()} may be {@code null}, in which
 * case {@link ArtioRuntime} derives them from {@link #baseDirectory()} with a per-runtime unique
 * suffix (name, mode, process id and an in-process counter) so two runtimes never collide, whether
 * they share a JVM or only a machine. That is the normal case; set them explicitly only when
 * something outside the process needs to find them. An explicit directory is never wiped on start:
 * the runtime fails fast if another media driver is live in an explicit {@code aeronDirectory},
 * and reuses whatever an explicit {@code logFileDir} already holds. Both are removed on
 * {@code close()} only when {@link #deleteDirectoriesOnClose()} is true.
 *
 * @param name              a short label; used for thread names, Aeron/Artio directory names and logs.
 *                          Must not contain a path separator.
 * @param mode              acceptor (bind) or initiator (connect out).
 * @param host              the address to bind to (acceptor) or connect to (initiator).
 * @param port              the port to bind to (acceptor) or connect to (initiator).
 * @param senderCompId      this engine's CompID: {@code SenderCompID(49)} on messages it sends.
 * @param targetCompId      the counterparty's CompID: {@code TargetCompID(56)} on messages it sends.
 * @param fixVersion        the FIX version, which selects the generated dictionary.
 * @param heartbeatIntervalSec  {@code HeartBtInt(108)} proposed at logon.
 * @param baseDirectory     parent of the derived Aeron and Artio directories.
 * @param aeronDirectory    explicit Aeron directory, or null to derive one under {@code baseDirectory}.
 * @param logFileDir        explicit Artio log directory, or null to derive one under {@code baseDirectory}.
 * @param resetSeqNumsOnLogon   send {@code ResetSeqNumFlag(141)=Y} and restart from 1 (initiator only).
 * @param libraryId         a fixed {@code FixLibrary} id, or {@link #AUTO_LIBRARY_ID} to let Artio pick.
 * @param idleStrategy      how the library poll thread idles.
 * @param logonTimeoutMs    how long {@code start()} waits for the session to become active (initiator).
 * @param replyTimeoutMs    Artio's {@code replyTimeoutInMs}: how long an engine/library round trip may take.
 * @param shutdownTimeoutMs how long {@code close()} waits for a graceful FIX logout before pulling the plug.
 * @param reconnectEnabled  initiator only: re-initiate the session after a disconnect that the
 *                          runtime did not ask for, with exponential back-off.
 * @param reconnectInitialBackoffMs  initiator only: the delay before the first reconnect attempt;
 *                          doubled after every failed attempt.
 * @param reconnectMaxBackoffMs      initiator only: the ceiling on the reconnect delay.
 * @param deleteDirectoriesOnClose  delete the Aeron and Artio directories on {@code close()}.
 * @param authenticationStrategy    the acceptor's logon hook. Artio's default accepts everyone.
 */
public record FixEngineConfig(
    String name,
    EngineMode mode,
    String host,
    int port,
    String senderCompId,
    String targetCompId,
    FixVersion fixVersion,
    int heartbeatIntervalSec,
    Path baseDirectory,
    Path aeronDirectory,
    Path logFileDir,
    boolean resetSeqNumsOnLogon,
    int libraryId,
    IdleStrategyType idleStrategy,
    long logonTimeoutMs,
    long replyTimeoutMs,
    long shutdownTimeoutMs,
    boolean reconnectEnabled,
    long reconnectInitialBackoffMs,
    long reconnectMaxBackoffMs,
    boolean deleteDirectoriesOnClose,
    AuthenticationStrategy authenticationStrategy)
{
    /** Passed as {@link #libraryId()} to let Artio assign a random library id, which is the norm. */
    public static final int AUTO_LIBRARY_ID = 0;

    /** Default for {@link #reconnectInitialBackoffMs()}: one second. */
    public static final long DEFAULT_RECONNECT_INITIAL_BACKOFF_MS = 1_000;

    /** Default for {@link #reconnectMaxBackoffMs()}: thirty seconds. */
    public static final long DEFAULT_RECONNECT_MAX_BACKOFF_MS = 30_000;

    /** Start of Header, the FIX field separator. Rejected inside CompIDs. */
    private static final char SOH = '\001';

    /**
     * Validates every component. A configuration that reaches {@link ArtioRuntime} has already been
     * checked here, so start-up failures are Artio's, not the caller's.
     */
    public FixEngineConfig
    {
        requireText(name, "name");
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
        {
            throw new IllegalArgumentException(
                "name is used as a directory name and must not contain '/' or '\\'; was '" + name + "'");
        }
        Objects.requireNonNull(mode, "mode");
        requireText(host, "host");
        if (port < 1 || port > 65535)
        {
            throw new IllegalArgumentException("port must be in 1..65535 but was " + port);
        }
        requireCompId(senderCompId, "senderCompId");
        requireCompId(targetCompId, "targetCompId");
        if (senderCompId.equals(targetCompId))
        {
            throw new IllegalArgumentException(
                "senderCompId and targetCompId must differ, both were '" + senderCompId + "'");
        }
        Objects.requireNonNull(fixVersion, "fixVersion");
        if (heartbeatIntervalSec < 1)
        {
            throw new IllegalArgumentException(
                "heartbeatIntervalSec must be at least 1 but was " + heartbeatIntervalSec);
        }
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        if (libraryId != AUTO_LIBRARY_ID && libraryId < 1)
        {
            throw new IllegalArgumentException(
                "libraryId must be AUTO_LIBRARY_ID or positive but was " + libraryId);
        }
        Objects.requireNonNull(idleStrategy, "idleStrategy");
        requirePositive(logonTimeoutMs, "logonTimeoutMs");
        requirePositive(replyTimeoutMs, "replyTimeoutMs");
        requirePositive(shutdownTimeoutMs, "shutdownTimeoutMs");
        requirePositive(reconnectInitialBackoffMs, "reconnectInitialBackoffMs");
        if (reconnectMaxBackoffMs < reconnectInitialBackoffMs)
        {
            throw new IllegalArgumentException(
                "reconnectMaxBackoffMs must be at least reconnectInitialBackoffMs (" +
                    reconnectInitialBackoffMs + ") but was " + reconnectMaxBackoffMs);
        }
        Objects.requireNonNull(authenticationStrategy, "authenticationStrategy");
    }

    private static void requireText(final String value, final String field)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireCompId(final String value, final String field)
    {
        requireText(value, field);
        if (value.indexOf(SOH) >= 0 || value.indexOf('=') >= 0)
        {
            throw new IllegalArgumentException(field + " must not contain SOH or '='; was '" + value + "'");
        }
    }

    private static void requirePositive(final long value, final String field)
    {
        if (value < 1)
        {
            throw new IllegalArgumentException(field + " must be positive but was " + value);
        }
    }

    /** @return a builder for a {@link EngineMode#ACCEPTOR}, defaulting the bind host to localhost. */
    public static Builder acceptor()
    {
        return builder(EngineMode.ACCEPTOR);
    }

    /** @return a builder for an {@link EngineMode#INITIATOR}, defaulting the remote host to localhost. */
    public static Builder initiator()
    {
        return builder(EngineMode.INITIATOR);
    }

    /**
     * @param mode acceptor or initiator.
     * @return a builder pre-loaded with the defaults documented on each setter.
     */
    public static Builder builder(final EngineMode mode)
    {
        return new Builder(mode);
    }

    /**
     * Mutable builder for {@link FixEngineConfig}. Not thread safe; build once at start-up.
     */
    public static final class Builder
    {
        private String name = "artio";
        private final EngineMode mode;
        private String host = "localhost";
        private int port;
        private String senderCompId = "ARTIO";
        private String targetCompId = "COUNTERPARTY";
        private FixVersion fixVersion = FixVersion.FIX42;
        private int heartbeatIntervalSec = 10;
        private Path baseDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        private Path aeronDirectory;
        private Path logFileDir;
        private boolean resetSeqNumsOnLogon = true;
        private int libraryId = AUTO_LIBRARY_ID;
        private IdleStrategyType idleStrategy = IdleStrategyType.BACKOFF;
        private long logonTimeoutMs = 20_000;
        private long replyTimeoutMs = 10_000;
        private long shutdownTimeoutMs = 5_000;
        private boolean reconnectEnabled = true;
        private long reconnectInitialBackoffMs = DEFAULT_RECONNECT_INITIAL_BACKOFF_MS;
        private long reconnectMaxBackoffMs = DEFAULT_RECONNECT_MAX_BACKOFF_MS;
        private boolean deleteDirectoriesOnClose = true;
        private AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.none();

        private Builder(final EngineMode mode)
        {
            this.mode = Objects.requireNonNull(mode, "mode");
        }

        /**
         * Short label used for thread names, directories and log lines. Default {@code artio}.
         * Must not contain {@code /} or {@code \}.
         */
        public Builder name(final String name)
        {
            this.name = name;
            return this;
        }

        /** Bind address (acceptor) or remote address (initiator). Default {@code localhost}. */
        public Builder host(final String host)
        {
            this.host = host;
            return this;
        }

        /** Bind port (acceptor) or remote port (initiator). Required. */
        public Builder port(final int port)
        {
            this.port = port;
            return this;
        }

        /** Convenience for {@link #host(String)} plus {@link #port(int)}. */
        public Builder address(final String host, final int port)
        {
            return host(host).port(port);
        }

        /** This engine's CompID. Default {@code ARTIO}. */
        public Builder senderCompId(final String senderCompId)
        {
            this.senderCompId = senderCompId;
            return this;
        }

        /** The counterparty's CompID. Default {@code COUNTERPARTY}. */
        public Builder targetCompId(final String targetCompId)
        {
            this.targetCompId = targetCompId;
            return this;
        }

        /** FIX version, which selects the generated dictionary. Default {@link FixVersion#FIX42}. */
        public Builder fixVersion(final FixVersion fixVersion)
        {
            this.fixVersion = fixVersion;
            return this;
        }

        /** {@code HeartBtInt(108)} in seconds. Default 10. */
        public Builder heartbeatIntervalSec(final int heartbeatIntervalSec)
        {
            this.heartbeatIntervalSec = heartbeatIntervalSec;
            return this;
        }

        /** Parent for the derived Aeron/Artio directories. Default {@code java.io.tmpdir}. */
        public Builder baseDirectory(final Path baseDirectory)
        {
            this.baseDirectory = baseDirectory;
            return this;
        }

        /**
         * Explicit Aeron directory. Default: derived under {@link #baseDirectory(Path)}. An explicit
         * directory is not wiped on start; if another media driver is live in it, {@code start()}
         * fails.
         */
        public Builder aeronDirectory(final Path aeronDirectory)
        {
            this.aeronDirectory = aeronDirectory;
            return this;
        }

        /**
         * Explicit Artio log directory. Default: derived under {@link #baseDirectory(Path)}. An
         * explicit directory is not wiped on start: Artio reuses the message log and sequence
         * numbers it finds there.
         */
        public Builder logFileDir(final Path logFileDir)
        {
            this.logFileDir = logFileDir;
            return this;
        }

        /** Reset sequence numbers at logon (initiator only). Default true. */
        public Builder resetSeqNumsOnLogon(final boolean resetSeqNumsOnLogon)
        {
            this.resetSeqNumsOnLogon = resetSeqNumsOnLogon;
            return this;
        }

        /** Fixed library id, or {@link #AUTO_LIBRARY_ID} (the default) to let Artio choose. */
        public Builder libraryId(final int libraryId)
        {
            this.libraryId = libraryId;
            return this;
        }

        /** Poll thread idle strategy. Default {@link IdleStrategyType#BACKOFF}. */
        public Builder idleStrategy(final IdleStrategyType idleStrategy)
        {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /** How long {@code start()} waits for logon. Default 20s. */
        public Builder logonTimeoutMs(final long logonTimeoutMs)
        {
            this.logonTimeoutMs = logonTimeoutMs;
            return this;
        }

        /** Artio's engine/library reply timeout. Default 10s. */
        public Builder replyTimeoutMs(final long replyTimeoutMs)
        {
            this.replyTimeoutMs = replyTimeoutMs;
            return this;
        }

        /** How long {@code close()} waits for a graceful logout. Default 5s. */
        public Builder shutdownTimeoutMs(final long shutdownTimeoutMs)
        {
            this.shutdownTimeoutMs = shutdownTimeoutMs;
            return this;
        }

        /**
         * Initiator only: re-initiate the session after an unrequested disconnect. Default true.
         * When false the runtime stays up with no session; {@code ArtioRuntime.isSessionActive()}
         * reports it and every {@code send} fails.
         */
        public Builder reconnectEnabled(final boolean reconnectEnabled)
        {
            this.reconnectEnabled = reconnectEnabled;
            return this;
        }

        /** Initiator only: delay before the first reconnect attempt, doubled per failure. Default 1s. */
        public Builder reconnectInitialBackoffMs(final long reconnectInitialBackoffMs)
        {
            this.reconnectInitialBackoffMs = reconnectInitialBackoffMs;
            return this;
        }

        /** Initiator only: the ceiling on the reconnect delay. Default 30s. */
        public Builder reconnectMaxBackoffMs(final long reconnectMaxBackoffMs)
        {
            this.reconnectMaxBackoffMs = reconnectMaxBackoffMs;
            return this;
        }

        /**
         * Delete the Aeron and Artio directories on close, explicit ones included. Default true.
         * Set to false when an explicit {@link #logFileDir(Path)} must persist sequence numbers
         * across restarts.
         */
        public Builder deleteDirectoriesOnClose(final boolean deleteDirectoriesOnClose)
        {
            this.deleteDirectoriesOnClose = deleteDirectoriesOnClose;
            return this;
        }

        /**
         * The acceptor's logon hook. Artio's default ({@code AuthenticationStrategy.none()})
         * accepts every logon; replace it to check credentials or CompIDs.
         */
        public Builder authenticationStrategy(final AuthenticationStrategy authenticationStrategy)
        {
            this.authenticationStrategy = authenticationStrategy;
            return this;
        }

        /**
         * @return the validated configuration.
         * @throws IllegalArgumentException if any component is missing or out of range.
         */
        public FixEngineConfig build()
        {
            return new FixEngineConfig(
                name, mode, host, port, senderCompId, targetCompId, fixVersion, heartbeatIntervalSec,
                baseDirectory, aeronDirectory, logFileDir, resetSeqNumsOnLogon, libraryId, idleStrategy,
                logonTimeoutMs, replyTimeoutMs, shutdownTimeoutMs,
                reconnectEnabled, reconnectInitialBackoffMs, reconnectMaxBackoffMs,
                deleteDirectoriesOnClose, authenticationStrategy);
        }
    }
}
