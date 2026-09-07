package com.demo.artio.bridge;

import com.demo.artio.engine.FixEngineConfig;
import org.agrona.concurrent.AgentRunner;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BridgeMain}'s shutdown arithmetic. The hook holds the JVM open for the clean-up in
 * {@code run}; a bound that is shorter than the clean-up can take is the silent-truncation bug
 * described in {@code docs/03} section 8, so the bound is derived from the timeouts rather than
 * guessed. No engine is launched: only configurations are built.
 */
class BridgeMainTest
{
    @Test
    void theShutdownHookWaitCoversEveryBoundedStepOfTheCleanUp()
    {
        final FixEngineConfig engine = BridgeMain.engineConfig(new Properties());
        final BridgeConfig bridge = BridgeConfig.builder().flushTimeoutMs(1_000).build();

        final long wait = BridgeMain.shutdownWaitMs(engine, bridge);

        assertAll(
            // The engine's graceful logout, then the publisher's close, each in full.
            () -> assertTrue(wait >= engine.shutdownTimeoutMs() + AmpsFixPublisher.closeBudgetMs(bridge),
                "hook wait " + wait + " ms must cover logout " + engine.shutdownTimeoutMs() +
                    " ms + publisher close " + AmpsFixPublisher.closeBudgetMs(bridge) + " ms"),
            // Stopping either agent can cost agrona's retry timeout before its first interrupt.
            () -> assertTrue(wait >= engine.shutdownTimeoutMs() + 2 * AgentRunner.RETRY_CLOSE_TIMEOUT_MS),
            () -> assertTrue(wait > bridge.flushTimeoutMs() + 5_000,
                "the old fixed flushTimeoutMs + 5 s was shorter than the clean-up"));
    }

    @Test
    void thePublisherCloseBudgetIsThreeFlushTimeoutsPlusTheAgentStop()
    {
        // The agent wait, the caller-thread drain and the AMPS flush each get flushTimeoutMs in
        // full - none of them shares a deadline with another - and stopping the agent is bounded
        // by agrona's retry timeout.
        final BridgeConfig bridge = BridgeConfig.builder().flushTimeoutMs(700).build();

        assertEquals(3 * 700 + AgentRunner.RETRY_CLOSE_TIMEOUT_MS, AmpsFixPublisher.closeBudgetMs(bridge));
    }

    @Test
    void theHookWaitGrowsWithTheTimeoutsItIsDerivedFrom()
    {
        final FixEngineConfig engine = BridgeMain.engineConfig(new Properties());
        final BridgeConfig shortFlush = BridgeConfig.builder().flushTimeoutMs(1_000).build();
        final BridgeConfig longFlush = BridgeConfig.builder().flushTimeoutMs(10_000).build();

        assertEquals(
            3 * 9_000,
            BridgeMain.shutdownWaitMs(engine, longFlush) - BridgeMain.shutdownWaitMs(engine, shortFlush),
            "each of the three flush-bounded steps of close() is counted once");
    }
}
