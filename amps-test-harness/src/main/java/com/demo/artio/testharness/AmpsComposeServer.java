package com.demo.artio.testharness;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A throwaway AMPS instance for one integration suite, started with
 * {@code podman compose} from {@code amps-server/docker-compose.yml}.
 *
 * <p>Not Testcontainers, deliberately. podman on macOS exposes a
 * Docker-compatible socket only if you go and enable it, and this repository
 * already describes its AMPS instance as a compose file that a developer runs
 * by hand. Driving the same file as a subprocess means the container a test
 * starts and the container {@code amps-server/scripts/amps.sh start} starts
 * are the same container, from the same YAML and the same flow config - so a
 * test cannot pass against a configuration nobody runs.
 *
 * <p>Every instance is isolated from every other one and from anything you
 * started by hand:
 *
 * <ul>
 *   <li>three free host ports, chosen at start, never a fixed number;</li>
 *   <li>a compose project name and container name of
 *       {@code artio-amps-it-<port>}, so two suites in the same Gradle run do
 *       not collide and a leaked container says which suite left it;</li>
 *   <li>an empty data directory under {@code <repo>/build/amps-it/<project>/},
 *       deleted on {@link #close()} and disposed of by {@code gradle clean}.</li>
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * AmpsAssumptions.assumeAvailable();
 * try (AmpsComposeServer amps = AmpsComposeServer.start("artio-fix")) {
 *     Client client = new Client("my-test");
 *     client.connect(amps.uri());
 *     client.logon(5000);
 *     ...
 * }
 * }</pre>
 *
 * <p>Every external command goes through a {@link CommandRunner}, so nothing
 * here can block on a pipe or outlive its timeout (see
 * {@link ProcessCommandRunner}), and the unit tests can script the container
 * engine's answers instead of needing one.
 *
 * @see AmpsAssumptions
 * @see SowReader
 */
