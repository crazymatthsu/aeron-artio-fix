# artio-amps-bridge

The Artio-to-AMPS publisher: a `FixMessageSink` that takes every FIX message the engine receives and
puts it on an AMPS topic **as the raw bytes that came off the session** — SOH separators and all, no
`String`, no JSON, no schema anywhere on the path — without ever letting a slow AMPS reach back into
Artio's poll thread.

Also the non-Spring demo entry point (`./gradlew :artio-amps-bridge:run`) and the "did it arrive"
utility (`./gradlew :artio-amps-bridge:sowDump`).

The design, with the reasoning and the measured numbers, is in
[`docs/03-artio-amps-bridge-design.md`](../docs/03-artio-amps-bridge-design.md).

## The hand-off, in a paragraph

Artio calls `onMessage` on its library poll thread, and while that call is running **no other
session on the library is served and no heartbeat is sent**. A publish to AMPS is a socket write
that can block for as long as the network feels like. Those two facts are incompatible, so the
bridge does not publish on the poll thread. `onMessage` claims space in an Agrona
`OneToOneRingBuffer` laid over an off-heap `UnsafeBuffer`, writes a 24-byte header and copies the
message bytes straight from Aeron's log buffer into it, and returns — a bounds check and a `memcpy`,
about **0.05 µs**, allocating nothing. A separate `AgentRunner` thread drains the ring, decides
which topics the message belongs on, copies it into one reusable `byte[]`, and calls the AMPS
byte-array publish. If the ring fills because AMPS is slower than the session, the configured
[`OverflowPolicy`](src/main/java/com/demo/artio/bridge/OverflowPolicy.java) decides whether to drop
and count, or to hold the poll thread for a bounded time first.

```
Artio poll thread                off-heap ring buffer            publisher agent thread
─────────────────                ────────────────────            ──────────────────────
onMessage(view) ──tryClaim──►  [type|session|seq|len|bytes]  ──read──►  TopicRouter
 (copy, no alloc)               4 MiB, single producer,                 reusable byte[]
                                single consumer                         AmpsPublishPort ──► AMPS
```

## Topics, and the rule that shapes them

| Message | Topic | Guard |
| --- | --- | --- |
| every application message | `fix.raw` | none — the topic has no SOW key, so nothing can be lost |
| `35=D` / `35=G` / `35=F` | `fix.orders` | must carry tag **11** (`ClOrdID`) |
| `35=8` | `fix.execs` | must carry tag **17** (`ExecID`) |
| `35=8` | `fix.order.state` | must carry tag **37** (`OrderID`) |
| session-level (`0 1 2 3 4 5 A`) | `fix.admin`, or dropped | `bridge.publishAdminMessages` |

The "must carry" column is a **correctness requirement**, not a precaution. AMPS 5.3.5.135 does not
reject a SOW publish that lacks the topic's key: it accepts it, logs nothing, and stores every such
message under one degenerate shared record — publish three keyless messages and the topic holds one.
There is no server-side guard to mirror; the bridge's tag check is the only thing standing between a
mis-tagged message and a quietly truncated blotter. The behaviour is pinned by
`SowKeyBehaviourIT` in `:amps-test-harness` and written up in
[`docs/05`](../docs/05-integration-testing-and-demo.md) section 8.1.

A message that matches a rule but lacks its tag is **counted as `unroutable` and not published
there** — and it is still on `fix.raw`, which is unkeyed and journalled, so nothing is actually
lost.

## Running the demo

```bash
amps-server/scripts/amps.sh start                 # AMPS in podman, artio-fix flow, port 9007

./gradlew :artio-amps-bridge:run                  # Artio acceptor on 9880 + the bridge

./gradlew :quickfixj-counterparty:run --args="initiator --host localhost --port 9880 \
    --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"

./gradlew :artio-amps-bridge:sowDump --args="--topic fix.orders"
./gradlew :artio-amps-bridge:sowDump --args="--replay fix.raw"

amps-server/scripts/amps.sh down
```

`sowDump --topic fix.orders` after the scenario prints five records, one per `ClOrdID`:

```
SOW fix.orders on tcp://localhost:9007/amps/fix
  [1] 8=FIX.4.2|9=130|35=D|34=2|49=QFJ|...|11=ORD-1|21=1|38=100|40=2|44=101.25|54=1|55=MSFT|...
  [2] 8=FIX.4.2|9=129|35=D|34=3|49=QFJ|...|11=ORD-2|21=1|38=200|40=2|44=102.5|54=1|55=MSFT|...
  [3] 8=FIX.4.2|9=130|35=D|34=4|49=QFJ|...|11=ORD-3|21=1|38=300|40=2|44=103.75|54=1|55=MSFT|...
  [4] 8=FIX.4.2|9=134|35=G|34=5|49=QFJ|...|11=ORD-4|21=1|38=150|40=2|41=ORD-1|44=101.75|...
  [5] 8=FIX.4.2|9=114|35=F|34=6|49=QFJ|...|11=ORD-5|38=200|41=ORD-2|54=1|55=MSFT|...
5 record(s) in fix.orders
```

