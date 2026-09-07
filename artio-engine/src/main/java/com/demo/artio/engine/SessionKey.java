package com.demo.artio.engine;

/**
 * The identity of one FIX session, resolved once when the session is acquired and then handed to
 * every {@link FixMessageView} for that session. Immutable, so a sink may keep it.
 *
 * <p>The two CompIDs are named from <em>this engine's</em> point of view rather than from any one
 * message's, because a session carries traffic both ways. On an inbound message
 * {@code SenderCompID(49)} is {@link #remoteCompId()} and {@code TargetCompID(56)} is
 * {@link #localCompId()}; on an outbound message it is the other way round.
 *
 * @param sessionId    Artio's surrogate session id, stable for the life of the session.
 * @param localCompId  this engine's CompID.
 * @param remoteCompId the counterparty's CompID.
 * @param beginString  the session's FIX version, e.g. {@code FIX.4.2}.
 * @param acceptor     true if this engine accepted the session, false if it initiated it.
 */
public record SessionKey(
    long sessionId,
    String localCompId,
    String remoteCompId,
    String beginString,
    boolean acceptor)
{
    @Override
    public String toString()
    {
        return (acceptor ? "acceptor[" : "initiator[") + beginString + ' ' +
            localCompId + "<->" + remoteCompId + " id=" + sessionId + ']';
    }
}
