package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;
import quickfix.Acceptor;
import quickfix.Initiator;
import quickfix.Session;
import quickfix.SessionFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QfjSettingsTest
{
    @Test
    void anInitiatorGetsConnectionTypeInitiatorAndTheRemoteAddressOnItsSession() throws Exception
    {
        final QfjConfig config = QfjConfig.initiator()
            .address("10.1.2.3", 9880)
            .version(QfjVersion.FIX42)
            .senderCompId("QFJ")
            .targetCompId("ARTIO")
            .reconnectIntervalSec(3)
            .build();

        final SessionSettings settings = QfjSettings.create(config);
        final SessionID sessionId = QfjSettings.sessionId(config);

        assertAll(
            () -> assertEquals(SessionFactory.INITIATOR_CONNECTION_TYPE,
                settings.getString(SessionFactory.SETTING_CONNECTION_TYPE)),
            () -> assertEquals("10.1.2.3",
                settings.getString(sessionId, Initiator.SETTING_SOCKET_CONNECT_HOST)),
            () -> assertEquals(9880,
                settings.getInt(sessionId, Initiator.SETTING_SOCKET_CONNECT_PORT)),
            () -> assertEquals(3,
                settings.getInt(sessionId, Initiator.SETTING_RECONNECT_INTERVAL)),
            () -> assertFalse(settings.isSetting(sessionId, Acceptor.SETTING_SOCKET_ACCEPT_PORT)));
    }

    @Test
    void anAcceptorGetsConnectionTypeAcceptorAndTheBindAddressOnItsSession() throws Exception
    {
        final QfjConfig config = QfjConfig.acceptor()
            .address("127.0.0.1", 9881)
            .version(QfjVersion.FIX44)
            .build();

        final SessionSettings settings = QfjSettings.create(config);
        final SessionID sessionId = QfjSettings.sessionId(config);

        assertAll(
            () -> assertEquals(SessionFactory.ACCEPTOR_CONNECTION_TYPE,
                settings.getString(SessionFactory.SETTING_CONNECTION_TYPE)),
            () -> assertEquals("127.0.0.1",
                settings.getString(sessionId, Acceptor.SETTING_SOCKET_ACCEPT_ADDRESS)),
            () -> assertEquals(9881,
                settings.getInt(sessionId, Acceptor.SETTING_SOCKET_ACCEPT_PORT)),
            () -> assertFalse(settings.isSetting(sessionId, Initiator.SETTING_SOCKET_CONNECT_HOST)));
    }

    @Test
    void theSessionIdCarriesTheBeginStringAndBothCompIds()
    {
        final SessionID sessionId = QfjSettings.sessionId(
            QfjConfig.initiator().port(1).version(QfjVersion.FIX44).senderCompId("US").targetCompId("THEM").build());

        assertAll(
            () -> assertEquals("FIX.4.4", sessionId.getBeginString()),
            () -> assertEquals("US", sessionId.getSenderCompID()),
            () -> assertEquals("THEM", sessionId.getTargetCompID()));
    }

    @Test
    void theSessionIsAlwaysOnBecauseStartTimeAndEndTimeAreEqual() throws Exception
    {
        final QfjConfig config = QfjConfig.acceptor().port(9881).build();
        final SessionSettings settings = QfjSettings.create(config);
        final SessionID sessionId = QfjSettings.sessionId(config);

        assertEquals(settings.getString(sessionId, Session.SETTING_START_TIME),
            settings.getString(sessionId, Session.SETTING_END_TIME));
    }

    @Test
    void latencyCheckingIsOffAndUserDefinedFieldValidationIsOffButTheDictionaryIsStillUsed()
        throws Exception
    {
        final SessionSettings settings = QfjSettings.create(QfjConfig.acceptor().port(9881).build());

        assertAll(
            () -> assertFalse(settings.getBool(Session.SETTING_CHECK_LATENCY)),
            () -> assertFalse(settings.getBool(Session.SETTING_VALIDATE_USER_DEFINED_FIELDS)),
            () -> assertTrue(settings.getBool(Session.SETTING_USE_DATA_DICTIONARY)));
    }

    @Test
    void heartbeatIntervalAndResetOnLogonComeFromTheConfiguration() throws Exception
    {
        final QfjConfig config = QfjConfig.initiator()
            .port(9880).heartbeatIntervalSec(42).resetOnLogon(false).build();
        final SessionSettings settings = QfjSettings.create(config);

        assertAll(
            () -> assertEquals(42,
                settings.getInt(QfjSettings.sessionId(config), Session.SETTING_HEARTBTINT)),
            () -> assertFalse(settings.getBool(Session.SETTING_RESET_ON_LOGON)));
    }

    @Test
    void exactlyOneSessionIsDescribed()
    {
        final SessionSettings settings = QfjSettings.create(QfjConfig.acceptor().port(9881).build());

        assertEquals(1, settings.size());
    }

    @Test
    void aConfigWithTheWrongRoleIsRejectedByTheEngineThatCannotUseIt()
    {
        final QfjConfig acceptorConfig = QfjConfig.acceptor().port(9881).build();
        final QfjConfig initiatorConfig = QfjConfig.initiator().port(9880).build();

        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new QfjInitiator(acceptorConfig)).getMessage().contains("INITIATOR config")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new QfjAcceptor(initiatorConfig)).getMessage().contains("ACCEPTOR config")));
    }
}
