package com.demo.artio.bridge;

import com.crankuptheamps.client.Store;
import com.crankuptheamps.client.exception.AMPSException;

/**
 * One live AMPS connection, reduced to the three calls {@link AmpsClientPort} makes.
 *
 * <p>This exists so the reconnect logic can be tested. {@code com.crankuptheamps.client.Client} is
 * a concrete class whose failure modes (a disconnect mid-publish, a connect that refuses, a flush
 * that times out) cannot be provoked without a server; behind this interface a unit test can
 * produce any of them on demand, and {@link AmpsClientPort} never knows the difference. The real
 * implementation is {@link AmpsClientConnection}.
 *
 * <p>Checked {@link AMPSException} is kept rather than wrapped: {@code DisconnectedException} is a
 * subclass of it, and telling a disconnect apart from a genuine failure is the whole job of the
 * port.
 */
public interface AmpsConnection extends AutoCloseable
{
    /**
     * @param topic       the topic name in US-ASCII, from index 0.
     * @param topicLength the topic length in bytes.
     * @param data        the payload.
     * @param offset      the first payload byte.
     * @param length      the payload length.
     * @throws AMPSException if the publish failed;
     *                       {@link com.crankuptheamps.client.exception.DisconnectedException} means
     *                       the connection is gone and the port should reconnect.
     */
    void publish(byte[] topic, int topicLength, byte[] data, int offset, int length) throws AMPSException;

    /**
     * @param timeoutMs how long to wait for outstanding publishes to be acknowledged.
     * @throws AMPSException if the flush failed or timed out.
     */
    void flush(long timeoutMs) throws AMPSException;

    /** Closes the connection. Must not throw. */
    @Override
    void close();

    /**
     * Opens connections on demand. The port calls this on its own thread, both at start-up and
     * after a disconnect.
     */
    @FunctionalInterface
    interface Factory
    {
        /**
         * @param config       the bridge configuration: URI, client name, guaranteed-publishing
         *                     choice.
         * @param publishStore the publish store every connection this port opens must share, or
         *                     null when {@link BridgeConfig#guaranteedPublishing()} is off. The
         *                     port owns it and hands the <em>same</em> instance to each new
         *                     connection: that is what lets a client that reconnects replay what
         *                     the previous one never had acknowledged. A factory that ignored it
         *                     and built a fresh store per connection would replay nothing.
         * @return a connected, logged-on connection.
         * @throws AMPSException if AMPS could not be reached.
         */
        AmpsConnection connect(BridgeConfig config, Store publishStore) throws AMPSException;
    }
}
