package com.demo.artio.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts what arrives and keeps a copy of each message, so a test on another thread can assert on
 * it. This is a test sink: it copies every message to the heap.
 *
 * <p>Writes happen on the library poll thread and reads on the test thread, so the counters are
 * {@link AtomicLong}s and the captured messages live in a {@link ConcurrentLinkedQueue}. A bound
 * keeps a runaway session from filling the heap; messages past the bound are counted but not kept.
 */
public final class CountingSink implements FixMessageSink
{
    /** Default number of messages kept. */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong admin = new AtomicLong();
    private final AtomicLong application = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final ConcurrentLinkedQueue<Captured> captured = new ConcurrentLinkedQueue<>();
    /**
     * Kept alongside the queue because {@link ConcurrentLinkedQueue#size()} is a linear walk, and
     * checking it once per message would make capturing N messages cost O(N^2).
     */
    private final AtomicLong capturedCount = new AtomicLong();
    private final int capacity;

    /** Keeps up to {@link #DEFAULT_CAPACITY} messages. */
    public CountingSink()
    {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity how many messages to keep. Further messages are counted only.
     */
    public CountingSink(final int capacity)
    {
        if (capacity < 0)
        {
            throw new IllegalArgumentException("capacity must not be negative but was " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public void onMessage(final FixMessageView message)
    {
        total.incrementAndGet();
        if (message.isAdmin())
        {
            admin.incrementAndGet();
        }
        else
        {
            application.incrementAndGet();
        }

        if (capturedCount.get() < capacity)
        {
            capturedCount.incrementAndGet();
            captured.add(new Captured(
                message.sessionId(),
                message.msgTypeAsString(),
                message.sequenceNumber(),
                message.isAdmin(),
                message.isValid(),
                message.toFixString()));
        }
        else
        {
            dropped.incrementAndGet();
        }
    }

    /** @return every message received, admin and application. */
    public long count()
    {
        return total.get();
    }

    /** @return session-level messages received ({@code 0 1 2 3 4 5 A}). */
    public long adminCount()
    {
        return admin.get();
    }

    /** @return application messages received. */
    public long applicationCount()
    {
        return application.get();
    }

    /** @return messages counted but not kept because {@code capacity} was reached. */
    public long droppedCount()
    {
        return dropped.get();
    }

    /** @return a snapshot of the kept messages, oldest first. */
    public List<Captured> messages()
    {
        return new ArrayList<>(captured);
    }

    /**
     * @param msgType the {@code MsgType(35)} value, e.g. {@code D}.
     * @return a snapshot of the kept messages of that type, oldest first.
     */
    public List<Captured> messagesOfType(final String msgType)
    {
        final List<Captured> result = new ArrayList<>();
        for (final Captured message : captured)
        {
            if (message.msgType().equals(msgType))
            {
                result.add(message);
            }
        }
        return result;
    }

    /**
     * @param msgType the {@code MsgType(35)} value.
     * @return how many messages of that type were kept.
     */
    public long countOfType(final String msgType)
    {
        return messagesOfType(msgType).size();
    }

    /** Resets the counters and forgets every kept message. */
    public void reset()
    {
        total.set(0);
        admin.set(0);
        application.set(0);
        dropped.set(0);
        captured.clear();
        capturedCount.set(0);
    }

    @Override
    public String toString()
    {
        return "CountingSink{total=" + count() + " app=" + applicationCount() +
            " admin=" + adminCount() + " dropped=" + droppedCount() + '}';
    }

    /**
     * One message, copied off the Aeron buffer.
     *
     * @param sessionId      Artio's surrogate session id.
     * @param msgType        the {@code MsgType(35)} value.
     * @param sequenceNumber {@code MsgSeqNum(34)}.
     * @param admin          true for a session-level message.
     * @param valid          Artio's verdict on the message.
     * @param rawFix         the raw message with real SOH separators.
     */
    public record Captured(
        long sessionId,
        String msgType,
        int sequenceNumber,
        boolean admin,
        boolean valid,
        String rawFix)
    {
        /**
         * @param tag the tag number.
         * @return the field's value, or null if absent.
         */
        public String field(final int tag)
        {
            return FixMessages.field(rawFix, tag);
        }

        /** @return the message with SOH rendered as {@code |}. */
        public String printable()
        {
            return FixMessages.printable(rawFix);
        }

        @Override
        public String toString()
        {
            return "35=" + msgType + " seq=" + sequenceNumber + ' ' + printable();
        }
    }
}
