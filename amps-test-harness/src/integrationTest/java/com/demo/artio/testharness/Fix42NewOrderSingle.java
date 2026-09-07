package com.demo.artio.testharness;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The smallest honest FIX 4.2 NewOrderSingle, built by hand.
 *
 * <p>By hand, and not with a codec, on purpose: this module is the thing the
 * codec-generating modules will be tested against, and it must not depend on
 * them. It is also the shape of payload the bridge will produce - raw
 * SOH-separated {@code tag=value} with nothing wrapped around it - so the test
 * exercises what AMPS will really be asked to parse.
 *
 * <p>BodyLength (9) and CheckSum (10) are computed rather than faked. AMPS does
 * not appear to verify either, but a message that is wrong in the framing
 * fields is not a FIX message, and a fixture that is quietly invalid is the
 * kind of thing a later phase inherits and cannot explain.
 */
final class Fix42NewOrderSingle {

    static final char SOH = '\u0001';

    private static final DateTimeFormatter SENDING_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private Fix42NewOrderSingle() {
    }

    /**
     * A NewOrderSingle carrying exactly the tags the flow's SOW key needs plus
     * the mandatory ones: 8, 9, 35, 49, 56, 34, 52, 11, 55, 54, 38, 40, 10.
     *
     * <p>Tag 11 is the one that matters to the topic design: {@code fix.orders}
     * is keyed {@code /11}, and a publish to a SOW topic that lacks the
     * topic's key field is not refused - it is quietly filed under a shared
     * degenerate key. See {@code SowKeyBehaviourIT}.
     */
    static String message(String clOrdId, int sequenceNumber) {
        String body = new StringBuilder()
                .append("35=D").append(SOH)
                .append("49=ARTIO").append(SOH)
                .append("56=COUNTERPARTY").append(SOH)
                .append("34=").append(sequenceNumber).append(SOH)
                .append("52=").append(SENDING_TIME.format(Instant.now())).append(SOH)
                .append("11=").append(clOrdId).append(SOH)
                .append("55=AAPL").append(SOH)
                .append("54=1").append(SOH)
                .append("38=100").append(SOH)
                .append("40=2").append(SOH)
                .toString();

        return frame(body);
    }

    /**
     * Wraps a body in {@code 8=FIX.4.2}, a computed {@code 9=} BodyLength and a
     * computed {@code 10=} CheckSum.
     *
     * <p>BodyLength counts everything after the 9 field's separator up to and
     * including the separator before the CheckSum field.
     */
    static String frame(String body) {
        String withHeader = "8=FIX.4.2" + SOH + "9=" + byteLength(body) + SOH + body;
        return withHeader + "10=" + checksum(withHeader) + SOH;
    }

    private static int byteLength(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /** The low byte of the sum of every byte so far, as three digits. */
    private static String checksum(String upToCheckSum) {
        int sum = 0;
        for (byte b : upToCheckSum.getBytes(StandardCharsets.US_ASCII)) {
            sum += b & 0xFF;
        }
        return String.format("%03d", sum % 256);
    }
}
