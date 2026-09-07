package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;
import quickfix.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReportsTest
{
    private static Message order(final QfjVersion version, final String clOrdId)
    {
        return Orders.newOrderSingle(version, clOrdId, "MSFT", Orders.SIDE_BUY, 100, 101.25);
    }

    @Test
    void aNewOrderSingleIsAnsweredWithAnAcknowledgementAndThenAFill() throws Exception
    {
        final List<Message> reports = new ExecutionReports(QfjVersion.FIX42).repliesTo(order(QfjVersion.FIX42, "ORD-1"));

        assertEquals(2, reports.size());
        final String acknowledged = reports.get(0).toString();
        final String filled = reports.get(1).toString();

        assertAll(
            () -> assertEquals("8", RawFix.field(acknowledged, Tags.MSG_TYPE)),
            () -> assertEquals("0", RawFix.field(acknowledged, Tags.EXEC_TYPE)),
            () -> assertEquals("0", RawFix.field(acknowledged, Tags.ORD_STATUS)),
            () -> assertEquals("100", RawFix.field(acknowledged, Tags.LEAVES_QTY)),
            () -> assertEquals("0", RawFix.field(acknowledged, Tags.CUM_QTY)),
            () -> assertEquals("2", RawFix.field(filled, Tags.EXEC_TYPE)),
            () -> assertEquals("2", RawFix.field(filled, Tags.ORD_STATUS)),
            () -> assertEquals("0", RawFix.field(filled, Tags.LEAVES_QTY)),
            () -> assertEquals("100", RawFix.field(filled, Tags.CUM_QTY)),
            () -> assertEquals("101.25", RawFix.field(filled, Tags.AVG_PX)),
            () -> assertEquals("100", RawFix.field(filled, Tags.LAST_QTY)),
            () -> assertEquals("101.25", RawFix.field(filled, Tags.LAST_PX)));
    }

    @Test
    void everyRequiredFieldOfTheFix42ExecutionReportIsPresent() throws Exception
    {
        final Message report = new ExecutionReports(QfjVersion.FIX42)
            .repliesTo(order(QfjVersion.FIX42, "ORD-1")).get(0);
        final String raw = report.toString();

        // FIX42.xml: OrderID, ExecID, ExecTransType, ExecType, OrdStatus, Symbol, Side, LeavesQty,
        // CumQty, AvgPx are required="Y".
        assertAll(
            () -> assertEquals("ORDER-1", RawFix.field(raw, Tags.ORDER_ID)),
            () -> assertEquals("EXEC-1", RawFix.field(raw, Tags.EXEC_ID)),
            () -> assertEquals("0", RawFix.field(raw, Tags.EXEC_TRANS_TYPE)),
            () -> assertEquals("0", RawFix.field(raw, Tags.EXEC_TYPE)),
            () -> assertEquals("0", RawFix.field(raw, Tags.ORD_STATUS)),
            () -> assertEquals("MSFT", RawFix.field(raw, Tags.SYMBOL)),
            () -> assertEquals("1", RawFix.field(raw, Tags.SIDE)),
            () -> assertEquals("100", RawFix.field(raw, Tags.LEAVES_QTY)),
            () -> assertEquals("0", RawFix.field(raw, Tags.CUM_QTY)),
            () -> assertEquals("0", RawFix.field(raw, Tags.AVG_PX)));
    }

    @Test
    void aFix44ReportOmitsExecTransTypeAndReportsAFillAsExecTypeTrade() throws Exception
    {
        final List<Message> reports = new ExecutionReports(QfjVersion.FIX44).repliesTo(order(QfjVersion.FIX44, "ORD-1"));
        final String acknowledged = reports.get(0).toString();
        final String filled = reports.get(1).toString();

        assertAll(
            // ExecTransType(20) does not exist in FIX 4.4; sending it would be an unknown field.
            () -> assertNull(RawFix.field(acknowledged, Tags.EXEC_TRANS_TYPE)),
            () -> assertNull(RawFix.field(filled, Tags.EXEC_TRANS_TYPE)),
            () -> assertEquals("F", RawFix.field(filled, Tags.EXEC_TYPE)),
            () -> assertEquals("2", RawFix.field(filled, Tags.ORD_STATUS)));
    }

    @Test
    void aCancelReplaceIsAnsweredWithOneReplacedReportCarryingBothClOrdIds() throws Exception
    {
        final ExecutionReports reports = new ExecutionReports(QfjVersion.FIX42);
        reports.repliesTo(order(QfjVersion.FIX42, "ORD-1"));

        final List<Message> replies = reports.repliesTo(Orders.cancelReplace(
            QfjVersion.FIX42, "ORD-1", "ORD-4", "MSFT", Orders.SIDE_BUY, 150, 101.75));

        assertEquals(1, replies.size());
        final String raw = replies.get(0).toString();
        assertAll(
            () -> assertEquals("5", RawFix.field(raw, Tags.EXEC_TYPE)),
            () -> assertEquals("5", RawFix.field(raw, Tags.ORD_STATUS)),
            () -> assertEquals("ORD-4", RawFix.field(raw, Tags.CL_ORD_ID)),
            () -> assertEquals("ORD-1", RawFix.field(raw, Tags.ORIG_CL_ORD_ID)),
            () -> assertEquals("150", RawFix.field(raw, Tags.LEAVES_QTY)));
    }

    @Test
    void aCancelIsAnsweredWithOneCanceledReportWithNothingLeft() throws Exception
    {
        final ExecutionReports reports = new ExecutionReports(QfjVersion.FIX44);
        reports.repliesTo(order(QfjVersion.FIX44, "ORD-2"));

        final List<Message> replies = reports.repliesTo(
            Orders.cancel(QfjVersion.FIX44, "ORD-2", "ORD-5", "MSFT", Orders.SIDE_BUY, 200));

        assertEquals(1, replies.size());
        final String raw = replies.get(0).toString();
        assertAll(
            () -> assertEquals("4", RawFix.field(raw, Tags.EXEC_TYPE)),
            () -> assertEquals("4", RawFix.field(raw, Tags.ORD_STATUS)),
            () -> assertEquals("ORD-5", RawFix.field(raw, Tags.CL_ORD_ID)),
            () -> assertEquals("ORD-2", RawFix.field(raw, Tags.ORIG_CL_ORD_ID)),
            () -> assertEquals("0", RawFix.field(raw, Tags.LEAVES_QTY)));
    }

    @Test
    void anOrderKeepsOneOrderIdAcrossItsReplaceSoOrderStateIsKeyedConsistently() throws Exception
    {
        final ExecutionReports reports = new ExecutionReports(QfjVersion.FIX42);

        final String newOrderId = RawFix.field(
            reports.repliesTo(order(QfjVersion.FIX42, "ORD-1")).get(0).toString(), Tags.ORDER_ID);
        final String replacedOrderId = RawFix.field(reports.repliesTo(Orders.cancelReplace(
            QfjVersion.FIX42, "ORD-1", "ORD-4", "MSFT", Orders.SIDE_BUY, 150, 101.75))
            .get(0).toString(), Tags.ORDER_ID);
        final String secondOrderId = RawFix.field(
            reports.repliesTo(order(QfjVersion.FIX42, "ORD-2")).get(0).toString(), Tags.ORDER_ID);

        assertAll(
            () -> assertEquals(newOrderId, replacedOrderId),
            () -> assertNotEquals(newOrderId, secondOrderId));
    }

    @Test
    void execIdsAreUniqueAndSequential() throws Exception
    {
        final ExecutionReports reports = new ExecutionReports(QfjVersion.FIX42);
        final List<Message> first = reports.repliesTo(order(QfjVersion.FIX42, "ORD-1"));
        final List<Message> second = reports.repliesTo(order(QfjVersion.FIX42, "ORD-2"));

        assertAll(
            () -> assertEquals("EXEC-1", RawFix.field(first.get(0).toString(), Tags.EXEC_ID)),
            () -> assertEquals("EXEC-2", RawFix.field(first.get(1).toString(), Tags.EXEC_ID)),
            () -> assertEquals("EXEC-3", RawFix.field(second.get(0).toString(), Tags.EXEC_ID)),
            () -> assertEquals("EXEC-4", RawFix.field(second.get(1).toString(), Tags.EXEC_ID)));
    }

    @Test
    void aMessageTypeTheVenueDoesNotHandleProducesNoReplyRatherThanAnError() throws Exception
    {
        final Message heartbeat = new Message();
        heartbeat.getHeader().setString(Tags.MSG_TYPE, "0");

        assertTrue(new ExecutionReports(QfjVersion.FIX42).repliesTo(heartbeat).isEmpty());
    }

    @Test
    void identifierPrefixesCanBeChosenSoTwoVenuesInOneTestDoNotCollide() throws Exception
    {
        final Message report = new ExecutionReports(QfjVersion.FIX42, "VENUE", "FILL")
            .repliesTo(order(QfjVersion.FIX42, "ORD-1")).get(0);

        assertAll(
            () -> assertEquals("VENUE-1", RawFix.field(report.toString(), Tags.ORDER_ID)),
            () -> assertEquals("FILL-1", RawFix.field(report.toString(), Tags.EXEC_ID)));
    }
}
