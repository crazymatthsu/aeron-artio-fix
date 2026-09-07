package com.demo.artio.bridge;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.Store;
import com.crankuptheamps.client.exception.AMPSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link AmpsConnection}: a {@code com.crankuptheamps.client.Client}, connected and logged
 * on, with the byte-array publish that keeps raw FIX off the heap-String path.
 *
 * <h2>Guaranteed publishing</h2>
 * When {@link BridgeConfig#guaranteedPublishing()} is set, {@link AmpsClientPort} creates one
 * {@link MemoryPublishStore} for its own lifetime and hands that same instance to every client it
 * opens, before the client connects. What that buys, precisely:
 *
 * <ul>
 *   <li>every publish is kept in the store until AMPS acknowledges it persisted to the transaction
 *       log. When the connection drops, the port closes the client but keeps the store; the next
 *       client is given the store and, as part of its logon, <strong>replays</strong> whatever is
 *       still unacknowledged in it. So a broker bounce stops losing the messages that were in
 *       flight - the ones the client had sent and AMPS had not yet confirmed;</li>
 *   <li>the client name is kept <em>stable</em> across reconnects (no per-connection suffix), and
 *       the store numbers every publish, so AMPS recognises the returning publisher and drops a
 *       replayed message it already has rather than storing it twice.</li>
 * </ul>
 *
 * What it does <strong>not</strong> buy:
 *
 * <ul>
 *   <li>the messages that arrive at the publisher <em>while</em> the connection is down. Those never
 *       reach a client; the port counts them in {@code lostWhileDisconnected} and drops them, on
 *       purpose (see {@link AmpsClientPort});</li>
 *   <li>durability across a crash of <em>this</em> process. The store is in memory. A publish
 *       store that survives a JVM death is {@code PublishStore} (memory-mapped) or
 *       {@code HybridPublishStore}; neither is wired here because the bridge's own upstream -
 *       Artio's message log - is already the durable record of what arrived, and replaying that
 *       is the recovery story;</li>
 *   <li>anything on an unjournalled topic. A publish to one is acknowledged immediately and nothing
 *       is retained; in the {@code artio-fix} flow the journalled topics are {@code fix.raw},
 *       {@code fix.orders} and {@code fix.execs}.</li>
 * </ul>
 */
public final class AmpsClientConnection implements AmpsConnection
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AmpsClientConnection.class);

    /** Entries the in-memory publish store holds before it grows. One block per message. */
    static final int PUBLISH_STORE_BLOCKS = 10_000;

    private final Client client;

    private AmpsClientConnection(final Client client)
    {
        this.client = client;
    }

    /**
     * @return the factory {@link AmpsClientPort} uses in production: opens a real client, installs
     * the port's publish store if there is one, connects and logs on.
     */
    public static Factory factory()
    {
        return AmpsClientConnection::open;
    }

    /**
     * @return a fresh in-memory publish store, sized for {@value #PUBLISH_STORE_BLOCKS} messages
     * before it grows. {@link AmpsClientPort} creates one per port, not one per connection.
     * @throws IllegalStateException if the AMPS client cannot allocate it.
     */
    public static Store newPublishStore()
    {
        try
        {
            return new MemoryPublishStore(PUBLISH_STORE_BLOCKS);
        }
        catch (final AMPSException e)
        {
            throw new IllegalStateException("cannot create the AMPS publish store", e);
        }
    }

    /**
     * The name a client connecting for {@code config} logs on with.
     *
     * <p>Without guaranteed publishing the name gets a unique suffix: AMPS treats the client name
     * as an identity, and a server that still holds a half-closed earlier connection under the
     * same name would tear the newcomer down. With guaranteed publishing the name is
     * {@link BridgeConfig#clientName()} exactly, every time, because the name is how the server
     * recognises a returning publisher and deduplicates what it replays - a fresh name per
     * reconnect would make the replay a set of duplicates instead of a recovery.
     *
     * @param config the bridge configuration.
     * @return the client name.
     */
    static String clientName(final BridgeConfig config)
    {
        return config.guaranteedPublishing() ?
            config.clientName() : config.clientName() + "-" + System.nanoTime();
    }

    /**
     * Opens a connection.
     *
     * @param config       the bridge configuration.
     * @param publishStore the port's publish store, or null when guaranteed publishing is off.
     * @return the connected client.
     * @throws AMPSException if the connect or logon failed.
     */
    public static AmpsClientConnection open(final BridgeConfig config, final Store publishStore)
        throws AMPSException
    {
        final Client client = new Client(clientName(config));
        try
        {
            if (publishStore != null)
            {
                // Must precede connect(): the client refuses a store once it has a socket. Logon
                // is where the client replays the store's unacknowledged messages, so a store that
                // has been through a previous connection is replayed here.
                client.setPublishStore(publishStore);
            }
            client.connect(config.uri());
            client.logon(config.flushTimeoutMs());
            LOGGER.info("connected to AMPS at {} as {}{}",
                config.uri(), client.getName(),
                publishStore == null ? "" :
                    " (guaranteed publishing; " + publishStore.unpersistedCount() + " unacknowledged)");
            return new AmpsClientConnection(client);
        }
        catch (final AMPSException | RuntimeException e)
        {
            client.close();
            throw e;
        }
    }

    @Override
    public void publish(
        final byte[] topic, final int topicLength, final byte[] data, final int offset, final int length)
        throws AMPSException
    {
        // The call this whole module exists to reach: topic and payload as bytes, no String, no
        // re-encoding, the FIX message exactly as it came off the session.
        client.publish(topic, 0, topicLength, data, offset, length);
    }

    @Override
    public void flush(final long timeoutMs) throws AMPSException
    {
        client.publishFlush(timeoutMs);
    }

    @Override
    public void close()
    {
        try
        {
            client.close();
        }
        catch (final RuntimeException e)
        {
            LOGGER.warn("closing the AMPS client failed: {}", e.toString());
        }
    }

    /**
     * @return the underlying client, for diagnostics. Do not publish through it directly: the port
     * would not see the failure.
     */
    public Client client()
    {
        return client;
    }
}
