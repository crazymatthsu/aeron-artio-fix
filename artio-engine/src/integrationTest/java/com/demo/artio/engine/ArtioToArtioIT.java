package com.demo.artio.engine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two {@link ArtioRuntime}s in one JVM, one acceptor and one initiator, talking to each other over
 * loopback: an order goes one way and an execution report comes back.
 *
 * <p>This is the test that pins the "unique directory per runtime" design. Two Artio runtimes need
 * two media drivers, two Aeron directories, two archives and two Artio log directories; every
 * fixed name or fixed port in the Aeron and archive contexts would collide here, which is why the
 * archive's remote control channel is disabled and its client talks over IPC inside its own Aeron
 * directory.
 *
 * <p>It is also where the reply path is exercised from the acceptor side: the acceptor's sink calls
 * {@link ArtioRuntime#send} from inside the callback, which enqueues onto the poll thread's own
 * command queue rather than touching the {@code Session} directly.
 */
class ArtioToArtioIT
{
    private static final String ACCEPTOR_COMP_ID = "ARTIO-ACC";
    private static final String INITIATOR_COMP_ID = "ARTIO-INI";

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void anOrderCrossesFromTheInitiatorAndAnExecutionReportComesBack(final FixVersion version)
        throws Exception
    {
        final int port = ItSupport.freePort();
        final CountingSink acceptorSink = new CountingSink();
        final CountingSink initiatorSink = new CountingSink();
        final RecordingSessionListener acceptorEvents = new RecordingSessionListener();
        final RecordingSessionListener initiatorEvents = new RecordingSessionListener();

        // The acceptor answers every application message it receives. send() is called from the
        // sink callback, i.e. from the poll thread itself; it enqueues rather than sending inline,
        // which is exactly what a bridge would do.
        final ArtioRuntime[] acceptorHolder = new ArtioRuntime[1];
        final FixMessageSink replyingSink = message ->
        {
            acceptorSink.onMessage(message);
            if (!message.isAdmin() && "D".equals(message.msgTypeAsString()))
            {
                // toFixString() allocates; a real sink would decode with a generated decoder over
                // view.asciiBuffer(). This is a test, and the point being made is the send path.
                final String clOrdId = FixMessages.field(message.toFixString(), 11);
                acceptorHolder[0].send(
                    ItSupport.executionReport(version, "ORDER-1", "EXEC-1", clOrdId, "MSFT", 100));
            }
        };

        try (ArtioRuntime acceptor = new ArtioRuntime(acceptorConfig(version, port), replyingSink, acceptorEvents))
        {
            acceptorHolder[0] = acceptor;
            acceptor.start();

            try (ArtioRuntime initiator = ArtioRuntime.launch(
                initiatorConfig(version, port), initiatorSink, initiatorEvents))
            {
                initiator.sendAndAwait(
                    ItSupport.newOrderSingle(version, "CROSS-1", "MSFT", 100, 10125, 2),
                    Duration.ofSeconds(10));

                await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> acceptorSink.countOfType("D") == 1);
                await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> initiatorSink.countOfType("8") == 1);

                final CountingSink.Captured order = acceptorSink.messagesOfType("D").get(0);
                final CountingSink.Captured report = initiatorSink.messagesOfType("8").get(0);

                assertAll(
                    () -> assertEquals("CROSS-1", order.field(11)),
                    () -> assertEquals("MSFT", order.field(55)),
                    () -> assertEquals(version.beginString(), order.field(8)),
                    () -> assertEquals(INITIATOR_COMP_ID, order.field(49)),
                    () -> assertEquals(ACCEPTOR_COMP_ID, order.field(56)),
                    () -> assertEquals("ORDER-1", report.field(37)),
                    () -> assertEquals("EXEC-1", report.field(17)),
                    () -> assertEquals("CROSS-1", report.field(11)),
                    () -> assertEquals(version.beginString(), report.field(8)),
                    () -> assertEquals(ACCEPTOR_COMP_ID, report.field(49)),
                    () -> assertEquals(INITIATOR_COMP_ID, report.field(56)),
                    () -> assertEquals(List.of(), acceptorEvents.errors()),
                    () -> assertEquals(List.of(), initiatorEvents.errors()));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixVersion.class)
    void theTwoRuntimesGetSeparateAeronAndArtioDirectoriesAndBothAreRemovedOnClose(final FixVersion version)
    {
        final int port = ItSupport.freePort();
        final Path acceptorDirectory;
        final Path initiatorDirectory;

        try (ArtioRuntime acceptor = ArtioRuntime.launch(
            acceptorConfig(version, port), new CountingSink(), null))
        {
            try (ArtioRuntime initiator = ArtioRuntime.launch(
                initiatorConfig(version, port), new CountingSink(), null))
            {
                acceptorDirectory = acceptor.directory();
                initiatorDirectory = initiator.directory();

                assertAll(
                    () -> assertNotEquals(acceptorDirectory, initiatorDirectory),
                    () -> assertNotEquals(acceptor.runtimeId(), initiator.runtimeId()),
                    () -> assertTrue(acceptorDirectory.toFile().isDirectory(), acceptorDirectory.toString()),
                    () -> assertTrue(initiatorDirectory.toFile().isDirectory(), initiatorDirectory.toString()),
                    () -> assertTrue(acceptorDirectory.resolve("aeron").toFile().isDirectory()),
                    () -> assertTrue(acceptorDirectory.resolve("archive").toFile().isDirectory()),
                    () -> assertTrue(acceptorDirectory.resolve("logs").toFile().isDirectory()));
            }
            await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> !initiatorDirectory.toFile().exists());
        }
        await().atMost(ItSupport.MESSAGE_TIMEOUT).until(() -> !acceptorDirectory.toFile().exists());
    }

    private static FixEngineConfig acceptorConfig(final FixVersion version, final int port)
    {
        return FixEngineConfig.acceptor()
            .name("a2a-acc-" + version.name())
            .address("localhost", port)
            .senderCompId(ACCEPTOR_COMP_ID)
            .targetCompId(INITIATOR_COMP_ID)
            .fixVersion(version)
            .heartbeatIntervalSec(5)
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }

    private static FixEngineConfig initiatorConfig(final FixVersion version, final int port)
    {
        return FixEngineConfig.initiator()
            .name("a2a-ini-" + version.name())
            .address("localhost", port)
            .senderCompId(INITIATOR_COMP_ID)
            .targetCompId(ACCEPTOR_COMP_ID)
            .fixVersion(version)
            .heartbeatIntervalSec(5)
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }
}
