# Can the Artio engine and the AMPS bridge run as a Spring Boot application?

The fourth question in `TODO.md`, answered by building it. The module is
[`artio-spring-boot`](../artio-spring-boot); this document is why it is shaped the way it is, what
Spring costs, and what it does not touch.

Everything measured here was measured on 2026-09-07 on the machine described in
[`docs/00` section 2](00-implementation-plan.md) — Apple Silicon, Corretto 21, AMPS 5.3.5.135 in
podman under amd64 emulation.

---

## 1. The answer

**Yes, and with less friction than the question implies.** The same `ArtioRuntime` and the same
`AmpsFixPublisher` run unmodified under Spring Boot 3.5.9; the fat jar works; SIGTERM flushes; and
the message path is byte-for-byte the one in [`docs/03`](03-artio-amps-bridge-design.md), because
Spring is nowhere near it.

Five constraints, and one of them is the only interesting one:

1. **Spring must not be given the threads.** Artio and the bridge own their agent threads; Spring's
   job is to call `start()` and `close()` at the right moments and otherwise stay out of the way
   (section 2).
2. **The start and stop ORDER is load-bearing**, and expressing it is the one thing that needs real
   care. Two `SmartLifecycle` phases do it; `@Bean(destroyMethod = …)` cannot (section 3).
3. **No servlet stack.** `spring-boot-starter`, not `-web`. The only port this process opens is
   Artio's (section 4).
4. **The three module flags are still mandatory**, and a fat jar has nowhere to get them from except
   the command line or `JAVA_TOOL_OPTIONS` (section 5).
5. **Start-up costs about 400 ms more** than the plain-Java `BridgeMain`, all of it before the first
   message. Steady-state throughput, latency and allocation are unchanged (sections 7 and 10).

What Spring buys for that: validated configuration binding with the key names `BridgeMain` already
uses, profiles, an executable jar, and a shutdown ordering that is declared rather than hand-rolled
— `BridgeMain` needed a two-latch shutdown hook and a page of comments to get right
([`docs/03` section 8](03-artio-amps-bridge-design.md)), and got it wrong the first time.

---

## 2. Thread ownership

Spring owns no thread that touches a FIX message. A running acceptor with the bridge attached has 43
threads; 20 of them are the JVM's own (GC, JIT, VM). The rest:

| Thread | Owner | What it does |
| --- | --- | --- |
| `…/aeron [sender,receiver,driver-conductor]` | Aeron | the embedded media driver, in SHARED mode: one thread running three agents |
| `archive-conductor` | Aeron Archive | Artio's replay log |
| `async-task-executor-0 <aeron dir>` | Aeron Archive | the archive's background tasks. Named misleadingly like a Spring pool; it is not one — Spring Boot's `applicationTaskExecutor` would be `task-N`, and it is never created here |
| `aeron-client-conductor` | Aeron | client-side conductor |
| `bridge-acceptor-1-Framer` | Artio engine | the socket loop: accepts connections, frames FIX |
| `[…-Indexer,…-Indexer,…-Replayer]` | Artio engine | sequence-number indices and replay |
| `[…-Error Printer,DuplicateEngineChecker]` | Artio engine | housekeeping |
| `bridge-acceptor-1-library` | `ArtioRuntime` | **the poll thread**: the only one that may touch `FixLibrary` or a `Session`, and the one that calls the sink |
| `amps-publisher` | `AmpsFixPublisher` | drains the ring buffer and publishes |
| `scheduling-1` | Spring | `StatsLogger`, one line every 5 s |
| `keep-alive` | Spring | a non-daemon thread so the process does not exit |

**Spring contributes exactly two threads, and neither carries a message.** Both are idle between
five-second intervals.

The two that matter are `bridge-acceptor-1-library` and `amps-publisher`, and both are created by
Agrona `AgentRunner`s inside the objects Spring merely holds. Spring's `SmartLifecycle` contract is
"start me, stop me", which is exactly the API those objects already had. There is no
`TaskExecutor` in the path, no `@Async`, no `@Scheduled` on anything that carries a message.

