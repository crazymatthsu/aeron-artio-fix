package com.demo.artio.testharness;

import static com.demo.artio.testharness.ScriptedCommandRunner.containing;
import static com.demo.artio.testharness.ScriptedCommandRunner.exactly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What {@link AmpsComposeServer#start} leaves behind when it fails: nothing.
 *
 * <p>Review finding H3. A failed {@code up -d} has usually done half its work
 * (the compose network exists, the container may), so {@code down} has to run
 * even though {@code up} did not succeed; and an interrupt while waiting for
 * readiness must tear down too, not escape with the container running. All
 * against a scripted engine: no container is started here.
 */
class AmpsComposeServerStartFailureTest {

    private static final UnaryOperator<String> NO_ENVIRONMENT = name -> null;

    /** A box with podman 5 and a working compose provider. */
    private static ScriptedCommandRunner podmanWithCompose() {
        return new ScriptedCommandRunner()
                .when(exactly("which", "podman"), 0, "/usr/bin/podman\n")
                .when(containing("podman", "compose", "version"), 0, "podman-compose 1.3.0\n");
    }

    @Test
    void aFailedUpStillRunsDownSoTheHalfCreatedProjectIsNotLeaked() {
        ScriptedCommandRunner runner = podmanWithCompose()
                .when(containing("up", "-d"), 125,
                        "Error: creating container storage: the container name is in use\n")
                .when(containing("down", "-v"), 0, "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AmpsComposeServer.start("artio-fix", NO_ENVIRONMENT, runner));

        assertTrue(failure.getMessage().contains("could not start the AMPS container"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("container name is in use"),
                "the engine's output must be in the failure: " + failure.getMessage());
        assertDownRanAfterUp(runner);
        assertDataDirectoryIsGone(runner);
    }

    @Test
    void aContainerThatExitsDuringStartupIsReportedWithItsLogAndTornDown() {
        ScriptedCommandRunner runner = podmanWithCompose()
                .when(containing("up", "-d"), 0, "")
                .when(containing("podman", "logs"), 0,
                        "info: starting\nfatal: 01-0002 licence file not found\n")
                // Some other container is running; ours is not.
                .when(containing("podman", "ps"), 0, "unrelated-container\n")
                .when(containing("down", "-v"), 0, "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AmpsComposeServer.start("artio-fix", NO_ENVIRONMENT, runner));

        assertTrue(failure.getMessage().contains("exited during startup"), failure.getMessage());
        assertTrue(failure.getMessage().contains("licence file not found"),
                "the server log must be in the failure: " + failure.getMessage());
        assertDownRanAfterUp(runner);
        assertDataDirectoryIsGone(runner);
    }

    @Test
    void anInterruptWhileWaitingForReadinessTearsDownAndKeepsTheInterrupt() {
        ScriptedCommandRunner runner = podmanWithCompose()
                .when(containing("up", "-d"), 0, "")
                .when(containing("podman", "logs"), 0, "info: starting\n")
                // A cancelled Gradle worker interrupts the waiting thread.
                .whenThrow(containing("podman", "ps"), InterruptedException::new)
                .when(containing("down", "-v"), 0, "");

        try {
            assertThrows(InterruptedException.class,
                    () -> AmpsComposeServer.start("artio-fix", NO_ENVIRONMENT, runner));

            assertTrue(Thread.currentThread().isInterrupted(),
                    "the interrupt must be put back once teardown is done");
            assertDownRanAfterUp(runner);
            assertDataDirectoryIsGone(runner);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailedDownFallsBackToRemovingTheContainerByName() {
        ScriptedCommandRunner runner = podmanWithCompose()
                .when(containing("up", "-d"), 1, "Error: no such network\n")
                .when(containing("down", "-v"), 1, "Error: compose bookkeeping is gone\n")
                .when(containing("rm", "-f"), 0, "");

        assertThrows(IllegalStateException.class,
                () -> AmpsComposeServer.start("artio-fix", NO_ENVIRONMENT, runner));

        List<String> rm = runner.firstCall(containing("rm", "-f"));
        assertEquals("podman", rm.get(0), rm.toString());
        assertEquals(project(runner), rm.get(rm.size() - 1),
                "the container is named after the project: " + rm);
    }

    @Test
    void anUnknownFlowFailsBeforeAnythingIsRun() {
        ScriptedCommandRunner runner = podmanWithCompose();

        assertThrows(IllegalArgumentException.class,
                () -> AmpsComposeServer.start("no-such-flow", NO_ENVIRONMENT, runner));

        assertEquals(List.of(), runner.calls());
    }

    // ---------------------------------------------------------------- helpers

    private static void assertDownRanAfterUp(ScriptedCommandRunner runner) {
        int up = runner.indexOf(containing("up", "-d"));
        int down = runner.indexOf(containing("down", "-v"));
        assertTrue(up >= 0, "up -d was never run: " + runner.calls());
        assertTrue(down > up, "down -v must run after the failed up: " + runner.calls());

        List<String> upCommand = runner.calls().get(up);
        List<String> downCommand = runner.calls().get(down);
        assertEquals(upCommand.subList(0, upCommand.size() - 2),
                downCommand.subList(0, downCommand.size() - 2),
                "down must address the same compose project and file as up");
    }

    private static void assertDataDirectoryIsGone(ScriptedCommandRunner runner) {
        Path dataDir = AmpsComposeServer.repositoryRoot()
                .resolve("build/amps-it").resolve(project(runner));
        assertFalse(Files.exists(dataDir), "data directory left behind: " + dataDir);
    }

    /** The compose project name, read back from the {@code -p} argument that was run. */
    private static String project(ScriptedCommandRunner runner) {
        List<String> up = runner.firstCall(containing("up", "-d"));
        int at = up.indexOf("-p");
        assertTrue(at >= 0 && at + 1 < up.size(), "no -p in " + up);
        return up.get(at + 1);
    }
}
