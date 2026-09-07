package com.demo.artio.qfj;

import quickfix.FieldNotFound;
import quickfix.Message;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns an inbound order message into the {@code ExecutionReport(35=8)}s a venue would send back.
 *
 * <ul>
 *   <li>{@code NewOrderSingle(D)} - an acknowledgement ({@code ExecType=0}, {@code OrdStatus=0})
 *       and then a full fill;</li>
 *   <li>{@code OrderCancelReplaceRequest(G)} - one {@code Replaced} report;</li>
 *   <li>{@code OrderCancelRequest(F)} - one {@code Canceled} report.</li>
 * </ul>
 *
 * <p>A request that lacks a field the report needs - a {@code NewOrderSingle} without
 * {@code OrderQty(38)}, any of them without {@code ClOrdID}, {@code Symbol} or {@code Side}, a
 * replace or cancel without {@code OrigClOrdID} - is answered the way a venue answers it: with a
 * {@code BusinessMessageReject(35=j)}, {@code BusinessRejectReason(380)=5} (conditionally required
 * field missing) and the tag number in {@code Text(58)}, rather than with a report that invents a
 * value or with silence.
 *
 * <p>Identifiers are deterministic ({@code EXEC-1}, {@code ORDER-1}, ...) and an order keeps the
 * {@code OrderID} it was given, so a replace or cancel reports against the same order. That makes
 * the AMPS {@code fix.order.state} topic - keyed on {@code OrderID(37)} - meaningful.
 *
 * <p>The FIX 4.2/4.4 differences handled here are {@code ExecTransType(20)}, which only 4.2 has,
 * and the {@code ExecType(150)} value for a fill: {@code 2} in 4.2, {@code F} in 4.4.
 */
public final class ExecutionReports
{
    /** {@code ExecType(150)} / {@code OrdStatus(39)} = New. */
    public static final char NEW = '0';

    /** {@code OrdStatus(39)} = Filled. */
    public static final char FILLED = '2';

    /** {@code ExecType(150)} / {@code OrdStatus(39)} = Canceled. */
    public static final char CANCELED = '4';

    /** {@code ExecType(150)} / {@code OrdStatus(39)} = Replaced. */
    public static final char REPLACED = '5';

    /** {@code ExecTransType(20)} = New. FIX 4.2 only. */
    public static final char EXEC_TRANS_TYPE_NEW = '0';

    /** {@code BusinessRejectReason(380)} = Conditionally required field missing. */
    public static final int CONDITIONALLY_REQUIRED_FIELD_MISSING = 5;

    private final QfjVersion version;
    private final String orderIdPrefix;
    private final String execIdPrefix;
    private final AtomicLong orderIds = new AtomicLong();
    private final AtomicLong execIds = new AtomicLong();
    private final Map<String, String> orderIdByClOrdId = new ConcurrentHashMap<>();

    /**
     * @param version the FIX version to answer in.
     */
    public ExecutionReports(final QfjVersion version)
    {
        this(version, "ORDER", "EXEC");
    }

    /**
     * @param version       the FIX version to answer in.
     * @param orderIdPrefix prefix for generated {@code OrderID(37)}s.
     * @param execIdPrefix  prefix for generated {@code ExecID(17)}s.
     */
    public ExecutionReports(final QfjVersion version, final String orderIdPrefix, final String execIdPrefix)
    {
        this.version = version;
        this.orderIdPrefix = orderIdPrefix;
        this.execIdPrefix = execIdPrefix;
    }

