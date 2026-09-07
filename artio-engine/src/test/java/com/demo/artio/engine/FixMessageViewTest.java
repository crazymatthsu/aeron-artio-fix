package com.demo.artio.engine;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flyweight, exercised over a buffer built by hand rather than by an engine: everything here
 * runs in microseconds and needs no ports, no Aeron and no counterparty.
 */
class FixMessageViewTest
{
    private static final String NEW_ORDER_SINGLE =
        "8=FIX.4.2\0019=100\00135=D\00149=QFJ\00156=ARTIO\00134=7\00111=ORD-1\00155=MSFT\001" +
            "54=1\00138=100\00140=2\00144=101.25\00110=123\001";

    private static final SessionKey SESSION =
        new SessionKey(42L, "ARTIO", "QFJ", "FIX.4.2", true);

    private final FixMessageView view = new FixMessageView();

    private FixMessageView wrap(final String rawFix, final String msgType, final int offset)
    {
        final byte[] messageBytes = rawFix.getBytes(StandardCharsets.US_ASCII);
        // A non-zero offset is the realistic case: Artio hands out a view into a shared log buffer.
        final byte[] backing = new byte[offset + messageBytes.length + 16];
        System.arraycopy(messageBytes, 0, backing, offset, messageBytes.length);
        return view.wrap(
            new UnsafeBuffer(backing), offset, messageBytes.length,
            MessageTypeEncoding.packMessageType(msgType), 7, 0, 1_234_000_000L, 3, true, SESSION);
    }

    @Test
    void theViewReadsTheMessageAtItsOffsetAndLengthNotTheWholeBuffer()
    {
        wrap(NEW_ORDER_SINGLE, "D", 64);

        assertAll(
            () -> assertEquals(64, view.offset()),
            () -> assertEquals(NEW_ORDER_SINGLE.length(), view.length()),
            () -> assertEquals(NEW_ORDER_SINGLE, view.toFixString()),
            () -> assertArrayEquals(NEW_ORDER_SINGLE.getBytes(StandardCharsets.US_ASCII), view.toByteArray()));
    }

