package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgsTest
{
    private static String[] words(final String line)
    {
        return line.split(" ");
    }

    @Test
    void theDocumentedInitiatorCommandLineParsesIntoTheDocumentedConfiguration()
    {
        final CliArgs args = CliArgs.parse(words(
            "initiator --host localhost --port 9880 --version FIX.4.2 --sender QFJ --target ARTIO " +
                "--scenario orders"));

        assertAll(
            () -> assertEquals(QfjRole.INITIATOR, args.role()),
            () -> assertEquals("localhost", args.host()),
            () -> assertEquals(9880, args.port()),
            () -> assertEquals(QfjVersion.FIX42, args.version()),
            () -> assertEquals("QFJ", args.senderCompId()),
            () -> assertEquals("ARTIO", args.targetCompId()),
            () -> assertTrue(args.runScenario()),
            () -> assertEquals(CliArgs.Scenario.DROP_COPY, args.scenario(),
                "'orders' is the whole drop copy stream; the spelling in the README still works"),
            () -> assertFalse(args.screenLog()));
    }

    @Test
    void theThreeScenarioSpellingsSelectTheThreeStreams()
    {
        assertAll(
            () -> assertEquals(CliArgs.Scenario.DROP_COPY,
                CliArgs.parse(words("initiator --port 1 --scenario orders")).scenario()),
            () -> assertEquals(CliArgs.Scenario.DROP_COPY,
                CliArgs.parse(words("initiator --port 1 --scenario drop-copy")).scenario(),
                "a synonym of 'orders' that says what the stream is"),
            () -> assertEquals(CliArgs.Scenario.ORDERS_ONLY,
                CliArgs.parse(words("initiator --port 1 --scenario orders-only")).scenario()),
            () -> assertEquals(CliArgs.Scenario.NONE,
                CliArgs.parse(words("initiator --port 1 --scenario none")).scenario()),
            () -> assertFalse(CliArgs.parse(words("initiator --port 1 --scenario none")).runScenario()),
            () -> assertTrue(CliArgs.parse(words("initiator --port 1 --scenario DROP-COPY")).runScenario(),
                "the name is case insensitive"));
    }

    @Test
    void eachScenarioNamesTheStepsItSends()
    {
        assertAll(
            () -> assertEquals(List.of(), CliArgs.Scenario.NONE.steps()),
            () -> assertEquals(OrderScenario.MESSAGE_COUNT, CliArgs.Scenario.DROP_COPY.steps().size()),
            () -> assertEquals(OrderScenario.DEFAULT.steps(), CliArgs.Scenario.DROP_COPY.steps()),
            () -> assertEquals(OrderScenario.ORDERS_ONLY, CliArgs.Scenario.ORDERS_ONLY.steps()),
            () -> assertEquals(OrderScenario.ORDER_COUNT, CliArgs.Scenario.ORDERS_ONLY.steps().size()));
    }

    @Test
    void theDocumentedAcceptorCommandLineParsesIntoTheDocumentedConfiguration()
    {
        final CliArgs args = CliArgs.parse(words(
            "acceptor --port 9881 --version FIX.4.4 --sender QFJ --target ARTIO"));

        assertAll(
            () -> assertEquals(QfjRole.ACCEPTOR, args.role()),
            () -> assertEquals(9881, args.port()),
            () -> assertEquals(QfjVersion.FIX44, args.version()),
            () -> assertFalse(args.runScenario()));
    }

    @Test
    void defaultsAreLocalhostFix42QfjToArtioNoScenarioAndATenSecondHeartbeat()
    {
        final CliArgs args = CliArgs.parse(words("acceptor --port 1234"));

        assertAll(
            () -> assertEquals("localhost", args.host()),
            () -> assertEquals(QfjVersion.FIX42, args.version()),
            () -> assertEquals("QFJ", args.senderCompId()),
            () -> assertEquals("ARTIO", args.targetCompId()),
            () -> assertFalse(args.runScenario()),
            () -> assertEquals(10, args.heartbeatIntervalSec()));
    }

    @Test
    void screenLogIsAFlagWithNoValueAndHeartbeatIsANumber()
    {
        final CliArgs args = CliArgs.parse(words("initiator --port 1 --screen-log --heartbeat 30"));

        assertAll(
            () -> assertTrue(args.screenLog()),
            () -> assertEquals(30, args.heartbeatIntervalSec()));
    }

    @Test
    void theParsedArgumentsTurnIntoAConfigurationOfTheMatchingRole()
    {
        final QfjConfig config = CliArgs.parse(words(
            "initiator --host 10.0.0.1 --port 4321 --version FIX.4.4 --sender US --target THEM " +
                "--heartbeat 15")).toConfig();

        assertAll(
            () -> assertEquals(QfjRole.INITIATOR, config.role()),
            () -> assertEquals("10.0.0.1", config.host()),
            () -> assertEquals(4321, config.port()),
            () -> assertEquals(QfjVersion.FIX44, config.version()),
            () -> assertEquals("US", config.senderCompId()),
            () -> assertEquals("THEM", config.targetCompId()),
            () -> assertEquals(15, config.heartbeatIntervalSec()));
    }

    @Test
    void aMissingRoleOrAnUnknownOneIsRejectedNamingTheTwoThatWork()
    {
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[0])).getMessage().contains("expected 'initiator' or 'acceptor'")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(words("bridge --port 1"))).getMessage().contains("'bridge'")));
    }

    @Test
    void aMissingPortIsRejectedBecauseThereIsNoSensibleDefault()
    {
        assertEquals("--port is required",
            assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(words("acceptor --version FIX.4.2"))).getMessage());
    }

    @Test
    void aNegativePortIsPresentButOutOfRangeSoTheConfigurationRejectsItAsSuch()
    {
        final CliArgs args = CliArgs.parse(words("acceptor --port -5"));

        assertEquals(-5, args.port(), "parsing keeps the value; the range check is QfjConfig's");
        final String message = assertThrows(IllegalArgumentException.class, args::toConfig).getMessage();
        assertAll(
            () -> assertTrue(message.contains("1..65535"), message),
            () -> assertTrue(message.contains("-5"), message),
            () -> assertFalse(message.contains("required"), message));
    }

    @Test
    void anUnknownOptionIsNamedRatherThanIgnored()
    {
        assertEquals("Unknown option '--nope'",
            assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(words("acceptor --port 1 --nope"))).getMessage());
    }

    @Test
    void anOptionWithNoValueOrANonNumericPortIsRejected()
    {
        assertAll(
            () -> assertEquals("--host needs a value",
                assertThrows(IllegalArgumentException.class,
                    () -> CliArgs.parse(words("acceptor --port 1 --host"))).getMessage()),
            () -> assertEquals("--port needs a number but got 'nine'",
                assertThrows(IllegalArgumentException.class,
                    () -> CliArgs.parse(words("acceptor --port nine"))).getMessage()));
    }

    @Test
    void anUnknownScenarioIsRejectedNamingTheOnesThatExist()
    {
        final String message = assertThrows(IllegalArgumentException.class,
            () -> CliArgs.parse(words("initiator --port 1 --scenario chaos"))).getMessage();

        assertAll(
            () -> assertTrue(message.contains("chaos"), message),
            () -> assertTrue(message.contains("orders"), message),
            () -> assertTrue(message.contains("drop-copy"), message),
            () -> assertTrue(message.contains("orders-only"), message),
            () -> assertTrue(message.contains("none"), message));
    }

    @Test
    void anUnsupportedFixVersionIsRejected()
    {
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> CliArgs.parse(words("acceptor --port 1 --version FIX.5.0")))
            .getMessage().contains("FIX.5.0"));
    }

    @Test
    void helpIsRecognisedInEitherFormAndAnywhereOnTheLine()
    {
        assertAll(
            () -> assertTrue(CliArgs.isHelp(words("--help"))),
            () -> assertTrue(CliArgs.isHelp(words("acceptor --port 1 -h"))),
            () -> assertFalse(CliArgs.isHelp(words("acceptor --port 1"))),
            () -> assertFalse(CliArgs.isHelp(null)));
    }

    @Test
    void theUsageTextNamesEveryOptionTheParserAccepts()
    {
        assertAll(
            () -> assertTrue(CliArgs.USAGE.contains("--host")),
            () -> assertTrue(CliArgs.USAGE.contains("--port")),
            () -> assertTrue(CliArgs.USAGE.contains("--version")),
            () -> assertTrue(CliArgs.USAGE.contains("--sender")),
            () -> assertTrue(CliArgs.USAGE.contains("--target")),
            () -> assertTrue(CliArgs.USAGE.contains("--scenario")),
            () -> assertTrue(CliArgs.USAGE.contains("--heartbeat")),
            () -> assertTrue(CliArgs.USAGE.contains("--screen-log")));
    }
}
