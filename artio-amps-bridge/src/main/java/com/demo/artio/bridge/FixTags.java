package com.demo.artio.bridge;

/**
 * The one piece of FIX parsing the bridge does: "is tag N present in these bytes?".
 *
 * <p>Nothing here allocates, and nothing here decodes. A FIX message is
 * {@code tag=value<SOH>tag=value<SOH>...}; a field therefore starts at the first byte of the
 * message or immediately after a SOH, and the tag is the run of digits before the first {@code =}
 * of that field. Scanning for {@code "11="} as a plain substring would be wrong twice over: it
 * matches inside a value (a {@code ClOrdID} of {@code X11=Y}), and it matches the tail of a longer
 * tag ({@code 111=}). Anchoring at field boundaries is what makes the answer trustworthy, and the
 * answer is what stands between a mis-tagged message and a silently truncated SOW - see
 * {@link TopicRoute}.
 */
public final class FixTags
{
    /** The FIX field separator. */
    public static final byte SOH = 1;

    private FixTags()
    {
    }

    /**
     * Is {@code tag} present as a field tag in the raw FIX message held in {@code data}?
     *
     * <p>Allocation-free and single-pass: at worst it reads every byte of the message once.
     *
     * @param data   the buffer holding the message.
     * @param offset the index of the first byte of the message - the {@code 8} of
     *               {@code 8=FIX.4.2}. The first field starts here, not after a separator.
     * @param length the message length in bytes.
     * @param tag    the FIX tag to look for, e.g. 11 for {@code ClOrdID}.
     * @return true if some field in the message has exactly this tag.
     */
    public static boolean containsTag(final byte[] data, final int offset, final int length, final int tag)
    {
        if (tag <= 0 || data == null || length <= 0)
        {
            return false;
        }
        final int end = offset + length;
        int fieldStart = offset;
        while (fieldStart < end)
        {
            if (tagAt(data, fieldStart, end) == tag)
            {
                return true;
            }
            // Skip to the byte after this field's separator. A trailing field with no SOH (a
            // truncated message) ends the scan, which is the right answer for a message that
            // cannot be trusted anyway.
            int i = fieldStart;
            while (i < end && data[i] != SOH)
            {
                i++;
            }
            fieldStart = i + 1;
        }
        return false;
    }

    /**
     * Reads the tag number of the field starting at {@code fieldStart}.
     *
     * @param data       the buffer.
     * @param fieldStart the index of the first byte of the field.
     * @param end        one past the last byte of the message.
     * @return the tag number, or -1 if the field does not start with digits followed by {@code =}.
     */
    private static int tagAt(final byte[] data, final int fieldStart, final int end)
    {
        int value = 0;
        int digits = 0;
        for (int i = fieldStart; i < end; i++)
        {
            final byte b = data[i];
            if (b == '=')
            {
                return digits == 0 ? -1 : value;
            }
            if (b < '0' || b > '9')
            {
                return -1;
            }
            // A tag longer than nine digits is not a FIX tag; stop rather than overflow.
            if (++digits > 9)
            {
                return -1;
            }
            value = value * 10 + (b - '0');
        }
        return -1;
    }
}
