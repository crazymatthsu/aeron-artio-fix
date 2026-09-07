package com.demo.artio.qfj;

import quickfix.Message;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Builds the three order messages this counterparty sends, on either FIX version.
 *
 * <p>Every message is a plain {@link Message} with fields set by tag number rather than a
 * {@code quickfix.fix42.NewOrderSingle} or {@code quickfix.fix44.NewOrderSingle}. That keeps one
 * implementation for both versions; the only real difference between them here is
 * {@code HandlInst(21)}, which FIX 4.2 requires on {@code NewOrderSingle} and
 * {@code OrderCancelReplaceRequest} and FIX 4.4 does not.
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
     * Turns one scenario step into a message.
     *
     * @param version the FIX version.
     * @param step    the step.
     * @return the message that step describes.
     */
    public static Message toMessage(final QfjVersion version, final OrderScenario.Step step)
    {
        if (step instanceof OrderScenario.NewOrder newOrder)
        {
            return newOrderSingle(version, newOrder.clOrdId(), newOrder.symbol(), newOrder.side(),
                newOrder.qty(), newOrder.price());
        }
        if (step instanceof OrderScenario.Replace replace)
        {
            return cancelReplace(version, replace.origClOrdId(), replace.clOrdId(), replace.symbol(),
                replace.side(), replace.qty(), replace.price());
        }
        final OrderScenario.Cancel cancel = (OrderScenario.Cancel)step;
        return cancel(version, cancel.origClOrdId(), cancel.clOrdId(), cancel.symbol(), cancel.side(),
            cancel.qty());
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
