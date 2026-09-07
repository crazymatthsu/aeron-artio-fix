package com.demo.artio.testharness;

import static com.demo.artio.testharness.ScriptedCommandRunner.containing;
import static com.demo.artio.testharness.ScriptedCommandRunner.exactly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Which engine the harness talks to, and how it decides whether the image is
 * there - both against a scripted container engine, so the answers do not
 * depend on what this machine has installed.
 *
 * <p>Review findings H4 (the engine must be the one the chosen compose drives),
 * H5 ({@code podman image exists} does not exist on docker) and H6 (a podman
 * that cannot answer is not a missing image).
 */
class AmpsComposeServerEngineTest {

    private static final String IMAGE = "localhost/amps-demo:test";

    private static UnaryOperator<String> environment(Map<String, String> values) {
        return values::get;
    }

    /** podman is installed but its compose shim has no provider; docker compose works. */
    private static ScriptedCommandRunner podmanWithoutComposeButDockerWithIt() {
        return new ScriptedCommandRunner()
                .when(exactly("which", "podman"), 0, "/usr/bin/podman\n")
                .when(containing("podman", "compose", "version"), 125,
                        "Error: looking up compose provider failed\n")
                .when(exactly("which", "podman-compose"), 1, "")
                .when(exactly("which", "docker"), 0, "/usr/bin/docker\n")
                .when(containing("docker", "compose", "version"), 0,
                        "Docker Compose version v2.29.0\n");
    }

    // ------------------------------------------------------------------- H4

    @Test
    void fallingBackToDockerComposeMakesDockerTheEngine() {
        AmpsComposeServer.Compose compose = AmpsComposeServer
                .composeCommand("podman", podmanWithoutComposeButDockerWithIt())
                .orElseThrow();

        assertEquals(List.of("docker", "compose"), compose.command());
        assertEquals("docker", compose.engine(),
                "logs/ps/restart must go to the engine that owns the container, not to "
                        + "CONTAINER_ENGINE");
    }

    @Test
    void aStandalonePodmanComposeDrivesPodman() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .when(exactly("which", "podman"), 0, "/usr/bin/podman\n")
                .when(containing("podman", "compose", "version"), 125, "no provider\n")
                .when(exactly("which", "podman-compose"), 0, "/usr/local/bin/podman-compose\n");

        AmpsComposeServer.Compose compose =
                AmpsComposeServer.composeCommand("podman", runner).orElseThrow();

