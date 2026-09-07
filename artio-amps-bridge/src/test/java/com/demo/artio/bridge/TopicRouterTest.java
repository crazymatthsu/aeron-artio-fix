package com.demo.artio.bridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routing rules for the {@code artio-fix} flow, and the tag guard that keeps a message off a
 * SOW topic whose key it does not carry.
 */
class TopicRouterTest
{
    private static TopicRouter defaultRouter()
    {
        return new TopicRouter(BridgeConfig.defaults());
    }

    private static List<String> topics(final List<TopicRouter.CompiledRoute> routes)
    {
        return routes.stream().map(TopicRouter.CompiledRoute::topic).toList();
    }

    @Test
    void aNewOrderSingleWithAClOrdIdGoesToTheRawTapeAndToTheOrdersTopic()
    {
        final List<TopicRouter.CompiledRoute> routed =
            defaultRouter().resolve("D", false, TestFix.newOrderSingle("ORD-1"));

        assertEquals(List.of("fix.raw", "fix.orders"), topics(routed));
    }

    @Test
    void theDefaultTopicComesFirstSoTheTapeIsWrittenBeforeAnyKeyedTopic()
    {
        final TopicRouter router = defaultRouter();

        assertAll(
            () -> assertEquals("fix.raw",
                topics(router.resolve("D", false, TestFix.newOrderSingle("ORD-1"))).get(0)),
            () -> assertEquals("fix.raw",
                topics(router.resolve("8", false, TestFix.executionReport("E-1", "O-1"))).get(0)));
    }

    @Test
    void cancelReplaceAndCancelShareTheOrdersTopicWithNewOrderSingle()
    {
        final TopicRouter router = defaultRouter();
        final byte[] message = TestFix.bytes("8=FIX.4.2|35=G|11=ORD-4|41=ORD-1|55=MSFT|10=1|");

        assertAll(
            () -> assertEquals(List.of("fix.raw", "fix.orders"), topics(router.resolve("G", false, message))),
            () -> assertEquals(List.of("fix.raw", "fix.orders"), topics(router.resolve("F", false, message))));
    }

    @Test
    void anExecutionReportWithBothKeysGoesToAllThreeTopicsInConfigurationOrder()
    {
        final List<TopicRouter.CompiledRoute> routed =
            defaultRouter().resolve("8", false, TestFix.executionReport("EXEC-1", "ORDER-1"));

        // fix.execs before fix.order.state, because that is the order the rules are declared in:
        // the fills history is the record, the blotter is derived from it.
        assertEquals(List.of("fix.raw", "fix.execs", "fix.order.state"), topics(routed));
    }

    @Test
    void anExecutionReportWithoutOrderIdIsKeptOffTheOrderStateTopicAndCountedThere()
    {
        final TopicRouter router = defaultRouter();

        final List<TopicRouter.CompiledRoute> routed =
            router.resolve("8", false, TestFix.executionReportWithoutOrderId("EXEC-9"));

        final TopicRouter.CompiledRoute orderState = routeTo(router, "fix.order.state");
        final TopicRouter.CompiledRoute execs = routeTo(router, "fix.execs");
        assertAll(
            // AMPS would have accepted it and put it on the topic's one keyless record, silently
            // overwriting whatever was there. This is the only guard that exists.
            () -> assertEquals(List.of("fix.raw", "fix.execs"), topics(routed)),
            () -> assertEquals(1, orderState.unroutable(), "declined, and counted"),
            () -> assertEquals(0, execs.unroutable(), "17 was present, so fix.execs is unaffected"));
    }

    @Test
    void anOrderWithoutAClOrdIdIsKeptOffTheOrdersTopicButStillReachesTheTape()
    {
        final TopicRouter router = defaultRouter();
        final byte[] message = TestFix.bytes("8=FIX.4.2|35=D|49=QFJ|55=MSFT|54=1|38=100|10=123|");

        final List<TopicRouter.CompiledRoute> routed = router.resolve("D", false, message);

        assertAll(
            () -> assertEquals(List.of("fix.raw"), topics(routed)),
            () -> assertEquals(1, routeTo(router, "fix.orders").unroutable()),
            // fix.raw is unkeyed and journalled, so nothing is actually lost - which is why the
            // default route has no tag condition.
            () -> assertEquals(0, router.defaultRoute().requiredTag()));
    }

