package com.demo.artio.bridge;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Message;
import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.FixMessageView;
import com.demo.artio.engine.FixVersion;
import com.demo.artio.engine.SessionKey;
import com.demo.artio.qfj.QfjInitiator;
import com.demo.artio.testharness.AmpsAssumptions;
import com.demo.artio.testharness.AmpsComposeServer;
import com.demo.artio.testharness.SowReader;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two routing rules that the order-flow test cannot reach, against a real AMPS:
 *
 * <ul>
 *   <li>session-level messages, which are dropped by default and go to {@code fix.admin} when
 *       {@code publishAdminMessages} is on;</li>
 *   <li>an {@code ExecutionReport} with no {@code OrderID(37)}, which must reach {@code fix.execs}
 *       and the tape but <strong>not</strong> {@code fix.order.state}. This is the case the whole
 *       tag-presence rule exists for: AMPS would have accepted it and silently stored it under the
 *       topic's one keyless record, so the assertion below is that the bridge stopped something the
 *       server would not have.</li>
 * </ul>
 *
 * <p>The execution report is fed straight into the sink over a hand-built buffer, in the same way
 * {@code artio-engine}'s {@code FixMessageViewTest} does, because the QuickFIX/J initiator in this
 * repository never sends one - and because a message deliberately missing a mandatory field is
 * easier to build by hand than to persuade a validating engine to emit.
 */
class BridgeRoutingAgainstAmpsIT
{
    private static final SessionKey SESSION = new SessionKey(7L, "ARTIO", "QFJ", "FIX.4.2", true);

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

    @Test
    void withAdminPublishingOnTheCounterpartysLogonArrivesOnTheAdminTopic() throws Exception
    {
        final int port = BridgeItSupport.freePort();
        final BridgeConfig config = BridgeItSupport.bridgeConfig(FixVersion.FIX42, amps.uri())
            .toBuilder()
            .publishAdminMessages(true)
            .build();

        // fix.admin has no SOW and is not journalled - deliberately: it is operational noise for a
        // live monitor, and Artio's own log is the durable record. So the only way to see it is to
        // be subscribed when it happens.
        final List<String> adminMessages = new CopyOnWriteArrayList<>();
        try (Client subscriber = new Client("artio-bridge-it-admin-watch-" + System.nanoTime()))
        {
            subscriber.connect(amps.uri());
            subscriber.logon(10_000);
            subscriber.subscribe(
                (Message message) ->
                {
                    if (!message.isDataNull())
                    {
                        adminMessages.add(message.getData());
                    }
                },
                BridgeConfig.TOPIC_ADMIN, 10_000L);

            final AmpsFixPublisher publisher = new AmpsFixPublisher(config);
            publisher.start();
            try
            {
                try (ArtioRuntime runtime = ArtioRuntime.launch(
                        BridgeItSupport.acceptorConfig(FixVersion.FIX42, port), publisher, null);
                    QfjInitiator qfj = new QfjInitiator(
                        BridgeItSupport.initiatorConfig(FixVersion.FIX42, port)))
                {
                    qfj.start();
                    assertTrue(qfj.awaitLogon(BridgeItSupport.STARTUP_TIMEOUT), "no logon");
                    await().atMost(BridgeItSupport.STARTUP_TIMEOUT)
                        .until(() -> !runtime.sessions().isEmpty());

                    await().atMost(BridgeItSupport.MESSAGE_TIMEOUT)
                        .until(() -> publisher.stats().publishedTo(BridgeConfig.TOPIC_ADMIN) >= 1);
                }
            }
            finally
            {
                publisher.close();
            }

            await().atMost(BridgeItSupport.MESSAGE_TIMEOUT).until(() -> !adminMessages.isEmpty());

            final String logon = adminMessages.stream()
                .filter(message -> "A".equals(BridgeItSupport.field(message, 35)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Logon on fix.admin, only " +
                    adminMessages.stream().map(SowReader::printable).toList()));

            final BridgeStats stats = publisher.stats();
            assertAll(
                () -> assertEquals("A", BridgeItSupport.field(logon, 35)),
                () -> assertEquals(BridgeItSupport.QFJ_COMP_ID, BridgeItSupport.field(logon, 49)),
                () -> assertEquals("1", BridgeItSupport.field(logon, 34), "the session's first message"),
                () -> assertEquals(0, stats.adminSkipped(), "nothing is dropped when the topic is set"),
                // A session-level message goes to the admin topic and nowhere else; fix.raw is the
                // application tape.
                () -> assertEquals(0, stats.publishedTo(BridgeConfig.TOPIC_RAW),
                    "no application message was sent in this test"),
                () -> assertTrue(stats.publishedTo(BridgeConfig.TOPIC_ADMIN) >= 1),
                () -> assertEquals(0, stats.dropped()),
                () -> assertEquals(0, stats.publishErrors()));
        }
    }

