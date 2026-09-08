package com.demo.artio.qfj;

import com.demo.artio.qfj.QfjVersion.ExecEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop copy stream, row by row against the table in {@code docs/07-drop-copy-execution-reports.md}.
 *
 * <p>What this pins down, and why each part is worth pinning:
 *
 * <ul>
 *   <li><strong>The fifteen messages and their order.</strong> Every demo, every bridge test and the
 *       AMPS publish counts in {@code docs/07} section 3 are read off this sequence; a step inserted
 *       or reordered silently changes all of them.</li>
 *   <li><strong>{@code OrderID(37)} continuity.</strong> {@code ORD-4} amends {@code ORD-1} and so
 *       reports {@code ORDER-1}; {@code ORD-5} cancels {@code ORD-2} and so reports {@code ORDER-2}.
 *       That is the whole reason {@code fix.order.state} collapses ten publishes into three
 *       records - if the reports carried five order ids the topic would prove nothing.</li>
 *   <li><strong>The running quantities.</strong> {@code LeavesQty}, {@code CumQty} and a
 *       quantity-weighted {@code AvgPx} that is <em>not</em> the last price.</li>
 *   <li><strong>The version differences on the wire</strong>, which are the reason a report is not
 *       simply a fixed byte string: {@code ExecTransType(20)} on FIX 4.2 only, and {@code 1}/{@code 2}
 *       versus {@code F}/{@code F} for a partial fill and a fill.</li>
 *   <li><strong>That none of it is hard coded.</strong> A scenario built with other quantities and
 *       prices still produces a stream whose fills add up.</li>
 * </ul>
 */
class DropCopyScenarioTest
{
    private static final OrderScenario SCENARIO = OrderScenario.DEFAULT;

    private static List<OrderScenario.Report> reports()
    {
        return SCENARIO.reports();
    }

    @Test
    void theStreamIsFifteenMessagesFiveOfThemOrderEventsAndTenOfThemExecutionReports()
    {
        assertAll(
            () -> assertEquals(15, OrderScenario.MESSAGE_COUNT),
            () -> assertEquals(5, OrderScenario.ORDER_COUNT),
            () -> assertEquals(10, OrderScenario.EXECUTION_REPORT_COUNT),
            () -> assertEquals(OrderScenario.MESSAGE_COUNT, SCENARIO.steps().size()),
            () -> assertEquals(OrderScenario.ORDER_COUNT, SCENARIO.orderSteps().size()),
            () -> assertEquals(OrderScenario.EXECUTION_REPORT_COUNT, reports().size()),
            () -> assertEquals(List.of("D", "8", "8", "D", "8", "8", "D", "8", "8", "8", "G", "8", "8", "F", "8"),
                SCENARIO.msgTypes()));
    }

    @Test
    void everyMessageCarriesTheClOrdIdAndOrigClOrdIdTheTableGivesIt()
    {
        final List<OrderScenario.Step> steps = SCENARIO.steps();

        assertAll(
            () -> assertEquals(
                List.of("ORD-1", "ORD-1", "ORD-1", "ORD-2", "ORD-2", "ORD-2", "ORD-3", "ORD-3", "ORD-3",
                    "ORD-3", "ORD-4", "ORD-4", "ORD-4", "ORD-5", "ORD-5"),
                steps.stream().map(OrderScenario.Step::clOrdId).toList()),
            // Only rows 11, 12, 14 and 15 carry tag 41: the two requests that reference an earlier
            // order, and the two acknowledgements of those requests. Row 13 is a fill of the amended
            // order and no longer references the order it replaced.
            () -> assertEquals("ORD-1", ((OrderScenario.Replace)steps.get(10)).origClOrdId()),
            () -> assertEquals("ORD-1", reports().get(7).origClOrdId()),
            () -> assertNull(reports().get(8).origClOrdId()),
            () -> assertEquals("ORD-2", ((OrderScenario.Cancel)steps.get(13)).origClOrdId()),
            () -> assertEquals("ORD-2", reports().get(9).origClOrdId()));
    }

    @Test
    void theTenReportsCarryExecIdsOneToTenInOrder()
    {
        assertEquals(
            List.of("EXEC-1", "EXEC-2", "EXEC-3", "EXEC-4", "EXEC-5",
                "EXEC-6", "EXEC-7", "EXEC-8", "EXEC-9", "EXEC-10"),
            SCENARIO.execIds());
    }

