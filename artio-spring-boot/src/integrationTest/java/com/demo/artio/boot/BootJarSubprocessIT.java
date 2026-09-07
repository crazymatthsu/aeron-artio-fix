package com.demo.artio.boot;

import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.qfj.OrderScenario;
import com.demo.artio.qfj.QfjInitiator;
import com.demo.artio.testharness.AmpsAssumptions;
import com.demo.artio.testharness.AmpsComposeServer;
import com.demo.artio.testharness.SowReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The <strong>fat jar</strong>, in its own JVM, killed with SIGTERM.
 *
 * <p>"Can this run as a Spring Boot application" is not fully answered by a context that refreshes
 * inside a test JVM. The deployable artefact is {@code bootJar}'s executable jar, which loads every
 * class - Artio's generated codecs, Aeron's, Agrona's - through Spring Boot's nested-jar
 * {@code LaunchedClassLoader} rather than the system one, and which in production is stopped by a
 * signal rather than by {@code context.close()}. Both of those are places where an application that
 * works in a test can fail in a container, so both are exercised here:
 *
 * <ul>
 *   <li>the jar is launched with {@code java -jar} and the three module flags Artio and Aeron need
 *       - the fat jar has nowhere else to get them from;</li>
 *   <li>the counterparty runs the same five-message scenario;</li>
 *   <li>the process is sent SIGTERM ({@link Process#destroy()}), which is what {@code docker stop},
 *       Kubernetes and Ctrl-C all send, and the assertions are on what the dying process managed to
 *       flush and on the order its log says it did it in.</li>
 * </ul>
 *
 * <p>The exit code is recorded rather than asserted equal to 0: a JVM killed by SIGTERM exits 143
 * (128 + 15) even when every shutdown hook completed, and pretending otherwise would either fail a
 * healthy run or hide a real crash. What is asserted is that it exited promptly and that the two
 * shutdown lines appear, in order, before it did.
 *
 * <p><strong>The output goes to a file, not a pipe.</strong> The obvious shape - a daemon thread
 * draining {@code process.getInputStream()} - loses the shutdown lines: they are written from a
 * shutdown hook microseconds before the JVM exits, and the reader thread reaches end-of-stream as
 * the process is reaped without them. That failure looks exactly like "the bridge does not flush on
 * SIGTERM", which is the most alarming way possible to be told about a test harness bug. Redirecting
 * to a file removes the race: the kernel has the bytes whether or not anything is reading.
 */
class BootJarSubprocessIT
{
    /** The line ArtioRuntimeLifecycle logs once the acceptor's port is bound. */
    private static final String STARTED_LINE = "artio runtime started";

    /** The line ArtioRuntimeLifecycle logs when the engine is down; must come first. */
    private static final String RUNTIME_CLOSED_LINE = "artio runtime closed";

    /** The line BridgePublisherLifecycle logs after the drain and the AMPS flush; must come second. */
    private static final String PUBLISHER_CLOSED_LINE = "amps publisher drained and flushed";

    private static AmpsComposeServer amps;

    @BeforeAll
    static void startAmps() throws Exception
    {
        AmpsAssumptions.assumeAvailable();
        amps = AmpsComposeServer.start("artio-fix");
    }

    @AfterAll
    static void stopAmps()
    {
        if (amps != null)
        {
            amps.close();
        }
    }

    @Test
    void theFatJarRunsTheSameFlowAndFlushesEverythingWhenItIsSentSigterm(@TempDir final Path dir)
        throws Exception
    {
        final Path jar = bootJar();
        final Path logFile = dir.resolve("boot-jar.log");
        final OrderScenario scenario = OrderScenario.DEFAULT;
        final int port = SpringItSupport.freePort();
        final long rawBefore = SowReader.countFromEpoch(
            amps.uri(), BridgeConfig.TOPIC_RAW, Duration.ofSeconds(2));

        final long startedAtNs = System.nanoTime();
        final Process process = launch(jar, port, logFile);
        final List<String> clOrdIds;
        final int exitCode;
        try
        {
            await("the fat jar's acceptor to bind")
                .atMost(SpringItSupport.STARTUP_TIMEOUT)
                .until(() -> contains(logFile, STARTED_LINE) || !process.isAlive());
            assertTrue(process.isAlive(),
                () -> "the fat jar died during start-up:\n" + read(logFile));
            final Duration startup = Duration.ofNanos(System.nanoTime() - startedAtNs);

            try (QfjInitiator qfj = new QfjInitiator(SpringItSupport.initiatorConfig(port)))
            {
                qfj.start();
                assertTrue(qfj.awaitLogon(SpringItSupport.STARTUP_TIMEOUT),
                    () -> "QuickFIX/J did not log on to the fat jar's acceptor:\n" + read(logFile));
                clOrdIds = qfj.run(scenario);
            }

            // SIGTERM. Nothing after this asks the process to finish anything: what lands in AMPS is
            // whatever the shutdown hook managed on its own, which is the property being tested - a
            // bridge that has to be asked nicely is not a bridge you can deploy.
            process.destroy();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS),
                () -> "the fat jar did not exit within 30s of SIGTERM:\n" + read(logFile));
            exitCode = process.exitValue();

            System.out.printf(
                "fat jar: start-up to bound acceptor %d ms, SIGTERM exit code %d%n",
                startup.toMillis(), exitCode);
        }
        finally
        {
            process.destroyForcibly();
        }

        final List<String> output = read(logFile);
        final String log = String.join("\n", output);
        System.out.println("---- fat jar log ----\n" + log + "\n---- end ----");

        final int runtimeClosed = indexOfLine(output, RUNTIME_CLOSED_LINE);
        final int publisherClosed = indexOfLine(output, PUBLISHER_CLOSED_LINE);
        assertAll(
            () -> assertTrue(runtimeClosed >= 0,
                () -> "the engine never reported closing:\n" + log),
            () -> assertTrue(publisherClosed >= 0,
                () -> "the publisher never reported flushing, so SIGTERM cut the shutdown short:\n" + log),
            // The whole reason for the two SmartLifecycle phases, observed from outside the JVM.
            () -> assertTrue(runtimeClosed < publisherClosed,
                () -> "the engine must close before the publisher flushes:\n" + log),
            // 143 = 128 + SIGTERM. Recorded in docs/04; asserted only as "not a crash code from
            // somewhere else".
            () -> assertTrue(exitCode == 143 || exitCode == 0,
                () -> "unexpected exit code " + exitCode + ":\n" + log));

        SpringItSupport.assertOrderScenarioLanded(amps.uri(), scenario, clOrdIds, rawBefore);
    }

    /**
     * @param jar     the executable jar.
     * @param port    the acceptor port to bind.
     * @param logFile where the merged stdout and stderr are written.
     * @return the running process.
     * @throws IOException if it could not be started.
     */
    private static Process launch(final Path jar, final int port, final Path logFile) throws IOException
    {
        final List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            // Not optional, and not something the jar can supply for itself: agrona's UnsafeApi
            // needs the first two, Artio's ReceiverEndPoints static initialiser needs the third.
            // In a container these would be JAVA_TOOL_OPTIONS; see docs/04.
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "-jar", jar.toString(),
            "--artio.port=" + port,
            "--artio.base-directory=" + SpringItSupport.baseDirectory(),
            "--bridge.uri=" + amps.uri(),
            "--bridge.client-name=artio-spring-bootjar-it",
            "--bridge.flush-timeout-ms=5000",
            "--bridge.stats-log-interval-ms=0"));

        return new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start();
    }

    /** @return the executable jar Gradle built, whose path arrives as {@code -Dboot.jar}. */
    private static Path bootJar()
    {
        final String configured = System.getProperty("boot.jar", "");
        if (configured.isBlank())
        {
            return fail("-Dboot.jar was not set; see artio-spring-boot/build.gradle.kts");
        }
        final Path jar = Path.of(configured);
        assertTrue(Files.isReadable(jar),
            () -> jar + " does not exist; integrationTest must depend on bootJar");
        return jar;
    }

    /** @return the log file's lines so far; empty until the process writes its first one. */
    private static List<String> read(final Path logFile)
    {
        try
        {
            return Files.exists(logFile) ?
                Files.readAllLines(logFile, StandardCharsets.UTF_8) : List.of();
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }

    private static boolean contains(final Path logFile, final String fragment)
    {
        return indexOfLine(read(logFile), fragment) >= 0;
    }

    private static int indexOfLine(final List<String> output, final String fragment)
    {
        for (int i = 0; i < output.size(); i++)
        {
            if (output.get(i).contains(fragment))
            {
                return i;
            }
        }
        return -1;
    }
}
