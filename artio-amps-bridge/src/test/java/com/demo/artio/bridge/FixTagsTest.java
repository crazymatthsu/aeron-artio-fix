package com.demo.artio.bridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tag-presence scan. This is the whole guard between a mis-tagged message and a SOW topic that
 * silently collapses every keyless record onto one, so its edge cases are worth spelling out.
 */
class FixTagsTest
{
    private static final byte[] ORDER = TestFix.newOrderSingle("ORD-1");

    private static boolean has(final byte[] message, final int tag)
    {
        return FixTags.containsTag(message, 0, message.length, tag);
    }

    @Test
    void aTagInTheMiddleOfTheMessageIsFound()
    {
        assertTrue(has(ORDER, 11), "11=ORD-1 is the seventh field");
    }

    @Test
    void theFirstFieldIsFoundEvenThoughNoSeparatorPrecedesIt()
    {
        // 8=FIX.4.2 starts at the offset itself. A scan that only looked after separators would
        // miss it, and BeginString is the one tag every message has.
        assertTrue(has(ORDER, 8), "8= is the first field");
    }

    @Test
    void theLastFieldIsFoundEvenThoughItSitsAgainstTheEndOfTheMessage()
    {
        assertTrue(has(ORDER, 10), "10= is the checksum, the last field");
    }

    @Test
    void aTagThatIsNotPresentIsNotFound()
    {
        assertAll(
            () -> assertFalse(has(ORDER, 37), "a NewOrderSingle carries no OrderID"),
            () -> assertFalse(has(ORDER, 17), "nor an ExecID"));
    }

    @Test
    void aTagIsNotMatchedInsideAFieldValue()
    {
        // A ClOrdID that literally contains "11=" would fool a substring search into publishing a
        // message to a topic keyed on a tag it does not have.
        final byte[] message = TestFix.bytes("8=FIX.4.2|35=D|49=QFJ|58=x11=y|10=123|");

        assertFalse(has(message, 11), "11= appears only inside the value of tag 58");
    }

    @Test
    void aShortTagIsNotMatchedAsTheTailOfALongerOne()
    {
        // 111= must not answer "yes" for tag 11: the digits are anchored at the field start.
        final byte[] message = TestFix.bytes("8=FIX.4.2|35=D|111=ORD-1|10=123|");

        assertAll(
            () -> assertFalse(has(message, 11), "111 is not 11"),
            () -> assertTrue(has(message, 111)));
    }

    @Test
    void aTagIsFoundAtAnOffsetInsideALargerBuffer()
    {
        // Artio hands out views into a shared log buffer, so offset 0 is the unrealistic case.
        final byte[] backing = new byte[64 + ORDER.length + 32];
        System.arraycopy(ORDER, 0, backing, 64, ORDER.length);

        assertAll(
            () -> assertTrue(FixTags.containsTag(backing, 64, ORDER.length, 11)),
            () -> assertFalse(FixTags.containsTag(backing, 64, ORDER.length, 37)),
            // Nothing outside [offset, offset+length) is read: the zero bytes around the message
            // must not be parsed as fields.
            () -> assertFalse(FixTags.containsTag(backing, 64, 10, 11),
                "a length that stops before 11= must not find it"));
    }

    @Test
    void aFieldWithANonNumericTagIsSkippedRatherThanMisread()
    {
        final byte[] message = "abc=1\00111=ORD-1\001".getBytes(StandardCharsets.US_ASCII);

        assertTrue(has(message, 11), "a malformed first field must not stop the scan");
    }

    @Test
    void degenerateInputsAnswerFalseRatherThanThrowing()
    {
        assertAll(
            () -> assertFalse(FixTags.containsTag(ORDER, 0, ORDER.length, 0), "tag 0 does not exist"),
            () -> assertFalse(FixTags.containsTag(ORDER, 0, ORDER.length, -1)),
            () -> assertFalse(FixTags.containsTag(ORDER, 0, 0, 11), "an empty message has no tags"),
            () -> assertFalse(FixTags.containsTag(null, 0, 10, 11)));
    }

    @Test
    void aFieldWithNoEqualsSignIsNotATag()
    {
        final byte[] message = TestFix.bytes("8=FIX.4.2|11|55=MSFT|");

        assertFalse(has(message, 11), "'11' with no '=' is not a field");
    }
}
