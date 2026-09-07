package com.demo.artio.bridge;

import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.CompositeSink;
import com.demo.artio.engine.EngineMode;
import com.demo.artio.engine.FixEngineConfig;
import com.demo.artio.engine.FixVersion;
import com.demo.artio.engine.LoggingSink;
import org.agrona.concurrent.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The bridge as a program: an Artio engine and an AMPS publisher, configured from a properties
 * file, running until Ctrl-C.
 *
 * <pre>
 *   ./gradlew :artio-amps-bridge:run
 *   ./gradlew :artio-amps-bridge:run --args="--config /path/to/bridge.properties"
 *   ./gradlew :artio-amps-bridge:run -Dartio.port=9881 -Dbridge.amps.uri=tcp://host:9007/amps/fix
 * </pre>
 *
 * <p>Configuration is layered: the file first (default
 * {@code artio-amps-bridge/bridge.properties}, relative to the repository root), then any system
 * property beginning {@code artio.} or {@code bridge.}. The {@code run} task forwards those from the
 * Gradle JVM, as amps-demo's {@code bootRun} does, so a one-off override needs no editing.
 *
 * <p>This is the non-Spring entry point. {@code :artio-spring-boot} builds the same two objects from
 * {@code @ConfigurationProperties} and a {@code SmartLifecycle}; nothing else differs.
 */
