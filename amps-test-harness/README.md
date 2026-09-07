# amps-test-harness

A throwaway AMPS instance for an integration suite, started with
`podman compose` from **the same compose file and the same flow config a
developer runs by hand** (`amps-server/`). A test therefore cannot pass against
a configuration nobody uses.

Not Testcontainers, deliberately: podman on macOS exposes a Docker-compatible
socket only if you go and enable it, so driving the CLI as a subprocess needs
nothing but the binary on `PATH`.

## Using it from another module

Add the dependency to your module's `integrationTest` source set:

```kotlin
dependencies {
    "integrationTestImplementation"(project(":amps-test-harness"))
}
```

and forward the environment as **declared task inputs** - copy the block from
this module's [`build.gradle.kts`](build.gradle.kts), including
`outputs.cacheIf { false }`, and read the comments there before you trim
anything. Set `workingDir = rootProject.projectDir` on the task.

Then:

```java
class MyBridgeIT {

    private static AmpsComposeServer amps;

    @BeforeAll
    static void startAmps() throws Exception {
        AmpsAssumptions.assumeAvailable();     // skips, with a reason, when it cannot run
        amps = AmpsComposeServer.start("artio-fix");
    }

    @AfterAll
    static void stopAmps() {
        if (amps != null) {
            amps.close();
        }
    }

    @Test
    void ordersReachTheSow() throws Exception {
        try (Client client = new Client("my-test")) {
            client.connect(amps.uri());        // tcp://127.0.0.1:<free port>/amps/fix
            client.logon(10_000);
            client.publish("fix.orders", rawFix);
            client.publishFlush(10_000);       // publishes are async; without this you race
        }

        List<String> records =
                SowReader.query(amps.uri(), "fix.orders", "/11 = '" + clOrdId + "'");

        assertEquals(rawFix, records.get(0));
    }
}
```

One container per test **class**, not per method: starting one costs a few
seconds, and the image is amd64 running emulated on Apple Silicon.

## The API

### `AmpsComposeServer implements AutoCloseable`

| Member | |
| --- | --- |
| `static Optional<String> unavailableReason()` | why an AMPS test cannot run here, or empty. See the skip rules below. |
| `static AmpsComposeServer start(String flow)` | starts `amps-server/config/flows/<flow>` and blocks until AMPS reports ready. Throws with the container log if it exits or never becomes ready. |
| `String uri()` | `tcp://127.0.0.1:<port>/amps/fix` |
| `String adminUrl()` | `http://127.0.0.1:<adminPort>/` |
| `String logs()` | the server log, for a failure message |
| `void restart()` | bounce the container on the same data; returns when it is ready again |
| `void close()` | `compose down` plus delete the data directory, both best-effort |
| `int port()`, `websocketPort()`, `adminPort()`, `String project()`, `String flow()`, `Path dataDir()`, `Path composeFile()` | accessors, for assertions and diagnostics |

### `AmpsAssumptions`

`assumeAvailable()` - one line at the top of a suite, turning
`unavailableReason()` into a JUnit 5 assumption with the reason as the skip
message. This is why the module puts JUnit on its `api` configuration: the
assumption is part of the harness's surface, not of its own tests.

### `SowReader`

| Member | |
| --- | --- |
| `static List<String> query(String uri, String topic, String filter)` | what the **SOW** holds now: raw FIX payloads, SOH intact, one per matching key. `filter` may be null. |
| `static List<String> query(String uri, String topic)` | every record |
| `static long countFromEpoch(String uri, String topic, Duration timeout)` | what the **transaction log** holds: a bookmark subscription from `EPOCH`, counted. Costs at least `timeout`, because a replay has no end marker - "finished" is "idle". A topic absent from `<TransactionLog>` answers zero. |
| `static String printable(String fix)` | SOH rendered as `\|`, **for messages and logs only** |
| `static final char SOH` | `'\u0001'` |

`printable` is never for comparison. Payloads come back byte for byte, and
comparing printable forms would hide a publisher that mangled the separators -
exactly the bug this harness exists to catch.

## Isolation

Every instance is independent of every other one and of anything you started by
hand with `amps-server/scripts/amps.sh`:

* **three free host ports** from `ServerSocket(0)`, all held open until the last
  is chosen so the OS cannot hand the same number out twice;
