package com.demo.artio.engine;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Fans one message out to several sinks, in order, on the same thread.
 *
 * <p>The delegates share the flyweight, so each one sees the same live view; none of them may keep
 * it. If a delegate throws, the ones after it do not run - wrap a delegate you do not trust.
 */
public final class CompositeSink implements FixMessageSink
{
    private final FixMessageSink[] delegates;

    /**
     * @param delegates the sinks to call, in order. None may be null.
     */
    public CompositeSink(final FixMessageSink... delegates)
    {
        this.delegates = delegates.clone();
        for (int i = 0; i < this.delegates.length; i++)
        {
            Objects.requireNonNull(this.delegates[i], "delegate " + i);
        }
    }

    /**
     * @param delegates the sinks to call, in order.
     */
    public CompositeSink(final List<? extends FixMessageSink> delegates)
    {
        this(delegates.toArray(new FixMessageSink[0]));
    }

    /**
     * Convenience factory that collapses the trivial cases.
     *
     * @param delegates the sinks to combine.
     * @return {@link FixMessageSink#NO_OP} for none, the sink itself for one, a composite otherwise.
     */
    public static FixMessageSink of(final FixMessageSink... delegates)
    {
        if (delegates.length == 0)
        {
            return FixMessageSink.NO_OP;
        }
        if (delegates.length == 1)
        {
            return Objects.requireNonNull(delegates[0], "delegate 0");
        }
        return new CompositeSink(delegates);
    }

    /** @return the delegates, in call order. */
    public List<FixMessageSink> delegates()
    {
        return List.of(delegates);
    }

    @Override
    public void onMessage(final FixMessageView message)
    {
        for (final FixMessageSink delegate : delegates)
        {
            delegate.onMessage(message);
        }
    }

    @Override
    public String toString()
    {
        return "CompositeSink" + Arrays.toString(delegates);
    }
}
