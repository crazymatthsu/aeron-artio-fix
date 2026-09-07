package com.demo.artio.engine;

/**
 * Where {@link ArtioRuntime} delivers every FIX message it receives.
 *
 * <p>This is the seam the AMPS bridge plugs into. The runtime calls {@link #onMessage} on the
 * library poll thread with a flyweight over Aeron's own buffer: nothing has been copied to the
 * heap, and nothing has to be, as long as the sink respects the rules on {@link FixMessageView}.
 *
 * <p>Implementations must be quick and must not block. Anything slower than "copy the bytes into a
 * ring buffer" belongs on another thread.
 */
@FunctionalInterface
public interface FixMessageSink
{
    /**
     * A FIX message has arrived.
     *
     * @param message a view valid only for the duration of this call.
     */
    void onMessage(FixMessageView message);

    /** A sink that throws everything away. Useful as a default. */
    FixMessageSink NO_OP = message ->
    {
    };
}
