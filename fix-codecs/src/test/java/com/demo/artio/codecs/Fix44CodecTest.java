package com.demo.artio.codecs;

import com.demo.artio.fix44.FixDictionaryImpl;
import com.demo.artio.fix44.builder.NewOrderSingleEncoder;
import com.demo.artio.fix44.decoder.NewOrderSingleDecoder;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same round trip for FIX 4.4, whose dictionary needed no conversion at all. */
class Fix44CodecTest {

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
        encoder.clOrdID("ORD-44")
                .side('1')
                .transactTime(TIMESTAMP)
                .ordType('2')
                .price(new DecimalFloat(1234, 2));
        // FIX 4.4 keeps Symbol and OrderQty in the Instrument and OrderQtyData components, so the
        // generated encoder exposes them through component encoders rather than directly.
        encoder.instrument().symbol("VOD.L");
        encoder.orderQtyData().orderQty(new DecimalFloat(250, 0));

        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[1024]);
        final long result = encoder.encode(buffer, 0);

        final NewOrderSingleDecoder decoder = new NewOrderSingleDecoder();
        decoder.decode(buffer, Encoder.offset(result), Encoder.length(result));

        assertTrue(decoder.validate(), "the decoded message should satisfy the dictionary");
        assertEquals("ORD-44", decoder.clOrdIDAsString());
        // The decoder implements the component interfaces, so the accessors are flat.
        assertEquals("VOD.L", decoder.symbolAsString());
        assertEquals('1', decoder.side());
        assertEquals(new DecimalFloat(250, 0), decoder.orderQty());
    }

    @Test
    void theEncodedMessageIsWellFormedFix44OnTheWire() {
        final NewOrderSingleEncoder encoder = new NewOrderSingleEncoder();
        encoder.header()
                .senderCompID("ARTIO")
                .targetCompID("QFJ")
                .msgSeqNum(2)
                .sendingTime(TIMESTAMP);
        encoder.clOrdID("ORD-45")
                .side('2')
                .transactTime(TIMESTAMP)
                .ordType('1');
        encoder.instrument().symbol("BP.L");

        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[1024]);
        final long result = encoder.encode(buffer, 0);
        final String wire = buffer.getAscii(Encoder.offset(result), Encoder.length(result));

        assertTrue(wire.startsWith("8=FIX.4.4" + SOH), wire.replace(SOH, '|'));
        assertTrue(wire.contains(SOH + "35=D" + SOH), wire.replace(SOH, '|'));
    }

    @Test
    void theGeneratedDictionaryClassReportsBeginStringFix44() {
        assertEquals("FIX.4.4", new FixDictionaryImpl().beginString());
    }

    @Test
    void theGeneratedDictionaryClassImplementsArtiosFixDictionaryInterface() {
        assertInstanceOf(FixDictionary.class, new FixDictionaryImpl());
        assertEquals("com.demo.artio.fix44.FixDictionaryImpl", FixDictionaryImpl.class.getName());
    }

    @Test
    void fix44LogonCanCarryCredentialsUnlikeFix42() {
        // FIX 4.4's Logon declares Username(553) and Password(554), so Artio's generator emits
        // real accessors and supportsUsername() is true.
        assertTrue(new FixDictionaryImpl().makeLogonEncoder().supportsUsername());
        assertTrue(new FixDictionaryImpl().makeLogonEncoder().supportsPassword());
    }

    @Test
    void theGeneratedDictionarySuppliesEverySessionCodecTheEngineAsksFor() {
        final FixDictionary dictionary = new FixDictionaryImpl();

        assertNotNull(dictionary.makeHeaderEncoder());
        assertNotNull(dictionary.makeHeaderDecoder());
        assertNotNull(dictionary.makeLogonEncoder());
        assertNotNull(dictionary.makeLogonDecoder());
        assertNotNull(dictionary.makeLogoutEncoder());
        assertNotNull(dictionary.makeLogoutDecoder());
        assertNotNull(dictionary.makeHeartbeatEncoder());
        assertNotNull(dictionary.makeHeartbeatDecoder());
        assertNotNull(dictionary.makeTestRequestEncoder());
        assertNotNull(dictionary.makeTestRequestDecoder());
        assertNotNull(dictionary.makeResendRequestEncoder());
        assertNotNull(dictionary.makeResendRequestDecoder());
        assertNotNull(dictionary.makeRejectEncoder());
        assertNotNull(dictionary.makeRejectDecoder());
        assertNotNull(dictionary.makeSequenceResetEncoder());
        assertNotNull(dictionary.makeSequenceResetDecoder());
        assertNotNull(dictionary.makeBusinessMessageRejectEncoder());
    }
}