**`spring.main.keep-alive=true`** is set in `ArtioBridgeApplication.main`, not in `application.yml`.
With no web server there is nothing to join, and while Artio's and the bridge's agent threads are
non-daemon and *would* keep the JVM alive, relying on that makes "the process exits immediately"
a possible consequence of a change three modules away. Setting it in `main` rather than in the YAML
keeps it out of `@SpringBootTest` contexts, which never call `main` and would otherwise inherit a
non-daemon thread they would have to shut down.

---

## 3. Start-up and shutdown ordering, and why it is the whole design

[`docs/03` section 8](03-artio-amps-bridge-design.md) fixes the order and explains why it is not
interchangeable:

* **Start**: the publisher first. It must be able to accept messages before Artio can deliver any.
* **Stop**: the engine first. After `ArtioRuntime.close()` nothing can write to the ring buffer, so
  `AmpsFixPublisher.close()` can drain what is left and `publishFlush` it. Closing the publisher
  first leaves Artio delivering into a stopped agent and counts the tail of the session as dropped.

Spring's `DefaultLifecycleProcessor` starts phases in **ascending** order and stops them in
**descending** order. Two constants therefore express the entire requirement:

```
 phase                                        start ▼        stop ▲
 ──────────────────────────────────────────────────────────────────────────────
 Integer.MAX_VALUE          Spring's scheduler   4             1   StatsLogger stops
 MAX-1000   ArtioRuntimeLifecycle               3             2   runtime.close()
      0     (free: the IT plants a probe here)
 MIN+1000   BridgePublisherLifecycle            2             3   publisher.close()
 ──────────────────────────────────────────────────────────────────────────────
                              context refresh   1             4   destroySingletons
```

Three details that are not obvious:

**`MAX-1000`, not `MAX`.** `SmartLifecycle.DEFAULT_PHASE` is `Integer.MAX_VALUE`, and that is where
`ScheduledAnnotationBeanPostProcessor` sits. Leaving the top slot alone means the scheduler — and
therefore `StatsLogger` — stops *before* the engine, rather than racing a closing runtime for its
session list.

**`stop(Runnable)` must be synchronous.** The callback is how a `SmartLifecycle` tells Spring its
phase is finished. Running it before `close()` has flushed would let the container move to the next
phase, and then to `System.exit`, with messages still in the ring buffer — the exact failure the
bridge exists to prevent. Both adapters call `stop()` and only then the callback, in a `finally` so
a throwing `close()` still releases the phase instead of costing the whole 30-second shutdown
timeout.

**No `destroyMethod`.** `@Bean` infers `close()` on an `AutoCloseable`, and both objects are one.
That inference is switched off (`@Bean(destroyMethod = "")`) because destruction runs *after* all
the stop phases and in reverse dependency order, **not** in phase order. Leaving it on would give
each object two close paths, one ordered and one not. Both `close()` methods are idempotent, so
nothing would visibly break; it would simply become impossible to tell from the code which path did
the flush.

### It is tested, not asserted

Claiming an ordering in a document is cheap. Three tests make it a property:

* `LifecyclePhaseOrderingTest` drives a real `DefaultLifecycleProcessor` with two recording
  lifecycles carrying the two `PHASE` constants and asserts the sequence
  `start publisher → start runtime → stop runtime → stop publisher`. No Artio, no sockets: the
  ordering is a property of two integers and is checked as one.
* `SpringApplicationOrderFlowIT` plants a third `SmartLifecycle` at phase 0 in the running
  application and records what was alive when Spring reached it. The engine must already be closed
  and the publisher must not be — the ordering observed from inside a real shutdown.
* `BootJarSubprocessIT` reads it out of the log of a separate JVM killed with SIGTERM:
  `artio runtime closed` must appear before `amps publisher drained and flushed`.

Observed, on `kill -TERM` of the fat jar after the five-message scenario:

