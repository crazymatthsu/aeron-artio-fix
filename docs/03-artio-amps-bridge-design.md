# The Artio to AMPS bridge as built

How `:artio-amps-bridge` gets a FIX message from Artio's Aeron log buffer onto an AMPS topic without
copying it to the heap and without ever blocking Artio's poll thread, what it does when either end
misbehaves, and what it costs. Every number below came from a run on this machine (Corretto 21,
Apple Silicon, AMPS 5.3.5.135 amd64 under emulation in podman) on 2026-09-06.

Companion documents: `docs/00-implementation-plan.md` section 4.7 (the brief),
`docs/01-artio-engine-design.md` (the engine and the sink contract this builds on),
`docs/05-integration-testing-and-demo.md` section 8.1 (the AMPS behaviour that shapes the routing
rules).

---

## 1. What the module is

Four things, and the first is the only one with any subtlety in it:

| Type | What it is |
| --- | --- |
| `AmpsFixPublisher` | a `FixMessageSink` and an `AutoCloseable`: the ring buffer, the publisher agent, the counters |
| `TopicRouter` / `TopicRoute` / `FixTags` | which topics a message belongs on, decided without allocating |
| `AmpsPublishPort` / `AmpsClientPort` / `AmpsClientConnection` / `InMemoryPublishPort` | the two AMPS calls the bridge makes, behind an interface, plus reconnect |
| `BridgeConfig` / `BridgeStats` / `OverflowPolicy` | validated configuration and an immutable counter snapshot |

plus two mains: `BridgeMain` (the non-Spring demo runtime) and `SowDump` (the "did it arrive"
utility).

---

## 2. Topology

```
                            one JVM
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                      │
│  Artio library poll thread          off-heap ring buffer      publisher agent thread │
│  ─────────────────────────          ────────────────────      ───────────────────── │
│                                                                                      │
│  SessionHandler.onMessage                                                            │
│        │                                                                             │
│        ▼                                                                             │
│  FixMessageView  ────────────►  tryClaim(24 + len)  ────►  read(handler, 64)          │
│  (flyweight over Aeron's         [msgType  long ]                │                   │
│   own log buffer)                [sessionId long]                ▼                   │
│        │                         [seqNum    int ]          TopicRouter.route()        │
│        │  getBytes(...)          [length    int ]          (packed long compare,      │
│        └────────────────────►    [ raw FIX bytes]           tag scan on raw bytes)    │
│                                                                  │                   │
│                                  OneToOneRingBuffer               ▼                   │
│                                  over UnsafeBuffer          reusable byte[4096]      │
│                                  over allocateDirect              │                   │
│                                  (4 MiB + TRAILER)                ▼                   │
│                                                            AmpsPublishPort.publish    │
│                                                            (byte[] topic, byte[] data)│
└──────────────────────────────────────────────────────────────────────────────────────┘
                                                                     │ TCP
                                                                     ▼
                                            AMPS  ── fix.raw ── fix.orders ── fix.execs
                                                     (journal)    (SOW /11)   (SOW /17)
                                                                ── fix.order.state (SOW /37)
                                                                ── fix.admin (optional)
```

One extra thread per publisher, named `amps-publisher`, run by an Agrona `AgentRunner` with the
configured idle strategy. Nothing else.

---

## 3. Why a ring buffer

This is the whole design decision, and it follows from one sentence in `docs/01` section 7:

> The callback runs on the library poll thread. While it is running, no other session on this
> library is served, no heartbeat is sent, and Aeron's log buffer is not being drained.

A publish to AMPS is a socket write. It can block for a scheduler quantum, or for a TCP retransmit,
or for as long as a GC pause on the broker lasts. Put that on the poll thread and a slow broker
becomes a FIX outage: heartbeats stop, `TestRequest`s go unanswered, and counterparties disconnect a
gateway that is in fact perfectly healthy. The failure is both severe and entirely avoidable, so the
publish does not happen there.

