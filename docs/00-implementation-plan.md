# Implementation plan: Artio FIX engine to AMPS bridge

Source of truth for the build. Written from `TODO.md`, the analysis in
`aeron_artio_amps_analysis.md`, and facts verified on this machine on
2026-09-06 (section 2). Every module and every sub-agent works from this
document; when the code and this document disagree, fix one of them.

## 1. Goals (from TODO.md)

1. A FIX 4.2 / 4.4 engine on **Artio** that connects to other FIX engines as
   **initiator or acceptor**.
2. Understand **QuickFIX/J FIX dictionaries** and **convert them to Artio
   dictionaries**, generating Artio codecs from the result.
3. Receive FIX messages from other engines and **publish them to AMPS topics**
   in native **FIX message format** (`MessageType fix`).
4. Analyse whether the Artio engine + AMPS bridge can run as a **Spring Boot**
   application, and build it.
5. AMPS runs locally in **podman**; integration tests spin it up with
   **podman compose**. The image comes from the `amps-demo` project.
6. Java 21, Gradle, multi-module, unit + integration tests per module, a
   README per module, an architecture diagram in the root README, detailed
   analysis documents in `docs/`.

## 2. Verified facts (do not re-derive)

| Item | Value | How verified |
| --- | --- | --- |
| JDK | Amazon Corretto 21 at `/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk` (default `java` is 23, so the Gradle toolchain must pin 21) | `ls /Library/Java/JavaVirtualMachines` |
| Gradle | wrapper 8.14.3 (copied from amps-demo); host has Gradle 9.2.1 too | `gradle --version` |
| Container engine | podman 5.8.2, `podman compose` delegates to `/usr/local/bin/podman-compose`; machine `podman-machine-default` (libkrun, 7 CPU, 6 GiB) is running | `podman compose version` |
| AMPS image | `localhost/amps-demo:5.3.5.135` already built (445 MB, linux/amd64, runs emulated on Apple Silicon). Built from `/Users/maojenhsu/ai-code/amps-demo/server/Containerfile` and the tarball `server/vendor/AMPS-5.3.5.135-Release-Linux.tar` | `podman images` |
| AMPS server layout | binary `/opt/amps/bin/ampServer`, config at `/amps/config/amps-config.xml`, workdir `/amps/data`, ports 9007 (tcp), 9008 (websocket), 8085 (admin). Readiness line in the log: `initialization completed` | amps-demo Containerfile, scripts |
| AMPS Java client | `com.crankuptheamps:amps-client:5.3.5.4` (Maven Central). Has byte-array publish: `long publish(byte[] topic, int off, int len, byte[] data, int off, int len)` plus `publishFlush(long timeoutMs)`, `sow(topic, filter)`, `setPublishStore(Store)` | `javap` on the jar |
| AMPS `fix` message type | selected in the client URI: `tcp://host:9007/amps/fix`; payload is SOH-separated `tag=value`; SOW keys reference tags as `/11`, `/37`, `/17`. **Corrected in phase 1b:** a SOW publish lacking its key field is NOT rejected by AMPS 5.3.5.135; it is silently stored under one degenerate key shared by every keyless message, so only the last survives. The bridge's tag-presence routing rule is therefore the only guard (see `docs/05` section 8.1, pinned by `SowKeyBehaviourIT`) | probed against the running server |
| Artio | `uk.co.real-logic:artio-core:0.168`, `artio-codecs:0.168`, `artio-session-codecs:0.168`. Depends on `io.aeron:aeron-client:1.47.4`, `aeron-archive:1.47.4`, `org.agrona:agrona:2.0.1`, `sbe-tool:1.34.1`. Needs `aeron-driver:1.47.4` for the embedded media driver | Maven Central POMs |
| Artio codec generator | `uk.co.real_logic.artio.dictionary.CodecGenerationTool <output-dir> <[fixt-xml;]xml>` with system properties `fix.codecs.parent_package`, `fix.codecs.flyweight`, `fix.codecs.wrap_empty_buffer`, `fix.codecs.tags_in_javadoc` | strings in the 0.168 jar |
| Artio dictionary format | the **same XML schema as QuickFIX/J** (`<fix type major minor>`, `<header>`, `<trailer>`, `<messages>`, `<components>`, `<fields>`). Reference: artio's own `artio-session-codecs/src/main/resources/session_dictionary.xml` (a slim FIX 4.4 admin-only dictionary) | fetched from GitHub |
| QuickFIX/J | `org.quickfixj:quickfixj-core:3.0.2` (Java 17+) and `quickfixj-messages-fix42` / `quickfixj-messages-fix44` (contain `FIX42.xml` / `FIX44.xml`). Raw dictionaries also at `quickfixj-messages/quickfixj-messages-fix42/src/main/resources/FIX42.xml` on the master branch | Maven Central HEAD + GitHub raw |
| Spring Boot | 3.5.9 (as used by amps-demo/fix42-publisher with `spring-boot-starter`, no web) | amps-demo catalog |

