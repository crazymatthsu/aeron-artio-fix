package com.demo.artio.qfj;

/**
 * Reading and printing raw FIX, without a dictionary.
 *
 * <p>Deliberately duplicated rather than shared with {@code com.demo.artio.engine.FixMessages}: this
 * module must not depend on the Artio module.
 */
public final class RawFix
{
    /** Start of Header (ASCII 1), the FIX field separator. */
    public static final char SOH = '\001';

    /** What SOH is rendered as when a message has to be readable. */
    public static final char PRINTABLE_SEPARATOR = '|';

    private RawFix()
    {
    }

    /**
     * @param rawFix a message with real SOH separators. May be null.
     * @return the same message with {@code |} separators, or null.
     */
    public static String printable(final String rawFix)
    {
        return rawFix == null ? null : rawFix.replace(SOH, PRINTABLE_SEPARATOR);
    }

    /**
     * Reads one field from a raw FIX message. Accepts either separator, so a message copied out of
     * a log works as well as one off the wire.
     *
     * @param rawFix the message. May be null.
     * @param tag    the tag number.
     * @return the field's value, or null if the tag is absent.
     */
    public static String field(final String rawFix, final int tag)
    {
        if (rawFix == null)
        {
            return null;
        }
        final String needle = tag + "=";
        int from = 0;
        while (from < rawFix.length())
        {
            final int at = rawFix.indexOf(needle, from);
            if (at < 0)
            {
                return null;
            }
            final char before = at == 0 ? SOH : rawFix.charAt(at - 1);
            if (before == SOH || before == PRINTABLE_SEPARATOR)
            {
                final int valueStart = at + needle.length();
                int valueEnd = valueStart;
                while (valueEnd < rawFix.length() &&
                    rawFix.charAt(valueEnd) != SOH && rawFix.charAt(valueEnd) != PRINTABLE_SEPARATOR)
                {
                    valueEnd++;
                }
                return rawFix.substring(valueStart, valueEnd);
            }
            from = at + 1;
        }
        return null;
    }

    /**
     * @param msgType a {@code MsgType(35)} value.
     * @return true for the seven FIX session-level message types ({@code 0 1 2 3 4 5 A}).
     */
    public static boolean isAdminMsgType(final String msgType)
    {
        return msgType != null && msgType.length() == 1 && "012345A".indexOf(msgType.charAt(0)) >= 0;
    }
}
