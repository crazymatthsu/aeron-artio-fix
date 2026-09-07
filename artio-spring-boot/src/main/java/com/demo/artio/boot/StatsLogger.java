package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.SessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The periodic health line: {@code BridgeStats.summary()} plus the sessions that are logged on.
 * This is the module's only observability surface, and it is deliberately the same one
 * {@code BridgeMain} prints, so a Spring deployment and a plain one are read the same way.
 *
 * <pre>
 * stats: accepted=7 published=10 pending=0 dropped=0 unroutable=0 errors=0 lost=0 bytes=1504
 *        ring=0/4194304 connected [fix.raw={published=5}, fix.orders={published=5}, ...]
 * sessions: [QFJ->ARTIO]
 * </pre>
 *
 * <p><strong>Why {@link SchedulingConfigurer} rather than {@code @Scheduled}.</strong> The interval
 * is configuration, and 0 means "off" - which is what the integration tests use so their output is
 * only the flow. A {@code @Scheduled(fixedRateString = ...)} of 0 is not "off", it is an
 * {@code IllegalArgumentException} at context refresh, and the obvious workarounds
 * ({@code @ConditionalOnExpression} over a placeholder) quietly stop working the moment the
 * property is spelled {@code bridge.statsLogIntervalMs} rather than
 * {@code bridge.stats-log-interval-ms}, because SpEL placeholder resolution is not relaxed
 * binding. Registering the task by hand costs three lines and has neither problem.
 * {@code @EnableScheduling} is still what supplies the scheduler.
 */
public final class StatsLogger implements SchedulingConfigurer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsLogger.class);

    private final AmpsFixPublisher publisher;
    private final ObjectProvider<ArtioRuntime> runtime;
    private final Duration interval;

    /**
     * @param publisher the publisher whose counters are logged.
     * @param runtime   the runtime whose sessions are listed; a provider because the engine can be
     *                  disabled while the publisher is not.
     * @param intervalMs how often to log; 0 or less disables logging entirely.
     */
    public StatsLogger(
        final AmpsFixPublisher publisher, final ObjectProvider<ArtioRuntime> runtime, final long intervalMs)
    {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.interval = Duration.ofMillis(Math.max(intervalMs, 0));
    }

    @Override
    public void configureTasks(final ScheduledTaskRegistrar registrar)
    {
        if (interval.isZero())
        {
            LOGGER.info("stats logging disabled (bridge.stats-log-interval-ms=0)");
            return;
        }
        // Initial delay equal to the interval: the first line at t=0 would only say "all zeros".
        registrar.addFixedRateTask(new FixedRateTask(this::logStats, interval, interval));
        LOGGER.info("stats logging every {}ms", interval.toMillis());
    }

    /** Writes one health line, and one session line when the engine is running. Never throws. */
    void logStats()
    {
        try
        {
            LOGGER.info("stats: {}", publisher.stats().summary());
            final ArtioRuntime engine = runtime.getIfAvailable();
            if (engine != null && engine.isRunning())
            {
                final List<SessionKey> keys = engine.sessionKeys();
                LOGGER.info("sessions: {}", keys.isEmpty() ? "none logged on" : keys);
            }
        }
        catch (final RuntimeException e)
        {
            // A broken health line must not kill the scheduler and take the rest of them with it.
            LOGGER.warn("could not log bridge stats", e);
        }
    }
}