What the poll thread does instead — `AmpsFixPublisher.onMessage` — is:

1. compare a length against `ringBuffer.maxMsgLength()`;
2. `tryClaim` 24 + *len* bytes, which is one atomic load and one store;
3. write four primitives and `getBytes` the message from Aeron's buffer into the ring buffer;
4. `commit`, which is one ordered store.

No allocation, no lock, no system call, no `String`. Measured at **0.04–0.09 µs per message**
(section 9). Aeron's buffer and the ring buffer are both off-heap, so step 3 is a `memcpy` between
two native regions and the message never becomes a Java object at all.

### Why *this* ring buffer

* **`OneToOneRingBuffer`, not many-to-one.** There is exactly one Artio poll thread per
  `FixLibrary`, and `ArtioRuntime` is single-library by construction (`SOLE_LIBRARY` mode, see
  `docs/01` section 4.1). Single-producer means the claim is a plain store-release instead of a CAS
  loop. If a second producer ever appears, this must become `ManyToOneRingBuffer` — it is one line,
  but it is not optional.
* **`ByteBuffer.allocateDirect`, not a heap array.** A 4 MiB backlog on the heap is 4 MiB that every
  young collection has to scan and that a full collection may copy. Off-heap it is invisible to the
  collector, which matters precisely when the bridge is behind — the moment you least want GC
  pressure.
* **`tryClaim`/`commit`, not `write`.** `write(msgTypeId, srcBuffer, offset, length)` would need the
  message in a `DirectBuffer` first, which it already is — but claiming lets the header and the
  payload be written directly into the ring with no staging buffer at all.
* **4 MiB default.** At ~130 bytes per FIX message plus 24 bytes of header and agrona's 16-byte
  alignment, that is roughly 20 000 messages in flight. Agrona caps a single message at
  `capacity / 8`, so 4 MiB also allows a 512 KiB message, which is far past anything FIX produces.

### The message header

`[msgType long][sessionId long][seqNum int][length int]` — 24 bytes. Only `msgType` and `length` are
read on the way out today; `sessionId` and `seqNum` are carried because they are free (the flyweight
already has them, the ring buffer record is 16-byte aligned so the header rounds to nothing) and
because the first question anyone asks of a bridge like this is "which session, and which sequence
number" — a per-session topic or a sequence-gap detector needs no format change.

---

## 4. Overflow: the two honest answers

When the publisher cannot keep up for long enough to fill 4 MiB, something has to give. There is no
third option — the point of the ring buffer is that back-pressure must *not* reach the poll thread —
so `OverflowPolicy` chooses between:

| | `DROP_AND_COUNT` (default) | `BLOCK` |
| --- | --- | --- |
| What happens | the message is dropped, `dropped` is incremented | the poll thread spins on the idle strategy for up to `overflowBlockTimeoutMs`, retrying, then drops |
| The FIX session | unaffected | stalled for up to that timeout |
| Measured (section 9) | 12.5M msg/s write, 23 841 of 50 000 dropped | 15 466 msg/s write, **0 dropped** |
| Use when | the FIX session is the system of record and Artio's log is the recovery path | the AMPS stream is the system of record and a brief pause beats a gap |

Two things to keep in mind when choosing `BLOCK`:

* **Keep the timeout well under the heartbeat interval.** Nothing else on the library is served while
  it waits; a block longer than `HeartBtInt` will get the session disconnected, which loses far more
  than the messages you were trying to save.
* **It only defers the problem.** If AMPS is *persistently* slower than the session, `BLOCK` paces
  the gateway to the broker's speed until the timeout expires, and then drops anyway. The benchmark
  shows this exactly: with `BLOCK` the write rate collapses from 12.5M/s to 15k/s — the writer is
  now the publisher.

A message larger than `maxMsgLength` is dropped immediately under either policy, with a warning
naming both sizes, because no amount of waiting creates room that cannot exist.

