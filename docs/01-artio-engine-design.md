# The Artio engine as built

How `:artio-engine` puts an Artio FIX engine inside one process, what the sink contract is, and
what had to be discovered to get FIX 4.2 working. Everything below was measured or observed on
this machine (Corretto 21, Apple Silicon) on 2026-09-06 while the 27 integration tests in
`artio-engine/src/integrationTest` were being written; where a number appears, it came from a run,
not from a datasheet.

Companion documents: `docs/00-implementation-plan.md` section 4.3 (the brief),
`docs/02-quickfixj-to-artio-dictionary.md` (where the FIX 4.2 dictionary comes from).

---

## 1. What this module is

One class does the work: `com.demo.artio.engine.ArtioRuntime`, an `AutoCloseable` that owns

* an **embedded Aeron media driver**,
* an **Aeron archive** (Artio requires one; it is not optional),
* an Artio **`FixEngine`** — the process that owns TCP connections and the message log,
* an Artio **`FixLibrary`** — the API through which application code sees sessions,
* and **one thread** that polls the library.

Everything else in the module is either configuration (`FixEngineConfig`, `FixVersion`,
`EngineMode`, `IdleStrategyType`), the seam downstream modules plug into (`FixMessageSink`,
`FixMessageView`, `SessionKey`, `SessionListener`), or ready-made sinks (`LoggingSink`,
`CompositeSink`, `CountingSink`).

The module deliberately does **not** know about AMPS, Spring, or QuickFIX/J. Its only outward
dependency is `:fix-codecs`.

---

## 2. Topology

```
                        one JVM, one ArtioRuntime
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │                                                                              │
 │  caller thread                        library poll thread (Agrona AgentRunner)│
 │  ─────────────                        ───────────────────────────────────────│
 │  start()                                                                     │
 │  close()                              while (running)                        │
 │  send(Encoder) ──┐                        library.poll(10) ──► SessionHandler │
 │  submit(action) ─┤                        drain(commandQueue)        │        │
 │                  │                            │                     ▼        │
 │                  └──► ManyToOneConcurrent ────┘             FixMessageView    │
 │                       LinkedQueue<Command>                  (flyweight over   │
 │                                     │                        Aeron's buffer)  │
 │                                     ▼                               │        │
 │                             Session.trySend(encoder)                ▼        │
 │                                                              FixMessageSink   │
 │                                                                              │
 │  ┌────────────────────┐   IPC (aeron:ipc)   ┌───────────────────────────────┐│
 │  │ FixLibrary         │◄───────────────────►│ FixEngine                     ││
 │  │  session ownership │                     │  Framer  (TCP, sessions)      ││
 │  │  session state m/c │                     │  Indexer/Replayer (message log)││
 │  └────────────────────┘                     │  Error Printer, DuplicateEngine││
 │                                             └───────────────────────────────┘│
 │                        ▲                                    ▲                │
 │                        │  Aeron IPC streams                 │                │
 │                        ▼                                    ▼                │
 │  ┌──────────────────────────────────────────────────────────────────────────┐│
 │  │ ArchivingMediaDriver                                                     ││
 │  │   MediaDriver (ThreadingMode.SHARED)   +   Archive (SHARED, IPC control)  ││
 │  │   <base>/artio-<id>/aeron                  <base>/artio-<id>/archive      ││
 │  └──────────────────────────────────────────────────────────────────────────┘│
 └──────────────────────────────────────────────────────────────────────────────┘
                                    │ TCP
                                    ▼
                            the other FIX engine
```

### 2.1 Threads

Nine threads per runtime, observed by name after `ArtioRuntime.launch(...)` returned
(`<id>` is `ArtioRuntime.runtimeId()`, e.g. `probe-acceptor-1`):

