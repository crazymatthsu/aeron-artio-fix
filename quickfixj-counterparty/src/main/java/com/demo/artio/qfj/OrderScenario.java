package com.demo.artio.qfj;

import java.util.List;

/**
 * The scripted order flow every demo and every bridge test replays: three new orders, one
 * cancel/replace of the first and one cancel of the second. Five application messages, all
 * identifiers derived from a prefix, so a test can name the exact {@code ClOrdID}s it expects
 * before anything has run.
 *
 * <p>With the default prefix {@code ORD} the messages are:
 *
 * <pre>
 *   35=D  11=ORD-1                  MSFT buy  100 @ 101.25
 *   35=D  11=ORD-2                  MSFT buy  200 @ 102.50
 *   35=D  11=ORD-3                  MSFT buy  300 @ 103.75
 *   35=G  11=ORD-4  41=ORD-1        MSFT buy  150 @ 101.75
 *   35=F  11=ORD-5  41=ORD-2        MSFT buy  200
 * </pre>
 *
 * The replace and the cancel deliberately reference earlier orders: the AMPS bridge keys
 * {@code fix.orders} by {@code ClOrdID}, so a scenario in which every message has a distinct
 * {@code ClOrdID} but two of them also carry an {@code OrigClOrdID} is what proves the SOW keying
 * is doing something.
 *
 * @param idPrefix the {@code ClOrdID} prefix; identifiers are {@code <prefix>-1}..{@code <prefix>-5}.
 * @param symbol   the instrument every message names.
 * @param side     {@code Side(54)} for every message.
 * @param baseQty  the first order's quantity; the second and third are 2x and 3x.
 * @param basePrice the first order's price; the second and third are +1.25 and +2.50.
 */
public record OrderScenario(
    String idPrefix,
    String symbol,
    char side,
    double baseQty,
    double basePrice)
{
    /** The scenario used by the demo and by every test that does not care about the numbers. */
    public static final OrderScenario DEFAULT = new OrderScenario("ORD", "MSFT", Orders.SIDE_BUY, 100, 101.25);

    /** How many application messages the scenario sends. */
    public static final int MESSAGE_COUNT = 5;

    /** Validates the components. */
    public OrderScenario
    {
        if (idPrefix == null || idPrefix.isBlank())
        {
            throw new IllegalArgumentException("idPrefix must not be blank");
        }
        if (symbol == null || symbol.isBlank())
        {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (baseQty <= 0)
        {
            throw new IllegalArgumentException("baseQty must be positive but was " + baseQty);
        }
        if (basePrice <= 0)
        {
            throw new IllegalArgumentException("basePrice must be positive but was " + basePrice);
        }
    }

    /**
     * @param index 1-based position in the scenario.
     * @return the {@code ClOrdID} of that message, e.g. {@code ORD-4}.
     */
    public String clOrdId(final int index)
    {
        if (index < 1 || index > MESSAGE_COUNT)
        {
            throw new IllegalArgumentException("index must be in 1.." + MESSAGE_COUNT + " but was " + index);
        }
        return idPrefix + '-' + index;
    }

    /** @return the five {@code ClOrdID}s, in the order they are sent. */
    public List<String> clOrdIds()
    {
        return List.of(clOrdId(1), clOrdId(2), clOrdId(3), clOrdId(4), clOrdId(5));
    }

    /** @return the five steps, in order. */
    public List<Step> steps()
    {
        return List.of(
            new NewOrder(clOrdId(1), symbol, side, baseQty, basePrice),
            new NewOrder(clOrdId(2), symbol, side, baseQty * 2, basePrice + 1.25),
            new NewOrder(clOrdId(3), symbol, side, baseQty * 3, basePrice + 2.50),
            new Replace(clOrdId(1), clOrdId(4), symbol, side, baseQty * 1.5, basePrice + 0.50),
            new Cancel(clOrdId(2), clOrdId(5), symbol, side, baseQty * 2));
    }

    /** @return the {@code MsgType(35)} of each step, in order: {@code [D, D, D, G, F]}. */
    public List<String> msgTypes()
    {
        return steps().stream().map(Step::msgType).toList();
    }

    /** One message in the scenario. */
    public sealed interface Step permits NewOrder, Replace, Cancel
    {
        /** @return the {@code ClOrdID(11)} this step sends. */
        String clOrdId();

        /** @return the {@code MsgType(35)} of the message this step sends. */
        String msgType();
    }

    /**
     * A {@code NewOrderSingle}.
     *
     * @param clOrdId {@code ClOrdID(11)}.
     * @param symbol  {@code Symbol(55)}.
     * @param side    {@code Side(54)}.
     * @param qty     {@code OrderQty(38)}.
     * @param price   {@code Price(44)}.
     */
    public record NewOrder(String clOrdId, String symbol, char side, double qty, double price) implements Step
    {
        @Override
        public String msgType()
        {
            return Tags.MSG_TYPE_NEW_ORDER_SINGLE;
        }
    }

    /**
     * An {@code OrderCancelReplaceRequest}.
     *
     * @param origClOrdId {@code OrigClOrdID(41)}.
     * @param clOrdId     {@code ClOrdID(11)}.
     * @param symbol      {@code Symbol(55)}.
     * @param side        {@code Side(54)}.
     * @param qty         the replacement {@code OrderQty(38)}.
     * @param price       the replacement {@code Price(44)}.
     */
    public record Replace(
        String origClOrdId, String clOrdId, String symbol, char side, double qty, double price) implements Step
    {
        @Override
        public String msgType()
        {
            return Tags.MSG_TYPE_CANCEL_REPLACE;
        }
    }

    /**
     * An {@code OrderCancelRequest}.
     *
     * @param origClOrdId {@code OrigClOrdID(41)}.
     * @param clOrdId     {@code ClOrdID(11)}.
     * @param symbol      {@code Symbol(55)}.
     * @param side        {@code Side(54)}.
     * @param qty         {@code OrderQty(38)} of the order being cancelled.
     */
    public record Cancel(
        String origClOrdId, String clOrdId, String symbol, char side, double qty) implements Step
    {
        @Override
        public String msgType()
        {
            return Tags.MSG_TYPE_CANCEL;
        }
    }
}