---

## 5. Topic routing, and the guard that has to be here

### The rules

```
admin (0 1 2 3 4 5 A)  ->  adminTopic, or dropped and counted
application            ->  defaultTopic  (fix.raw)      unconditionally
                       ->  then every matching rule, in configuration order
```

with the `artio-fix` defaults:

| Rule | Topic | Required tag | Why that tag |
| --- | --- | --- | --- |
| `35=D`, `35=G`, `35=F` | `fix.orders` | 11 `ClOrdID` | the topic's SOW key; mandatory on all three |
| `35=8` | `fix.execs` | 17 `ExecID` | the topic's SOW key; unique per report |
| `35=8` | `fix.order.state` | 37 `OrderID` | the topic's SOW key; the venue's order id |

`fix.raw` has **no** condition, and that is deliberate: it is the topic with no SOW key, so there is
nothing a missing tag could corrupt, and it is journalled, so it is the recovery path for anything a
rule below declines. Session-level messages do not reach it — `fix.raw` is the application tape, and
a heartbeat per session per interval would be the largest and least useful stream in the instance.

### The phase 1b finding, and what it forces

`docs/00` section 2 recorded, as a fact inherited from the `amps-demo` project, that AMPS *rejects* a
SOW publish lacking the topic's key. Phase 1b measured it against 5.3.5.135 with `MessageType fix`
and found the opposite (`docs/05` section 8.1, pinned by `SowKeyBehaviourIT`):

> It is **accepted**. No exception at the client, no ack failure, nothing in the server log. Stored
> under a synthetic key — and every keyless message shares that one record, so three keyless
> publishes leave one.

So the failure mode is not a rejection the publisher can count. It is silent, unbounded loss that
presents as "the SOW looks a bit short". There is no server-side check to mirror and no error to
propagate: **`TopicRouter`'s tag-presence rule is the only guard that exists anywhere in the
system.** That is why it is a first-class, tested feature of this module rather than a defensive
nicety, why a declined message is *counted* rather than dropped in silence, and why
`BridgeRoutingAgainstAmpsIT` asserts against a real server that a `35=8` with no tag 37 reaches
`fix.execs` and `fix.raw` but leaves `fix.order.state` holding only the well-keyed record.

### Scanning for a tag without allocating

`FixTags.containsTag(data, offset, length, tag)` walks field boundaries: a field starts at the first
byte of the message or immediately after a SOH, and its tag is the digits before the first `=`.
Anchoring matters twice over, and both cases are in `FixTagsTest`:

* `58=x11=y` — a substring search for `"11="` finds a match **inside a field value** and would route
  a message to a topic keyed on a tag it does not have;
* `111=ORD-1` — a substring search finds `11=` as the **tail of a longer tag**, with the same result.

The scan reads each byte at most once, parses the tag as an `int` as it goes, and allocates nothing.

### Nothing on the publish path builds a String

Every topic name is encoded to `byte[]` once, in `TopicRouter.CompiledRoute`'s constructor, and the
same array instance is handed to `AmpsPublishPort.publish` for the life of the process
(`TopicRouterTest.topicNamesArePreEncodedOnceSoResolvingARouteCreatesNoString` asserts the identity,
not just the contents). Message types are compared as Artio's packed `long`, so matching `35=D` is a
primitive comparison rather than a `String.equals`. `route()` takes a handler callback — one lambda,
allocated once as a field — instead of returning a collection.

---

## 6. The port, and what a disconnect costs

`AmpsPublishPort` is four methods (`publish`, `flush`, `close`, `isConnected`). Two implementations:
`AmpsClientPort` over a real client, and `InMemoryPublishPort` that records copies, which is what
lets the unit suite cover the hand-off, the routing, the overflow accounting and the shutdown drain
with no container at all.

