# artio-spring-boot

The Artio FIX engine and the AMPS bridge as a **Spring Boot application**, and the answer to the
TODO's fourth question.

Nothing on the message path differs from [`:artio-amps-bridge`](../artio-amps-bridge): the same
`AmpsFixPublisher`, the same off-heap ring buffer, the same `ArtioRuntime`, the same threads. What
Spring adds is `@ConfigurationProperties` binding with validation, profiles, an executable fat jar,
and a pair of `SmartLifecycle` phases that get the start-up and shutdown **order** right without a
hand-written shutdown hook.

The analysis, with the measured numbers, is in
[`docs/04-spring-boot-feasibility.md`](../docs/04-spring-boot-feasibility.md).

## What it wires

```
application.yml / --args / -D…
        │
        ├── ArtioProperties  ──toFixEngineConfig()──►  ArtioRuntime ◄── FixMessageSink
        │                                                   ▲                 │
        └── BridgeProperties ──toBridgeConfig()───►  AmpsFixPublisher ────────┘
                                                            │        (CompositeSink:
                                                            │         LoggingSink + publisher)
                                                            ▼
                                                          AMPS
```

Six beans, all in [`BridgeConfiguration`](src/main/java/com/demo/artio/boot/BridgeConfiguration.java):

| Bean | Conditional on | What it is |
| --- | --- | --- |
| `ampsFixPublisher` | `bridge.enabled` | the bridge, not yet started |
| `fixMessageSink` | always (`@Primary`) | `CompositeSink.of(LoggingSink, publisher)` |
| `artioRuntime` | `artio.enabled` | the engine and library, not yet started |
| `bridgePublisherLifecycle` | `bridge.enabled` | phase `MIN+1000`: starts first, stops last |
| `artioRuntimeLifecycle` | `artio.enabled` | phase `MAX-1000`: starts last, stops first |
| `statsLogger` | `bridge.enabled` | the periodic health line; `0` disables it |

`@Primary` on `fixMessageSink` is load-bearing, not tidiness: `AmpsFixPublisher` **is** a
`FixMessageSink`, so with the bridge enabled there are two beans of that type and the engine's
injection point is ambiguous. Without it the application does not start at all.

## Lifecycle: the ordering is the point

Spring starts `SmartLifecycle` phases in ascending order and stops them in descending order. The two
phase numbers turn that into exactly the ordering the bridge requires — the one `BridgeMain` writes
out by hand and [`docs/03` section 8](../docs/03-artio-amps-bridge-design.md) explains:

```
 phase                                start ▼            stop ▲
 ─────────────────────────────────────────────────────────────────────────────
 MAX (Integer.MAX_VALUE)   Spring's scheduler      4     1   (StatsLogger stops)
 MAX-1000  ArtioRuntimeLifecycle      3     2   runtime.close()   ← engine first
    0      (nothing; room for probes)
 MIN+1000  BridgePublisherLifecycle   2     3   publisher.close() ← drain + flush
 ─────────────────────────────────────────────────────────────────────────────
                            context refresh 1     4   destroySingletons
```

* **Start**: the publisher must be able to accept messages before Artio can deliver any.
* **Stop**: the engine must go down **first**, so the ring buffer stops filling and the publisher's
  `close()` can drain what is left and `publishFlush` it. Closing the publisher first would leave
  Artio delivering into a stopped agent and count the tail of the session as dropped.
* `ArtioRuntimeLifecycle` deliberately sits at `MAX-1000` rather than `MAX`, leaving the top slot to
  Spring's `ScheduledAnnotationBeanPostProcessor` — so the stats logger stops before the engine
  instead of racing a closing one for its session list.
* Neither `@Bean` uses `destroyMethod`. `@Bean` would otherwise infer `close()` on both
  `AutoCloseable`s, and destruction runs *after* the stop phases in reverse dependency order, not in
  phase order. One owner each: the lifecycle adapter.

`stop(Runnable)` runs `close()` synchronously and only then calls the callback — the callback
releases the phase, so running it early would let the JVM proceed to exit with messages still in the
ring buffer.

## Running the demo

