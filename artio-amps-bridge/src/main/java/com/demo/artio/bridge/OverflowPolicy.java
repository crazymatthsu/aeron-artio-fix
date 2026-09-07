package com.demo.artio.bridge;

/**
 * What {@link AmpsFixPublisher#onMessage} does when the ring buffer is full - that is, when AMPS
 * has been slower than the FIX session for long enough to fill
 * {@link BridgeConfig#ringBufferCapacityBytes()}.
 *
 * <p>There is no third option. The ring buffer exists precisely because the alternative - letting
 * a slow AMPS reach back into Artio's poll thread - stalls every session on the library, stops
 * heartbeats, and eventually gets the engine disconnected by its counterparties. So the choice is
 * only ever "drop the message" or "delay the poll thread for a bounded time and then drop the
 * message".
 */
public enum OverflowPolicy
{
    /**
     * Drop the message immediately and increment {@link BridgeStats#dropped()}. The default, and
     * the right answer for a FIX gateway: the session keeps running, the loss is visible in the
     * counters, and Artio's own message log still holds what arrived.
     */
    DROP_AND_COUNT,

    /**
     * Spin on the configured idle strategy for up to
     * {@link BridgeConfig#overflowBlockTimeoutMs()}, retrying the write, and drop only if the ring
     * is still full when that expires.
     *
     * <p>Buys a short burst absorbed rather than lost, at the price of holding the Artio poll
     * thread. Use it when the AMPS stream is a system of record and a brief pause is preferable to
     * a gap - and keep the timeout well under the session's heartbeat interval, because nothing
     * else on that library is served while it waits.
     */
    BLOCK
}