    @Test
    void aRequiredTagAtTheStartAndAtTheEndOfAMessageAreBothSeen()
    {
        // The two positions a boundary-anchored scan can get wrong.
        final TopicRouter atStart = new TopicRouter(
            List.of(TopicRoute.requiring("D", "t.first", 8)), null, null);
        final TopicRouter atEnd = new TopicRouter(
            List.of(TopicRoute.requiring("D", "t.last", 10)), null, null);
        final byte[] order = TestFix.newOrderSingle("ORD-1");

        assertAll(
            () -> assertEquals(List.of("t.first"), topics(atStart.resolve("D", false, order))),
            () -> assertEquals(List.of("t.last"), topics(atEnd.resolve("D", false, order))));
    }

    @Test
    void aRuleWithNoRequiredTagFiresForEveryMessageOfItsType()
    {
        final TopicRouter router = new TopicRouter(
            List.of(TopicRoute.of("D", "t.orders")), null, null);
        final byte[] noClOrdId = TestFix.bytes("8=FIX.4.2|35=D|55=MSFT|10=1|");

        assertAll(
            () -> assertEquals(List.of("t.orders"), topics(router.resolve("D", false, noClOrdId))),
            () -> assertEquals(0, routeTo(router, "t.orders").unroutable()));
    }

    @Test
    void byDefaultASessionLevelMessageIsDroppedAndCountedRatherThanPublished()
    {
        final TopicRouter router = defaultRouter();

        final List<TopicRouter.CompiledRoute> routed = router.resolve("A", true, TestFix.logon());

        assertAll(
            () -> assertEquals(List.of(), topics(routed)),
            () -> assertEquals(1, router.adminSkipped()),
            () -> assertNull(router.adminRoute(), "no admin route when publishAdminMessages is false"));
    }

    @Test
    void withPublishAdminMessagesTheLogonGoesToTheAdminTopicAndNowhereElse()
    {
        final TopicRouter router = new TopicRouter(
            BridgeConfig.builder().publishAdminMessages(true).build());

        final List<TopicRouter.CompiledRoute> routed = router.resolve("A", true, TestFix.logon());

        assertAll(
            // Not fix.raw: that topic is the application tape, and a heartbeat per session per
            // interval would be the largest stream in the instance.
            () -> assertEquals(List.of("fix.admin"), topics(routed)),
            () -> assertEquals(0, router.adminSkipped()),
            () -> assertTrue(routed.get(0).isAdminRoute()));
    }

    @Test
    void topicNamesArePreEncodedOnceSoResolvingARouteCreatesNoString()
    {
        final TopicRouter router = defaultRouter();
        final TopicRouter.CompiledRoute orders = routeTo(router, "fix.orders");

        final byte[] first = orders.topicBytes();
        final byte[] second = router.resolve("D", false, TestFix.newOrderSingle("X")).get(1).topicBytes();

        assertAll(
            () -> assertArrayEquals("fix.orders".getBytes(StandardCharsets.US_ASCII), first),
            // The same array instance every time: nothing on the publish path encodes a name.
            () -> assertSame(first, second));
    }

    @Test
    void messageTypesAreComparedAsPackedLongsNotStrings()
    {
        final TopicRouter router = defaultRouter();

        // "D" and "8" are distinct rules; a message of an unconfigured type matches none of them.
        final List<TopicRouter.CompiledRoute> unknown =
            router.resolve("9", false, TestFix.bytes("8=FIX.4.2|35=9|11=ORD-1|10=1|"));

        assertEquals(List.of("fix.raw"), topics(unknown),
            "an OrderCancelReject has a ClOrdID but no rule, so only the tape takes it");
    }

    @Test
    void routesAreListedDefaultFirstThenTheRulesInOrderThenAdmin()
    {
        final TopicRouter router = new TopicRouter(
            BridgeConfig.builder().publishAdminMessages(true).build());

        assertEquals(
            List.of("fix.raw", "fix.orders", "fix.orders", "fix.orders", "fix.execs",
                "fix.order.state", "fix.admin"),
            topics(router.routes()));
    }

    @Test
    void aRouterWithNoDefaultTopicPublishesOnlyWhatItsRulesMatch()
    {
        final TopicRouter router = new TopicRouter(
            List.of(TopicRoute.requiring("8", "t.execs", 17)), null, null);

        assertAll(
            () -> assertNull(router.defaultRoute()),
            () -> assertEquals(List.of(),
                topics(router.resolve("D", false, TestFix.newOrderSingle("ORD-1")))),
            () -> assertEquals(List.of("t.execs"),
                topics(router.resolve("8", false, TestFix.executionReport("E", "O")))));
    }

    private static TopicRouter.CompiledRoute routeTo(final TopicRouter router, final String topic)
    {
        return router.routes().stream()
            .filter(route -> route.topic().equals(topic))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no route to " + topic));
    }
}