```bash
# 1. AMPS in podman, artio-fix flow
./amps-server/scripts/amps.sh start

# 2. the bridge: Artio acceptor on 9880 + the AMPS publisher
./gradlew :artio-spring-boot:bootRun

# 3. "the other FIX engine": 3 orders, 1 replace, 1 cancel
./gradlew :quickfixj-counterparty:run \
    --args="initiator --host localhost --port 9880 --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"

# 4. did it arrive?
./gradlew :artio-amps-bridge:sowDump --args="--topic fix.orders"

# 5. Ctrl-C the bootRun, then
./amps-server/scripts/amps.sh down
```

Step 4 prints one SOW record per `ClOrdID` — five, not three, because a cancel/replace carries a new
tag 11 with the previous one in tag 41:

```
SOW fix.orders on tcp://localhost:9007/amps/fix
  [1] 8=FIX.4.2|...|35=D|...|11=ORD-1|...|55=MSFT|...
  [2] 8=FIX.4.2|...|35=D|...|11=ORD-2|...
  [3] 8=FIX.4.2|...|35=D|...|11=ORD-3|...
  [4] 8=FIX.4.2|...|35=G|...|11=ORD-4|...|41=ORD-1|...
  [5] 8=FIX.4.2|...|35=F|...|11=ORD-5|...|41=ORD-2|...
5 record(s) in fix.orders
```

The same flow without Spring is `./gradlew :artio-amps-bridge:run`.

### The fat jar