```
02:00:34.581 INFO  ArtioRuntimeLifecycle      artio runtime closed
02:00:34.591 INFO  BridgePublisherLifecycle   amps publisher drained and flushed:
    accepted=7 published=10 pending=0 dropped=0 unroutable=0 errors=0 lost=0 bytes=1504
    ring=0/4194304 DISCONNECTED [fix.raw={published=5}, fix.orders={published=3}, …]
```

`pending=0`, `dropped=0`, ten publishes for seven accepted messages: everything the session sent
reached AMPS, 10 ms after the signal.

---

## 4. No servlet stack

`spring-boot-starter` without `-web`. `SpringApplication.deduceFromClasspath()` finds neither
`jakarta.servlet.Servlet` nor `ConfigurableWebApplicationContext` and returns
`WebApplicationType.NONE`, so the context is a plain `AnnotationConfigApplicationContext` and the
only port the process opens is Artio's. `ArtioBridgeApplication` also says `.web(NONE)` explicitly
and `application.yml` sets `spring.main.web-application-type: none`, so a dependency that happens to
drag in `spring-web` cannot silently start a web server next to a FIX gateway.

`ContextLoadsWithTheRuntimeDisabledTest` asserts the classpath rather than the intent: both class
names must be absent. That is the thing that actually decides, and it is the assertion that fires
when someone adds Actuator without thinking (section 9).

---

## 5. The JVM flags

