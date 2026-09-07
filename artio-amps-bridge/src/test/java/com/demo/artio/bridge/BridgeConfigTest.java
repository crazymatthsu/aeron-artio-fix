package com.demo.artio.bridge;

import com.demo.artio.engine.IdleStrategyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BridgeConfig}: the defaults, the property binding, and every validation rule. */
class BridgeConfigTest
{
    private static Properties props(final String... keyValues)
    {
        final Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2)
        {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return properties;
    }

    @Test
    void theDefaultsDescribeTheArtioFixFlowExactly()
    {
        final BridgeConfig config = BridgeConfig.defaults();

        assertAll(
            () -> assertEquals("tcp://localhost:9007/amps/fix", config.uri()),
            () -> assertEquals("fix.raw", config.defaultTopic()),
            () -> assertEquals("fix.admin", config.adminTopic()),
            () -> assertFalse(config.publishAdminMessages()),
            // Every required tag is the destination topic's SOW key.
            () -> assertEquals(
                List.of(
                    new TopicRoute("D", "fix.orders", 11),
                    new TopicRoute("G", "fix.orders", 11),
                    new TopicRoute("F", "fix.orders", 11),
                    new TopicRoute("8", "fix.execs", 17),
                    new TopicRoute("8", "fix.order.state", 37)),
                config.routes()),
            () -> assertEquals(4 * 1024 * 1024, config.ringBufferCapacityBytes()),
            () -> assertEquals(OverflowPolicy.DROP_AND_COUNT, config.overflowPolicy()),
            () -> assertEquals(IdleStrategyType.BACKOFF, config.idleStrategy()),
            () -> assertFalse(config.guaranteedPublishing()));
    }

    @Test
    void anEmptyPropertySetYieldsTheDefaults()
    {
        assertEquals(BridgeConfig.defaults(), BridgeConfig.fromProperties(new Properties()));
    }

    @Test
    void everyScalarKeyBinds()
    {
        final BridgeConfig config = BridgeConfig.fromProperties(props(
            "bridge.amps.uri", "tcp://amps-host:9107/amps/fix",
            "bridge.amps.clientName", "gateway-1",
            "bridge.amps.guaranteedPublishing", "true",
            "bridge.defaultTopic", "tape",
            "bridge.adminTopic", "session",
            "bridge.publishAdminMessages", "true",
            "bridge.ringBufferCapacityBytes", "65536",
            "bridge.overflowPolicy", "BLOCK",
            "bridge.overflowBlockTimeoutMs", "250",
            "bridge.flushTimeoutMs", "2500",
            "bridge.reconnect.initialBackoffMs", "100",
            "bridge.reconnect.maxBackoffMs", "5000",
            "bridge.idleStrategy", "BUSY_SPIN"));

        assertAll(
            () -> assertEquals("tcp://amps-host:9107/amps/fix", config.uri()),
            () -> assertEquals("gateway-1", config.clientName()),
            () -> assertTrue(config.guaranteedPublishing()),
            () -> assertEquals("tape", config.defaultTopic()),
            () -> assertEquals("session", config.adminTopic()),
            () -> assertTrue(config.publishAdminMessages()),
            () -> assertEquals(65536, config.ringBufferCapacityBytes()),
            () -> assertEquals(OverflowPolicy.BLOCK, config.overflowPolicy()),
            () -> assertEquals(250, config.overflowBlockTimeoutMs()),
            () -> assertEquals(2500, config.flushTimeoutMs()),
            () -> assertEquals(100, config.reconnectInitialBackoffMs()),
            () -> assertEquals(5000, config.reconnectMaxBackoffMs()),
            () -> assertEquals(IdleStrategyType.BUSY_SPIN, config.idleStrategy()),
            // Untouched keys keep their defaults rather than being reset.
            () -> assertEquals(BridgeConfig.DEFAULT_ROUTES, config.routes()));
    }

    @Test
    void enumValuesAreCaseInsensitiveAndAcceptHyphens()
    {
        final BridgeConfig config = BridgeConfig.fromProperties(props(
            "bridge.overflowPolicy", "drop_and_count",
            "bridge.idleStrategy", "busy-spin"));

        assertAll(
            () -> assertEquals(OverflowPolicy.DROP_AND_COUNT, config.overflowPolicy()),
            () -> assertEquals(IdleStrategyType.BUSY_SPIN, config.idleStrategy()));
    }

