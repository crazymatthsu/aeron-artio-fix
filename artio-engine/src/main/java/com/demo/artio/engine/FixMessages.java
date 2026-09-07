package com.demo.artio.engine;

/**
 * Small helpers for looking at raw FIX. No state, no allocation beyond the Strings returned.
 */
public final class FixMessages
{
    /** Start of Header (ASCII 1), the FIX field separator. */
    public static final char SOH = '\001';

    /** What SOH is rendered as when a message has to be readable. */
    public static final char PRINTABLE_SEPARATOR = '|';

    private FixMessages()
    {
    }

    /**
     * Renders a raw FIX message readably by replacing every SOH with {@code |}.
     *
     * @param rawFix the message, SOH separated. May be null.
     * @return the printable form, or null if {@code rawFix} was null.
     */
    public static String printable(final String rawFix)
    {
        return rawFix == null ? null : rawFix.replace(SOH, PRINTABLE_SEPARATOR);
    }

    /**
     * The inverse of {@link #printable(String)}, for turning a message pasted from a log back into
     * wire bytes.
     *
     * @param printableFix the message with {@code |} separators. May be null.
     * @return the SOH separated form, or null if {@code printableFix} was null.
     */
    public static String fromPrintable(final String printableFix)
    {
        return printableFix == null ? null : printableFix.replace(PRINTABLE_SEPARATOR, SOH);
    }

    /**
     * Reads one field out of a raw FIX message. Linear scan, allocates one String; for tests and
     * diagnostics, not for a hot path.
     *
     * @param rawFix the message, SOH separated.
     * @param tag    the tag number to look for.
     * @return the field value, or null if the tag is absent.
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
            final boolean atFieldStart = at == 0 || rawFix.charAt(at - 1) == SOH ||
                rawFix.charAt(at - 1) == PRINTABLE_SEPARATOR;
            if (atFieldStart)
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
}
