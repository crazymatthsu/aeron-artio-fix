package com.demo.artio.bridge;

import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.FixVersion;
import com.demo.artio.qfj.OrderScenario;
import com.demo.artio.qfj.Orders;
import com.demo.artio.qfj.QfjInitiator;
import com.demo.artio.testharness.AmpsAssumptions;
import com.demo.artio.testharness.AmpsComposeServer;
import com.demo.artio.testharness.SowReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path, end to end, on both FIX versions: a QuickFIX/J initiator sends the five-message
 * order scenario to an Artio acceptor whose sink is the bridge, and the records come back out of
 * AMPS.
 *
 * <p>Everything real: a container, a media driver, an Aeron archive, two FIX engines on loopback,
 * an off-heap ring buffer, an AMPS client, and a SOW.
 *
 * <p>One container for the class. The two parameterised runs share it, so every assertion is
 * written to be independent of what the other run left behind: {@code ClOrdID}s are prefixed per
 * version, and the journal count is a <em>delta</em> measured around the scenario rather than an
 * absolute. The two topics nothing in this class publishes to - {@code fix.execs} and
 * {@code fix.order.state} - are asserted absolutely empty, which is the strongest statement
 * available and the one that matters: a QuickFIX/J initiator never sends an execution report, so a
 * record on either would mean the router put something where it does not belong.
 */
class BridgeOrderFlowIT
{
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

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void theOrderScenarioLeavesOneSowRecordPerClOrdIdAndFiveMessagesOnTheJournal(final FixVersion version)
        throws Exception
    {
        // A ClOrdID prefix per version, so the two runs cannot see each other's records in a SOW
        // that is keyed on exactly that field.
        final OrderScenario scenario =
            new OrderScenario(version == FixVersion.FIX42 ? "ORD" : "V44", "MSFT", Orders.SIDE_BUY, 100, 101.25);
        final int port = BridgeItSupport.freePort();

        // fix.raw is journalled and cumulative; measure what this run adds, not what the topic holds.
        final long rawBefore = SowReader.countFromEpoch(
            amps.uri(), BridgeConfig.TOPIC_RAW, Duration.ofSeconds(2));

        final List<String> clOrdIds;
        final AmpsFixPublisher publisher =
            new AmpsFixPublisher(BridgeItSupport.bridgeConfig(version, amps.uri()));
        publisher.start();
        try
        {
            try (ArtioRuntime runtime = ArtioRuntime.launch(
                    BridgeItSupport.acceptorConfig(version, port), publisher, null);
                QfjInitiator qfj = new QfjInitiator(BridgeItSupport.initiatorConfig(version, port)))
            {
                qfj.start();
                assertTrue(qfj.awaitLogon(BridgeItSupport.STARTUP_TIMEOUT),
                    "QuickFIX/J did not log on to the bridge's acceptor");
                await().atMost(BridgeItSupport.STARTUP_TIMEOUT).until(() -> !runtime.sessions().isEmpty());

                clOrdIds = qfj.run(scenario);

                // The bridge has seen and published everything: five application messages on
                // fix.raw plus five on fix.orders. The Logon is dropped, so it is not in the count.
                await().atMost(BridgeItSupport.MESSAGE_TIMEOUT)
                    .until(() -> publisher.stats().published() >= 2L * OrderScenario.MESSAGE_COUNT);
            }
        }
        finally
        {
            // The engine is closed first: nothing else can arrive. Then the publisher drains what
            // is left and flushes, which is what makes the SOW queries below deterministic.
            publisher.close();
        }
        final BridgeStats stats = publisher.stats();

        // --- what AMPS holds -------------------------------------------------------------------

        // One record per ClOrdID, found by the SOW key itself. A cancel/replace carries a NEW 11
        // and the previous one in 41, so five requests are five records, not three.
        assertEquals(scenario.clOrdIds(), clOrdIds, "the counterparty sent what the scenario says");
        for (int i = 0; i < clOrdIds.size(); i++)
        {
            final String clOrdId = clOrdIds.get(i);
            final String expectedType = scenario.msgTypes().get(i);
            final List<String> records =
                SowReader.query(amps.uri(), BridgeConfig.TOPIC_ORDERS, "/11 = '" + clOrdId + "'");

            assertEquals(1, records.size(),
                () -> "expected exactly one fix.orders record for " + clOrdId + " but got " +
                    records.stream().map(SowReader::printable).toList());
            final String record = records.get(0);
            assertAll(
                () -> assertEquals(expectedType, BridgeItSupport.field(record, 35),
                    () -> "wrong message type in " + SowReader.printable(record)),
                () -> assertEquals(clOrdId, BridgeItSupport.field(record, 11)),
                () -> assertEquals(version.beginString(), BridgeItSupport.field(record, 8),
                    "the payload is the message as it came off the session, BeginString included"),
                () -> assertEquals(BridgeItSupport.QFJ_COMP_ID, BridgeItSupport.field(record, 49)),
                () -> assertEquals(BridgeItSupport.ARTIO_COMP_ID, BridgeItSupport.field(record, 56)));
        }

        final long rawAfter = SowReader.countFromEpoch(
            amps.uri(), BridgeConfig.TOPIC_RAW, Duration.ofSeconds(3));
        assertEquals(OrderScenario.MESSAGE_COUNT, rawAfter - rawBefore,
            "the journal holds the five application messages and no session-level ones");

        assertAll(
            // Nothing in this class ever sends an execution report, and only a 35=8 is routed to
            // either of these. A record here would be a routing bug.
            () -> assertEquals(List.of(), SowReader.query(amps.uri(), BridgeConfig.TOPIC_EXECS)),
            () -> assertEquals(List.of(), SowReader.query(amps.uri(), BridgeConfig.TOPIC_ORDER_STATE)));

        // --- what the bridge says it did --------------------------------------------------------

        assertAll(
            () -> assertEquals(OrderScenario.MESSAGE_COUNT,
                stats.publishedTo(BridgeConfig.TOPIC_RAW), "every application message on the tape"),
            () -> assertEquals(OrderScenario.MESSAGE_COUNT,
                stats.publishedTo(BridgeConfig.TOPIC_ORDERS), "D, G and F, all carrying tag 11"),
            () -> assertEquals(0, stats.publishedTo(BridgeConfig.TOPIC_EXECS)),
            () -> assertEquals(0, stats.publishedTo(BridgeConfig.TOPIC_ORDER_STATE)),
            () -> assertEquals(2L * OrderScenario.MESSAGE_COUNT, stats.published()),
            () -> assertEquals(0, stats.dropped(), "the ring buffer never filled"),
            () -> assertEquals(0, stats.unroutable(), "every message carried its topic's SOW key"),
            () -> assertEquals(0, stats.publishErrors()),
            () -> assertEquals(0, stats.lostWhileDisconnected()),
            () -> assertEquals(0, stats.reconnects()),
            // The Logon reaches the sink - Artio delivers session-level messages too - and is
            // dropped because publishAdminMessages is off by default.
            () -> assertTrue(stats.adminSkipped() >= 1,
                "the counterparty's Logon reached the sink and was dropped, not published"),
            () -> assertEquals(stats.accepted(), stats.drained(),
                "close() drained the ring buffer before flushing"),
            () -> assertEquals(0, stats.pendingBytes()));
    }
}