`AmpsClientPort` in turn talks to an `AmpsConnection`, a three-method interface over
`com.crankuptheamps.client.Client`. That indirection exists for one reason: a disconnect mid-publish,
a connect that is refused and a flush that times out cannot be provoked against a real server on
demand, and the reconnect logic is exactly the code that most needs testing. `AmpsClientPortTest`
injects a fake connection *and a fake clock*, so a 30-second back-off ceiling is verified in
microseconds.

### On disconnect

1. The publish throws `DisconnectedException`. The connection is closed and forgotten, the message is
   counted in `lostWhileDisconnected`, and `publish` returns **false** — it is not retried, because
   retrying on the agent thread is back-pressure on the ring buffer, which becomes back-pressure on
   the poll thread by way of the overflow policy.
2. Later publishes check the back-off deadline **before touching the network**, so an outage costs
   one connect attempt per interval, not one per message. `AmpsClientPortTest` asserts this
   explicitly: it is the difference between a quiet outage and a connect storm.
3. Back-off doubles from `reconnectInitialBackoffMs` (250 ms) to `reconnectMaxBackoffMs` (30 s). A
   successful reconnect resets it, increments `reconnects`, and logs how many messages the outage
   cost.

`DisconnectedException` extends `ConnectionException`, so the port catches the parent and treats
both as the reconnect path. `TimedOutException` also extends it — and is caught *first*, and
reported as an error rather than a disconnect, because a flush that ran out of time says AMPS is slow
rather than gone, and tearing the connection down over it would turn a slow shutdown into a lossy
one. Anything else (`BadFilterException` and friends) is an `AmpsPublishException`, counted as
`publishErrors`; reconnecting would not help and swallowing it would hide a real bug.

### What is actually lost, and what limits it

Messages that arrive while the port is down are **gone from AMPS**. The bridge does not pretend
otherwise — it counts them, logs the total when the connection comes back, and puts the number in
every stats line. Three things bound the damage:

* **`guaranteedPublishing`** installs a `MemoryPublishStore`, so messages already handed to the
  client are retained until AMPS acknowledges persistence and are **replayed** on reconnect. That
  covers the in-flight window, which is the common case for a broker bounce. It does not survive a
  crash of *this* process (the store is in memory) and it applies only to journalled topics
  (`fix.raw`, `fix.orders`, `fix.execs` here). `PublishStore` (memory-mapped) or
  `HybridPublishStore` would survive a JVM death and are a one-line change in
  `AmpsClientConnection` if that is ever wanted.
* **Artio's own message log** still holds every message that arrived. It is the durable upstream, and
  replaying it is the honest recovery story for a long outage.
* **`fix.raw` is journalled**, so a message that a *routing rule* declined is still recoverable from
  the tape — which is the second reason the default topic has no tag condition.

---

## 7. Allocation profile of the hot path

| Step | Thread | Allocates |
| --- | --- | --- |
| `onMessage`: length check, `tryClaim`, header write, `getBytes`, `commit` | Artio poll | **nothing** |
| `onMessage` under `BLOCK`: `idleStrategy.idle()` in a loop | Artio poll | **nothing** (the strategy is a field, created once) |
| ring `read` → header decode → `getBytes` into `payload` | publisher agent | **nothing** while messages fit the buffer |
| `payload` growth | publisher agent | one `byte[]`, only when a message exceeds every message seen so far; rounds to the next power of two and stays |
| `TopicRouter.route` + `FixTags.containsTag` | publisher agent | **nothing** — packed-long compares, a byte scan, a pre-allocated handler lambda |
| `AmpsClientPort.publish` → `Client.publish(byte[], …)` | publisher agent | whatever the AMPS client does internally; the bridge hands it arrays it already owns |
| counter increments (`AtomicLong`) | both | nothing |
| `stats()` | any | a `BridgeStats` and one `RouteStats` per route — a diagnostic call, not a hot path |

The two deliberate exceptions are the `payload` growth (bounded, one-off per size class) and
`stats()`. Everything else on the message path is steady-state allocation-free, which is what makes
`dropped=0` under a burst a property of the buffer size rather than of the collector's mood.

