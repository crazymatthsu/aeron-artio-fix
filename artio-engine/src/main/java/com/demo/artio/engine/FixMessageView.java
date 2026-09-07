package com.demo.artio.engine;

import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import uk.co.real_logic.artio.util.MessageTypeEncoding;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.nio.charset.StandardCharsets;

/**
 * A read-only, reusable flyweight over one FIX message sitting in Artio's Aeron log buffer.
 *
 * <h2>The contract</h2>
 * The view and the buffer behind it are valid <strong>only for the duration of the
 * {@link FixMessageSink#onMessage(FixMessageView)} call</strong>. Artio reuses the buffer for the
 * next message as soon as the callback returns. A sink must therefore:
 * <ul>
 *   <li>never store the view, or {@link #buffer()}, beyond the callback;</li>
 *   <li>copy out anything it needs to keep, with {@link #copyTo(byte[], int)} or
 *       {@link #toByteArray()};</li>
 *   <li>not block: the callback runs on the library poll thread, and while it is running no other
 *       session on this library is served and no heartbeat is sent.</li>
 * </ul>
 *
 * <h2>Allocation</h2>
 * Reading the view allocates nothing. {@link #msgTypeAsString()} interns each distinct message type
 * once in a small map, so the first {@code 35=D} allocates a two-character String and every later
 * one returns the same instance. {@link #toByteArray()}, {@link #toFixString()} and
 * {@link #toPrintableString()} do allocate and exist for diagnostics.
 */
public final class FixMessageView
{
    /**
     * The seven FIX session-level message types, packed with Artio's encoding so
     * {@link #isAdmin()} is a set lookup on a long rather than a String comparison.
     */
    private static final LongHashSet ADMIN_MESSAGE_TYPES = new LongHashSet();

    /** {@code Logon}, packed. */
    public static final long LOGON = MessageTypeEncoding.packMessageType("A");

    /** {@code Logout}, packed. */
    public static final long LOGOUT = MessageTypeEncoding.packMessageType("5");

    static
    {
        for (final String adminType : new String[] {"0", "1", "2", "3", "4", "5", "A"})
        {
            ADMIN_MESSAGE_TYPES.add(MessageTypeEncoding.packMessageType(adminType));
        }
    }

    /**
     * Is this packed message type one of the seven FIX session-level types
     * ({@code 0 1 2 3 4 5 A})? Static so callers holding only a packed type can ask.
     *
     * @param packedMessageType Artio's packed {@code MsgType(35)}.
     * @return true for Heartbeat, TestRequest, ResendRequest, Reject, SequenceReset, Logout, Logon.
     */
    public static boolean isAdminMessageType(final long packedMessageType)
    {
        return ADMIN_MESSAGE_TYPES.contains(packedMessageType);
    }

    private final Long2ObjectHashMap<String> messageTypeNames = new Long2ObjectHashMap<>();
    private final byte[] messageTypeBytes = new byte[MessageTypeEncoding.MAX_MESSAGE_TYPE_LENGTH];
    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();

    private DirectBuffer buffer;
    private int offset;
    private int length;
    private long messageType;
    private int sequenceNumber;
    private int sequenceIndex;
    private long timestampNs;
    private int libraryId;
    private boolean valid;
    private SessionKey sessionKey;
    private boolean asciiBufferWrapped;

    /**
     * Points this view at a message. Called by {@link ArtioRuntime} on the poll thread; public so a
     * test can build a view over a hand-made buffer without an engine.
     *
     * @param buffer         the buffer holding the message.
     * @param offset         where the message starts, i.e. the {@code 8=FIX...} byte.
     * @param length         the message length in bytes, including the checksum field.
     * @param messageType    Artio's packed {@code MsgType(35)}; see {@link MessageTypeEncoding}.
     * @param sequenceNumber {@code MsgSeqNum(34)}.
     * @param sequenceIndex  Artio's sequence index, incremented on every sequence number reset.
     * @param timestampNs    Artio's receive timestamp in nanoseconds.
     * @param libraryId      the id of the {@code FixLibrary} that received the message.
     * @param valid          Artio's verdict from {@code OnMessageInfo.isValid()}.
     * @param sessionKey     the session's identity.
     * @return this view.
     */
    public FixMessageView wrap(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long messageType,
        final int sequenceNumber,
        final int sequenceIndex,
        final long timestampNs,
        final int libraryId,
        final boolean valid,
        final SessionKey sessionKey)
    {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        this.messageType = messageType;
        this.sequenceNumber = sequenceNumber;
        this.sequenceIndex = sequenceIndex;
        this.timestampNs = timestampNs;
        this.libraryId = libraryId;
        this.valid = valid;
        this.sessionKey = sessionKey;
        this.asciiBufferWrapped = false;
        return this;
    }

    /**
     * Clears the view - buffer and metadata alike - so a stale reference cannot be read after the
     * callback: a retained view answers with no buffer, message type 0, sequence number 0, no
     * session and {@code isValid() == false} rather than with the previous message's values.
     */
    void unwrap()
    {
        buffer = null;
        offset = 0;
        length = 0;
        messageType = 0;
        sequenceNumber = 0;
        sequenceIndex = 0;
        timestampNs = 0;
        libraryId = 0;
        valid = false;
        sessionKey = null;
        asciiBufferWrapped = false;
    }

