package com.demo.artio.testharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The runner that really forks, against real children.
 *
 * <p>The timeout test is the one that matters (review finding H1): the
 * previous implementation read the child's output with {@code readAllBytes()}
 * before calling {@code waitFor(timeout)}, so the timeout could never fire and
 * a stalled podman machine hung the Gradle worker for good. A {@code sleep 30}
 * under a one-second deadline must therefore throw in about a second, and the
 * sleep must be gone afterwards.
 */
class ProcessCommandRunnerTest {

    private static final CommandRunner runner = ProcessCommandRunner.INSTANCE;

    /** Far above the 1 s deadline, far below the 30 s the child would take. */
    private static final Duration PROMPTLY = Duration.ofSeconds(8);

    @Test
    void aCommandThatOutlivesItsTimeoutIsKilledPromptlyAndReportedWithWhatItSaid() {
        // Not `exec sleep`: the sleep is a grandchild on purpose, because
        // destroying only the direct child leaves whatever it spawned behind.
        List<String> command = List.of("sh", "-c", "echo partial-output; sleep 30");
        long started = System.nanoTime();

        ProcessCommandRunner.TimedOutException timedOut = assertThrows(
                ProcessCommandRunner.TimedOutException.class,
                () -> runner.run(command, Map.of(), Duration.ofSeconds(1)));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(PROMPTLY) < 0,
                "the timeout must be the deadline, not the child's own exit: took " + elapsed);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) >= 0,
                "the child must be given its full timeout before it is killed: took " + elapsed);
        assertTrue(timedOut.getMessage().contains("partial-output"),
                "what the child said before it was killed must be in the message: "
                        + timedOut.getMessage());
        assertTrue(timedOut.getMessage().contains("sleep 30"),
                "the command must be in the message: " + timedOut.getMessage());
        assertEquals(timedOut.output().strip(), "partial-output");
        assertFalse(timedOut.killedPids().isEmpty(), "the shell and the sleep were both to be killed");
        for (long pid : timedOut.killedPids()) {
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "pid " + pid + " is still alive after the timeout");
        }
    }

    @Test
    void aFinishedCommandReportsItsExitStatusAndItsMergedOutput() throws Exception {
        CommandRunner.Result result = runner.run(
                List.of("sh", "-c", "echo to-stdout; echo to-stderr >&2; exit 3"),
                Map.of(), Duration.ofSeconds(10));

        assertEquals(3, result.exitCode());
        assertFalse(result.succeeded());
        assertTrue(result.output().contains("to-stdout"), result.output());
        assertTrue(result.output().contains("to-stderr"),
                "stderr is merged into the output, not dropped: " + result.output());
    }

    @Test
    void aSuccessfulCommandSucceeds() throws Exception {
        CommandRunner.Result result = runner.run(List.of("true"), Map.of(), Duration.ofSeconds(10));

        assertTrue(result.succeeded());
        assertEquals("", result.output());
    }

    @Test
    void theEnvironmentIsAddedToTheChilds() throws Exception {
        CommandRunner.Result result = runner.run(
                List.of("sh", "-c", "printf '%s' \"$AMPS_RUNNER_TEST\""),
                Map.of("AMPS_RUNNER_TEST", "compose-sees-this"), Duration.ofSeconds(10));

        assertEquals("compose-sees-this", result.output());
    }

    @Test
    void aCommandThatCannotBeStartedThrowsRatherThanReportingAnExitStatus() {
        assertThrows(IOException.class, () -> runner.run(
                List.of("no-such-binary-artio-amps-runner-test"), Map.of(), Duration.ofSeconds(10)));
    }

    @Test
    void stdinIsClosedSoAChildThatReadsItFinishesInsteadOfWaitingForUs() throws Exception {
        long started = System.nanoTime();

        CommandRunner.Result result = runner.run(List.of("cat"), Map.of(), Duration.ofSeconds(20));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(result.succeeded(), "cat on a closed stdin exits 0, got " + result);
        assertTrue(elapsed.compareTo(PROMPTLY) < 0,
                "cat must see end-of-file at once, not wait for the timeout: took " + elapsed);
    }

    @Test
    void anInterruptedWaitPropagatesPromptlyInsteadOfWaitingOutTheChild() {
        long started = System.nanoTime();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> runner.run(
                    List.of("sleep", "30"), Map.of(), Duration.ofSeconds(30)));
        } finally {
            Thread.interrupted();
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(PROMPTLY) < 0, "took " + elapsed);
    }
}
