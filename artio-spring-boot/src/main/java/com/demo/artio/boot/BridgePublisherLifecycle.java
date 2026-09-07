package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * Starts the AMPS publisher <strong>first</strong> and stops it <strong>last</strong>.
 *
 * <p>Spring's {@code DefaultLifecycleProcessor} starts phases in ascending order and stops them in
 * descending order, so the low phase here and the high phase in {@link ArtioRuntimeLifecycle}
 * produce exactly the ordering {@code BridgeMain} writes by hand:
 *
 * <pre>
 *   start:  publisher (MIN+1000)  ->  runtime (MAX-1000)
 *   stop:   runtime   (MAX-1000)  ->  publisher (MIN+1000)
 * </pre>
 *
 * <p>The order is not a preference. The publisher must be able to accept messages before Artio can
 * deliver any; and at shutdown the engine must stop before the publisher, so that the ring buffer
 * stops filling and {@code close()} can drain what is left and flush AMPS. Closing the publisher
 * first would leave Artio delivering into a stopped agent, and the tail of the session would be
 * counted as dropped. See docs/03 section 8.
 *
 * <p><strong>MIN_VALUE + 1000, not MIN_VALUE.</strong> The gap leaves room for a component that has
 * to outlive even this one - a metrics exporter that wants the final counters, say - without
 * having to renumber anything.
 */
public final class BridgePublisherLifecycle implements SmartLifecycle
{
    /** Low, so Spring starts this first and stops it last. */
    public static final int PHASE = Integer.MIN_VALUE + 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger(BridgePublisherLifecycle.class);

    private final AmpsFixPublisher publisher;

    /**
     * @param publisher the publisher to own; never closed by anything else, in particular not by an
     *                  inferred {@code destroyMethod}.
     */
    public BridgePublisherLifecycle(final AmpsFixPublisher publisher)
    {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public int getPhase()
    {
        return PHASE;
    }

    /**
     * Connects the port and starts the publisher agent thread.
     *
     * <p>An unreachable AMPS is deliberately <em>not</em> a start-up failure: {@code start()} logs
     * it and the port keeps retrying with back-off. A FIX gateway that refused to come up because
     * the message broker was briefly down would be the more surprising behaviour - its first duty
     * is to stay logged on to its counterparty.
     */
    @Override
    public void start()
    {
        publisher.start();
        LOGGER.info("amps publisher started: {} -> {}",
            publisher.config().uri(), publisher.router().routes());
    }

    /**
     * Drains the ring buffer, flushes AMPS and disconnects. Idempotent, because
     * {@link AmpsFixPublisher#close()} is.
     */
    @Override
    public void stop()
    {
        if (!publisher.isRunning())
        {
            return;
        }
        publisher.close();
        LOGGER.info("amps publisher drained and flushed: {}", publisher.stats().summary());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Synchronous on purpose: the callback tells Spring this phase is finished, so running it
     * before the flush completes would let the JVM proceed to exit with messages still in the ring
     * buffer - the exact failure the bridge exists to prevent. The {@code finally} makes sure a
     * throwing {@code close()} still releases the phase rather than costing the whole 30-second
     * shutdown timeout.
     *
     * @param callback run once the publisher really has flushed.
     */
    @Override
    public void stop(final Runnable callback)
    {
        try
        {
            stop();
        }
        finally
        {
            callback.run();
        }
    }

    @Override
    public boolean isRunning()
    {
        return publisher.isRunning();
    }

    /** @return the publisher this adapter owns. */
    public AmpsFixPublisher publisher()
    {
        return publisher;
    }
}
