package com.demo.artio.qfj;

/** Which end of the TCP connection this QuickFIX/J engine is. */
public enum QfjRole
{
    /** Connects out and sends the {@code Logon}. */
    INITIATOR,

    /** Binds a port and answers a {@code Logon}. */
    ACCEPTOR
}
