package com.demo.artio.engine;

import com.demo.artio.qfj.QfjAcceptor;
import com.demo.artio.qfj.QfjConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An initiator that loses its venue reconnects on its own: the runtime stays up, backs off, and
 * logs on again to whatever answers on the address, without any thread beyond the library poll
 * thread being involved.
 *
 * <p>The venue is restarted rather than merely bounced because that is the honest failure: the
 * process on the other end went away and came back with no memory of the session. Artio's
 * {@code resetSeqNumsOnLogon} (on by default) is what makes the new session start from 1 on both
 * sides.
 */
class ArtioInitiatorReconnectIT
{
    private static final String ARTIO_COMP_ID = "ARTIO";
    private static final String QFJ_COMP_ID = "QFJ";
    private static final FixVersion VERSION = FixVersion.FIX44;

    /** Short enough that the test does not sit around, long enough to be visibly a back-off. */
    private static final long INITIAL_BACKOFF_MS = 250;
    private static final long MAX_BACKOFF_MS = 2_000;

    private static final Duration RECONNECT_TIMEOUT = Duration.ofSeconds(45);

    @Test
    void anInitiatorLogsOnAgainToAVenueThatWasRestartedAndKeepsSendingOnTheNewSession() throws Exception
    {
        final int port = ItSupport.freePort();
        final CountingSink sink = new CountingSink();
        final RecordingSessionListener listener = new RecordingSessionListener();

        final QfjAcceptor firstVenue = new QfjAcceptor(acceptorConfig(port));
        firstVenue.start();
        try (ArtioRuntime runtime = ArtioRuntime.launch(initiatorConfig("recon", port, true), sink, listener))
        {
            assertTrue(firstVenue.awaitLogon(ItSupport.STARTUP_TIMEOUT), "the first venue saw the logon");
            final long firstSessionId = runtime.session().orElseThrow().id();

            // The venue goes away; the initiator must notice and start trying again.
            firstVenue.close();
            await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> !runtime.isSessionActive());
            await().atMost(RECONNECT_TIMEOUT).until(() -> listener.reconnectAttempts() >= 1);

            assertAll(
                () -> assertTrue(runtime.isRunning(), "losing a session does not stop the runtime"),
                () -> assertEquals(INITIAL_BACKOFF_MS, listener.reconnectBackoffs().get(0),
                    "the first attempt waits the initial back-off"));

            // A brand new acceptor on the same address: same CompIDs, no memory of the session.
            try (QfjAcceptor secondVenue = new QfjAcceptor(acceptorConfig(port)))
            {
                secondVenue.start();

                assertTrue(secondVenue.awaitLogon(RECONNECT_TIMEOUT), "the restarted venue saw a fresh logon");
                await().atMost(ItSupport.MESSAGE_TIMEOUT).until(runtime::isSessionActive);

                runtime.sendAndAwait(
                    ItSupport.newOrderSingle(VERSION, "AFTER-RECONNECT", "MSFT", 100, 10125, 2),
                    Duration.ofSeconds(10));

                assertTrue(secondVenue.awaitReceived("D", 1, ItSupport.MESSAGE_TIMEOUT),
                    () -> "the restarted venue saw " + secondVenue.receivedMessages());
                await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> sink.countOfType("8") >= 1);

                final List<String> events = listener.events();
                assertAll(
                    () -> assertEquals(1, runtime.sessions().size(),
                        "the lost session was replaced, not added to"),
                    // Artio's surrogate session id is keyed by the composite key (BeginString and
                    // the two CompIDs), so the reconnected session answers to the same id. A caller
                    // that remembered the id for send(sessionId, ...) keeps working.
                    () -> assertEquals(firstSessionId, runtime.session().orElseThrow().id()),
                    () -> assertTrue(events.stream().filter(e -> e.startsWith("logon:")).count() >= 2,
                        () -> "one logon per connection was expected, got " + events),
                    () -> assertTrue(events.stream().anyMatch(e -> e.startsWith("disconnect:")), events::toString),
                    () -> assertEquals("AFTER-RECONNECT",
                        secondVenue.receivedMessages().stream()
                            .filter(m -> m.msgType().equals("D")).findFirst().orElseThrow().field(11)));
            }
        }
        finally
        {
            firstVenue.close();
        }
    }

    @Test
    void backOffGrowsWhileNothingIsListeningAndNeverPassesItsCeiling() throws Exception
    {
        final int port = ItSupport.freePort();
        final RecordingSessionListener listener = new RecordingSessionListener();

        final QfjAcceptor venue = new QfjAcceptor(acceptorConfig(port));
        venue.start();
        try (ArtioRuntime runtime = ArtioRuntime.launch(
            initiatorConfig("backoff", port, true), new CountingSink(), listener))
        {
            assertTrue(venue.awaitLogon(ItSupport.STARTUP_TIMEOUT));
            venue.close();

            // Nothing is listening on the port any more, so every attempt fails and the delay
            // between them doubles.
            await().atMost(RECONNECT_TIMEOUT).until(() -> listener.reconnectAttempts() >= 4);

            final List<Long> backoffs = listener.reconnectBackoffs();
            assertAll(
                () -> assertEquals(INITIAL_BACKOFF_MS, backoffs.get(0)),
                () -> assertTrue(backoffs.get(1) > backoffs.get(0),
                    () -> "the back-off must grow, was " + backoffs),
                () -> assertTrue(backoffs.stream().allMatch(backoff -> backoff <= MAX_BACKOFF_MS),
                    () -> "no back-off may pass the ceiling, was " + backoffs),
                () -> assertTrue(runtime.isRunning(), "a venue that never comes back does not stop the runtime"),
                () -> assertFalse(runtime.isSessionActive()));
        }
        finally
        {
            venue.close();
        }
    }

    @Test
    void reconnectDisabledMeansNoAttemptIsEverMade() throws Exception
    {
        final int port = ItSupport.freePort();
        final RecordingSessionListener listener = new RecordingSessionListener();

        final QfjAcceptor venue = new QfjAcceptor(acceptorConfig(port));
        venue.start();
        try (ArtioRuntime runtime = ArtioRuntime.launch(
            initiatorConfig("norecon", port, false), new CountingSink(), listener))
        {
            assertTrue(venue.awaitLogon(ItSupport.STARTUP_TIMEOUT));
            venue.close();
            await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> listener.sawEventStartingWith("disconnect:"));

            // Long enough for several attempts at the configured back-off, had any been made.
            Thread.sleep(4 * INITIAL_BACKOFF_MS);

            assertAll(
                () -> assertEquals(0, listener.reconnectAttempts()),
                () -> assertFalse(runtime.isSessionActive()),
                () -> assertTrue(runtime.isRunning()),
                () -> assertEquals(List.of(), runtime.sessions()));
        }
        finally
        {
            venue.close();
        }
    }

    private static FixEngineConfig initiatorConfig(final String name, final int port, final boolean reconnect)
    {
        return FixEngineConfig.initiator()
            .name(name)
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(VERSION)
            .heartbeatIntervalSec(5)
            .reconnectEnabled(reconnect)
            .reconnectInitialBackoffMs(INITIAL_BACKOFF_MS)
            .reconnectMaxBackoffMs(MAX_BACKOFF_MS)
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }

    private static QfjConfig acceptorConfig(final int port)
    {
        return QfjConfig.acceptor()
            .version(ItSupport.counterpartyVersion(VERSION))
            .address("localhost", port)
            .senderCompId(QFJ_COMP_ID)
            .targetCompId(ARTIO_COMP_ID)
            .heartbeatIntervalSec(5)
            .build();
    }
}
