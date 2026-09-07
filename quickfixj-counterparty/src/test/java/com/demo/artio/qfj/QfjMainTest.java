package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line's exit codes and shutdown hook, without a socket: every case here returns
 * before an engine is started. The paths that do start one are in {@code QfjMainIT}.
 */
class QfjMainTest
{
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final RecordingHooks hooks = new RecordingHooks();

    /** Records what would have been registered with the JVM. */
    static final class RecordingHooks implements QfjMain.ShutdownHooks
    {
        final List<Thread> added = new ArrayList<>();
        final List<Thread> removed = new ArrayList<>();

        @Override
        public void add(final Thread hook)
        {
            added.add(hook);
        }

        @Override
        public void remove(final Thread hook)
        {
            removed.add(hook);
        }
    }

    private int run(final String line) throws InterruptedException
    {
        return QfjMain.run(line.split(" "),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            hooks);
    }

    private String err()
    {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpPrintsTheUsageOnStdoutAndExitsZero() throws Exception
    {
        assertEquals(0, run("--help"));

        assertAll(
            () -> assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage:")),
            () -> assertEquals("", err()),
            () -> assertTrue(hooks.added.isEmpty(), "nothing was started"));
    }

    @Test
    void aMissingPortIsAUsageErrorSayingItIsRequired() throws Exception
    {
        assertEquals(QfjMain.EXIT_BAD_ARGUMENTS, run("acceptor --version FIX.4.4"));

        assertAll(
            () -> assertTrue(err().startsWith("--port is required"), err()),
            () -> assertTrue(err().contains("Usage:"), err()));
    }

    @Test
    void aNegativePortIsReportedAsOutOfRangeNotAsMissing() throws Exception
    {
        assertEquals(QfjMain.EXIT_BAD_ARGUMENTS, run("acceptor --port -5"));

        assertAll(
            () -> assertTrue(err().contains("1..65535"), err()),
            () -> assertTrue(err().contains("-5"), err()),
            () -> assertFalse(err().contains("required"), err()),
            () -> assertTrue(err().contains("Usage:"), err()));
    }

    @Test
    void aPortOutsideTheTcpRangeIsAUsageErrorWithTheUsageTextNotAStackTrace() throws Exception
    {
        assertAll(
            () -> assertEquals(QfjMain.EXIT_BAD_ARGUMENTS, run("acceptor --port 0")),
            () -> assertEquals(QfjMain.EXIT_BAD_ARGUMENTS, run("initiator --port 70000")));

        assertAll(
            () -> assertTrue(err().contains("port must be in 1..65535 but was 0"), err()),
            () -> assertTrue(err().contains("port must be in 1..65535 but was 70000"), err()),
            () -> assertTrue(err().contains("Usage:"), err()),
            () -> assertFalse(err().contains("Exception"), err()));
    }

    @Test
    void aZeroHeartbeatIsAUsageError() throws Exception
    {
        assertEquals(QfjMain.EXIT_BAD_ARGUMENTS, run("acceptor --port 9881 --heartbeat 0"));

        assertAll(
            () -> assertTrue(err().contains("heartbeatIntervalSec must be at least 1"), err()),
            () -> assertTrue(err().contains("Usage:"), err()));
    }

    @Test
    void equalCompIdsAreAUsageError() throws Exception
    {
        assertEquals(QfjMain.EXIT_BAD_ARGUMENTS, run("acceptor --port 9881 --sender SAME --target SAME"));

        assertAll(
            () -> assertTrue(err().contains("must differ"), err()),
            () -> assertTrue(err().contains("Usage:"), err()));
    }

    @Test
    void anUnknownOptionIsAUsageError() throws Exception
    {
        assertEquals(QfjMain.EXIT_BAD_ARGUMENTS, run("acceptor --port 9881 --nope"));

        assertTrue(err().startsWith("Unknown option '--nope'"), err());
    }

    @Test
    void noEngineIsStartedForABadCommandLine() throws Exception
    {
        run("acceptor --port 0");
        run("initiator --port 9880 --heartbeat 0");

        assertTrue(hooks.added.isEmpty(), "a hook is only installed once an engine exists");
    }

    @Test
    void theShutdownHookClosesTheEngineAndReleasesWhoeverIsWaiting() throws Exception
    {
        final AtomicBoolean closed = new AtomicBoolean();
        final QfjMain.ShutdownHook hook = QfjMain.ShutdownHook.install(
            () -> closed.set(true), hooks, new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(List.of(hook.thread()), hooks.added, "installed on construction");

        // What the JVM does on Ctrl-C.
        hook.thread().start();
        hook.thread().join();
        hook.awaitShutdown();

        assertTrue(closed.get());

        hook.uninstall();
        assertEquals(List.of(hook.thread()), hooks.removed);
    }

    @Test
    void theShutdownHookReportsAFailingCloseAndStillReleasesTheWaiter() throws Exception
    {
        final QfjMain.ShutdownHook hook = QfjMain.ShutdownHook.install(
            () ->
            {
                throw new IllegalStateException("boom");
            },
            hooks, new PrintStream(err, true, StandardCharsets.UTF_8));

        hook.thread().start();
        hook.thread().join();
        hook.awaitShutdown();

        assertTrue(err().contains("Shutdown failed"), err());
        assertTrue(err().contains("boom"), err());
    }

    @Test
    void theRealRegistryIsTheJvmsAndUninstallingTwiceIsHarmless() throws Exception
    {
        final QfjMain.ShutdownHook hook = QfjMain.ShutdownHook.install(
            () ->
            {
            },
            QfjMain.ShutdownHooks.JVM, new PrintStream(err, true, StandardCharsets.UTF_8));

        assertTrue(Runtime.getRuntime().removeShutdownHook(hook.thread()), "was registered with the JVM");
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook.thread()), "and only once");
        hook.uninstall();
    }
}
