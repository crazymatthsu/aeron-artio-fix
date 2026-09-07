package com.demo.artio.bridge;

import com.demo.artio.engine.FixMessageSink;
import com.demo.artio.engine.FixMessageView;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bridge: a {@link FixMessageSink} that publishes every FIX message Artio receives onto AMPS
 * topics, as raw bytes, without ever blocking Artio's poll thread.
 *
 * <h2>The hand-off</h2>
 * <pre>
 *   Artio poll thread                 off-heap ring buffer            publisher agent thread
 *   ─────────────────                 ────────────────────            ──────────────────────
 *   onMessage(view)  ──tryClaim──►  [type|session|seq|len|bytes]  ──read──►  route
 *   (copy, no alloc)                 OneToOneRingBuffer over an              copy into one
 *                                    UnsafeBuffer over a direct              reusable byte[]
 *                                    ByteBuffer                              port.publish(...)
 * </pre>
 *
 * <p>Why a ring buffer at all: the sink callback runs on the Artio library poll thread, and while
 * it runs, no other session on that library is served and no heartbeat is sent (see
 * {@code docs/01-artio-engine-design.md} section 7). A publish to AMPS is a socket write that can
 * block for as long as the network feels like. Those two facts are incompatible, and the ring
 * buffer is what separates them: the poll thread's whole obligation is a bounds check and a
 * {@code memcpy} into off-heap memory.
 *
 * <p>Off-heap, specifically {@code ByteBuffer.allocateDirect}, so that a 4 MiB backlog is not 4 MiB
 * of live heap that every young collection has to look at. Single-producer/single-consumer, so the
 * write path is a claim and a store-release with no CAS.
 *
 * <h2>What is published where</h2>
 * {@link TopicRouter}. In short: every application message to {@code fix.raw}, order requests to
 * {@code fix.orders}, execution reports to {@code fix.execs} and {@code fix.order.state}, and each
 * of those SOW topics only if the message really carries the tag that topic is keyed on - because
 * AMPS accepts a keyless SOW publish and silently collapses every such record into one.
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * AmpsFixPublisher publisher = new AmpsFixPublisher(BridgeConfig.defaults());
 * publisher.start();                                   // connects, starts the agent thread
 * try (ArtioRuntime runtime = ArtioRuntime.launch(engineConfig, publisher))
 * {
 *     ...
 * }                                                    // engine closed first: no more messages
 * publisher.close();                                   // drain, flush, disconnect
 * }</pre>
 *
 * <p>The order matters and is not interchangeable. Close the runtime first and the ring buffer
 * stops filling; then {@code close()} drains what is left, flushes AMPS and disconnects. Closing
 * the publisher first would leave Artio delivering messages into a stopped agent - they would be
 * counted as dropped, which is honest but pointless. Never flush concurrently with either.
 */
