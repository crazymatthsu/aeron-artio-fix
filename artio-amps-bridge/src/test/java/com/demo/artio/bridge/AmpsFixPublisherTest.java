package com.demo.artio.bridge;

import com.demo.artio.engine.FixMessageView;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RecordDescriptor;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void blockHoldsThePollThreadOnAFullRingAndLosesNothingThatDropAndCountWouldHaveDropped()
        throws InterruptedException
    {
        // An in-memory port keeps up with anything, so against it a 500-message burst never fills
        // an 8 KiB ring and "dropped == 0" holds under BOTH policies. The gate makes the ring fill:
        // the agent is stuck in its first publish, the poll thread finds the ring full, and what
        // happens next is the policy - BLOCK holds the poll thread, DROP_AND_COUNT drops.
        final int capacity = 8192;
        final GatedPort gated = new GatedPort();
        final BridgeConfig config = BridgeConfig.builder()
            .ringBufferCapacityBytes(capacity)
            .overflowPolicy(OverflowPolicy.BLOCK)
            .overflowBlockTimeoutMs(5_000)
            .build();
        final AmpsFixPublisher bridge = new AmpsFixPublisher(config, gated);
        publisher = bridge;
        bridge.start();

        final int burst = 500;
        final java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        final Thread pollThread = new Thread(
            () ->
            {
                for (int i = 0; i < burst; i++)
                {
                    bridge.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-" + i), "D", i));
                    sent.incrementAndGet();
                }
            },
            "fake-poll-thread");
        try
        {
            pollThread.start();

            // The ring fills; nothing leaves it while the gate is shut.
            await().atMost(TIMEOUT).until(() -> bridge.stats().ringUtilisation() > 0.9);
            Thread.sleep(200);
            final int heldAt = sent.get();
            assertAll(
                () -> assertTrue(heldAt < burst, "the poll thread is held on the full ring, at " + heldAt),
                () -> assertTrue(pollThread.isAlive()),
                () -> assertEquals(0, bridge.stats().dropped(), "held, not dropped"));

            gated.open();
            pollThread.join(TIMEOUT.toMillis());
            await().atMost(TIMEOUT).until(() -> gated.count(BridgeConfig.TOPIC_ORDERS) == burst);
            assertAll(
                () -> assertFalse(pollThread.isAlive(), "released once the agent caught up"),
                () -> assertEquals(0, bridge.stats().dropped()),
                () -> assertEquals(burst, bridge.stats().accepted()));
        }
        finally
        {
            gated.open();
        }
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
    void closeDrainsPastAPaddingRecordAtTheTailOfTheRing()
    {
        // When a record will not fit before the end of the buffer, agrona writes a PADDING record
        // at the tail and the message at index 0. A read that starts on that padding skips it,
        // moves the head to 0 and returns ZERO messages, with the real record still waiting for the
        // next call. A drain that stops at the first zero abandons everything after the padding and
        // then logs it as "not published".
        final int capacity = 1024;
        final RingBuffer ring = new OneToOneRingBuffer(new UnsafeBuffer(
            ByteBuffer.allocateDirect(capacity + RingBufferDescriptor.TRAILER_LENGTH)));
        final AmpsFixPublisher bridge = new AmpsFixPublisher(BridgeConfig.defaults(), port, ring);
        publisher = bridge;
        final MessageHandler discard = (typeId, buffer, index, length) -> { };

        // A ClOrdID chosen so that the aligned record length does not divide the capacity;
        // otherwise the tail lands exactly on the end and no padding is ever written.
        final byte[] order = TestFix.newOrderSingle("ORD-WRAP-1");
        bridge.onMessage(TestFix.view(order, "D", 1));
        final int recordLength = ring.size();
        assertEquals(1, ring.read(discard));
        assertTrue(capacity % recordLength != 0, "record length " + recordLength + " must not divide " + capacity);

        // Walk the tail towards the end: write one, consume one (the agent is not started, so
        // nothing else reads the ring), until the next record cannot fit before the end.
        while (capacity - (int)(ring.producerPosition() & (capacity - 1)) >= recordLength)
        {
            bridge.onMessage(TestFix.view(order, "D", 1));
            assertEquals(1, ring.read(discard));
        }
        assertEquals(0, ring.size(), "head and tail together, just short of the end");

        // This one wraps: padding at the tail, the record at index 0, and the head on the padding.
        bridge.onMessage(TestFix.view(order, "D", 2));
        final int headIndex = (int)(ring.consumerPosition() & (capacity - 1));
        assertAll(
            () -> assertEquals(RingBuffer.PADDING_MSG_TYPE_ID,
                ring.buffer().getInt(RecordDescriptor.typeOffset(headIndex)),
                "precondition: the head sits on a padding record"),
            () -> assertEquals(0, port.count(), "nothing has been published yet"));

        bridge.close();
        publisher = null;

        assertAll(
            () -> assertEquals(2, port.count(), "the wrapped order, on fix.raw and fix.orders"),
            () -> assertEquals(0, bridge.pending(), "nothing was abandoned behind the padding"));
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
    void theCallerThreadDrainKeepsItsOwnBudgetWhenTheAgentWaitHasSpentAllOfIts()
        throws InterruptedException
    {
        // close() first waits flushTimeoutMs for the agent to empty the ring. Here the agent is far
        // too slow for that, so the wait spends its whole budget; then the agent is stopped with
        // messages still in the ring. The drain on the closing thread that follows must have a
        // budget of its OWN: on a shared deadline it gets none, and everything the agent did not
        // reach is logged as "not published" without a single attempt.
        final SlowAgentPort port = new SlowAgentPort();
        final BridgeConfig config = BridgeConfig.builder().flushTimeoutMs(300).build();
        final AmpsFixPublisher bridge = new AmpsFixPublisher(config, port);
        publisher = bridge;
        bridge.start();
        try
        {
            // More than the agent hands over in one doWork (64), so when close() tells it to stop
            // it finishes the batch it is on and exits with the rest still in the ring.
            final int messages = 200;
            for (int i = 1; i <= messages; i++)
            {
                bridge.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-" + i), "D", i));
            }

            final Thread closer = new Thread(bridge::close, "closer");
            closer.start();

            // Step 1 spends its 300 ms; step 2 stops the agent; step 3 brings the closing thread
            // itself to the gate with the next message. It arrives there only if step 3 has time.
            await().atMost(TIMEOUT).until(() -> port.isWaiting(closer) || !closer.isAlive());
            assertTrue(closer.isAlive(),
                "the closing thread is draining on a budget of its own; instead close() finished with " +
                    port.count() + " publishes and " + bridge.pending() + " bytes still in the ring");

            port.open();
            closer.join(TIMEOUT.toMillis());
            assertAll(
                () -> assertFalse(closer.isAlive()),
                () -> assertEquals(2 * messages, port.count(), "every message on both topics; none abandoned"),
                () -> assertEquals(0, bridge.pending()),
                () -> assertEquals(messages, bridge.stats().drained()));
        }
        finally
        {
            port.open();
        }
    }

    @Test
    void startAfterCloseIsRefusedRatherThanLaunchingAnAgentNothingWillStop()
    {
        // Closed before it was ever started: without the guard, start() would pass its
        // compare-and-set and launch an agent thread that the already-spent close() can never
        // stop.
        final AmpsFixPublisher neverStarted = new AmpsFixPublisher(BridgeConfig.defaults(), port);
        neverStarted.close();

        // Started and closed: without the guard this start() is silently ignored, which at least
        // launches nothing, but a caller who restarts a closed publisher and then sends into it
        // deserves to be told.
        final AmpsFixPublisher startedOnce = new AmpsFixPublisher(BridgeConfig.defaults(), port);
        startedOnce.start();
        startedOnce.close();

        assertAll(
            () -> assertThrows(IllegalStateException.class, neverStarted::start),
            () -> assertThrows(IllegalStateException.class, startedOnce::start),
            () -> assertFalse(neverStarted.isRunning()),
            () -> assertFalse(startedOnce.isRunning()),
            () -> assertTrue(
                Thread.getAllStackTraces().keySet().stream().noneMatch(
                    thread -> thread.isAlive() && "amps-publisher".equals(thread.getName())),
                "no publisher agent thread outlives its publisher"));
    }

    @Test
    void theBacklogIsNeverNegativeEvenWhenTheAgentDrainsARecordBeforeTheProducerHasCountedIt()
    {
        // Between commit() making a record visible and the producer's bookkeeping there is a gap
        // the agent can read the record in. If `accepted` is incremented after the commit, a
        // stats() snapshot in that gap sees drained == 1 and accepted == 0: a backlog of -1. The
        // hook makes the gap deterministic - the agent is started and drains the record before
        // commit() returns to the producer.
        final CommitHookRingBuffer ring = new CommitHookRingBuffer(new OneToOneRingBuffer(new UnsafeBuffer(
            ByteBuffer.allocateDirect(4096 + RingBufferDescriptor.TRAILER_LENGTH))));
        final AmpsFixPublisher bridge = new AmpsFixPublisher(BridgeConfig.defaults(), port, ring);
        publisher = bridge;
        final List<Long> backlogSeenInTheGap = new ArrayList<>();
        ring.afterCommit = () ->
        {
            bridge.start();
            await().atMost(TIMEOUT).until(() -> bridge.stats().drained() == 1);
            backlogSeenInTheGap.add(bridge.stats().pendingMessages());
        };

        bridge.onMessage(TestFix.view(TestFix.newOrderSingle("ORD-GAP"), "D", 1));

        assertAll(
            () -> assertEquals(List.of(0L), backlogSeenInTheGap,
                "accepted was counted before the record became visible, so the gap shows 0, not -1"),
            () -> assertEquals(1, bridge.stats().accepted()));
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

    /**
     * An in-memory port whose publishes wait at a gate until the test opens it, so a test can make
     * the ring buffer fill up - something no in-memory port does on its own.
     *
     * <p>A thread that is interrupted while waiting publishes what it holds and carries on with the
     * interrupt flag set: {@code AgentRunner.close()} interrupts the agent and then joins it,
     * retrying forever if it never returns, so the gate must let an interrupted agent out.
     */
    private static final class GatedPort implements AmpsPublishPort
    {
        private final InMemoryPublishPort delegate = new InMemoryPublishPort();
        private final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicInteger waiting = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public boolean publish(
            final byte[] topic, final int topicLength, final byte[] data, final int offset, final int length)
        {
            waiting.incrementAndGet();
            try
            {
                gate.await();
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                waiting.decrementAndGet();
            }
            return delegate.publish(topic, topicLength, data, offset, length);
        }

        @Override
        public void flush(final long timeoutMs)
        {
            delegate.flush(timeoutMs);
        }

        @Override
        public void close()
        {
            delegate.close();
        }

        @Override
        public boolean isConnected()
        {
            return delegate.isConnected();
        }

        /** Lets every current and future publish through. */
        void open()
        {
            gate.countDown();
        }

        /** @return how many threads are at the gate right now. */
        int waiting()
        {
            return waiting.get();
        }

        int count()
        {
            return delegate.count();
        }

        int count(final String topic)
        {
            return delegate.count(topic);
        }
    }

    /**
     * An in-memory port that is slow for the publisher agent and shut for everyone else.
     *
     * <p>Publishes from the agent thread (named after the agent's role) each take a few
     * milliseconds - slow enough that {@code close()}'s wait for the agent spends its whole budget,
     * but not stuck: the agent has to finish the batch it is on so that {@code AgentRunner.close()}
     * can join it without waiting for its 5 s interrupt. Publishes from any other thread - the one
     * calling {@code close()} - wait at a gate until the test opens it, which is how the test sees
     * that the closing thread got as far as trying.
     */
    private static final class SlowAgentPort implements AmpsPublishPort
    {
        private static final long AGENT_PUBLISH_NS = 5_000_000L;

        private final InMemoryPublishPort delegate = new InMemoryPublishPort();
        private final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        private final java.util.Set<Thread> waiting = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean publish(
            final byte[] topic, final int topicLength, final byte[] data, final int offset, final int length)
        {
            final Thread current = Thread.currentThread();
            if ("amps-publisher".equals(current.getName()))
            {
                java.util.concurrent.locks.LockSupport.parkNanos(AGENT_PUBLISH_NS);
            }
            else
            {
                waiting.add(current);
                try
                {
                    gate.await();
                }
                catch (final InterruptedException e)
                {
                    current.interrupt();
                }
                finally
                {
                    waiting.remove(current);
                }
            }
            return delegate.publish(topic, topicLength, data, offset, length);
        }

        @Override
        public void flush(final long timeoutMs)
        {
            delegate.flush(timeoutMs);
        }

        @Override
        public void close()
        {
            delegate.close();
        }

        @Override
        public boolean isConnected()
        {
            return delegate.isConnected();
        }

        void open()
        {
            gate.countDown();
        }

        boolean isWaiting(final Thread thread)
        {
            return waiting.contains(thread);
        }

        int count()
        {
            return delegate.count();
        }
    }

    /**
     * A ring buffer that runs a hook after every {@code commit}, before the producer gets control
     * back - the window in which the consumer can already see the record. Everything else is
     * delegated; {@code OneToOneRingBuffer} is final, so this is a decorator rather than a subclass.
     */
    private static final class CommitHookRingBuffer implements RingBuffer
    {
        private final RingBuffer delegate;
        private Runnable afterCommit = () -> { };

        CommitHookRingBuffer(final RingBuffer delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void commit(final int index)
        {
            delegate.commit(index);
            afterCommit.run();
        }

        @Override
        public int capacity()
        {
            return delegate.capacity();
        }

        @Override
        public boolean write(final int msgTypeId, final org.agrona.DirectBuffer srcBuffer, final int offset, final int length)
        {
            return delegate.write(msgTypeId, srcBuffer, offset, length);
        }

        @Override
        public int tryClaim(final int msgTypeId, final int length)
        {
            return delegate.tryClaim(msgTypeId, length);
        }

        @Override
        public void abort(final int index)
        {
            delegate.abort(index);
        }

        @Override
        public int read(final MessageHandler handler)
        {
            return delegate.read(handler);
        }

        @Override
        public int read(final MessageHandler handler, final int messageCountLimit)
        {
            return delegate.read(handler, messageCountLimit);
        }

        @Override
        public int controlledRead(final org.agrona.concurrent.ControlledMessageHandler handler)
        {
            return delegate.controlledRead(handler);
        }

        @Override
        public int controlledRead(final org.agrona.concurrent.ControlledMessageHandler handler, final int messageCountLimit)
        {
            return delegate.controlledRead(handler, messageCountLimit);
        }

        @Override
        public int maxMsgLength()
        {
            return delegate.maxMsgLength();
        }

        @Override
        public long nextCorrelationId()
        {
            return delegate.nextCorrelationId();
        }

        @Override
        public org.agrona.concurrent.AtomicBuffer buffer()
        {
            return delegate.buffer();
        }

        @Override
        public void consumerHeartbeatTime(final long time)
        {
            delegate.consumerHeartbeatTime(time);
        }

        @Override
        public long consumerHeartbeatTime()
        {
            return delegate.consumerHeartbeatTime();
        }

        @Override
        public long producerPosition()
        {
            return delegate.producerPosition();
        }

        @Override
        public long consumerPosition()
        {
            return delegate.consumerPosition();
        }

        @Override
        public int size()
        {
            return delegate.size();
        }

        @Override
        public boolean unblock()
        {
            return delegate.unblock();
        }
    }
}
