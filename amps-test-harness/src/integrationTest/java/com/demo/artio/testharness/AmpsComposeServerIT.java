package com.demo.artio.testharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crankuptheamps.client.Client;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The harness proving itself against a real AMPS, so that nothing downstream
 * has to debug it.
 *
 * <p>One container for the class. Starting it costs a few seconds even on a
 * warm image - the AMPS build is amd64 and this machine emulates it - and
 * every assertion here is about the same published record, so a container per
 * method would buy isolation nobody needs.
 *
 * <p>The methods are order-independent on purpose, including the restart:
 * SOW state and journal history both survive a bounce, which is exactly what
 * {@link #recordsSurviveARestartOfTheContainer()} asserts, so a later method
 * seeing a restarted server sees the same answers.
 */
class AmpsComposeServerIT {

    private static final String FLOW = "artio-fix";

    /** Long enough for a replay that has nothing to replay to prove it. */
    private static final Duration REPLAY_IDLE = Duration.ofSeconds(2);

    private static AmpsComposeServer amps;
    private static String clOrdId;
    private static String order;

    @BeforeAll
    static void publishOneOrder() throws Exception {
        AmpsAssumptions.assumeAvailable();

        amps = AmpsComposeServer.start(FLOW);

        clOrdId = "ARTIO-IT-" + System.nanoTime();
        order = Fix42NewOrderSingle.message(clOrdId, 1);

        try (Client client = new Client("artio-harness-it-publisher")) {
            client.connect(amps.uri());
            client.logon(10_000);
            // fix.orders is a SOW keyed /11; fix.raw is plain pub/sub and is
            // journalled. The same bytes go to both, which is what the bridge
            // will do.
            client.publish("fix.orders", order);
            client.publish("fix.raw", order);
            // Publishes are asynchronous. Without this the SOW query below
            // races the publish and intermittently finds nothing.
            client.publishFlush(10_000);
        }
    }

    @AfterAll
    static void stopAmps() {
        if (amps != null) {
            amps.close();
        }
    }

    @Test
    void sowQueryByClOrdIdReturnsExactlyOneRecordWithTheBytesThatWerePublished()
            throws Exception {
        List<String> records = SowReader.query(amps.uri(), "fix.orders", "/11 = '" + clOrdId + "'");

        assertEquals(1, records.size(),
                () -> "expected one record keyed on ClOrdID " + clOrdId + ", got "
                        + records.stream().map(SowReader::printable).toList());
        assertEquals(order, records.get(0),
                "AMPS must return the FIX payload byte for byte, separators included");
    }

    @Test
    void theSowKeepsTheSohSeparatorsRatherThanNormalisingThePayload() throws Exception {
        List<String> records = SowReader.query(amps.uri(), "fix.orders", "/11 = '" + clOrdId + "'");

        String stored = records.get(0);
        assertTrue(stored.indexOf(SowReader.SOH) >= 0,
                "the stored record must still be raw FIX, not a re-encoded form: "
                        + SowReader.printable(stored));
        assertTrue(SowReader.printable(stored).contains("|11=" + clOrdId + "|"),
                SowReader.printable(stored));
    }

    @Test
    void aFilterThatMatchesNothingReturnsNoRecordsRatherThanEverything() throws Exception {
        // Guards the assertion above: if the filter were being ignored, the
        // "exactly one" test would pass for the wrong reason.
        List<String> records =
                SowReader.query(amps.uri(), "fix.orders", "/11 = 'NO-SUCH-CLORDID'");

        assertEquals(List.of(), records);
    }

    @Test
    void journalReplayFromEpochCountsWhatWasPublishedToFixRaw() throws Exception {
        long count = SowReader.countFromEpoch(amps.uri(), "fix.raw", REPLAY_IDLE);

        assertEquals(1L, count,
                "fix.raw is in <TransactionLog>, so a bookmark subscription from EPOCH "
                        + "must replay every message ever published to it");
    }

    @Test
    void recordsSurviveARestartOfTheContainer() throws Exception {
        amps.restart();

        List<String> records = SowReader.query(amps.uri(), "fix.orders", "/11 = '" + clOrdId + "'");

        assertEquals(1, records.size(), "the SOW file is on a host bind mount and must survive");
        assertEquals(order, records.get(0));
        assertEquals(1L, SowReader.countFromEpoch(amps.uri(), "fix.raw", REPLAY_IDLE),
                "the journal must survive too, or a replay-based rebuild would lose history");
    }

    @Test
    void theAdminPortIsMappedAndServesTheMonitoringInterface() throws Exception {
        // The third of the three free ports. Nothing else here would notice if
        // it were mapped to the wrong container port.
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(amps.adminUrl()))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), amps.adminUrl());
    }

    @Test
    void theInstanceIsIsolatedFromAnythingStartedByHand() {
        assertTrue(amps.project().startsWith("artio-amps-it-"), amps.project());
        assertTrue(amps.dataDir().toString().contains("build/amps-it"), amps.dataDir().toString());
        assertEquals("tcp://127.0.0.1:" + amps.port() + "/amps/fix", amps.uri());
    }
}