Consequences:

* **FIX 4.2 sessions need a generated dictionary.** Artio's bundled session
  codecs are FIX 4.4; an acceptor validates `BeginString` against the
  dictionary it is given, so FIX 4.2 counterparties require the
  `FixDictionaryImpl` generated from the converted QuickFIX/J `FIX42.xml`.
  That is why dictionary conversion is a first-phase module, not an extra.
* **Zero-String publish is possible.** Artio hands the library a
  `DirectBuffer`; the AMPS client accepts `byte[]` topic and payload. The
  bridge copies buffer to a reusable `byte[]` and never builds a `String`.
* **No Docker socket.** podman on macOS does not expose one unless enabled,
  so the test harness drives `podman compose` as a subprocess (the TODO's
  stated preference) rather than Testcontainers.

## 3. Module layout

```
aeron-artio-fix/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
├── README.md                  architecture diagram, quick start, module table
├── TODO.md                    the brief
├── docs/                      analysis documents (section 7)
├── fix-dictionary/            QuickFIX/J XML -> Artio XML converter (library + CLI)
├── fix-codecs/                generated Artio codecs for FIX 4.2 and FIX 4.4
├── artio-engine/              Artio engine + library runtime: acceptor/initiator, message sink SPI
├── quickfixj-counterparty/    QuickFIX/J initiator/acceptor: "the other FIX engine" for tests and demos
├── amps-server/               AMPS flow config, compose file, lifecycle script, Containerfile
├── amps-test-harness/         starts a throwaway AMPS via podman compose for integration suites
├── artio-amps-bridge/         AMPS publisher attached to the Artio library (plain Java runnable)
└── artio-spring-boot/         the same engine + bridge as a Spring Boot application
```

Dependency graph (compile-time):

```
fix-dictionary ──> fix-codecs ──> artio-engine ──> artio-amps-bridge ──> artio-spring-boot
                                     ▲                    ▲
quickfixj-counterparty (tests) ──────┘                    │
amps-test-harness (integrationTest only) ─────────────────┘
amps-server (no code: config + scripts, read by the harness)
```

Root package: `com.demo.artio`. One sub-package per module
(`dictionary`, `fix42`/`fix44`, `engine`, `qfj`, `testharness`, `bridge`,
`boot`).

## 4. Module designs

### 4.1 fix-dictionary

Purpose: read a QuickFIX/J dictionary, produce an XML the Artio generator
accepts, and explain every change it made.

* Bundled inputs under `src/main/resources/quickfixj/FIX42.xml` and
  `FIX44.xml` (QuickFIX/J master; keep the QuickFIX license header).
* Model: `FixDictionary` record tree (header, trailer, messages, components,
  fields, groups, enum values). DOM parsing, no external XML libs.
