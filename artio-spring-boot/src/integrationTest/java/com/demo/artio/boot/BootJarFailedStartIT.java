package com.demo.artio.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The fat jar, when the engine cannot start: the process must <strong>exit</strong>, non-zero,
 * with the publisher closed - not hang.
 *
 * <p>The README promises that an initiator which cannot log on "fails the context refresh", and
 * calls that the right answer. It is only the right answer if the JVM then goes away. The publisher
 * is started first and its agent thread is not a daemon, so if nothing closed it the process would
 * sit there forever, printing a stack trace once and serving no FIX session - which an orchestrator
 * sees as a healthy running container. This test points the fat jar at a port nothing listens on
 * and watches it die properly.
 *
 * <p>Needs no AMPS and no container: the publisher is pointed at a second dead port, which is
 * deliberately not a start-up failure. It does need the executable jar, which is why it lives in
 * the integration suite.
 */
class BootJarFailedStartIT
{
    /** The line ArtioRuntimeLifecycle logs once an initiator's logon completed; must NOT appear. */
    private static final String STARTED_LINE = "artio runtime started";

    /** The line BridgePublisherLifecycle logs after the drain and the AMPS flush; must appear. */
    private static final String PUBLISHER_CLOSED_LINE = "amps publisher drained and flushed";

    @Test
    void anInitiatorThatCannotLogOnExitsNonZeroWithThePublisherClosed(@TempDir final Path dir)
        throws Exception
    {
        final Path jar = bootJar();
        final Path logFile = dir.resolve("boot-jar-failed-start.log");
        final int deadFixPort = SpringItSupport.freePort();
        final int deadAmpsPort = SpringItSupport.freePort();

        final Process process = new ProcessBuilder(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "-jar", jar.toString(),
            "--artio.mode=initiator",
            "--artio.host=127.0.0.1",
            "--artio.port=" + deadFixPort,
            "--artio.logon-timeout-ms=3000",
            "--artio.base-directory=" + SpringItSupport.baseDirectory(),
            "--bridge.uri=tcp://127.0.0.1:" + deadAmpsPort + "/amps/fix",
            "--bridge.client-name=artio-spring-failed-start-it",
            "--bridge.reconnect-initial-backoff-ms=600000",
            "--bridge.reconnect-max-backoff-ms=600000",
            "--bridge.flush-timeout-ms=1000",
            "--bridge.stats-log-interval-ms=0"))
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start();

        final int exitCode;
        try
        {
            // Media driver, engine, library, a refused connect and the logon timeout: seconds, not
            // minutes. A process still alive here is the hang this test exists to catch.
            assertTrue(process.waitFor(SpringItSupport.STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                () -> "the fat jar did not exit after its engine failed to start:\n" + read(logFile));
            exitCode = process.exitValue();
        }
        finally
        {
            process.destroyForcibly();
        }

        final String log = String.join("\n", read(logFile));
        System.out.println("---- fat jar (failed start) exit code " + exitCode + " ----\n" + log + "\n---- end ----");

        assertAll(
            () -> assertNotEquals(0, exitCode, () -> "a failed start must not report success:\n" + log),
            () -> assertFalse(log.contains(STARTED_LINE),
                () -> "the initiator had nothing to log on to and must not report started:\n" + log),
            () -> assertTrue(log.contains("artioRuntimeLifecycle"),
                () -> "the failure must name the bean that could not start:\n" + log),
            () -> assertTrue(log.contains(PUBLISHER_CLOSED_LINE),
                () -> "the publisher must be closed when the engine fails to start, or its agent " +
                    "thread keeps the JVM alive:\n" + log));
    }

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
}
