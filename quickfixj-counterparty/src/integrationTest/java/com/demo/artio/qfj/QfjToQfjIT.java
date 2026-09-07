package com.demo.artio.qfj;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import quickfix.Message;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counterparty on its own: a QuickFIX/J initiator against a QuickFIX/J acceptor over loopback,
 * running the whole order scenario.
 *
 * <p>Artio is deliberately absent. If this suite passes and an Artio pairing in
 * {@code :artio-engine:integrationTest} fails, the fault is on the Artio side; without this test
 * the two possibilities would be indistinguishable.
 */
class QfjToQfjIT
{
    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(20);

    /** 3 new orders x (ack + fill) + 1 replaced + 1 canceled. */
    private static final int EXPECTED_EXECUTION_REPORTS = 8;

    @ParameterizedTest(name = "{0}")
    @EnumSource(QfjVersion.class)
    void theAcceptorReceivesEveryScenarioMessageWithTheClOrdIdsTheScenarioNames(final QfjVersion version)
        throws Exception
    {
        final int port = FreePort.next();

        try (QfjAcceptor acceptor = new QfjAcceptor(acceptorConfig(version, port));
            QfjInitiator initiator = new QfjInitiator(initiatorConfig(version, port)))
        {
            acceptor.start();
            initiator.start();
            assertTrue(initiator.awaitLogon(LOGON_TIMEOUT), "initiator did not log on");
            assertTrue(acceptor.awaitLogon(LOGON_TIMEOUT), "acceptor did not see a logon");

            final List<String> clOrdIds = initiator.run(OrderScenario.DEFAULT);

            assertTrue(acceptor.awaitReceived(Tags.MSG_TYPE_NEW_ORDER_SINGLE, 3, MESSAGE_TIMEOUT),
                () -> "acceptor saw " + acceptor.receivedMessages());
            assertTrue(acceptor.awaitReceived(Tags.MSG_TYPE_CANCEL_REPLACE, 1, MESSAGE_TIMEOUT));
            assertTrue(acceptor.awaitReceived(Tags.MSG_TYPE_CANCEL, 1, MESSAGE_TIMEOUT));

            final List<CapturedMessage> applicationMessages = acceptor.receivedMessages().stream()
                .filter(message -> !message.admin())
                .toList();

            assertAll(
                () -> assertEquals(OrderScenario.MESSAGE_COUNT, applicationMessages.size()),
                () -> assertEquals(clOrdIds,
                    applicationMessages.stream().map(m -> m.field(Tags.CL_ORD_ID)).toList()),
                () -> assertEquals(List.of("D", "D", "D", "G", "F"),
                    applicationMessages.stream().map(CapturedMessage::msgType).toList()),
                () -> assertEquals(version.beginString(),
                    applicationMessages.get(0).field(Tags.BEGIN_STRING)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(QfjVersion.class)
    void theInitiatorGetsAnAcknowledgementAndAFillForEveryOrderAndAReportForTheReplaceAndCancel(
        final QfjVersion version) throws Exception
    {
        final int port = FreePort.next();

        try (QfjAcceptor acceptor = new QfjAcceptor(acceptorConfig(version, port));
            QfjInitiator initiator = new QfjInitiator(initiatorConfig(version, port)))
        {
            acceptor.start();
            initiator.start();
            assertTrue(initiator.awaitLogon(LOGON_TIMEOUT), "initiator did not log on");

            initiator.run(OrderScenario.DEFAULT);

            assertTrue(
                initiator.awaitReceived(Tags.MSG_TYPE_EXECUTION_REPORT, EXPECTED_EXECUTION_REPORTS, MESSAGE_TIMEOUT),
                () -> "initiator saw " + initiator.receivedMessages(Tags.MSG_TYPE_EXECUTION_REPORT));

            final List<CapturedMessage> reports =
                initiator.receivedMessages(Tags.MSG_TYPE_EXECUTION_REPORT);

            assertAll(
                () -> assertEquals(EXPECTED_EXECUTION_REPORTS, reports.size()),
                // Two reports per new order: an acknowledgement then a fill.
                () -> assertEquals(List.of("0", String.valueOf(version.fillExecType())),
                    reports.subList(0, 2).stream().map(m -> m.field(Tags.EXEC_TYPE)).toList()),
                () -> assertEquals("5", reports.get(6).field(Tags.EXEC_TYPE)),
                () -> assertEquals("4", reports.get(7).field(Tags.EXEC_TYPE)),
                // The replace reports against the OrderID the original order was given.
                () -> assertEquals(reports.get(0).field(Tags.ORDER_ID), reports.get(6).field(Tags.ORDER_ID)),
                () -> assertEquals("ORD-4", reports.get(6).field(Tags.CL_ORD_ID)),
                () -> assertEquals("ORD-1", reports.get(6).field(Tags.ORIG_CL_ORD_ID)),
                () -> assertEquals("ORD-5", reports.get(7).field(Tags.CL_ORD_ID)),
                () -> assertEquals("ORD-2", reports.get(7).field(Tags.ORIG_CL_ORD_ID)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(QfjVersion.class)
    void neitherSideRejectsAnythingTheOtherSendsSoNoRejectOrBusinessRejectAppears(final QfjVersion version)
        throws Exception
    {
        final int port = FreePort.next();

        try (QfjAcceptor acceptor = new QfjAcceptor(acceptorConfig(version, port));
            QfjInitiator initiator = new QfjInitiator(initiatorConfig(version, port)))
        {
            acceptor.start();
            initiator.start();
            assertTrue(initiator.awaitLogon(LOGON_TIMEOUT), "initiator did not log on");
            initiator.run(OrderScenario.DEFAULT);
            assertTrue(
                initiator.awaitReceived(Tags.MSG_TYPE_EXECUTION_REPORT, EXPECTED_EXECUTION_REPORTS, MESSAGE_TIMEOUT));

            // 35=3 is a session-level Reject, 35=j a BusinessMessageReject, 35=9 an OrderCancelReject.
            assertAll(
                () -> assertEquals(List.of(), rejects(initiator.receivedMessages())),
                () -> assertEquals(List.of(), rejects(acceptor.receivedMessages())));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(QfjVersion.class)
    void closingTheInitiatorLogsTheSessionOutAtTheAcceptor(final QfjVersion version) throws Exception
    {
        final int port = FreePort.next();

        try (QfjAcceptor acceptor = new QfjAcceptor(acceptorConfig(version, port)))
        {
            acceptor.start();
            try (QfjInitiator initiator = new QfjInitiator(initiatorConfig(version, port)))
            {
                initiator.start();
                assertTrue(initiator.awaitLogon(LOGON_TIMEOUT), "initiator did not log on");
                assertTrue(acceptor.awaitLogon(LOGON_TIMEOUT), "acceptor did not see a logon");
            }

            assertTrue(acceptor.awaitLogout(MESSAGE_TIMEOUT), "acceptor never saw the session go away");
            assertAll(
                () -> assertEquals(false, acceptor.isLoggedOn()),
                () -> assertTrue(acceptor.logoutCount() >= 1));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(QfjVersion.class)
    void anOrderWithoutOrderQtyIsAnsweredWithABusinessMessageRejectNotIgnored(final QfjVersion version)
        throws Exception
    {
        final int port = FreePort.next();

        try (QfjAcceptor acceptor = new QfjAcceptor(acceptorConfig(version, port));
            QfjInitiator initiator = new QfjInitiator(initiatorConfig(version, port)))
        {
            acceptor.start();
            initiator.start();
            assertTrue(initiator.awaitLogon(LOGON_TIMEOUT), "initiator did not log on");

            final Message order = Orders.newOrderSingle(version, "ORD-NOQTY", "MSFT", Orders.SIDE_BUY, 100, 101.25);
            order.removeField(Tags.ORDER_QTY);
            assertTrue(initiator.send(order));

            assertTrue(initiator.awaitReceived(Tags.MSG_TYPE_BUSINESS_REJECT, 1, MESSAGE_TIMEOUT),
                () -> "initiator saw " + initiator.receivedMessages());
            final CapturedMessage reject = initiator.receivedMessages(Tags.MSG_TYPE_BUSINESS_REJECT).get(0);
            assertAll(
                () -> assertEquals("D", reject.field(Tags.REF_MSG_TYPE)),
                () -> assertEquals("ORD-NOQTY", reject.field(Tags.BUSINESS_REJECT_REF_ID)),
                () -> assertEquals(String.valueOf(ExecutionReports.CONDITIONALLY_REQUIRED_FIELD_MISSING),
                    reject.field(Tags.BUSINESS_REJECT_REASON)),
                () -> assertEquals(List.of(), initiator.receivedMessages(Tags.MSG_TYPE_EXECUTION_REPORT),
                    "no execution report for an order with no quantity"),
                // The venue's reject is an application message; neither session layer rejected anything.
                () -> assertEquals(List.of(), rejects(acceptor.receivedMessages())));
        }
    }

    private static List<String> rejects(final List<CapturedMessage> messages)
    {
        return messages.stream()
            .map(CapturedMessage::msgType)
            .filter(msgType -> msgType.equals("3") || msgType.equals("j") || msgType.equals("9"))
            .toList();
    }

    private static QfjConfig acceptorConfig(final QfjVersion version, final int port)
    {
        return QfjConfig.acceptor()
            .version(version)
            .address("localhost", port)
            .senderCompId("VENUE")
            .targetCompId("TRADER")
            .heartbeatIntervalSec(5)
            .build();
    }

    private static QfjConfig initiatorConfig(final QfjVersion version, final int port)
    {
        return QfjConfig.initiator()
            .version(version)
            .address("localhost", port)
            .senderCompId("TRADER")
            .targetCompId("VENUE")
            .heartbeatIntervalSec(5)
            .build();
    }
}
