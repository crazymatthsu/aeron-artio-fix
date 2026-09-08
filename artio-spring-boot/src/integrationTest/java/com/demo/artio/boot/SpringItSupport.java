package com.demo.artio.boot;

import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.qfj.OrderScenario;
import com.demo.artio.qfj.QfjConfig;
import com.demo.artio.qfj.QfjVersion;
import com.demo.artio.testharness.SowReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Free ports, a scratch directory outside the repository, the counterparty configuration, and the
 * SOW assertions both integration tests make.
 *
 * <p>The two tests differ only in <em>how</em> the application is started - a
 * {@code SpringApplication} in this JVM, or the fat jar in another - so everything about what the
 * flow should leave behind lives here, once. If the fat jar behaved differently from the in-process
 * context, {@link #assertOrderScenarioLanded} is where it would show.
 *
 * <p>Written in the spirit of {@code artio-amps-bridge}'s {@code BridgeItSupport} rather than
 * depending on it: a test source set is not a published artefact, and one module reaching into
 * another's is how build graphs rot.
 */
final class SpringItSupport
{
    /** The CompIDs in application.yml: the bridge is ARTIO, the counterparty is QFJ. */
    static final String ARTIO_COMP_ID = "ARTIO";

    /** @see #ARTIO_COMP_ID */
    static final String QFJ_COMP_ID = "QFJ";

    /** Long enough for a media driver, an archive, an engine, a library and a Spring context. */
    static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    /** Long enough for a message to cross loopback, an Aeron stream, a ring buffer and AMPS. */
    static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(20);

    private SpringItSupport()
    {
    }

    /** @return a TCP port the operating system has just confirmed is free. */
    static int freePort()
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Could not find a free TCP port", e);
        }
    }

    /**
     * Where each runtime puts its Aeron directory, archive and Artio log directory.
     *
     * <p>{@code java.io.tmpdir} rather than {@code build/}: Aeron leaves a mapped file behind if a
     * JVM is killed mid-test - and one of these tests kills a JVM on purpose - and nothing that
     * survives a crash should land inside the repository. Override with {@code -Dartio.it.dir}.
     *
     * @return the parent directory.
     */
    static Path baseDirectory()
    {
        final String override = System.getProperty("artio.it.dir", "");
        return Path.of(override.isBlank() ? System.getProperty("java.io.tmpdir") : override);
    }

    /**
     * @param port where the Spring application's acceptor is listening.
     * @return a QuickFIX/J FIX 4.2 initiator pointed at it, matching application.yml's CompIDs.
     */
    static QfjConfig initiatorConfig(final int port)
    {
        return QfjConfig.initiator()
            .version(QfjVersion.FIX42)
            .address("localhost", port)
            .senderCompId(QFJ_COMP_ID)
            .targetCompId(ARTIO_COMP_ID)
            .heartbeatIntervalSec(5)
            .build();
    }

    /**
     * Everything the drop copy stream must have left in AMPS, whichever way the application was
     * started: fifteen messages become forty publishes, five {@code fix.orders} records, ten
     * {@code fix.execs} records and three {@code fix.order.state} records (docs/07 section 3).
     *
     * @param uri       the harness AMPS URI.
     * @param scenario  the scenario that was run.
     * @param clOrdIds  the identifiers the counterparty reported sending.
     * @param rawBefore {@code fix.raw}'s journal count before the run.
     * @throws Exception if a query fails.
     */
    static void assertOrderScenarioLanded(
        final String uri,
        final OrderScenario scenario,
        final List<String> clOrdIds,
        final long rawBefore) throws Exception
    {
        assertEquals(scenario.clOrdIds(), clOrdIds, "the counterparty sent what the scenario says");

        // One SOW record per ClOrdID, found by the SOW key itself. A cancel/replace carries a NEW
        // tag 11 with the previous one in 41, so five requests are five records, not three. The ten
        // reports repeat those ClOrdIDs but only D, G and F are routed here, so they add none.
        for (int i = 0; i < clOrdIds.size(); i++)
        {
            final String clOrdId = clOrdIds.get(i);
            final String expectedType = scenario.orderSteps().get(i).msgType();
            final List<String> records =
                SowReader.query(uri, BridgeConfig.TOPIC_ORDERS, "/11 = '" + clOrdId + "'");

            assertEquals(1, records.size(),
                () -> "expected exactly one fix.orders record for " + clOrdId + " but got " +
                    records.stream().map(SowReader::printable).toList());
            final String record = records.get(0);
            assertAll(
                () -> assertEquals(expectedType, field(record, 35),
                    () -> "wrong message type in " + SowReader.printable(record)),
                () -> assertEquals(clOrdId, field(record, 11)),
                () -> assertEquals("FIX.4.2", field(record, 8),
                    "the payload is the message as it came off the session, BeginString included"),
                () -> assertEquals(QFJ_COMP_ID, field(record, 49)),
                () -> assertEquals(ARTIO_COMP_ID, field(record, 56)));
        }

        // fix.raw is journalled and cumulative: measure what this run added.
        final long rawAfter = SowReader.countFromEpoch(uri, BridgeConfig.TOPIC_RAW, Duration.ofSeconds(3));
        assertEquals(OrderScenario.MESSAGE_COUNT, rawAfter - rawBefore,
            "the journal holds the fifteen application messages and no session-level ones");

        // One fix.execs record per ExecID. Absolute rather than a delta: each test class starts its
        // own container on an emptied data directory, and the two classes run the same scenario, so
        // even if they shared one the ten ExecIDs would overwrite rather than accumulate.
        assertEquals(OrderScenario.EXECUTION_REPORT_COUNT,
            SowReader.query(uri, BridgeConfig.TOPIC_EXECS).size(), "one fix.execs record per ExecID");
        for (final String execId : scenario.execIds())
        {
            final List<String> records = SowReader.query(uri, BridgeConfig.TOPIC_EXECS, "/17 = '" + execId + "'");
            assertEquals(1, records.size(), () -> "expected exactly one fix.execs record for " + execId);
            assertEquals("8", field(records.get(0), 35),
                () -> "only a 35=8 belongs on fix.execs: " + SowReader.printable(records.get(0)));
        }

        // And the reason fix.order.state exists: ten publishes, three records, each the latest state
        // of one order. ORDER-1 was amended then completed, ORDER-2 cancelled after a partial fill,
        // ORDER-3 filled on its own.
        assertEquals(3, SowReader.query(uri, BridgeConfig.TOPIC_ORDER_STATE).size(),
            "ten publishes collapse into one record per OrderID");
        assertAll(
            () -> assertEquals("2", orderState(uri, "ORDER-1", 39), "ORDER-1 reads Filled"),
            () -> assertEquals("4", orderState(uri, "ORDER-2", 39), "ORDER-2 reads Canceled"),
            () -> assertEquals("2", orderState(uri, "ORDER-3", 39), "ORDER-3 reads Filled"),
            () -> assertEquals("EXEC-9", orderState(uri, "ORDER-1", 17),
                "the record is the last report for that order, not the first"),
            () -> assertEquals("EXEC-10", orderState(uri, "ORDER-2", 17)),
            () -> assertEquals("EXEC-7", orderState(uri, "ORDER-3", 17)));
    }

    /**
     * @param uri     the harness AMPS URI.
     * @param orderId the {@code OrderID(37)}.
     * @param tag     the tag to read.
     * @return that field of the order's one {@code fix.order.state} record.
     * @throws Exception if the query fails.
     */
    private static String orderState(final String uri, final String orderId, final int tag) throws Exception
    {
        final List<String> records =
            SowReader.query(uri, BridgeConfig.TOPIC_ORDER_STATE, "/37 = '" + orderId + "'");
        assertEquals(1, records.size(),
            () -> "expected exactly one fix.order.state record for " + orderId + " but got " +
                records.stream().map(SowReader::printable).toList());
        return field(records.get(0), tag);
    }

    /**
     * @param rawFix a raw FIX message.
     * @param tag    the tag to read.
     * @return the field's value, or null if the message does not carry it.
     */
    static String field(final String rawFix, final int tag)
    {
        for (final String part : rawFix.split("\001"))
        {
            final int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).chars().allMatch(Character::isDigit) &&
                Integer.parseInt(part.substring(0, equals)) == tag)
            {
                return part.substring(equals + 1);
            }
        }
        return null;
    }
}
