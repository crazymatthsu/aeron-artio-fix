package com.demo.artio.engine;

import com.demo.artio.qfj.CapturedMessage;
import com.demo.artio.qfj.QfjAcceptor;
import com.demo.artio.qfj.QfjConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    () -> assertTrue(runtime.logonLatency().orElseThrow().toMillis() >= 0),
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
    void sendingWithNoSessionFailsTheFutureRatherThanThrowingOnTheCallersThread(final FixVersion version)
        throws Exception
    {
        final int port = ItSupport.freePort();

        try (QfjAcceptor venue = new QfjAcceptor(acceptorConfig(version, port)))
        {
            venue.start();
            final ArtioRuntime runtime = ArtioRuntime.launch(
                initiatorConfig(version, port), new CountingSink(), null);
            runtime.close();

            final Exception failure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> runtime.send(ItSupport.newOrderSingle(version, "GONE", "MSFT", 1, 1, 0))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS));

            assertTrue(failure.getCause().getMessage().contains("is closed"), failure.toString());
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
