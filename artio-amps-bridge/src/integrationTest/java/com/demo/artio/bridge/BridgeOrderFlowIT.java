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
 * The whole path, end to end, on both FIX versions: a QuickFIX/J initiator sends the fifteen-message
 * drop copy stream to an Artio acceptor whose sink is the bridge, and the records come back out of
 * AMPS.
 *
 * <p>Everything real: a container, a media driver, an Aeron archive, two FIX engines on loopback,
 * an off-heap ring buffer, an AMPS client, and a SOW.
 *
 * <p>Fifteen messages become forty publishes and eighteen records, which is the whole point of the
 * routing table (docs/07 section 3):
 *
 * <table border="1">
 *   <caption>What one run leaves behind</caption>
 *   <tr><th>Topic</th><th>SOW key</th><th>Publishes</th><th>Records</th></tr>
 *   <tr><td>{@code fix.raw}</td><td>none, journalled</td><td>15</td><td>n/a</td></tr>
 *   <tr><td>{@code fix.orders}</td><td>{@code /11}</td><td>5</td><td>5</td></tr>
 *   <tr><td>{@code fix.execs}</td><td>{@code /17}</td><td>10</td><td>10</td></tr>
 *   <tr><td>{@code fix.order.state}</td><td>{@code /37}</td><td>10</td><td>3</td></tr>
 * </table>
 *
 * <p>{@code fix.order.state} is the interesting one and the reason the topic exists: ten publishes
 * collapse into three records, one per order, each holding that order's latest state.
 *
 * <p>One container for the class, and the two parameterised runs share it. {@code ClOrdID}s are
 * prefixed per version so the two runs cannot see each other's {@code fix.orders} records, and the
 * journal count is a <em>delta</em> measured around the scenario rather than an absolute. The
 * scenario's {@code OrderID}s and {@code ExecID}s are <em>not</em> prefixed - they are
 * {@code ORDER-1}..{@code ORDER-3} and {@code EXEC-1}..{@code EXEC-10} in both runs, as the docs/07
 * table says - so on {@code /17} and {@code /37} the second run overwrites the first's records
 * rather than adding to them. That is why the counts on those two topics are exact absolutes (10
 * and 3, whichever run has just finished) and why every content assertion about them is made
 * immediately after this run's own publishes have been flushed.
 */
class BridgeOrderFlowIT
{
    /** How many orders the fifteen messages describe, and so how many {@code OrderID}s exist. */
    private static final int DISTINCT_ORDER_IDS = 3;

    /** 15 on fix.raw + 5 on fix.orders + 10 on fix.execs + 10 on fix.order.state. */
    private static final long EXPECTED_PUBLISHES = OrderScenario.MESSAGE_COUNT +
        OrderScenario.ORDER_COUNT + 2L * OrderScenario.EXECUTION_REPORT_COUNT;

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
    void theDropCopyStreamLeavesOneRecordPerClOrdIdTenPerExecIdThreePerOrderIdAndFifteenOnTheJournal(
        final FixVersion version) throws Exception
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

                // The bridge has seen and published everything: fifteen application messages on
                // fix.raw, five of them again on fix.orders, and the ten reports twice more - once
                // on fix.execs and once on fix.order.state. The Logon is dropped, so it is not in
                // the count.
                await().atMost(BridgeItSupport.MESSAGE_TIMEOUT)
                    .until(() -> publisher.stats().published() >= EXPECTED_PUBLISHES);
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
        // and the previous one in 41, so five requests are five records, not three. The ten reports
        // repeat those ClOrdIDs but are not routed here - only D, G and F are - so they cannot add
        // a record or overwrite one.
        assertEquals(scenario.clOrdIds(), clOrdIds, "the counterparty sent what the scenario says");
        for (int i = 0; i < clOrdIds.size(); i++)
        {
            final String clOrdId = clOrdIds.get(i);
            final String expectedType = scenario.orderSteps().get(i).msgType();
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
            "the journal holds the fifteen application messages and no session-level ones");