    /**
     * Builds the replies to one inbound order message.
     *
     * @param request a {@code NewOrderSingle}, {@code OrderCancelReplaceRequest} or
     *                {@code OrderCancelRequest}.
     * @return the execution reports to send, in order; one {@code BusinessMessageReject} when the
     *         request lacks a field the report needs; empty for any other message type.
     * @throws FieldNotFound if the request has no {@code MsgType(35)} in its header.
     */
    public List<Message> repliesTo(final Message request) throws FieldNotFound
    {
        final String msgType = request.getHeader().getString(Tags.MSG_TYPE);
        try
        {
            return switch (msgType)
            {
                case Tags.MSG_TYPE_NEW_ORDER_SINGLE -> acknowledgeAndFill(request);
                case Tags.MSG_TYPE_CANCEL_REPLACE -> List.of(replaced(request));
                case Tags.MSG_TYPE_CANCEL -> List.of(canceled(request));
                default -> List.of();
            };
        }
        catch (final FieldNotFound missing)
        {
            return List.of(businessReject(request, msgType, missing.field));
        }
    }

    /**
     * {@code BusinessMessageReject(35=j)} for a request the venue cannot act on. Both dictionaries
     * declare the message with {@code RefMsgType(372)} and {@code BusinessRejectReason(380)} required;
     * {@code RefSeqNum(45)} points back at the request when its header carries a sequence number
     * (always, once QuickFIX/J has delivered it) and {@code BusinessRejectRefID(379)} carries the
     * {@code ClOrdID} when the request has one.
     */
    private static Message businessReject(final Message request, final String msgType, final int missingTag)
        throws FieldNotFound
    {
        final Message reject = new Message();
        reject.getHeader().setString(Tags.MSG_TYPE, Tags.MSG_TYPE_BUSINESS_REJECT);
        if (request.getHeader().isSetField(Tags.MSG_SEQ_NUM))
        {
            reject.setInt(Tags.REF_SEQ_NUM, request.getHeader().getInt(Tags.MSG_SEQ_NUM));
        }
        reject.setString(Tags.REF_MSG_TYPE, msgType);
        if (request.isSetField(Tags.CL_ORD_ID))
        {
            reject.setString(Tags.BUSINESS_REJECT_REF_ID, request.getString(Tags.CL_ORD_ID));
        }
        reject.setInt(Tags.BUSINESS_REJECT_REASON, CONDITIONALLY_REQUIRED_FIELD_MISSING);
        reject.setString(Tags.TEXT, "Conditionally required field missing: tag " + missingTag);
        return reject;
    }

    private List<Message> acknowledgeAndFill(final Message request) throws FieldNotFound
    {
        require(request, Tags.CL_ORD_ID, Tags.ORDER_QTY, Tags.SYMBOL, Tags.SIDE);
        final String clOrdId = request.getString(Tags.CL_ORD_ID);
        final String orderId = orderIdFor(clOrdId, null);
        final double qty = request.getDouble(Tags.ORDER_QTY);
        final double price = request.isSetField(Tags.PRICE) ? request.getDouble(Tags.PRICE) : 0;

        final Message acknowledged = base(request, orderId, clOrdId, NEW, NEW);
        acknowledged.setDouble(Tags.ORDER_QTY, qty);
        acknowledged.setDouble(Tags.LEAVES_QTY, qty);
        acknowledged.setDouble(Tags.CUM_QTY, 0);
        acknowledged.setDouble(Tags.AVG_PX, 0);
        if (price > 0)
        {
            acknowledged.setDouble(Tags.PRICE, price);
        }

        final Message filled = base(request, orderId, clOrdId, version.fillExecType(), FILLED);
        filled.setDouble(Tags.ORDER_QTY, qty);
        filled.setDouble(Tags.LEAVES_QTY, 0);
        filled.setDouble(Tags.CUM_QTY, qty);
        filled.setDouble(Tags.AVG_PX, price);
        filled.setDouble(Tags.LAST_QTY, qty);
        filled.setDouble(Tags.LAST_PX, price);
        if (price > 0)
        {
            filled.setDouble(Tags.PRICE, price);
        }

        return List.of(acknowledged, filled);
    }