public final class AmpsFixPublisher implements FixMessageSink, AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AmpsFixPublisher.class);

    /** The only message type id in the ring buffer; agrona requires a positive one. */
    private static final int MESSAGE_TYPE_ID = 1;

    /** {@code [msgType long][sessionId long][seqNum int][length int]}. */
    private static final int HEADER_LENGTH = 8 + 8 + 4 + 4;
    private static final int MSG_TYPE_OFFSET = 0;
    private static final int SESSION_ID_OFFSET = 8;
    private static final int SEQ_NUM_OFFSET = 16;
    private static final int LENGTH_OFFSET = 20;

    /** Messages drained per {@code doWork}, so one burst cannot starve the idle strategy. */
    private static final int DRAIN_LIMIT = 64;

    /** The reusable publish buffer's starting size; a FIX order is a few hundred bytes. */
    private static final int INITIAL_PAYLOAD_CAPACITY = 4096;

    private final BridgeConfig config;
    private final AmpsPublishPort port;
    private final TopicRouter router;
    private final boolean ownsPort;

    private final RingBuffer ringBuffer;
    private final int ringCapacity;

    /** The Artio poll thread's own idle strategy, used only by {@link OverflowPolicy#BLOCK}. */
    private final IdleStrategy producerIdleStrategy;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong drained = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong publishErrors = new AtomicLong();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile AgentRunner agentRunner;

    // --- publisher-agent-thread state; touched by no other thread while the agent is running ---
    private byte[] payload = new byte[INITIAL_PAYLOAD_CAPACITY];
    private int payloadLength;
    private boolean currentIsAdmin;
    private final TopicRouter.RouteHandler publishHandler = this::publishTo;

    /**
     * The ring buffer's read callback, bound once. {@code doWork} runs once per spin of the idle
     * strategy, and a bound method reference written inline there is a fresh object per call -
     * under {@code BUSY_SPIN} that is an allocation per spin on a path that is meant to have none.
     */
    private final MessageHandler ringHandler = this::onRingMessage;

    /**
     * A publisher that talks to a real AMPS server.
     *
     * @param config where to connect and how to route.
     */
    public AmpsFixPublisher(final BridgeConfig config)
    {
        this(config, new AmpsClientPort(config), true, allocateRing(config));
    }

    /**
     * A publisher over an injected port, so unit tests need no AMPS.
     *
     * @param config where to route and how big the ring buffer is.
     * @param port   where messages go. The publisher does <strong>not</strong> close a port it was
     *               handed; whoever created it closes it.
     */
    public AmpsFixPublisher(final BridgeConfig config, final AmpsPublishPort port)
    {
        this(config, port, false, allocateRing(config));
    }

    /**
     * A publisher over an injected port <em>and</em> an injected ring buffer, so a test can put the
     * buffer into a precise state - a padding record at the tail with the head sitting on it, say -
     * and then watch what {@link #close()} makes of it. Package-private: nothing outside the tests
     * has a reason to see the ring.
     *
     * @param config     where to route.
     * @param port       where messages go; not closed by this publisher.
     * @param ringBuffer the hand-off buffer. Single producer, single consumer; the test must not
     *                   read from it while the agent is running.
     */
    AmpsFixPublisher(final BridgeConfig config, final AmpsPublishPort port, final RingBuffer ringBuffer)
    {
        this(config, port, false, ringBuffer);
    }

    private AmpsFixPublisher(
        final BridgeConfig config, final AmpsPublishPort port, final boolean ownsPort, final RingBuffer ringBuffer)
    {
        this.config = config;
        this.port = port;
        this.ownsPort = ownsPort;
        this.router = new TopicRouter(config);
        this.ringBuffer = ringBuffer;
        this.ringCapacity = ringBuffer.capacity();
        this.producerIdleStrategy = config.idleStrategy().create();
    }

    private static RingBuffer allocateRing(final BridgeConfig config)
    {
        return new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect(
            config.ringBufferCapacityBytes() + RingBufferDescriptor.TRAILER_LENGTH)));
    }

    /**
     * The longest {@link #close()} can take, for whoever has to wait for it - a shutdown hook, say.
     *
     * <p>Three of {@code close()}'s five steps are bounded by {@link BridgeConfig#flushTimeoutMs()}
     * each and get that budget separately: the wait for the agent, the caller-thread drain, and
     * the AMPS flush. Stopping the agent is bounded by agrona's
     * {@link AgentRunner#RETRY_CLOSE_TIMEOUT_MS} per attempt. Closing the port is a socket close.
     *
     * @param config the configuration the publisher was built from.
     * @return the worst-case duration of {@code close()}, in milliseconds.
     */
    public static long closeBudgetMs(final BridgeConfig config)
    {
        return 3 * config.flushTimeoutMs() + AgentRunner.RETRY_CLOSE_TIMEOUT_MS;
    }

    /**
     * Connects the port and starts the publisher agent thread. Idempotent while the publisher is
     * open.
     *
     * <p>A failure to reach AMPS is <strong>not</strong> fatal: it is logged, and the port keeps
     * retrying with back-off while the counters record what is being lost. A bridge that refused to
     * start because the message broker was briefly down would be the more surprising behaviour for
     * a FIX gateway, whose first duty is to stay logged on.
     *
     * @throws IllegalStateException if {@link #close()} has already been called. A publisher is
     *                               closed once: a start after that would launch an agent thread
     *                               that no later close could stop, against a port that may already
     *                               be disconnected.
     */
    public void start()
    {
        if (closed.get())
        {
            throw new IllegalStateException("this publisher has been closed; build a new one");
        }
        if (!started.compareAndSet(false, true))
        {
            return;
        }
        if (closed.get())
        {
            // Lost the race against a concurrent close(): it saw started == false and skipped the
            // agent, so no agent may be launched now either.
            throw new IllegalStateException("this publisher has been closed; build a new one");
        }
        if (port instanceof AmpsClientPort clientPort && !clientPort.connect())
        {
            LOGGER.warn("AMPS at {} is not reachable yet; the publisher will keep retrying",
                config.uri());
        }
        final PublisherAgent agent = new PublisherAgent();
        final AgentRunner runner = new AgentRunner(
            config.idleStrategy().create(),
            error -> LOGGER.error("publisher agent error", error),
            null,
            agent);
        agentRunner = runner;
        AgentRunner.startOnThread(runner);
        LOGGER.info("bridge publishing to {} via {}; ring buffer {} bytes, overflow {}",
            config.uri(), router.routes(), ringCapacity, config.overflowPolicy());
    }

    /**
     * Artio's callback, on the library poll thread.
     *
     * <p>Copies the message into the ring buffer and returns. Allocates nothing; touches no lock;
     * makes no system call. On a full ring it applies {@link BridgeConfig#overflowPolicy()}.
     *
     * @param message the flyweight, valid only for this call.
     */
    @Override
    public void onMessage(final FixMessageView message)
    {
        final int length = message.length();
        final int claimLength = HEADER_LENGTH + length;
        if (claimLength > ringBuffer.maxMsgLength())
        {
            // A single message larger than an eighth of the ring can never be written; a bigger
            // ring is the only fix, so say so rather than counting it forever in silence.
            dropped.incrementAndGet();
            LOGGER.warn("message of {} bytes exceeds the ring buffer's {} byte limit; dropped",
                length, ringBuffer.maxMsgLength());
            return;
        }

        if (tryWrite(message, claimLength, length))
        {
            return;
        }
        if (config.overflowPolicy() == OverflowPolicy.BLOCK && blockingWrite(message, claimLength, length))
        {
            return;
        }
        dropped.incrementAndGet();
    }

    /**
     * @return the ring buffer's current occupancy in bytes, including agrona's per-message framing.
     * Non-zero means the publisher is behind the session.
     */
    public int pending()
    {
        return ringBuffer.size();
    }

    /** @return an immutable snapshot of every counter. Safe to call from any thread. */
    public BridgeStats stats()
    {
        final List<BridgeStats.RouteStats> routes = new ArrayList<>(router.routes().size());
        long published = 0;
        long unroutable = 0;
        long bytes = 0;
        for (final TopicRouter.CompiledRoute route : router.routes())
        {
            final BridgeStats.RouteStats snapshot = route.snapshot();
            routes.add(snapshot);
            published += snapshot.published();
            unroutable += snapshot.unroutable();
            bytes += snapshot.bytesPublished();
        }
        return new BridgeStats(
            accepted.get(),
            drained.get(),
            dropped.get(),
            published,
            unroutable,
            router.adminSkipped(),
            publishErrors.get(),
            bytes,
            port.lostWhileDisconnected(),
            port.reconnects(),
            ringBuffer.size(),
            ringCapacity,
            port.isConnected(),
            routes);
    }

    /** @return the router, for tests and diagnostics. */
    public TopicRouter router()
    {
        return router;
    }

    /** @return the configuration this publisher was built from. */
    public BridgeConfig config()
    {
        return config;
    }

    /** @return true between {@link #start()} and {@link #close()}. */
    public boolean isRunning()
    {
        return started.get() && !closed.get();
    }

    /**
     * Drains what is left, flushes AMPS, and disconnects. Idempotent, and safe on a publisher that
     * was never started.
     *
     * <p>In order:
     * <ol>
     *   <li>wait up to {@link BridgeConfig#flushTimeoutMs()} for the agent to empty the ring
     *       buffer;</li>
     *   <li>stop the agent thread;</li>
     *   <li>drain anything still there on the calling thread, with its <em>own</em>
     *       {@code flushTimeoutMs} budget, so a slow agent cannot cost messages that were already
     *       accepted - if step 1 burnt its whole allowance, step 3 still gets a full one;</li>
     *   <li>{@code publishFlush}, again bounded by {@code flushTimeoutMs} - without which a SOW
     *       query races the publishes and finds nothing;</li>
     *   <li>close the port, if this publisher created it.</li>
     * </ol>
     *
     * <p>The sum of those bounds is {@link #closeBudgetMs(BridgeConfig)}.
     *
     * <p>Call it <strong>after</strong> closing the {@code ArtioRuntime}, never concurrently with
     * it.
     */
    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        final long flushTimeoutNs = config.flushTimeoutMs() * 1_000_000L;

        final AgentRunner runner = agentRunner;
        agentRunner = null;

        // 1. Let the agent finish on its own; it is already draining. Only worth waiting for if
        //    there is an agent: with none, nothing would ever empty the buffer and this would
        //    simply burn the whole timeout before step 3 did the work anyway.
        if (runner != null)
        {
            final long agentDeadline = System.nanoTime() + flushTimeoutNs;
            while (ringBuffer.size() > 0 && System.nanoTime() < agentDeadline)
            {
                Thread.onSpinWait();
                java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
            }
        }

        // 2. Stop the agent. AgentRunner.close() interrupts, joins and runs onClose().
        if (runner != null)
        {
            runner.close();
        }

        // 3. Anything the agent did not get to is drained here, on this thread. The agent is gone,
        //    so there is no second reader. A fresh budget: step 1 may have used all of its own on
        //    an agent that was stuck in a publish, and that is no reason to abandon what is here.
        final int remaining = drainOnCallingThread(System.nanoTime() + flushTimeoutNs);
        if (remaining > 0)
        {
            LOGGER.info("drained {} message(s) left in the ring buffer at close", remaining);
        }
        final int abandoned = ringBuffer.size();
        if (abandoned > 0)
        {
            LOGGER.warn("{} bytes were still in the ring buffer after {} ms; they were not published",
                abandoned, config.flushTimeoutMs());
        }

        // 4. Publishes are asynchronous. Without this a SOW query races them.
        try
        {
            port.flush(config.flushTimeoutMs());
        }
        catch (final RuntimeException e)
        {
            LOGGER.warn("AMPS flush at shutdown failed: {}", e.toString());
        }

        // 5. Only a port we created is ours to close.
        if (ownsPort)
        {
            port.close();
        }
        LOGGER.info("bridge closed: {}", stats().summary());
    }

    /**
     * Reads the ring buffer until it is empty, until nothing more can be read, or until the
     * deadline.
     *
     * <p>"Nothing more can be read" is decided by the occupancy, not by the read count. Agrona's
     * {@code read} returns 0 for two different reasons: a record the producer claimed and never
     * committed (which only happens if the poll thread died mid-write, and will never change), and
     * a padding record at the end of the buffer with the head sitting exactly on it - the reader
     * skips the padding, moves the head to index 0, and reports zero messages, with the real
     * records at the start of the buffer still waiting for the <em>next</em> call. The first leaves
     * {@code size()} unchanged; the second shrinks it. Breaking on {@code read == 0} alone abandons
     * everything after the padding and then logs it as "not published".
     *
     * @param deadlineNs when to give up, on {@link System#nanoTime()}'s clock.
     * @return how many messages were read.
     */
    private int drainOnCallingThread(final long deadlineNs)
    {
        int drainedHere = 0;
        while (System.nanoTime() < deadlineNs)
        {
            final int before = ringBuffer.size();
            if (before == 0)
            {
                break;
            }
            final int read = ringBuffer.read(ringHandler, DRAIN_LIMIT);
            drainedHere += read;
            if (read == 0 && ringBuffer.size() == before)
            {
                break;
            }
        }
        return drainedHere;
    }

    // ------------------------------------------------------------------- Artio poll thread (write)

    private boolean tryWrite(final FixMessageView message, final int claimLength, final int length)
    {
        final int index = ringBuffer.tryClaim(MESSAGE_TYPE_ID, claimLength);
        if (index <= 0)
        {
            return false;
        }
        try
        {
            final MutableDirectBuffer buffer = ringBuffer.buffer();
            buffer.putLong(index + MSG_TYPE_OFFSET, message.msgType());
            buffer.putLong(index + SESSION_ID_OFFSET, message.sessionId());
            buffer.putInt(index + SEQ_NUM_OFFSET, message.sequenceNumber());
            buffer.putInt(index + LENGTH_OFFSET, length);
            // Aeron's buffer to the ring buffer, off-heap to off-heap, no intermediate array.
            message.buffer().getBytes(message.offset(), buffer, index + HEADER_LENGTH, length);
        }
        catch (final RuntimeException e)
        {
            ringBuffer.abort(index);
            throw e;
        }
        // Counted BEFORE the commit makes the record visible. The other way round, the agent can
        // read the record and bump `drained` in the gap between commit and this increment, and a
        // stats() snapshot taken in that gap reports accepted - drained == -1.
        accepted.incrementAndGet();
        ringBuffer.commit(index);
        return true;
    }

    /**
     * {@link OverflowPolicy#BLOCK}: spin on the idle strategy while the agent catches up.
     *
     * @return true if the message was eventually written.
     */
    private boolean blockingWrite(final FixMessageView message, final int claimLength, final int length)
    {
        final long deadline = System.nanoTime() + config.overflowBlockTimeoutMs() * 1_000_000L;
        producerIdleStrategy.reset();
        while (System.nanoTime() < deadline)
        {
            producerIdleStrategy.idle();
            if (tryWrite(message, claimLength, length))
            {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- publisher agent thread (read)

    /** Drains the ring buffer, routes each message, and publishes it. */
    private final class PublisherAgent implements Agent
    {
        @Override
        public int doWork()
        {
            return ringBuffer.read(ringHandler, DRAIN_LIMIT);
        }

        @Override
        public String roleName()
        {
            return "amps-publisher";
        }
    }

    /**
     * One message off the ring buffer. Runs on the publisher agent thread (and, during
     * {@link #close()}, on the closing thread once the agent has stopped).
     */
    private void onRingMessage(
        final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length)
    {
        final long msgType = buffer.getLong(index + MSG_TYPE_OFFSET);
        final int payloadBytes = buffer.getInt(index + LENGTH_OFFSET);

        if (payloadBytes > payload.length)
        {
            // The only allocation on this path, and only when a message is bigger than every
            // message seen so far. Grows to the next power of two and stays there.
            payload = new byte[Math.max(INITIAL_PAYLOAD_CAPACITY, Integer.highestOneBit(payloadBytes - 1) << 1)];
        }
        buffer.getBytes(index + HEADER_LENGTH, payload, 0, payloadBytes);

        currentIsAdmin = FixMessageView.isAdminMessageType(msgType);
        payloadLength = payloadBytes;
        drained.incrementAndGet();

        router.route(msgType, currentIsAdmin, payload, 0, payloadBytes, publishHandler);
    }

    /** Publishes the message currently in {@link #payload} to one route. */
    private void publishTo(final TopicRouter.CompiledRoute route)
    {
        final byte[] topic = route.topicBytes();
        try
        {
            if (port.publish(topic, topic.length, payload, 0, payloadLength))
            {
                route.recordPublished(payloadLength);
            }
            // A false return is a disconnect; the port has counted it in lostWhileDisconnected.
        }
        catch (final RuntimeException e)
        {
            publishErrors.incrementAndGet();
            LOGGER.warn("publish of a {} byte {} message to {} failed: {}",
                payloadLength, currentIsAdmin ? "session-level" : "application", route.topic(),
                e.toString());
        }
    }
}