`bootJar` is **enabled** here (amps-demo's `fix42-publisher` disables it) because "does the
executable jar run?" is part of the feasibility question this module exists to answer. `jar` is
disabled instead, so `build/libs` holds exactly one artefact and nothing has to guess between
`-plain.jar` and the real one.

```bash
./gradlew :artio-spring-boot:bootJar

java --add-opens   java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-opens   java.base/sun.nio.ch=ALL-UNNAMED \
     -jar artio-spring-boot/build/libs/artio-spring-boot-1.0.0.jar \
     --artio.port=9880 --bridge.uri=tcp://localhost:9007/amps/fix
```

`BootJarSubprocessIT` runs exactly that, sends it SIGTERM, and asserts what reached AMPS. A JVM
killed by SIGTERM exits **143** (128 + 15) even when every shutdown hook completed; Gradle reports
`bootRun` as failing for the same reason. The work is done — look for the two shutdown lines below.

## JVM flags — all three, mandatory

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens   java.base/sun.nio.ch=ALL-UNNAMED
```

The first two are agrona 2.0.1's (`UnsafeApi` reaches `jdk.internal.misc.Unsafe`); the third is
Artio's (`ReceiverEndPoints` reflects on `sun.nio.ch.SelectorImpl.selectedKeys` in a static
initialiser). Spring changes nothing about this — the fat jar needs them exactly as a plain
classpath does, and `java -jar` has nowhere to get them from except the command line or
`JAVA_TOOL_OPTIONS`.

* `bootRun` and every `Test` task set them in [`build.gradle.kts`](build.gradle.kts).
* `java -jar` needs them on the command line, as above.
* In a container: `ENV JAVA_TOOL_OPTIONS="--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED"`.

Without them you get `IllegalAccessError` from a static initialiser during `ArtioRuntime.start()`,
which surfaces as an `ExceptionInInitializerError` inside a Spring bean factory method and reads as
a Spring problem. It is not.

## Configuration

Every key below can be set in `application.yml`, as `--key=value` on the command line, as
`-Dkey=value` through `bootRun`, or as an environment variable (`ARTIO_PORT`, `BRIDGE_URI`).
Relaxed binding means `artio.senderCompId`, `artio.sender-comp-id` and `ARTIO_SENDERCOMPID` are the
same key.

### `artio.*` — the FIX engine

| Key | Default | Meaning |
| --- | --- | --- |
| `artio.enabled` | `true` | `false` leaves `ArtioRuntime` out of the context entirely |
| `artio.mode` | `acceptor` | `acceptor` (bind and wait) or `initiator` (connect out) |
| `artio.name` | `bridge` | label for thread names, directories and logs |
| `artio.host` | `0.0.0.0` | bind address (acceptor) or remote address (initiator) |
| `artio.port` | `9880` | bind port, or remote port |
| `artio.sender-comp-id` | `ARTIO` | this engine's `SenderCompID(49)` |
| `artio.target-comp-id` | `QFJ` | the counterparty's `TargetCompID(56)` |
| `artio.fix-version` | `FIX.4.2` | `FIX.4.2` or `FIX.4.4` (`FIX42` / `FIX44` also accepted) |
| `artio.heartbeat-interval-sec` | `30` | `HeartBtInt(108)` proposed at logon |
| `artio.base-directory` | *(blank)* | parent of the derived Aeron/archive/log dirs; blank means `java.io.tmpdir` |
| `artio.reset-seq-nums-on-logon` | `true` | send `ResetSeqNumFlag(141)=Y` (initiator) |
| `artio.idle-strategy` | `BACKOFF` | `BACKOFF`, `BUSY_SPIN` or `SLEEPING` for the library poll thread |
| `artio.logon-timeout-ms` | `20000` | how long start-up waits for an initiator's session to go active |
| `artio.reply-timeout-ms` | `10000` | Artio's engine/library reply timeout |
| `artio.shutdown-timeout-ms` | `5000` | how long `close()` waits for a graceful FIX logout |
| `artio.delete-directories-on-close` | `true` | delete the Aeron and Artio directories on shutdown |
| `artio.log-messages` | `true` | compose a `LoggingSink` ahead of the publisher (allocates a `String` per message; a demo affordance) |

### `bridge.*` — AMPS

| Key | Default | Meaning |
| --- | --- | --- |
| `bridge.enabled` | `true` | `false` leaves `AmpsFixPublisher` out of the context |
| `bridge.uri` | `tcp://localhost:9007/amps/fix` | the AMPS URI. `/amps/fix` selects the server-side FIX parser — that is what makes `/11`-style SOW keys work |
| `bridge.client-name` | `artio-bridge` | AMPS client name; a unique suffix is appended per connection |
| `bridge.default-topic` | `fix.raw` | every application message goes here; blank for none |
| `bridge.admin-topic` | `fix.admin` | where session-level messages go when the next key is true |
| `bridge.publish-admin-messages` | `false` | publish `Logon`, `Heartbeat`, … at all |
| `bridge.ring-buffer-capacity-bytes` | `4194304` | the off-heap hand-off buffer; a power of two |
| `bridge.overflow-policy` | `DROP_AND_COUNT` | or `BLOCK`: hold the Artio poll thread instead of dropping |
| `bridge.overflow-block-timeout-ms` | `1000` | how long `BLOCK` holds it; keep well under the heartbeat |
| `bridge.flush-timeout-ms` | `10000` | the drain-and-flush budget at shutdown, and the AMPS logon timeout |
| `bridge.reconnect-initial-backoff-ms` | `250` | first delay after an AMPS disconnect |
| `bridge.reconnect-max-backoff-ms` | `30000` | the ceiling it doubles up to |
| `bridge.guaranteed-publishing` | `false` | install a client-side publish store (in memory; does not survive this process) |
| `bridge.idle-strategy` | `BACKOFF` | how the publisher agent idles when the ring is empty |
| `bridge.stats-log-interval-ms` | `5000` | how often `StatsLogger` prints; **`0` disables it** |
| `bridge.route[N].msg-type` | *(see below)* | the `MsgType(35)` this rule matches |
| `bridge.route[N].topic` | | the AMPS topic to publish to |
| `bridge.route[N].required-tag` | `0` | a tag that must be present before the rule fires |

Leave `bridge.route` unset and you get `BridgeConfig.DEFAULT_ROUTES`:

| Message | Topic | Guard |
| --- | --- | --- |
| every application message | `fix.raw` | none — no SOW key, so nothing can be collapsed |
| `35=D` / `35=G` / `35=F` | `fix.orders` | must carry tag **11** (`ClOrdID`) |
| `35=8` | `fix.execs` | must carry tag **17** (`ExecID`) |
| `35=8` | `fix.order.state` | must carry tag **37** (`OrderID`) |

The guard is a **correctness requirement**, not a precaution: AMPS 5.3.5.135 accepts a SOW publish
that lacks the topic's key and silently stores every such message under one shared record.

`bridge.route` is **all-or-nothing**, exactly as in `BridgeMain`: define one rule and it replaces the
whole set. Spring binds a collection from the single highest-precedence source that has any element
of it, so an override file with two rules yields two rules — not two plus the five it did not
mention. In YAML, quote a numeric message type:

```yaml
bridge:
  route:
    - msg-type: D
      topic: fix.orders
      required-tag: 11
    - msg-type: "8"          # unquoted this is the integer 8, and the binder will say so
      topic: fix.execs
      required-tag: 17
```

### One configuration, two entry points

`artio-amps-bridge/bridge.properties` configures **this** application unchanged:

```bash
java … -jar artio-spring-boot/build/libs/artio-spring-boot-1.0.0.jar \
    --spring.config.location=optional:classpath:/,file:artio-amps-bridge/bridge.properties
```

Almost every key matches already, `bridge.route[N]` included — the properties record calls its list
component `route`, not `routes`, precisely so that no translation is needed. Five keys do differ,
because `BridgeMain` groups them and `BridgeProperties` is flat, and
[`BridgeMainPropertyAliases`](src/main/java/com/demo/artio/boot/BridgeMainPropertyAliases.java) (an
`EnvironmentPostProcessor`, registered in `META-INF/spring.factories`) translates them:

| `bridge.properties` | `BridgeProperties` |
| --- | --- |
| `bridge.amps.uri` | `bridge.uri` |
| `bridge.amps.clientName` | `bridge.client-name` |
| `bridge.amps.guaranteedPublishing` | `bridge.guaranteed-publishing` |
| `bridge.reconnect.initialBackoffMs` | `bridge.reconnect-initial-backoff-ms` |
| `bridge.reconnect.maxBackoffMs` | `bridge.reconnect-max-backoff-ms` |

Without the translation those five would not fail — they would *silently* fall back to their
defaults, so a bridge pointed at a remote AMPS by an existing file would come up cheerfully
connected to `localhost`. A translated key is inserted immediately before the source it came from,
so it keeps that source's precedence exactly: it still beats `application.yml` and still loses to
`--bridge.uri` on the command line. `BridgeMainPropertyAliasesTest` binds the real shipped file and
compares the result with `BridgeConfig.fromProperties` of the same bytes, so this table cannot go
stale quietly.

### Profiles

[`application-initiator.yml`](src/main/resources/application-initiator.yml) turns the bridge round to
dial out:

```bash
./gradlew :artio-spring-boot:bootRun -Dspring.profiles.active=initiator
java … -jar …/artio-spring-boot-1.0.0.jar --spring.profiles.active=initiator

# and the venue for it to call:
./gradlew :quickfixj-counterparty:run \
    --args="acceptor --port 9881 --version FIX.4.2 --sender QFJ --target ARTIO"
```

An initiator's `start()` does not return until the logon exchange completes, so a counterparty that
is not there fails the context refresh after `artio.logon-timeout-ms`. That is the right answer: an
initiator that cannot log on has nothing to do.

### Validation

`ArtioProperties` and `BridgeProperties` are `@Validated` records, so a bad value fails the context
refresh **with the key named**, before a socket is opened:

```
Failed to bind properties under 'artio' to com.demo.artio.boot.ArtioProperties
    Property: artio.port
    Value: "70000"
    Reason: must be less than or equal to 65535
```

Two layers, on purpose. Jakarta constraints catch what a field can express; `toFixEngineConfig()` and
`toBridgeConfig()` then hand the values to `FixEngineConfig` and `BridgeConfig`, whose constructors
catch what it cannot — the two CompIDs must differ, the ring buffer must be a power of two, a bridge
with no default topic and no routes is refused.

## Tests

```bash
./gradlew :artio-spring-boot:test              # 30 tests, ~3 s, no container, no Artio, no AMPS
./gradlew :artio-spring-boot:integrationTest   # 2 tests, ~30 s, needs podman and the AMPS image
./gradlew :artio-spring-boot:build             # both (check depends on integrationTest)
```

`test` — property binding from a profile YAML including a custom route list; validation failures by
key name; the shipped `bridge.properties` binding to exactly what `BridgeMain` builds from it; the
two lifecycle phases producing the required start and stop order under a real
`DefaultLifecycleProcessor`; a real `AmpsFixPublisher` over the bridge's `InMemoryPublishPort` being
started, closed and flushed by its adapter; the `FixMessageSink` ambiguity that `@Primary` resolves;
the stats logger registering one task, or none at interval 0.

`integrationTest` — the whole flow twice, against a throwaway AMPS from
[`:amps-test-harness`](../amps-test-harness):

* `SpringApplicationOrderFlowIT` runs a `SpringApplication` in the test JVM. Beyond the SOW
  assertions it plants a third `SmartLifecycle` at phase 0 — between the publisher's and the
  runtime's — which records what was still running when Spring reached it. The engine must already
  be closed and the publisher must not be: the ordering as a test failure rather than as a log line
  someone has to read.
* `BootJarSubprocessIT` runs `bootJar`'s output as `java -jar` in another JVM, kills it with SIGTERM
  and asserts on what the dying process flushed and on the order its log says it did it in.

Both skip with a reason when podman or the AMPS image is missing (`AmpsAssumptions.assumeAvailable`),
and `AMPS_IT=false` skips them outright.

## What to watch in the logs

```
BridgePublisherLifecycle   amps publisher started: tcp://localhost:9007/amps/fix -> [* -> fix.raw, 35=D -> …]
ArtioRuntimeLifecycle      artio runtime started: acceptor listening bridge-acceptor-1 FIX.4.2 on 0.0.0.0:9880 as ARTIO->QFJ
ArtioBridgeApplication     Started ArtioBridgeApplication in 0.847 seconds (process running for 0.931)
StatsLogger                stats logging every 5000ms
```

Those four, in that order, mean the bridge is up: publisher first, engine second. Then, per interval:

```
StatsLogger  stats: accepted=7 published=10 pending=0 dropped=0 unroutable=0 errors=0 lost=0
                    bytes=1504 ring=0/4194304 connected [fix.raw={published=5}, fix.orders={published=3}, …]
StatsLogger  sessions: [acceptor[FIX.4.2 ARTIO<->QFJ id=1]]
```

* `accepted` counts what Artio handed the sink; `published` counts AMPS publishes, and is higher
  because one message goes to several topics.
* `dropped` non-zero means the ring buffer filled — AMPS is slower than the session. `unroutable`
  non-zero means a message matched a rule but lacked its topic's SOW key; it is still on `fix.raw`.
* `DISCONNECTED` instead of `connected`, with `lost` climbing, means AMPS is away and the publisher
  is backing off. The FIX session is unaffected, which is the design.
* `sessions: none logged on` with the acceptor started is normal until a counterparty connects.

And on shutdown, **these two lines in this order** are the thing to check:

```
ArtioRuntimeLifecycle      artio runtime closed
BridgePublisherLifecycle   amps publisher drained and flushed: accepted=7 published=10 pending=0 dropped=0 …
```

`pending=0` and `dropped=0` on the second line mean the ring buffer was empty and `publishFlush`
returned: everything the session sent is in AMPS. The reverse order, or a missing second line, means
the shutdown was cut short and the tail of the session was lost.

## Using it from code

The two properties records and the lifecycle adapters are ordinary public classes; a larger
application can `@Import(BridgeConfiguration.class)` and add its own beans. Two seams are already
open:

* declare a `SessionListener` bean and the runtime picks it up (`ObjectProvider`), for logon, logout
  and disconnect callbacks;
* `AmpsFixPublisher`, `ArtioRuntime` and `BridgeStats` are all injectable, so an actuator endpoint or
  a Micrometer binder over `publisher.stats()` is a few lines. Neither is here on purpose — see
  `docs/04` on why Actuator was left out.

Do **not** add a `destroyMethod` or a `@PreDestroy` to either object, and do not call `close()` from
application code. The lifecycle adapters own them, and the ordering is the whole point.