`LoggingSink`, which `BridgeMain` composes ahead of the publisher, **does** allocate — it builds a
printable `String` per message. That is a demo affordance, not part of the bridge; turn it off with
`-Dorg.slf4j.simpleLogger.log.com.demo.artio.engine.LoggingSink=warn` or leave it out of the
`CompositeSink` in production.

---

## 8. Shutdown

The order is fixed and not interchangeable:

1. **Close the `ArtioRuntime` first.** It logs the sessions out, stops the poll thread, and closes
   the engine and driver (`docs/01` section 10). After this nothing can write to the ring buffer.
2. **Then `AmpsFixPublisher.close()`**, which:
   1. waits up to `flushTimeoutMs` for the agent to empty the ring on its own;
   2. stops the agent (`AgentRunner.close()` interrupts and joins);
   3. drains whatever is left **on the calling thread** — the agent is gone, so there is no second
      reader, and a message already accepted should not be lost to a slow agent;
   4. calls `publishFlush`, without which a SOW query races the publishes and intermittently finds
      nothing (`docs/05` section 8);
   5. closes the port, but only one it created — a port handed in by a caller is the caller's.

`close()` is idempotent and safe on a publisher that was never started; in that case step 2.1 is
skipped entirely, because with no agent nothing would ever empty the buffer and the wait would burn
the whole timeout before step 2.3 did the work anyway.

Closing the publisher first would leave Artio delivering into a stopped agent, and those messages
would be counted as dropped. Flushing concurrently with either is not supported.

### The shutdown-hook trap

`BridgeMain` originally used the obvious idiom — a shutdown hook that counts down a latch the main
thread is waiting on — and it silently truncated the shutdown. **The JVM halts as soon as every hook
has returned**; it does not wait for a non-daemon thread that a hook happened to release. So
`runtime.close()` and `publisher.close()` were racing the exit, and the messages still in the ring
buffer were lost — exactly the failure the whole module exists to prevent, in the one code path
where it is least visible. The fix is a second latch: the hook signals, then blocks (bounded, at
`flushTimeoutMs + 5 s`) until the main thread says the clean-up is done. Observed before and after,
on `kill -TERM`:

```
before:  shutdown requested → (JVM exits)
after:   shutdown requested → bridge-acceptor-1 closed
                            → bridge closed: accepted=7 published=10 pending=0 dropped=0 ...
                            → final stats: ...
```

Gradle still reports the `run` task as failing with **exit value 143** afterwards. That is the JVM
saying it was terminated by a signal; the work completed.

---

## 9. Measured numbers

`./gradlew :artio-amps-bridge:publishBenchmark`, 50 000 hand-built FIX 4.2 `NewOrderSingle`s of ~100
bytes pushed through `onMessage` at full speed, after a 5 000-message warm-up, into an AMPS in
podman (amd64 under emulation on Apple Silicon — a Linux host on native hardware will be
considerably faster on the publish side and no different on the write side).

| | `DROP_AND_COUNT` | `BLOCK` (30 s bound) |
| --- | --- | --- |
| sink write, i.e. Artio poll-thread cost | 1–4 ms total, **0.04–0.09 µs/msg**, 12.5M–50M msg/s | 3 233 ms, 15 466 msg/s |
| end to end into AMPS, including `close()`'s flush | 6 358 ms, **7 864 msg/s** | 10 977 ms, 4 555 msg/s |
| publishes issued (2 topics per message) | 62 318 | 110 000 |
| peak ring occupancy | 4 194 168 of 4 194 304 bytes (**100 %**) | — |
| dropped | 23 841 | **0** |

Read them together and the design is visible in the numbers:

* The write path is **five to six orders of magnitude cheaper** than the publish path (0.05 µs versus
  ~130 µs per message end to end). That gap is the entire justification for the ring buffer: the
  poll thread's obligation is negligible, and the buffer is what keeps it that way.
