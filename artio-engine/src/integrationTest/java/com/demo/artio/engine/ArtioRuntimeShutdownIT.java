package com.demo.artio.engine;

import com.demo.artio.qfj.QfjAcceptor;
import com.demo.artio.qfj.QfjConfig;
import com.demo.artio.qfj.QfjInitiator;
import com.demo.artio.qfj.QfjVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ArtioRuntime#close()} is required to do: log the counterparty out rather than drop
 * the socket, leave no agent thread behind, remove its directories, and be safe to call twice or on
 * a runtime that never started.
 *
 * <p>The thread assertion is the one that catches real bugs. Artio and Aeron between them start
 * seven or eight threads per runtime - shared media driver, archive conductor, Aeron client
 * conductor, framer, indexer, monitoring, error printer, and this module's library poller - and a
 * single one left running turns a test suite into a slow leak and a production restart into a
 * second engine fighting the first for the same Aeron directory.
 */
class ArtioRuntimeShutdownIT
{
    private static final String ARTIO_COMP_ID = "ARTIO";
    private static final String QFJ_COMP_ID = "QFJ";
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);

    @Test
    void closingAnArtioAcceptorLogsTheQuickfixjInitiatorOut() throws Exception
    {
        final int port = ItSupport.freePort();

        try (QfjInitiator qfj = new QfjInitiator(initiatorConfig(port)))
        {
            try (ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(port), new CountingSink(), null))
            {
                qfj.start();
                assertTrue(qfj.awaitLogon(ItSupport.STARTUP_TIMEOUT), "QuickFIX/J did not log on");
                await().atMost(ItSupport.STARTUP_TIMEOUT).until(() -> !runtime.sessions().isEmpty());
            }

            assertTrue(qfj.awaitLogout(SHUTDOWN_TIMEOUT),
                () -> "QuickFIX/J never saw the session end; it saw " + qfj.receivedMessages());
            assertAll(
                () -> assertFalse(qfj.isLoggedOn()),
                () -> assertTrue(qfj.logoutCount() >= 1),
                // close() asks the session to log out before pulling the plug, so the counterparty
                // gets a 35=5 rather than a bare TCP reset.
                () -> assertTrue(qfj.receivedMessages().stream().anyMatch(m -> m.msgType().equals("5")),
                    () -> "expected a Logout, saw " + qfj.receivedMessages()));
        }
    }

    @Test
    void closingAnArtioInitiatorLogsTheQuickfixjAcceptorOut() throws Exception
    {
        final int port = ItSupport.freePort();

        try (QfjAcceptor venue = new QfjAcceptor(venueConfig(port)))
        {
            venue.start();
            try (ArtioRuntime runtime = ArtioRuntime.launch(
                initiatorConfig(port, FixVersion.FIX44), new CountingSink(), null))
            {
                assertTrue(venue.awaitLogon(ItSupport.STARTUP_TIMEOUT), "the venue did not see a logon");
                assertEquals(1, runtime.sessions().size());
            }

            assertTrue(venue.awaitLogout(SHUTDOWN_TIMEOUT), "the venue never saw the session end");
            assertAll(
                () -> assertFalse(venue.isLoggedOn()),
                () -> assertTrue(venue.receivedMessages().stream().anyMatch(m -> m.msgType().equals("5")),
                    () -> "expected a Logout, saw " + venue.receivedMessages()));
        }
    }

    @Test
    void everyArtioAndAeronThreadTheRuntimeStartedIsGoneAfterCloseReturns()
    {
        final Set<String> before = ItSupport.liveThreadNames();
        final int port = ItSupport.freePort();

        final ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(port), new CountingSink(), null);
        final String runtimeId = runtime.runtimeId();

        final Set<String> started = ItSupport.leakedThreadNames(before);
        assertAll(
            // If this fails the scan is looking for the wrong thing and the assertion below would
            // pass vacuously.
            () -> assertTrue(started.size() >= 4,
                () -> "expected several Artio/Aeron threads, saw " + started),
            () -> assertTrue(started.stream().anyMatch(name -> name.contains(runtimeId)),
                () -> "expected Artio's threads to carry the runtime id, saw " + started));

        runtime.close();

        // close() joins the poll thread and closes the driver, but Aeron's own agent threads finish
        // asynchronously, so give them a moment rather than asserting on the instant close returns.
        await().atMost(SHUTDOWN_TIMEOUT).until(() -> ItSupport.leakedThreadNames(before).isEmpty());
        assertEquals(Set.of(), ItSupport.liveThreadNames().stream()
            .filter(name -> name.contains(runtimeId))
            .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void closeRemovesTheAeronArchiveAndArtioLogDirectories()
    {
        final int port = ItSupport.freePort();
        final ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(port), new CountingSink(), null);
        final Path directory = runtime.directory();
        assertTrue(directory.toFile().isDirectory(), directory.toString());

        runtime.close();

        assertAll(
            () -> assertFalse(directory.toFile().exists(), directory.toString()),
            () -> assertFalse(runtime.isRunning()));
    }

    @Test
    void closeIsIdempotentAndARuntimeThatWasNeverStartedClosesWithoutError()
    {
        final int port = ItSupport.freePort();
        final ArtioRuntime neverStarted = new ArtioRuntime(acceptorConfig(port), new CountingSink());
        neverStarted.close();
        neverStarted.close();

        final ArtioRuntime started = ArtioRuntime.launch(acceptorConfig(port), new CountingSink(), null);
        started.close();
        started.close();

        assertAll(
            () -> assertFalse(neverStarted.isRunning()),
            () -> assertFalse(started.isRunning()),
            () -> assertFalse(started.directory().toFile().exists()));
    }

    @Test
    void startingTwiceIsRejectedRatherThanQuietlyLaunchingASecondMediaDriver()
    {
        final int port = ItSupport.freePort();

        try (ArtioRuntime runtime = ArtioRuntime.launch(acceptorConfig(port), new CountingSink(), null))
        {
            assertTrue(assertThrows(IllegalStateException.class, runtime::start)
                .getMessage().contains("has already been started"));
        }
    }

    @Test
    void anInitiatorThatFindsNothingListeningFailsToStartAndLeavesNoThreadsOrDirectoryBehind()
    {
        final Set<String> before = ItSupport.liveThreadNames();
        // Nothing is bound here: the port was free a moment ago and no engine was started on it.
        final int port = ItSupport.freePort();
        final FixEngineConfig config = FixEngineConfig.initiator()
            .name("nolistener")
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(FixVersion.FIX42)
            .logonTimeoutMs(4_000)
            .replyTimeoutMs(3_000)
            .baseDirectory(ItSupport.baseDirectory())
            .build();

        final ArtioRuntime runtime = new ArtioRuntime(config, new CountingSink());

        assertThrows(IllegalStateException.class, runtime::start);

        // A start that failed part way through still has to clean up after itself.
        await().atMost(SHUTDOWN_TIMEOUT).until(() -> ItSupport.leakedThreadNames(before).isEmpty());
        assertAll(
            () -> assertFalse(runtime.directory().toFile().exists(), runtime.directory().toString()),
            () -> assertFalse(runtime.isRunning()));
    }

    private static FixEngineConfig acceptorConfig(final int port)
    {
        return FixEngineConfig.acceptor()
            .name("shutdown-acc")
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(FixVersion.FIX42)
            .heartbeatIntervalSec(5)
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }

    private static FixEngineConfig initiatorConfig(final int port, final FixVersion version)
    {
        return FixEngineConfig.initiator()
            .name("shutdown-ini")
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(version)
            .heartbeatIntervalSec(5)
            .baseDirectory(ItSupport.baseDirectory())
            .build();
    }

    private static QfjConfig initiatorConfig(final int port)
    {
        return QfjConfig.initiator()
            .version(QfjVersion.FIX42)
            .address("localhost", port)
            .senderCompId(QFJ_COMP_ID)
            .targetCompId(ARTIO_COMP_ID)
            .heartbeatIntervalSec(5)
            .build();
    }

    private static QfjConfig venueConfig(final int port)
    {
        return QfjConfig.acceptor()
            .version(QfjVersion.FIX44)
            .address("localhost", port)
            .senderCompId(QFJ_COMP_ID)
            .targetCompId(ARTIO_COMP_ID)
            .heartbeatIntervalSec(5)
            .build();
    }
}