* `ArtioDictionaryConverter.convert(FixDictionary) -> ConversionResult`
  (converted dictionary + list of `ConversionNote`s: what was renamed,
  dropped, added and why). Normalisations to expect and verify by actually
  running the Artio generator on the output:
  * root attributes (`type`, `major`, `minor`, `servicepack`) preserved;
    Artio derives `BeginString` from major/minor.
  * every message carries `msgcat` (`admin` for the seven session messages,
    `app` otherwise);
  * the session messages Artio requires are present (Logon A, Logout 5,
    Heartbeat 0, TestRequest 1, ResendRequest 2, Reject 3, SequenceReset 4);
  * field `type` mapped onto Artio's `Field.Type` names; unknown types map
    to `STRING` with a note;
  * enum `description`s turned into valid Java identifiers and de-duplicated
    per field; values on `BOOLEAN` fields kept as `Y`/`N`;
  * anything else the generator rejects on the real FIX42/FIX44 inputs.
    Discover by running the tool; record each rule in `docs/02`.
* CLI `com.demo.artio.dictionary.ConvertDictionary <in.xml> <out.xml>` and a
  Gradle task `convertDictionaries` writing
  `build/artio-dictionaries/FIX42.xml` and `FIX44.xml`.
* `ArtioDictionaryCheck`: parses the output with Artio's own
  `uk.co.real_logic.artio.dictionary.DictionaryParser` (compile dependency on
  `artio-codecs`) so a conversion that Artio will reject fails here, with the
  reason, not later in code generation.
* Unit tests: parse both bundled dictionaries; conversion is idempotent;
  Artio's parser accepts both outputs; every message in the input survives;
  notes are produced for every mutation.

### 4.2 fix-codecs

Purpose: generated encoders/decoders and `FixDictionaryImpl` for FIX 4.2 and
FIX 4.4, produced at build time.

* Gradle: task `convertDictionaries` (JavaExec of `:fix-dictionary`), then
  two `JavaExec` tasks running `CodecGenerationTool` with
  `-Dfix.codecs.parent_package=com.demo.artio.fix42` (and `fix44`) into
  `build/generated/sources/artio/<version>`; both added to `main` sources;
  `compileJava` depends on them. Inputs/outputs declared so the generation is
  cached and incremental.
* Dependencies: `api(artio-codecs)`, `api(artio-session-codecs)`.
* Unit tests: encode a NewOrderSingle with the generated encoder, decode with
  the decoder, assert fields; `com.demo.artio.fix42.FixDictionaryImpl`
  reports `FIX.4.2`; same for 4.4.

### 4.3 artio-engine

Purpose: the engine, embeddable in any process, that receives FIX from other
engines and hands each message to a sink without copying it to the heap.

* `FixVersion { FIX42, FIX44 }` with `beginString()` and the dictionary
  class from fix-codecs.
* `EngineMode { ACCEPTOR, INITIATOR }`.
* `FixEngineConfig` record: mode, bind host/port (acceptor) or remote
  host/port (initiator), senderCompId, targetCompId, fixVersion,
  heartbeatIntervalSec, aeronDirectory, logFileDir, resetSeqNumsOnLogon,
  libraryId. Builder with sane defaults; validation.