        assertEquals(List.of("podman-compose"), compose.command());
        assertEquals("podman", compose.engine());
    }

    @Test
    void theConfiguredEngineWithAWorkingComposeIsUsedAsIs() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .when(exactly("which", "docker"), 0, "/usr/bin/docker\n")
                .when(containing("docker", "compose", "version"), 0, "v2\n");

        AmpsComposeServer.Compose compose =
                AmpsComposeServer.composeCommand("docker", runner).orElseThrow();

        assertEquals(List.of("docker", "compose"), compose.command());
        assertEquals("docker", compose.engine());
    }

    @Test
    void noComposeAnywhereIsEmpty() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .when(exactly("which", "podman"), 0, "/usr/bin/podman\n")
                .when(containing("podman", "compose", "version"), 125, "no provider\n");

        assertTrue(AmpsComposeServer.composeCommand("podman", runner).isEmpty());
    }

    @Test
    void theImageIsLookedUpOnTheEngineTheChosenComposeDrives() {
        // CONTAINER_ENGINE is podman (the default), but the compose that will
        // start the container is docker's - so an image only podman has is no
        // use, and the question must be put to docker.
        ScriptedCommandRunner runner = podmanWithoutComposeButDockerWithIt()
                .when(containing("docker", "image", "inspect"), 0, "sha256:abc\n")
                .when(containing("podman", "image", "inspect"), 125,
                        "Cannot connect to Podman\n");

        Optional<String> reason = AmpsComposeServer.unavailableReason(
                environment(Map.of("AMPS_IMAGE", IMAGE)), runner);

        assertTrue(reason.isEmpty(), reason.orElse(""));
        List<String> inspect = runner.firstCall(containing("image", "inspect"));
        assertEquals("docker", inspect.get(0), "asked of the wrong engine: " + inspect);
        assertEquals(-1, runner.indexOf(containing("podman", "image")),
                "podman must not be asked about an image docker will run: " + runner.calls());
    }

    // ---------------------------------------------------------------- H5/H6

    @Test
    void theImageCheckIsImageInspectWhichDockerAlsoHas() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .when(containing("image", "inspect"), 0, "sha256:abc\n");

        Optional<String> reason = AmpsComposeServer.imageUnavailableReason("docker", IMAGE, runner);

        assertTrue(reason.isEmpty(), reason.orElse(""));
        List<String> call = runner.calls().get(0);
        assertEquals(List.of("docker", "image", "inspect"), call.subList(0, 3),
                "`image exists` is podman-only; with CONTAINER_ENGINE=docker it skipped "
                        + "every suite on a machine that had the image: " + call);
        assertEquals(IMAGE, call.get(call.size() - 1));
    }

    @Test
    void podmanImageNotKnownMeansTheImageIsAbsent() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .when(containing("image", "inspect"), 125,
                        "Error: failed to find image " + IMAGE + ": " + IMAGE
                                + ": image not known\n");

        String reason = AmpsComposeServer.imageUnavailableReason("podman", IMAGE, runner)
                .orElseThrow();

        assertTrue(reason.contains("is not present"), reason);
        assertTrue(reason.contains("Containerfile"), "the fix is to build one: " + reason);
    }

    @Test
    void dockerNoSuchImageMeansTheImageIsAbsent() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .when(containing("image", "inspect"), 1,
                        "[]\nError: No such image: " + IMAGE + "\n");

        String reason = AmpsComposeServer.imageUnavailableReason("docker", IMAGE, runner)
                .orElseThrow();

        assertTrue(reason.contains("is not present"), reason);
    }

    @Test
    void aStoppedPodmanMachineIsReportedVerbatimAndNotAsAMissingImage() {
        // Same exit status as "image not known" - only the message differs.
        String engineSaid = "Cannot connect to Podman. Please verify your connection to the "
                + "Linux system using `podman system connection list`, or try `podman machine "
                + "init` and `podman machine start` to manage a new Linux VM";
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .when(containing("image", "inspect"), 125, engineSaid + "\n");

        String reason = AmpsComposeServer.imageUnavailableReason("podman", IMAGE, runner)
                .orElseThrow();

        assertTrue(reason.contains(engineSaid),
                "the engine's own words say what to do; they must survive: " + reason);
        assertTrue(reason.contains("exited 125"), reason);
        assertFalse(reason.contains("is not present"),
                "\"build the image\" is the wrong advice for a stopped VM: " + reason);
        assertFalse(reason.contains("Containerfile"), reason);
    }

    @Test
    void anEngineThatCannotBeRunAtAllIsACheckFailureNotAMissingImage() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .whenThrow(containing("image", "inspect"),
                        () -> new IOException("Cannot run program \"podman\""));

        String reason = AmpsComposeServer.imageUnavailableReason("podman", IMAGE, runner)
                .orElseThrow();

        assertTrue(reason.contains("could not check"), reason);
        assertTrue(reason.contains("Cannot run program"), reason);
        assertFalse(reason.contains("is not present"), reason);
    }

    @Test
    void anInterruptedImageCheckKeepsTheInterruptAndDoesNotClaimAbsence() {
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .whenThrow(containing("image", "inspect"), InterruptedException::new);

        try {
            String reason = AmpsComposeServer.imageUnavailableReason("podman", IMAGE, runner)
                    .orElseThrow();

            assertTrue(reason.contains("interrupted"), reason);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must be restored");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void onlyNotFoundMessagesReadAsAbsent() {
        assertTrue(AmpsComposeServer.isImageNotFound("Error: x: image not known"));
        assertTrue(AmpsComposeServer.isImageNotFound("Error: No such image: x"));
        assertTrue(AmpsComposeServer.isImageNotFound("Error: failed to find image x"));
        assertFalse(AmpsComposeServer.isImageNotFound("Cannot connect to Podman"));
        assertFalse(AmpsComposeServer.isImageNotFound("Error: unable to connect to Podman socket"));
        assertFalse(AmpsComposeServer.isImageNotFound(""));
    }
}
