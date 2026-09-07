package com.demo.artio.engine;

import io.aeron.CommonContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The directory policy with real drivers and engines: an explicit directory is reused rather than
 * wiped, a second runtime cannot take over a live one, and close removes what it owns.
 */
class ArtioRuntimeDirectoriesIT
{
    /** The media driver's own liveness threshold, which is what ArtioRuntime's check uses too. */
    private static final long DRIVER_TIMEOUT_MS = CommonContext.DRIVER_TIMEOUT_MS;

    @Test
    void anExplicitLogDirectoryIsNotWipedOnStartAndIsRemovedOnClose(@TempDir final Path tempDir) throws Exception
    {
        final Path logs = tempDir.resolve("logs");
        Files.createDirectories(logs);
        final Path marker = logs.resolve("left-by-a-previous-run.txt");
        Files.writeString(marker, "keep me");

        final ArtioRuntime runtime = ArtioRuntime.launch(
            config("explicit-logs").logFileDir(logs).build(), new CountingSink(), null);
        try
        {
            assertAll(
                () -> assertTrue(runtime.isRunning()),
                () -> assertTrue(Files.exists(marker), "start() must not wipe an explicit log directory"),
                () -> assertTrue(Files.list(logs).count() > 1, "Artio wrote its own files next to the marker"));
        }
        finally
        {
            runtime.close();
        }

        assertAll(
            () -> assertFalse(Files.exists(logs), "close() removes an explicit directory by default"),
            () -> assertFalse(Files.exists(runtime.directory())));
    }

    @Test
    void anExplicitLogDirectorySurvivesCloseWhenDeleteOnCloseIsOff(@TempDir final Path tempDir) throws Exception
    {
        final Path logs = tempDir.resolve("logs");

        final ArtioRuntime runtime = ArtioRuntime.launch(
            config("kept-logs").logFileDir(logs).deleteDirectoriesOnClose(false).build(),
            new CountingSink(), null);
        runtime.close();

        assertAll(
            () -> assertTrue(Files.isDirectory(logs)),
            () -> assertTrue(Files.list(logs).findAny().isPresent(), "the message log is still there"),
            () -> assertTrue(Files.isDirectory(runtime.directory()), "the runtime directory is kept too"));
    }

    @Test
    void aSecondRuntimeGivenTheSameExplicitAeronDirectoryFailsFastAndLeavesTheFirstAlone(
        @TempDir final Path tempDir)
    {
        final Path aeron = tempDir.resolve("aeron");

        final ArtioRuntime first = ArtioRuntime.launch(
            config("first").aeronDirectory(aeron).build(), new CountingSink(), null);
        try
        {
            final ArtioRuntime second = new ArtioRuntime(
                config("second").aeronDirectory(aeron).logonTimeoutMs(5_000).build(), new CountingSink());

            final long startedAt = System.nanoTime();
            final IllegalStateException failure = assertThrows(IllegalStateException.class, second::start);
            final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertAll(
                () -> assertTrue(failure.getMessage().contains(aeron.toString()), failure.getMessage()),
                () -> assertTrue(failure.getMessage().contains("another media driver is live"), failure.getMessage()),
                () -> assertTrue(elapsedMs < 5_000, "failed in " + elapsedMs + "ms, not after a timeout"),
                () -> assertFalse(second.isRunning()),
                () -> assertFalse(Files.exists(second.directory()), "the failed start cleaned up its own directory"),
                // The first runtime's driver is untouched: its directory is still there and it is
                // still serving.
                () -> assertTrue(first.isRunning()),
                () -> assertTrue(Files.isDirectory(aeron)),
                () -> assertTrue(Files.exists(aeron.resolve("cnc.dat")), "the live driver's CnC file survived"));
        }
        finally
        {
            first.close();
        }
        await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> !Files.exists(aeron));
    }

    @Test
    void anExplicitAeronDirectoryLeftByADeadDriverIsReusedNotRefused(@TempDir final Path tempDir)
    {
        final Path aeron = tempDir.resolve("aeron");

        // Start and close a runtime that keeps its directories: what it leaves behind is exactly
        // what a crashed process would leave behind, minus the heartbeat.
        ArtioRuntime.launch(
            config("dead").aeronDirectory(aeron).deleteDirectoriesOnClose(false).build(),
            new CountingSink(), null).close();
        assertTrue(Files.isDirectory(aeron), "the stale directory is the precondition here");

        // Aeron tells a dead driver from a live one by the heartbeat in cnc.dat. A driver that
        // closed cleanly clears it on the way out, so this returns at once; one that crashed still
        // looks alive for driverTimeoutMs (10s by default) and a restart inside that window is
        // refused exactly as the previous test shows. Waiting covers both.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .until(() -> !CommonContext.isDriverActive(aeron.toFile(), DRIVER_TIMEOUT_MS, line -> { }));

        final ArtioRuntime next = ArtioRuntime.launch(
            config("next").aeronDirectory(aeron).build(), new CountingSink(), null);
        try
        {
            assertTrue(next.isRunning());
        }
        finally
        {
            next.close();
        }
    }

    private static FixEngineConfig.Builder config(final String name)
    {
        return FixEngineConfig.acceptor()
            .name(name)
            .address("localhost", ItSupport.freePort())
            .senderCompId("ARTIO")
            .targetCompId("QFJ")
            .fixVersion(FixVersion.FIX42)
            .heartbeatIntervalSec(5)
            .baseDirectory(ItSupport.baseDirectory());
    }
}