* `FixMessageSink`: `void onMessage(FixMessageView message)`. `FixMessageView`
  exposes `DirectBuffer buffer()`, `offset()`, `length()`, `msgType()`
  (long, Artio's packed type), `sessionId()`, `sequenceNumber()`,
  `timestampNs()`, `sessionKey()` (sender/target comp IDs), plus a
  `isAdmin()` helper. The view is a reusable flyweight; sinks must not keep a
  reference beyond the callback.
* `ArtioRuntime implements AutoCloseable`: starts an embedded
  `ArchivingMediaDriver` (Artio needs Aeron Archive), `FixEngine.launch`,
  `FixLibrary.connect`, polls the library on an Agrona `AgentRunner` with a
  configurable idle strategy, and for INITIATOR calls `library.initiate(...)`
  and awaits the reply. Session events (logon, logout, disconnect) are
  logged and exposed through a `SessionListener`. Every accepted session's
  `SessionHandler.onMessage` fills the `FixMessageView` and calls the sink.
* Provided sinks: `LoggingSink` (prints printable FIX with `|` for SOH),
  `CompositeSink`, `CountingSink` (tests).
* Aeron directories live under the module's `build/` (or `java.io.tmpdir`)
  with a unique suffix; `close()` deletes them.
* Unit tests: config validation; `FixMessageView` accessors over a hand-built
  buffer; sinks.
* Integration tests (`integrationTest` source set, loopback only, no AMPS):
  Artio acceptor <- QuickFIX/J initiator, FIX 4.2 and 4.4: logon completes,
  N NewOrderSingle messages reach the sink with the right bytes; Artio
  initiator -> QuickFIX/J acceptor; Artio initiator <-> Artio acceptor.

### 4.4 quickfixj-counterparty

Purpose: the "other FIX engine". Used by every integration test that needs a
counterparty, and runnable for the demo.

* `QfjInitiator` / `QfjAcceptor` built from programmatic `SessionSettings`
  (no `.cfg` files on disk); FIX 4.2 and 4.4 via the version's
  `quickfixj-messages` jar; memory stores; screen log off by default.
* Acceptor replies to NewOrderSingle with an ExecutionReport (New, then a
  Fill) so an Artio initiator sees application traffic in both directions.
* Initiator has a `sendNewOrderSingle(...)`, `sendCancelReplace(...)`,
  `sendCancel(...)` and a scripted `OrderScenario` (3 orders, 1 replace, 1
  cancel) used by the bridge tests and the demo.
* `application` plugin: `./gradlew :quickfixj-counterparty:run
  --args="initiator --host localhost --port 9880 --version FIX.4.2
  --sender QFJ --target ARTIO --scenario orders"`.

### 4.5 amps-server

Purpose: the AMPS instance for this project, started with podman compose.

* `docker-compose.yml` modelled on amps-demo's: image `${AMPS_IMAGE}`,
  platform `${AMPS_PLATFORM:-linux/amd64}`, ports `${AMPS_PORT:-9007}`,
  `${AMPS_WS_PORT:-9008}`, `${AMPS_ADMIN_PORT:-8085}`, config and data
  bind-mounts from env, `restart: "no"`. **As built:** all three ports are
  published on `127.0.0.1` only — an unauthenticated demo server should not be
  reachable from the network the laptop is on.
* Flow `config/flows/artio-fix/amps-config.xml` (comments explain each
  topic):
  * `fix.raw` -- every application message, plain pub/sub, **journalled**
    (TransactionLog) so bookmark replay works; no SOW because a raw FIX
    message has no guaranteed unique key.
  * `fix.orders` -- SOW, MessageType fix, key `/11` (ClOrdID): 35=D, G, F.
  * `fix.execs` -- SOW, key `/17` (ExecID): every 35=8, the fills history.
  * `fix.order.state` -- SOW, key `/37` (OrderID): 35=8, latest report per
    order is the order's state (FIX reports are cumulative snapshots).
  * `fix.admin` -- optional pub/sub for session-level messages when the
    bridge is configured to publish them.
  * Admin on 8085 with `SQLTransport` on the websocket transport, logging to
    stdout, `MinJournalSize` 10MB (the floor).
* `scripts/amps.sh start|stop|down|restart|status|logs|wait|config` wrapping
  `podman compose -p artio-amps -f amps-server/docker-compose.yml`. Defaults:
  `AMPS_IMAGE=localhost/amps-demo:5.3.5.135`, `AMPS_FLOW=artio-fix`. Data
  under `amps-server/data/<flow>/` (gitignored). `wait` polls the port and
  then the `initialization completed` log line.
* `Containerfile` and `vendor/README.md` copied from amps-demo so the image
  can be rebuilt here from a tarball; the tarball itself is gitignored.
  Document in the README that the default is to reuse amps-demo's image.
* No Java code. A Gradle `check` task (`checkConfigXml`) validates
  `amps-config.xml` is well-formed XML, contains no `--` inside comments (AMPS
  rejects that), **and does not mention the startup log line `AMPS
  initialization completed` anywhere** — AMPS echoes its whole config into its
  own log at startup, so a comment quoting the readiness marker makes every
  reader watching for that phrase declare the server ready about a second
  before it is listening (see `docs/05` section 3).

### 4.6 amps-test-harness

Purpose: give every integration suite a throwaway AMPS, started with podman
compose, skipped cleanly when it cannot run.

* `AmpsComposeServer implements AutoCloseable` with static
  `unavailableReason()` (podman missing, `podman compose` missing, image not
  known per **`<engine> image inspect`**, or `AMPS_IT=false`) and `start(flow)`.
  **As built:** `image inspect`, not `podman image exists` — the latter is
  podman-only and would skip every run under `CONTAINER_ENGINE=docker` — run
  against the engine belonging to the compose rung that actually succeeded, not
  whatever `CONTAINER_ENGINE` names, so `logs`/`ps`/`restart` cannot end up
  talking to a different engine than `up`. Only "not known" means a missing
  image; any other engine failure (a stopped podman machine) is reported
  verbatim as a check failure.
  Each start picks three free host ports and a unique compose project name
  `artio-amps-it-<port>`, sets `AMPS_CONFIG_VOLUME` and `AMPS_DATA_VOLUME`
  (data under `build/amps-it/<project>/`), runs `up -d`, waits for the port
  and the readiness line in `compose logs`, fails fast if the container
  exits. `close()` runs `down` and deletes the data directory.
* `uri()` -> `tcp://127.0.0.1:<port>/amps/fix`, `adminUrl()`, `logs()`.
* `AmpsAssumptions.assumeAvailable()` for JUnit 5 (`Assumptions`).
* `SowReader`: `List<String> query(uri, topic, filter)` returning raw FIX
  payloads, and `subscribeCount(uri, topic, bookmark, timeout)`.
* Its own integration test: start, connect with the AMPS client, publish
  one FIX record to `fix.orders`, query it back, stop.

### 4.7 artio-amps-bridge

Purpose: the Artio-to-AMPS publisher, and the answer to "can Artio publish
to AMPS without leaving the off-heap path".

* `AmpsFixPublisher implements FixMessageSink, AutoCloseable`.
  * Artio library thread: `onMessage` writes `[msgType][length][bytes]` into
    an Agrona `OneToOneRingBuffer` over an off-heap `UnsafeBuffer`; if the
    ring is full, apply `OverflowPolicy` (`DROP_AND_COUNT` default,
    `BLOCK` optional). Nothing here allocates.
  * Publisher thread (Agrona `AgentRunner`): drains the ring buffer, resolves
    the topic via `TopicRouter`, copies into a reusable `byte[]`, calls
    `client.publish(topicBytes, ..., dataBytes, ...)`. On AMPS disconnect it
    reconnects with back-off and counts what was lost.
  * `close()` calls `publishFlush(timeout)` so nothing is left in the
    client's send buffer, then closes the client.
* `TopicRouter`: ordered rules `msgType -> topics` plus a default topic;
  admin messages go to `fix.admin` or are dropped, per config. Defaults
  match the `artio-fix` flow in 4.5. A rule can require a tag to be present
  (35=8 to `fix.order.state` only if tag 37 exists). This is a
  correctness requirement, not a mirror of a server check: AMPS accepts a
  keyless SOW publish and silently collapses all such records into one.
  Unroutable messages are counted, never dropped silently; `fix.raw` keeps
  every message regardless.
* `AmpsPublishPort` interface wraps the two AMPS calls used, so unit tests
  run against an in-memory fake and only the integration test touches the
  real client.
* `BridgeConfig` record: uri, clientName, routes, ringBufferCapacityBytes,
  publishAdminMessages, overflowPolicy, flushTimeoutMs, reconnect back-off.
* `BridgeMain`: plain-Java runnable that reads `bridge.properties` (or
  system properties) and starts `ArtioRuntime` + `AmpsFixPublisher`; this is
  the non-Spring demo entry point (`./gradlew :artio-amps-bridge:run`).
* `SowDump` utility main: prints a SOW topic (`--topic fix.orders`) as
  printable FIX, for the demo's "did it arrive" step.
* Unit tests: `TopicRouter` rules; ring-buffer hand-off under a fake port
  including overflow accounting; printable rendering.
* Integration test: harness AMPS + Artio acceptor + bridge + QuickFIX/J
  initiator running the order scenario; after `publishFlush`, `fix.orders`
  holds one record per ClOrdID, `fix.raw` replays the full count from the
  epoch bookmark, and the counters match.

### 4.8 artio-spring-boot

Purpose: the same runtime as a Spring Boot application, and the feasibility
analysis the TODO asks for.

* `spring-boot-starter` only (no web). `ArtioProperties` and
  `BridgeProperties` (`@ConfigurationProperties`, records, validated) bound
  from `application.yml`; beans: AMPS `Client` (`destroyMethod = "close"`),
  `AmpsFixPublisher`, `ArtioRuntime` as a `SmartLifecycle` so Spring starts
  it after the publisher and stops it first.
* Defaults: acceptor on `0.0.0.0:9880`, `ARTIO`/`QFJ`, FIX.4.2, AMPS
  `tcp://localhost:9007/amps/fix`. Every property overridable with
  `-Dartio.*` / `-Damps.*` through `bootRun`, as amps-demo does.
* Profiles: `initiator` profile example in `application-initiator.yml`.
* Unit test: context loads with the runtime disabled
  (`artio.enabled=false`) and properties bind.
* Integration test: full flow in-process (harness AMPS, `SpringApplication`
  run with random port, QuickFIX/J initiator scenario, SOW assertions).
* `docs/04` records the analysis: it works; the constraints are thread
  ownership (Artio and the bridge own their agent threads, Spring only starts
  and stops them), no servlet stack, the JVM flags Aeron wants, fat-jar
  considerations, and where allocation happens (context start-up only).

## 5. Conventions

* Java 21 toolchain, `-Xlint:all -parameters`, UTF-8. No Lombok. Records for
  configuration. SLF4J API everywhere; `slf4j-simple` at runtime for mains
  and tests.
* **JVM flags (found in phase 1a):** every JVM that instantiates an Artio
  codec or runs Aeron needs
  `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED`
  (Agrona 2.0.1 `UnsafeApi`), and any JVM that launches a `FixEngine` also
  needs `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` (found in phase 2:
  Artio's `ReceiverEndPoints` reflects on `SelectorImpl.selectedKeys` in a
  static initialiser). Set all three on every `Test`, `JavaExec`,
  `application` and `bootRun` task; document them in every README that shows
  a run command.
* `:fix-codecs` compiles with `-Xlint:none` (generated code trips javac's
  `this-escape` cap); no other module relaxes lint.
* JUnit 5 (`junit-bom`), Awaitility for asynchronous assertions. Test names
  say what is asserted.
* Two suites per module that needs it: `test` (no containers, no external
  ports) and `integrationTest` (own source set and task, `check` depends on
  it). Anything that needs AMPS calls `AmpsAssumptions.assumeAvailable()`
  and skips with a reason when it cannot run; everything else in
  `integrationTest` must pass with no AMPS present.
* Free ports from `new ServerSocket(0)`; never a fixed port in a test. Aeron
  directories and AMPS data directories under `build/`, unique per run,
  deleted on close.
* Environment variables honoured by the harness and scripts: `AMPS_IMAGE`
  (default `localhost/amps-demo:5.3.5.135`), `AMPS_PLATFORM`
  (`linux/amd64`), `CONTAINER_ENGINE` (`podman`), `AMPS_IT` (`false` skips).
  Gradle declares each as a task input so the build cache cannot restore an
  all-skipped result for a run that could execute.
* Every module has a `README.md`: what it is, how to run its demo, how to
  run its tests, what its configuration means.
* Never edit another module's sources. Shared versions live only in
  `gradle/libs.versions.toml`; append, do not rewrite.
* `./gradlew build` must pass at the end of every step; `./gradlew
  <module>:integrationTest` must pass for the module just built.

## 6. Phases and sub-agent assignment

**All phases are complete.** Outcome per phase:

| Phase | Agent | Modules | Documents | Outcome |
| --- | --- | --- | --- | --- |
| 0 | orchestrator | skeleton: root Gradle files, version catalog, wrapper, `.gitignore`, empty module stubs | `docs/00` (this) | done: Gradle 8.14.3 wrapper, Java 21 toolchain pinned to Corretto 21, eight modules |
| 1a | A | `fix-dictionary`, `fix-codecs` | `docs/02-quickfixj-to-artio-dictionary.md` | done: QuickFIX/J's stock FIX42/FIX44 convert with **one** change between them (a `UTCDATE` alias), and both generate and compile |
| 1b | B | `amps-server`, `amps-test-harness` | `docs/05-integration-testing-and-demo.md` (harness half) | done: compose-driven throwaway AMPS with clean skip rules, and the keyless-SOW correction to section 2 that the bridge depends on |
| 2 | C | `artio-engine`, `quickfixj-counterparty` | `docs/01-artio-engine-design.md` | done: acceptor and initiator on both versions, zero-copy sink, plus the third JVM flag (`sun.nio.ch`) that only a `FixEngine` needs |
| 3 | D | `artio-amps-bridge` | `docs/03-artio-amps-bridge-design.md` | done: off-heap ring buffer hand-off, tag-guarded topic routing, `run` and `sowDump` mains; nothing allocates per message |
| 4 | E | `artio-spring-boot` | `docs/04-spring-boot-feasibility.md` | done, and the answer is yes: two `SmartLifecycle` phases give the required start/stop order, the fat jar runs, Spring owns no thread on the message path |
| 5 | F | root `README.md` with architecture diagram, module READMEs review, `docs/05` runbook half | -- | done: root README with the architecture diagram, every module README reconciled with the code, the demo runbook run for real (section 9 of `docs/05`) |
| 6 | orchestrator (Fable) | code review of every module (correctness, allocation on the hot path, shutdown ordering, test quality), fixes, then full `./gradlew build` and every `integrationTest` with AMPS up; commit | `docs/06-code-review.md` | done: 54 findings across the five module reviews, every one fixed, test-added or explicitly `wontfix` with a reason; full build and every `integrationTest` green with AMPS up |

Phases 1a and 1b ran in parallel (disjoint files); phase 2 started as soon as
1a was done. The review record lives in
[`06-code-review.md`](06-code-review.md), not appended to `docs/05` as
originally planned — it is a per-module table, and `docs/05` is a runbook.

## 7. Documents

| File | Content |
| --- | --- |
| `docs/aeron_artio_amps_analysis.md` | existing background analysis |
| `docs/00-implementation-plan.md` | this plan |
| `docs/01-artio-engine-design.md` | engine/library/media-driver topology as built, threading, session lifecycle, configuration, how acceptor and initiator differ, how a sink gets a zero-copy view, what was learned making FIX 4.2 work |
| `docs/02-quickfixj-to-artio-dictionary.md` | the two dictionary formats compared, every conversion rule and why the Artio generator needs it, how codecs are generated at build time, how to add another dictionary |
| `docs/03-artio-amps-bridge-design.md` | the hand-off design (ring buffer, publisher agent, overflow policy), topic routing and the AMPS topic/SOW design, the guaranteed-delivery option, measured throughput if time allows |
| `docs/04-spring-boot-feasibility.md` | the feasibility analysis with the answer, constraints, and the wiring used |
| `docs/05-integration-testing-and-demo.md` | how podman compose is driven, the skip rules, the end-to-end demo runbook and how to verify each step |
| `docs/06-code-review.md` | the phase-6 review record: every finding, where it was, and how it was resolved |

## 8. Demo (end state)

```bash
amps-server/scripts/amps.sh start                       # AMPS in podman, artio-fix flow
./gradlew :artio-spring-boot:bootRun                    # Artio acceptor on 9880 + AMPS bridge
./gradlew :quickfixj-counterparty:run --args="initiator --host localhost --port 9880 --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"
./gradlew :artio-amps-bridge:sowDump --args="--topic fix.orders"   # records keyed by ClOrdID
```

The same flow without Spring: `./gradlew :artio-amps-bridge:run` in place
of `bootRun`.

**As run.** The full runbook, with the real output of every step and the two
results that surprise people (five SOW records for three orders; zero execution
reports, because an Artio gateway is not a matching engine), is
[`05-integration-testing-and-demo.md`](05-integration-testing-and-demo.md)
section 9, and summarised in the root [`README.md`](../README.md).
