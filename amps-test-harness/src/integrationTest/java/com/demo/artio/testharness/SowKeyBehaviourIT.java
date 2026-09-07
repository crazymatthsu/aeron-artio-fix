package com.demo.artio.testharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crankuptheamps.client.Client;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What AMPS really does with a publish to a SOW topic that does not carry the
 * topic's key field.
 *
 * <p>This is here because {@code docs/00} section 2 records, as a verified
 * fact, that such a publish is REJECTED - and on 5.3.5.135 with
 * {@code MessageType fix} it is not. The difference is the whole design of the
 * bridge's {@code TopicRouter}: if the server refused these, a mis-routed
 * message would be a countable error; because it accepts them, a mis-routed
 * message is silent data loss and the router is the only guard there is.
 *
 * <p>An executable statement of the behaviour, rather than a sentence in a
 * document, because the next AMPS version may well change it - and if it does,
 * this fails and says so.
 */
class SowKeyBehaviourIT {

    /** Keyed {@code /37} (OrderID), and fed only 35=8 by design. */
    private static final String KEYED_TOPIC = "fix.order.state";

    private static final char SOH = '\u0001';

    private static AmpsComposeServer amps;

    @BeforeAll
    static void startAmps() throws Exception {
        AmpsAssumptions.assumeAvailable();
        amps = AmpsComposeServer.start("artio-fix");
    }

    @AfterAll
    static void stopAmps() {
        if (amps != null) {
            amps.close();
        }
    }

    @Test
    void aPublishMissingTheTopicsKeyFieldIsAcceptedRatherThanRejected() throws Exception {
        // If this ever starts throwing, the folklore has become true and the
        // TopicRouter's job gets easier. Until then it does not.
        try (Client client = new Client("sow-key-behaviour-accepted")) {
            client.connect(amps.uri());
            client.logon(10_000);
            client.publish(KEYED_TOPIC, executionReport("ACCEPTED-1", null));
            client.publishFlush(10_000);
        }

        List<String> records = SowReader.query(amps.uri(), KEYED_TOPIC, null);

        assertTrue(records.size() >= 1,
                "AMPS 5.3.5.135 stores a keyless publish instead of refusing it; if this "
                        + "topic is empty, the server now rejects them and docs/05 section "
                        + "8.1 needs revisiting");
    }

    @Test
    void severalKeylessPublishesCollapseOntoOneRecordSoOnlyTheLastSurvives() throws Exception {
        // The reason the router matters. Three distinct execution reports, all
        // missing tag 37, leave ONE record behind: no error, no log line, and a
        // SOW that merely looks short.
        try (Client client = new Client("sow-key-behaviour-collapse")) {
            client.connect(amps.uri());
            client.logon(10_000);
            for (int i = 1; i <= 3; i++) {
                client.publish(KEYED_TOPIC, executionReport("COLLAPSE-" + i, null));
            }
            client.publishFlush(10_000);
        }

        // Filtered in Java rather than with a server-side filter: the point of
        // the assertion is how many RECORDS exist, and reaching that through
        // the same filter engine the records were mis-keyed by would be
        // circular.
        List<String> survivors = SowReader.query(amps.uri(), KEYED_TOPIC, null).stream()
                .map(SowReader::printable)
                .filter(record -> record.contains("|17=COLLAPSE-"))
                .toList();

        assertEquals(1, survivors.size(),
                () -> "three keyless publishes must leave exactly one record, got " + survivors);
        assertTrue(survivors.get(0).contains("|17=COLLAPSE-3|"),
                () -> "the survivor is the LAST one published: " + survivors.get(0));
    }

    @Test
    void aPublishCarryingTheKeyFieldGetsItsOwnRecord() throws Exception {
        try (Client client = new Client("sow-key-behaviour-keyed")) {
            client.connect(amps.uri());
            client.logon(10_000);
            client.publish(KEYED_TOPIC, executionReport("KEYED-1", "ORDER-A"));
            client.publish(KEYED_TOPIC, executionReport("KEYED-2", "ORDER-B"));
            client.publishFlush(10_000);
        }

        assertEquals(1, SowReader.query(amps.uri(), KEYED_TOPIC, "/37 = 'ORDER-A'").size());
        assertEquals(1, SowReader.query(amps.uri(), KEYED_TOPIC, "/37 = 'ORDER-B'").size());
    }

    @Test
    void fixOrderStateIsNotJournalledSoTheEpochReplayIsEmpty() throws Exception {
        // The other half of the topic design, and the check that
        // countFromEpoch is really reading the transaction log rather than the
        // SOW: everything above was published to this topic, and none of it is
        // replayable.
        long replayed = SowReader.countFromEpoch(amps.uri(), KEYED_TOPIC, Duration.ofSeconds(2));

        assertEquals(0L, replayed,
                "fix.order.state is deliberately absent from <TransactionLog> because it is "
                        + "derivable from fix.execs");
    }

    /** A 35=8 with ExecID {@code execId}, and OrderID only when one is given. */
    private static String executionReport(String execId, String orderId) {
        StringBuilder body = new StringBuilder()
                .append("35=8").append(SOH)
                .append("49=ARTIO").append(SOH)
                .append("56=COUNTERPARTY").append(SOH)
                .append("34=1").append(SOH)
                .append("17=").append(execId).append(SOH);
        if (orderId != null) {
            body.append("37=").append(orderId).append(SOH);
        }
        body.append("150=0").append(SOH).append("39=0").append(SOH);
        return Fix42NewOrderSingle.frame(body.toString());
    }
}