Five, not three: a cancel/replace carries a **new** `ClOrdID` and the previous one in tag 41, so a
topic keyed on `/11` holds one record per *request*, which is the audit shape.

`Ctrl-C` (or `kill -TERM`) stops the bridge cleanly: it logs the counterparty out, drains the ring
buffer, flushes AMPS and disconnects, printing the final counters. Gradle then reports the `run`
task as failed with **exit value 143** — that is the JVM reporting that it was terminated by a
signal, and it is expected.

### `sowDump`

```
--topic <topic>     query the SOW: one record per key, as it stands now
--replay <topic>    replay the transaction log from the epoch, and count it
--filter "<expr>"   an AMPS content filter over FIX tags, e.g. "/11 = 'ORD-1'"
--uri <uri>         default tcp://localhost:9007/amps/fix
--timeout-ms <ms>   idle time that ends a query (default 3000)
```

`fix.raw` and `fix.admin` have no SOW declaration, so `--topic` finds nothing there by design; use
`--replay fix.raw` for the tape. `fix.admin` is not journalled either — to see it you have to be
subscribed while it happens.

## `bridge.properties`

Read by `BridgeMain` from `artio-amps-bridge/bridge.properties` (or `--config <file>`). Every key
can be overridden with a system property — `run` forwards `-Dartio.*` and `-Dbridge.*` from the
Gradle JVM, as amps-demo's `bootRun` does:

```bash
./gradlew :artio-amps-bridge:run -Dartio.port=9881 -Dbridge.amps.uri=tcp://host:9007/amps/fix
```

### The engine

| Key | Default | Meaning |
| --- | --- | --- |
| `artio.mode` | `acceptor` | `acceptor` binds and waits; `initiator` connects out |
| `artio.name` | `bridge` | labels thread names, directories and logs |
| `artio.host` / `artio.port` | `0.0.0.0` / `9880` | bind address, or remote address |
| `artio.senderCompId` / `artio.targetCompId` | `ARTIO` / `QFJ` | from this engine's point of view |
| `artio.fixVersion` | `FIX.4.2` | `FIX.4.2` or `FIX.4.4` |
| `artio.heartbeatIntervalSec` | 30 | `HeartBtInt(108)` |

### AMPS and the bridge

| Key | Default | Meaning |
| --- | --- | --- |
| `bridge.amps.uri` | `tcp://localhost:9007/amps/fix` | **the `/amps/fix` path is not decoration** — it selects the server-side FIX parser, which is what makes `/11`-style SOW keys work. `/amps/json` connects and then parses nothing |
| `bridge.amps.clientName` | `artio-bridge` | a unique suffix is appended per connection |
| `bridge.amps.guaranteedPublishing` | `false` | install a client-side publish store; see below |
| `bridge.defaultTopic` | `fix.raw` | every application message, unconditionally. Blank means none |
| `bridge.adminTopic` | `fix.admin` | where session-level messages go when the next key is true |
| `bridge.publishAdminMessages` | `false` | off by default: a heartbeat per session per interval would be the largest and least useful stream in the instance |
| `bridge.route[N].msgType` | — | the `MsgType(35)` this rule matches |
| `bridge.route[N].topic` | — | where it publishes |
| `bridge.route[N].requiredTag` | 0 | the tag that must be present; normally the topic's SOW key |
| `bridge.ringBufferCapacityBytes` | 4194304 | a power of two; ~20 000 FIX messages in flight |
| `bridge.overflowPolicy` | `DROP_AND_COUNT` | or `BLOCK` |
| `bridge.overflowBlockTimeoutMs` | 1000 | how long `BLOCK` holds the poll thread before dropping |
| `bridge.flushTimeoutMs` | 10000 | `close()`'s drain-and-flush budget, and the AMPS logon timeout |
| `bridge.reconnect.initialBackoffMs` | 250 | first delay after a disconnect |
| `bridge.reconnect.maxBackoffMs` | 30000 | the ceiling it doubles up to |
| `bridge.idleStrategy` | `BACKOFF` | `BACKOFF`, `BUSY_SPIN` or `SLEEPING` for the publisher thread |

**Routes are all-or-nothing.** Define `bridge.route[0]` and the whole default set is replaced by
what you define, from index 0, stopping at the first gap. Leave them out and you get the defaults in
the table above. Merging a partial override into the defaults would be more forgiving and more
dangerous: it makes "I removed the `fix.order.state` rule" impossible to express.

**Guaranteed publishing** installs a `MemoryPublishStore` on the AMPS client. What it buys: every
publish is retained until AMPS acknowledges it persisted, and the unacknowledged ones are
**replayed** on reconnect, so a broker bounce stops losing what was in flight. What it does **not**
buy: durability across a crash of this process — the store is in memory — and it applies only to
topics AMPS journals (`fix.raw`, `fix.orders`, `fix.execs` in this flow). Turn it on when the AMPS
stream is a system of record and a broker restart is likelier than a gateway one.

