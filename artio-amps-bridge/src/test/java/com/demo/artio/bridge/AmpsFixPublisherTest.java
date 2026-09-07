package com.demo.artio.bridge;

import com.demo.artio.engine.FixMessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring-buffer hand-off, against an in-memory port: what the Artio poll thread writes, what the
 * publisher agent drains, what happens when the buffer is full, and what {@code close()} does.
 *
 * <p>No AMPS, no Artio runtime, no sockets. The {@link FixMessageView} is wrapped by hand, exactly
 * as {@code :artio-engine}'s own unit suite does.
 */
class AmpsFixPublisherTest
{
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final InMemoryPublishPort port = new InMemoryPublishPort();
    private AmpsFixPublisher publisher;

    @AfterEach
    void closePublisher()
    {
        if (publisher != null)
        {
            publisher.close();
        }
        port.close();
    }

    private AmpsFixPublisher started(final BridgeConfig config)
    {
        publisher = new AmpsFixPublisher(config, port);
        publisher.start();
        return publisher;
    }

    private void send(final AmpsFixPublisher target, final byte[] raw, final String msgType, final int seq)
    {
        target.onMessage(TestFix.view(raw, msgType, seq));
    }

    @Test
    void aMessageWrittenOnTheCallingThreadIsPublishedOnTheAgentThread()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());

        send(bridge, TestFix.newOrderSingle("ORD-1"), "D", 7);

        await().atMost(TIMEOUT).until(() -> port.count() == 2);
        assertAll(
            () -> assertEquals(List.of("fix.raw", "fix.orders"),
                port.records().stream().map(InMemoryPublishPort.Published::topic).toList()),
            () -> assertEquals(TestFix.fix("8=FIX.4.2|9=100|35=D|49=QFJ|56=ARTIO|34=7|11=ORD-1|" +
                    "55=MSFT|54=1|38=100|40=2|44=101.25|10=123|"),
                port.records().get(0).fix(),
                "the payload is the FIX bytes byte for byte, separators included"));
    }

    @Test
    void theBytesPublishedAreTheMessageOnlyEvenWhenItSitsAtAnOffsetInSomeoneElsesBuffer()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());
        final byte[] order = TestFix.newOrderSingle("ORD-OFFSET");

        // Artio hands out views into a shared log buffer; a publisher that ignored the offset would
        // publish whatever was in front of the message.
        bridge.onMessage(TestFix.view(new FixMessageView(), order, "D", 3, 128));

        await().atMost(TIMEOUT).until(() -> port.count() == 2);
        assertEquals(new String(order, java.nio.charset.StandardCharsets.US_ASCII),
            port.records().get(0).fix());
    }

    @Test
    void theFlyweightIsCopiedSoRewrappingItBetweenMessagesCannotCorruptWhatWasPublished()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());
        // One view reused for every message, which is exactly what ArtioRuntime does.
        final FixMessageView view = new FixMessageView();

        for (int i = 1; i <= 5; i++)
        {
            bridge.onMessage(TestFix.view(view, TestFix.newOrderSingle("ORD-" + i), "D", i, 0));
        }

        await().atMost(TIMEOUT).until(() -> port.count(BridgeConfig.TOPIC_ORDERS) == 5);
        assertEquals(
            List.of("ORD-1", "ORD-2", "ORD-3", "ORD-4", "ORD-5"),
            port.records(BridgeConfig.TOPIC_ORDERS).stream()
                .map(record -> field(record.fix(), 11))
                .toList());
    }

    @Test
    void anExecutionReportWithoutOrderIdReachesExecsAndTheTapeButNotOrderState()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());

        send(bridge, TestFix.executionReportWithoutOrderId("EXEC-1"), "8", 9);

        await().atMost(TIMEOUT).until(() -> port.count() == 2);
        final BridgeStats stats = bridge.stats();
        assertAll(
            () -> assertEquals(1, port.count(BridgeConfig.TOPIC_RAW)),
            () -> assertEquals(1, port.count(BridgeConfig.TOPIC_EXECS)),
            () -> assertEquals(0, port.count(BridgeConfig.TOPIC_ORDER_STATE)),
            () -> assertEquals(1, stats.unroutableFor(BridgeConfig.TOPIC_ORDER_STATE)),
            () -> assertEquals(1, stats.unroutable()));
    }

    @Test
    void aSessionLevelMessageIsDroppedByDefaultAndPublishedWhenAdminPublishingIsOn()
    {
        final AmpsFixPublisher dropping = started(BridgeConfig.defaults());
        send(dropping, TestFix.logon(), "A", 1);
        send(dropping, TestFix.newOrderSingle("ORD-1"), "D", 2);
        await().atMost(TIMEOUT).until(() -> port.count() == 2);
        assertAll(
            () -> assertEquals(0, port.count(BridgeConfig.TOPIC_ADMIN)),
            () -> assertEquals(2, port.count(), "the order, twice; the Logon, not at all"),
            () -> assertEquals(1, dropping.stats().adminSkipped()));
        dropping.close();

        final InMemoryPublishPort adminPort = new InMemoryPublishPort();
        try (AmpsFixPublisher publishing = new AmpsFixPublisher(
            BridgeConfig.builder().publishAdminMessages(true).build(), adminPort))
        {
            publishing.start();
            publishing.onMessage(TestFix.view(TestFix.logon(), "A", 1));

            await().atMost(TIMEOUT).until(() -> adminPort.count() == 1);
            assertEquals(BridgeConfig.TOPIC_ADMIN, adminPort.records().get(0).topic());
        }
    }

    @Test
    void underDropAndCountAFullRingBufferCostsMessagesAndSaysSo()
    {
        // A ring the agent is not draining: nothing is started, so everything written stays there.
        final BridgeConfig config = BridgeConfig.builder()
            .ringBufferCapacityBytes(2048)
            .overflowPolicy(OverflowPolicy.DROP_AND_COUNT)
            .build();
        publisher = new AmpsFixPublisher(config, port);

        final int attempts = 200;
        for (int i = 0; i < attempts; i++)
        {
            publisher.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-" + i), "D", i));
        }

        final BridgeStats stats = publisher.stats();
        assertAll(
            () -> assertTrue(stats.dropped() > 0, "a 2 KiB ring cannot hold 200 messages"),
            () -> assertEquals(attempts, stats.accepted() + stats.dropped(),
                "every message is either accepted or counted as dropped; none vanishes"),
            () -> assertEquals(0, stats.drained(), "the agent was never started"),
            () -> assertEquals(stats.accepted(), stats.pendingMessages()),
            () -> assertTrue(stats.pendingBytes() > 0),
            () -> assertTrue(stats.ringUtilisation() > 0.5, "the ring is nearly full"));
    }

    @Test
    void underBlockAFullRingBufferIsRetriedForTheConfiguredTimeAndThenDropped()
    {
        final BridgeConfig config = BridgeConfig.builder()
            .ringBufferCapacityBytes(2048)
            .overflowPolicy(OverflowPolicy.BLOCK)
            .overflowBlockTimeoutMs(50)
            .build();
        publisher = new AmpsFixPublisher(config, port);

        // Fill it without a consumer, then time one more write: BLOCK must wait its bound and give
        // up rather than wait forever.
        int accepted = 0;
        while (publisher.stats().dropped() == 0 && accepted < 500)
        {
            publisher.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-" + accepted), "D", accepted));
            accepted++;
        }
        final long droppedAfterFill = publisher.stats().dropped();

        final long startNs = System.nanoTime();
        publisher.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-LAST"), "D", 999));
        final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertAll(
            () -> assertTrue(droppedAfterFill > 0, "the ring did fill"),
            () -> assertEquals(droppedAfterFill + 1, publisher.stats().dropped(),
                "the message is dropped once the bound expires, not retried forever"),
            () -> assertTrue(elapsedMs >= 45, "it waited: " + elapsedMs + " ms"),
            () -> assertTrue(elapsedMs < 5_000, "and not much longer: " + elapsedMs + " ms"));
    }

    @Test
    void blockAbsorbsABurstThatDropAndCountWouldHaveLost()
    {
        // The same ring, the same load, with the agent draining: BLOCK trades poll-thread time for
        // messages, and here it should lose none.
        final BridgeConfig config = BridgeConfig.builder()
            .ringBufferCapacityBytes(8192)
            .overflowPolicy(OverflowPolicy.BLOCK)
            .overflowBlockTimeoutMs(5_000)
            .build();
        final AmpsFixPublisher bridge = started(config);

        for (int i = 0; i < 500; i++)
        {
            bridge.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-" + i), "D", i));
        }

        await().atMost(TIMEOUT).until(() -> port.count(BridgeConfig.TOPIC_ORDERS) == 500);
        assertEquals(0, bridge.stats().dropped());
    }

    @Test
    void aMessageLargerThanTheRingCanEverHoldIsDroppedRatherThanBlockingForever()
    {
        final BridgeConfig config = BridgeConfig.builder()
            .ringBufferCapacityBytes(2048)      // maxMsgLength is an eighth of this
            .overflowPolicy(OverflowPolicy.BLOCK)
            .overflowBlockTimeoutMs(2_000)
            .build();
        publisher = new AmpsFixPublisher(config, port);

        final byte[] huge = new byte[1024];
        java.util.Arrays.fill(huge, (byte)'x');
        final long startNs = System.nanoTime();
        publisher.onMessage(TestFix.view(huge, "D", 1));

        assertAll(
            () -> assertEquals(1, publisher.stats().dropped()),
            () -> assertTrue((System.nanoTime() - startNs) / 1_000_000L < 500,
                "no point waiting for room that can never exist"));
    }

    @Test
    void aPublishFailureIsCountedAndTheAgentCarriesOn()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());
        port.failNextPublishes(2);

        send(bridge, TestFix.newOrderSingle("ORD-1"), "D", 1);
        send(bridge, TestFix.newOrderSingle("ORD-2"), "D", 2);

        await().atMost(TIMEOUT).until(() -> bridge.stats().drained() == 2);
        await().atMost(TIMEOUT).until(() -> port.count() == 2);
        final BridgeStats stats = bridge.stats();
        assertAll(
            () -> assertEquals(2, stats.publishErrors(), "the two failed publishes"),
            () -> assertEquals(2, stats.published(), "and the two that followed them"),
            () -> assertEquals(2, stats.drained()));
    }

    @Test
    void closeDrainsWhatIsLeftFlushesAndIsIdempotent()
    {
        final AmpsFixPublisher bridge = new AmpsFixPublisher(BridgeConfig.defaults(), port);
        // Deliberately not started: everything written sits in the ring buffer, so close() has to
        // drain it itself rather than relying on the agent.
        for (int i = 1; i <= 10; i++)
        {
            bridge.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-" + i), "D", i));
        }
        assertEquals(0, port.count(), "nothing has been published yet");

        bridge.close();
        bridge.close();
        publisher = null;

        assertAll(
            () -> assertEquals(20, port.count(), "ten messages on two topics each"),
            () -> assertEquals(1, port.flushes(), "flushed once, and only once"),
            // The port was injected, so the publisher does not own it: closing the bridge must not
            // close someone else's connection.
            () -> assertFalse(port.isClosed(), "an injected port is the caller's to close"),
            () -> assertEquals(0, bridge.pending()));
    }

    @Test
    void closingAPublisherThatWasNeverStartedIsSafe()
    {
        final AmpsFixPublisher bridge = new AmpsFixPublisher(BridgeConfig.defaults(), port);

        bridge.close();
        publisher = null;

        assertAll(
            () -> assertEquals(0, port.count()),
            () -> assertFalse(bridge.isRunning()));
    }

    @Test
    void theStatsSnapshotAddsUpAcrossEveryRoute()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());

        send(bridge, TestFix.newOrderSingle("ORD-1"), "D", 1);
        send(bridge, TestFix.executionReport("EXEC-1", "ORDER-1"), "8", 2);
        send(bridge, TestFix.logon(), "A", 3);

        await().atMost(TIMEOUT).until(() -> port.count() == 5);
        final BridgeStats stats = bridge.stats();
        assertAll(
            () -> assertEquals(3, stats.accepted()),
            () -> assertEquals(3, stats.drained()),
            () -> assertEquals(0, stats.dropped()),
            // 1 order on 2 topics + 1 report on 3 topics + 1 admin on none.
            () -> assertEquals(5, stats.published()),
            () -> assertEquals(1, stats.adminSkipped()),
            () -> assertEquals(0, stats.unroutable()),
            () -> assertEquals(0, stats.publishErrors()),
            () -> assertEquals(2, stats.publishedTo(BridgeConfig.TOPIC_RAW)),
            () -> assertEquals(1, stats.publishedTo(BridgeConfig.TOPIC_ORDERS)),
            () -> assertEquals(1, stats.publishedTo(BridgeConfig.TOPIC_EXECS)),
            () -> assertEquals(1, stats.publishedTo(BridgeConfig.TOPIC_ORDER_STATE)),
            () -> assertEquals(0, stats.publishedTo("fix.nothing")),
            () -> assertTrue(stats.bytesPublished() > 0),
            () -> assertEquals(
                stats.routes().stream().mapToLong(BridgeStats.RouteStats::bytesPublished).sum(),
                stats.bytesPublished()),
            () -> assertTrue(stats.connected()),
            () -> assertTrue(stats.summary().contains("published=5"), stats.summary()));
    }

    @Test
    void startIsIdempotentAndOnlyEverStartsOneAgent()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());
        bridge.start();
        bridge.start();

        send(bridge, TestFix.newOrderSingle("ORD-1"), "D", 1);

        await().atMost(TIMEOUT).until(() -> port.count() == 2);
        assertAll(
            // Two agents on a single-consumer ring buffer would produce duplicates or corruption.
            () -> assertEquals(2, port.count()),
            () -> assertEquals(1, bridge.stats().drained()),
            () -> assertTrue(bridge.isRunning()));
    }

    @Test
    void aMessageLostToADisconnectIsNotCountedAsPublished()
    {
        final AmpsFixPublisher bridge = started(BridgeConfig.defaults());
        port.disconnectNextPublishes(2);

        send(bridge, TestFix.newOrderSingle("ORD-1"), "D", 1);
        send(bridge, TestFix.newOrderSingle("ORD-2"), "D", 2);

        await().atMost(TIMEOUT).until(() -> bridge.stats().drained() == 2);
        await().atMost(TIMEOUT).until(() -> port.count() == 2);
        final BridgeStats stats = bridge.stats();
        assertAll(
            () -> assertEquals(2, stats.published(), "two of the four route publishes got through"),
            () -> assertEquals(0, stats.publishErrors(), "a disconnect is not an error"),
            () -> assertEquals(2, stats.drained(), "both messages left the ring buffer"));
    }

    /** @return the value of {@code tag} in a raw FIX message, or null. */
    private static String field(final String rawFix, final int tag)
    {
        for (final String part : rawFix.split("\001"))
        {
            final int equals = part.indexOf('=');
            if (equals > 0 && Integer.parseInt(part.substring(0, equals)) == tag)
            {
                return part.substring(equals + 1);
            }
        }
        return null;
    }
}
