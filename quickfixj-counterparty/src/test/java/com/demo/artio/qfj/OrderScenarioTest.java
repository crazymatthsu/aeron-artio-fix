package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;
import quickfix.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderScenarioTest
{
    private static final OrderScenario SCENARIO = OrderScenario.DEFAULT;

    @Test
    void theOrderEventsAreThreeNewOrdersOneReplaceAndOneCancel()
    {
        // The execution reports interleaved between them are DropCopyScenarioTest's subject; this
        // class is about the order events and the identifiers, which the reports did not change.
        assertAll(
            () -> assertEquals(OrderScenario.ORDER_COUNT, SCENARIO.orderSteps().size()),
            () -> assertEquals(List.of("D", "D", "D", "G", "F"),
                SCENARIO.orderSteps().stream().map(OrderScenario.Step::msgType).toList()),
            () -> assertEquals(OrderScenario.MESSAGE_COUNT, SCENARIO.steps().size()));
    }

    @Test
    void clOrdIdsAreDeterministicSoATestCanNameThemBeforeAnythingRuns()
    {
        assertAll(
            () -> assertEquals(List.of("ORD-1", "ORD-2", "ORD-3", "ORD-4", "ORD-5"), SCENARIO.clOrdIds()),
            () -> assertEquals("ORD-3", SCENARIO.clOrdId(3)));
    }

    @Test
    void theReplaceAndTheCancelReferenceTheFirstTwoOrders()
    {
        final List<OrderScenario.Step> steps = SCENARIO.orderSteps();

        assertAll(
            () -> assertEquals("ORD-1", ((OrderScenario.Replace)steps.get(3)).origClOrdId()),
            () -> assertEquals("ORD-4", steps.get(3).clOrdId()),
            () -> assertEquals("ORD-2", ((OrderScenario.Cancel)steps.get(4)).origClOrdId()),
            () -> assertEquals("ORD-5", steps.get(4).clOrdId()));
    }

    @Test
    void theThreeNewOrdersScaleQuantityAndPriceFromTheBaseValues()
    {
        final List<OrderScenario.Step> steps = SCENARIO.orderSteps();

        assertAll(
            () -> assertEquals(100.0, ((OrderScenario.NewOrder)steps.get(0)).qty()),
            () -> assertEquals(200.0, ((OrderScenario.NewOrder)steps.get(1)).qty()),
            () -> assertEquals(300.0, ((OrderScenario.NewOrder)steps.get(2)).qty()),
            () -> assertEquals(101.25, ((OrderScenario.NewOrder)steps.get(0)).price()),
            () -> assertEquals(102.50, ((OrderScenario.NewOrder)steps.get(1)).price()),
            () -> assertEquals(103.75, ((OrderScenario.NewOrder)steps.get(2)).price()));
    }

    @Test
    void aCustomPrefixChangesEveryClOrdId()
    {
        final OrderScenario scenario = new OrderScenario("XYZ", "VOD.L", Orders.SIDE_SELL, 50, 12.5);

        assertAll(
            () -> assertEquals(List.of("XYZ-1", "XYZ-2", "XYZ-3", "XYZ-4", "XYZ-5"), scenario.clOrdIds()),
            () -> assertEquals("VOD.L", ((OrderScenario.NewOrder)scenario.steps().get(0)).symbol()),
            () -> assertEquals(Orders.SIDE_SELL, scenario.steps().get(0) instanceof OrderScenario.NewOrder n ?
                n.side() : '?'));
    }

    @Test
    void anIndexOutsideOneToFiveIsRejected()
    {
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SCENARIO.clOrdId(0)).getMessage().contains("index must be in 1..5")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SCENARIO.clOrdId(6)).getMessage().contains("index must be in 1..5")));
    }

    @Test
    void blankOrNonPositiveScenarioComponentsAreRejected()
    {
        assertAll(
            () -> assertEquals("idPrefix must not be blank", assertThrows(IllegalArgumentException.class,
                () -> new OrderScenario(" ", "MSFT", '1', 1, 1)).getMessage()),
            () -> assertEquals("symbol must not be blank", assertThrows(IllegalArgumentException.class,
                () -> new OrderScenario("ORD", "", '1', 1, 1)).getMessage()),
            () -> assertEquals("baseQty must be positive but was 0.0",
                assertThrows(IllegalArgumentException.class,
                    () -> new OrderScenario("ORD", "MSFT", '1', 0, 1)).getMessage()),
            () -> assertEquals("basePrice must be positive but was -1.0",
                assertThrows(IllegalArgumentException.class,
                    () -> new OrderScenario("ORD", "MSFT", '1', 1, -1)).getMessage()));
    }

    @Test
    void everyStepTurnsIntoAMessageOfItsOwnTypeCarryingItsClOrdId()
    {
        for (final OrderScenario.Step step : SCENARIO.steps())
        {
            final Message message = Orders.toMessage(QfjVersion.FIX42, step);
            final String raw = message.toString();

            assertAll(
                () -> assertEquals(step.msgType(), RawFix.field(raw, Tags.MSG_TYPE)),
                () -> assertEquals(step.clOrdId(), RawFix.field(raw, Tags.CL_ORD_ID)));
        }
    }

    @Test
    void aFix42ScenarioMessageCarriesHandlInstAndAFix44OneDoesNot()
    {
        final OrderScenario.Step firstOrder = SCENARIO.steps().get(0);

        final String fix42 = Orders.toMessage(QfjVersion.FIX42, firstOrder).toString();
        final String fix44 = Orders.toMessage(QfjVersion.FIX44, firstOrder).toString();

        assertAll(
            () -> assertEquals("1", RawFix.field(fix42, Tags.HANDL_INST)),
            () -> assertEquals(null, RawFix.field(fix44, Tags.HANDL_INST)),
            () -> assertFalse(fix44.contains("\00121=")));
    }
}
