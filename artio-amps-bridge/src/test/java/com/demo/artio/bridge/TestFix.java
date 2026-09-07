package com.demo.artio.bridge;

import com.demo.artio.engine.FixMessageView;
import com.demo.artio.engine.SessionKey;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.nio.charset.StandardCharsets;

/**
 * Hand-built FIX messages and the flyweights over them, so the unit suite needs no engine.
 *
 * <p>Messages are written with {@code |} for readability and converted to real SOH here - the same
 * trick {@code :artio-engine}'s unit tests use. Nothing in this class produces a printable message:
 * what a test hands the publisher has genuine {@code } separators, because a publisher that
 * mangled them would otherwise pass.
 */
final class TestFix
{
    /** The session every message in the unit suite claims to belong to. */
    static final SessionKey SESSION = new SessionKey(42L, "ARTIO", "QFJ", "FIX.4.2", true);

    private TestFix()
    {
    }

    /**
     * @param printable a FIX message written with {@code |} in place of SOH.
     * @return the same message with real separators.
     */
    static String fix(final String printable)
    {
        return printable.replace('|', '\001');
    }

    /**
     * @param printable a FIX message written with {@code |} in place of SOH.
     * @return its bytes, with real separators.
     */
    static byte[] bytes(final String printable)
    {
        return fix(printable).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * A {@code NewOrderSingle} carrying {@code ClOrdID}.
     *
     * @param clOrdId tag 11.
     * @return the raw bytes.
     */
    static byte[] newOrderSingle(final String clOrdId)
    {
        return bytes("8=FIX.4.2|9=100|35=D|49=QFJ|56=ARTIO|34=7|11=" + clOrdId +
            "|55=MSFT|54=1|38=100|40=2|44=101.25|10=123|");
    }

    /**
     * An {@code ExecutionReport} with both SOW keys.
     *
     * @param execId  tag 17.
     * @param orderId tag 37.
     * @return the raw bytes.
     */
    static byte[] executionReport(final String execId, final String orderId)
    {
        return bytes("8=FIX.4.2|9=140|35=8|49=QFJ|56=ARTIO|34=9|37=" + orderId + "|17=" + execId +
            "|11=ORD-1|20=0|150=0|39=0|55=MSFT|54=1|151=100|14=0|6=0|10=201|");
    }

    /**
     * An {@code ExecutionReport} with {@code ExecID} but <strong>no</strong> {@code OrderID} - the
     * message the {@code fix.order.state} route must decline.
     *
     * @param execId tag 17.
     * @return the raw bytes.
     */
    static byte[] executionReportWithoutOrderId(final String execId)
    {
        return bytes("8=FIX.4.2|9=130|35=8|49=QFJ|56=ARTIO|34=9|17=" + execId +
            "|11=ORD-1|20=0|150=0|39=0|55=MSFT|54=1|151=100|14=0|6=0|10=202|");
    }

    /** @return a {@code Logon}, the admin message every session starts with. */
    static byte[] logon()
    {
        return bytes("8=FIX.4.2|9=70|35=A|49=QFJ|56=ARTIO|34=1|98=0|108=30|141=Y|10=099|");
    }

    /**
     * Wraps raw bytes in the flyweight Artio would hand a sink.
     *
     * @param raw     the message.
     * @param msgType the {@code MsgType(35)} value.
     * @param seqNum  {@code MsgSeqNum(34)}.
     * @return a view over a private buffer; safe to keep for the length of a test.
     */
    static FixMessageView view(final byte[] raw, final String msgType, final int seqNum)
    {
        return view(new FixMessageView(), raw, msgType, seqNum, 0);
    }

    /**
     * Points an existing flyweight at a message, at a non-zero offset - the realistic case, since
     * Artio hands out views into a shared log buffer.
     *
     * @param view    the flyweight to reuse.
     * @param raw     the message.
     * @param msgType the {@code MsgType(35)} value.
     * @param seqNum  {@code MsgSeqNum(34)}.
     * @param offset  where in the backing buffer to place the message.
     * @return the wrapped view.
     */
    static FixMessageView view(
        final FixMessageView view, final byte[] raw, final String msgType, final int seqNum, final int offset)
    {
        final byte[] backing = new byte[offset + raw.length + 16];
        System.arraycopy(raw, 0, backing, offset, raw.length);
        return view.wrap(
            new UnsafeBuffer(backing), offset, raw.length,
            MessageTypeEncoding.packMessageType(msgType), seqNum, 0,
            System.nanoTime(), 3, true, SESSION);
    }
}
