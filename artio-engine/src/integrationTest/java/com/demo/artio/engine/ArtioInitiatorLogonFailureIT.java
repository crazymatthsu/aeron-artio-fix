package com.demo.artio.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An initiator whose logon is refused must learn it from the disconnect, not from the logon
 * timeout. Two counterparties refuse here: an Artio acceptor whose {@code AuthenticationStrategy}
 * says no, and a bare socket that accepts and hangs up.
 *
 * <p>QuickFIX/J is not one of them: on a Logon for a session it does not know it logs an error and
 * leaves the connection open, so an Artio initiator with the wrong CompIDs would sit there until
 * its own timeout whatever this module did.
 */
class ArtioInitiatorLogonFailureIT
{
    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);

    @Test
    void aCounterpartyThatRejectsTheLogonFailsStartLongBeforeTheLogonTimeout()
    {
        final Set<String> before = ItSupport.liveThreadNames();
        final int port = ItSupport.freePort();
        final FixEngineConfig acceptorConfig = FixEngineConfig.acceptor()
            .name("rejecting-acc")
            .address("localhost", port)
            .senderCompId("VENUE")
            .targetCompId("ARTIO")
            .fixVersion(FixVersion.FIX44)
            .baseDirectory(ItSupport.baseDirectory())
            // Artio disconnects a logon its strategy refuses; this is what a venue that does not
            // know the CompIDs, or dislikes the password, does.
            .authenticationStrategy(logon -> false)
            .build();

        try (ArtioRuntime venue = ArtioRuntime.launch(acceptorConfig, new CountingSink(), null))
        {
            final ArtioRuntime initiator = new ArtioRuntime(initiatorConfig("rejected-ini", port), new CountingSink());

            final long elapsedMs = timeFailedStart(initiator);

            assertFailedFast(initiator, elapsedMs, before, venue.runtimeId());
        }
    }

    @Test
    void aCounterpartyThatDropsTheConnectionFailsStartLongBeforeTheLogonTimeout() throws Exception
    {
        final Set<String> before = ItSupport.liveThreadNames();

        try (ServerSocket dropper = new ServerSocket(0))
        {
            final Thread hangUp = new Thread(() ->
            {
                try
                {
                    while (!dropper.isClosed())
                    {
                        // Accept and close at once: the initiator's TCP connect succeeds, its Logon
                        // goes nowhere, and the session ends with a disconnect.
                        final Socket socket = dropper.accept();
                        socket.close();
                    }
                }
                catch (final IOException ignored)
                {
                    // the ServerSocket was closed: done
                }
            }, "hang-up");
            hangUp.setDaemon(true);
            hangUp.start();

            final ArtioRuntime initiator = new ArtioRuntime(
                initiatorConfig("dropped-ini", dropper.getLocalPort()), new CountingSink());

            final long elapsedMs = timeFailedStart(initiator);

            assertFailedFast(initiator, elapsedMs, before, "hang-up");
        }
    }

    private static long timeFailedStart(final ArtioRuntime initiator)
    {
        final long startedAt = System.nanoTime();
        final IllegalStateException failure = assertThrows(IllegalStateException.class, initiator::start);
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        System.out.println("start() failed after " + elapsedMs + "ms: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("before the logon completed") ||
            failure.getMessage().contains("could not initiate"), failure.getMessage());
        return elapsedMs;
    }

    private static void assertFailedFast(
        final ArtioRuntime initiator, final long elapsedMs, final Set<String> before, final String stillRunning)
    {
        // Half the logon timeout is a generous bound; the disconnect is seen within milliseconds.
        assertTrue(elapsedMs < LOGON_TIMEOUT.toMillis() / 2,
            "start() took " + elapsedMs + "ms, i.e. it waited for the logon timeout instead of the disconnect");
        await().atMost(SHUTDOWN_TIMEOUT).until(() -> ItSupport.leakedThreadNames(before).stream()
            .noneMatch(name -> name.contains(initiator.runtimeId())));
        assertAll(
            () -> assertFalse(initiator.isRunning()),
            () -> assertFalse(initiator.directory().toFile().exists(), initiator.directory().toString()),
            () -> assertTrue(ItSupport.liveThreadNames().stream().anyMatch(name -> name.contains(stillRunning)),
                "the counterparty (" + stillRunning + ") is untouched by the initiator's failure"));
    }

    private static FixEngineConfig initiatorConfig(final String name, final int port)
    {
        return FixEngineConfig.initiator()
            .name(name)
            .address("localhost", port)
            .senderCompId("ARTIO")
            .targetCompId("VENUE")
            .fixVersion(FixVersion.FIX44)
            .heartbeatIntervalSec(5)
            .logonTimeoutMs(LOGON_TIMEOUT.toMillis())
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }
}
