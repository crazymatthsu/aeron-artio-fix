package com.demo.artio.qfj;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.RuntimeError;
import quickfix.SLF4JLogFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A QuickFIX/J initiator: connects out, logs on, and sends orders on demand or as a scripted
 * {@link OrderScenario}.
 *
 * <p>Used as the counterparty for an Artio <em>acceptor</em> in the integration tests, and as the
 * {@code initiator} mode of {@link QfjMain} for the demo.
 */
public final class QfjInitiator implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QfjInitiator.class);

    private final QfjConfig config;
    private final RecordingApplication application = new RecordingApplication();
    private final SessionSettings settings;
    private final SessionID sessionId;
    private final SocketInitiator initiator;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Builds the initiator. No connection is attempted until {@link #start()}.
     *
     * @param config the configuration; its role must be {@link QfjRole#INITIATOR}.
     */
    public QfjInitiator(final QfjConfig config)
    {
        if (config.role() != QfjRole.INITIATOR)
        {
            throw new IllegalArgumentException("QfjInitiator needs an INITIATOR config but got " + config.role());
        }
        this.config = config;
        this.settings = QfjSettings.create(config);
        this.sessionId = QfjSettings.sessionId(config);

        final LogFactory logFactory = config.screenLog() ?
            new ScreenLogFactory(settings) : new SLF4JLogFactory(settings);
        try
        {
            this.initiator = new SocketInitiator(
                application, new MemoryStoreFactory(), settings, logFactory, new DefaultMessageFactory());
        }
        catch (final ConfigError e)
        {
            throw new IllegalStateException(
                "Could not build a QuickFIX/J initiator for " + config.host() + ':' + config.port(), e);
        }
    }

    /**
     * Starts connecting. Returns immediately; use {@link #awaitLogon(Duration)} to wait for the
     * session to come up.
     *
     * @throws IllegalStateException if already started or if QuickFIX/J refuses the settings.
     */
    public void start()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("QfjInitiator has already been started");
        }
        try
        {
            initiator.start();
        }
        catch (final ConfigError | RuntimeError e)
        {
            throw new IllegalStateException(
                "Could not start the QuickFIX/J initiator for " + config.host() + ':' + config.port(), e);
        }
        LOGGER.info("QuickFIX/J initiator connecting to {}:{} as {} ({} -> {})",
            config.host(), config.port(), config.version().beginString(),
            config.senderCompId(), config.targetCompId());
    }

    /**
     * Blocks until the session is logged on.
     *
     * @param timeout how long to wait.
     * @return true if the session logged on, false on timeout.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitLogon(final Duration timeout) throws InterruptedException
    {
        return application.awaitLogon(timeout);
    }

    /**
     * Blocks until the session has logged out or the connection has gone.
     *
     * @param timeout how long to wait.
     * @return true if logged out, false on timeout.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitLogout(final Duration timeout) throws InterruptedException
    {
        return application.awaitLogout(timeout);
    }

    /**
     * Blocks until at least {@code count} messages of one type have been received.
     *
     * @param msgType the {@code MsgType(35)} value, e.g. {@code 8}.
     * @param count   how many to wait for.
     * @param timeout how long to wait.
     * @return true if enough arrived, false on timeout.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitReceived(final String msgType, final int count, final Duration timeout)
        throws InterruptedException
    {
        return application.awaitReceived(count, message -> message.msgType().equals(msgType), timeout);
    }

    /**
     * Sends a {@code NewOrderSingle(35=D)}.
     *
     * @param clOrdId {@code ClOrdID(11)}.
     * @param symbol  {@code Symbol(55)}.
     * @param side    {@code Side(54)}, e.g. {@link Orders#SIDE_BUY}.
     * @param qty     {@code OrderQty(38)}.
     * @param price   {@code Price(44)}.
     * @return true if QuickFIX/J accepted the message for sending.
     */
    public boolean sendNewOrderSingle(
        final String clOrdId, final String symbol, final char side, final double qty, final double price)
    {
        return send(Orders.newOrderSingle(config.version(), clOrdId, symbol, side, qty, price));
    }

    /**
     * Sends an {@code OrderCancelReplaceRequest(35=G)}.
     *
     * @param origClOrdId {@code OrigClOrdID(41)}: the order being replaced.
     * @param clOrdId     {@code ClOrdID(11)}: the new identifier.
     * @param symbol      {@code Symbol(55)}.
     * @param side        {@code Side(54)}.
     * @param qty         the replacement {@code OrderQty(38)}.
     * @param price       the replacement {@code Price(44)}.
     * @return true if QuickFIX/J accepted the message for sending.
     */
    public boolean sendCancelReplace(
        final String origClOrdId,
        final String clOrdId,
        final String symbol,
        final char side,
        final double qty,
        final double price)
    {
        return send(Orders.cancelReplace(config.version(), origClOrdId, clOrdId, symbol, side, qty, price));
    }

    /**
     * Sends an {@code OrderCancelRequest(35=F)}.
     *
     * @param origClOrdId {@code OrigClOrdID(41)}: the order being cancelled.
     * @param clOrdId     {@code ClOrdID(11)}: the identifier of the cancel.
     * @param symbol      {@code Symbol(55)}.
     * @param side        {@code Side(54)}.
     * @param qty         {@code OrderQty(38)} of the order being cancelled.
     * @return true if QuickFIX/J accepted the message for sending.
     */
    public boolean sendCancel(
        final String origClOrdId,
        final String clOrdId,
        final String symbol,
        final char side,
        final double qty)
    {
        return send(Orders.cancel(config.version(), origClOrdId, clOrdId, symbol, side, qty));
    }

    /**
     * Sends the fifteen messages of a scenario's drop copy stream, in order: the five order events
     * and the ten execution reports they produce.
     *
     * @param scenario the scenario.
     * @return the five order {@code ClOrdID}s, in the order they were sent; the reports repeat them.
     */
    public List<String> run(final OrderScenario scenario)
    {
        run(scenario.steps());
        return scenario.clOrdIds();
    }

    /**
     * Sends an explicit list of steps, in order - {@link OrderScenario#ORDERS_ONLY}, say, or any
     * other slice of a scenario.
     *
     * @param steps the steps to send.
     * @return the distinct {@code ClOrdID}s sent, in first-sent order.
     */
    public List<String> run(final List<OrderScenario.Step> steps)
    {
        for (final OrderScenario.Step step : steps)
        {
            send(Orders.toMessage(config.version(), step));
        }
        return steps.stream().map(OrderScenario.Step::clOrdId).distinct().toList();
    }

    /**
     * Sends an already built message on this initiator's session.
     *
     * @param message the message; QuickFIX/J fills in the header.
     * @return true if QuickFIX/J accepted it for sending.
     * @throws IllegalStateException if the session does not exist.
     */
    public boolean send(final Message message)
    {
        try
        {
            return Session.sendToTarget(message, sessionId);
        }
        catch (final SessionNotFound e)
        {
            throw new IllegalStateException("No QuickFIX/J session " + sessionId, e);
        }
    }

    /** @return every message received, oldest first. */
    public List<CapturedMessage> receivedMessages()
    {
        return application.received();
    }

    /**
     * @param msgType the {@code MsgType(35)} value.
     * @return the received messages of that type, oldest first.
     */
    public List<CapturedMessage> receivedMessages(final String msgType)
    {
        return application.receivedOfType(msgType);
    }

    /** @return every message sent, oldest first. */
    public List<CapturedMessage> sentMessages()
    {
        return application.sent();
    }

    /** @return true if the session is logged on right now. */
    public boolean isLoggedOn()
    {
        return application.isLoggedOn();
    }

    /** @return how many logouts have been seen. */
    public int logoutCount()
    {
        return application.logoutCount();
    }

    /** @return the recording application, for tests that need finer control. */
    public RecordingApplication application()
    {
        return application;
    }

    /** @return the session identity this initiator uses. */
    public SessionID sessionId()
    {
        return sessionId;
    }

    /** @return the configuration this initiator was built from. */
    public QfjConfig config()
    {
        return config;
    }

    /**
     * @param print true to print every message to stdout with SOH shown as {@code |}.
     */
    public void printMessages(final boolean print)
    {
        application.printMessages(print);
    }

    /** Logs out, disconnects and releases QuickFIX/J's threads. Idempotent. */
    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        if (started.get())
        {
            initiator.stop(false);
        }
        LOGGER.info("QuickFIX/J initiator to {}:{} stopped", config.host(), config.port());
    }
}
