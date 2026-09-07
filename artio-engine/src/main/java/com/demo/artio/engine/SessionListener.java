package com.demo.artio.engine;

/**
 * Session lifecycle callbacks. Every method is invoked on the library poll thread, in the middle of
 * {@code FixLibrary.poll}, so an implementation must not block.
 *
 * <p>All methods have no-op defaults; implement only the ones you need.
 */
public interface SessionListener
{
    /**
     * The {@code FixLibrary} now owns this session. For an acceptor this happens once the logon has
     * been accepted; for an initiator, once {@code library.initiate(...)} completes. It does
     * <em>not</em> mean the session is logged on yet.
     *
     * @param session the session's identity.
     */
    default void onSessionAcquired(SessionKey session)
    {
    }

    /**
     * A {@code Logon(35=A)} arrived from the counterparty. For an acceptor this is the counterparty's
     * logon; for an initiator it is the logon acknowledgement, i.e. the session is now active.
     *
     * @param session the session's identity.
     */
    default void onLogon(SessionKey session)
    {
    }

    /**
     * A {@code Logout(35=5)} arrived from the counterparty. A disconnect normally follows.
     *
     * @param session the session's identity.
     */
    default void onLogout(SessionKey session)
    {
    }

    /**
     * The TCP connection has gone.
     *
     * @param session the session's identity.
     * @param reason  Artio's {@code DisconnectReason}, as a string.
     */
    default void onDisconnect(SessionKey session, String reason)
    {
    }

    /**
     * The library lost ownership of the session because it stopped polling for too long. The
     * connection is still up but the engine now owns it.
     *
     * @param session the session's identity.
     */
    default void onTimeout(SessionKey session)
    {
    }

    /**
     * An initiator lost its session and is about to initiate it again. Fires once per attempt,
     * just before {@code library.initiate(...)}; a successful attempt is followed by the usual
     * {@link #onSessionAcquired(SessionKey)} and {@link #onLogon(SessionKey)}. Only when
     * {@code FixEngineConfig.reconnectEnabled()} is true.
     *
     * @param attempt   1 for the first attempt after a disconnect, 2 for the next, and so on;
     *                  resets after a successful logon.
     * @param backoffMs how long the runtime waited before this attempt.
     */
    default void onReconnectAttempt(int attempt, long backoffMs)
    {
    }

    /**
     * An error was raised by an Artio or Aeron agent thread. The runtime logs these too; implement
     * this to fail a test or trip a health check.
     *
     * @param error the error.
     */
    default void onError(Throwable error)
    {
    }
}
