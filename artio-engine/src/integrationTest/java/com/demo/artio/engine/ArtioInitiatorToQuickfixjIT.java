package com.demo.artio.engine;

import com.demo.artio.qfj.CapturedMessage;
import com.demo.artio.qfj.QfjAcceptor;
import com.demo.artio.qfj.QfjConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.co.real_logic.artio.session.Session;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An Artio <strong>initiator</strong> with a QuickFIX/J acceptor on the other end, on both FIX
 * versions: Artio connects out, logs on, sends a {@code NewOrderSingle} built with a generated
 * encoder, and the venue's execution reports come back to the sink.
 *
 * <p>This is the direction that exercises {@link ArtioRuntime#send(uk.co.real_logic.artio.builder.Encoder)}
 * and, with it, the rule that only the library poll thread may touch a {@code Session}.
 */
class ArtioInitiatorToQuickfixjIT
{
    private static final String ARTIO_COMP_ID = "ARTIO";
    private static final String QFJ_COMP_ID = "QFJ";

    /** The venue answers a NewOrderSingle with an acknowledgement and then a fill. */
    private static final int REPORTS_PER_ORDER = 2;

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void artioLogsOnToTheVenueAndReportsHowLongItTook(final FixVersion version) throws Exception
    {
        final int port = ItSupport.freePort();

        try (QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port)))
        {
            venue.start();
            try (ArtioRuntime runtime = ArtioRuntime.launch(
                initiatorConfig(version, port), new CountingSink(), null))
            {
                // launch() only returns once the session is active, so there is nothing to wait for.
                assertAll(
                    () -> assertEquals(1, runtime.sessions().size()),
                    () -> assertTrue(runtime.session().orElseThrow().isActive()),
                    () -> assertTrue(runtime.logonLatency().isPresent(),
                        "an initiator measures the logon it drove"),
                    // A real measurement: a logon exchange over loopback takes some time but must
                    // be well inside the timeout start() would otherwise have failed on.
                    () -> assertTrue(runtime.logonLatency().orElseThrow().toNanos() > 0,
                        "the logon cannot have taken zero nanoseconds"),
                    () -> assertTrue(
                        runtime.logonLatency().orElseThrow().toMillis() < runtime.config().logonTimeoutMs(),
                        () -> "the logon took " + runtime.logonLatency().orElseThrow().toMillis() +
                            "ms, which is not less than the " + runtime.config().logonTimeoutMs() +
                            "ms timeout start() enforces"),
                    () -> assertTrue(venue.isLoggedOn(), "the venue did not see the session"),
                    () -> assertEquals(false, runtime.sessionKeys().get(0).acceptor()));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void anOrderSentWithAGeneratedEncoderIsAcceptedByQuickfixjAndAnsweredWithExecutionReports(
        final FixVersion version) throws Exception
    {
        final int port = ItSupport.freePort();
        final CountingSink sink = new CountingSink();
        final String clOrdId = "ART-" + version.name() + "-1";

        try (QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port)))
        {
            venue.start();
            try (ArtioRuntime runtime = ArtioRuntime.launch(initiatorConfig(version, port), sink, null))
            {
                runtime.sendAndAwait(
                    ItSupport.newOrderSingle(version, clOrdId, "MSFT", 100, 10125, 2),
                    Duration.ofSeconds(10));

                assertTrue(venue.awaitReceived("D", 1, ItSupport.MESSAGE_TIMEOUT),
                    () -> "the venue saw " + venue.receivedMessages());

                final CapturedMessage order = venue.receivedMessages().stream()
                    .filter(message -> message.msgType().equals("D"))
                    .findFirst()
                    .orElseThrow();

                assertAll(
                    // QuickFIX/J validates inbound application messages against its own FIX42.xml /
                    // FIX44.xml, so an order it accepts is an order the generated encoder built
                    // correctly - required fields and all.
                    () -> assertEquals(clOrdId, order.field(11)),
                    () -> assertEquals("MSFT", order.field(55)),
                    () -> assertEquals("1", order.field(54)),
                    () -> assertEquals("100", order.field(38)),
                    () -> assertEquals("101.25", order.field(44)),
                    () -> assertEquals(version.beginString(), order.field(8)),
                    () -> assertEquals(ARTIO_COMP_ID, order.field(49)),
                    () -> assertEquals(QFJ_COMP_ID, order.field(56)));

                await().atMost(ItSupport.MESSAGE_TIMEOUT)
                    .until(() -> sink.countOfType("8") >= REPORTS_PER_ORDER);

                final List<CountingSink.Captured> reports = sink.messagesOfType("8");
                assertAll(
                    () -> assertEquals("0", reports.get(0).field(150), "first report is an acknowledgement"),
                    () -> assertEquals(String.valueOf(ItSupport.counterpartyVersion(version).fillExecType()),
                        reports.get(1).field(150), "second report is a fill"),
                    () -> assertEquals(clOrdId, reports.get(0).field(11)),
                    () -> assertEquals("100", reports.get(1).field(14), "the fill reports CumQty"),
                    () -> assertTrue(reports.stream().noneMatch(CountingSink.Captured::admin),
                        "an ExecutionReport is an application message"));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void threeOrdersSentInARowAllArriveInOrder(final FixVersion version) throws Exception
    {
        final int port = ItSupport.freePort();
        final CountingSink sink = new CountingSink();

        try (QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port)))
        {
            venue.start();
            try (ArtioRuntime runtime = ArtioRuntime.launch(initiatorConfig(version, port), sink, null))
            {
                for (int i = 1; i <= 3; i++)
                {
                    runtime.sendAndAwait(
                        ItSupport.newOrderSingle(version, "SEQ-" + i, "VOD.L", 10L * i, 1000 + i, 1),
                        Duration.ofSeconds(10));
                }

                assertTrue(venue.awaitReceived("D", 3, ItSupport.MESSAGE_TIMEOUT));
                await().atMost(ItSupport.MESSAGE_TIMEOUT)
                    .until(() -> sink.countOfType("8") >= 3 * REPORTS_PER_ORDER);

                final List<String> clOrdIds = venue.receivedMessages().stream()
                    .filter(message -> message.msgType().equals("D"))
                    .map(message -> message.field(11))
                    .toList();
                final List<String> sequenceNumbers = venue.receivedMessages().stream()
                    .filter(message -> message.msgType().equals("D"))
                    .map(message -> message.field(34))
                    .toList();

                assertAll(
                    () -> assertEquals(List.of("SEQ-1", "SEQ-2", "SEQ-3"), clOrdIds),
                    // MsgSeqNum increases by one per message, so nothing was lost or reordered.
                    () -> assertEquals(2, Integer.parseInt(sequenceNumbers.get(2)) -
                        Integer.parseInt(sequenceNumbers.get(0))),
                    () -> assertEquals(6, sink.countOfType("8"),
                        "two execution reports per order should have come back"));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void sendingOnASessionIdThisRuntimeDoesNotOwnFailsTheFutureAndLeavesTheRealSessionAlone(
        final FixVersion version) throws Exception
    {
        final int port = ItSupport.freePort();
        final long strangerId = 987_654_321L;

        try (QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port)))
        {
            venue.start();
            try (ArtioRuntime runtime = ArtioRuntime.launch(
                initiatorConfig(version, port), new CountingSink(), null))
            {
                // The null-session branch: the runtime is up and healthy, but nothing it owns
                // answers to this id, so the command is failed instead of being written anywhere.
                final ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> runtime.send(strangerId, ItSupport.newOrderSingle(version, "STRANGER", "MSFT", 1, 1, 0))
                        .get(10, TimeUnit.SECONDS));

                // The runtime is still usable afterwards: a send on the session it does own works.
                runtime.sendAndAwait(
                    ItSupport.newOrderSingle(version, "REAL-" + version.name(), "MSFT", 1, 1, 0),
                    Duration.ofSeconds(10));

                assertTrue(venue.awaitReceived("D", 1, ItSupport.MESSAGE_TIMEOUT));
                assertAll(
                    () -> assertTrue(failure.getCause() instanceof IllegalStateException, failure.toString()),
                    () -> assertTrue(failure.getCause().getMessage().contains("no session with id " + strangerId),
                        failure.getCause().getMessage()),
                    () -> assertTrue(runtime.isSessionActive()),
                    () -> assertEquals(List.of("REAL-" + version.name()),
                        venue.receivedMessages().stream()
                            .filter(message -> message.msgType().equals("D"))
                            .map(message -> message.field(11))
                            .toList(),
                        "only the send that named a real session reached the venue"));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void sendingAfterCloseFailsTheFutureRatherThanThrowingOnTheCallersThread(final FixVersion version)
        throws Exception
    {
        final int port = ItSupport.freePort();

        try (QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port)))
        {
            venue.start();
            final ArtioRuntime runtime = ArtioRuntime.launch(
                initiatorConfig(version, port), new CountingSink(), null);
            runtime.close();

            final ExecutionException failure = assertThrows(ExecutionException.class,
                () -> runtime.send(ItSupport.newOrderSingle(version, "GONE", "MSFT", 1, 1, 0))
                    .get(5, TimeUnit.SECONDS));

            assertTrue(failure.getCause().getMessage().contains("is closed"), failure.toString());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void sendingWhileTheSessionIsLoggingOutFailsTheFutureRatherThanReportingAPosition(final FixVersion version)
        throws Exception
    {
        final int port = ItSupport.freePort();

        try (QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port)))
        {
            venue.start();
            try (ArtioRuntime runtime = ArtioRuntime.launch(initiatorConfig(version, port), new CountingSink(), null))
            {
                final Session session = runtime.session().orElseThrow();

                // Both commands go on the poll thread's queue back to back and are drained in one
                // pass, with no library.poll in between: by the time the send runs the session has
                // left ACTIVE and the counterparty's Logout has not yet arrived. Session.trySend
                // itself would happily encode and write the order into the closing session.
                final CompletableFuture<Void> logout = runtime.submit(library -> session.startLogout());
                final CompletableFuture<Long> send = runtime.send(
                    ItSupport.newOrderSingle(version, "LATE-" + version.name(), "MSFT", 1, 1, 0));

                logout.get(10, TimeUnit.SECONDS);
                final ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> send.get(10, TimeUnit.SECONDS));

                assertAll(
                    () -> assertTrue(failure.getCause() instanceof IllegalStateException, failure.toString()),
                    () -> assertTrue(failure.getCause().getMessage().contains("not ACTIVE"), failure.toString()),
                    () -> assertTrue(venue.awaitLogout(ItSupport.MESSAGE_TIMEOUT), "the venue saw the logout"),
                    () -> assertFalse(venue.awaitReceived("D", 1, Duration.ofSeconds(2)),
                        "the order must not reach the venue after its future failed"));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void sendingAfterTheCounterpartyDroppedTheSessionFailsTheFuture(final FixVersion version) throws Exception
    {
        final int port = ItSupport.freePort();
        final RecordingSessionListener listener = new RecordingSessionListener();
        final FixEngineConfig config = FixEngineConfig.initiator()
            .name("dropped" + version.name())
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(version)
            .heartbeatIntervalSec(5)
            // Reconnecting is what an initiator does after a drop; this test is about the sends
            // in between, so keep the runtime session-less once the venue has gone.
            .reconnectEnabled(false)
            .baseDirectory(ItSupport.baseDirectory())
            .build();

        final QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port));
        venue.start();
        try (ArtioRuntime runtime = ArtioRuntime.launch(config, new CountingSink(), listener))
        {
            venue.close();
            await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> listener.sawEventStartingWith("disconnect:"));
            await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> !runtime.isSessionActive());

            final ExecutionException failure = assertThrows(ExecutionException.class,
                () -> runtime.send(ItSupport.newOrderSingle(version, "GONE", "MSFT", 1, 1, 0))
                    .get(10, TimeUnit.SECONDS));

            assertAll(
                () -> assertTrue(failure.getCause() instanceof IllegalStateException, failure.toString()),
                // The other null-session branch: no session at all rather than an unknown id.
                () -> assertTrue(failure.getCause().getMessage().contains("no session is active"),
                    failure.getCause().getMessage()),
                () -> assertTrue(runtime.isRunning(), "the runtime itself is still up"),
                () -> assertFalse(runtime.isSessionActive()));
        }
    }

    private static FixEngineConfig initiatorConfig(final FixVersion version, final int port)
    {
        return FixEngineConfig.initiator()
            .name("ini" + version.name())
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(version)
            .heartbeatIntervalSec(5)
            // Every test here is about one stable session: a reconnect racing a deliberate logout
            // would only add noise. Reconnecting has its own suite, ArtioInitiatorReconnectIT.
            .reconnectEnabled(false)
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }

    private static QfjConfig acceptorConfig(final FixVersion version, final int port)
    {
        return QfjConfig.acceptor()
            .version(ItSupport.counterpartyVersion(version))
            .address("localhost", port)
            .senderCompId(QFJ_COMP_ID)
            .targetCompId(ARTIO_COMP_ID)
            .heartbeatIntervalSec(5)
            .build();
    }
}
