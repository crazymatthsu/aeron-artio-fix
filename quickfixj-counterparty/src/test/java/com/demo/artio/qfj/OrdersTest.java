package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;
import quickfix.Message;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrdersTest
{
    @Test
    void aFix42NewOrderSingleCarriesEveryFieldThatDictionaryRequires()
    {
        final String raw = Orders.newOrderSingle(
            QfjVersion.FIX42, "ORD-1", "MSFT", Orders.SIDE_BUY, 100, 101.25).toString();

        // FIX42.xml: ClOrdID, HandlInst, Symbol, Side, TransactTime, OrdType are required="Y".
        assertAll(
            () -> assertEquals("D", RawFix.field(raw, Tags.MSG_TYPE)),
            () -> assertEquals("ORD-1", RawFix.field(raw, Tags.CL_ORD_ID)),
            () -> assertEquals("1", RawFix.field(raw, Tags.HANDL_INST)),
            () -> assertEquals("MSFT", RawFix.field(raw, Tags.SYMBOL)),
            () -> assertEquals("1", RawFix.field(raw, Tags.SIDE)),
            () -> assertNotNull(RawFix.field(raw, Tags.TRANSACT_TIME)),
            () -> assertEquals("2", RawFix.field(raw, Tags.ORD_TYPE)),
            () -> assertEquals("100", RawFix.field(raw, Tags.ORDER_QTY)),
            () -> assertEquals("101.25", RawFix.field(raw, Tags.PRICE)));
    }

    @Test
    void aFix44NewOrderSingleOmitsHandlInstBecauseTheDictionaryDoesNotRequireIt()
    {
        final String raw = Orders.newOrderSingle(
            QfjVersion.FIX44, "ORD-1", "MSFT", Orders.SIDE_BUY, 100, 101.25).toString();

        assertAll(
            () -> assertNull(RawFix.field(raw, Tags.HANDL_INST)),
            () -> assertEquals("MSFT", RawFix.field(raw, Tags.SYMBOL)),
            () -> assertEquals("100", RawFix.field(raw, Tags.ORDER_QTY)));
    }

    @Test
    void aCancelReplaceCarriesBothTheOldAndTheNewClOrdId()
    {
        final String raw = Orders.cancelReplace(
            QfjVersion.FIX42, "ORD-1", "ORD-4", "MSFT", Orders.SIDE_BUY, 150, 101.75).toString();

        assertAll(
            () -> assertEquals("G", RawFix.field(raw, Tags.MSG_TYPE)),
            () -> assertEquals("ORD-1", RawFix.field(raw, Tags.ORIG_CL_ORD_ID)),
            () -> assertEquals("ORD-4", RawFix.field(raw, Tags.CL_ORD_ID)),
            () -> assertEquals("150", RawFix.field(raw, Tags.ORDER_QTY)),
            () -> assertEquals("101.75", RawFix.field(raw, Tags.PRICE)));
    }

    @Test
    void aCancelAlwaysCarriesOrderQtyBecauseFix44RequiresTheOrderQtyDataComponent()
    {
        final String fix42 = Orders.cancel(
            QfjVersion.FIX42, "ORD-2", "ORD-5", "MSFT", Orders.SIDE_BUY, 200).toString();
        final String fix44 = Orders.cancel(
            QfjVersion.FIX44, "ORD-2", "ORD-5", "MSFT", Orders.SIDE_BUY, 200).toString();

        assertAll(
            () -> assertEquals("F", RawFix.field(fix42, Tags.MSG_TYPE)),
            () -> assertEquals("200", RawFix.field(fix42, Tags.ORDER_QTY)),
            () -> assertEquals("200", RawFix.field(fix44, Tags.ORDER_QTY)),
            () -> assertEquals("ORD-2", RawFix.field(fix44, Tags.ORIG_CL_ORD_ID)));
    }

    @Test
    void aCancelHasNoHandlInstOnEitherVersionBecauseNeitherDictionaryHasIt()
    {
        assertAll(
            () -> assertNull(RawFix.field(
                Orders.cancel(QfjVersion.FIX42, "A", "B", "MSFT", '1', 1).toString(), Tags.HANDL_INST)),
            () -> assertNull(RawFix.field(
                Orders.cancel(QfjVersion.FIX44, "A", "B", "MSFT", '1', 1).toString(), Tags.HANDL_INST)));
    }

    @Test
    void theBuiltMessageHasNoCompIdsBecauseQuickFixJFillsTheHeaderWhenItSends()
    {
        final Message message = Orders.newOrderSingle(
            QfjVersion.FIX42, "ORD-1", "MSFT", Orders.SIDE_BUY, 100, 101.25);

        assertAll(
            () -> assertNull(RawFix.field(message.toString(), Tags.SENDER_COMP_ID)),
            () -> assertNull(RawFix.field(message.toString(), Tags.TARGET_COMP_ID)));
    }

    @Test
    void transactTimeIsAUtcTimestampWithMillisecondPrecision()
    {
        final String transactTime = RawFix.field(Orders.newOrderSingle(
            QfjVersion.FIX44, "ORD-1", "MSFT", Orders.SIDE_BUY, 100, 101.25).toString(), Tags.TRANSACT_TIME);

        // yyyyMMdd-HH:mm:ss.SSS
        assertAll(
            () -> assertNotNull(transactTime),
            () -> assertEquals(21, transactTime.length(), transactTime),
            () -> assertEquals('-', transactTime.charAt(8)),
            () -> assertEquals('.', transactTime.charAt(17)));
    }
}
