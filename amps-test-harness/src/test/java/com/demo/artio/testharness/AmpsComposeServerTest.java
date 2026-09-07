package com.demo.artio.testharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The parts of {@link AmpsComposeServer} that must work without a container:
 * the skip rules and the readiness marker counting.
 *
 * <p>The skip rules are tested against an injected environment rather than the
 * real one, because a JVM cannot set its own environment variables and because
 * this test has to give the same answer on a machine with podman and on one
 * without.
 */
class AmpsComposeServerTest {

    private static UnaryOperator<String> environment(Map<String, String> values) {
        return values::get;
    }

    @Test
    void ampsItFalseIsAReasonToSkip() {
        Optional<String> reason =
                AmpsComposeServer.unavailableReason(environment(Map.of("AMPS_IT", "false")));

        assertTrue(reason.isPresent(), "AMPS_IT=false must switch the suite off");
        assertTrue(reason.get().contains("AMPS_IT=false"),
                "the reason is shown as the skip message, so it must name the switch: "
                        + reason.get());
    }

    @Test
    void ampsItFalseIsCheckedBeforeAnythingThatShellsOut() {
        // The switch has to work on a machine with no podman at all, so it must
        // be the first thing tested. If podman were probed first this would
        // report the missing binary instead.
        Optional<String> reason = AmpsComposeServer.unavailableReason(environment(Map.of(
                "AMPS_IT", "false",
                "CONTAINER_ENGINE", "no-such-engine-binary")));

        assertTrue(reason.orElseThrow().contains("AMPS_IT=false"));
    }

    @Test
    void ampsItIsCaseInsensitiveBecauseItIsTypedByHand() {
        assertTrue(AmpsComposeServer.unavailableReason(environment(Map.of("AMPS_IT", "FALSE")))
                .isPresent());
    }

    @Test
    void ampsItTrueDoesNotByItselfMakeAmpsAvailable() {
        // AMPS_IT only ever switches the suite OFF. Setting it to true must not
        // be able to force a run on a machine with no container engine, which
        // would turn a clean skip into a confusing failure.
        Optional<String> reason = AmpsComposeServer.unavailableReason(environment(Map.of(
                "AMPS_IT", "true",
                "CONTAINER_ENGINE", "no-such-engine-binary")));

        assertTrue(reason.orElseThrow().contains("no-such-engine-binary"));
    }

    @Test
    void aMissingContainerEngineIsReportedWithTheNameThatWasTried() {
        Optional<String> reason = AmpsComposeServer.unavailableReason(
                environment(Map.of("CONTAINER_ENGINE", "no-such-engine-binary")));

        assertTrue(reason.orElseThrow().contains("no-such-engine-binary"),
                "the reason must name the binary so the fix is obvious: " + reason.get());
    }

    @Test
    void readyMarkerCountsEachAnnouncementSoARestartCanWaitForANewOne() {
        String log = "starting\n"
                + "info: " + AmpsComposeServer.READY_MARKER + " (1 seconds).\n"
                + "some traffic\n"
                + "info: " + AmpsComposeServer.READY_MARKER + " (2 seconds).\n";

        assertEquals(2, AmpsComposeServer.readyMarkerCount(log));
    }

    @Test
    void readyMarkerIncludesTheMessageCodeSoTheConfigEchoCannotMatchIt() {
        // AMPS echoes the whole config file, comments included, into the log
        // before it starts. A comment that mentions the English phrase must not
        // be mistaken for the server announcing readiness.
        String configEcho = "      the test harness detects initialization completed here\n";

        assertEquals(0, AmpsComposeServer.readyMarkerCount(configEcho));
        assertFalse(AmpsComposeServer.READY_MARKER.equals("initialization completed"));
    }

    @Test
    void repositoryRootIsTheDirectoryHoldingSettingsGradleKts() {
        Path root = AmpsComposeServer.repositoryRoot();

        assertTrue(Files.isRegularFile(root.resolve("settings.gradle.kts")), root.toString());
        assertTrue(Files.isRegularFile(root.resolve("amps-server/docker-compose.yml")),
                "the harness resolves the compose file from here: " + root);
    }
}
