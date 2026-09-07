package com.demo.artio.engine;

import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

/**
 * How the library poll thread behaves when {@code FixLibrary.poll} finds nothing to do.
 *
 * <p>The poll thread is the only thread allowed to touch the {@code FixLibrary} and its
 * {@code Session}s, so this choice is the runtime's whole latency/CPU trade-off.
 */
public enum IdleStrategyType
{
    /**
     * Spin, then yield, then park with a growing back-off. Artio's own default
     * ({@code CommonConfiguration.backoffIdleStrategy()}) and the right choice unless you have
     * a core to spare.
     */
    BACKOFF
        {
            public IdleStrategy create()
            {
                return new BackoffIdleStrategy(100, 100, 1, 1 << 20);
            }
        },

    /** Never sleeps. Lowest latency, burns a core; only sensible with a pinned thread. */
    BUSY_SPIN
        {
            public IdleStrategy create()
            {
                return BusySpinIdleStrategy.INSTANCE;
            }
        },

    /** Sleeps a millisecond between empty polls. Cheapest; adds up to a millisecond of latency. */
    SLEEPING
        {
            public IdleStrategy create()
            {
                return new SleepingMillisIdleStrategy(1);
            }
        };

    /**
     * Builds a fresh strategy. Never share one instance between threads: {@link BackoffIdleStrategy}
     * carries mutable state.
     *
     * @return a new idle strategy of this kind.
     */
    public abstract IdleStrategy create();
}