public final class BridgeMain
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BridgeMain.class);

    /** Where the configuration lives when {@code --config} is not given. */
    public static final String DEFAULT_CONFIG_FILE = "artio-amps-bridge/bridge.properties";

    /** How often the counters are logged. */
    private static final Duration STATS_INTERVAL = Duration.ofSeconds(5);

    /**
     * Added to the derived shutdown wait for the parts of the clean-up that have no timeout of
     * their own: closing the Aeron archive and media driver, deleting their directories, closing
     * the AMPS socket, and the final stats line.
     */
    static final long SHUTDOWN_GRACE_MS = 2_000;

    private BridgeMain()
    {
    }

    /**
     * How long the shutdown hook holds the JVM open for the clean-up in {@link #run}.
     *
     * <p>Derived, not guessed, because a hook that gives up early is the silent-truncation bug in
     * {@link #awaitShutdown} all over again: the JVM halts the moment the hook returns, and any
     * message still in the ring buffer at that moment is gone. The worst case is the sum of every
     * bounded step, in the order they run:
     * <ol>
     *   <li>{@code ArtioRuntime.close()}: a graceful logout bounded by
     *       {@link FixEngineConfig#shutdownTimeoutMs()}, then stopping the poll agent, which
     *       {@link AgentRunner#close()} bounds at {@link AgentRunner#RETRY_CLOSE_TIMEOUT_MS} per
     *       attempt;</li>
     *   <li>{@code AmpsFixPublisher.close()}: {@link AmpsFixPublisher#closeBudgetMs(BridgeConfig)}
     *       - the agent wait, the drain and the flush at {@code flushTimeoutMs} each, plus stopping
     *       its agent;</li>
     *   <li>{@link #SHUTDOWN_GRACE_MS} for everything that has no bound of its own.</li>
     * </ol>
     * With the defaults that is 5 + 5 + 30 + 5 + 2 = 47 seconds; the old fixed
     * {@code flushTimeoutMs + 5 s} was 15.
     *
     * @param engineConfig the engine, for its logout timeout.
     * @param bridgeConfig the bridge, for its flush timeout.
     * @return the bound on the hook's wait, in milliseconds.
     */
    static long shutdownWaitMs(final FixEngineConfig engineConfig, final BridgeConfig bridgeConfig)
    {
        return engineConfig.shutdownTimeoutMs() +
            AgentRunner.RETRY_CLOSE_TIMEOUT_MS +
            AmpsFixPublisher.closeBudgetMs(bridgeConfig) +
            SHUTDOWN_GRACE_MS;
    }

    /**
     * @param args {@code --config <file>}, or nothing.
     * @throws Exception if the engine or the publisher could not be started.
     */
    public static void main(final String[] args) throws Exception
    {
        final Path configFile = configFile(args);
        final Properties properties = load(configFile);

        final BridgeConfig bridgeConfig = BridgeConfig.fromProperties(properties);
        final FixEngineConfig engineConfig = engineConfig(properties);

        LOGGER.info("artio: {} {} {}:{} {}->{} heartbeat {}s",
            engineConfig.mode(), engineConfig.fixVersion().beginString(),
            engineConfig.host(), engineConfig.port(),
            engineConfig.senderCompId(), engineConfig.targetCompId(),
            engineConfig.heartbeatIntervalSec());
        LOGGER.info("amps:  {} client {}{}", bridgeConfig.uri(), bridgeConfig.clientName(),
            bridgeConfig.guaranteedPublishing() ? " (guaranteed publishing)" : "");

        run(engineConfig, bridgeConfig);
    }

    /**
     * Starts the publisher, then the engine, and blocks until the JVM is asked to stop.
     *
     * @param engineConfig the Artio configuration.
     * @param bridgeConfig the AMPS configuration.
     * @throws Exception if the engine could not be started.
     */
    static void run(final FixEngineConfig engineConfig, final BridgeConfig bridgeConfig) throws Exception
    {
        // The publisher first: it must be able to accept messages before the engine can deliver any.
        final AmpsFixPublisher publisher = new AmpsFixPublisher(bridgeConfig);
        publisher.start();

        ArtioRuntime runtime = null;
        final ScheduledExecutorService stats = Executors.newSingleThreadScheduledExecutor(
            runnable ->
            {
                final Thread thread = new Thread(runnable, "bridge-stats");
                thread.setDaemon(true);
                return thread;
            });
        // Signalled by the shutdown hook; the hook then waits on `stopped` until the clean-up below
        // has finished. Without that second latch the JVM halts as soon as every hook returns, and
        // the drain-and-flush in the finally block is cut off half way - the messages still in the
        // ring buffer are lost, silently, which is exactly the failure the bridge exists to avoid.
        final CountDownLatch stopRequested = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        try
        {
            // LoggingSink renders SOH as '|', so the console shows readable FIX; the publisher gets
            // the same flyweight and the same bytes.
            runtime = ArtioRuntime.launch(
                engineConfig,
                CompositeSink.of(new LoggingSink("recv", true), publisher),
                null);

            stats.scheduleAtFixedRate(
                () -> LOGGER.info("stats: {}", publisher.stats().summary()),
                STATS_INTERVAL.toMillis(), STATS_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

            LOGGER.info("bridge running; Ctrl-C to stop");
            awaitShutdown(stopRequested, stopped, shutdownWaitMs(engineConfig, bridgeConfig));
        }
        finally
        {
            stats.shutdownNow();
            // Order: the engine first, so nothing else arrives, then the publisher, which drains
            // what is already in the ring buffer and flushes AMPS. The reverse would count the tail
            // of the session as dropped.
            if (runtime != null)
            {
                runtime.close();
            }
            publisher.close();
            LOGGER.info("final stats: {}", publisher.stats().summary());
            // Releases the shutdown hook, which lets the JVM finish exiting.
            stopped.countDown();
        }
    }

    /**
     * Blocks until SIGTERM or Ctrl-C, and arranges for the JVM to wait while the caller cleans up.
     *
     * <p>A shutdown hook that merely wakes the main thread is not enough: the JVM halts as soon as
     * every hook has <em>returned</em>, and does not wait for a non-daemon thread that a hook
     * happened to release. So the hook signals, and then blocks on {@code stopped} until the caller
     * says it has finished - bounded, because a hook that never returns hangs the process.
     *
     * @param stopRequested counted down by the hook.
     * @param stopped       counted down by the caller when clean-up is complete.
     * @param cleanupWaitMs how long the hook waits for that, before giving up and letting the JVM
     *                      exit anyway; see {@link #shutdownWaitMs}.
     * @throws InterruptedException if the wait is interrupted.
     */
    private static void awaitShutdown(
        final CountDownLatch stopRequested, final CountDownLatch stopped, final long cleanupWaitMs)
        throws InterruptedException
    {
        Runtime.getRuntime().addShutdownHook(new Thread(
            () ->
            {
                stopRequested.countDown();
                try
                {
                    if (!stopped.await(cleanupWaitMs, TimeUnit.MILLISECONDS))
                    {
                        LOGGER.warn("shutdown did not finish within {} ms; exiting anyway", cleanupWaitMs);
                    }
                }
                catch (final InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            },
            "bridge-shutdown"));
        stopRequested.await();
        LOGGER.info("shutdown requested");
    }

    // ------------------------------------------------------------------------------ configuration

    /**
     * @param args the command line.
     * @return the properties file to read.
     */
    static Path configFile(final String[] args)
    {
        for (int i = 0; i < args.length; i++)
        {
            if ("--config".equals(args[i]) || "-c".equals(args[i]))
            {
                if (i + 1 >= args.length)
                {
                    throw new IllegalArgumentException("--config needs a file path");
                }
                return Path.of(args[i + 1]);
            }
            if ("--help".equals(args[i]) || "-h".equals(args[i]))
            {
                System.out.println("usage: BridgeMain [--config <bridge.properties>]");
                System.out.println("       system properties -Dartio.* and -Dbridge.* override the file");
                System.exit(0);
            }
        }
        return Path.of(DEFAULT_CONFIG_FILE);
    }

    /**
     * Reads the file if it exists, then lets {@code -Dartio.*} and {@code -Dbridge.*} override it.
     *
     * @param file the properties file; a missing file is not an error, the defaults stand.
     * @return the merged properties.
     */
    static Properties load(final Path file)
    {
        final Properties properties = new Properties();
        if (Files.isReadable(file))
        {
            try (InputStream in = Files.newInputStream(file))
            {
                properties.load(in);
                LOGGER.info("configuration from {}", file.toAbsolutePath());
            }
            catch (final IOException e)
            {
                throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
            }
        }
        else
        {
            LOGGER.warn("{} not found; using built-in defaults", file.toAbsolutePath());
        }
        overrideFromSystemProperties(properties);
        return properties;
    }

    /**
     * @param properties the properties to override in place.
     */
    static void overrideFromSystemProperties(final Properties properties)
    {
        for (final String key : System.getProperties().stringPropertyNames())
        {
            if (key.startsWith("artio.") || key.startsWith(BridgeConfig.PREFIX))
            {
                final String value = System.getProperty(key);
                if (value != null)
                {
                    properties.setProperty(key, value);
                }
            }
        }
    }

    /**
     * Binds the Artio half of the configuration.
     *
     * <table>
     *   <caption>Keys</caption>
     *   <tr><th>Key</th><th>Default</th></tr>
     *   <tr><td>{@code artio.mode}</td><td>{@code acceptor} or {@code initiator}</td></tr>
     *   <tr><td>{@code artio.name}</td><td>{@code bridge}</td></tr>
     *   <tr><td>{@code artio.host}</td><td>{@code 0.0.0.0}</td></tr>
     *   <tr><td>{@code artio.port}</td><td>9880</td></tr>
     *   <tr><td>{@code artio.senderCompId}</td><td>{@code ARTIO}</td></tr>
     *   <tr><td>{@code artio.targetCompId}</td><td>{@code QFJ}</td></tr>
     *   <tr><td>{@code artio.fixVersion}</td><td>{@code FIX.4.2} or {@code FIX.4.4}</td></tr>
     *   <tr><td>{@code artio.heartbeatIntervalSec}</td><td>30</td></tr>
     * </table>
     *
     * @param properties the source.
     * @return the engine configuration.
     */
    static FixEngineConfig engineConfig(final Properties properties)
    {
        final EngineMode mode = switch (properties.getProperty("artio.mode", "acceptor")
            .trim().toLowerCase(Locale.ROOT))
        {
            case "acceptor" -> EngineMode.ACCEPTOR;
            case "initiator" -> EngineMode.INITIATOR;
            default -> throw new IllegalArgumentException(
                "artio.mode must be acceptor or initiator: " + properties.getProperty("artio.mode"));
        };

        return FixEngineConfig.builder(mode)
            .name(properties.getProperty("artio.name", "bridge").trim())
            .address(
                properties.getProperty("artio.host", "0.0.0.0").trim(),
                Integer.parseInt(properties.getProperty("artio.port", "9880").trim()))
            .senderCompId(properties.getProperty("artio.senderCompId", "ARTIO").trim())
            .targetCompId(properties.getProperty("artio.targetCompId", "QFJ").trim())
            .fixVersion(fixVersion(properties.getProperty("artio.fixVersion", "FIX.4.2").trim()))
            .heartbeatIntervalSec(
                Integer.parseInt(properties.getProperty("artio.heartbeatIntervalSec", "30").trim()))
            .build();
    }

    /**
     * @param value {@code FIX.4.2}, {@code FIX42} or {@code 4.2}, in any case.
     * @return the version.
     */
    static FixVersion fixVersion(final String value)
    {
        final String normalised = value.toUpperCase(Locale.ROOT).replace(".", "").replace("FIX", "");
        return switch (normalised)
        {
            case "42" -> FixVersion.FIX42;
            case "44" -> FixVersion.FIX44;
            default -> throw new IllegalArgumentException(
                "artio.fixVersion must be FIX.4.2 or FIX.4.4: " + value);
        };
    }
}