    @Test
    void anOrderKeepsOneOrderIdForItsWholeLifeSoTheAmendAndTheCancelReportAgainstTheOriginal()
    {
        final List<OrderScenario.Report> reports = reports();

        assertAll(
            // Three orders, three OrderIDs, ten reports.
            () -> assertEquals(
                List.of("ORDER-1", "ORDER-1", "ORDER-2", "ORDER-2", "ORDER-3",
                    "ORDER-3", "ORDER-3", "ORDER-1", "ORDER-1", "ORDER-2"),
                reports.stream().map(OrderScenario.Report::orderId).toList()),
            () -> assertEquals(3, reports.stream().map(OrderScenario.Report::orderId).distinct().count()),
            // ORD-4 amends ORD-1 ...
            () -> assertEquals("ORDER-1", reports.get(7).orderId()),
            () -> assertEquals("ORDER-1", reports.get(8).orderId()),
            // ... and ORD-5 cancels ORD-2.
            () -> assertEquals("ORDER-2", reports.get(9).orderId()));
    }

    @Test
    void everyReportSaysWhatHappenedAndWhatTheOrderStatusIsAfterwards()
    {
        final List<OrderScenario.Report> reports = reports();

        assertAll(
            () -> assertEquals(
                List.of(ExecEvent.ACKNOWLEDGED, ExecEvent.PARTIALLY_FILLED,
                    ExecEvent.ACKNOWLEDGED, ExecEvent.PARTIALLY_FILLED,
                    ExecEvent.ACKNOWLEDGED, ExecEvent.PARTIALLY_FILLED, ExecEvent.FILLED,
                    ExecEvent.REPLACED, ExecEvent.FILLED,
                    ExecEvent.CANCELED),
                reports.stream().map(OrderScenario.Report::execEvent).toList()),
            () -> assertEquals("0101012124",
                reports.stream().map(r -> String.valueOf(r.ordStatus())).reduce("", String::concat),
                "OrdStatus down the table: new, partial, new, partial, new, partial, filled, " +
                    "partial (the amend ack), filled, canceled"));
    }

    @Test
    void theRunningQuantitiesAddUpDownTheWholeStream()
    {
        final List<OrderScenario.Report> reports = reports();

        assertAll(
            () -> assertEquals(List.of(100.0, 100.0, 200.0, 200.0, 300.0, 300.0, 300.0, 150.0, 150.0, 200.0),
                reports.stream().map(OrderScenario.Report::orderQty).toList(), "OrderQty(38)"),
            () -> assertEquals(List.of(0.0, 50.0, 0.0, 100.0, 0.0, 150.0, 150.0, 0.0, 100.0, 0.0),
                reports.stream().map(OrderScenario.Report::lastQty).toList(), "LastQty(32)"),
            () -> assertEquals(List.of(0.0, 101.25, 0.0, 102.50, 0.0, 103.75, 103.75, 0.0, 101.75, 0.0),
                reports.stream().map(OrderScenario.Report::lastPx).toList(), "LastPx(31)"),
            () -> assertEquals(List.of(100.0, 50.0, 200.0, 100.0, 300.0, 150.0, 0.0, 100.0, 0.0, 0.0),
                reports.stream().map(OrderScenario.Report::leavesQty).toList(), "LeavesQty(151)"),
            () -> assertEquals(List.of(0.0, 50.0, 0.0, 100.0, 0.0, 150.0, 300.0, 50.0, 150.0, 100.0),
                reports.stream().map(OrderScenario.Report::cumQty).toList(), "CumQty(14)"),
            () -> assertEquals(
                List.of(0.0, 101.25, 0.0, 102.50, 0.0, 103.75, 103.75, 101.25, 101.5833, 102.50),
                reports.stream().map(OrderScenario.Report::avgPx).toList(), "AvgPx(6)"));
    }

    @Test
    void everyReportThatSaysNothingTradedCarriesNoLastQtyAndEveryFillDoes()
    {
        assertEquals(
            List.of(false, true, false, true, false, true, true, false, true, false),
            reports().stream().map(OrderScenario.Report::traded).toList());
    }

    @Test
    void theAvgPxOfTheAmendedOrdersFillIsWeightedByQuantityAndNotTheLastPrice()
    {
        final OrderScenario.Report finalFill = reports().get(8);

        // (50 x 101.25 + 100 x 101.75) / 150 = 101.58333..., to four decimal places.
        assertAll(
            () -> assertEquals(101.5833, finalFill.avgPx()),
            () -> assertEquals(101.75, finalFill.lastPx(), "the last price is the amended price"),
            () -> assertEquals(150.0, finalFill.cumQty()),
            () -> assertEquals((50 * 101.25 + 100 * 101.75) / 150, finalFill.avgPx(), 0.00005));
    }