Unchanged from [`docs/01` section 12](01-artio-engine-design.md) and not negotiable:

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens   java.base/sun.nio.ch=ALL-UNNAMED
```

The first two are agrona 2.0.1's (`UnsafeApi` reaches `jdk.internal.misc.Unsafe`); the third is
Artio's (`ReceiverEndPoints` reflects on `sun.nio.ch.SelectorImpl.selectedKeys` in a static
initialiser). **Spring changes nothing here** — but it changes where they have to be written, and
one of those places is easy to forget.

| How it runs | Where the flags go |
| --- | --- |
| `./gradlew :artio-spring-boot:bootRun` | `tasks.withType<JavaExec>` in `build.gradle.kts`; `BootRun` extends `JavaExec`, so one block covers it |
| `./gradlew :artio-spring-boot:test` / `integrationTest` | `tasks.withType<Test>` in the same file |
| `java -jar artio-spring-boot-1.0.0.jar` | **the command line, and nowhere else** |
| a container image | `ENV JAVA_TOOL_OPTIONS="--add-opens … --add-exports … --add-opens …"` |
| `bootBuildImage` / Paketo | the same `JAVA_TOOL_OPTIONS`, as a container env var |

The fat jar cannot supply them for itself. A `Launcher-JVM-Args` manifest attribute does not exist;
`JAVA_TOOL_OPTIONS` is the only way to attach them to a `java -jar` invocation you do not control,
and it is what a Dockerfile or a Kubernetes `env:` should set. The failure mode without them is an
`IllegalAccessError` from a static initialiser inside `ArtioRuntime.start()`, which surfaces as an
`ExceptionInInitializerError` in a Spring bean factory method and reads, misleadingly, as a Spring
problem.

---

## 6. The fat jar

`bootJar` is enabled and the plain `jar` is disabled, so `build/libs` holds exactly one artefact —
28.5 MB, of which 27 MB is the 40-odd nested dependency jars. `BootJarSubprocessIT` runs it as a
real subprocess for every `integrationTest`, because "the context refreshes inside a test JVM" is
not the same claim as "the deployable artefact works".

**Classloading.** Spring Boot's `LaunchedClassLoader` reads classes out of `BOOT-INF/lib/*.jar`
without extracting them, which is where a library that assumes a file-based classpath breaks.
Nothing here does: Artio's dictionaries are *generated classes* (`com.demo.artio.fix42.FixDictionaryImpl`),
not XML read at runtime; Aeron and Agrona load no resources; SBE's runtime is pure code, with the
generation done at build time in `:fix-codecs`. Aeron's memory-mapped files live under
`artio.base-directory`, which is a real filesystem path and never inside the jar. Every codec, every
`Encoder`/`Decoder` and the media driver came up under the launcher on the first attempt, and the
integration test has kept doing so since.

**The nested jars are stored, not deflated** — `unzip -v` shows `Stored 0%` for every
`BOOT-INF/lib/*.jar`. That is deliberate on Spring's part (so entries can be located without
inflating the container) and it is why the fat jar is *larger* than the extracted directory rather
than smaller.

**`META-INF/spring.factories` is relocated to the archive root**, not left under
`BOOT-INF/classes/`, while `application.yml` stays in `BOOT-INF/classes/`. Worth knowing because
this module registers an `EnvironmentPostProcessor` there
(`BridgeMainPropertyAliases`, section 8) and a registration that silently stopped working inside the
jar would be invisible. It does work: running the fat jar with
`--spring.config.location=…,file:<a copy of bridge.properties with a changed bridge.amps.uri>`
produced `bridge publishing to tcp://localhost:19099/amps/fix`, which is only possible if the
post-processor ran.

**Start-up, jar versus exploded.** Time from `exec` to the acceptor's port being bound, best of
three, same JVM and same machine:

| | time to bound acceptor |
| --- | --- |
| `BridgeMain`, plain jars on the classpath | **553 ms** (553 / 569 / 860) |
| Spring, extracted with `-Djarmode=tools … extract`, plain jars on the classpath | **888 ms** (888 / 908 / 943) |
| Spring, the fat jar (`java -jar`) | **1008 ms** (1008 / 1017 / 1222) |

So the nested-jar classloader costs about **120 ms** and the Spring container about **330 ms**. If
120 ms of start-up matters, `-Djarmode=tools -jar app.jar extract` gives back most of it and keeps a
single build artefact; nothing else about the application changes.

**SIGTERM.** `Process.destroy()` on the fat jar produces exit code **143** (128 + SIGTERM) after the
shutdown hook has completed. That is the JVM reporting how it was asked to stop, not a failure —
Gradle reports `bootRun` as `finished with non-zero exit value 143` for the same reason, exactly as
`:artio-amps-bridge:run` does. The test asserts `143 or 0` and checks the two shutdown log lines,
which is what actually distinguishes a clean stop from a truncated one.

**One harness trap, recorded because it cost an hour and looked like a product bug.** Draining the
subprocess's output through `process.getInputStream()` on a daemon thread loses the shutdown lines:
they are written from a shutdown hook microseconds before the JVM exits, and the reader reaches
end-of-stream as the process is reaped without them. The test then fails with "the publisher never
reported flushing, so SIGTERM cut the shutdown short" — a false alarm indistinguishable from the real
thing. `ProcessBuilder.redirectOutput(file)` removes the race: the kernel has the bytes whether or
not anything is reading. Any test that asserts on a dying process's last words needs this.

---

## 7. Allocation, and where Spring is not

The hot path is the one in [`docs/03` section 7](03-artio-amps-bridge-design.md), unchanged:
`onMessage` claims ring-buffer space, writes a 24-byte header, copies the bytes and returns —
allocating **nothing** — and the publisher agent drains, routes and publishes out of one reusable
`byte[]`, also allocating nothing in steady state.

Spring appears in that path nowhere. It is not a proxy around the sink, it is not an interceptor, and
`@Configuration(proxyBeanMethods = false)` means even the configuration class is not CGLIB-enhanced.
What Spring allocates it allocates once, during `refresh()`: the bean factory, the bean definitions,
the two bound property records, the reflection metadata, Hibernate Validator's constraint metadata.
That is the 330 ms in section 6 and a few MB of heap that G1 collects on the first young GC. After
`Started ArtioBridgeApplication`, the container is idle: one scheduled task every five seconds, and
nothing else.

The one allocating thing in the delivered path is not Spring's either — `LoggingSink`, composed
ahead of the publisher when `artio.log-messages` is true, builds a printable `String` per message.
It is a demo affordance and `artio.log-messages: false` removes it, exactly as leaving it out of
`BridgeMain`'s `CompositeSink` would.

---

## 8. Configuration: the same keys as `BridgeMain`

`ArtioProperties` and `BridgeProperties` are `@Validated` records bound by constructor injection.
The point of interest is that their key names are deliberately the ones `bridge.properties` already
uses, so **one configuration serves both entry points**.

That falls out for free for the whole `artio.*` block, because Spring's relaxed binding treats
`artio.senderCompId`, `artio.sender-comp-id` and `ARTIO_SENDERCOMPID` as one key. It also falls out
for the routes, but only because of a deliberate naming choice: the list component is called
`route`, not `routes`, so `bridge.route[0].msgType` — what `bridge.properties` writes and what
`BridgeConfig.fromProperties` reads — binds with no translation at all. Naming it `routes` and
aliasing `bridge.route[N]` onto `bridge.routes[N]` would have worked too, and would have meant
rewriting *indexed* keys across property sources, which is precisely where "which source wins" gets
hard to reason about. One spelling is better than two spellings and a regex.

Five keys are left, because `BridgeMain` groups them and the record is flat:

| `bridge.properties` | `BridgeProperties` |
| --- | --- |
| `bridge.amps.uri` | `bridge.uri` |
| `bridge.amps.clientName` | `bridge.client-name` |
| `bridge.amps.guaranteedPublishing` | `bridge.guaranteed-publishing` |
| `bridge.reconnect.initialBackoffMs` | `bridge.reconnect-initial-backoff-ms` |
| `bridge.reconnect.maxBackoffMs` | `bridge.reconnect-max-backoff-ms` |

`BridgeMainPropertyAliases`, an `EnvironmentPostProcessor` at `LOWEST_PRECEDENCE` (so config data is
already loaded), translates them. This is worth the class because the alternative is not an error:
those five would *silently* fall back to their defaults, and a bridge pointed at a remote AMPS by an
existing file would come up cheerfully connected to `localhost`. An alias is inserted in a property
source placed immediately **before** the source it was derived from, so it inherits that source's
standing exactly — it still beats `application.yml` and still loses to `--bridge.uri` on the command
line, which is what the canonical spelling would have done. `BridgeMainPropertyAliasesTest` pins
all three claims, and binds the *real shipped file* to compare the result against
`BridgeConfig.fromProperties` of the same bytes.

Two things learned doing it:

* **`EnvironmentPostProcessor` is still registered through `META-INF/spring.factories` in Spring Boot
  3.x.** The `META-INF/spring/*.imports` mechanism that replaced `spring.factories` in 2.7 covers
  `AutoConfiguration` only. A post-processor listed in an `.imports` file is ignored with no warning
  and no error — the aliases simply never appear, and the only symptom is a property that kept its
  default. This cost one debugging cycle and is the reason `spring.factories` carries a comment.
* **`SpringApplicationBuilder.properties(...)` registers *default* properties**, the
  lowest-precedence source of all, below `application.yml`. An integration test that set
  `artio.port` that way would silently have bound 9880 and passed or failed depending on what else
  was running on the machine. Overrides in tests and on the command line go through
  `run("--artio.port=…")`, which lands in `commandLineArgs`, near the top.

Validation is in two layers on purpose. Jakarta constraints catch what a field can express and name
the offending key at bind time (`artio.port` `must be less than or equal to 65535`);
`toFixEngineConfig()` and `toBridgeConfig()` then hand the values to `FixEngineConfig` and
`BridgeConfig`, whose constructors catch what a field constraint cannot — the two CompIDs must
differ, the ring buffer must be a power of two, a bridge with no default topic and no routes is
refused. `PropertyValidationTest` covers both layers, including two cases that deliberately bind
cleanly and fail in the mapper, so nobody deletes the second layer as redundant.

---

## 9. Observability, and why there is no Actuator

`StatsLogger` is the whole observability surface, and it is deliberately the same one `BridgeMain`
prints, so a Spring deployment and a plain one are read the same way:

```
StatsLogger  stats: accepted=7 published=10 pending=0 dropped=0 unroutable=0 errors=0 lost=0
                    bytes=1504 ring=0/4194304 connected [fix.raw={published=5}, …]
StatsLogger  sessions: [acceptor[FIX.4.2 ARTIO<->QFJ id=1]]
```

It implements `SchedulingConfigurer` rather than carrying a `@Scheduled` annotation, because the
interval is configuration and `0` has to mean *off* — the integration tests set it so their output is
only the flow. `@Scheduled(fixedRateString = "${bridge.stats-log-interval-ms}")` of 0 is not "off",
it is an `IllegalArgumentException` at context refresh; and the obvious workaround
(`@ConditionalOnExpression` over a placeholder) stops working the moment the property is spelled
`bridge.statsLogIntervalMs`, because SpEL placeholder resolution is not relaxed binding. Registering
the task by hand costs three lines and has neither problem.

**Actuator is not here, and the omission is the finding.** `spring-boot-starter-actuator` without
`spring-boot-starter-web` gives you `/actuator` over… nothing: the endpoints exist as beans and there
is no transport. Adding the web starter to expose them puts Tomcat, a servlet container and a second
listening port next to a FIX gateway whose entire performance story is "one thread owns the session
and never blocks". That is a real trade, not a free one, and this module's job was to answer whether
the engine runs under Spring — not to decide someone's operational posture.

Where it would plug in, if the trade is worth making in a given deployment:

* a `HealthIndicator` over `publisher.stats().connected()` and `runtime.isRunning()` — both beans are
  already injectable, and `BridgeStats` is an immutable snapshot safe to read from any thread;
* a `MeterBinder` registering `accepted`, `published`, `dropped`, `unroutable`, `pendingBytes` and
  `lostWhileDisconnected` as gauges over the same snapshot;
* `management.server.port` set to a *different* port from the FIX one, so a liveness probe cannot
  reach the session's socket.

None of that needs a change to this module: `AmpsFixPublisher`, `ArtioRuntime` and `BridgeStats` are
ordinary beans, and `SessionListener` is already an optional bean the runtime picks up for logon,
logout and disconnect callbacks.

---

## 10. Measured: Spring versus `BridgeMain`

Same machine, same JDK, same AMPS container, three runs each.

**Start-up** — `exec` to the acceptor's port being bound:

| | best | runs |
| --- | --- | --- |
| `BridgeMain` (plain classpath) | **553 ms** | 553 / 569 / 860 |
| Spring, extracted jars | 888 ms | 888 / 908 / 943 |
| Spring, fat jar | 1008 ms | 1008 / 1017 / 1222 |

Spring's own report of the same start, from inside the fat jar:
`Started ArtioBridgeApplication in 0.976 seconds (process running for 1.131)`. From `bootRun`, on
exploded classes with `-XX:TieredStopAtLevel=1`: `in 0.847 seconds (process running for 0.931)`.

**Logon latency** — a QuickFIX/J counterparty process from `exec` to a completed logon, against each
acceptor in turn:

| against | best | runs |
| --- | --- | --- |
| `BridgeMain` | 1265 ms | 1265 / 1271 / 1414 |
| Spring, fat jar | **1255 ms** | 1255 / 1270 / 1283 |

Indistinguishable, and that is the expected result rather than a surprise: the logon is handled by
the same `ArtioRuntime` on the same poll thread, and the measurement is dominated by QuickFIX/J's own
start-up and reconnect timer. The number is here so that "Spring costs nothing after start-up" is a
measurement and not an assurance.

**Publishing** is likewise unchanged, and is not re-measured here: the sink, the ring buffer and the
publisher agent are the same objects, so [`docs/03` section 9](03-artio-amps-bridge-design.md)'s
0.05 µs sink write and ~7 900 msg/s end-to-end stand. The integration suite confirms the shape rather
than the rate — seven messages accepted, ten published, `dropped=0`, `unroutable=0`, five SOW records
keyed by `ClOrdID`, five on the `fix.raw` journal — identically for the in-process context and for
the fat jar.

---

## 11. Gotchas

**11.1 `AmpsFixPublisher` *is* a `FixMessageSink`, so the context has two of them.** The design is
that the publisher is a sink and the engine gets a `CompositeSink` containing it — which means the
engine's injection point is ambiguous the moment the bridge is enabled, and the application does not
start:

```
Parameter 1 of method artioRuntime required a single bean, but 2 were found:
    - ampsFixPublisher
    - fixMessageSink
```

`@Primary` on `fixMessageSink` resolves it. This was found by running the fat jar, not by a test:
every test at the time disabled the bridge, which is the one configuration where the ambiguity does
not exist. `SinkWiringWithThePublisherEnabledTest` now covers it in the cheapest configuration that
reproduces it — bridge on, engine off, pointed at a port confirmed free.

**11.2 An unreachable AMPS is deliberately not a start-up failure.** `AmpsFixPublisher.start()` logs
the refused connection and the port keeps retrying with back-off. A FIX gateway that refused to come
up because the message broker was briefly down would be the more surprising behaviour: its first duty
is to stay logged on to its counterparty, and `fix.raw` is journalled so nothing published later is
lost. An *initiator*, by contrast, does fail the context refresh if it cannot log on, because an
initiator that cannot log on has nothing to do.

**11.3 `ContextClosedEvent` fires before the lifecycle stop phases.** `AbstractApplicationContext.doClose()`
publishes the event, *then* calls `lifecycleProcessor.onClose()`, *then* destroys beans. A listener
that assumes "context closed" means "everything is stopped" will see a running engine and a running
publisher. Both adapters therefore key off their own object's state, not off the event.

**11.4 `bridge.route` is all-or-nothing, and Spring makes that work by accident of a good rule.**
Spring binds a collection from the single highest-precedence property source that has any element of
it, rather than merging across sources. That is exactly the `BridgeMain` semantics — an override file
with two rules yields two rules, not two plus the five it did not mention — and
`PropertyBindingTest` pins it.

**11.5 Quote numeric `MsgType`s in YAML.** `msg-type: 8` is the integer 8 and the binder says so;
`msg-type: "8"` is an execution report. `bridge.properties` has no such problem, which is one small
argument for keeping the properties file as the canonical example.

**11.6 The `slf4j-simple` leak.** `:artio-amps-bridge` declares `runtimeOnly(libs.slf4j.simple)`
because it has a `main` of its own, and a `runtimeOnly` dependency of a project dependency **is** on
the consumer's runtime classpath. Without an exclude, `slf4j-simple` and Spring Boot's Logback would
both be present, SLF4J would bind one arbitrarily with a "multiple bindings" warning, and half of
`logback-spring.xml` would silently do nothing. This module excludes it
(`configurations.configureEach { exclude(group = "org.slf4j", module = "slf4j-simple") }`); verify
with `./gradlew :artio-spring-boot:dependencies --configuration runtimeClasspath | grep slf4j`,
which should show `slf4j-api`, `logback-classic`, `log4j-to-slf4j` and `jul-to-slf4j`, and no
`slf4j-simple`. The clean fix is `:artio-amps-bridge` moving its binding to its `application`
runtime only; that is a change to another module and is left to the review phase.

---

## 12. Verdict

The FIX engine and the AMPS bridge run under Spring Boot with no change to either, and the parts of
Spring that would have been a problem — proxies, a servlet container, a task executor between the
socket and the sink — are simply not used. What Spring is used for is the part `BridgeMain` found
hardest: getting two components started and stopped in the right order, and being told which
configuration key was wrong before anything opens a socket.

The cost is 330 ms of start-up for the container, 120 ms more if the fat jar is run as a fat jar, and
a dependency on remembering that the three module flags belong in `JAVA_TOOL_OPTIONS` when someone
containerises it. On the message path the cost is zero, and that is measured rather than assumed.