| Thread name | Owner | What it does |
| --- | --- | --- |
| `<aeronDir> [sender,receiver,driver-conductor]` | Aeron | the media driver, `ThreadingMode.SHARED` |
| `aeron-client-conductor` | Aeron | the archive's Aeron client |
| `[[<id>-Error Printer],aeron-client-conductor]` | Aeron + Artio | Artio's Aeron client, sharing a thread with its error printer |
| `archive-conductor` | Aeron Archive | `ArchiveThreadingMode.SHARED` |
| `async-task-executor-0 <aeronDir>` | Aeron Archive | archive background tasks |
| `<id>-Framer` | Artio | TCP accept/connect, session state, the acceptor's bind |
| `[<id>-Indexer,<id>-Indexer,<id>-Replayer]` | Artio | the message log: sequence-number index, replay |
| `[<id>-Error Printer,DuplicateEngineChecker]` | Artio | error reporting and the duplicate-engine guard |
| `<id>-library` | this module | `FixLibrary.poll` and the command queue |

`ArtioRuntime` sets `CommonConfiguration.agentNamePrefix(runtimeId + "-")` on both the engine and
the library configuration, which is why every Artio thread is identifiable. Aeron's are not: agrona's
`AgentRunner.startOnThread(runner, threadFactory)` calls `thread.setName(agent.roleName())` *after*
the factory has created the thread, so a custom `ThreadFactory` cannot name an Aeron agent thread.
`ArtioRuntimeShutdownIT` therefore identifies leaked Aeron threads by a word list applied only to
threads that did not exist before the runtime started.

### 2.2 Why the Aeron archive is configured the way it is

Artio's `FixEngine` connects an `AeronArchive` client unconditionally when message logging is on
(`FixEngine.java:299`), so the archive is a hard requirement, not a feature. The awkward part is
that its defaults are **fixed UDP ports** — control channel `localhost:8010`, control response
`localhost:8020`, recording events `localhost:8030` — which collide the moment a second runtime
starts in the same JVM. `ArtioToArtioIT` starts two.

The fix, in `ArtioRuntime.launchMediaDriver` / `engineConfiguration`:

```java
new Archive.Context()
    .aeronDirectoryName(<unique>)          // its own driver, so nothing is shared
    .archiveDirectoryName(<unique>)
    .threadingMode(ArchiveThreadingMode.SHARED)
    .deleteArchiveOnStart(true)
    .recordingEventsEnabled(false)         // nothing consumes them; one less UDP endpoint
    .controlChannelEnabled(false)          // no remote control: IPC only
    .replicationChannel("aeron:udp?endpoint=localhost:0")
    .archiveClientContext(new AeronArchive.Context().controlResponseChannel("aeron:ipc"))

engineConfiguration.aeronArchiveContext()
    .aeronDirectoryName(<unique>)
    .controlRequestChannel(archiveContext.localControlChannel())   // "aeron:ipc?term-length=64k"
    .controlRequestStreamId(archiveContext.localControlStreamId())
    .controlResponseChannel("aeron:ipc");
```

With `controlChannelEnabled(false)` the archive is reachable only over its **local (IPC) control
channel**, which lives inside that runtime's own Aeron directory — so two runtimes cannot see each
other at all, and no port is bound. `archiveClientContext(...)` is required, not optional: without
it `Archive.Context.conclude()` fails with

```
ERROR - Archive.Context.archiveClientContext.controlResponseChannel must be set
        if Archive.Context.controlChannelEnabled is false
```

### 2.3 Directories

Each runtime creates `<baseDirectory>/artio-<runtimeId>/` holding `aeron/`, `archive/`, `logs/`,
`engineCounters` and `libraryCounters`. `runtimeId` is
`<name>-<mode>-<counter>`, so two runtimes in one JVM never collide even with the same `name`.

`FixEngineConfig.aeronDirectory` and `logFileDir` override the derived paths when something outside
the process needs to find them; leaving them null (the default) is the normal case.

The integration suite puts `baseDirectory` under `java.io.tmpdir`, not `build/`: Aeron leaves a
mapped file behind if a JVM is killed mid-test, and nothing that survives a crash should land inside
the repository. `-Dartio.it.dir=...` overrides it when you want to look at what was written.

---

## 3. Configuration reference

`FixEngineConfig` is a validated record; build it with `FixEngineConfig.acceptor()` /
`.initiator()` / `.builder(mode)`. Validation happens in the compact constructor, so a bad value
fails at `build()` with a message naming the field rather than inside Artio at start-up.