public final class AmpsComposeServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AmpsComposeServer.class);

    /** The image built by the sibling amps-demo project, and this repository's default. */
    public static final String DEFAULT_IMAGE = "localhost/amps-demo:5.3.5.135";

    /**
     * The server's own startup-complete line, INCLUDING its numeric message
     * code.
     *
     * <p>The code is load-bearing. AMPS echoes the whole config file into its
     * log before it starts - comments and all - so matching the bare English
     * phrase also matches any config comment that mentions it, roughly a
     * second before the server is listening. On 5.3.5.135 that echo is about
     * 500 of the first 1600 log lines.
     *
     * <p>An open port is not readiness either: the engine's port forwarder
     * accepts connections as soon as the container exists, so a client that
     * races it connects and is then dropped mid-logon with "Socket closed".
     */
    static final String READY_MARKER = "00-0015 AMPS initialization completed";

    /** Generous because the image is amd64 and an Apple Silicon host emulates it. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    /** For {@code up}, {@code down} and {@code restart}, which may wait on an emulated VM. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(3);

    /**
     * For the questions asked in a loop or before a skip decision: {@code logs},
     * {@code ps}, {@code which}, {@code compose version}, {@code image inspect}.
     * An engine that takes longer than this to answer any of them is not going
     * to recover by being waited on.
     */
    private static final Duration QUERY_TIMEOUT = Duration.ofMinutes(1);

    private final String flow;
    private final String project;
    private final int port;
    private final int websocketPort;
    private final int adminPort;
    private final Path dataDir;
    private final Path composeFile;
    private final List<String> compose;
    private final String engine;
    private final Map<String, String> environment;
    private final CommandRunner runner;
    private boolean up;

    private AmpsComposeServer(String flow, String project, int port, int websocketPort,
                              int adminPort, Path dataDir, Path composeFile,
                              List<String> compose, String engine,
                              Map<String, String> environment, CommandRunner runner) {
        this.flow = flow;
        this.project = project;
        this.port = port;
        this.websocketPort = websocketPort;
        this.adminPort = adminPort;
        this.dataDir = dataDir;
        this.composeFile = composeFile;
        this.compose = List.copyOf(compose);
        this.engine = engine;
        this.environment = Map.copyOf(environment);
        this.runner = runner;
    }

    // ------------------------------------------------------------------ skip

    /**
     * Why an AMPS-backed test cannot run here, or empty when it can.
     *
     * <p>Returned rather than thrown so a suite can turn it into a skip with a
     * readable reason instead of a failure. There is no public AMPS server
     * image - it has to be built from a licensed release tarball - so
     * "no image" is an ordinary state of a developer machine, not a broken
     * one, and {@code ./gradlew build} has to stay green on one.
     *
     * <p>A green build is therefore not by itself proof these ran. See the
     * comment on the {@code integrationTest} task in this module's
     * {@code build.gradle.kts} about why every variable read here is also a
     * declared task input.
     */
    public static Optional<String> unavailableReason() {
        return unavailableReason(System::getenv, ProcessCommandRunner.INSTANCE);
    }

    /** As {@link #unavailableReason()}, against an injected environment, for tests. */
    static Optional<String> unavailableReason(UnaryOperator<String> env) {
        return unavailableReason(env, ProcessCommandRunner.INSTANCE);
    }

    /**
     * As {@link #unavailableReason()}, against an injected environment and an
     * injected command runner, for tests that must not shell out.
     */
    static Optional<String> unavailableReason(UnaryOperator<String> env, CommandRunner runner) {
        String flag = env.apply("AMPS_IT");
        if (flag != null && flag.equalsIgnoreCase("false")) {
            return Optional.of("AMPS_IT=false: AMPS integration tests are switched off "
                    + "for this run.");
        }
        String engine = engine(env);
        if (!commandExists(engine, runner)) {
            return Optional.of("'" + engine + "' is not on PATH. Install podman, or set "
                    + "CONTAINER_ENGINE to the binary you use.");
        }
        Optional<Compose> compose = composeCommand(engine, runner);
        if (compose.isEmpty()) {
            return Optional.of("no compose implementation found: '" + engine + " compose "
                    + "version' failed and neither podman-compose nor 'docker compose' is "
                    + "usable. podman 5 delegates 'podman compose' to an external provider "
                    + "(podman-compose); install one.");
        }
        // Asked of the engine the chosen compose will drive, which is not
        // always the configured one (see composeCommand): an image that only
        // podman has is no use to a container docker starts.
        return imageUnavailableReason(compose.get().engine(), image(env), runner);
    }

    /**
     * Why {@code image} cannot be used with {@code engine}, or empty when it
     * is present.
     *
     * <p>{@code <engine> image inspect}, which podman and docker both have.
     * {@code podman image exists} does not exist on docker, so with
     * {@code CONTAINER_ENGINE=docker} every suite used to skip with "image
     * absent" on a machine that had the image.
     *
     * <p>Only "not found" means absent. Podman exits 125 both for an unknown
     * image and for a machine that is not running, so the exit status cannot
     * tell them apart; the message can. Anything that is not a not-found
     * message is reported verbatim, because "build the image" is the wrong
     * advice for a stopped VM and the engine's own text says what to do.
     */
    static Optional<String> imageUnavailableReason(String engine, String image,
                                                   CommandRunner runner) {
        List<String> command = List.of(engine, "image", "inspect", "--format", "{{.Id}}", image);
        CommandRunner.Result result;
        try {
            result = runner.run(command, Map.of(), QUERY_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted while checking for image '" + image + "'");
        } catch (Exception e) {
            return Optional.of("could not check for image '" + image + "': '"
                    + String.join(" ", command) + "' failed: " + e);
        }
        if (result.succeeded()) {
            return Optional.empty();
        }
        if (isImageNotFound(result.output())) {
            return Optional.of("container image '" + image + "' is not present ('" + engine
                    + " image inspect' does not know it). There is no public AMPS server image; "
                    + "build one with amps-server/Containerfile, or set AMPS_IMAGE to one you "
                    + "have.");
        }
        return Optional.of("could not check for image '" + image + "': '"
                + String.join(" ", command) + "' exited " + result.exitCode() + ":\n"
                + result.output().strip());
    }

    /**
     * Whether an {@code image inspect} failure means the image is absent, as
     * opposed to the engine being unable to answer.
     *
     * <p>podman 5: {@code failed to find image X: X: image not known}.
     * docker: {@code Error: No such image: X}. Deliberately narrow: a stopped
     * podman machine says "Cannot connect to Podman", and that must not be
     * read as "build the image".
     */
    static boolean isImageNotFound(String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("image not known")
                || lower.contains("no such image")
                || lower.contains("failed to find image");
    }

    // ----------------------------------------------------------------- start

    /**
     * Starts {@code amps-server/config/flows/<flow>} on free ports and blocks
     * until AMPS reports itself ready.
     *
     * @param flow the flow directory name, e.g. {@code artio-fix}
     * @throws IllegalStateException if the container exits during startup, or
     *     does not become ready in time; the message carries the server log
     */
    public static AmpsComposeServer start(String flow) throws Exception {
        return start(flow, System::getenv, ProcessCommandRunner.INSTANCE);
    }

    /**
     * As {@link #start(String)}, against an injected environment and command
     * runner, for tests of the start and teardown paths that must not start
     * anything.
     */
    static AmpsComposeServer start(String flow, UnaryOperator<String> env, CommandRunner runner)
            throws Exception {
        Path repoRoot = repositoryRoot();
        Path flowDir = repoRoot.resolve("amps-server/config/flows").resolve(flow);
        if (!Files.isRegularFile(flowDir.resolve("amps-config.xml"))) {
            throw new IllegalArgumentException("no such flow '" + flow + "': expected "
                    + flowDir.resolve("amps-config.xml"));
        }
        Path composeFile = repoRoot.resolve("amps-server/docker-compose.yml");
        if (!Files.isRegularFile(composeFile)) {
            throw new IllegalStateException("missing " + composeFile);
        }

        // All three sockets are held open until every port is chosen, so the OS
        // cannot hand the same number out twice within one start.
        int[] ports = freePorts(3);
        int port = ports[0];
        String project = "artio-amps-it-" + port;

        // Under build/, so it is inside the repository - and therefore inside
        // the directory a podman machine shares on macOS - and `gradle clean`
        // disposes of it.
        Path dataDir = repoRoot.resolve("build/amps-it").resolve(project);
        deleteRecursively(dataDir);
        for (String directory : List.of("sow", "journal", "stats")) {
            Files.createDirectories(dataDir.resolve(directory));
        }

        Compose composeChoice = composeCommand(engine(env), runner)
                .orElseThrow(() -> new IllegalStateException("no compose implementation "
                        + "available; call AmpsAssumptions.assumeAvailable() first"));
        List<String> compose = new ArrayList<>(composeChoice.command());
        compose.add("-p");
        compose.add(project);
        compose.add("-f");
        compose.add(composeFile.toString());

        // SELinux hosts need :z on a bind mount; podman's macOS VM rejects the
        // label on its virtiofs mounts, so it is Linux-only.
        String mountSuffix = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("linux") ? ":z" : "";

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("AMPS_IMAGE", image(env));
        environment.put("AMPS_PLATFORM", orDefault(env.apply("AMPS_PLATFORM"), "linux/amd64"));
        environment.put("AMPS_BIN", orDefault(env.apply("AMPS_BIN"), "/opt/amps/bin/ampServer"));
        environment.put("AMPS_PORT", Integer.toString(port));
        environment.put("AMPS_WS_PORT", Integer.toString(ports[1]));
        environment.put("AMPS_ADMIN_PORT", Integer.toString(ports[2]));
        environment.put("AMPS_CONTAINER_NAME", project);
        environment.put("AMPS_CONFIG_VOLUME", flowDir + ":/amps/config" + mountSuffix);
        environment.put("AMPS_DATA_VOLUME", dataDir + ":/amps/data" + mountSuffix);

        AmpsComposeServer server = new AmpsComposeServer(flow, project, port, ports[1], ports[2],
                dataDir, composeFile, compose, composeChoice.engine(), environment, runner);
        server.up();
        return server;
    }

    private void up() throws Exception {
        log.info("starting AMPS flow '{}' as compose project {} on ports {}/{}/{}",
                flow, project, port, websocketPort, adminPort);
        List<String> command = new ArrayList<>(compose);
        command.addAll(List.of("up", "-d"));

        // Marked up BEFORE `up -d` runs. A failed `up` has usually done half
        // its work - the network exists, the container may exist - and close()
        // has to run `down` to remove it. Set only after a successful `up`, a
        // failed start left all of that behind for the next suite to trip on.
        up = true;
        try {
            run(command, "could not start the AMPS container");
            awaitReady(0);
        } catch (Exception | Error e) {
            // Every kind, and InterruptedException in particular: it is what
            // Thread.sleep in awaitReady throws when a Gradle worker is
            // cancelled, and a catch of RuntimeException let it escape with the
            // container still running. The interrupt flag is put back only
            // AFTER close(): a pending interrupt makes the very next waitFor
            // throw before `down` has done anything.
            boolean interrupted = Thread.interrupted() || e instanceof InterruptedException;
            close();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
        log.info("AMPS ready: {} (admin {})", uri(), adminUrl());
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Restarts the container on the same data.
     *
     * <p>For tests that care what survives a bounce: the SOW files and the
     * journal are on a host bind mount, so a restart must return the same
     * records. A restart that quietly came back empty would pass a weaker
     * test than the one being written.
     */
    public void restart() throws Exception {
        int before = readyMarkerCount(logs());
        // `podman restart <name>` rather than `compose restart`: podman-compose
        // does its own container lookup for restart and has been seen to no-op
        // when the container carries an explicit container_name. The engine
        // always knows the name, because the compose file pins it.
        run(List.of(engine, "restart", project), "could not restart the AMPS container");
        awaitReady(before);
        log.info("AMPS restarted: {}", uri());
    }

    /**
     * Stops and removes the container and deletes the data directory.
     *
     * <p>Best-effort and never throws: a test that already failed should
     * report its own reason, not a teardown error on top of it.
     */
    @Override
    public void close() {
        if (up) {
            List<String> command = new ArrayList<>(compose);
            command.addAll(List.of("down", "-v"));
            try {
                run(command, "could not remove the AMPS container");
                log.info("removed compose project {}", project);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("interrupted while removing compose project {}; container {} may "
                        + "still be running", project, project);
            } catch (Exception e) {
                log.warn("could not remove compose project {}: {}", project, e.toString());
                // compose failed; make sure the container itself is gone.
                try {
                    run(List.of(engine, "rm", "-f", project), "could not remove container");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("interrupted; container {} may still be running", project);
                } catch (Exception ignored) {
                    log.warn("container {} may still be running", project);
                }
            }
            up = false;
        }
        deleteRecursively(dataDir);
    }

    // -------------------------------------------------------------- accessors

    /**
     * The client URI, selecting the {@code fix} message type.
     *
     * <p>The message type is a property of the CONNECTION, not of the topic:
     * connect on {@code /amps/json} to these topics and nothing parses - the
     * client connects and then silently understands nothing.
     */
    public String uri() {
        return "tcp://127.0.0.1:" + port + "/amps/fix";
    }

    /** The admin UI and its SQL console. */
    public String adminUrl() {
        return "http://127.0.0.1:" + adminPort + "/";
    }

    /** The host port for {@code amps/tcp}. */
    public int port() {
        return port;
    }

    /** The host port for the websocket transport, which the admin SQL console uses. */
    public int websocketPort() {
        return websocketPort;
    }

    /** The host port for the admin UI. */
    public int adminPort() {
        return adminPort;
    }

    /** The compose project name, which is also the container name. */
    public String project() {
        return project;
    }

    /** The flow this instance runs. */
    public String flow() {
        return flow;
    }

    /** Where this instance's SOW, journal and stats live on the host. */
    public Path dataDir() {
        return dataDir;
    }

    /** The compose file being driven, for diagnostics. */
    public Path composeFile() {
        return composeFile;
    }

    /**
     * The container engine binary this instance talks to: the one the chosen
     * compose implementation drives, which is not necessarily
     * {@code CONTAINER_ENGINE}.
     */
    public String engine() {
        return engine;
    }

    /**
     * The server log.
     *
     * <p>Read with {@code podman logs <container>}, not {@code compose logs}:
     * podman-compose's own {@code logs} depends on project bookkeeping that is
     * unreliable for a service with an explicit {@code container_name}, and
     * this is the string readiness detection and every failure message are
     * built from. The container name is ours because the compose file pins it,
     * so the engine can always be asked directly.
     */
    public String logs() {
        try {
            return runner.run(List.of(engine, "logs", project), Map.of(), QUERY_TIMEOUT).output();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while reading container logs";
        } catch (Exception e) {
            return "could not read container logs: " + e;
        }
    }

    // ---------------------------------------------------------------- waiting

    /**
     * Blocks until AMPS has announced readiness more than {@code seenBefore}
     * times and the port accepts a connection.
     *
     * <p>Counted rather than matched because {@link #restart()} needs a NEW
     * announcement: the previous run's line is still in the log afterwards, so
     * testing for presence returns instantly and hands back a server that is
     * still starting.
     */
    private void awaitReady(int seenBefore) throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String logs = logs();
            if (readyMarkerCount(logs) > seenBefore && portAccepts()) {
                return;
            }
            if (!isRunning()) {
                throw new IllegalStateException("the AMPS container " + project
                        + " exited during startup. Log:\n" + logs);
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("AMPS (" + project + ") was not ready on port " + port
                + " within " + STARTUP_TIMEOUT.toSeconds() + "s. Log:\n" + logs());
    }

    /** How many times the server has announced readiness in {@code logs}. */
    static int readyMarkerCount(String logs) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = logs.indexOf(READY_MARKER, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + READY_MARKER.length();
        }
    }

    private boolean portAccepts() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException notYet) {
            return false;
        }
    }

    /**
     * Whether the engine lists the container as running. An interrupt
     * propagates rather than reading as "not running", so a cancelled wait is
     * reported as cancelled and not as a crashed container.
     */
    private boolean isRunning() throws InterruptedException {
        try {
            CommandRunner.Result result = runner.run(
                    List.of(engine, "ps", "--format", "{{.Names}}"), Map.of(), QUERY_TIMEOUT);
            return result.succeeded() && result.output().lines().anyMatch(project::equals);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------- subprocess

    /**
     * Runs a command with the compose variables in its environment, failing
     * with its combined output.
     *
     * <p>stderr is merged into stdout rather than checked, because podman 5's
     * {@code podman compose} always writes a banner there about delegating to
     * the external provider. Treating stderr output as failure would make
     * every single invocation fail.
     */
    private void run(List<String> command, String failureMessage) throws Exception {
        CommandRunner.Result result;
        try {
            result = runner.run(command, environment, COMMAND_TIMEOUT);
        } catch (ProcessCommandRunner.TimedOutException e) {
            throw new IllegalStateException(failureMessage + ": " + e.getMessage(), e);
        }
        if (!result.succeeded()) {
            throw new IllegalStateException(failureMessage + "\ncommand: "
                    + String.join(" ", command) + "\nexit status: " + result.exitCode()
                    + "\noutput:\n" + result.output());
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The repository root, found by walking up from the working directory
     * until {@code settings.gradle.kts} appears.
     *
     * <p>Gradle's {@code Test} task sets {@code workingDir} to the root, so in
     * a normal build the first candidate is the answer. The walk is for
     * everything else: an IDE that runs a single test with the module
     * directory as its working directory, and anyone running the harness from
     * a scratch directory. Without it, the flow config and the compose file
     * are simply not found and the failure reads like a missing file rather
     * than a wrong working directory.
     */
    static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path at = candidate; at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
                return at;
            }
        }
        throw new IllegalStateException("could not find the repository root: no "
                + "settings.gradle.kts in " + candidate + " or any parent directory");
    }

    private static String engine(UnaryOperator<String> env) {
        return orDefault(env.apply("CONTAINER_ENGINE"), "podman");
    }

    private static String image(UnaryOperator<String> env) {
        return orDefault(env.apply("AMPS_IMAGE"), DEFAULT_IMAGE);
    }

    /**
     * A compose invocation and the engine binary it drives.
     *
     * <p>One decision, so that {@code logs}, {@code ps}, {@code restart} and
     * the image check all ask the engine that owns the container. Choosing the
     * compose command and the engine independently let the ladder fall back to
     * {@code docker compose} while {@code CONTAINER_ENGINE} stayed
     * {@code podman}, after which every question was put to the wrong engine.
     */
    record Compose(List<String> command, String engine) {
    }

    /**
     * The compose invocation to use, or empty when there is none.
     *
     * <p>Tried in the order that matches this repository's scripts:
     * {@code <engine> compose} (podman 5 has it, and delegates to an external
     * provider), then a standalone {@code podman-compose} (which drives
     * podman), then {@code docker compose} (which drives docker). The engine
     * in the answer is derived from the rung that succeeded, never from the
     * configuration.
     */
    static Optional<Compose> composeCommand(String engine, CommandRunner runner) {
        if (commandExists(engine, runner)
                && exitsZero(List.of(engine, "compose", "version"), runner)) {
            return Optional.of(new Compose(List.of(engine, "compose"), engine));
        }
        if (commandExists("podman-compose", runner) && commandExists("podman", runner)) {
            return Optional.of(new Compose(List.of("podman-compose"), "podman"));
        }
        if (commandExists("docker", runner)
                && exitsZero(List.of("docker", "compose", "version"), runner)) {
            return Optional.of(new Compose(List.of("docker", "compose"), "docker"));
        }
        return Optional.empty();
    }

    private static boolean commandExists(String command, CommandRunner runner) {
        return exitsZero(List.of("which", command), runner);
    }

    private static boolean exitsZero(List<String> command, CommandRunner runner) {
        try {
            return runner.run(command, Map.of(), QUERY_TIMEOUT).succeeded();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** {@code n} free host ports, all held until the last one is chosen. */
    private static int[] freePorts(int n) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>(n);
        try {
            int[] ports = new int[n];
            for (int i = 0; i < n; i++) {
                ServerSocket socket = new ServerSocket(0);
                sockets.add(socket);
                ports[i] = socket.getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Nothing useful to do; the port is either free or it is not.
                }
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A container running as another uid can leave files we
                    // cannot remove; the next run uses a different directory.
                }
            });
        } catch (IOException ignored) {
            // Cleanup is best-effort, never a test failure.
        }
    }
}
