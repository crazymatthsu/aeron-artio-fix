package com.demo.artio.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AmpsPublishPort} that keeps everything in a list instead of sending it anywhere.
 *
 * <p>This is what makes almost all of the bridge testable without a container: the ring-buffer
 * hand-off, the routing, the overflow accounting and the shutdown drain are all exercised against
 * it, and only the four integration tests need a real AMPS.
 *
 * <p><strong>It copies.</strong> The publisher hands out slices of one reusable buffer that is
 * overwritten by the next message, so a port that stored the array would record five identical
 * messages and hide every bug worth catching.
 *
 * <p>Thread-safe: the publisher agent writes and the test thread reads.
 */
public final class InMemoryPublishPort implements AmpsPublishPort
{
    /**
     * One recorded publish.
     *
     * @param topic   the topic name, decoded for readability.
     * @param payload a private copy of the payload bytes.
     */
    public record Published(String topic, byte[] payload)
    {
        /** @return the payload as a String with its real SOH separators. */
        public String fix()
        {
            return new String(payload, StandardCharsets.US_ASCII);
        }

        /** @return the payload with SOH rendered as {@code |}, for a failure message. */
        public String printable()
        {
            return fix().replace('\001', '|');
        }

        @Override
        public String toString()
        {
            return topic + " <- " + printable();
        }
    }

    private final List<Published> records = new ArrayList<>();
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicInteger disconnectsRemaining = new AtomicInteger();

    private volatile boolean closed;
    private volatile boolean connected = true;

    @Override
    public boolean publish(
        final byte[] topic, final int topicLength, final byte[] data, final int offset, final int length)
    {
        if (failuresRemaining.get() > 0 && failuresRemaining.getAndDecrement() > 0)
        {
            throw new AmpsPublishException("injected publish failure");
        }
        if (disconnectsRemaining.get() > 0 && disconnectsRemaining.getAndDecrement() > 0)
        {
            return false;
        }
        final Published record = new Published(
            new String(topic, 0, topicLength, StandardCharsets.US_ASCII),
            Arrays.copyOfRange(data, offset, offset + length));
        synchronized (records)
        {
            records.add(record);
        }
        return true;
    }

    @Override
    public void flush(final long timeoutMs)
    {
        flushes.incrementAndGet();
    }

    @Override
    public void close()
    {
        closed = true;
        connected = false;
    }

    @Override
    public boolean isConnected()
    {
        return connected && !closed;
    }

    /** @return a snapshot of everything published, in publish order. */
    public List<Published> records()
    {
        synchronized (records)
        {
            return List.copyOf(records);
        }
    }

    /**
     * @param topic the topic to filter on.
     * @return everything published to {@code topic}, in order.
     */
    public List<Published> records(final String topic)
    {
        return records().stream().filter(record -> record.topic().equals(topic)).toList();
    }

    /** @return how many messages have been published in total. */
    public int count()
    {
        synchronized (records)
        {
            return records.size();
        }
    }

    /**
     * @param topic the topic to count.
     * @return how many messages were published to it.
     */
    public int count(final String topic)
    {
        return records(topic).size();
    }

    /** @return how many times {@link #flush(long)} was called. */
    public int flushes()
    {
        return flushes.get();
    }

    /** @return true if {@link #close()} has been called. */
    public boolean isClosed()
    {
        return closed;
    }

    /**
     * Makes the next {@code n} publishes throw, so the publisher's error counting can be exercised.
     *
     * @param n how many publishes should fail.
     */
    public void failNextPublishes(final int n)
    {
        failuresRemaining.set(n);
    }

    /**
     * Makes the next {@code n} publishes report themselves lost to a disconnect (return false)
     * rather than throwing.
     *
     * @param n how many publishes should report a disconnect.
     */
    public void disconnectNextPublishes(final int n)
    {
        disconnectsRemaining.set(n);
    }

    /** Forgets everything recorded so far. */
    public void clear()
    {
        synchronized (records)
        {
            records.clear();
        }
    }

    @Override
    public String toString()
    {
        return "InMemoryPublishPort{" + count() + " published, " + flushes.get() + " flush(es)}";
    }
}
