package com.demo.artio.qfj;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * A QuickFIX/J {@link Application} that records every message and lets another thread wait for
 * logon, logout or a message that satisfies a predicate.
 *
 * <p>Waiting is done with {@code wait}/{@code notifyAll} on one monitor rather than by polling, so
 * a caller is woken the moment the condition holds instead of on the next tick.
 *
 * <p>An {@link AppMessageHandler} can be installed to react to inbound application messages; that
 * is how {@link QfjAcceptor} answers orders with execution reports. It runs on QuickFIX/J's own
 * message thread.
 */
public class RecordingApplication implements Application
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingApplication.class);

    /** Reacts to an inbound application message. Runs on QuickFIX/J's message thread. */
    @FunctionalInterface
    public interface AppMessageHandler
    {
        /**
         * @param message   the inbound application message.
         * @param sessionId the session it arrived on.
         * @throws FieldNotFound if the handler expects a field the message does not have.
         */
        void onAppMessage(Message message, SessionID sessionId) throws FieldNotFound;
    }

    private final Object monitor = new Object();
    private final CopyOnWriteArrayList<CapturedMessage> received = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CapturedMessage> sent = new CopyOnWriteArrayList<>();
    private final Set<SessionID> loggedOn = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<SessionID> created = new CopyOnWriteArrayList<>();

    private volatile AppMessageHandler appMessageHandler;
    private volatile boolean printMessages;
    private volatile int logoutCount;

    /**
     * @param handler called for every inbound application message, or null for none.
     */
    public void appMessageHandler(final AppMessageHandler handler)
    {
        this.appMessageHandler = handler;
    }

    /**
     * @param printMessages true to print every message to stdout with SOH shown as {@code |}. Used
     *                      by the command line main; off in tests.
     */
    public void printMessages(final boolean printMessages)
    {
        this.printMessages = printMessages;
    }

    @Override
    public void onCreate(final SessionID sessionId)
    {
        created.addIfAbsent(sessionId);
        LOGGER.debug("session created: {}", sessionId);
    }

    @Override
    public void onLogon(final SessionID sessionId)
    {
        synchronized (monitor)
        {
            loggedOn.add(sessionId);
            monitor.notifyAll();
        }
        LOGGER.info("logon: {}", sessionId);
        if (printMessages)
        {
            System.out.println("== logon " + sessionId);
        }
    }

    @Override
    public void onLogout(final SessionID sessionId)
    {
        synchronized (monitor)
        {
            loggedOn.remove(sessionId);
            logoutCount++;
            monitor.notifyAll();
        }
        LOGGER.info("logout: {}", sessionId);
        if (printMessages)
        {
            System.out.println("== logout " + sessionId);
        }
    }

    @Override
    public void toAdmin(final Message message, final SessionID sessionId)
    {
        record(false, true, message, sessionId);
    }

    @Override
    public void fromAdmin(final Message message, final SessionID sessionId)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon
    {
        record(true, true, message, sessionId);
    }

    @Override
    public void toApp(final Message message, final SessionID sessionId) throws DoNotSend
    {
        record(false, false, message, sessionId);
    }

    @Override
    public void fromApp(final Message message, final SessionID sessionId)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType
    {
        record(true, false, message, sessionId);
        final AppMessageHandler handler = appMessageHandler;
        if (handler != null)
        {
            handler.onAppMessage(message, sessionId);
        }
    }

    private void record(final boolean inbound, final boolean admin, final Message message, final SessionID sessionId)
    {
        String msgType;
        try
        {
            msgType = message.getHeader().getString(Tags.MSG_TYPE);
        }
        catch (final FieldNotFound e)
        {
            msgType = "?";
        }
        final CapturedMessage captured =
            new CapturedMessage(inbound, admin, msgType, sessionId, message.toString());
        synchronized (monitor)
        {
            (inbound ? received : sent).add(captured);
            monitor.notifyAll();
        }
        if (printMessages)
        {
            System.out.println(captured);
        }
    }

    /** @return every message received so far, oldest first. */
    public List<CapturedMessage> received()
    {
        return List.copyOf(received);
    }

    /** @return every message sent so far, oldest first. */
    public List<CapturedMessage> sent()
    {
        return List.copyOf(sent);
    }

    /**
     * @param msgType the {@code MsgType(35)} value.
     * @return the messages of that type received so far, oldest first.
     */
    public List<CapturedMessage> receivedOfType(final String msgType)
    {
        final List<CapturedMessage> result = new ArrayList<>();
        for (final CapturedMessage message : received)
        {
            if (message.msgType().equals(msgType))
            {
                result.add(message);
            }
        }
        return result;
    }

    /** @return the sessions currently logged on. */
    public Set<SessionID> loggedOnSessions()
    {
        return Set.copyOf(loggedOn);
    }

    /** @return true if at least one session is logged on. */
    public boolean isLoggedOn()
    {
        return !loggedOn.isEmpty();
    }

    /** @return how many logouts have been seen since start-up. */
    public int logoutCount()
    {
        return logoutCount;
    }

    /**
     * Blocks until at least one session is logged on.
     *
     * @param timeout how long to wait.
     * @return true if a session logged on, false if the timeout expired.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitLogon(final Duration timeout) throws InterruptedException
    {
        return await(timeout, () -> !loggedOn.isEmpty());
    }

    /**
     * Blocks until every session has logged out (or was never on).
     *
     * @param timeout how long to wait.
     * @return true if no session is logged on, false if the timeout expired.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitLogout(final Duration timeout) throws InterruptedException
    {
        return await(timeout, loggedOn::isEmpty);
    }

    /**
     * Blocks until at least {@code count} messages matching {@code predicate} have been received.
     *
     * @param count     how many matching messages to wait for.
     * @param predicate the match.
     * @param timeout   how long to wait.
     * @return true if enough matched, false if the timeout expired.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    public boolean awaitReceived(final int count, final Predicate<CapturedMessage> predicate, final Duration timeout)
        throws InterruptedException
    {
        return await(timeout, () -> received.stream().filter(predicate).count() >= count);
    }

    private boolean await(final Duration timeout, final java.util.function.BooleanSupplier condition)
        throws InterruptedException
    {
        final long deadlineNs = System.nanoTime() + timeout.toNanos();
        synchronized (monitor)
        {
            while (!condition.getAsBoolean())
            {
                final long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0)
                {
                    return false;
                }
                monitor.wait(Math.max(1L, remainingNs / 1_000_000L), (int)(remainingNs % 1_000_000L));
            }
            return true;
        }
    }
}