* **compose project and container name** `artio-amps-it-<port>`, so two suites
  in one Gradle run cannot collide and a leaked container says where it came
  from;
* **data directory** `build/amps-it/artio-amps-it-<port>/{sow,journal,stats}`,
  emptied before start, deleted on `close()`, and disposed of by
  `gradle clean`. Under `build/` so it stays inside the directory a podman
  machine shares on macOS.

## Environment

| Variable | Default | |
| --- | --- | --- |
| `AMPS_IMAGE` | `localhost/amps-demo:5.3.5.135` | must exist locally; `<engine> image inspect` is the check, run against the engine the chosen compose implementation drives |
| `AMPS_PLATFORM` | `linux/amd64` | AMPS is x86_64 only |
| `AMPS_BIN` | `/opt/amps/bin/ampServer` | |
| `CONTAINER_ENGINE` | `podman` | |
| `AMPS_IT` | unset | `false` switches the suites off |

The harness *exports* `AMPS_PORT`, `AMPS_WS_PORT`, `AMPS_ADMIN_PORT`,
`AMPS_CONTAINER_NAME`, `AMPS_CONFIG_VOLUME` and `AMPS_DATA_VOLUME` to compose;
setting those yourself has no effect.

## Skip rules

`unavailableReason()` returns a reason, in this order:

1. `AMPS_IT=false`. Checked **first**, so the switch works on a machine with no
   podman at all.
2. `CONTAINER_ENGINE` (default `podman`) is not on `PATH`.
3. No usable compose implementation: `<engine> compose version` fails and
   neither `podman-compose` nor `docker compose` works.
4. `<engine> image inspect $AMPS_IMAGE` says the image is not known. There is no
   public AMPS server image, so this is an ordinary state of a developer
   machine. `image inspect` rather than `podman image exists` because the latter
   is podman-only and would skip every run under `CONTAINER_ENGINE=docker`; the
   engine is the one the compose rung that succeeded drives, not whatever
   `CONTAINER_ENGINE` names. Only "not known" is a missing image — any **other**
   engine failure (a stopped podman machine, a refused connection) is reported
   verbatim as a check failure, so a broken machine does not masquerade as an
   ordinary absent image.

A skip is not a failure and `./gradlew build` stays green without AMPS. The
corollary is that **a green build is not proof these ran** - look for
`0 skipped`. The `integrationTest` task is deliberately never up to date and
never cached, so asking for it always runs it; the reasoning is in the comments
in `build.gradle.kts`.

## Tests

```bash
./gradlew :amps-test-harness:build              # unit tests only, no container
./gradlew :amps-test-harness:integrationTest    # starts a real AMPS
```

`test` covers `printable`, the skip rules against an injected environment (a
JVM cannot set its own environment variables), readiness-marker counting,
subprocess timeout handling (`ProcessCommandRunnerTest`), a failed `up -d`
still running `down` (`AmpsComposeServerStartFailureTest`), engine selection
(`AmpsComposeServerEngineTest`), and repository-root discovery.

No `--add-opens` / `--add-exports` are set on this module's tasks, and none are
needed: the harness drives subprocesses and the AMPS client, and touches
neither Artio nor agrona. A **consumer's** `integrationTest` task usually does
need [the three flags](../artio-engine/README.md#jvm-flags--all-three-mandatory),
because it runs an engine alongside this harness — copy them with the rest of
the block from `build.gradle.kts`.

`integrationTest` has two classes:

* `AmpsComposeServerIT` publishes a hand-built FIX 4.2 NewOrderSingle to
  `fix.orders` and `fix.raw`, reads it back byte for byte, replays the journal
  from the epoch, restarts the container and checks both survive, and confirms
  the admin port is mapped.
* `SowKeyBehaviourIT` pins down what AMPS does with a publish that lacks the
  topic's SOW key, because it is **not** what `docs/00` says. It is accepted,
  silently, and every such message shares one record - so three keyless
  publishes leave one. This is the fact the bridge's `TopicRouter` exists to
  work around; see
  [`../docs/05-integration-testing-and-demo.md`](../docs/05-integration-testing-and-demo.md)
  section 8.1.

## Further reading

[`../docs/05-integration-testing-and-demo.md`](../docs/05-integration-testing-and-demo.md)
covers how compose is driven, the podman-compose quirks that shaped this code,
and readiness detection.
