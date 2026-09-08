# quickfixj-counterparty

"The other FIX engine": a [QuickFIX/J](https://www.quickfixj.org/) initiator and acceptor, built
from programmatic settings with no `.cfg` files anywhere, speaking **FIX 4.2** and **FIX 4.4**.

Two jobs:

1. the counterparty for every integration test in this repository that needs one — the Artio
   pairings live in [`:artio-engine`](../artio-engine/README.md), and the AMPS bridge test drives
   its `OrderScenario`;
2. a runnable demo participant, so you can watch a session by hand.

It deliberately does **not** depend on `:artio-engine`. If it did, "the counterparty works" and
"Artio works" would be the same claim, and a failure in either would be indistinguishable. Where
both sides need the same concept — a FIX version — each defines its own enum and the test maps
between them.

## What is in it

| Type | What it is |
| --- | --- |
| `QfjVersion` | `FIX42` / `FIX44`, plus the behavioural differences that matter: `hasExecTransType()`, `fillExecType()`, `execType(ExecEvent)`, `requiresHandlInst()`. |
| `QfjRole` | `INITIATOR` / `ACCEPTOR`. |
| `QfjConfig` | Validated record + builder: version, address, CompIDs, heartbeat, screen log, reconnect and logon timeouts, reset-on-logon. |
| `QfjSettings` | Turns a `QfjConfig` into a QuickFIX/J `SessionSettings` describing exactly one session. |
| `QfjInitiator` | Connects out, logs on, sends orders, records everything. |
| `QfjAcceptor` | Binds, accepts, answers orders with execution reports, records everything. |
| `OrderScenario` | The scripted **drop copy** flow: 3 new orders, 1 cancel/replace, 1 cancel and the 10 `ExecutionReport`s they produce. Deterministic `ClOrdID`s, `OrderID`s and `ExecID`s. |
| `Orders` | Builds `NewOrderSingle(D)`, `OrderCancelReplaceRequest(G)`, `OrderCancelRequest(F)` and `ExecutionReport(8)` for either version. |
| `ExecutionReports` | Builds the venue's replies: ack + fill for `D`, one `Replaced` for `G`, one `Canceled` for `F`. |
| `RecordingApplication` | The `quickfix.Application`: records every message, and lets another thread await logon, logout or a matching message without polling. |
| `CapturedMessage` | One recorded message: direction, `MsgType`, raw FIX, `field(tag)`, `printable()`. |
| `CliArgs` / `QfjMain` | The command line. |

Messages are built as plain `quickfix.Message` objects with fields set **by tag number**, not with
`quickfix.fix42.NewOrderSingle` / `quickfix.fix44.NewOrderSingle`. One implementation then serves
both versions; the version-specific parts are exactly three: `HandlInst(21)` (required by FIX 4.2's
`NewOrderSingle`, absent from 4.4's), `ExecTransType(20)` (FIX 4.2 only) and the `ExecType(150)`
values for a trade — FIX 4.2 distinguishes `1` (partial fill) from `2` (fill), FIX 4.4 calls both
`F` (Trade) and leaves the distinction to `OrdStatus(39)`.

## Configuration

| Component | Default | Meaning |
| --- | --- | --- |
| `version` | `FIX42` | `BeginString(8)`, and which `DataDictionary` QuickFIX/J loads |
| `host` / `port` | `localhost` / required | connect address (initiator) or bind address (acceptor) |
| `senderCompId` / `targetCompId` | `QFJ` / `ARTIO` | must differ |
| `heartbeatIntervalSec` | 10 | `HeartBtInt(108)` |
| `screenLog` | false | print QuickFIX/J's own message log; off because `RecordingApplication` already has everything |
| `reconnectIntervalSec` | 1 | initiator retry interval |
| `logonTimeoutSec` | 10 | QuickFIX/J's `LogonTimeout` and `LogoutTimeout` |
| `resetOnLogon` | true | `ResetSeqNumFlag(141)=Y` |

Fixed settings, chosen once in `QfjSettings`:

* `MemoryStoreFactory` — no sequence-number files to clean up;
* `UseDataDictionary=Y` — inbound messages **are** validated, which is what makes "QuickFIX/J
  accepted the message Artio's encoder built" worth asserting;
* `CheckLatency=N` — Artio's `SendingTime` comes from a different clock source, and a latency check
  that can fail for reasons unrelated to the code under test is a flaky test waiting to happen;
* `ValidateUserDefinedFields=N` — an extension on the counterparty's side should not become a
  session-level reject;
* `StartTime = EndTime = 00:00:00` — the session never rolls.

## Running it

```bash
# a venue on 9881, FIX 4.4, until Ctrl-C
./gradlew :quickfixj-counterparty:run \
  --args="acceptor --port 9881 --version FIX.4.4 --sender QFJ --target ARTIO"

# a drop copy sender that logs on to 9880, sends the whole stream, reports what went out and exits
./gradlew :quickfixj-counterparty:run \
  --args="initiator --host localhost --port 9880 --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"

# the options
./gradlew :quickfixj-counterparty:run --args="--help"
```

`--scenario` takes `orders` (the whole drop copy stream), `drop-copy` (a synonym that says what it
is), `orders-only` (the five order events alone) or `none`.

Every message sent or received is printed with SOH shown as `|`. Real output, against
`:artio-spring-boot`'s Artio acceptor on 9880, trimmed to the first order and its two reports:

```
-> 8=FIX.4.2|9=69|35=A|34=1|49=QFJ|52=20260907-22:34:11.031|56=ARTIO|98=0|108=10|141=Y|10=137|
<- 8=FIX.4.2|9=69|35=A|34=1|49=ARTIO|52=20260907-22:34:10.935|56=QFJ|98=0|108=10|141=Y|10=149|
== logon FIX.4.2:QFJ->ARTIO
-> 8=FIX.4.2|9=130|35=D|34=2|49=QFJ|…|56=ARTIO|11=ORD-1|21=1|38=100|40=2|44=101.25|54=1|55=MSFT|59=0|60=…|10=112|
-> 8=FIX.4.2|9=159|35=8|34=3|49=QFJ|…|56=ARTIO|6=0|11=ORD-1|14=0|17=EXEC-1|20=0|37=ORDER-1|38=100|39=0|54=1|55=MSFT|60=…|150=0|151=100|10=089|
-> 8=FIX.4.2|9=180|35=8|34=4|49=QFJ|…|56=ARTIO|6=101.25|11=ORD-1|14=50|17=EXEC-2|20=0|31=101.25|32=50|37=ORDER-1|38=100|39=1|54=1|55=MSFT|60=…|150=1|151=50|10=042|
…
== sent scenario: [ORD-1, ORD-2, ORD-3, ORD-4, ORD-5]
== sent 15 message(s): 5 order event(s) and 10 execution report(s) [EXEC-1, EXEC-2, EXEC-3, EXEC-4, EXEC-5, EXEC-6, EXEC-7, EXEC-8, EXEC-9, EXEC-10]
== the peer answered with no execution reports, which is what a drop copy consumer does
```

**That last line is the expected result against Artio, not a failure.** A drop copy session sends
copies of an order session's traffic to a *consumer*; `:artio-engine` receives them, hands them to
its sink and answers only session-level traffic. What matters is what went out, which is what the
initiator now reports. Point the same command at `acceptor` mode — a venue, not a consumer — and
the line reads instead, again real output:

```
== the peer answered with 8 execution report(s): it is a venue, not a drop copy consumer
```

Eight, not ten: the acceptor answers the five order events (two reports per new order, one each for
the replace and the cancel) and answers an inbound `35=8` with nothing, as it should. The initiator
exits 0 once the stream has been sent and that short reply window has passed.

Both modes install the shutdown hook **before** `start()`, and both close the engine inside it, so
Ctrl-C sends a `Logout` rather than dropping the socket — the acceptor waiting for Ctrl-C and the
initiator waiting out its reply window alike. `QfjMainIT` proves it by running the hook.

`QfjMain.run(String[], PrintStream out, PrintStream err)` returns the exit code and does all
validation inside the usage path; only `main` calls `System.exit`. Exit codes: `0` ran,
`1` (`EXIT_NO_LOGON`) an initiator never logged on, `2` (`EXIT_BAD_ARGUMENTS`) bad arguments, with
the reason and the usage on `err`.

Config validation used to sit *outside* the catch, so `--port 0`, `--port 70000`, `--heartbeat 0`
and equal CompIDs printed a stack trace and the wrong code; `toConfig()` now runs inside it.
`--port` also distinguishes its two failures: `--port -5` and `--port 70000` are *out of range*, a
missing `--port` is *required*.

## The order scenario

`OrderScenario.DEFAULT` — a **drop copy** stream of fifteen application messages, all of them sent
by this side: five order events and the ten execution reports those orders produced on the venue the
copy comes from. Every identifier and every quantity is derived from the record's components, so a
test can name them before anything runs. Written out, and asserted row for row by
`DropCopyScenarioTest`:

| # | 35 | 11 | 41 | 37 | 17 | 150 | 39 | 38 | 32 | 31 | 151 | 14 | 6 | |
| --: | :-- | :-- | :-- | :-- | :-- | :-- | :-- | --: | --: | --: | --: | --: | --: | :-- |
| 1 | D | ORD-1 | | | | | | 100 | | | | | | new order |
| 2 | 8 | ORD-1 | | ORDER-1 | EXEC-1 | 0 | 0 | 100 | | | 100 | 0 | 0 | order ack |
| 3 | 8 | ORD-1 | | ORDER-1 | EXEC-2 | 1 / F | 1 | 100 | 50 | 101.25 | 50 | 50 | 101.25 | partial fill |
| 4 | D | ORD-2 | | | | | | 200 | | | | | | new order |
| 5 | 8 | ORD-2 | | ORDER-2 | EXEC-3 | 0 | 0 | 200 | | | 200 | 0 | 0 | order ack |
| 6 | 8 | ORD-2 | | ORDER-2 | EXEC-4 | 1 / F | 1 | 200 | 100 | 102.50 | 100 | 100 | 102.50 | partial fill |
| 7 | D | ORD-3 | | | | | | 300 | | | | | | new order |
| 8 | 8 | ORD-3 | | ORDER-3 | EXEC-5 | 0 | 0 | 300 | | | 300 | 0 | 0 | order ack |
| 9 | 8 | ORD-3 | | ORDER-3 | EXEC-6 | 1 / F | 1 | 300 | 150 | 103.75 | 150 | 150 | 103.75 | partial fill |
| 10 | 8 | ORD-3 | | ORDER-3 | EXEC-7 | 2 / F | 2 | 300 | 150 | 103.75 | 0 | 300 | 103.75 | fully filled |
| 11 | G | ORD-4 | ORD-1 | | | | | 150 | | | | | | amend |
| 12 | 8 | ORD-4 | ORD-1 | ORDER-1 | EXEC-8 | 5 | 1 | 150 | | | 100 | 50 | 101.25 | amend ack |
| 13 | 8 | ORD-4 | | ORDER-1 | EXEC-9 | 2 / F | 2 | 150 | 100 | 101.75 | 0 | 150 | 101.5833 | fully filled |
| 14 | F | ORD-5 | ORD-2 | | | | | 200 | | | | | | cancel |
| 15 | 8 | ORD-5 | ORD-2 | ORDER-2 | EXEC-10 | 4 | 4 | 200 | | | 0 | 100 | 102.50 | cancel ack |

Three things in it are the point:

* **An order keeps one `OrderID(37)` for its whole life.** `ORD-4` amends `ORD-1` and so reports
  `ORDER-1`; `ORD-5` cancels `ORD-2` and so reports `ORDER-2`. Three orders, three `OrderID`s, ten
  reports — which is what makes the bridge's `fix.order.state` topic, keyed on `/37`, collapse ten
  publishes into three records.
* **`AvgPx(6)` is the quantity-weighted average of the fills so far**, not the last price. Row 13 is
  `(50 x 101.25 + 100 x 101.75) / 150 = 101.5833`, to four decimal places.
* **The replace and the cancel reference earlier orders.** `fix.orders` is keyed on `ClOrdID(11)`
  and every request carries a distinct one, so five requests are five records — the audit shape.

`OrderScenario.ORDERS_ONLY` is the same five order events without the reports, for the tests that
are about order routing or about a peer that answers rather than about the drop copy. Against
`QfjAcceptor` either stream produces eight execution reports coming back: an acknowledgement and a
fill for each of the three orders, one `Replaced` and one `Canceled`. `ExecutionReports`, which
builds those, is the acceptor's side of a different topology and is not what the scenario sends.

A `NewOrderSingle` that is **missing a required body tag** — `OrderQty(38)` is the one that bites,
because `getDouble` throws `FieldNotFound` — is answered with a `BusinessMessageReject`
(`35=j`, `380=5` *conditionally required field missing*) naming the tag. Silence would be worse: a
counterparty that neither fills nor rejects looks identical to one that never received the order.

## Running the tests

```bash
./gradlew :quickfixj-counterparty:test              # 84 unit tests, no sockets
./gradlew :quickfixj-counterparty:integrationTest   # 12 tests: QuickFIX/J <-> QuickFIX/J on loopback
./gradlew :quickfixj-counterparty:build             # both (check depends on integrationTest)
```

* **Unit**: `SessionSettings` construction for both roles, the scenario's contents and identifiers,
  the drop copy stream row for row against the table above (`DropCopyScenarioTest`: types,
  identifiers, `OrderID` continuity, the running `LeavesQty`/`CumQty`/`AvgPx`, and the two
  version differences on the wire), the message builders for both versions (including the
  required-field differences), the `ExecutionReport` builder (both versions, all three request
  types, `OrderID` continuity across a replace), command-line parsing including every error message,
  and the raw-FIX helpers.
* **Integration** (`QfjToQfjIT`, parameterised over both versions): the acceptor receives all fifteen
  drop copy messages in order with the right `ClOrdID`s, `ExecID`s and `OrderID`s; the initiator gets
  the eight execution reports for `ORDERS_ONLY` with the right `ExecType`s and `OrderID` continuity;
  neither side emits a `Reject`, `BusinessMessageReject` or `OrderCancelReject` — which, with
  `UseDataDictionary=Y` at the acceptor, is what proves the reports this module builds are valid on
  both versions; an order missing `OrderQty` *does* come back as `35=j` `380=5`; closing the
  initiator logs the session out at the acceptor. `QfjMainIT` runs the CLI's shutdown hook and
  asserts the counterparty saw a `Logout`.

Ports come from `new ServerSocket(0)`; none is hard-coded, and nothing is written to disk.

## JVM flags

This module does not touch Artio or agrona, but the build puts the same flags on its tasks as
everywhere else, because its classes run inside `:artio-engine`'s integration-test JVM, which does:

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
```

See [`artio-engine/README.md`](../artio-engine/README.md) for the third flag, which only a process
that launches an Artio `FixEngine` needs.
