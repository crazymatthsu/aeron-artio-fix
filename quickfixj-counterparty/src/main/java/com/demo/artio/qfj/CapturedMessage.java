package com.demo.artio.qfj;

import quickfix.SessionID;

/**
 * One FIX message that passed through this engine, recorded as the raw string QuickFIX/J put on or
 * took off the wire.
 *
 * @param inbound   true if received, false if sent.
 * @param admin     true for a session-level message ({@code 0 1 2 3 4 5 A}).
 * @param msgType   the {@code MsgType(35)} value.
 * @param sessionId the QuickFIX/J session it belonged to.
 * @param rawFix    the message with real SOH separators.
 */
public record CapturedMessage(
    boolean inbound,
    boolean admin,
    String msgType,
    SessionID sessionId,
    String rawFix)
{
    /**
     * @param tag the tag number.
     * @return the field's value, or null if absent.
     */
    public String field(final int tag)
    {
        return RawFix.field(rawFix, tag);
    }

    /** @return the message with SOH rendered as {@code |}. */
    public String printable()
    {
        return RawFix.printable(rawFix);
    }

    @Override
    public String toString()
    {
        return (inbound ? "<- " : "-> ") + printable();
    }
}
