package com.demo.artio.engine;

/**
 * Which end of the TCP connection this runtime is.
 *
 * <p>The difference is bigger than it looks: an {@link #ACCEPTOR} binds a port and needs a
 * dictionary configured on the <em>engine</em> so it can parse a logon from a counterparty it has
 * never seen, while an {@link #INITIATOR} connects out and carries its dictionary on the
 * <em>session</em> it initiates. See {@code docs/01-artio-engine-design.md} section 4.
 */
public enum EngineMode
{
    /** Binds {@code host:port} and accepts sessions initiated by a counterparty. */
    ACCEPTOR,

    /** Connects out to {@code host:port} and initiates a session. */
    INITIATOR
}
