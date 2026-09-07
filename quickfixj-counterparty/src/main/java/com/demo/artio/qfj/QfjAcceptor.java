package com.demo.artio.qfj;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.ConfigError;
import quickfix.LogFactory;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.RuntimeError;
import quickfix.SLF4JLogFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.DefaultMessageFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A QuickFIX/J acceptor that behaves like a small venue: it binds a port, accepts one session, and
 * answers every order it receives with execution reports.
 *
 * <p>Used two ways: as the counterparty for an Artio <em>initiator</em> in the integration tests,
 * and as the {@code acceptor} mode of {@link QfjMain} for the demo.
 *
 * <p>Everything is in memory - {@link MemoryStoreFactory} for sequence numbers, no files - so a
 * test can start and stop as many as it likes without cleaning up after itself.
 */
public final class QfjAcceptor implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QfjAcceptor.class);

    private final QfjConfig config;
    private final RecordingApplication application = new RecordingApplication();
    private final ExecutionReports executionReports;
    private final SessionSettings settings;
    private final SocketAcceptor acceptor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Builds the acceptor. Nothing is bound until {@link #start()}.
     *
     * @param config the configuration; its role must be {@link QfjRole#ACCEPTOR}.
     */
    public QfjAcceptor(final QfjConfig config)
    {
        if (config.role() != QfjRole.ACCEPTOR)
        {
            throw new IllegalArgumentException("QfjAcceptor needs an ACCEPTOR config but got " + config.role());
        }
        this.config = config;
        this.executionReports = new ExecutionReports(config.version());
        this.settings = QfjSettings.create(config);
        application.appMessageHandler(this::reply);

        final LogFactory logFactory = config.screenLog() ?
            new ScreenLogFactory(settings) : new SLF4JLogFactory(settings);
        try
        {
            this.acceptor = new SocketAcceptor(
                application, new MemoryStoreFactory(), settings, logFactory, new DefaultMessageFactory());
        }
        catch (final ConfigError e)
        {
            throw new IllegalStateException("Could not build a QuickFIX/J acceptor on port " + config.port(), e);
        }
    }

    /**
     * Binds the port and starts accepting.
     *
     * @throws IllegalStateException if already started or if the port cannot be bound.
     */
    public void start()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("QfjAcceptor has already been started");
        }
        try
        {
            acceptor.start();
        }
        catch (final ConfigError | RuntimeError e)
        {
            throw new IllegalStateException("Could not start the QuickFIX/J acceptor on port " + config.port(), e);
        }
        LOGGER.info("QuickFIX/J acceptor listening on {}:{} as {} ({} <- {})",
            config.host(), config.port(), config.version().beginString(),
            config.senderCompId(), config.targetCompId());
    }

    private void reply(final Message request, final SessionID sessionId)
    {
        try
        {
            for (final Message report : executionReports.repliesTo(request))
            {
                Session.sendToTarget(report, sessionId);
            }
        }
        catch (final SessionNotFound e)
        {
            LOGGER.warn("Could not answer {}: session {} is gone", request, sessionId, e);
        }
        catch (final quickfix.FieldNotFound e)
        {
            LOGGER.warn("Could not answer {}: {}", RawFix.printable(request.toString()), e.getMessage());
        }
    }

    /**
     * Blocks until a counterparty has logged on.
     *
     * @param timeout how long to wait.
     * @return true if a session logged on, false on timeout.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitLogon(final Duration timeout) throws InterruptedException
    {
        return application.awaitLogon(timeout);
    }

    /**
     * Blocks until every session has logged out.
     *
     * @param timeout how long to wait.
     * @return true if none is logged on, false on timeout.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitLogout(final Duration timeout) throws InterruptedException
    {
        return application.awaitLogout(timeout);
    }

    /**
     * Blocks until at least {@code count} application messages of one type have been received.
     *
     * @param msgType the {@code MsgType(35)} value, e.g. {@code D}.
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

    /** @return every message received, oldest first. */
    public List<CapturedMessage> receivedMessages()
    {
        return application.received();
    }

    /** @return every message sent, oldest first. */
    public List<CapturedMessage> sentMessages()
    {
        return application.sent();
    }

    /** @return true if a counterparty is logged on right now. */
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

    /** @return the session identity this acceptor answers on. */
    public SessionID sessionId()
    {
        return QfjSettings.sessionId(config);
    }

    /** @return the configuration this acceptor was built from. */
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

    /** Logs out, unbinds the port and releases QuickFIX/J's threads. Idempotent. */
    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        if (started.get())
        {
            acceptor.stop(false);
        }
        LOGGER.info("QuickFIX/J acceptor on port {} stopped", config.port());
    }
}