    @Test
    void aCancelledOrderReportsWhatItHadAlreadyTradedRatherThanACleanZero()
    {
        final OrderScenario.Report cancelAck = reports().get(9);

        assertAll(
            () -> assertEquals(ExecEvent.CANCELED, cancelAck.execEvent()),
            () -> assertEquals(100.0, cancelAck.cumQty(), "ORD-2 was half filled before it was cancelled"),
            () -> assertEquals(0.0, cancelAck.leavesQty()),
            () -> assertEquals(102.50, cancelAck.avgPx()));
    }

    @Test
    void ordersOnlyIsTheFiveOrderEventsWithoutAnyReport()
    {
        assertAll(
            () -> assertEquals(OrderScenario.ORDER_COUNT, OrderScenario.ORDERS_ONLY.size()),
            () -> assertEquals(List.of("D", "D", "D", "G", "F"),
                OrderScenario.ORDERS_ONLY.stream().map(OrderScenario.Step::msgType).toList()),
            () -> assertEquals(SCENARIO.clOrdIds(),
                OrderScenario.ORDERS_ONLY.stream().map(OrderScenario.Step::clOrdId).toList()),
            () -> assertEquals(OrderScenario.ORDERS_ONLY, SCENARIO.orderSteps()));
    }

    @Test
    void aFix42ReportCarriesExecTransTypeAndAFix44OneDoesNot()
    {
        final OrderScenario.Report acknowledgement = reports().get(0);

        final String fix42 = Orders.toMessage(QfjVersion.FIX42, acknowledgement).toString();
        final String fix44 = Orders.toMessage(QfjVersion.FIX44, acknowledgement).toString();

        assertAll(
            () -> assertEquals("0", RawFix.field(fix42, Tags.EXEC_TRANS_TYPE)),
            () -> assertNull(RawFix.field(fix44, Tags.EXEC_TRANS_TYPE),
                "ExecTransType does not exist in FIX 4.4; sending it would be an unknown field"),
            () -> assertEquals("8", RawFix.field(fix42, Tags.MSG_TYPE)),
            () -> assertEquals("8", RawFix.field(fix44, Tags.MSG_TYPE)));
    }

    @Test
    void fix42DistinguishesAPartialFillFromAFillAndFix44CallsBothATrade()
    {
        final OrderScenario.Report partial = reports().get(1);
        final OrderScenario.Report fill = reports().get(6);

        assertAll(
            () -> assertEquals("1", execType(QfjVersion.FIX42, partial)),
            () -> assertEquals("2", execType(QfjVersion.FIX42, fill)),
            () -> assertEquals("F", execType(QfjVersion.FIX44, partial)),
            () -> assertEquals("F", execType(QfjVersion.FIX44, fill)),
            // OrdStatus is what carries the distinction on FIX 4.4, and it is the same on both.
            () -> assertEquals("1", RawFix.field(Orders.toMessage(QfjVersion.FIX44, partial).toString(),
                Tags.ORD_STATUS)),
            () -> assertEquals("2", RawFix.field(Orders.toMessage(QfjVersion.FIX44, fill).toString(),
                Tags.ORD_STATUS)));
    }

    @Test
    void theOtherThreeExecTypesAreTheSameCharacterOnBothVersions()
    {
        assertAll(
            () -> assertEquals("0", execType(QfjVersion.FIX42, reports().get(0))),
            () -> assertEquals("0", execType(QfjVersion.FIX44, reports().get(0))),
            () -> assertEquals("5", execType(QfjVersion.FIX42, reports().get(7))),
            () -> assertEquals("5", execType(QfjVersion.FIX44, reports().get(7))),
            () -> assertEquals("4", execType(QfjVersion.FIX42, reports().get(9))),
            () -> assertEquals("4", execType(QfjVersion.FIX44, reports().get(9))));
    }

    @Test
    void aFillCarriesTagThirtyTwoAndAnAcknowledgementDoesNot()
    {
        final String acknowledgement = Orders.toMessage(QfjVersion.FIX42, reports().get(0)).toString();
        final String partialFill = Orders.toMessage(QfjVersion.FIX42, reports().get(1)).toString();
        final String amendAck = Orders.toMessage(QfjVersion.FIX44, reports().get(7)).toString();

        assertAll(
            () -> assertNull(RawFix.field(acknowledgement, Tags.LAST_QTY)),
            () -> assertNull(RawFix.field(acknowledgement, Tags.LAST_PX)),
            () -> assertEquals("50", RawFix.field(partialFill, Tags.LAST_QTY)),
            () -> assertEquals("101.25", RawFix.field(partialFill, Tags.LAST_PX)),
            () -> assertNull(RawFix.field(amendAck, Tags.LAST_QTY), "an amend acknowledgement is not a trade"),
            () -> assertNull(RawFix.field(amendAck, Tags.LAST_PX)));
    }