    private Message replaced(final Message request) throws FieldNotFound
    {
        require(request, Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.SYMBOL, Tags.SIDE);
        final String clOrdId = request.getString(Tags.CL_ORD_ID);
        final String origClOrdId = request.getString(Tags.ORIG_CL_ORD_ID);
        final String orderId = orderIdFor(clOrdId, origClOrdId);
        final double qty = request.isSetField(Tags.ORDER_QTY) ? request.getDouble(Tags.ORDER_QTY) : 0;

        final Message report = base(request, orderId, clOrdId, REPLACED, REPLACED);
        report.setString(Tags.ORIG_CL_ORD_ID, origClOrdId);
        report.setDouble(Tags.ORDER_QTY, qty);
        report.setDouble(Tags.LEAVES_QTY, qty);
        report.setDouble(Tags.CUM_QTY, 0);
        report.setDouble(Tags.AVG_PX, 0);
        if (request.isSetField(Tags.PRICE))
        {
            report.setDouble(Tags.PRICE, request.getDouble(Tags.PRICE));
        }
        return report;
    }

    private Message canceled(final Message request) throws FieldNotFound
    {
        require(request, Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.SYMBOL, Tags.SIDE);
        final String clOrdId = request.getString(Tags.CL_ORD_ID);
        final String origClOrdId = request.getString(Tags.ORIG_CL_ORD_ID);
        final String orderId = orderIdFor(clOrdId, origClOrdId);

        final Message report = base(request, orderId, clOrdId, CANCELED, CANCELED);
        report.setString(Tags.ORIG_CL_ORD_ID, origClOrdId);
        report.setDouble(Tags.ORDER_QTY, request.isSetField(Tags.ORDER_QTY) ? request.getDouble(Tags.ORDER_QTY) : 0);
        report.setDouble(Tags.LEAVES_QTY, 0);
        report.setDouble(Tags.CUM_QTY, 0);
        report.setDouble(Tags.AVG_PX, 0);
        return report;
    }

    /**
     * Fails on the first missing tag before any identifier is handed out, so a rejected request
     * leaves no {@code OrderID}, no {@code ExecID} and no {@code ClOrdID} mapping behind.
     */
    private static void require(final Message request, final int... tags) throws FieldNotFound
    {
        for (final int tag : tags)
        {
            if (!request.isSetField(tag))
            {
                throw new FieldNotFound(tag);
            }
        }
    }

    private Message base(
        final Message request,
        final String orderId,
        final String clOrdId,
        final char execType,
        final char ordStatus) throws FieldNotFound
    {
        final Message report = new Message();
        report.getHeader().setString(Tags.MSG_TYPE, Tags.MSG_TYPE_EXECUTION_REPORT);
        report.setString(Tags.ORDER_ID, orderId);
        report.setString(Tags.EXEC_ID, execIdPrefix + '-' + execIds.incrementAndGet());
        if (version.hasExecTransType())
        {
            // Removed in FIX 4.4; sending it there would be an unknown field.
            report.setChar(Tags.EXEC_TRANS_TYPE, EXEC_TRANS_TYPE_NEW);
        }
        report.setChar(Tags.EXEC_TYPE, execType);
        report.setChar(Tags.ORD_STATUS, ordStatus);
        report.setString(Tags.CL_ORD_ID, clOrdId);
        report.setString(Tags.SYMBOL, request.getString(Tags.SYMBOL));
        report.setChar(Tags.SIDE, request.getChar(Tags.SIDE));
        report.setUtcTimeStamp(Tags.TRANSACT_TIME, LocalDateTime.now(ZoneOffset.UTC), true);
        return report;
    }

    /**
     * An order keeps one {@code OrderID} for its whole life, so a replace or a cancel reports
     * against the {@code OrderID} the original {@code NewOrderSingle} was given.
     */
    private String orderIdFor(final String clOrdId, final String origClOrdId)
    {
        if (origClOrdId != null)
        {
            final String existing = orderIdByClOrdId.get(origClOrdId);
            if (existing != null)
            {
                orderIdByClOrdId.put(clOrdId, existing);
                return existing;
            }
        }
        return orderIdByClOrdId.computeIfAbsent(
            clOrdId, id -> orderIdPrefix + '-' + orderIds.incrementAndGet());
    }
}