| Component | Default | Meaning |
| --- | --- | --- |
| `name` | `artio` | label for thread names, directories, logs. Need not be unique. |
| `mode` | (required) | `ACCEPTOR` binds; `INITIATOR` connects out. |
| `host` / `port` | `localhost` / (required) | bind address, or remote address. |
| `senderCompId` / `targetCompId` | `ARTIO` / `COUNTERPARTY` | must differ; must not contain SOH or `=`. |
| `fixVersion` | `FIX42` | selects the generated dictionary. |
| `heartbeatIntervalSec` | 10 | `HeartBtInt(108)`. |
| `baseDirectory` | `java.io.tmpdir` | parent of the derived directories. |
| `aeronDirectory` / `logFileDir` | null (derived) | explicit overrides. |
| `resetSeqNumsOnLogon` | true | `ResetSeqNumFlag(141)=Y`; initiator only. |
| `libraryId` | `AUTO_LIBRARY_ID` (0) | fixed `FixLibrary` id, or let Artio pick. |
| `idleStrategy` | `BACKOFF` | poll thread idling; `BUSY_SPIN` and `SLEEPING` also available. |
| `logonTimeoutMs` | 20 000 | how long `start()` waits for each start-up phase. |
| `replyTimeoutMs` | 10 000 | Artio's engine/library reply timeout, and the send back-pressure deadline. |
| `shutdownTimeoutMs` | 5 000 | how long `close()` waits for a graceful logout. |
| `deleteDirectoriesOnClose` | true | delete `<base>/artio-<id>/` on close. |
| `authenticationStrategy` | `AuthenticationStrategy.none()` | the acceptor's logon hook; the default accepts everyone. |

`authenticationStrategy` is the extension point for CompID or credential checks. Note the FIX 4.2
credential gotcha in section 9.3 before designing anything around passwords.

---

## 4. Acceptor and initiator

The two modes differ in more than a flag.

### 4.1 Acceptor

```java
configuration
    .bindTo(host, port)                                    // also sets bindAtStartup(true)
    .initialAcceptedSessionOwner(InitialAcceptedSessionOwner.SOLE_LIBRARY)
    .acceptorfixDictionary(config.fixVersion().dictionary())
    .authenticationStrategy(config.authenticationStrategy());
```

* **`acceptorfixDictionary`** is what makes FIX 4.2 possible (section 8).
* **`SOLE_LIBRARY`** is the interesting choice. Artio's default is
  `InitialAcceptedSessionOwner.ENGINE`: an accepted session belongs to the engine, and a library has
  to ask for it with `library.requestSession(...)` from a `SessionExistsHandler` — which is what
  Artio's own `AcquiringSessionExistsHandler` sample does. That works, but it is asynchronous and the
  `Logon` has already been consumed by the engine by the time the library owns the session.
  In `SOLE_LIBRARY` mode the single connected library owns accepted sessions from the start, and the
  `Logon` reaches the application (section 6).
* `SOLE_LIBRARY` also makes `start()` **deterministic**. Look at Artio's `Framer.onLibraryConnect`:
  it calls `soleLibraryModeBind()` — which is what actually binds the `ServerSocketChannel` —
  *before* `saveControlNotification(...)`, the message that makes `FixLibrary.isConnected()` return
  true. So by the time `ArtioRuntime.start()` sees `library.isConnected()`, the port is already
  bound and a counterparty can connect. No sleep, no retry loop, no probe connection.
* The cost of `SOLE_LIBRARY` is in the name: exactly one library. A second one is refused with an
  error rather than sharing the sessions. For an embedded engine — which is what this module is —
  that is the right trade.

An acceptor never measures a logon latency: it did not drive the exchange.
`ArtioRuntime.logonLatency()` is empty for an acceptor, and `ArtioAcceptorFromQuickfixjIT` asserts it.

### 4.2 Initiator

```java
SessionConfiguration.builder()
    .address(host, port)
    .senderCompId(...).targetCompId(...)
    .fixDictionary(config.fixVersion().dictionary())   // on the SESSION, not the engine
    .resetSeqNum(config.resetSeqNumsOnLogon())
    .timeoutInMs(config.logonTimeoutMs())
    .build();
```

`start()` then, **on the calling thread**:

1. polls the library until `isConnected()`;
2. calls `library.initiate(sessionConfiguration)`, retrying while it returns `null` (Artio returns
   null when the request could not be enqueued — it is a duty-cycle API, not a failure);
3. polls until the `Reply<Session>` stops executing, and fails loudly if it did not complete;
4. polls until `session.isActive()`, i.e. the logon exchange has finished;
5. records the elapsed time as `logonLatency()`.

Only then does the `AgentRunner` start. That is what makes it safe to run start-up on the caller's
thread: at no point are two threads polling the same library.

`ArtioRuntime.launch(...)` therefore returns with the session already logged on, which is why the
initiator integration tests have nothing to wait for after `launch`.

---

## 5. Session lifecycle

`SessionListener` has five callbacks, all invoked on the poll thread, all with no-op defaults:

| Callback | Fired when |
| --- | --- |
| `onSessionAcquired(SessionKey)` | the `FixLibrary` takes ownership — acceptor: logon accepted; initiator: `initiate` completed. Not yet logged on. |
| `onLogon(SessionKey)` | an inbound `Logon(35=A)` was seen. |
| `onLogout(SessionKey)` | an inbound `Logout(35=5)` was seen. |
| `onDisconnect(SessionKey, reason)` | Artio's `SessionHandler.onDisconnect`; `reason` is the `DisconnectReason` name. |
| `onTimeout(SessionKey)` | the library stopped polling for too long and the engine took the session back. |
| `onError(Throwable)` | any Aeron or Artio agent raised an error, or a sink threw. |

Observed order for an Artio acceptor with a QuickFIX/J initiator (asserted in
`ArtioAcceptorFromQuickfixjIT`):

```
acquired:QFJ   →   logon:QFJ   →   ... application messages ...   →   disconnect:QFJ:...
```

`SessionKey` is resolved once when the session is acquired, from Artio's `CompositeKey`, and is an
immutable record a sink may keep. Its two CompIDs are named from **this engine's** point of view
(`localCompId` / `remoteCompId`) rather than from any one message's, because a session carries
traffic both ways; `FixMessageView.senderCompId()` and `targetCompId()` re-project them onto the
received message's tags 49 and 56.

---

## 6. What Artio delivers to a `SessionHandler`

**Everything inbound, admin included.** Artio's `SessionSubscriber.onMessage` (artio-core 0.168)
runs the `SessionParser` first and then calls `handler.onMessage(...)` for the same message,
unconditionally, for every `MessageStatus`. There is no admin/application filter anywhere on that
path.

Confirmed empirically: `ArtioAcceptorFromQuickfixjIT.theSinkAlsoSeesTheCounterpartysLogonAndReportsItAsAnAdminMessage`
asserts that a `Logon(35=A)` with `MsgSeqNum=1` arrives at the sink with `isAdmin() == true`, on both
FIX 4.2 and FIX 4.4. Heartbeats arrive the same way once the interval elapses.

Two qualifications that matter to the bridge:

* **Inbound only.** Messages this engine *sends* — including the ones `ArtioRuntime.send` writes and
  every heartbeat and logout the session layer generates — never reach `onMessage`. A sink sees one
  side of the conversation. If the bridge has to publish outbound traffic too, that needs
  `EngineConfiguration.messageTimingHandler` or an archive scan, not the `SessionHandler`.
* **The `Logon` is only delivered because of `SOLE_LIBRARY`.** With Artio's default
  `ENGINE` ownership the engine consumes the logon before any library owns the session, and a
  library acquiring with `requestSession(..., NO_MESSAGE_REPLAY, ...)` never sees it.

`FixMessageView.isAdmin()` is a set lookup on Artio's packed message-type long — the seven session
types `0 1 2 3 4 5 A` are packed once in a static `LongHashSet` — so filtering admin traffic out
costs nothing.

---

## 7. The sink contract

```java
@FunctionalInterface
public interface FixMessageSink
{
    void onMessage(FixMessageView message);
}
```

`FixMessageView` is a **single reusable flyweight** owned by the runtime. It is pointed at Aeron's
own log buffer; nothing has been copied to the heap, and nothing needs to be.

What a sink **must not** do:

