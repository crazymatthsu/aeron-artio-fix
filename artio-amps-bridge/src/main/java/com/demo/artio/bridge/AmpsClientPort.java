package com.demo.artio.bridge;

import com.crankuptheamps.client.Store;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.ConnectionException;
import com.crankuptheamps.client.exception.TimedOutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The production {@link AmpsPublishPort}: one AMPS connection, re-established with bounded
 * exponential back-off when it drops, and an honest count of what was lost while it was down.
 *
 * <h2>What happens when AMPS goes away</h2>
 * <ol>
 *   <li>A publish throws {@code DisconnectedException}. The connection is closed and forgotten, the
 *       message is counted in {@link #lostWhileDisconnected()}, and {@code publish} returns false.
 *       It is <strong>not</strong> retried: the publisher agent must keep draining the ring buffer,
 *       because the alternative is back-pressure onto Artio's poll thread.</li>
 *   <li>Every later publish first checks whether the back-off deadline has passed. Until it has,
 *       the message is counted and dropped without touching the network - so a long outage costs
 *       one connect attempt per back-off interval, not one per message.</li>
 *   <li>A successful reconnect resets the back-off, increments {@link #reconnects()}, and logs the
 *       loss for the outage that just ended.</li>
 * </ol>
 *
 * <p>Back-off starts at {@link BridgeConfig#reconnectInitialBackoffMs()} and doubles to
 * {@link BridgeConfig#reconnectMaxBackoffMs()}.
 *
 * <p>The loss is real and the bridge does not pretend otherwise. What limits it:
 * {@link BridgeConfig#guaranteedPublishing()} adds a client-side publish store that this port owns
 * for its whole life and hands to every connection it opens, so messages the previous connection
 * had sent but AMPS had not yet acknowledged are replayed by the next one (see
 * {@link AmpsClientConnection}); and Artio's own message log still holds everything that arrived,
 * so a gap can be replayed from there. Messages that reach this port <em>while</em> it is
 * disconnected are in neither: they are counted and dropped.
 *
 * <h2>Threading</h2>
 * {@link #publish} is called only from the publisher agent thread. The counters are atomics so
 * {@link AmpsFixPublisher#stats()} can be read from anywhere; {@code connection} is volatile so a
 * concurrent {@link #close()} during shutdown is safe.
 */
public final class AmpsClientPort implements AmpsPublishPort
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AmpsClientPort.class);

    private final BridgeConfig config;
    private final AmpsConnection.Factory factory;
    private final LongSupplier clock;

    /**
     * One store per port, never per connection: a store that died with the connection would have
     * nothing left to replay when the next one logs on. Null when guaranteed publishing is off.
     */
    private final Store publishStore;

    private final AtomicLong lostWhileDisconnected = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong connectFailures = new AtomicLong();

    private volatile AmpsConnection connection;
    private volatile boolean closed;

    private long backoffMs;
    private long nextAttemptAtMs;
    private long lostThisOutage;

    /**
     * Whether this port has ever held a connection. It is what makes the next successful connect a
     * <em>re</em>connect: a first connection that follows a run of refusals at start-up is not one,
     * and a connection that follows a disconnect is one however the disconnect was noticed -
     * during a publish or during a flush.
     */
    private volatile boolean wasConnected;

    /**
     * A port that talks to a real AMPS server.
     *
     * @param config where to connect and how to back off.
     */
    public AmpsClientPort(final BridgeConfig config)
    {
        this(config, AmpsClientConnection.factory(), System::currentTimeMillis,
            AmpsClientConnection::newPublishStore);
    }

    /**
     * A port over an injected connection factory.
     *
     * @param config  where to connect and how to back off.
     * @param factory opens connections; see {@link AmpsClientConnection#factory()}.
     */
    public AmpsClientPort(final BridgeConfig config, final AmpsConnection.Factory factory)
    {
        this(config, factory, System::currentTimeMillis, AmpsClientConnection::newPublishStore);
    }

    /**
     * A port over an injected connection factory and clock, so a test can step time rather than
     * sleep through a back-off.
     *
     * @param config  where to connect and how to back off.
     * @param factory opens connections.
     * @param clock   the current time in milliseconds.
     */
    public AmpsClientPort(
        final BridgeConfig config, final AmpsConnection.Factory factory, final LongSupplier clock)
    {
        this(config, factory, clock, AmpsClientConnection::newPublishStore);
    }

    /**
     * A port over an injected connection factory, clock and publish store, so a test can watch
     * which store each connection is given.
     *
     * @param config       where to connect and how to back off.
     * @param factory      opens connections.
     * @param clock        the current time in milliseconds.
     * @param publishStore builds the store, called once and only if
     *                     {@link BridgeConfig#guaranteedPublishing()} is on.
     */
    public AmpsClientPort(
        final BridgeConfig config,
        final AmpsConnection.Factory factory,
        final LongSupplier clock,
        final Supplier<Store> publishStore)
    {
        this.config = config;
        this.factory = factory;
        this.clock = clock;
        this.publishStore = config.guaranteedPublishing() ? publishStore.get() : null;
        this.backoffMs = config.reconnectInitialBackoffMs();
    }

    /**
     * Opens the connection now, on the calling thread.
     *
     * <p>Optional: {@link #publish} connects on demand. Calling it at start-up turns "AMPS is not
     * running" into a message at start-up rather than a silent stream of dropped publishes.
     *
     * @return true if the connection was established.
     */
    public boolean connect()
    {
        return ensureConnected();
    }

    @Override
    public boolean publish(
        final byte[] topic, final int topicLength, final byte[] data, final int offset, final int length)
    {
        AmpsConnection current = connection;
        if (current == null)
        {
            ensureConnected();
            current = connection;
        }
        if (current == null)
        {
            // Backing off, or the connect failed. Count it and move on: blocking here would push
            // back-pressure through the ring buffer onto Artio's poll thread.
            recordLoss();
            return false;
        }
        return publishOn(current, topic, topicLength, data, offset, length);
    }

    private boolean publishOn(
        final AmpsConnection target,
        final byte[] topic,
        final int topicLength,
        final byte[] data,
        final int offset,
        final int length)
    {
        try
        {
            target.publish(topic, topicLength, data, offset, length);
            return true;
        }
        catch (final ConnectionException e)
        {
            // DisconnectedException extends ConnectionException, so catching the parent covers both
            // "the socket went away mid-publish" and "there was no socket". Either way this is the
            // reconnect path, not an error.
            onDisconnected(e);
            recordLoss();
            return false;
        }
        catch (final AMPSException e)
        {
            throw new AmpsPublishException("AMPS refused a publish", e);
        }
    }

    @Override
    public void flush(final long timeoutMs)
    {
        final AmpsConnection current = connection;
        if (current == null)
        {
            // Nothing was handed to a client that still exists, so there is nothing to wait for.
            return;
        }
        try
        {
            current.flush(timeoutMs);
        }
        catch (final TimedOutException e)
        {
            // Caught ahead of ConnectionException, which it extends: a flush that ran out of time
            // says AMPS is slow, not that the socket is gone, and tearing the connection down over
            // it would turn a slow shutdown into a lossy one.
            throw new AmpsPublishException(
                "AMPS did not acknowledge everything within " + timeoutMs + " ms", e);
        }
        catch (final ConnectionException e)
        {
            onDisconnected(e);
        }
        catch (final AMPSException e)
        {
            throw new AmpsPublishException("AMPS flush failed", e);
        }
    }

    @Override
    public boolean isConnected()
    {
        return !closed && connection != null;
    }

    @Override
    public void close()
    {
        closed = true;
        final AmpsConnection current = connection;
        connection = null;
        if (current != null)
        {
            current.close();
        }
        if (publishStore != null)
        {
            try
            {
                publishStore.close();
            }
            catch (final Exception e)
            {
                LOGGER.warn("closing the AMPS publish store failed: {}", e.toString());
            }
        }
    }

    /**
     * @return the publish store every connection of this port shares, or null when guaranteed
     * publishing is off. For diagnostics: {@code unpersistedCount()} is how many publishes AMPS
     * has not yet acknowledged.
     */
    public Store publishStore()
    {
        return publishStore;
    }

    /** @return true once this port has held a connection at least once. */
    public boolean wasConnected()
    {
        return wasConnected;
    }

    @Override
    public long lostWhileDisconnected()
    {
        return lostWhileDisconnected.get();
    }

    @Override
    public long reconnects()
    {
        return reconnects.get();
    }

    /** @return how many connect attempts have failed. */
    public long connectFailures()
    {
        return connectFailures.get();
    }

    /** @return the delay before the next connect attempt, in milliseconds. */
    public long currentBackoffMs()
    {
        return backoffMs;
    }

    // ------------------------------------------------------------------------------------ private

    /**
     * @return true if there is a usable connection, opening one if the back-off allows.
     */
    private boolean ensureConnected()
    {
        if (closed)
        {
            return false;
        }
        if (connection != null)
        {
            return true;
        }
        final long now = clock.getAsLong();
        if (now < nextAttemptAtMs)
        {
            // Still backing off. Do not touch the network: an outage must not cost one connect per
            // message.
            return false;
        }
        try
        {
            connection = factory.connect(config, publishStore);
            final long lost = lostThisOutage;
            lostThisOutage = 0;
            backoffMs = config.reconnectInitialBackoffMs();
            nextAttemptAtMs = 0;
            if (wasConnected)
            {
                // There was a connection before this one, so this is a reconnect - whether the
                // old one was lost in a publish or in a flush, and whether or not anything
                // arrived while it was gone.
                reconnects.incrementAndGet();
                LOGGER.warn("reconnected to AMPS at {}; {} message(s) were lost while it was down",
                    config.uri(), lost);
            }
            else if (connectFailures.get() > 0)
            {
                // The first connection this port ever made, after a run of refusals. Not a
                // reconnect: there was nothing to come back to.
                LOGGER.warn("connected to AMPS at {} after {} failed attempt(s); {} message(s) were " +
                    "lost before it came up", config.uri(), connectFailures.get(), lost);
            }
            wasConnected = true;
            return true;
        }
        catch (final AMPSException | RuntimeException e)
        {
            connectFailures.incrementAndGet();
            nextAttemptAtMs = now + backoffMs;
            LOGGER.warn("cannot connect to AMPS at {} ({}); retrying in {} ms",
                config.uri(), e.toString(), backoffMs);
            backoffMs = Math.min(config.reconnectMaxBackoffMs(), backoffMs * 2);
            return false;
        }
    }

    private void onDisconnected(final Exception cause)
    {
        final AmpsConnection current = connection;
        connection = null;
        if (current != null)
        {
            current.close();
            LOGGER.warn("AMPS connection to {} dropped ({}); reconnecting with back-off",
                config.uri(), cause.toString());
        }
        nextAttemptAtMs = clock.getAsLong() + backoffMs;
    }

    private void recordLoss()
    {
        lostWhileDisconnected.incrementAndGet();
        lostThisOutage++;
    }

    @Override
    public String toString()
    {
        return "AmpsClientPort{" + config.uri() + (isConnected() ? " connected" : " disconnected") +
            " lost=" + lostWhileDisconnected.get() + " reconnects=" + reconnects.get() + '}';
    }
}
