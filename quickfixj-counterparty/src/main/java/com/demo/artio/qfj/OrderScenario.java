package com.demo.artio.qfj;

import com.demo.artio.qfj.QfjVersion.ExecEvent;

import java.util.List;

/**
 * The scripted <strong>drop copy</strong> flow every demo and every bridge test replays: five order
 * events - three new orders, one cancel/replace of the first and one cancel of the second - and the
 * ten execution reports those orders produce on the venue the copy comes from. Fifteen application
 * messages, every identifier and every quantity derived from the record's components, so a test can
 * name the exact values it expects before anything has run.
 *
 * <p>All fifteen are <em>sent</em> by this side. That is what a drop copy session is: copies of an
 * order session's traffic - {@code 35=D}, {@code 35=G}, {@code 35=F} and {@code 35=8} - travelling
 * to a consumer that receives, records and answers nothing. The reports here are not replies to
 * anything; {@link ExecutionReports}, which turns an inbound order into replies for the QuickFIX/J
 * <em>acceptor</em>, is a different feature for a different topology.
 *
 * <p>With the default components the stream is:
 *
 * <pre>
 *    1  35=D  11=ORD-1                                              MSFT buy 100 @ 101.25
 *    2  35=8  11=ORD-1            37=ORDER-1 17=EXEC-1   150=0    39=0  151=100 14=0   6=0
 *    3  35=8  11=ORD-1            37=ORDER-1 17=EXEC-2   150=1/F  39=1  151=50  14=50  6=101.25
 *    4  35=D  11=ORD-2                                              MSFT buy 200 @ 102.50
 *    5  35=8  11=ORD-2            37=ORDER-2 17=EXEC-3   150=0    39=0  151=200 14=0   6=0
 *    6  35=8  11=ORD-2            37=ORDER-2 17=EXEC-4   150=1/F  39=1  151=100 14=100 6=102.50
 *    7  35=D  11=ORD-3                                              MSFT buy 300 @ 103.75
 *    8  35=8  11=ORD-3            37=ORDER-3 17=EXEC-5   150=0    39=0  151=300 14=0   6=0
 *    9  35=8  11=ORD-3            37=ORDER-3 17=EXEC-6   150=1/F  39=1  151=150 14=150 6=103.75
 *   10  35=8  11=ORD-3            37=ORDER-3 17=EXEC-7   150=2/F  39=2  151=0   14=300 6=103.75
 *   11  35=G  11=ORD-4  41=ORD-1                                    MSFT buy 150 @ 101.75
 *   12  35=8  11=ORD-4  41=ORD-1  37=ORDER-1 17=EXEC-8   150=5    39=1  151=100 14=50  6=101.25
 *   13  35=8  11=ORD-4            37=ORDER-1 17=EXEC-9   150=2/F  39=2  151=0   14=150 6=101.5833
 *   14  35=F  11=ORD-5  41=ORD-2                                    MSFT buy 200
 *   15  35=8  11=ORD-5  41=ORD-2  37=ORDER-2 17=EXEC-10  150=4    39=4  151=0   14=100 6=102.50
 * </pre>
 *
 * Three things in that table are the reason it is shaped this way:
 *
 * <ul>
 *   <li><strong>An order keeps one {@code OrderID(37)} for its whole life.</strong> {@code ORD-4}
 *       amends {@code ORD-1}, so its reports carry {@code ORDER-1}; {@code ORD-5} cancels
 *       {@code ORD-2}, so its report carries {@code ORDER-2}. Three orders, three {@code OrderID}s,
 *       ten reports - which is what makes the AMPS {@code fix.order.state} topic, keyed on
 *       {@code /37}, collapse ten publishes into three records.</li>
 *   <li><strong>{@code AvgPx(6)} is the quantity-weighted average of the fills so far</strong>, not
 *       the last price. Row 13 is {@code (50 x 101.25 + 100 x 101.75) / 150 = 101.5833}, rounded to
 *       four decimal places.</li>
 *   <li><strong>The replace and the cancel reference earlier orders.</strong> The bridge keys
 *       {@code fix.orders} by {@code ClOrdID}, so a scenario in which every request has a distinct
 *       {@code ClOrdID} but two of them also carry an {@code OrigClOrdID} is what proves the SOW
 *       keying is doing something.</li>
 * </ul>
 *
 * @param idPrefix  the {@code ClOrdID} prefix; identifiers are {@code <prefix>-1}..{@code <prefix>-5}.
 * @param symbol    the instrument every message names.
 * @param side      {@code Side(54)} for every message.
 * @param baseQty   the first order's quantity; the second and third are 2x and 3x, the amend 1.5x,
 *                  and half of each order trades on its first fill.
 * @param basePrice the first order's price; the second and third are +1.25 and +2.50, the amend +0.50.
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

    /** How many order events the scenario sends: three {@code 35=D}, one {@code 35=G}, one {@code 35=F}. */
    public static final int ORDER_COUNT = 5;

    /** How many {@code ExecutionReport(35=8)}s the scenario sends. */
    public static final int EXECUTION_REPORT_COUNT = 10;

    /** How many application messages the scenario sends in total. */
    public static final int MESSAGE_COUNT = ORDER_COUNT + EXECUTION_REPORT_COUNT;

    /** The {@code OrderID(37)} prefix: one per <em>order</em>, so {@code ORDER-1}..{@code ORDER-3}. */
    public static final String ORDER_ID_PREFIX = "ORDER";

    /** The {@code ExecID(17)} prefix: one per report, so {@code EXEC-1}..{@code EXEC-10}. */
    public static final String EXEC_ID_PREFIX = "EXEC";

    /**
     * The five order events of {@link #DEFAULT} without the reports: the stream this scenario was
     * before the reports were scripted into it, kept for the tests that are about order routing or
     * about a peer that answers, rather than about the drop copy.
     */
    public static final List<Step> ORDERS_ONLY = DEFAULT.orderSteps();

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
     * @param index 1-based position among the <em>order events</em>, not among the messages.
     * @return the {@code ClOrdID} of that order event, e.g. {@code ORD-4}.
     */
    public String clOrdId(final int index)
    {
        if (index < 1 || index > ORDER_COUNT)
        {
            throw new IllegalArgumentException("index must be in 1.." + ORDER_COUNT + " but was " + index);
        }
        return idPrefix + '-' + index;
    }

    /** @return the five {@code ClOrdID}s, in the order they are sent. */
    public List<String> clOrdIds()
    {
        return List.of(clOrdId(1), clOrdId(2), clOrdId(3), clOrdId(4), clOrdId(5));
    }

    /** @return the ten {@code ExecID}s, in the order they are sent. */
    public List<String> execIds()
    {
        return reports().stream().map(Report::execId).toList();
    }

    /** @return the fifteen steps, in order. */
    public List<Step> steps()
    {
        final double qty1 = baseQty;
        final double qty2 = baseQty * 2;
        final double qty3 = baseQty * 3;
        final double amendQty = baseQty * 1.5;

        final double price1 = basePrice;
        final double price2 = basePrice + 1.25;
        final double price3 = basePrice + 2.50;
        final double amendPrice = basePrice + 0.50;

        // Half of each order trades first, at the order's own price.
        final double fill1 = qty1 / 2;
        final double fill2 = qty2 / 2;
        final double fill3 = qty3 / 2;
        // ORD-3 completes on its own; ORD-1 completes after the amend, at the amended price.
        final double amendFill = amendQty - fill1;

        return List.of(
            new NewOrder(clOrdId(1), symbol, side, qty1, price1),
            report(1, 1, ExecEvent.ACKNOWLEDGED, Orders.ORD_STATUS_NEW,
                qty1, 0, 0, qty1, 0, 0, clOrdId(1), null),
            report(1, 2, ExecEvent.PARTIALLY_FILLED, Orders.ORD_STATUS_PARTIALLY_FILLED,
                qty1, fill1, price1, qty1 - fill1, fill1, price1, clOrdId(1), null),

            new NewOrder(clOrdId(2), symbol, side, qty2, price2),
            report(2, 3, ExecEvent.ACKNOWLEDGED, Orders.ORD_STATUS_NEW,
                qty2, 0, 0, qty2, 0, 0, clOrdId(2), null),
            report(2, 4, ExecEvent.PARTIALLY_FILLED, Orders.ORD_STATUS_PARTIALLY_FILLED,
                qty2, fill2, price2, qty2 - fill2, fill2, price2, clOrdId(2), null),

            new NewOrder(clOrdId(3), symbol, side, qty3, price3),
            report(3, 5, ExecEvent.ACKNOWLEDGED, Orders.ORD_STATUS_NEW,
                qty3, 0, 0, qty3, 0, 0, clOrdId(3), null),
            report(3, 6, ExecEvent.PARTIALLY_FILLED, Orders.ORD_STATUS_PARTIALLY_FILLED,
                qty3, fill3, price3, qty3 - fill3, fill3, price3, clOrdId(3), null),
            report(3, 7, ExecEvent.FILLED, Orders.ORD_STATUS_FILLED,
                qty3, qty3 - fill3, price3, 0, qty3, price3, clOrdId(3), null),

            new Replace(clOrdId(1), clOrdId(4), symbol, side, amendQty, amendPrice),
            // The amend acknowledgement reports the order as it now stands: a new quantity, but the
            // fill it already had. OrdStatus stays "partially filled"; only ExecType says "replaced".
            report(1, 8, ExecEvent.REPLACED, Orders.ORD_STATUS_PARTIALLY_FILLED,
                amendQty, 0, 0, amendQty - fill1, fill1, price1, clOrdId(4), clOrdId(1)),
            report(1, 9, ExecEvent.FILLED, Orders.ORD_STATUS_FILLED,
                amendQty, amendFill, amendPrice, 0, amendQty,
                weightedAvgPx(fill1, price1, amendFill, amendPrice), clOrdId(4), null),

            new Cancel(clOrdId(2), clOrdId(5), symbol, side, qty2),
            // ORD-2 was half filled before it was cancelled, so the acknowledgement reports what
            // traded rather than a clean zero.
            report(2, 10, ExecEvent.CANCELED, Orders.ORD_STATUS_CANCELED,
                qty2, 0, 0, 0, fill2, price2, clOrdId(5), clOrdId(2)));
    }

    /** @return the five order events, in order: the stream without the execution reports. */
    public List<Step> orderSteps()
    {
        return steps().stream().filter(step -> !(step instanceof Report)).toList();
    }

    /** @return the ten execution reports, in order. */
    public List<Report> reports()
    {
        return steps().stream().filter(Report.class::isInstance).map(Report.class::cast).toList();
    }

    /**
     * @return the {@code MsgType(35)} of each step, in order:
     *         {@code [D, 8, 8, D, 8, 8, D, 8, 8, 8, G, 8, 8, F, 8]}.
     */
    public List<String> msgTypes()
    {
        return steps().stream().map(Step::msgType).toList();
    }

    /**
     * @param orderIndex 1-based index of the <em>order</em> the report belongs to, which is what
     *                   {@code OrderID(37)} counts: 1, 2 or 3.
     * @param execIndex  1-based index of the report itself, which is what {@code ExecID(17)} counts.
     */
    private Report report(
        final int orderIndex,
        final int execIndex,
        final ExecEvent execEvent,
        final char ordStatus,
        final double orderQty,
        final double lastQty,
        final double lastPx,
        final double leavesQty,
        final double cumQty,
        final double avgPx,
        final String clOrdId,
        final String origClOrdId)
    {
        return new Report(
            ORDER_ID_PREFIX + '-' + orderIndex,
            EXEC_ID_PREFIX + '-' + execIndex,
            execEvent,
            ordStatus,
            orderQty,
            lastQty,
            lastPx,
            leavesQty,
            cumQty,
            avgPx,
            clOrdId,
            origClOrdId,
            symbol,
            side);
    }

    /**
     * @return the quantity-weighted average of two fills, rounded to four decimal places - which is
     *         what {@code AvgPx(6)} means, and is not the last price.
     */
    private static double weightedAvgPx(
        final double firstQty, final double firstPx, final double secondQty, final double secondPx)
    {
        final double average = (firstQty * firstPx + secondQty * secondPx) / (firstQty + secondQty);
        return Math.round(average * 10_000.0) / 10_000.0;
    }

    /** One message in the scenario. */
    public sealed interface Step permits NewOrder, Replace, Cancel, Report
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

    /**
     * An {@code ExecutionReport(35=8)} on the drop copy stream: what the venue said happened to one
     * order, sent onwards to the consumer.
     *
     * <p>{@code ExecType(150)} is carried semantically as an {@link ExecEvent} rather than as a
     * character, because the character is version dependent - a partial fill is {@code 1} on FIX 4.2
     * and {@code F} on FIX 4.4. {@code OrdStatus(39)} is not: it is the same value in both.
     *
     * @param orderId     {@code OrderID(37)}: the venue's identifier, one per order for its whole life.
     * @param execId      {@code ExecID(17)}: unique per report, and the SOW key of {@code fix.execs}.
     * @param execEvent   what happened; {@link QfjVersion#execType(ExecEvent)} renders it.
     * @param ordStatus   {@code OrdStatus(39)}: the order's state after this report.
     * @param orderQty    {@code OrderQty(38)}: the order's quantity as it now stands.
     * @param lastQty     {@code LastShares/LastQty(32)}: what traded on this report, 0 if nothing did.
     * @param lastPx      {@code LastPx(31)}: at what price, 0 if nothing traded.
     * @param leavesQty   {@code LeavesQty(151)}: what is still working.
     * @param cumQty      {@code CumQty(14)}: what has traded in total.
     * @param avgPx       {@code AvgPx(6)}: the quantity-weighted average of the fills so far.
     * @param clOrdId     {@code ClOrdID(11)}: the request this report answers.
     * @param origClOrdId {@code OrigClOrdID(41)}, or null when the report carries none.
     * @param symbol      {@code Symbol(55)}.
     * @param side        {@code Side(54)}.
     */
    public record Report(
        String orderId,
        String execId,
        ExecEvent execEvent,
        char ordStatus,
        double orderQty,
        double lastQty,
        double lastPx,
        double leavesQty,
        double cumQty,
        double avgPx,
        String clOrdId,
        String origClOrdId,
        String symbol,
        char side) implements Step
    {
        @Override
        public String msgType()
        {
            return Tags.MSG_TYPE_EXECUTION_REPORT;
        }

        /**
         * @return true if something traded on this report, and {@code LastQty(32)} and
         *         {@code LastPx(31)} therefore belong on the wire. An acknowledgement, an amend
         *         acknowledgement and a cancel acknowledgement report no trade and carry neither.
         */
        public boolean traded()
        {
            return lastQty > 0;
        }
    }
}
