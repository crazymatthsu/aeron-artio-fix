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
 *
 * <p>The peer here is a <em>venue</em>, not a drop copy consumer: it answers orders with execution
 * reports of its own. So the tests that are about those answers send
 * {@link OrderScenario#ORDERS_ONLY} - the five order events - and the ones that are about the drop
 * copy stream itself send all fifteen. Sending all fifteen is also the only place the reports this
 * module builds are validated by another engine's data dictionary: QuickFIX/J is configured with
 * {@code UseDataDictionary=Y}, so a report missing a required field would come back as a
 * session-level {@code Reject(35=3)} rather than being quietly accepted.
 */
class QfjToQfjIT
{
    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(20);

    /** 3 new orders x (ack + fill) + 1 replaced + 1 canceled. An inbound 35=8 is answered by nobody. */
    private static final int EXPECTED_EXECUTION_REPORTS = 8;

    @ParameterizedTest(name = "{0}")
    @EnumSource(QfjVersion.class)
    void theAcceptorReceivesEveryDropCopyMessageInOrderWithTheIdentifiersTheScenarioNames(
        final QfjVersion version) throws Exception
    {
        final int port = FreePort.next();
        final OrderScenario scenario = OrderScenario.DEFAULT;

        try (QfjAcceptor acceptor = new QfjAcceptor(acceptorConfig(version, port));
            QfjInitiator initiator = new QfjInitiator(initiatorConfig(version, port)))
        {
            acceptor.start();
            initiator.start();
            assertTrue(initiator.awaitLogon(LOGON_TIMEOUT), "initiator did not log on");
            assertTrue(acceptor.awaitLogon(LOGON_TIMEOUT), "acceptor did not see a logon");

            final List<String> clOrdIds = initiator.run(scenario);

            assertTrue(acceptor.awaitReceived(Tags.MSG_TYPE_NEW_ORDER_SINGLE, 3, MESSAGE_TIMEOUT),
                () -> "acceptor saw " + acceptor.receivedMessages());
            assertTrue(acceptor.awaitReceived(Tags.MSG_TYPE_EXECUTION_REPORT,
                OrderScenario.EXECUTION_REPORT_COUNT, MESSAGE_TIMEOUT),
                () -> "acceptor saw " + acceptor.receivedMessages());
            assertTrue(acceptor.awaitReceived(Tags.MSG_TYPE_CANCEL_REPLACE, 1, MESSAGE_TIMEOUT));
            assertTrue(acceptor.awaitReceived(Tags.MSG_TYPE_CANCEL, 1, MESSAGE_TIMEOUT));

            final List<CapturedMessage> applicationMessages = acceptor.receivedMessages().stream()
                .filter(message -> !message.admin())
                .toList();
            // Inbound only, so these are the ten the initiator sent, not the eight the acceptor
            // answered the order events with.
            final List<CapturedMessage> reports = applicationMessages.stream()
                .filter(message -> message.msgType().equals(Tags.MSG_TYPE_EXECUTION_REPORT))
                .toList();

            assertAll(
                () -> assertEquals(OrderScenario.MESSAGE_COUNT, applicationMessages.size()),
                () -> assertEquals(scenario.msgTypes(),
                    applicationMessages.stream().map(CapturedMessage::msgType).toList()),
                () -> assertEquals(scenario.steps().stream().map(OrderScenario.Step::clOrdId).toList(),
                    applicationMessages.stream().map(m -> m.field(Tags.CL_ORD_ID)).toList()),
                () -> assertEquals(clOrdIds, scenario.clOrdIds(),
                    "run() reports the order events, which the reports then repeat"),
                () -> assertEquals(scenario.execIds(),
                    reports.stream().map(m -> m.field(Tags.EXEC_ID)).toList()),
                () -> assertEquals(
                    List.of("ORDER-1", "ORDER-1", "ORDER-2", "ORDER-2", "ORDER-3",
                        "ORDER-3", "ORDER-3", "ORDER-1", "ORDER-1", "ORDER-2"),
                    reports.stream().map(m -> m.field(Tags.ORDER_ID)).toList(),
                    "an amended or cancelled order still reports against the OrderID it was given"),
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

            // The order events alone: this is about what the venue answers, and an inbound 35=8 is
            // answered by nobody, so sending the reports too would only add noise to the count.
            initiator.run(OrderScenario.ORDERS_ONLY);

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
            // The whole drop copy stream, reports included: with UseDataDictionary=Y the acceptor
            // validates every one of them, so this is where a 35=8 missing a required field on
            // either version would show up - as a Reject, not as a silent pass.
            initiator.run(OrderScenario.DEFAULT);
            assertTrue(
                initiator.awaitReceived(Tags.MSG_TYPE_EXECUTION_REPORT, EXPECTED_EXECUTION_REPORTS, MESSAGE_TIMEOUT));
            assertTrue(acceptor.awaitReceived(
                Tags.MSG_TYPE_EXECUTION_REPORT, OrderScenario.EXECUTION_REPORT_COUNT, MESSAGE_TIMEOUT));

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
