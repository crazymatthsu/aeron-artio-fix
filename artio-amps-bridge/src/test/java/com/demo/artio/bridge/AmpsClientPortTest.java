package com.demo.artio.bridge;

import com.crankuptheamps.client.Store;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.BadFilterException;
import com.crankuptheamps.client.exception.ConnectionRefusedException;
import com.crankuptheamps.client.exception.DisconnectedException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AmpsClientPort}'s reconnect behaviour, against a fake connection that can be made to fail
 * on demand and a clock the test moves by hand - so a 30-second back-off costs nothing to verify.
 */
class AmpsClientPortTest
{
    private static final byte[] TOPIC = "fix.raw".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PAYLOAD = TestFix.newOrderSingle("ORD-1");

    private final BridgeConfig config = BridgeConfig.builder()
        .reconnectInitialBackoffMs(100)
        .reconnectMaxBackoffMs(800)
        .build();

    /** A clock the test advances explicitly. */
    private static final class FakeClock
    {
        private long millis = 1_000;

        long now()
        {
            return millis;
        }

        void advance(final long by)
        {
            millis += by;
        }
    }

    /** A connection that records publishes and can be told to fail. */
    private static final class FakeConnection implements AmpsConnection
    {
        private final List<String> published = new ArrayList<>();
        private AMPSException failPublishWith;
        private AMPSException failFlushWith;
        private boolean closed;

        @Override
        public void publish(
            final byte[] topic, final int topicLength, final byte[] data, final int offset, final int length)
            throws AMPSException
        {
            if (failPublishWith != null)
            {
                throw failPublishWith;
            }
            published.add(new String(topic, 0, topicLength, StandardCharsets.US_ASCII));
        }

        @Override
        public void flush(final long timeoutMs) throws AMPSException
        {
            if (failFlushWith != null)
            {
                throw failFlushWith;
            }
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    /** A factory that hands out fakes, records the store each was given, and can be told to refuse. */
    private static final class FakeFactory implements AmpsConnection.Factory
    {
        private final List<FakeConnection> opened = new ArrayList<>();
        /** The publish store handed over on each successful connect, null included, in order. */
        private final List<Store> storesGiven = new ArrayList<>();
        private final AtomicInteger refusalsRemaining = new AtomicInteger();

        @Override
        public AmpsConnection connect(final BridgeConfig config, final Store publishStore)
            throws AMPSException
        {
            if (refusalsRemaining.get() > 0 && refusalsRemaining.getAndDecrement() > 0)
            {
                throw new ConnectionRefusedException("AMPS is not listening");
            }
            final FakeConnection connection = new FakeConnection();
            opened.add(connection);
            storesGiven.add(publishStore);
            return connection;
        }

        FakeConnection latest()
        {
            return opened.get(opened.size() - 1);
        }

        int openCount()
        {
            return opened.size();
        }
    }

    private final FakeFactory factory = new FakeFactory();
    private final FakeClock clock = new FakeClock();
    private final AmpsClientPort port = new AmpsClientPort(config, factory, clock::now);

    @Test
    void theFirstPublishConnectsOnDemand()
    {
        assertFalse(port.isConnected(), "nothing is opened until it is needed");

        final boolean published = port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);

        assertAll(
            () -> assertTrue(published),
            () -> assertTrue(port.isConnected()),
            () -> assertEquals(1, factory.openCount()),
            () -> assertEquals(List.of("fix.raw"), factory.latest().published),
            () -> assertEquals(0, port.lostWhileDisconnected()));
    }

    @Test
    void aDisconnectMidPublishCostsThatMessageAndReconnectsOnTheNext()
    {
        port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        final FakeConnection first = factory.latest();
        first.failPublishWith = new DisconnectedException("socket closed");

        final boolean lost = port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);

        assertAll(
            () -> assertFalse(lost, "the message that met the disconnect is lost, not retried"),
            () -> assertEquals(1, port.lostWhileDisconnected()),
            () -> assertFalse(port.isConnected()),
            () -> assertTrue(first.closed, "the dead connection is closed, not leaked"));

        // Still inside the back-off: no connect attempt is made at all.
        final boolean stillLost = port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        assertAll(
            () -> assertFalse(stillLost),
            () -> assertEquals(2, port.lostWhileDisconnected()),
            () -> assertEquals(1, factory.openCount(), "an outage costs one connect per back-off, not per message"));