## JVM flags — all three, mandatory

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens   java.base/sun.nio.ch=ALL-UNNAMED
```

The first two are agrona's (`UnsafeApi` reaches `jdk.internal.misc.Unsafe`, and both the ring buffer
and every generated codec want it); the third is Artio's (`ReceiverEndPoints` reflects on
`sun.nio.ch.SelectorImpl.selectedKeys` in a static initialiser, and without it `FixEngine.launch`
dies with `NoClassDefFoundError`). This build puts all three on every `Test`, every `JavaExec` and
on the `application` start script. Any process that embeds `AmpsFixPublisher` next to an
`ArtioRuntime` needs them too — see `docs/01` section 12.

## Tests

```bash
./gradlew :artio-amps-bridge:test              # 66 unit tests. No AMPS, no Artio, no sockets.
./gradlew :artio-amps-bridge:integrationTest   # 4 tests against a real AMPS in podman
./gradlew :artio-amps-bridge:build             # both (check depends on integrationTest)
./gradlew :artio-amps-bridge:publishBenchmark  # a non-asserting throughput measurement
```

| Suite | What it covers |
| --- | --- |
| `FixTagsTest` | the tag scan: at the start, at the end, absent, inside a value (`58=x11=y`), as the tail of a longer tag (`111=`), at a buffer offset |
| `TopicRouterTest` | the default routes, rule order, the tag guard, admin drop versus publish, and that a topic name is encoded once into a `byte[]` and reused |
| `AmpsFixPublisherTest` | the ring-buffer hand-off against `InMemoryPublishPort`: bytes and offsets, flyweight reuse, both overflow policies, publish errors, the shutdown drain, the stats snapshot |
| `BridgeConfigTest` | every property key, every validation rule, and that the shipped `bridge.properties` still parses into the built-in defaults |
| `AmpsClientPortTest` | reconnect: back-off doubling to its ceiling, one connect per interval rather than per message, a non-disconnect failure staying an error, close |
| `BridgeOrderFlowIT` | the whole path on FIX 4.2 **and** 4.4: QuickFIX/J → Artio → ring buffer → AMPS, one SOW record per `ClOrdID`, five on the journal, `fix.execs` and `fix.order.state` empty, every counter |
| `BridgeRoutingAgainstAmpsIT` | the `Logon` on `fix.admin` (seen through a live subscription, because the topic has no SOW and no journal), and a `35=8` with no tag 37 reaching `fix.execs` and the tape but **not** `fix.order.state` |

The integration suite needs podman and the AMPS image; it skips with a reason when either is
missing, so `./gradlew build` stays green on a machine that has never seen AMPS. **A green build is
therefore not proof it ran — look for `0 skipped`.** The task is deliberately never up to date and
never cached; the reasoning is in the comments in `build.gradle.kts`.

## The counters

`AmpsFixPublisher.stats()` returns a `BridgeStats` snapshot, logged every five seconds by
`BridgeMain`:

```
accepted=7 published=10 pending=0 dropped=0 unroutable=0 errors=0 lost=0 bytes=1504
ring=0/4194304 connected [fix.raw={published=5}, fix.orders={published=3}, ...]
```

| Counter | Meaning | Healthy |
| --- | --- | --- |
| `accepted` | messages the poll thread wrote into the ring buffer | grows with traffic |
| `drained` / `published` | messages taken off the ring / successful publishes, **summed over routes** — one message on three topics counts three | `drained == accepted` at rest |
| `pending` | `accepted - drained`; `ring=` is the same backlog in bytes | 0 at rest |
| `dropped` | the ring was full and the message never got in | **0** |
| `unroutable` | matched a rule but lacked its required tag, so it was kept off that SOW topic | **0**; non-zero means a counterparty is sending something the topic map does not describe |
| `adminSkipped` | session-level messages dropped because no admin topic is configured | expected when `publishAdminMessages=false` |
| `errors` | publishes AMPS refused for a reason other than a disconnect | **0** |
| `lost` | messages that arrived while AMPS was unreachable | **0** |
| `reconnects` | how many times the port re-established its connection | **0** |
| `bytes` | payload bytes handed to AMPS | — |

`dropped`, `unroutable` and `lost` are the three ways a message can fail to reach a topic, and each
is counted separately because each has a different fix: a bigger ring buffer or `BLOCK`; a topic map
that matches the traffic; a broker that stays up (and `guaranteedPublishing`).

## Using it from code

```java
BridgeConfig config = BridgeConfig.builder()
    .uri("tcp://localhost:9007/amps/fix")
    .clientName("gateway")
    .build();

AmpsFixPublisher publisher = new AmpsFixPublisher(config);
publisher.start();                                  // connect, start the agent thread
try (ArtioRuntime runtime = ArtioRuntime.launch(engineConfig, publisher))
{
    // ... the session runs ...
}                                                   // engine closed FIRST: no more messages
finally
{
    publisher.close();                              // drain, flush, disconnect
}
```

**The order is not interchangeable.** Close the runtime first so the ring buffer stops filling, then
the publisher, which drains what is left and flushes. Closing the publisher first leaves Artio
delivering into a stopped agent. Never flush concurrently with either.

`new AmpsFixPublisher(config, port)` takes an `AmpsPublishPort` for tests — and does **not** close a
port it was handed.
