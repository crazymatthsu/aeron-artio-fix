package com.demo.artio.engine;

import com.demo.artio.qfj.OrderScenario;
import com.demo.artio.qfj.Orders;
import com.demo.artio.qfj.QfjConfig;
import com.demo.artio.qfj.QfjInitiator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An Artio <strong>acceptor</strong> with a QuickFIX/J initiator on the other end, on both FIX
 * versions.
 *
 * <p>The FIX 4.2 case is the one that matters: it only works because {@code :fix-codecs} generates
 * {@code com.demo.artio.fix42.FixDictionaryImpl} and {@link ArtioRuntime} passes it to
 * {@code EngineConfiguration.acceptorfixDictionary}. Artio's bundled session codecs are FIX 4.4 and
 * would reject the {@code BeginString}.
 */
class ArtioAcceptorFromQuickfixjIT
{
    private static final String ARTIO_COMP_ID = "ARTIO";
    private static final String QFJ_COMP_ID = "QFJ";
    private static final int ORDER_COUNT = 5;

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void fiveNewOrderSinglesArriveAtTheSinkWithTheirClOrdIdsAndAreNotAdminMessages(final FixVersion version)
        throws Exception
    {
        final int port = ItSupport.freePort();
        final CountingSink sink = new CountingSink();
        final RecordingSessionListener listener = new RecordingSessionListener();

        try (ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(version, port), sink, listener);
            QfjInitiator qfj = new QfjInitiator(initiatorConfig(version, port)))
        {
            qfj.start();
            assertTrue(qfj.awaitLogon(ItSupport.STARTUP_TIMEOUT), "QuickFIX/J did not log on to Artio");
            await().atMost(ItSupport.STARTUP_TIMEOUT).until(() -> !runtime.sessions().isEmpty());

            final List<String> clOrdIds = IntStream.rangeClosed(1, ORDER_COUNT)
                .mapToObj(i -> "ACC-" + version.name() + '-' + i)
                .toList();
            for (int i = 0; i < ORDER_COUNT; i++)
            {
                qfj.sendNewOrderSingle(clOrdIds.get(i), "MSFT", Orders.SIDE_BUY, 100 + i, 101.25 + i);
            }

            await().atMost(ItSupport.MESSAGE_TIMEOUT)
                .until(() -> sink.countOfType("D") == ORDER_COUNT);

            final List<CountingSink.Captured> orders = sink.messagesOfType("D");
            assertAll(
                () -> assertEquals(clOrdIds, orders.stream().map(m -> m.field(11)).toList()),
                () -> assertEquals(List.of("MSFT", "MSFT", "MSFT", "MSFT", "MSFT"),
                    orders.stream().map(m -> m.field(55)).toList()),
                () -> assertTrue(orders.stream().noneMatch(CountingSink.Captured::admin),
                    "a NewOrderSingle must not be reported as a session-level message"),
                () -> assertTrue(orders.stream().allMatch(CountingSink.Captured::valid),
                    "Artio rejected a message it should have accepted"),
                () -> assertEquals(version.beginString(), orders.get(0).field(8)),
                () -> assertEquals(QFJ_COMP_ID, orders.get(0).field(49)),
                () -> assertEquals(ARTIO_COMP_ID, orders.get(0).field(56)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void theSinkAlsoSeesTheCounterpartysLogonAndReportsItAsAnAdminMessage(final FixVersion version)
        throws Exception
    {
        final int port = ItSupport.freePort();
        final CountingSink sink = new CountingSink();
        final RecordingSessionListener listener = new RecordingSessionListener();

        try (ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(version, port), sink, listener);
            QfjInitiator qfj = new QfjInitiator(initiatorConfig(version, port)))
        {
            qfj.start();
            assertTrue(qfj.awaitLogon(ItSupport.STARTUP_TIMEOUT), "QuickFIX/J did not log on to Artio");

            // Artio's SessionSubscriber hands EVERY inbound message to the SessionHandler, session
            // level ones included, and in sole-library mode the library owns the session from the
            // logon onwards - so the Logon itself reaches the sink.
            await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> sink.countOfType("A") >= 1);

            final CountingSink.Captured logon = sink.messagesOfType("A").get(0);
            assertAll(
                () -> assertTrue(logon.admin(), "a Logon must be reported as a session-level message"),
                () -> assertEquals("A", logon.msgType()),
                () -> assertEquals(1, logon.sequenceNumber()),
                () -> assertTrue(sink.adminCount() >= 1),
                () -> assertTrue(runtime.sessions().size() == 1));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void theSessionListenerSeesTheSessionAcquiredAndThenLoggedOn(final FixVersion version) throws Exception
    {
        final int port = ItSupport.freePort();
        final RecordingSessionListener listener = new RecordingSessionListener();

        try (ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(version, port), new CountingSink(), listener);
            QfjInitiator qfj = new QfjInitiator(initiatorConfig(version, port)))
        {
            qfj.start();
            assertTrue(qfj.awaitLogon(ItSupport.STARTUP_TIMEOUT), "QuickFIX/J did not log on to Artio");
            await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> listener.sawEventStartingWith("logon:"));

            final SessionKey acquired = listener.acquired().get(0);
            assertAll(
                () -> assertEquals(List.of("acquired:" + QFJ_COMP_ID, "logon:" + QFJ_COMP_ID),
                    listener.events().subList(0, 2)),
                () -> assertEquals(ARTIO_COMP_ID, acquired.localCompId()),
                () -> assertEquals(QFJ_COMP_ID, acquired.remoteCompId()),
                () -> assertEquals(version.beginString(), acquired.beginString()),
                () -> assertTrue(acquired.acceptor(), "the session was accepted, not initiated"),
                () -> assertEquals(List.of(), listener.errors()),
                () -> assertEquals(acquired.sessionId(), runtime.sessions().get(0).id()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void thePlannedOrderScenarioArrivesAsThreeNewOrdersAReplaceAndACancel(final FixVersion version)
        throws Exception
    {
        final int port = ItSupport.freePort();
        final CountingSink sink = new CountingSink();

        try (ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(version, port), sink, null);
            QfjInitiator qfj = new QfjInitiator(initiatorConfig(version, port)))
        {
            qfj.start();
            assertTrue(qfj.awaitLogon(ItSupport.STARTUP_TIMEOUT), "QuickFIX/J did not log on to Artio");

            final List<String> clOrdIds = qfj.run(OrderScenario.DEFAULT);

            await().atMost(ItSupport.MESSAGE_TIMEOUT)
                .until(() -> sink.applicationCount() == OrderScenario.MESSAGE_COUNT);

            final List<CountingSink.Captured> application = sink.messages().stream()
                .filter(message -> !message.admin())
                .toList();
            assertAll(
                () -> assertEquals(List.of("D", "D", "D", "G", "F"),
                    application.stream().map(CountingSink.Captured::msgType).toList()),
                () -> assertEquals(clOrdIds, application.stream().map(m -> m.field(11)).toList()),
                () -> assertEquals("ORD-1", application.get(3).field(41)),
                () -> assertEquals("ORD-2", application.get(4).field(41)),
                () -> assertFalse(runtime.logonLatency().isPresent(),
                    "an acceptor does not drive the logon, so it measures no logon latency"));
        }
    }

    private static FixEngineConfig acceptorConfig(final FixVersion version, final int port)
    {
        return FixEngineConfig.acceptor()
            .name("acc" + version.name())
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(version)
            .heartbeatIntervalSec(5)
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }

    private static QfjConfig initiatorConfig(final FixVersion version, final int port)
    {
        return QfjConfig.initiator()
            .version(ItSupport.counterpartyVersion(version))
            .address("localhost", port)
            .senderCompId(QFJ_COMP_ID)
            .targetCompId(ARTIO_COMP_ID)
            .heartbeatIntervalSec(5)
            .build();
    }
}