    @Test
    void aReportCarriesEveryFieldTheDictionariesRequireOfAnExecutionReport()
    {
        // FIX42.xml and FIX44.xml both mark 37, 17, 150, 39, 55 (in Instrument for 4.4), 54, 151,
        // 14 and 6 required="Y"; 4.2 adds 20.
        for (final QfjVersion version : QfjVersion.values())
        {
            final String raw = Orders.toMessage(version, reports().get(8)).toString();

            assertAll(
                () -> assertEquals("ORDER-1", RawFix.field(raw, Tags.ORDER_ID)),
                () -> assertEquals("EXEC-9", RawFix.field(raw, Tags.EXEC_ID)),
                () -> assertEquals(String.valueOf(version.fillExecType()), RawFix.field(raw, Tags.EXEC_TYPE)),
                () -> assertEquals("2", RawFix.field(raw, Tags.ORD_STATUS)),
                () -> assertEquals("MSFT", RawFix.field(raw, Tags.SYMBOL)),
                () -> assertEquals("1", RawFix.field(raw, Tags.SIDE)),
                () -> assertEquals("0", RawFix.field(raw, Tags.LEAVES_QTY)),
                () -> assertEquals("150", RawFix.field(raw, Tags.CUM_QTY)),
                () -> assertEquals("101.5833", RawFix.field(raw, Tags.AVG_PX)),
                () -> assertEquals("ORD-4", RawFix.field(raw, Tags.CL_ORD_ID)),
                () -> assertEquals("150", RawFix.field(raw, Tags.ORDER_QTY)),
                () -> assertEquals("100", RawFix.field(raw, Tags.LAST_QTY)),
                () -> assertEquals("101.75", RawFix.field(raw, Tags.LAST_PX)),
                () -> assertTrue(RawFix.field(raw, Tags.TRANSACT_TIME).startsWith("20"),
                    "a UTC timestamp, not an empty tag"),
                () -> assertEquals(version == QfjVersion.FIX42 ? "0" : null,
                    RawFix.field(raw, Tags.EXEC_TRANS_TYPE)));
        }
    }

    @Test
    void aScenarioWithOtherQuantitiesAndPricesStillProducesAStreamThatAddsUp()
    {
        final OrderScenario scenario = new OrderScenario("XYZ", "VOD.L", Orders.SIDE_SELL, 50, 12.5);
        final List<OrderScenario.Report> reports = scenario.reports();

        assertAll(
            () -> assertEquals(OrderScenario.MESSAGE_COUNT, scenario.steps().size()),
            () -> assertEquals(List.of("XYZ-1", "XYZ-2", "XYZ-3", "XYZ-4", "XYZ-5"), scenario.clOrdIds()),
            // Half of the 50-share order trades at 12.5, then the amend to 75 completes at 13.0.
            () -> assertEquals(25.0, reports.get(1).lastQty()),
            () -> assertEquals(12.5, reports.get(1).avgPx()),
            () -> assertEquals(75.0, reports.get(7).orderQty(), "the amend is 1.5x the base quantity"),
            () -> assertEquals(50.0, reports.get(7).leavesQty(), "75 asked for, 25 already traded"),
            () -> assertEquals(50.0, reports.get(8).lastQty()),
            () -> assertEquals(13.0, reports.get(8).lastPx()),
            // (25 x 12.5 + 50 x 13.0) / 75 = 12.8333...
            () -> assertEquals(12.8333, reports.get(8).avgPx()),
            () -> assertEquals("VOD.L", reports.get(0).symbol()),
            () -> assertEquals(Orders.SIDE_SELL, reports.get(0).side()));

        assertQuantitiesAccountForTheOrder(scenario);
    }

    @Test
    void whatIsLeftPlusWhatHasTradedIsWhatWasAskedForOnEveryReportButTheCancel()
    {
        assertQuantitiesAccountForTheOrder(SCENARIO);
    }

    private static void assertQuantitiesAccountForTheOrder(final OrderScenario scenario)
    {
        for (final OrderScenario.Report report : scenario.reports())
        {
            if (report.execEvent() == ExecEvent.CANCELED)
            {
                // A cancel is where the arithmetic deliberately stops: the unfilled remainder is
                // gone rather than traded, so LeavesQty is 0 while CumQty is short of OrderQty.
                continue;
            }
            assertEquals(report.orderQty(), report.leavesQty() + report.cumQty(), 1e-9,
                () -> "LeavesQty + CumQty must be OrderQty on " + report.execId());
        }
    }

    private static String execType(final QfjVersion version, final OrderScenario.Report report)
    {
        return RawFix.field(Orders.toMessage(version, report).toString(), Tags.EXEC_TYPE);
    }
}