* 50 000 messages arriving as fast as a JIT-warm loop can produce them is far beyond any real FIX
  session, and it fills a 4 MiB ring. That is the honest reading of "20 000 messages of headroom":
  it absorbs a burst, not a sustained overload.
* `BLOCK` really does lose nothing, and really does pace the writer to the broker — the write rate
  falls from 12.5M/s to 15k/s. Both halves of that sentence matter when choosing.

Other timings, from the integration suite and the demo:

| | Observed |
| --- | --- |
| `AmpsFixPublisher.start()` (connect + logon + agent thread) | ~30 ms against a local container |
| `close()` on an idle bridge (drain + `publishFlush` + disconnect) | 3–8 ms |
| logon, five orders, and all ten publishes landed in AMPS | ~1 s wall clock, dominated by the QuickFIX/J logon round trip |
| `:artio-amps-bridge:test` (66 tests) | ~4 s |
| `:artio-amps-bridge:integrationTest` (4 tests, 2 containers) | ~35 s, of which ~7 s is starting AMPS twice |

---

## 10. Gotchas

**10.1 The AMPS URI path selects the parser, not a transport.** `tcp://host:9007/amps/fix` is what
makes the server parse FIX and honour `/11`-style SOW keys and content filters. Connect on
`/amps/json` and the client connects, publishes succeed, and nothing is keyed or filterable — a
confusing failure because nothing errors.

**10.2 `publishFlush` is not optional.** Publishes are asynchronous. A SOW query that races them
intermittently returns nothing, which reads as "the bridge is broken" rather than "the test is
early". `close()` does it; a test that queries a still-running bridge must do it itself.

**10.3 Several rules can share one topic, so `BridgeStats.route(topic)` is a trap.** `35=D`, `35=G`
and `35=F` are three routes all feeding `fix.orders`, and `route()` returns the first. This cost an
integration-test failure during development (`expected 5 but was 3`). Use `publishedTo(topic)` and
`unroutableFor(topic)`, which sum.

**10.4 A cancel/replace produces a new record, not an updated one.** `fix.orders` is keyed on `/11`,
and `35=G` carries a *new* `ClOrdID` with the previous one in tag 41. The five-message demo scenario
therefore leaves **five** records for what a human would call three orders. That is the audit shape,
and it is the one that survives out-of-order delivery; "the state of order X" lives on
`fix.order.state`, keyed on `/37`.

**10.5 Artio delivers admin messages to the sink, and the bridge drops them by default.** The
counterparty's `Logon` really does arrive (`docs/01` section 6) and is counted in `adminSkipped`, not
in `dropped`. Those are different failures and are deliberately different counters.

**10.6 `fix.admin` cannot be queried after the fact.** It has no SOW and is not journalled, on
purpose. `BridgeRoutingAgainstAmpsIT` proves the `Logon` arrives by holding a live subscription open
across the session; `sowDump --topic fix.admin` will always print nothing.

**10.7 `OneToOneRingBuffer` means exactly one producer.** One `FixLibrary`, one poll thread — true
today by construction. Attaching one publisher to two runtimes would corrupt the buffer silently.
`ManyToOneRingBuffer` is a one-line change if that ever happens, and it is not optional.

**10.8 The default `bridge.properties` and `BridgeConfig.defaults()` are asserted to agree.**
`BridgeConfigTest.theShippedBridgePropertiesFileParsesIntoTheDefaults` fails if the file and the code
drift apart, which is the only way a documented default stays true.

**10.9 Routes are all-or-nothing in properties.** Defining `bridge.route[0]` replaces the entire
default set, from index 0, stopping at the first gap. Merging would be friendlier and would make
"I removed a rule" inexpressible.

**10.10 A shutdown hook does not hold the JVM open for anyone else.** See section 8. This is the one
bug in this module that would have shipped invisibly.