        clock.advance(config.reconnectInitialBackoffMs());
        final boolean recovered = port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);

        assertAll(
            () -> assertTrue(recovered),
            () -> assertEquals(2, factory.openCount()),
            () -> assertEquals(1, port.reconnects()),
            () -> assertEquals(2, port.lostWhileDisconnected(), "the count is cumulative, not reset"));
    }

    @Test
    void theBackoffDoublesUpToTheConfiguredCeilingAndResetsOnSuccess()
    {
        factory.refusalsRemaining.set(5);

        // 100 -> 200 -> 400 -> 800 -> 800 (the ceiling), one attempt per elapsed interval.
        final List<Long> backoffs = new ArrayList<>();
        for (int i = 0; i < 5; i++)
        {
            port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
            backoffs.add(port.currentBackoffMs());
            clock.advance(port.currentBackoffMs());
        }

        assertAll(
            () -> assertEquals(List.of(200L, 400L, 800L, 800L, 800L), backoffs),
            () -> assertEquals(5, port.connectFailures()),
            () -> assertEquals(5, port.lostWhileDisconnected()),
            () -> assertFalse(port.isConnected()));

        // The sixth attempt succeeds; the back-off goes back to where it started. This is the
        // port's FIRST connection, so it is not a reconnect: reconnects() counts recoveries of a
        // connection that was once up, and a start-up refusal is not one.
        assertAll(
            () -> assertTrue(port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length)),
            () -> assertEquals(config.reconnectInitialBackoffMs(), port.currentBackoffMs()),
            () -> assertEquals(0, port.reconnects(), "a first connection after refusals is not a reconnect"),
            () -> assertTrue(port.wasConnected()));
    }

    @Test
    void aFailureThatIsNotADisconnectIsAnErrorRatherThanTheReconnectPath()
    {
        port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        factory.latest().failPublishWith = new BadFilterException("nonsense");

        assertAll(
            // Reconnecting would not help, and swallowing it would hide a real bug; the publisher
            // counts these separately as publishErrors.
            () -> assertThrows(AmpsPublishException.class,
                () -> port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length)),
            () -> assertEquals(0, port.lostWhileDisconnected()),
            () -> assertTrue(port.isConnected(), "the connection is still good"));
    }

    @Test
    void connectAtStartUpReportsFailureWithoutThrowing()
    {
        factory.refusalsRemaining.set(1);

        assertAll(
            () -> assertFalse(port.connect(), "a broker that is down must not stop the FIX gateway"),
            () -> assertEquals(1, port.connectFailures()),
            () -> assertEquals(0, port.lostWhileDisconnected(), "no message was involved"));

        clock.advance(1_000);
        assertTrue(port.connect());
    }

    @Test
    void flushOnADisconnectedPortIsANoOpRatherThanAFailure()
    {
        // Nothing was handed to a client that still exists, so there is nothing to wait for.
        port.flush(1_000);

        assertFalse(port.isConnected());
    }

    @Test
    void closeReleasesTheConnectionAndStopsReconnecting()
    {
        port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        final FakeConnection connection = factory.latest();

        port.close();
        port.close();

        final boolean afterClose = port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        assertAll(
            () -> assertTrue(connection.closed),
            () -> assertFalse(port.isConnected()),
            () -> assertFalse(afterClose),
            () -> assertEquals(1, factory.openCount(), "a closed port does not reconnect"));
    }

    @Test
    void theSameStoreReachesEveryConnectionWhenGuaranteedPublishingIsOn()
    {
        // The whole point of the store is what it still holds when the NEXT connection logs on.
        // A store created per connection dies with the connection and replays nothing; the port
        // must create one, keep it, and hand that same instance to every client it opens.
        final BridgeConfig guaranteed = config.toBuilder().guaranteedPublishing(true).build();
        final List<Store> created = new ArrayList<>();
        final AmpsClientPort guaranteedPort = new AmpsClientPort(guaranteed, factory, clock::now,
            () ->
            {
                final Store store = AmpsClientConnection.newPublishStore();
                created.add(store);
                return store;
            });

        guaranteedPort.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        factory.latest().failPublishWith = new DisconnectedException("socket closed");
        guaranteedPort.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        clock.advance(config.reconnectInitialBackoffMs());
        guaranteedPort.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);

        assertAll(
            () -> assertEquals(2, factory.openCount(), "one connection, one reconnection"),
            () -> assertEquals(1, created.size(), "one store for the life of the port"),
            () -> assertEquals(2, factory.storesGiven.size()),
            () -> assertSame(created.get(0), factory.storesGiven.get(0), "the first connection got it"),
            () -> assertSame(created.get(0), factory.storesGiven.get(1), "and so did the one after the outage"),
            () -> assertSame(created.get(0), guaranteedPort.publishStore()));
        guaranteedPort.close();
    }

    @Test
    void noStoreIsCreatedOrHandedOverWhenGuaranteedPublishingIsOff()
    {
        final AmpsClientPort plain = new AmpsClientPort(config, factory, clock::now,
            () -> { throw new AssertionError("no store should be built when guaranteed publishing is off"); });

        plain.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);

        assertAll(
            () -> assertEquals(1, factory.storesGiven.size()),
            () -> assertNull(factory.storesGiven.get(0), "the factory is told there is no store"),
            () -> assertNull(plain.publishStore()));
    }

    @Test
    void theClientNameIsStableAcrossConnectionsOnlyWithGuaranteedPublishing()
    {
        // The store numbers every publish and AMPS deduplicates a replay by client name and
        // sequence number; a fresh name per reconnect would turn the replay into duplicates. Without
        // a store there is nothing to deduplicate, and a unique suffix keeps a half-closed earlier
        // connection under the same name from colliding with the new one.
        final BridgeConfig guaranteed = config.toBuilder().guaranteedPublishing(true).build();

        assertAll(
            () -> assertEquals(config.clientName(), AmpsClientConnection.clientName(guaranteed)),
            () -> assertEquals(AmpsClientConnection.clientName(guaranteed), AmpsClientConnection.clientName(guaranteed),
                "the same name every time"),
            () -> assertTrue(AmpsClientConnection.clientName(config).startsWith(config.clientName() + "-"),
                "without a store the configured name is a prefix"),
            () -> assertNotEquals(AmpsClientConnection.clientName(config), AmpsClientConnection.clientName(config),
                "and each connection gets its own suffix"));
    }

    @Test
    void aDisconnectNoticedInFlushIsAReconnectOnceTheNextPublishGetsThrough()
    {
        // The counters must not depend on WHERE the disconnect was noticed. A connection lost in
        // flush() and regained on the next publish is a reconnect like any other, and since no
        // message arrived in between, the loss for that outage is zero - not "untouched".
        port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);
        final FakeConnection first = factory.latest();
        first.failFlushWith = new DisconnectedException("gone during flush");

        port.flush(1_000);
        assertAll(
            () -> assertFalse(port.isConnected(), "the port noticed"),
            () -> assertTrue(first.closed, "and closed the dead connection"),
            () -> assertEquals(0, port.reconnects(), "nothing has come back yet"));

        clock.advance(config.reconnectInitialBackoffMs());
        final boolean recovered = port.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);

        assertAll(
            () -> assertTrue(recovered),
            () -> assertEquals(2, factory.openCount()),
            () -> assertEquals(1, port.reconnects(), "lost in flush, regained in publish: a reconnect"),
            () -> assertEquals(0, port.lostWhileDisconnected(), "no message met the outage"),
            () -> assertTrue(port.wasConnected()));
    }

    @Test
    void aDisconnectDuringFlushIsNotAnError()
    {
        final AmpsConnection.Factory disconnectingFlush = (cfg, store) -> new AmpsConnection()
        {
            @Override
            public void publish(
                final byte[] topic, final int topicLength, final byte[] data,
                final int offset, final int length)
            {
            }

            @Override
            public void flush(final long timeoutMs) throws AMPSException
            {
                throw new DisconnectedException("gone during flush");
            }

            @Override
            public void close()
            {
            }
        };
        final AmpsClientPort flushing = new AmpsClientPort(config, disconnectingFlush, clock::now);
        flushing.publish(TOPIC, TOPIC.length, PAYLOAD, 0, PAYLOAD.length);

        flushing.flush(1_000);

        assertFalse(flushing.isConnected(), "the port noticed and will reconnect");
    }
}
