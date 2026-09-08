package com.demo.artio.qfj;

import quickfix.Message;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Builds the four messages this counterparty sends on a drop copy session - the three order
 * requests and the {@code ExecutionReport} - on either FIX version.
 *
 * <p>Every message is a plain {@link Message} with fields set by tag number rather than a
 * {@code quickfix.fix42.NewOrderSingle} or {@code quickfix.fix44.NewOrderSingle}. That keeps one
 * implementation for both versions; the differences between them here are exactly three:
 * {@code HandlInst(21)}, which FIX 4.2 requires on {@code NewOrderSingle} and
 * {@code OrderCancelReplaceRequest} and FIX 4.4 does not; {@code ExecTransType(20)}, which FIX 4.2
 * requires on every {@code ExecutionReport} and FIX 4.4 removed; and the {@code ExecType(150)}
 * value for a trade, which {@link QfjVersion#execType} decides.
 */
public final class Orders
{
    /** {@code HandlInst(21)} = automated execution, no broker intervention. */
    public static final char HANDL_INST_AUTOMATED = '1';

    /** {@code OrdType(40)} = Limit. Every order here carries a price. */
    public static final char ORD_TYPE_LIMIT = '2';

    /** {@code TimeInForce(59)} = Day. */
    public static final char TIME_IN_FORCE_DAY = '0';

    /** {@code Side(54)} = Buy. */
    public static final char SIDE_BUY = '1';

    /** {@code Side(54)} = Sell. */
    public static final char SIDE_SELL = '2';

    /** {@code OrdStatus(39)} = New: acknowledged and working. */
    public static final char ORD_STATUS_NEW = '0';

    /** {@code OrdStatus(39)} = Partially filled. */
    public static final char ORD_STATUS_PARTIALLY_FILLED = '1';

    /** {@code OrdStatus(39)} = Filled. */
    public static final char ORD_STATUS_FILLED = '2';

    /** {@code OrdStatus(39)} = Canceled. */
    public static final char ORD_STATUS_CANCELED = '4';

    /** {@code ExecTransType(20)} = New. FIX 4.2 only; the field does not exist in FIX 4.4. */
    public static final char EXEC_TRANS_TYPE_NEW = '0';

    private Orders()
    {
    }

    /**
     * Builds a {@code NewOrderSingle(35=D)}.
     *
     * @param version the FIX version, which decides whether {@code HandlInst} is added.
     * @param clOrdId {@code ClOrdID(11)}.
     * @param symbol  {@code Symbol(55)}.
     * @param side    {@code Side(54)}, e.g. {@link #SIDE_BUY}.
     * @param qty     {@code OrderQty(38)}.
     * @param price   {@code Price(44)}.
     * @return the message, without header CompIDs: QuickFIX/J fills those in when it sends it.
     */
    public static Message newOrderSingle(
        final QfjVersion version,
        final String clOrdId,
        final String symbol,
        final char side,
        final double qty,
        final double price)
    {
        final Message message = message(Tags.MSG_TYPE_NEW_ORDER_SINGLE);
        message.setString(Tags.CL_ORD_ID, clOrdId);
        if (version.requiresHandlInst())
        {
            message.setChar(Tags.HANDL_INST, HANDL_INST_AUTOMATED);
        }
        message.setString(Tags.SYMBOL, symbol);
        message.setChar(Tags.SIDE, side);
        message.setDouble(Tags.ORDER_QTY, qty);
        message.setChar(Tags.ORD_TYPE, ORD_TYPE_LIMIT);
        message.setDouble(Tags.PRICE, price);
        message.setChar(Tags.TIME_IN_FORCE, TIME_IN_FORCE_DAY);
        message.setUtcTimeStamp(Tags.TRANSACT_TIME, utcNow(), true);
        return message;
    }

    /**
     * Builds an {@code OrderCancelReplaceRequest(35=G)}.
     *
     * @param version     the FIX version.
     * @param origClOrdId {@code OrigClOrdID(41)}: the order being replaced.
     * @param clOrdId     {@code ClOrdID(11)}: the new identifier.
     * @param symbol      {@code Symbol(55)}.
     * @param side        {@code Side(54)}.
     * @param qty         the replacement {@code OrderQty(38)}.
     * @param price       the replacement {@code Price(44)}.
     * @return the message.
     */
    public static Message cancelReplace(
        final QfjVersion version,
        final String origClOrdId,
        final String clOrdId,
        final String symbol,
        final char side,
        final double qty,
        final double price)
    {
        final Message message = message(Tags.MSG_TYPE_CANCEL_REPLACE);
        message.setString(Tags.ORIG_CL_ORD_ID, origClOrdId);
        message.setString(Tags.CL_ORD_ID, clOrdId);
        if (version.requiresHandlInst())
        {
            message.setChar(Tags.HANDL_INST, HANDL_INST_AUTOMATED);
        }
        message.setString(Tags.SYMBOL, symbol);
        message.setChar(Tags.SIDE, side);
        message.setDouble(Tags.ORDER_QTY, qty);
        message.setChar(Tags.ORD_TYPE, ORD_TYPE_LIMIT);
        message.setDouble(Tags.PRICE, price);
        message.setUtcTimeStamp(Tags.TRANSACT_TIME, utcNow(), true);
        return message;
    }

    /**
     * Builds an {@code OrderCancelRequest(35=F)}. {@code OrderQty(38)} is always included: FIX 4.4
     * requires the {@code OrderQtyData} component here, and FIX 4.2 accepts the field.
     *
     * @param version     the FIX version.
     * @param origClOrdId {@code OrigClOrdID(41)}: the order being cancelled.
     * @param clOrdId     {@code ClOrdID(11)}: the identifier of the cancel itself.
     * @param symbol      {@code Symbol(55)}.
     * @param side        {@code Side(54)}.
     * @param qty         {@code OrderQty(38)} of the order being cancelled.
     * @return the message.
     */
    public static Message cancel(
        final QfjVersion version,
        final String origClOrdId,
        final String clOrdId,
        final String symbol,
        final char side,
        final double qty)
    {
        final Message message = message(Tags.MSG_TYPE_CANCEL);
        message.setString(Tags.ORIG_CL_ORD_ID, origClOrdId);
        message.setString(Tags.CL_ORD_ID, clOrdId);
        message.setString(Tags.SYMBOL, symbol);
        message.setChar(Tags.SIDE, side);
        message.setDouble(Tags.ORDER_QTY, qty);
        message.setUtcTimeStamp(Tags.TRANSACT_TIME, utcNow(), true);
        return message;
    }

    /**
     * Builds an {@code ExecutionReport(35=8)} for one step of a drop copy stream.
     *
     * <p>Every field either dictionary declares required is written: {@code OrderID(37)},
     * {@code ExecID(17)}, {@code ExecType(150)}, {@code OrdStatus(39)}, {@code Symbol(55)},
     * {@code Side(54)}, {@code LeavesQty(151)}, {@code CumQty(14)} and {@code AvgPx(6)}, plus
     * {@code ExecTransType(20)} on FIX 4.2, where it is required and where FIX 4.4 does not have the
     * field at all. {@code ClOrdID(11)}, {@code OrderQty(38)} and {@code TransactTime(60)} are
     * optional in both and are always written: the first is what a consumer joins on, the second is
     * what makes {@code LeavesQty} readable, and the third is what makes the report timestamped.
     * {@code OrigClOrdID(41)}, {@code LastShares/LastQty(32)} and {@code LastPx(31)} appear only on
     * the reports that have them - a report that carries a {@code LastQty} of zero says a trade
     * happened for nothing, which is not what an acknowledgement means.
     *
     * @param version the FIX version.
     * @param report  the report to build.
     * @return the message, without header CompIDs: QuickFIX/J fills those in when it sends it.
     */
    public static Message executionReport(final QfjVersion version, final OrderScenario.Report report)
    {
        final Message message = message(Tags.MSG_TYPE_EXECUTION_REPORT);
        message.setString(Tags.ORDER_ID, report.orderId());
        message.setString(Tags.EXEC_ID, report.execId());
        if (version.hasExecTransType())
        {
            message.setChar(Tags.EXEC_TRANS_TYPE, EXEC_TRANS_TYPE_NEW);
        }
        message.setChar(Tags.EXEC_TYPE, version.execType(report.execEvent()));
        message.setChar(Tags.ORD_STATUS, report.ordStatus());
        message.setString(Tags.CL_ORD_ID, report.clOrdId());
        if (report.origClOrdId() != null)
        {
            message.setString(Tags.ORIG_CL_ORD_ID, report.origClOrdId());
        }
        message.setString(Tags.SYMBOL, report.symbol());
        message.setChar(Tags.SIDE, report.side());
        message.setDouble(Tags.ORDER_QTY, report.orderQty());
        if (report.traded())
        {
            message.setDouble(Tags.LAST_QTY, report.lastQty());
            message.setDouble(Tags.LAST_PX, report.lastPx());
        }
        message.setDouble(Tags.LEAVES_QTY, report.leavesQty());
        message.setDouble(Tags.CUM_QTY, report.cumQty());
        message.setDouble(Tags.AVG_PX, report.avgPx());
        message.setUtcTimeStamp(Tags.TRANSACT_TIME, utcNow(), true);
        return message;
    }

    /**
     * Turns one scenario step into a message.
     *
     * @param version the FIX version.
     * @param step    the step.
     * @return the message that step describes.
     */
    public static Message toMessage(final QfjVersion version, final OrderScenario.Step step)
    {
        return switch (step)
        {
            case OrderScenario.NewOrder newOrder -> newOrderSingle(version, newOrder.clOrdId(),
                newOrder.symbol(), newOrder.side(), newOrder.qty(), newOrder.price());
            case OrderScenario.Replace replace -> cancelReplace(version, replace.origClOrdId(),
                replace.clOrdId(), replace.symbol(), replace.side(), replace.qty(), replace.price());
            case OrderScenario.Cancel cancelStep -> cancel(version, cancelStep.origClOrdId(),
                cancelStep.clOrdId(), cancelStep.symbol(), cancelStep.side(), cancelStep.qty());
            case OrderScenario.Report report -> executionReport(version, report);
        };
    }

    private static Message message(final String msgType)
    {
        final Message message = new Message();
        message.getHeader().setString(Tags.MSG_TYPE, msgType);
        return message;
    }

    private static LocalDateTime utcNow()
    {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
