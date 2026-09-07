package com.demo.artio.boot;

import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.bridge.OverflowPolicy;
import com.demo.artio.bridge.TopicRoute;
import com.demo.artio.engine.EngineMode;
import com.demo.artio.engine.FixEngineConfig;
import com.demo.artio.engine.FixVersion;
import com.demo.artio.engine.IdleStrategyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every key in {@code application-custom-routes.yml} reaches the object it is meant to configure.
 *
 * <p>A binding test is only worth writing if every value differs from the default, otherwise a key
 * that never binds at all still passes. The profile file therefore changes all of them, and this
 * class asserts on the two <em>mapped</em> records - {@link FixEngineConfig} and
 * {@link BridgeConfig} - rather than on the properties records, because the mapping is where a
 * name can be dropped silently.
 */
@SpringBootTest
@ActiveProfiles("custom-routes")
class PropertyBindingTest
{
    @Autowired
    private ArtioProperties artio;

    @Autowired
    private BridgeProperties bridge;

    @Test
    void everyArtioKeyReachesTheFixEngineConfig()
    {
        final FixEngineConfig config = artio.toFixEngineConfig();
        assertAll(
            () -> assertFalse(artio.enabled()),
            () -> assertEquals(EngineMode.INITIATOR, config.mode(), "mode is bound case-insensitively"),
            () -> assertEquals("binding-test", config.name()),
            () -> assertEquals("127.0.0.1", config.host()),
            () -> assertEquals(19999, config.port()),
            () -> assertEquals("SPRING", config.senderCompId()),
            () -> assertEquals("VENUE", config.targetCompId()),
            () -> assertEquals(FixVersion.FIX44, config.fixVersion()),
            () -> assertEquals(7, config.heartbeatIntervalSec()),
            () -> assertEquals(Path.of("/tmp/artio-binding-test"), config.baseDirectory()),
            () -> assertFalse(config.resetSeqNumsOnLogon()),
            () -> assertEquals(IdleStrategyType.SLEEPING, config.idleStrategy()),
            () -> assertEquals(1234, config.logonTimeoutMs()),
            () -> assertEquals(2345, config.replyTimeoutMs()),
            () -> assertEquals(3456, config.shutdownTimeoutMs()),
            () -> assertFalse(config.deleteDirectoriesOnClose()),
            () -> assertFalse(artio.logMessages()));
    }

    @Test
    void everyBridgeKeyReachesTheBridgeConfig()
    {
        final BridgeConfig config = bridge.toBridgeConfig();
        assertAll(
            () -> assertFalse(bridge.enabled()),
            () -> assertEquals("tcp://amps.example.com:19007/amps/fix", config.uri()),
            () -> assertEquals("binding-test-client", config.clientName()),
            () -> assertEquals("fix.tape", config.defaultTopic()),
            () -> assertEquals("fix.session", config.adminTopic()),
            () -> assertTrue(config.publishAdminMessages()),
            () -> assertEquals(65536, config.ringBufferCapacityBytes()),
            () -> assertEquals(OverflowPolicy.BLOCK, config.overflowPolicy()),
            () -> assertEquals(42, config.overflowBlockTimeoutMs()),
            () -> assertEquals(4321, config.flushTimeoutMs()),
            () -> assertEquals(111, config.reconnectInitialBackoffMs()),
            () -> assertEquals(2222, config.reconnectMaxBackoffMs()),
            () -> assertTrue(config.guaranteedPublishing()),
            () -> assertEquals(IdleStrategyType.BUSY_SPIN, config.idleStrategy()),
            () -> assertEquals(0, bridge.statsLogIntervalMs()));
    }

    @Test
    void aConfiguredRouteListReplacesTheDefaultsRatherThanAddingToThem()
    {
        // BridgeConfig.DEFAULT_ROUTES has five rules; the profile file declares two. Getting seven
        // here would mean a merge, which makes "I removed the fix.order.state rule" inexpressible.
        assertEquals(
            List.of(
                new TopicRoute("8", "fix.execs", 17),
                new TopicRoute("D", "fix.tape.orders", TopicRoute.NO_REQUIRED_TAG)),
            bridge.toBridgeConfig().routes());
    }
    @Test
    void theInitiatorReconnectKeysReachTheEngineConfig()
    {
        // Added after the engine grew reconnect: without these three keys a Spring initiator could
        // neither disable nor tune it, while BridgeMain's properties could.
        final FixEngineConfig config = artio.toFixEngineConfig();
        assertAll(
            () -> assertFalse(config.reconnectEnabled(), "reconnect-enabled: false"),
            () -> assertEquals(250L, config.reconnectInitialBackoffMs()),
            () -> assertEquals(2500L, config.reconnectMaxBackoffMs()));
    }
}
