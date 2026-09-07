package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line paths of {@link QfjMain} with a real session on loopback: the acceptor form
 * listens until the shutdown hook runs and logs the session out, the initiator form runs the
 * scenario and exits 0. The hook is handed to a recorder instead of the JVM so the test can
 * check when it was registered and run it the way Ctrl-C would.
 */
class QfjMainIT
{
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** Captures the hook and notes what the world looked like when it was registered. */
    private static final class CapturingHooks implements QfjMain.ShutdownHooks
    {
        final AtomicReference<Thread> hook = new AtomicReference<>();
        final AtomicReference<Boolean> engineWasLiveAtRegistration = new AtomicReference<>();
        final AtomicReference<Thread> removed = new AtomicReference<>();
        private final java.util.function.BooleanSupplier engineIsLive;

        CapturingHooks(final java.util.function.BooleanSupplier engineIsLive)
        {
            this.engineIsLive = engineIsLive;
        }

        @Override
        public void add(final Thread hook)
        {
            engineWasLiveAtRegistration.set(engineIsLive.getAsBoolean());
            this.hook.set(hook);
        }

        @Override
        public void remove(final Thread hook)
        {
            removed.set(hook);
        }
    }

    @Test
    void theAcceptorCommandLineListensUntilTheHookRunsAndTheHookLogsTheSessionOut() throws Exception
    {
        final int port = FreePort.next();
        final CapturingHooks hooks = new CapturingHooks(() -> isListening(port));
        final CompletableFuture<Integer> exitCode = CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return QfjMain.run(
                    ("acceptor --port " + port + " --version FIX.4.4 --sender VENUE --target TRADER").split(" "),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    hooks);
            }
            catch (final InterruptedException e)
            {
                throw new IllegalStateException(e);
            }
        });

        try (QfjInitiator initiator = new QfjInitiator(QfjConfig.initiator()
            .version(QfjVersion.FIX44).address("localhost", port)
            .senderCompId("TRADER").targetCompId("VENUE").heartbeatIntervalSec(5).build()))
        {
            initiator.start();
            assertTrue(initiator.awaitLogon(TIMEOUT), "initiator did not log on to the CLI acceptor");

            final Thread hook = hooks.hook.get();
            assertNotNull(hook, "the hook is registered before the port is bound");
            assertEquals(Boolean.FALSE, hooks.engineWasLiveAtRegistration.get(),
                "the hook must be registered before start(), while nothing is listening yet");

            // Ctrl-C.
            hook.start();
            hook.join(TIMEOUT.toMillis());

            assertTrue(initiator.awaitLogout(TIMEOUT), "closing from the hook must send a Logout");
        }

        assertAll(
            () -> assertEquals(0, exitCode.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)),
            () -> assertEquals(hooks.hook.get(), hooks.removed.get(), "the hook is uninstalled on the way out"),
            () -> assertFalse(isListening(port), "the port is released"),
            () -> assertTrue(out.toString(StandardCharsets.UTF_8).contains("== listening on localhost:" + port)),
            () -> assertEquals("", err.toString(StandardCharsets.UTF_8)));
    }

    @Test
    void theInitiatorCommandLineRunsTheScenarioAgainstAnAcceptorAndExitsZero() throws Exception
    {
        final int port = FreePort.next();

        try (QfjAcceptor acceptor = new QfjAcceptor(QfjConfig.acceptor()
            .version(QfjVersion.FIX42).address("localhost", port)
            .senderCompId("VENUE").targetCompId("TRADER").heartbeatIntervalSec(5).build()))
        {
            acceptor.start();
            final CapturingHooks hooks = new CapturingHooks(acceptor::isLoggedOn);

            final int exitCode = QfjMain.run(
                ("initiator --host localhost --port " + port +
                    " --version FIX.4.2 --sender TRADER --target VENUE --scenario orders").split(" "),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                hooks);

            final String output = out.toString(StandardCharsets.UTF_8);
            assertAll(
                () -> assertEquals(0, exitCode, () -> "stderr: " + err.toString(StandardCharsets.UTF_8)),
                () -> assertTrue(output.contains("== sent scenario: [ORD-1, ORD-2, ORD-3, ORD-4, ORD-5]"), output),
                () -> assertTrue(output.contains("== received 8 execution report(s) of an expected 8"), output),
                () -> assertNotNull(hooks.hook.get(), "a hook covers the scenario wait too"),
                () -> assertEquals(Boolean.FALSE, hooks.engineWasLiveAtRegistration.get(),
                    "registered before start(), so before any logon"),
                () -> assertEquals(hooks.hook.get(), hooks.removed.get(), "and uninstalled on the normal return"),
                () -> assertTrue(acceptor.awaitLogout(TIMEOUT), "the try-with-resources close sent a Logout"));
        }
    }

    private static boolean isListening(final int port)
    {
        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress("localhost", port), 500);
            return true;
        }
        catch (final IOException e)
        {
            return false;
        }
    }
}
