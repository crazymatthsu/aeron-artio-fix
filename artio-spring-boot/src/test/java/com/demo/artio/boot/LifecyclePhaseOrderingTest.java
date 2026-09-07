package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.bridge.InMemoryPublishPort;
import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.FixEngineConfig;
import com.demo.artio.engine.FixMessageSink;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // SmartLifecycle.DEFAULT_PHASE (Integer.MAX_VALUE) is where Spring Boot's own
        // ThreadPoolTaskScheduler sits (every ExecutorConfigurationSupport is a SmartLifecycle at
        // that phase), so it stops before the engine. ScheduledAnnotationBeanPostProcessor itself
        // is not a lifecycle bean in Spring 6.2: it cancels the @Scheduled tasks on
        // ContextClosedEvent, which is published before any stop phase runs. Either way the stats
        // logger never races a closing engine for its session list.
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

    @Test
    void aRuntimeThatFailsToStartIsRolledBackBySpringAndThePublisherIsStoppedInPhaseOrder()
    {
        // The start-failure path: an initiator that cannot log on, a port already in use, the JVM
        // flags missing. The publisher is already up when ArtioRuntime.start() throws. Spring 6.2's
        // DefaultLifecycleProcessor.onRefresh() catches the failure and stops what it had started,
        // in descending phase order, before the refresh is cancelled. This module relies on that
        // roll-back - it is what lets a mis-configured initiator exit instead of hanging on the
        // publisher's non-daemon agent thread - so it is pinned here rather than assumed.
        final InMemoryPublishPort port = new InMemoryPublishPort();
        final AmpsFixPublisher publisher = new AmpsFixPublisher(
            BridgeConfig.builder().flushTimeoutMs(1_000).build(), port);

        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("bridgePublisherLifecycle", BridgePublisherLifecycle.class,
            () -> new BridgePublisherLifecycle(publisher));
        context.registerBean("artioRuntimeLifecycle", ThrowingLifecycle.class,
            () -> new ThrowingLifecycle(ArtioRuntimeLifecycle.PHASE));

        final ApplicationContextException failure =
            assertThrows(ApplicationContextException.class, context::refresh);

        assertAll(
            () -> assertTrue(failure.getMessage().contains("artioRuntimeLifecycle"),
                () -> "the failure must name the bean that could not start: " + failure.getMessage()),
            () -> assertFalse(context.isActive(), "a failed refresh leaves the context inactive"),
            () -> assertFalse(publisher.isRunning(),
                "the publisher must be closed when the engine fails to start, or its agent " +
                    "thread keeps the JVM alive with no engine in it"),
            () -> assertEquals(1, port.flushes(), "closed through the same drain-and-flush path"));

        // A close() on the inactive context afterwards - which is what SpringApplication does on
        // a run failure - must stay harmless.
        assertDoesNotThrow(context::close);
    }

    @Test
    void aRefreshThatFailsAfterThePhasesStartedStillClosesThePublisherThroughBeanDestruction()
    {
        // The path Spring does NOT roll back. Once every phase has started, finishRefresh publishes
        // ContextRefreshedEvent; a listener that throws there (Spring's own scheduler registration,
        // Boot's keep-alive, anything a larger application adds) fails the refresh AFTER both the
        // publisher and the engine are up. AbstractApplicationContext then destroys the singletons
        // without running a single stop phase, and close() on the inactive context it leaves
        // behind is a no-op. The only thing that still runs is bean destruction, which is why the
        // adapters implement DisposableBean: without it the publisher's non-daemon agent thread
        // would keep the JVM alive with nothing serving FIX.
        final InMemoryPublishPort port = new InMemoryPublishPort();
        final AmpsFixPublisher publisher = new AmpsFixPublisher(
            BridgeConfig.builder().flushTimeoutMs(1_000).build(), port);

        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("bridgePublisherLifecycle", BridgePublisherLifecycle.class,
            () -> new BridgePublisherLifecycle(publisher));
        context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>)event ->
        {
            throw new IllegalStateException("a listener that throws on ContextRefreshedEvent");
        });

        final IllegalStateException failure = assertThrows(IllegalStateException.class, context::refresh);

        assertAll(
            () -> assertTrue(failure.getMessage().contains("ContextRefreshedEvent")),
            () -> assertFalse(context.isActive(), "a failed refresh leaves the context inactive"),
            () -> assertFalse(publisher.isRunning(),
                "no stop phase runs on this path; DisposableBean.destroy() must close the publisher"),
            () -> assertEquals(1, port.flushes(), "closed through the same drain-and-flush path"));
        assertDoesNotThrow(context::close);
    }

    @Test
    void destroyIsANoOpAfterANormalStopAndClosesWhatWasNeverStopped()
    {
        final InMemoryPublishPort port = new InMemoryPublishPort();
        final AmpsFixPublisher publisher = new AmpsFixPublisher(
            BridgeConfig.builder().flushTimeoutMs(1_000).build(), port);
        final BridgePublisherLifecycle lifecycle = new BridgePublisherLifecycle(publisher);

        lifecycle.start();
        lifecycle.stop();
        lifecycle.destroy();
        assertEquals(1, port.flushes(), "the normal path closes once: destroy() after stop() does nothing");

        final InMemoryPublishPort other = new InMemoryPublishPort();
        final BridgePublisherLifecycle neverStopped = new BridgePublisherLifecycle(
            new AmpsFixPublisher(BridgeConfig.builder().flushTimeoutMs(1_000).build(), other));
        neverStopped.start();
        neverStopped.destroy();
        assertAll(
            () -> assertFalse(neverStopped.isRunning(), "destroy() is the fallback close"),
            () -> assertEquals(1, other.flushes()));

        // The runtime adapter has the same shape; over a runtime that never started it is silent.
        final ArtioRuntimeLifecycle runtime = new ArtioRuntimeLifecycle(new ArtioRuntime(
            FixEngineConfig.acceptor().port(9999).build(), FixMessageSink.NO_OP));
        assertDoesNotThrow(runtime::destroy);
        assertFalse(runtime.isRunning());
    }

    /** Stands in for a runtime whose {@code start()} throws, at whatever phase it is given. */
    static final class ThrowingLifecycle implements SmartLifecycle
    {
        private final int phase;

        ThrowingLifecycle(final int phase)
        {
            this.phase = phase;
        }

        @Override
        public int getPhase()
        {
            return phase;
        }

        @Override
        public void start()
        {
            throw new IllegalStateException("initiator could not log on within the timeout");
        }

        @Override
        public void stop()
        {
        }

        @Override
        public boolean isRunning()
        {
            return false;
        }
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