    /**
     * The buffer holding the message. Valid only inside the sink callback.
     *
     * @return the buffer, or null if the view is not currently wrapped.
     */
    public DirectBuffer buffer()
    {
        return buffer;
    }

    /** @return the index of the first byte of the message within {@link #buffer()}. */
    public int offset()
    {
        return offset;
    }

    /** @return the message length in bytes. */
    public int length()
    {
        return length;
    }

    /**
     * @return Artio's packed {@code MsgType(35)}. Compare against a generated decoder's
     * {@code MESSAGE_TYPE} constant, or unpack with {@link #msgTypeAsString()}.
     */
    public long msgType()
    {
        return messageType;
    }

    /**
     * The message type as text, e.g. {@code D} or {@code 8}. The String for each distinct type is
     * created once and cached, so repeated calls allocate nothing.
     *
     * @return the {@code MsgType(35)} value.
     */
    public String msgTypeAsString()
    {
        final String cached = messageTypeNames.get(messageType);
        if (cached != null)
        {
            return cached;
        }
        final int typeLength = MessageTypeEncoding.unpackMessageType(messageType, messageTypeBytes);
        final String name = new String(messageTypeBytes, 0, typeLength, StandardCharsets.US_ASCII);
        messageTypeNames.put(messageType, name);
        return name;
    }

    /** @return Artio's surrogate session id. */
    public long sessionId()
    {
        return sessionKey == null ? 0 : sessionKey.sessionId();
    }

    /** @return the session's identity; immutable and safe to keep. */
    public SessionKey sessionKey()
    {
        return sessionKey;
    }

    /**
     * @return {@code SenderCompID(49)} of this message, i.e. the counterparty for an inbound
     * message. Equivalent to {@code sessionKey().remoteCompId()}.
     */
    public String senderCompId()
    {
        return sessionKey == null ? null : sessionKey.remoteCompId();
    }

    /**
     * @return {@code TargetCompID(56)} of this message, i.e. this engine for an inbound message.
     * Equivalent to {@code sessionKey().localCompId()}.
     */
    public String targetCompId()
    {
        return sessionKey == null ? null : sessionKey.localCompId();
    }

    /** @return {@code MsgSeqNum(34)}. */
    public int sequenceNumber()
    {
        return sequenceNumber;
    }

    /** @return Artio's sequence index, which increments on every sequence number reset. */
    public int sequenceIndex()
    {
        return sequenceIndex;
    }

    /** @return Artio's receive timestamp in nanoseconds. */
    public long timestampNs()
    {
        return timestampNs;
    }

    /** @return the id of the {@code FixLibrary} that received the message. */
    public int libraryId()
    {
        return libraryId;
    }

    /**
     * @return Artio's verdict on the message. False means the session layer found a problem (an
     * unknown field, a failed validation) and has already sent, or will send, a {@code Reject}.
     */
    public boolean isValid()
    {
        return valid;
    }

    /**
     * @return true for the seven session-level message types ({@code 0 1 2 3 4 5 A}), false for
     * application messages such as {@code D} or {@code 8}.
     */
    public boolean isAdmin()
    {
        return isAdminMessageType(messageType);
    }

    /**
     * An {@code AsciiBuffer} wrapping exactly this message, for feeding a generated decoder:
     * {@code decoder.decode(view.asciiBuffer(), 0, view.length())}. The offset is always 0. The
     * wrapper is reused; like the view, it is only valid inside the callback.
     *
     * @return the reusable ASCII view of this message.
     */
    public MutableAsciiBuffer asciiBuffer()
    {
        if (!asciiBufferWrapped)
        {
            asciiBuffer.wrap(buffer, offset, length);
            asciiBufferWrapped = true;
        }
        return asciiBuffer;
    }

    /**
     * Copies the raw message bytes out of the Aeron buffer. This is how a sink keeps a message.
     *
     * @param destination the array to copy into.
     * @param destinationOffset where in {@code destination} to start.
     * @return the number of bytes copied, i.e. {@link #length()}.
     * @throws IndexOutOfBoundsException if {@code destination} is too small.
     */
    public int copyTo(final byte[] destination, final int destinationOffset)
    {
        buffer.getBytes(offset, destination, destinationOffset, length);
        return length;
    }

    /**
     * @return a fresh array holding the raw message. Allocates; prefer
     * {@link #copyTo(byte[], int)} on a hot path.
     */
    public byte[] toByteArray()
    {
        final byte[] bytes = new byte[length];
        buffer.getBytes(offset, bytes, 0, length);
        return bytes;
    }

    /**
     * @return the message as a String with its real SOH separators. Allocates; diagnostics only.
     */
    public String toFixString()
    {
        return new String(toByteArray(), StandardCharsets.US_ASCII);
    }

    /**
     * @return the message with SOH rendered as {@code |}, the form used in logs and test failures.
     * Allocates; diagnostics only.
     */
    public String toPrintableString()
    {
        return FixMessages.printable(toFixString());
    }

    @Override
    public String toString()
    {
        return "FixMessageView{" + sessionKey + " seq=" + sequenceNumber +
            " 35=" + msgTypeAsString() + (isAdmin() ? " admin" : " app") +
            (valid ? "" : " INVALID") + " length=" + length + '}';
    }
}
