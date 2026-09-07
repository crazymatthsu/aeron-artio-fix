package com.demo.artio.testharness;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs one external command to completion and reports its exit status and its
 * combined output.
 *
 * <p>The seam between {@link AmpsComposeServer} and the operating system.
 * {@link ProcessCommandRunner} is the one that really forks; the unit tests
 * inject a scripted one, so the skip rules, the engine choice and the
 * start-failure teardown can all be exercised on a machine with no podman, and
 * without starting anything on one that has it.
 */
@FunctionalInterface
interface CommandRunner {

    /**
     * Runs {@code command} with {@code environment} added to the child's
     * environment and waits at most {@code timeout} for it to finish.
     *
     * <p>A non-zero exit is an ordinary {@link Result}, not an exception: half
     * the callers ask questions ("is this image here?") whose answer is the
     * exit status. A command that outlives the timeout is another matter, and
     * the real runner kills it and throws.
     *
     * @throws IOException          if the command cannot be started at all
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting; the real runner kills the child first
     */
    Result run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException;

    /** What a finished command said and how it ended: stdout and stderr merged. */
    record Result(int exitCode, String output) {

        boolean succeeded() {
            return exitCode == 0;
        }
    }
}