    @Test
    void anExecutionReportWithoutOrderIdIsKeptOffTheOrderStateTopicThatAmpsWouldHaveAccepted()
        throws Exception
    {
        final String execIdWithKeys = "EXEC-KEYED-" + System.nanoTime();
        final String execIdNoOrderId = "EXEC-NOKEY-" + System.nanoTime();
        final String orderId = "ORDER-" + System.nanoTime();

        final BridgeStats stats;
        final AmpsFixPublisher publisher =
            new AmpsFixPublisher(BridgeItSupport.bridgeConfig(FixVersion.FIX42, amps.uri()));
        publisher.start();
        try
        {
            // A well-formed report first, so the topic is not empty for the wrong reason.
            publisher.onMessage(view(executionReport(execIdWithKeys, orderId), "8"));
            publisher.onMessage(view(executionReportWithoutOrderId(execIdNoOrderId), "8"));

            await().atMost(BridgeItSupport.MESSAGE_TIMEOUT)
                .until(() -> publisher.stats().drained() == 2);
        }
        finally
        {
            publisher.close();
        }
        stats = publisher.stats();

        final List<String> keyedOnOrderState = SowReader.query(
            amps.uri(), BridgeConfig.TOPIC_ORDER_STATE, "/37 = '" + orderId + "'");
        final List<String> allOnOrderState =
            SowReader.query(amps.uri(), BridgeConfig.TOPIC_ORDER_STATE);
        final List<String> keyedOnExecs = SowReader.query(
            amps.uri(), BridgeConfig.TOPIC_EXECS, "/17 = '" + execIdNoOrderId + "'");

        assertAll(
            // The report WITHOUT tag 37 reached the two topics whose keys it does carry...
            () -> assertEquals(1, keyedOnExecs.size(), "fix.execs is keyed on 17, which it has"),
            () -> assertEquals(execIdNoOrderId, BridgeItSupport.field(keyedOnExecs.get(0), 17)),
            // ...and not the one whose key it does not. AMPS would have taken it: the server does
            // not reject a keyless SOW publish, it stores every such message under one shared
            // record. See docs/05 section 8.1.
            () -> assertEquals(1, allOnOrderState.size(),
                () -> "fix.order.state should hold only the keyed report, but holds " +
                    allOnOrderState.stream().map(SowReader::printable).toList()),
            () -> assertEquals(1, keyedOnOrderState.size()),
            () -> assertEquals(orderId, BridgeItSupport.field(keyedOnOrderState.get(0), 37)),
            // ...and it was counted, not dropped in silence.
            () -> assertEquals(1, stats.unroutableFor(BridgeConfig.TOPIC_ORDER_STATE)),
            () -> assertEquals(1, stats.unroutable()),
            () -> assertEquals(1, stats.publishedTo(BridgeConfig.TOPIC_ORDER_STATE)),
            () -> assertEquals(2, stats.publishedTo(BridgeConfig.TOPIC_EXECS)),
            // Both reports are on the tape, so nothing is actually lost: fix.raw is unkeyed and
            // journalled precisely so that a message a rule declines is still recoverable.
            () -> assertEquals(2, stats.publishedTo(BridgeConfig.TOPIC_RAW)),
            () -> assertEquals(2, SowReader.countFromEpoch(
                amps.uri(), BridgeConfig.TOPIC_RAW, Duration.ofSeconds(3))),
            () -> assertEquals(0, stats.dropped()),
            () -> assertEquals(0, stats.publishErrors()));
    }

    // ---------------------------------------------------------------------------------- fixtures

    private static FixMessageView view(final byte[] raw, final String msgType)
    {
        // A non-zero offset, because Artio hands out views into a shared log buffer and a publisher
        // that ignored it would ship the wrong bytes.
        final int offset = 96;
        final byte[] backing = new byte[offset + raw.length + 16];
        System.arraycopy(raw, 0, backing, offset, raw.length);
        return new FixMessageView().wrap(
            new UnsafeBuffer(backing), offset, raw.length,
            MessageTypeEncoding.packMessageType(msgType), 2, 0, System.nanoTime(), 3, true, SESSION);
    }

    private static byte[] executionReport(final String execId, final String orderId)
    {
        return fix("8=FIX.4.2|9=150|35=8|49=QFJ|56=ARTIO|34=2|37=" + orderId + "|17=" + execId +
            "|11=ORD-1|20=0|150=0|39=0|55=MSFT|54=1|151=100|14=0|6=0|10=201|");
    }

    /** The same report with tag 37 removed - the message {@code fix.order.state} must refuse. */
    private static byte[] executionReportWithoutOrderId(final String execId)
    {
        return fix("8=FIX.4.2|9=140|35=8|49=QFJ|56=ARTIO|34=3|17=" + execId +
            "|11=ORD-1|20=0|150=0|39=0|55=MSFT|54=1|151=100|14=0|6=0|10=202|");
    }

    private static byte[] fix(final String printable)
    {
        return printable.replace('|', '\001').getBytes(StandardCharsets.US_ASCII);
    }
}