    @Test
    void definingRoutesReplacesTheWholeDefaultSet()
    {
        final BridgeConfig config = BridgeConfig.fromProperties(props(
            "bridge.route[0].msgType", "8",
            "bridge.route[0].topic", "fix.execs",
            "bridge.route[0].requiredTag", "17",
            "bridge.route[1].msgType", "D",
            "bridge.route[1].topic", "fix.orders"));

        assertEquals(
            List.of(
                new TopicRoute("8", "fix.execs", 17),
                // No requiredTag key means no condition, not a default of 11.
                new TopicRoute("D", "fix.orders", TopicRoute.NO_REQUIRED_TAG)),
            config.routes());
    }

    @Test
    void aGapInTheRouteIndicesIsRejectedRatherThanSilentlyDroppingTheLaterRoutes()
    {
        // A mis-numbered SOW guard must not vanish quietly: route[2] without route[1] is an error
        // that names the missing index, not a configuration with one route.
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> BridgeConfig.fromProperties(props(
                "bridge.route[0].msgType", "D",
                "bridge.route[0].topic", "fix.orders",
                "bridge.route[2].msgType", "8",
                "bridge.route[2].topic", "fix.execs")));

        assertTrue(error.getMessage().contains("bridge.route[1]"), error.getMessage());
    }

    @Test
    void aLoneRequiredTagIsRejectedRatherThanKeepingTheDefaultsInSilence()
    {
        // With a probe from route[0].msgType this key would be invisible: the defaults would stand
        // and the operator would believe a guard exists that does not.
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> BridgeConfig.fromProperties(props("bridge.route[0].requiredTag", "11")));

        assertEquals("bridge.route[0] has only .requiredTag; a route needs .msgType and .topic", error.getMessage());
    }

    @Test
    void aRouteKeyWhoseIndexIsNotANumberIsRejectedByName()
    {
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props("bridge.route[x].msgType", "D", "bridge.route[x].topic", "t")))
                .getMessage().contains("bridge.route[x].msgType")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props("bridge.route[-1].msgType", "D", "bridge.route[-1].topic", "t")))
                .getMessage().contains("bridge.route[-1]")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props("bridge.route[0]msgType", "D")))
                .getMessage().contains("bridge.route[0]msgType")));
    }

    @Test
    void anIntegerThatDoesNotFitAnIntIsRejectedByNameRatherThanTruncated()
    {
        // (int)4294967296L is 0 and (int)2147483648L is negative: a cast would hand validation a
        // different, wrong number, and the error would name no key.
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props("bridge.ringBufferCapacityBytes", "4294967296")))
                .getMessage().contains("bridge.ringBufferCapacityBytes")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props(
                    "bridge.route[0].msgType", "D",
                    "bridge.route[0].topic", "fix.orders",
                    "bridge.route[0].requiredTag", "2147483648")))
                .getMessage().contains("bridge.route[0].requiredTag")));
    }

    @Test
    void aRouteMissingItsTopicOrItsMsgTypeIsRejectedByName()
    {
        assertAll(
            () -> assertEquals(
                "bridge.route[0] needs both .msgType and .topic",
                assertThrows(IllegalArgumentException.class,
                    () -> BridgeConfig.fromProperties(props("bridge.route[0].msgType", "D"))).getMessage()),
            () -> assertEquals(
                "bridge.route[0] needs both .msgType and .topic",
                assertThrows(IllegalArgumentException.class,
                    () -> BridgeConfig.fromProperties(props("bridge.route[0].topic", "t"))).getMessage()));
    }

    @Test
    void aBlankDefaultTopicMeansPublishOnlyWhatTheRulesMatch()
    {
        final BridgeConfig config = BridgeConfig.fromProperties(props("bridge.defaultTopic", ""));

        assertNull(config.defaultTopic());
    }

    @Test
    void theEffectiveAdminTopicIsNullUntilAdminPublishingIsTurnedOn()
    {
        assertAll(
            () -> assertNull(BridgeConfig.defaults().effectiveAdminTopic(),
                "configured but not enabled means dropped"),
            () -> assertEquals("fix.admin",
                BridgeConfig.builder().publishAdminMessages(true).build().effectiveAdminTopic()));
    }

    @Test
    void aRingBufferCapacityThatIsNotAPowerOfTwoIsRejectedHereRatherThanInsideAgrona()
    {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> BridgeConfig.builder().ringBufferCapacityBytes(3000).build());

        assertTrue(e.getMessage().contains("power of two"), e.getMessage());
    }

    @Test
    void aRingBufferTooSmallToBeUsefulIsRejected()
    {
        assertThrows(IllegalArgumentException.class,
            () -> BridgeConfig.builder().ringBufferCapacityBytes(512).build());
    }

    @Test
    void publishingAdminMessagesWithNoAdminTopicIsRejected()
    {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> BridgeConfig.builder().adminTopic("").publishAdminMessages(true).build());

        assertTrue(e.getMessage().contains("nowhere to publish"), e.getMessage());
    }

    @Test
    void aBridgeWithNeitherADefaultTopicNorAnyRuleIsRejected()
    {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> BridgeConfig.builder().defaultTopic(null).routes(List.of()).build());

        assertTrue(e.getMessage().contains("would publish nothing"), e.getMessage());
    }

    @Test
    void aMaximumBackoffBelowTheInitialOneIsRejected()
    {
        assertThrows(IllegalArgumentException.class,
            () -> BridgeConfig.builder()
                .reconnectInitialBackoffMs(5000)
                .reconnectMaxBackoffMs(1000)
                .build());
    }

    @Test
    void blankAndNonPositiveScalarsAreRejected()
    {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.builder().uri(" ").build()),
            () -> assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.builder().clientName("").build()),
            () -> assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.builder().flushTimeoutMs(0).build()),
            () -> assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.builder().reconnectInitialBackoffMs(0).build()),
            () -> assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.builder().overflowBlockTimeoutMs(-1).build()));
    }

    @Test
    void aMalformedPropertyValueNamesTheKeyThatIsWrong()
    {
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props("bridge.flushTimeoutMs", "soon")))
                .getMessage().contains("bridge.flushTimeoutMs")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props("bridge.overflowPolicy", "MAYBE")))
                .getMessage().contains("bridge.overflowPolicy")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BridgeConfig.fromProperties(props("bridge.publishAdminMessages", "yes")))
                .getMessage().contains("bridge.publishAdminMessages")));
    }

    @Test
    void aTopicRouteRejectsBlankNamesAndSeparatorsInThem()
    {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> new TopicRoute("", "t", 0)),
            () -> assertThrows(IllegalArgumentException.class, () -> new TopicRoute("D", " ", 0)),
            () -> assertThrows(IllegalArgumentException.class, () -> new TopicRoute("D", "a=b", 0)),
            () -> assertThrows(IllegalArgumentException.class, () -> new TopicRoute("D", "t", -1)));
    }

    @Test
    void toBuilderRoundTripsEveryField()
    {
        final BridgeConfig original = BridgeConfig.builder()
            .uri("tcp://x:1/amps/fix")
            .clientName("c")
            .publishAdminMessages(true)
            .overflowPolicy(OverflowPolicy.BLOCK)
            .guaranteedPublishing(true)
            .idleStrategy(IdleStrategyType.SLEEPING)
            .build();

        assertEquals(original, original.toBuilder().build());
    }

    @Test
    void theShippedBridgePropertiesFileParsesIntoTheDefaults()
    {
        // The file a developer edits and the defaults the code ships with must not drift apart.
        // Found from either working directory, so this cannot pass vacuously by not finding it.
        final java.nio.file.Path file = java.util.stream.Stream
            .of("bridge.properties", "artio-amps-bridge/bridge.properties")
            .map(java.nio.file.Path::of)
            .filter(java.nio.file.Files::isReadable)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "cannot find bridge.properties from " + java.nio.file.Path.of("").toAbsolutePath()));

        final Properties properties = new Properties();
        try (var in = java.nio.file.Files.newInputStream(file))
        {
            properties.load(in);
        }
        catch (final java.io.IOException e)
        {
            throw new AssertionError("cannot read " + file.toAbsolutePath(), e);
        }

        assertEquals(BridgeConfig.defaults(), BridgeConfig.fromProperties(properties));
    }
}