        // One record per ExecID: the reports are the only thing routed to fix.execs, every one of
        // them carries a distinct tag 17, and nothing ever overwrites one within a run.
        assertEquals(OrderScenario.EXECUTION_REPORT_COUNT,
            SowReader.query(amps.uri(), BridgeConfig.TOPIC_EXECS).size(),
            "one fix.execs record per ExecID");
        for (final String execId : scenario.execIds())
        {
            final List<String> records =
                SowReader.query(amps.uri(), BridgeConfig.TOPIC_EXECS, "/17 = '" + execId + "'");
            assertEquals(1, records.size(), () -> "expected exactly one fix.execs record for " + execId);
            assertEquals("8", BridgeItSupport.field(records.get(0), 35),
                () -> "only a 35=8 belongs on fix.execs: " + SowReader.printable(records.get(0)));
        }

        // And the topic the /37 keying exists for: ten publishes, three records, each holding the
        // order's latest state. ORDER-1 was amended and then completed, ORDER-2 was cancelled after
        // a partial fill, ORDER-3 filled on its own.
        assertEquals(DISTINCT_ORDER_IDS,
            SowReader.query(amps.uri(), BridgeConfig.TOPIC_ORDER_STATE).size(),
            "ten publishes collapse into one record per OrderID");
        assertAll(
            () -> assertEquals("2", latestOrdStatus("ORDER-1"), "ORDER-1 reads Filled"),
            () -> assertEquals("4", latestOrdStatus("ORDER-2"), "ORDER-2 reads Canceled"),
            () -> assertEquals("2", latestOrdStatus("ORDER-3"), "ORDER-3 reads Filled"),
            () -> assertEquals("EXEC-9", latestField("ORDER-1", 17),
                "the record is the last report for that order, not the first"),
            () -> assertEquals("EXEC-10", latestField("ORDER-2", 17)),
            () -> assertEquals("EXEC-7", latestField("ORDER-3", 17)),
            () -> assertEquals(version.beginString(), latestField("ORDER-1", 8),
                "and it is this run's record: the two runs share these keys"));

        // --- what the bridge says it did --------------------------------------------------------

        assertAll(
            () -> assertEquals(OrderScenario.MESSAGE_COUNT,
                stats.publishedTo(BridgeConfig.TOPIC_RAW), "every application message on the tape"),
            () -> assertEquals(OrderScenario.ORDER_COUNT,
                stats.publishedTo(BridgeConfig.TOPIC_ORDERS), "D, G and F, all carrying tag 11"),
            () -> assertEquals(OrderScenario.EXECUTION_REPORT_COUNT,
                stats.publishedTo(BridgeConfig.TOPIC_EXECS), "every 35=8 carries tag 17"),
            () -> assertEquals(OrderScenario.EXECUTION_REPORT_COUNT,
                stats.publishedTo(BridgeConfig.TOPIC_ORDER_STATE), "and every 35=8 carries tag 37"),
            () -> assertEquals(EXPECTED_PUBLISHES, stats.published()),
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

    /**
     * @param orderId the {@code OrderID(37)}.
     * @return {@code OrdStatus(39)} of that order's one {@code fix.order.state} record.
     * @throws Exception if the query fails.
     */
    private static String latestOrdStatus(final String orderId) throws Exception
    {
        return latestField(orderId, 39);
    }

    /**
     * @param orderId the {@code OrderID(37)}.
     * @param tag     the tag to read.
     * @return that field of the order's one {@code fix.order.state} record - the state left by the
     *         last report published for it, because the topic is keyed on {@code /37}.
     * @throws Exception if the query fails.
     */
    private static String latestField(final String orderId, final int tag) throws Exception
    {
        final List<String> records =
            SowReader.query(amps.uri(), BridgeConfig.TOPIC_ORDER_STATE, "/37 = '" + orderId + "'");
        assertEquals(1, records.size(),
            () -> "expected exactly one fix.order.state record for " + orderId + " but got " +
                records.stream().map(SowReader::printable).toList());
        return BridgeItSupport.field(records.get(0), tag);
    }
}
