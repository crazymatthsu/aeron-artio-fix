package com.demo.artio.bridge;

/**
 * The two AMPS calls the bridge makes, behind an interface, so that everything except one
 * integration test runs against a fake.
 *
 * <p>The surface is deliberately tiny: a byte-array publish and a flush. Both take arrays with
 * explicit offsets and lengths, because the publisher hands over slices of one reusable buffer and
 * never a copy.
 *
 * <h2>Threading</h2>
 * Every method is called from the publisher agent thread, except {@link #close()} and
 * {@link #flush(long)} during shutdown, which run on whichever thread called
 * {@link AmpsFixPublisher#close()} <em>after</em> the agent has stopped. An implementation
 * therefore needs no internal locking, but must make its counters safely readable from other
 * threads.
 */
public interface AmpsPublishPort extends AutoCloseable
{
    /**
     * Publishes one message.
     *
     * @param topic       the topic name in US-ASCII, starting at index 0.
     * @param topicLength the number of bytes of {@code topic} to use.
     * @param data        the raw FIX payload.
     * @param offset      the index of the first payload byte.
     * @param length      the payload length.
     * @return true if the message was handed to AMPS; false if it was lost because the port is not
     * connected. A false return is not an error - it is the reconnect path, and the loss is counted
     * by {@link #lostWhileDisconnected()}.
     * @throws AmpsPublishException if AMPS refused the message for any reason other than being
     *                              disconnected.
     */
    boolean publish(byte[] topic, int topicLength, byte[] data, int offset, int length);

    /**
     * Waits for AMPS to acknowledge everything published so far.
     *
     * <p>Not optional before reading the SOW back: publishes are asynchronous, and a query that
     * races them intermittently finds nothing.
     *
     * @param timeoutMs how long to wait.
     * @throws AmpsPublishException if the flush failed or timed out.
     */
    void flush(long timeoutMs);

    /** Releases the connection. Idempotent. */
    @Override
    void close();

    /** @return true if a publish right now would reach AMPS. */
    boolean isConnected();

    /**
     * How many messages were dropped because the port was disconnected when they arrived. Always
     * zero for a port that cannot disconnect.
     *
     * @return the count.
     */
    default long lostWhileDisconnected()
    {
        return 0;
    }

    /**
     * How many times this port has re-established its connection since it was created.
     *
     * @return the count.
     */
    default long reconnects()
    {
        return 0;
    }
}
