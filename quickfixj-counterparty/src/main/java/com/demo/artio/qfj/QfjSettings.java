package com.demo.artio.qfj;

import quickfix.Acceptor;
import quickfix.Initiator;
import quickfix.Session;
import quickfix.SessionFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * Builds QuickFIX/J {@link SessionSettings} in memory.
 *
 * <p>There are no {@code .cfg} files anywhere in this project: a settings file would be one more
 * thing to keep in step with a test's free port, and QuickFIX/J's own API is perfectly capable of
 * expressing everything. Settings that belong to the whole connector (connection type, store,
 * validation) go in the default section; the per-session ones go under the {@link SessionID}.
 */
public final class QfjSettings
{
    /** {@code StartTime} = {@code EndTime} means the session never rolls: always on. */
    private static final String ALWAYS_ON = "00:00:00";

    private QfjSettings()
    {
    }

    /**
     * @param config the configuration.
     * @return the session identity QuickFIX/J will key everything by.
     */
    public static SessionID sessionId(final QfjConfig config)
    {
        return new SessionID(config.version().beginString(), config.senderCompId(), config.targetCompId());
    }

    /**
     * @param config the configuration.
     * @return settings describing exactly one session, ready for {@code SocketInitiator} or
     * {@code SocketAcceptor}.
     */
    public static SessionSettings create(final QfjConfig config)
    {
        final SessionSettings settings = new SessionSettings();
        final SessionID sessionId = sessionId(config);

        settings.setString(SessionFactory.SETTING_CONNECTION_TYPE,
            config.role() == QfjRole.INITIATOR ? SessionFactory.INITIATOR_CONNECTION_TYPE :
                SessionFactory.ACCEPTOR_CONNECTION_TYPE);
        // Everything is in memory: MemoryStoreFactory for sequence numbers, no FileStorePath.
        settings.setString(Session.SETTING_USE_DATA_DICTIONARY, "Y");
        // The counterparty is Artio, whose SendingTime comes from a different clock source. On
        // loopback the difference is microseconds, but a latency check that can fail for reasons
        // unrelated to what is being tested is a flaky test waiting to happen.
        settings.setBool(Session.SETTING_CHECK_LATENCY, false);
        // The demo sends no user-defined (5000+) fields, and rejecting them would turn a harmless
        // extension on the counterparty's side into a session-level reject.
        settings.setBool(Session.SETTING_VALIDATE_USER_DEFINED_FIELDS, false);
        settings.setBool(Session.SETTING_RESET_ON_LOGON, config.resetOnLogon());
        settings.setLong(Session.SETTING_LOGON_TIMEOUT, config.logonTimeoutSec());
        settings.setLong(Session.SETTING_LOGOUT_TIMEOUT, config.logonTimeoutSec());

        settings.setString(sessionId, SessionSettings.BEGINSTRING, config.version().beginString());
        settings.setString(sessionId, SessionSettings.SENDERCOMPID, config.senderCompId());
        settings.setString(sessionId, SessionSettings.TARGETCOMPID, config.targetCompId());
        settings.setString(sessionId, Session.SETTING_START_TIME, ALWAYS_ON);
        settings.setString(sessionId, Session.SETTING_END_TIME, ALWAYS_ON);
        settings.setLong(sessionId, Session.SETTING_HEARTBTINT, config.heartbeatIntervalSec());

        if (config.role() == QfjRole.INITIATOR)
        {
            settings.setString(sessionId, Initiator.SETTING_SOCKET_CONNECT_HOST, config.host());
            settings.setLong(sessionId, Initiator.SETTING_SOCKET_CONNECT_PORT, config.port());
            settings.setLong(sessionId, Initiator.SETTING_RECONNECT_INTERVAL, config.reconnectIntervalSec());
        }
        else
        {
            settings.setString(sessionId, Acceptor.SETTING_SOCKET_ACCEPT_ADDRESS, config.host());
            settings.setLong(sessionId, Acceptor.SETTING_SOCKET_ACCEPT_PORT, config.port());
        }

        return settings;
    }
}
