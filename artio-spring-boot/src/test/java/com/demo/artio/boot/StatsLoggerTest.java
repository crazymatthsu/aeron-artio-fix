package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.bridge.InMemoryPublishPort;
import com.demo.artio.engine.ArtioRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stats logger registers one task when it is configured to, none when the interval is 0, and
 * never lets a failure kill the scheduler.
 *
 * <p>{@code bridge.stats-log-interval-ms=0} is not a decoration: the integration tests set it so
 * their output is only the flow, and a {@code @Scheduled(fixedRateString = "${...}")} of 0 is an
 * {@code IllegalArgumentException} at context refresh rather than "off". That is why
 * {@link StatsLogger} registers its task by hand, and why the zero case is tested rather than
 * assumed.
 */
class StatsLoggerTest
{
    @Test
    void anIntervalOfZeroRegistersNoTaskAtAll()
    {
        final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        newLogger(0).configureTasks(registrar);

        assertEquals(0, registrar.getFixedRateTaskList().size(),
            "0 must mean off, not 'every zero milliseconds'");
    }

    @Test
    void aPositiveIntervalRegistersExactlyOneFixedRateTask()
    {
        final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        newLogger(2_500).configureTasks(registrar);

        assertEquals(1, registrar.getFixedRateTaskList().size());
    }

    @Test
    void theRegisteredTaskWritesItsLineWithNoRuntimeBeanAndAnUnstartedPublisher()
    {
        // The two states that would trip a naive implementation: artio.enabled=false, so the
        // ObjectProvider is empty, and a publisher that has never been started. Neither may
        // propagate - one throw from a scheduled task cancels every future run of it.
        final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        newLogger(1_000).configureTasks(registrar);

        assertDoesNotThrow(() -> registrar.getFixedRateTaskList().get(0).getRunnable().run());
    }

    @Test
    void logStatsSwallowsWhateverTheCountersThrowRatherThanKillingTheScheduler()
    {
        assertDoesNotThrow(newLogger(1_000)::logStats);
    }

    private static StatsLogger newLogger(final long intervalMs)
    {
        final AmpsFixPublisher publisher =
            new AmpsFixPublisher(BridgeConfig.defaults(), new InMemoryPublishPort());
        return new StatsLogger(publisher, emptyProvider(), intervalMs);
    }

    /** @return a real, empty {@link ObjectProvider}: what {@code artio.enabled=false} produces. */
    private static ObjectProvider<ArtioRuntime> emptyProvider()
    {
        return new DefaultListableBeanFactory().getBeanProvider(ArtioRuntime.class);
    }
}
