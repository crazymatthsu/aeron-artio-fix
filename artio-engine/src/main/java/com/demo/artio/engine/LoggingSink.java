package com.demo.artio.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Prints every message through SLF4J with SOH rendered as {@code |}.
 *
 * <p>This allocates a String per message, so it is a development and demo sink, not a production
 * one. Set the logger {@code com.demo.artio.engine.LoggingSink} to {@code INFO} to see traffic and
 * to {@code WARN} to silence it without removing the sink.
 */
public final class LoggingSink implements FixMessageSink
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingSink.class);

    private final String prefix;
    private final boolean includeAdmin;
    /** Where formatted lines go; null means the SLF4J logger. Tests capture lines through this. */
    private final Consumer<String> output;

    /**
     * Logs application messages only, unprefixed: session-level messages (Heartbeat, Logon, ...)
     * are skipped. Use {@link #LoggingSink(String, boolean)} to see them too.
     */
    public LoggingSink()
    {
        this("", false);
    }

    /**
     * @param prefix       a label printed before each message, e.g. the runtime name.
     * @param includeAdmin false to skip session-level messages (Heartbeat, Logon, ...).
     */
    public LoggingSink(final String prefix, final boolean includeAdmin)
    {
        this(prefix, includeAdmin, null);
    }

    /**
     * Package-private: lets a test read the lines back instead of scraping a logger.
     *
     * @param prefix       a label printed before each message.
     * @param includeAdmin false to skip session-level messages.
     * @param output       receives each formatted line; null to log through SLF4J.
     */
    LoggingSink(final String prefix, final boolean includeAdmin, final Consumer<String> output)
    {
        this.prefix = prefix == null ? "" : prefix;
        this.includeAdmin = includeAdmin;
        this.output = output;
    }

    /** @return true if session-level messages are logged as well as application messages. */
    public boolean includesAdminMessages()
    {
        return includeAdmin;
    }

    /** @return the label printed before each message; empty by default. */
    public String prefix()
    {
        return prefix;
    }

    @Override
    public void onMessage(final FixMessageView message)
    {
        if (!includeAdmin && message.isAdmin())
        {
            return;
        }
        if (output != null)
        {
            output.accept(format(message));
        }
        else if (LOGGER.isInfoEnabled())
        {
            LOGGER.info(format(message));
        }
    }

    private String format(final FixMessageView message)
    {
        return prefix + "<- [" + (message.isAdmin() ? "admin" : "app") + ' ' + message.msgTypeAsString() +
            " seq=" + message.sequenceNumber() + "] " + message.toPrintableString();
    }

    @Override
    public String toString()
    {
        return "LoggingSink{prefix='" + prefix + "', includeAdmin=" + includeAdmin + '}';
    }
}
