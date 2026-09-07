package com.demo.artio.bridge;

/**
 * An AMPS call failed for a reason that is not a disconnect.
 *
 * <p>Unchecked on purpose. The publisher agent's {@code doWork} is the only caller, it counts these
 * as {@link BridgeStats#publishErrors()} and carries on, and threading a checked exception through
 * an agrona {@code MessageHandler} - which cannot declare one - would achieve nothing else.
 * A disconnect is <em>not</em> reported this way: {@link AmpsPublishPort#publish} returns false and
 * the port reconnects.
 */
public class AmpsPublishException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * @param message what failed.
     * @param cause   the AMPS exception.
     */
    public AmpsPublishException(final String message, final Throwable cause)
    {
        super(message, cause);
    }

    /**
     * @param message what failed.
     */
    public AmpsPublishException(final String message)
    {
        super(message);
    }
}
