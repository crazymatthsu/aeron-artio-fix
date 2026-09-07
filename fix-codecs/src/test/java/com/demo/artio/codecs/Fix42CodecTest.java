package com.demo.artio.codecs;

import com.demo.artio.fix42.FixDictionaryImpl;
import com.demo.artio.fix42.builder.NewOrderSingleEncoder;
import com.demo.artio.fix42.decoder.NewOrderSingleDecoder;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FIX 4.2 codecs are generated at build time from the dictionary :fix-dictionary converts.
 * These tests are the proof that the conversion produced something usable, not merely something
 * that compiles.
 */
class Fix42CodecTest {

    private static final char SOH = 1; // ASCII SOH, the FIX field separator
    private static final byte[] TIMESTAMP =
            "20260906-12:00:00.000".getBytes(StandardCharsets.US_ASCII);

    @Test
    void aNewOrderSingleEncodedByTheGeneratedEncoderDecodesBackWithTheSameFields() {
        final NewOrderSingleEncoder encoder = new NewOrderSingleEncoder();
        encoder.header()
                .senderCompID("ARTIO")
                .targetCompID("QFJ")
                .msgSeqNum(1)
                .sendingTime(TIMESTAMP);
        encoder.clOrdID("ORD-1")
                .handlInst('1')
                .symbol("MSFT")
                .side('1')
                .transactTime(TIMESTAMP)
                .orderQty(new DecimalFloat(100, 0))
                .ordType('2')
                .price(new DecimalFloat(4275, 2));

        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[1024]);
        final long result = encoder.encode(buffer, 0);

        final NewOrderSingleDecoder decoder = new NewOrderSingleDecoder();
        decoder.decode(buffer, Encoder.offset(result), Encoder.length(result));

        assertTrue(decoder.validate(), "the decoded message should satisfy the dictionary");
        assertEquals("ORD-1", decoder.clOrdIDAsString());
        assertEquals("MSFT", decoder.symbolAsString());
        assertEquals('1', decoder.side());
        assertEquals(new DecimalFloat(100, 0), decoder.orderQty());
        assertEquals(new DecimalFloat(4275, 2), decoder.price());
    }

    @Test
    void theEncodedMessageIsWellFormedFix42OnTheWire() {
        final NewOrderSingleEncoder encoder = new NewOrderSingleEncoder();
        encoder.header()
                .senderCompID("ARTIO")
                .targetCompID("QFJ")
                .msgSeqNum(7)
                .sendingTime(TIMESTAMP);
        encoder.clOrdID("ORD-2")
                .handlInst('1')
                .symbol("AAPL")
                .side('2')
                .transactTime(TIMESTAMP)
                .ordType('1');

        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[1024]);
        final long result = encoder.encode(buffer, 0);
        final String wire = buffer.getAscii(Encoder.offset(result), Encoder.length(result));
        final String printable = wire.replace(SOH, '|');

        assertTrue(wire.startsWith("8=FIX.4.2" + SOH), printable);
        assertTrue(wire.contains(SOH + "35=D" + SOH), printable);
        assertTrue(wire.contains(SOH + "11=ORD-2" + SOH), printable);
        assertTrue(wire.endsWith(String.valueOf(SOH)), "a FIX message ends with SOH: " + printable);
    }

    @Test
    void theGeneratedDictionaryClassReportsBeginStringFix42() {
        assertEquals("FIX.4.2", new FixDictionaryImpl().beginString());
    }

    @Test
    void theGeneratedDictionaryClassImplementsArtiosFixDictionaryInterface() {
        // The engine module resolves this class by name; keep it stable.
        assertInstanceOf(FixDictionary.class, new FixDictionaryImpl());
        assertEquals("com.demo.artio.fix42.FixDictionaryImpl", FixDictionaryImpl.class.getName());
    }

    @Test
    void theGeneratedDictionarySuppliesEverySessionCodecTheEngineAsksFor() {
        final FixDictionary dictionary = new FixDictionaryImpl();

        assertNotNull(dictionary.makeHeaderEncoder(), "header encoder");
        assertNotNull(dictionary.makeHeaderDecoder(), "header decoder");
        assertNotNull(dictionary.makeLogonEncoder(), "Logon encoder");
        assertNotNull(dictionary.makeLogonDecoder(), "Logon decoder");
        assertNotNull(dictionary.makeLogoutEncoder(), "Logout encoder");
        assertNotNull(dictionary.makeLogoutDecoder(), "Logout decoder");
        assertNotNull(dictionary.makeHeartbeatEncoder(), "Heartbeat encoder");
        assertNotNull(dictionary.makeHeartbeatDecoder(), "Heartbeat decoder");
        assertNotNull(dictionary.makeTestRequestEncoder(), "TestRequest encoder");
        assertNotNull(dictionary.makeTestRequestDecoder(), "TestRequest decoder");
        assertNotNull(dictionary.makeResendRequestEncoder(), "ResendRequest encoder");
        assertNotNull(dictionary.makeResendRequestDecoder(), "ResendRequest decoder");
        assertNotNull(dictionary.makeRejectEncoder(), "Reject encoder");
        assertNotNull(dictionary.makeRejectDecoder(), "Reject decoder");
        assertNotNull(dictionary.makeSequenceResetEncoder(), "SequenceReset encoder");
        assertNotNull(dictionary.makeSequenceResetDecoder(), "SequenceReset decoder");
    }

    @Test
    void fix42LogonCannotCarryCredentialsBecauseTheDictionaryHasNoUsernameField() {
        // QuickFIX/J's FIX 4.2 Logon predates Username(553)/Password(554), so Artio's generator
        // emits supportsUsername() == false. An engine configured with credentials on a FIX 4.2
        // session will not send them; see docs/02-quickfixj-to-artio-dictionary.md.
        assertFalse(new FixDictionaryImpl().makeLogonEncoder().supportsUsername());
        assertFalse(new FixDictionaryImpl().makeLogonEncoder().supportsPassword());
    }
}
