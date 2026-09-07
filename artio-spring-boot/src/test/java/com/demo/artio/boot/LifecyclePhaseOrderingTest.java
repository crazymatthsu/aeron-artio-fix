package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.bridge.InMemoryPublishPort;
import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.FixEngineConfig;
import com.demo.artio.engine.FixMessageSink;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two phase numbers really do produce the one ordering the bridge tolerates.
 *
 * <p>From {@code docs/03} section 8: the publisher must be able to accept messages before Artio can
 * deliver any, and at shutdown the engine must stop <em>first</em>, so the ring buffer stops filling
 * and {@code close()} can drain what is left and flush AMPS. Closing the publisher first would
 * leave Artio delivering into a stopped agent and count the tail of the session as dropped.
 *
 * <p>{@code BridgeMain} writes that order out by hand. Here it is a consequence of two integers, so
 * the test is against the integers: a real {@code DefaultLifecycleProcessor} drives two recording
 * lifecycles carrying {@link BridgePublisherLifecycle#PHASE} and
 * {@link ArtioRuntimeLifecycle#PHASE}, and the recorded sequence is the assertion. No media driver,
 * no socket, no AMPS - which is the point: the ordering is a property of the wiring and can be
 * checked as one.
 */
class LifecyclePhaseOrderingTest
{
    @Test
    void springStartsThePublisherFirstAndStopsTheRuntimeFirst()
    {
        final List<String> events = new ArrayList<>();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext())
        {
            context.registerBean("publisher", RecordingLifecycle.class,
                () -> new RecordingLifecycle("publisher", BridgePublisherLifecycle.PHASE, events));
            context.registerBean("runtime", RecordingLifecycle.class,
                () -> new RecordingLifecycle("runtime", ArtioRuntimeLifecycle.PHASE, events));
            context.refresh();

            assertEquals(List.of("start publisher", "start runtime"), events,
                "the publisher must be able to accept messages before the engine can deliver any");
        }
        assertEquals(
            List.of("start publisher", "start runtime", "stop runtime", "stop publisher"), events,
            "the engine must stop before the publisher, so the drain-and-flush has nothing arriving");
    }

    @Test
    void theRuntimePhaseIsBelowSpringsSchedulerSoTheStatsLoggerStopsBeforeTheEngine()
    {
        // ScheduledAnnotationBeanPostProcessor sits at SmartLifecycle.DEFAULT_PHASE
        // (Integer.MAX_VALUE) and therefore stops first. Taking the top slot ourselves would have
        // the stats logger racing a closing engine for its session list.
        assertAll(
            () -> assertTrue(ArtioRuntimeLifecycle.PHASE < SmartLifecycle.DEFAULT_PHASE),
            () -> assertTrue(BridgePublisherLifecycle.PHASE < ArtioRuntimeLifecycle.PHASE));
    }

    @Test
    void thePublisherLifecycleStartsAndClosesTheRealPublisherAndOnlyThenRunsTheCallback()
    {
        // A real AmpsFixPublisher over the bridge's in-memory port: the agent thread, the ring
        // buffer and close()'s drain-and-flush are all exercised, and nothing touches a network.
        final InMemoryPublishPort port = new InMemoryPublishPort();
        final AmpsFixPublisher publisher = new AmpsFixPublisher(
            BridgeConfig.builder().flushTimeoutMs(1_000).build(), port);
        final BridgePublisherLifecycle lifecycle = new BridgePublisherLifecycle(publisher);

        assertFalse(lifecycle.isRunning(), "not running before start()");
        lifecycle.start();
        assertTrue(lifecycle.isRunning());

        final List<String> events = new ArrayList<>();
        lifecycle.stop(() -> events.add("callback, publisher running=" + publisher.isRunning()));

        assertAll(
            () -> assertFalse(publisher.isRunning(), "stop() must close the publisher, not park it"),
            // Synchronous on purpose: the callback releases the phase, so running it before the
            // flush completed would let the JVM proceed to exit with messages still in the ring.
            () -> assertEquals(List.of("callback, publisher running=false"), events),
            () -> assertEquals(1, port.flushes(), "close() must publishFlush before disconnecting"));

        // Idempotent: Spring can call stop() on a bean it already stopped, and close() is
        // idempotent underneath.
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void theRuntimeLifecycleIsNotRunningAndStopsSilentlyBeforeItIsEverStarted()
    {
        // An ArtioRuntime allocates nothing expensive until start(); this is the state a context
        // that failed to refresh leaves behind, and stop() must be a no-op rather than a throw.
        final ArtioRuntime runtime = new ArtioRuntime(
            FixEngineConfig.acceptor().port(9999).build(), FixMessageSink.NO_OP);
        final ArtioRuntimeLifecycle lifecycle = new ArtioRuntimeLifecycle(runtime);

        assertFalse(lifecycle.isRunning());
        final List<String> events = new ArrayList<>();
        lifecycle.stop(() -> events.add("callback"));
        assertAll(
            () -> assertEquals(List.of("callback"), events),
            () -> assertEquals(ArtioRuntimeLifecycle.PHASE, lifecycle.getPhase()),
            () -> assertEquals(runtime, lifecycle.runtime()));
    }

    /** Records its own start and stop against a shared list, at whatever phase it is given. */
    static final class RecordingLifecycle implements SmartLifecycle
    {
        private final String name;
        private final int phase;
        private final List<String> events;
        private volatile boolean running;

        RecordingLifecycle(final String name, final int phase, final List<String> events)
        {
            this.name = name;
            this.phase = phase;
            this.events = events;
        }

        @Override
        public int getPhase()
        {
            return phase;
        }

        @Override
        public void start()
        {
            events.add("start " + name);
            running = true;
        }

        @Override
        public void stop()
        {
            events.add("stop " + name);
            running = false;
        }

        @Override
        public boolean isRunning()
        {
            return running;
        }
    }
}
