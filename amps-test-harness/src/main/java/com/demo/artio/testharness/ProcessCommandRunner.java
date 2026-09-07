package com.demo.artio.testharness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The {@link CommandRunner} that really forks: output captured to a temporary
 * file, a hard deadline, and the whole process tree destroyed when the
 * deadline passes.
 *
 * <p>Output goes to a <b>file</b>, not a pipe, and that is the whole point of
 * this class. Reading a pipe with {@code readAllBytes()} blocks until the child
 * closes its end, which it does when it exits - so a {@code waitFor(timeout)}
 * placed after the read can never fire, and a podman machine that has stopped
 * answering hangs the Gradle worker for as long as it stays stopped. With the
 * child writing to a file nobody has to read while it runs, {@code waitFor}
 * really is the deadline, and whatever the child managed to say before it was
 * killed is still on disk for the error message.
 */
final class ProcessCommandRunner implements CommandRunner {

    static final ProcessCommandRunner INSTANCE = new ProcessCommandRunner();

    /**
     * How long a killed process may take to actually disappear before we stop
     * waiting for it. SIGKILL is not negotiable, so this is only ever a
     * scheduling delay.
     */
    private static final Duration REAP_GRACE = Duration.ofSeconds(10);

    private ProcessCommandRunner() {
    }

    @Override
    public Result run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {
        Path capture = Files.createTempFile("artio-amps-cmd-", ".out");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(capture.toFile());
            builder.environment().putAll(environment);
            Process process = builder.start();
            // Nothing here has anything to say to a child. Close its stdin so
            // one that reads it sees end-of-file instead of waiting for us.
            process.getOutputStream().close();

            boolean exited;
            try {
                exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // An interrupted caller is on its way out. A `compose up` left
                // running behind it would race the `down` that follows.
                destroyTree(process);
                throw e;
            }
            if (!exited) {
                List<Long> killed = destroyTree(process);
                throw new TimedOutException(command, timeout, killed, read(capture));
            }
            return new Result(process.exitValue(), read(capture));
        } finally {
            Files.deleteIfExists(capture);
        }
    }

    /**
     * Kills the descendants first and the process itself last, and returns
     * every pid it signalled.
     *
     * <p>Descendants explicitly, because {@code destroyForcibly()} on the
     * child alone leaves whatever it spawned - podman's compose shim execs
     * podman-compose, which runs podman - orphaned and still holding the
     * container engine's lock. Waits, bounded, for them to be gone: the caller
     * is about to report a timeout and must not hang on the cleanup of one.
     */
    private static List<Long> destroyTree(Process process) {
        List<ProcessHandle> handles = new ArrayList<>();
        process.toHandle().descendants().forEach(handles::add);
        handles.add(process.toHandle());

        List<Long> pids = new ArrayList<>(handles.size());
        for (ProcessHandle handle : handles) {
            pids.add(handle.pid());
            handle.destroyForcibly();
        }

        long deadline = System.nanoTime() + REAP_GRACE.toNanos();
        try {
            process.waitFor(REAP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            for (ProcessHandle handle : handles) {
                while (handle.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return pids;
    }

    private static String read(Path capture) throws IOException {
        return new String(Files.readAllBytes(capture), StandardCharsets.UTF_8);
    }

    /**
     * A command outlived its deadline. By the time this is thrown the process
     * tree has been destroyed; {@link #killedPids()} says which pids, and
     * {@link #output()} is whatever it wrote before it died.
     */
    static final class TimedOutException extends IllegalStateException {

        private final List<Long> killedPids;
        private final String output;

        TimedOutException(List<String> command, Duration timeout, List<Long> killedPids,
                          String output) {
            super("command did not finish within " + timeout.toSeconds() + "s and was killed "
                    + "(pids " + killedPids + "): " + String.join(" ", command)
                    + "\noutput before it was killed:\n" + output);
            this.killedPids = List.copyOf(killedPids);
            this.output = output;
        }

        List<Long> killedPids() {
            return killedPids;
        }

        String output() {
            return output;
        }
    }
}
