# artio-engine

An embedded [Artio](https://github.com/artiofix/artio) FIX engine — media driver, Aeron archive,
`FixEngine`, `FixLibrary` and one poll thread — behind a single `AutoCloseable`, speaking **FIX 4.2**
and **FIX 4.4** as either **acceptor** or **initiator**, and handing every message it receives to a
`FixMessageSink` without copying it to the heap.

This is the module the AMPS bridge and the Spring Boot application are built on. The full design,
including the reasoning behind every non-obvious Aeron setting, is in
[`docs/01-artio-engine-design.md`](../docs/01-artio-engine-design.md).

## The API in one screen

```java
FixEngineConfig config = FixEngineConfig.acceptor()   // or .initiator()
    .name("gateway")
    .address("0.0.0.0", 9880)
    .senderCompId("ARTIO")
    .targetCompId("VENUE")
    .fixVersion(FixVersion.FIX42)
    .heartbeatIntervalSec(30)
    .build();

try (ArtioRuntime runtime = ArtioRuntime.launch(config, message ->
    {
        if (!message.isAdmin())
        {
            // The view is valid ONLY inside this call. Copy anything you keep.
            byte[] bytes = message.toByteArray();
        }
    }, listener))
{
    // Acceptor: the port is bound and a counterparty may connect.
    // Initiator: the session is already logged on.
    runtime.send(someEncoder);        // CompletableFuture<Long>, sent on the poll thread
}
```

| Type | What it is |
| --- | --- |
| `FixVersion` | `FIX42` / `FIX44`, each bound to the generated `FixDictionaryImpl` from `:fix-codecs`. |
| `EngineMode` | `ACCEPTOR` (bind) / `INITIATOR` (connect out). |
| `IdleStrategyType` | `BACKOFF` (default), `BUSY_SPIN`, `SLEEPING` — how the poll thread idles. |
| `FixEngineConfig` | Validated record + builder. See the table below. |
| `FixMessageSink` | `void onMessage(FixMessageView)` — the seam. |
| `FixMessageView` | Reusable zero-copy flyweight over Aeron's buffer. |
| `SessionKey` | Immutable session identity; safe to keep. |
| `SessionListener` | `onSessionAcquired` / `onLogon` / `onLogout` / `onDisconnect` / `onTimeout` / `onError`. |
| `ArtioRuntime` | Starts everything, polls, sends, closes. |
| `LoggingSink` / `CompositeSink` / `CountingSink` | Ready-made sinks. |

## Configuration

| Component | Default | Meaning |
| --- | --- | --- |
| `name` | `artio` | label for thread names, directories and logs |
| `host` / `port` | `localhost` / required | bind address (acceptor) or remote address (initiator) |
| `senderCompId` / `targetCompId` | `ARTIO` / `COUNTERPARTY` | must differ; no SOH or `=` |
| `fixVersion` | `FIX42` | selects the generated dictionary |
| `heartbeatIntervalSec` | 10 | `HeartBtInt(108)` |
| `baseDirectory` | `java.io.tmpdir` | parent of `artio-<runtimeId>/{aeron,archive,logs}` |
| `aeronDirectory` / `logFileDir` | derived | explicit overrides |
| `resetSeqNumsOnLogon` | true | `ResetSeqNumFlag(141)=Y` (initiator) |
| `libraryId` | auto | fixed `FixLibrary` id if you need one |
| `idleStrategy` | `BACKOFF` | poll thread idling |
| `logonTimeoutMs` | 20 000 | per start-up phase |
| `replyTimeoutMs` | 10 000 | Artio reply timeout; also the send back-pressure deadline |
| `shutdownTimeoutMs` | 5 000 | how long `close()` waits for a graceful logout |
| `deleteDirectoriesOnClose` | true | remove the runtime's directories on close |
| `authenticationStrategy` | accept everyone | the acceptor's logon hook |

Every runtime gets its own Aeron directory, archive and Artio log directory under `baseDirectory`
with a unique suffix, and deletes them on `close()`. Two runtimes can therefore share a JVM — which
`ArtioToArtioIT` does.

## The sink contract

`FixMessageView` is a flyweight over Aeron's own log buffer, valid **only** for the duration of the
`onMessage` call. A sink must:

* **not keep the view or its `buffer()`** past the callback — copy with `copyTo(byte[], int)`;
* **not block** — the callback runs on the library poll thread, and while it runs nothing else on
  that library is served;
* expect **admin messages too**: Artio delivers every inbound message, `Logon` and `Heartbeat`
  included. Filter with `isAdmin()`, which is a set lookup on a packed long and costs nothing.

Only messages this engine **receives** reach the sink; what it sends does not.

## Sending

`ArtioRuntime.send(Encoder)` returns a `CompletableFuture<Long>` (the Aeron position). It does not
touch the session on your thread: it enqueues onto the poll thread, which retries Artio
back-pressure until `replyTimeoutMs`. **Do not modify the encoder until the future completes** — it
is not copied. `sendAndAwait(encoder, timeout)` is the blocking convenience;
`submit(Consumer<FixLibrary>)` is the escape hatch for anything else that must run on the poll
thread.

## JVM flags — all three, mandatory

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens   java.base/sun.nio.ch=ALL-UNNAMED
```

The first two are agrona's (`UnsafeApi` reaches `jdk.internal.misc.Unsafe`, and every generated
codec allocates an `UnsafeBuffer` in its constructor). The third is Artio's:
`ReceiverEndPoints` reflects on `sun.nio.ch.SelectorImpl.selectedKeys` in a static initialiser, and
without it `FixEngine.launch` dies with
`NoClassDefFoundError: Could not initialize class ...ReceiverEndPoints`. This build puts all three
on every `Test` and `JavaExec` task in this module; any process that embeds `ArtioRuntime` needs
them too.

## Running the tests

```bash
./gradlew :artio-engine:test              # 42 unit tests: config, the flyweight, the sinks. No ports.
./gradlew :artio-engine:integrationTest   # 27 integration tests: real engines on loopback
./gradlew :artio-engine:build             # both (check depends on integrationTest)
```

The integration suite is unconditional — nothing skips, nothing needs a container:

| Suite | What it proves |
| --- | --- |
| `ArtioAcceptorFromQuickfixjIT` | Artio acceptor ← QuickFIX/J initiator, FIX 4.2 and 4.4: logon, five orders with their `ClOrdID`s, the `Logon` arriving as an admin message, the lifecycle events, the full order scenario |
| `ArtioInitiatorToQuickfixjIT` | Artio initiator → QuickFIX/J acceptor, both versions: logon with a measured latency, an order built with a generated encoder that QuickFIX/J validates and answers, ordering across three sends, and a send after close failing the future |
| `ArtioToArtioIT` | two runtimes, two Aeron directories: an order one way and an execution report back; separate directories, both removed on close |
| `ArtioRuntimeShutdownIT` | the counterparty gets a `Logout` not a reset (both directions), no Artio or Aeron thread survives `close()`, directories are removed, `close()` is idempotent, a failed start leaves nothing behind |

Aeron and Artio directories live under `java.io.tmpdir` and are deleted on close, so nothing is left
in the repository. Override with `-Dartio.it.dir=/somewhere` to keep them for inspection.

Ports are always taken from `new ServerSocket(0)`; none is hard-coded.

## Trying it by hand

There is no `main` here — this module is a library. To watch it work, use the counterparty:

```bash
# terminal 1: a QuickFIX/J venue on 9881
./gradlew :quickfixj-counterparty:run --args="acceptor --port 9881 --version FIX.4.4 --sender QFJ --target ARTIO"
```

and point an `ArtioRuntime` initiator at it from a test or from `:artio-amps-bridge`, which does
have a `main`.

## Known behaviour worth remembering

* A **FIX 4.2 `Logon` cannot carry `Username`/`Password`** — the dictionary has no such fields, so
  Artio's generated codec reports `supportsUsername() == false` and credentials are silently not
  sent.
* The acceptor runs in Artio's `SOLE_LIBRARY` mode: exactly one `FixLibrary`, which is what makes
  `start()` deterministic (the port is bound before `isConnected()` becomes true) and what makes the
  counterparty's `Logon` visible to the sink.
* An acceptor reports no `logonLatency()`: it did not drive the exchange.
