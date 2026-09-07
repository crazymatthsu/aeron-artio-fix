package com.demo.artio.engine;

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixEngineConfigTest
{
    @Test
    void acceptorBuilderDefaultsToLocalhostFix42BackoffAndDeleteOnClose()
    {
        final FixEngineConfig config = FixEngineConfig.acceptor().port(9880).build();

        assertAll(
            () -> assertEquals(EngineMode.ACCEPTOR, config.mode()),
            () -> assertEquals("localhost", config.host()),
            () -> assertEquals(9880, config.port()),
            () -> assertEquals(FixVersion.FIX42, config.fixVersion()),
            () -> assertEquals(IdleStrategyType.BACKOFF, config.idleStrategy()),
            () -> assertEquals(10, config.heartbeatIntervalSec()),
            () -> assertEquals(FixEngineConfig.AUTO_LIBRARY_ID, config.libraryId()),
            () -> assertTrue(config.deleteDirectoriesOnClose()),
            () -> assertTrue(config.resetSeqNumsOnLogon()),
            () -> assertNotNull(config.authenticationStrategy()),
            () -> assertEquals(Path.of(System.getProperty("java.io.tmpdir")), config.baseDirectory()));
    }

    @Test
    void unsetAeronAndLogDirectoriesStayNullSoTheRuntimeCanDeriveUniqueOnes()
    {
        final FixEngineConfig config = FixEngineConfig.initiator().port(9880).build();

        assertAll(
            () -> assertNull(config.aeronDirectory()),
            () -> assertNull(config.logFileDir()));
    }

    @Test
    void everyBuilderSetterIsCarriedIntoTheRecord()
    {
        final AuthenticationStrategy strategy = logon -> true;
        final FixEngineConfig config = FixEngineConfig.initiator()
            .name("bridge")
            .address("10.0.0.1", 4321)
            .senderCompId("US")
            .targetCompId("THEM")
            .fixVersion(FixVersion.FIX44)
            .heartbeatIntervalSec(30)
            .baseDirectory(Path.of("/tmp/base"))
            .aeronDirectory(Path.of("/tmp/aeron"))
            .logFileDir(Path.of("/tmp/logs"))
            .resetSeqNumsOnLogon(false)
            .libraryId(7)
            .idleStrategy(IdleStrategyType.BUSY_SPIN)
            .logonTimeoutMs(1234)
            .replyTimeoutMs(2345)
            .shutdownTimeoutMs(3456)
            .deleteDirectoriesOnClose(false)
            .authenticationStrategy(strategy)
            .build();

        assertAll(
            () -> assertEquals("bridge", config.name()),
            () -> assertEquals(EngineMode.INITIATOR, config.mode()),
            () -> assertEquals("10.0.0.1", config.host()),
            () -> assertEquals(4321, config.port()),
            () -> assertEquals("US", config.senderCompId()),
            () -> assertEquals("THEM", config.targetCompId()),
            () -> assertEquals(FixVersion.FIX44, config.fixVersion()),
            () -> assertEquals(30, config.heartbeatIntervalSec()),
            () -> assertEquals(Path.of("/tmp/base"), config.baseDirectory()),
            () -> assertEquals(Path.of("/tmp/aeron"), config.aeronDirectory()),
            () -> assertEquals(Path.of("/tmp/logs"), config.logFileDir()),
            () -> assertEquals(false, config.resetSeqNumsOnLogon()),
            () -> assertEquals(7, config.libraryId()),
            () -> assertEquals(IdleStrategyType.BUSY_SPIN, config.idleStrategy()),
            () -> assertEquals(1234, config.logonTimeoutMs()),
            () -> assertEquals(2345, config.replyTimeoutMs()),
            () -> assertEquals(3456, config.shutdownTimeoutMs()),
            () -> assertEquals(false, config.deleteDirectoriesOnClose()),
            () -> assertSame(strategy, config.authenticationStrategy()));
    }

    @Test
    void aPortOutsideOneToSixtyFiveThousandFiveHundredAndThirtyFiveIsRejected()
    {
        assertAll(
            () -> assertEquals("port must be in 1..65535 but was 0",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(0).build()).getMessage()),
            () -> assertEquals("port must be in 1..65535 but was 65536",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(65536).build()).getMessage()));
    }

    @Test
    void blankNameHostOrCompIdIsRejectedByName()
    {
        assertAll(
            () -> assertEquals("name must not be blank",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).name(" ").build()).getMessage()),
            () -> assertEquals("host must not be blank",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).host("").build()).getMessage()),
            () -> assertEquals("senderCompId must not be blank",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).senderCompId("").build()).getMessage()),
            () -> assertEquals("targetCompId must not be blank",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).targetCompId(null).build()).getMessage()));
    }

    @Test
    void aCompIdContainingSohOrEqualsIsRejectedBecauseItWouldCorruptTheHeader()
    {
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FixEngineConfig.acceptor().port(1).senderCompId("A\001B").build())
                .getMessage().contains("must not contain SOH")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FixEngineConfig.acceptor().port(1).targetCompId("A=B").build())
                .getMessage().contains("must not contain SOH")));
    }

    @Test
    void identicalSenderAndTargetCompIdsAreRejectedBecauseTheSessionWouldTalkToItself()
    {
        assertEquals("senderCompId and targetCompId must differ, both were 'SAME'",
            assertThrows(IllegalArgumentException.class,
                () -> FixEngineConfig.acceptor().port(1).senderCompId("SAME").targetCompId("SAME").build())
                .getMessage());
    }

    @Test
    void nonPositiveIntervalsAndTimeoutsAreRejected()
    {
        assertAll(
            () -> assertEquals("heartbeatIntervalSec must be at least 1 but was 0",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).heartbeatIntervalSec(0).build()).getMessage()),
            () -> assertEquals("logonTimeoutMs must be positive but was 0",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).logonTimeoutMs(0).build()).getMessage()),
            () -> assertEquals("replyTimeoutMs must be positive but was -1",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).replyTimeoutMs(-1).build()).getMessage()),
            () -> assertEquals("shutdownTimeoutMs must be positive but was 0",
                assertThrows(IllegalArgumentException.class,
                    () -> FixEngineConfig.acceptor().port(1).shutdownTimeoutMs(0).build()).getMessage()));
    }

    @Test
    void aLibraryIdThatArtioWouldRejectIsRejectedHereInstead()
    {
        assertEquals("libraryId must be AUTO_LIBRARY_ID or positive but was -3",
            assertThrows(IllegalArgumentException.class,
                () -> FixEngineConfig.acceptor().port(1).libraryId(-3).build()).getMessage());
    }
}