    @Test
    void msgTypeAsStringUnpacksArtiosPackedLongAndReturnsTheSameInstanceEveryTime()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);
        final String first = view.msgTypeAsString();
        final String second = view.msgTypeAsString();

        assertAll(
            () -> assertEquals("D", first),
            // Cached, so a sink that asks per message allocates nothing after the first of a type.
            () -> assertSame(first, second));
    }

    @Test
    void msgTypeAsStringHandlesTwoCharacterTypesSuchAsAnExecutionAcknowledgement()
    {
        wrap(NEW_ORDER_SINGLE, "AE", 0);

        assertEquals("AE", view.msgTypeAsString());
    }

    @Test
    void aCachedMessageTypeStringSurvivesRewrappingWithADifferentMessage()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);
        final String firstWrap = view.msgTypeAsString();
        wrap(NEW_ORDER_SINGLE, "8", 0);
        assertEquals("8", view.msgTypeAsString());
        wrap(NEW_ORDER_SINGLE, "D", 0);

        assertSame(firstWrap, view.msgTypeAsString());
    }

    @Test
    void theSevenSessionLevelMessageTypesAreAdminAndApplicationTypesAreNot()
    {
        assertAll(
            () -> assertTrue(wrap(NEW_ORDER_SINGLE, "0", 0).isAdmin(), "Heartbeat"),
            () -> assertTrue(wrap(NEW_ORDER_SINGLE, "1", 0).isAdmin(), "TestRequest"),
            () -> assertTrue(wrap(NEW_ORDER_SINGLE, "2", 0).isAdmin(), "ResendRequest"),
            () -> assertTrue(wrap(NEW_ORDER_SINGLE, "3", 0).isAdmin(), "Reject"),
            () -> assertTrue(wrap(NEW_ORDER_SINGLE, "4", 0).isAdmin(), "SequenceReset"),
            () -> assertTrue(wrap(NEW_ORDER_SINGLE, "5", 0).isAdmin(), "Logout"),
            () -> assertTrue(wrap(NEW_ORDER_SINGLE, "A", 0).isAdmin(), "Logon"),
            () -> assertFalse(wrap(NEW_ORDER_SINGLE, "D", 0).isAdmin(), "NewOrderSingle"),
            () -> assertFalse(wrap(NEW_ORDER_SINGLE, "8", 0).isAdmin(), "ExecutionReport"),
            () -> assertFalse(wrap(NEW_ORDER_SINGLE, "9", 0).isAdmin(), "OrderCancelReject"));
    }

    @Test
    void logonAndLogoutConstantsMatchArtiosPackedEncoding()
    {
        assertAll(
            () -> assertEquals(MessageTypeEncoding.packMessageType("A"), FixMessageView.LOGON),
            () -> assertEquals(MessageTypeEncoding.packMessageType("5"), FixMessageView.LOGOUT),
            () -> assertTrue(FixMessageView.isAdminMessageType(FixMessageView.LOGON)),
            () -> assertFalse(FixMessageView.isAdminMessageType(MessageTypeEncoding.packMessageType("D"))));
    }

    @Test
    void senderAndTargetCompIdsAreReportedFromTheReceivedMessagesPointOfView()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);

        assertAll(
            // The inbound message says 49=QFJ 56=ARTIO; the session key calls those remote and local.
            () -> assertEquals("QFJ", view.senderCompId()),
            () -> assertEquals("ARTIO", view.targetCompId()),
            () -> assertSame(SESSION, view.sessionKey()),
            () -> assertEquals(42L, view.sessionId()));
    }

    @Test
    void sequenceNumberTimestampLibraryIdAndValidityArePassedThrough()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);

        assertAll(
            () -> assertEquals(7, view.sequenceNumber()),
            () -> assertEquals(0, view.sequenceIndex()),
            () -> assertEquals(1_234_000_000L, view.timestampNs()),
            () -> assertEquals(3, view.libraryId()),
            () -> assertTrue(view.isValid()));
    }

    @Test
    void copyToWritesExactlyTheMessageBytesAtTheGivenOffset()
    {
        wrap(NEW_ORDER_SINGLE, "D", 32);
        final byte[] destination = new byte[NEW_ORDER_SINGLE.length() + 8];

        final int copied = view.copyTo(destination, 4);

        assertAll(
            () -> assertEquals(NEW_ORDER_SINGLE.length(), copied),
            () -> assertEquals(NEW_ORDER_SINGLE,
                new String(destination, 4, copied, StandardCharsets.US_ASCII)),
            () -> assertEquals(0, destination[3], "nothing written before the offset"));
    }

    @Test
    void theAsciiBufferStartsAtZeroSoAGeneratedDecoderCanReadItDirectly()
    {
        wrap(NEW_ORDER_SINGLE, "D", 128);

        assertEquals(NEW_ORDER_SINGLE, view.asciiBuffer().getAscii(0, view.length()));
    }

    @Test
    void printableFormReplacesEverySohWithAPipe()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);

        assertAll(
            () -> assertEquals(NEW_ORDER_SINGLE.replace('\001', '|'), view.toPrintableString()),
            () -> assertFalse(view.toPrintableString().indexOf('\001') >= 0));
    }

    @Test
    void unwrappingClearsTheBufferSoAStaleViewCannotBeReadAfterTheCallback()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);
        view.unwrap();

        assertAll(
            () -> assertNull(view.buffer()),
            () -> assertEquals(0, view.length()),
            () -> assertEquals(0, view.offset()));
    }

    @Test
    void unwrappingAlsoClearsTheMetadataSoAStaleViewCannotBeMistakenForARealMessage()
    {
        wrap(NEW_ORDER_SINGLE, "D", 16);
        view.unwrap();

        assertAll(
            // Clearing only the buffer would leave a retained view answering msgType(),
            // sequenceNumber(), sessionKey() and isValid() with the last message's values - a
            // sink that kept the reference would report a message that is no longer there.
            () -> assertEquals(0, view.msgType()),
            () -> assertEquals(0, view.sequenceNumber()),
            () -> assertEquals(0, view.sequenceIndex()),
            () -> assertEquals(0, view.timestampNs()),
            () -> assertEquals(0, view.libraryId()),
            () -> assertFalse(view.isValid(), "an empty view is not a valid message"),
            () -> assertNull(view.sessionKey()),
            () -> assertFalse(view.isAdmin()));
    }

    @Test
    void aViewCanBeRewrappedAfterUnwrappingAndReadsTheNewMessageOnly()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);
        view.unwrap();
        wrap(NEW_ORDER_SINGLE, "A", 8);

        assertAll(
            () -> assertEquals("A", view.msgTypeAsString()),
            () -> assertTrue(view.isAdmin()),
            () -> assertEquals(7, view.sequenceNumber()),
            () -> assertSame(SESSION, view.sessionKey()),
            () -> assertEquals(NEW_ORDER_SINGLE, view.toFixString()));
    }

    @Test
    void toStringNamesTheSessionSequenceNumberMessageTypeAndWhetherItIsAdmin()
    {
        wrap(NEW_ORDER_SINGLE, "D", 0);

        final String description = view.toString();

        assertAll(
            () -> assertTrue(description.contains("35=D"), description),
            () -> assertTrue(description.contains("seq=7"), description),
            () -> assertTrue(description.contains("app"), description),
            () -> assertTrue(description.contains("ARTIO<->QFJ"), description));
    }
}