* **Keep the view, or `buffer()`, beyond the callback.** Artio reuses the buffer for the next
  message the moment `onMessage` returns. `ArtioRuntime` calls `view.unwrap()` in a `finally` block
  so a sink that ignores this reads an empty view rather than someone else's message — a bug that
  fails loudly instead of producing plausible garbage.
* **Block.** The callback runs on the library poll thread. While it is running, no other session on
  this library is served, no heartbeat is sent, and Aeron's log buffer is not being drained. Anything
  slower than "copy the bytes into a ring buffer" belongs on another thread — which is exactly what
  `:artio-amps-bridge` will do.
* **Throw.** The runtime catches `RuntimeException` and `Error` from the sink and routes them to
  `SessionListener.onError`, so a broken sink cannot kill the poll thread; but it also cannot report
  the message as failed, and Artio will not redeliver it.

What a sink **may** do, allocation-free:

| Call | Cost |
| --- | --- |
| `buffer()`, `offset()`, `length()`, `msgType()`, `sessionId()`, `sequenceNumber()`, `timestampNs()`, `isAdmin()`, `isValid()` | free |
| `msgTypeAsString()` | one `String` the first time each distinct type is seen, then cached in a `Long2ObjectHashMap` and returned by identity |
| `sessionKey()`, `senderCompId()`, `targetCompId()` | free; resolved once per session |
| `asciiBuffer()` | free; a reused `MutableAsciiBuffer` wrapping this message at offset 0, ready for `decoder.decode(view.asciiBuffer(), 0, view.length())` |
| `copyTo(byte[], int)` | a bounded copy, no allocation — this is how a sink keeps a message |
| `toByteArray()`, `toFixString()`, `toPrintableString()` | allocate; diagnostics only |

Provided sinks: `LoggingSink` (SLF4J, SOH rendered as `|`), `CompositeSink` (fan-out; use
`CompositeSink.of(...)` which collapses the 0- and 1-delegate cases), `CountingSink` (counts and
keeps copies, bounded; the sink every integration test uses).

---

## 8. Sending from the Artio side

```java
CompletableFuture<Long> send(Encoder encoder);
CompletableFuture<Long> send(long sessionId, Encoder encoder);
long sendAndAwait(Encoder encoder, Duration timeout);
CompletableFuture<Void> submit(Consumer<FixLibrary> action);
```

Artio's `FixLibrary` and every `Session` it owns belong to the poll thread **and only** to the poll
thread. `send` therefore does not call `Session.trySend` on the caller's thread: it puts a command on
an Agrona `ManyToOneConcurrentLinkedQueue` that the poll agent drains in the same `doWork` cycle as
`library.poll`, and returns a future completed with the Aeron position.

Back-pressure is handled where it happens. `Session.trySend` returns a negative position when the
publication is back-pressured (`Pressure.isBackPressured`); the agent keeps the command as
`pendingSend` and retries it ahead of the rest of the queue on the next cycle, until
`replyTimeoutMs` has passed, at which point the future fails. The caller never spins.

Two consequences for callers:

* **Do not touch the encoder until the future completes.** It is not copied.
* `send` from *inside* a sink callback is legal and cheap — it is the same thread enqueueing to
  itself, drained later in the same `doWork`. `ArtioToArtioIT` replies to orders exactly this way.

`submit(Consumer<FixLibrary>)` is the escape hatch for anything this class does not wrap; it runs on
the poll thread and completes its future when the action returns.

---

## 9. FIX 4.2

### 9.1 What it took

One line, and a whole module behind it:

```java
configuration.acceptorfixDictionary(com.demo.artio.fix42.FixDictionaryImpl.class);
```

Artio ships `artio-session-codecs`, and those codecs are **FIX 4.4**. An acceptor validates the
counterparty's `BeginString(8)` against the `FixDictionary` it was handed, so a FIX 4.2 logon against
an acceptor with the default dictionary is rejected before the application sees anything. The FIX 4.2
`FixDictionaryImpl` is generated by `:fix-codecs` from the QuickFIX/J `FIX42.xml` that
`:fix-dictionary` converts — see `docs/02-quickfixj-to-artio-dictionary.md`.

On an **initiator** the same class goes on the session instead of the engine
(`SessionConfiguration.Builder.fixDictionary(Class)`), because an initiator knows which version it is
about to speak.

