package com.demo.artio.boot;

import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.EngineMode;
import com.demo.artio.engine.FixEngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * Starts the Artio runtime <strong>last</strong> and stops it <strong>first</strong>.
 *
 * <p>The high phase is the whole trick: Spring starts ascending and stops descending, so this one
 * comes up after {@link BridgePublisherLifecycle} has a live ring buffer to write into, and goes
 * down before it, which is what lets the publisher drain and flush with nothing still arriving.
 *
 * <p>Everything expensive - the Aeron media driver, the archive, {@code FixEngine.launch},
 * {@code FixLibrary.connect} - happens inside {@link ArtioRuntime#start()}, on Spring's main
 * thread, and is finished before this method returns. That is intentional: an acceptor that
 * "started" before its port was bound would report ready and refuse connections.
 *
 * <p><strong>MAX_VALUE - 1000, not MAX_VALUE.</strong> {@code Integer.MAX_VALUE} is
 * {@code SmartLifecycle.DEFAULT_PHASE}, where Spring Boot's own {@code ThreadPoolTaskScheduler}
 * bean sits (every {@code ExecutorConfigurationSupport} is a {@code SmartLifecycle} at that phase),
 * so leaving the top slot alone means the scheduler's executor is stopped before the engine is.
 * The {@link StatsLogger} task itself is cancelled even earlier: {@code
 * ScheduledAnnotationBeanPostProcessor} is not a lifecycle bean at all but a listener that cancels
 * its tasks on {@code ContextClosedEvent}, which {@code doClose()} publishes before any stop phase
 * runs. Either way the stats line never races a closing engine for its session list.
 *
 * <p><strong>Not restartable.</strong> {@link ArtioRuntime#start()} refuses a second call, so a
 * {@code context.stop()} followed by {@code context.start()} fails here. A stopped context is
 * finished with; a new one is built for a new run.
 */
public final class ArtioRuntimeLifecycle implements SmartLifecycle, DisposableBean
{
    /** High, so Spring starts this last and stops it first. */
    public static final int PHASE = Integer.MAX_VALUE - 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtioRuntimeLifecycle.class);

    private final ArtioRuntime runtime;

    /**
     * @param runtime the runtime to own; never closed by anything else, in particular not by an
     *                inferred {@code destroyMethod} on its own bean.
     */
    public ArtioRuntimeLifecycle(final ArtioRuntime runtime)
    {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public int getPhase()
    {
        return PHASE;
    }

    /** Launches the media driver, the archive, the engine and the library. Blocks until ready. */
    @Override
    public void start()
    {
        if (runtime.isRunning())
        {
            return;
        }
        runtime.start();
        final FixEngineConfig config = runtime.config();
        LOGGER.info("artio runtime started: {} {} {} on {}:{} as {}->{}",
            config.mode() == EngineMode.ACCEPTOR ? "acceptor listening" : "initiator connected",
            runtime.runtimeId(), config.fixVersion().beginString(),
            config.host(), config.port(), config.senderCompId(), config.targetCompId());
    }

    /**
     * Logs the sessions out, stops the poll thread and closes the engine and driver. After this
     * returns nothing can write to the bridge's ring buffer, which is the precondition
     * {@link BridgePublisherLifecycle#stop()} relies on.
     */
    @Override
    public void stop()
    {
        if (!runtime.isRunning())
        {
            return;
        }
        runtime.close();
        LOGGER.info("artio runtime closed");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Synchronous: the callback releases this phase and lets Spring move on to the publisher's,
     * so it must not run until the engine is genuinely down.
     *
     * @param callback run once the runtime really has closed.
     */
    @Override
    public void stop(final Runnable callback)
    {
        try
        {
            stop();
        }
        finally
        {
            callback.run();
        }
    }

    /**
     * The fallback for a context that never became active: a refresh that fails after the phases
     * have started destroys the singletons without running a stop phase, and this is the only
     * hook left. A no-op on the normal path, where {@link #stop()} has already run; see
     * {@link BridgePublisherLifecycle#destroy()} for the whole story. Spring destroys this bean
     * before the publisher's adapter because {@link BridgeConfiguration} declares it dependent on
     * that one, so the shutdown order holds on this path as well.
     */
    @Override
    public void destroy()
    {
        if (runtime.isRunning())
        {
            LOGGER.warn("artio runtime still running at bean destruction: no stop phase ran " +
                "(the refresh failed after start-up?); closing it now");
        }
        stop();
    }

    @Override
    public boolean isRunning()
    {
        return runtime.isRunning();
    }

    /** @return the runtime this adapter owns. */
    public ArtioRuntime runtime()
    {
        return runtime;
    }
}