`FixVersion` is the whole of that knowledge in this module: an enum of
`(beginString, Class<? extends FixDictionary>)`.

### 9.2 The encoder API differs between versions

FIX 4.4 keeps `Symbol` in the `Instrument` component and `OrderQty` in `OrderQtyData`, so the
generated **encoders** differ:

```java
// FIX 4.2 — flat
encoder.symbol("MSFT");
encoder.orderQty(100, 0);

// FIX 4.4 — through component encoders
encoder.instrument().symbol("MSFT");
encoder.orderQtyData().orderQty(100, 0);
```

The **decoders** implement the component interfaces, so `decoder.symbolAsString()` works on both.
`ItSupport.newOrderSingle` in the integration suite is the worked example.

`ExecutionReport` differs too: FIX 4.2 requires `ExecTransType(20)`, which FIX 4.4 removed — the 4.4
encoder has no such setter at all.

### 9.3 A FIX 4.2 `Logon` cannot carry credentials

QuickFIX/J's FIX 4.2 `Logon` predates `Username(553)`/`Password(554)`, so Artio's generator emits
`supportsUsername() == false` and credentials configured on a FIX 4.2 session are **silently not
sent**. This is not something the engine can work around; it is what the dictionary says. If a FIX
4.2 counterparty needs credentials, add the two fields to `FIX42.xml` in `:fix-dictionary`.

---

## 10. Shutdown

`ArtioRuntime.close()` is idempotent (`AtomicBoolean`), safe on a runtime that was never started,
and safe on one whose `start()` failed part way through. The order matters:

1. **Ask every session to log out.** `close()` sets a flag the poll agent reads; the agent calls
   `session.logoutAndDisconnect()` on each session that is not already `DISCONNECTED`, retrying on
   back-pressure and remembering which it has already asked. `close()` waits until the tracked
   session list is empty or `shutdownTimeoutMs` has passed. This is why a QuickFIX/J counterparty
   sees a `Logout(35=5)` rather than a TCP reset — asserted in `ArtioRuntimeShutdownIT`, both
   directions.
2. **Stop the poll thread** (`agentRunner.close()`). The `AgentRunner` interrupts and joins, then
   calls `PollAgent.onClose()` **on the poll thread**, which is where the `FixLibrary` is closed.
   Closing a `FixLibrary` from any other thread is not safe, and this is the only ordering that
   guarantees it.
3. **Close the `FixEngine`.** After the library, so the engine is not shut down under a live library.
4. **Close the `ArchivingMediaDriver`.** Last, because everything above talks to it.
5. **Delete the directories** (unless `deleteDirectoriesOnClose` is false).

If start-up failed before the `AgentRunner` existed, step 2 closes the library directly instead.

Every command still queued when the agent closes is failed with "is shutting down", so no caller is
left holding a future that never completes.

`ArtioRuntimeShutdownIT.everyArtioAndAeronThreadTheRuntimeStartedIsGoneAfterCloseReturns` snapshots
thread names before start, asserts that at least four new Artio/Aeron-looking threads appeared and
that Artio's carry the runtime id (so the scan cannot pass vacuously), then closes and waits with
Awaitility until none of them is left. Aeron's agents finish asynchronously, so the wait is real,
but it completes in well under a second.

---

## 11. Numbers

Measured on this machine, Corretto 21, Apple Silicon, loopback:

| | Observed |
| --- | --- |
| `ArtioRuntime.launch` for an acceptor (driver + archive + engine + library + bind) | ~0.30 s warm, 0.45 s for the first in a JVM |
| Initiator logon latency: `library.initiate(...)` to `session.isActive()` | **10–28 ms**, typically 18–20 ms |
| Threads per runtime | 9 |
| Full `:artio-engine:integrationTest` (27 tests, 32 runtimes started and closed) | ~32 s |
| `:quickfixj-counterparty:integrationTest` (8 tests) | ~15 s |

The logon latency is dominated by the TCP round trip plus one poll cycle of the `BackoffIdleStrategy`
on each side; it is not a measure of Artio's message path, which is a different order of magnitude.

---

## 12. JVM flags

**Three, and all three are mandatory for a JVM that launches a `FixEngine`.**

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens   java.base/sun.nio.ch=ALL-UNNAMED
```

* The first two are agrona's. `org.agrona.UnsafeApi` reaches `jdk.internal.misc.Unsafe`, and every
  generated codec allocates an `UnsafeBuffer` in its constructor, so without them the first
  `new NewOrderSingleEncoder()` throws
  `IllegalAccessError: class org.agrona.UnsafeApi ... cannot access class jdk.internal.misc.Unsafe`.
* The third is **Artio's, and it was discovered here**, not documented upstream in a way that is
  easy to find. `uk.co.real_logic.artio.engine.framer.ReceiverEndPoints` reflects on
  `sun.nio.ch.SelectorImpl.selectedKeys` in a *static initialiser*, to swap the selected-key set for
  an array-backed one. Without the flag, `FixEngine.launch` fails with:

  ```
  java.lang.NoClassDefFoundError: Could not initialize class
      uk.co.real_logic.artio.engine.framer.ReceiverEndPoints
    Caused by: java.lang.ExceptionInInitializerError:
      java.lang.reflect.InaccessibleObjectException: Unable to make field
      private final java.util.Set sun.nio.ch.SelectorImpl.selectedKeys accessible:
      module java.base does not "opens sun.nio.ch" to unnamed module
  ```

  Nothing in `:fix-codecs` needs it, which is why phase 1 did not find it: it only bites once an
  engine is actually launched. Because it fails in a static initialiser, the *second* attempt in the
  same JVM reports only `NoClassDefFoundError` with no cause, which is a confusing way to meet it.

`artio-engine/build.gradle.kts` puts all three on every `Test` and `JavaExec` task. **Every
downstream module that launches an engine — the AMPS bridge, the Spring Boot application — needs all
three**, on `bootRun`, on `run`, on tests, and in whatever launches the packaged application.

---

## 13. Gotchas

**13.1 `library.initiate` can return `null`.** It is a duty-cycle API: `null` means the request could
not be enqueued right now, not that it failed. Retry on the next poll. Artio's own `Buyer` sample
does not show this because it initiates from a state machine that is polled anyway.

**13.2 `FixEngine.bind()` is not needed with `bindTo` + `SOLE_LIBRARY`.** `bindTo(host, port)` sets
`bindAtStartup(true)` as a side effect (its javadoc says so, easy to miss), and in sole-library mode
the bind is deferred to library connect. Calling `bind()` as well is harmless but pointless — and
calling it *before* a library connects sets a flag rather than binding, which looks like success.

**13.3 The archive's defaults are fixed ports.** See section 2.2. Two runtimes in one JVM is the case
that finds this, and it is exactly the case a bridge test needs.

**13.4 `agentNamePrefix`, not a `ThreadFactory`.** agrona's `AgentRunner.startOnThread` overwrites
the thread name with `agent.roleName()` after the factory has run, so `CommonConfiguration.threadFactory`
cannot be used to label threads. Artio's `agentNamePrefix` can, because it feeds `roleName()`.

**13.5 `MsgSeqNum` is not a `SessionHandler.onMessage` parameter.** Artio passes `sequenceIndex` but
not the sequence number. `SessionParser.onMessage` runs immediately before the handler, so
`session.lastReceivedMsgSeqNum()` *is* this message's `MsgSeqNum(34)` at that point, and that is what
`FixMessageView.sequenceNumber()` reports.

**13.6 `Session.compositeKey()` can be null at acquisition.** `RuntimeSessionHandler` falls back to
the configured CompIDs and re-resolves the key on the next message until Artio has populated it.

**13.7 QuickFIX/J's `CheckLatency` will bite you.** Artio's `SendingTime` comes from a different
clock source; on loopback the difference is microseconds, but a latency check that can fail for
reasons unrelated to the code under test is a flaky test waiting to happen.
`:quickfixj-counterparty` sets `CheckLatency=N` and leaves everything else validating — including
the data dictionary, which is what makes "QuickFIX/J accepted the message Artio's encoder built" a
meaningful assertion.

**13.8 A sink that both records and replies must not share an encoder.** `send` does not copy the
encoder. Build one per reply, or serialise access yourself.
