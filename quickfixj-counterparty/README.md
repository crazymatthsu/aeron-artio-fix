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
| `QfjVersion` | `FIX42` / `FIX44`, plus the two behavioural differences that matter: `hasExecTransType()`, `fillExecType()`, `requiresHandlInst()`. |
| `QfjRole` | `INITIATOR` / `ACCEPTOR`. |
| `QfjConfig` | Validated record + builder: version, address, CompIDs, heartbeat, screen log, reconnect and logon timeouts, reset-on-logon. |
| `QfjSettings` | Turns a `QfjConfig` into a QuickFIX/J `SessionSettings` describing exactly one session. |
| `QfjInitiator` | Connects out, logs on, sends orders, records everything. |
| `QfjAcceptor` | Binds, accepts, answers orders with execution reports, records everything. |
| `OrderScenario` | The scripted flow: 3 new orders, 1 cancel/replace, 1 cancel, deterministic `ClOrdID`s. |
| `Orders` | Builds `NewOrderSingle(D)`, `OrderCancelReplaceRequest(G)`, `OrderCancelRequest(F)` for either version. |
| `ExecutionReports` | Builds the venue's replies: ack + fill for `D`, one `Replaced` for `G`, one `Canceled` for `F`. |
| `RecordingApplication` | The `quickfix.Application`: records every message, and lets another thread await logon, logout or a matching message without polling. |
| `CapturedMessage` | One recorded message: direction, `MsgType`, raw FIX, `field(tag)`, `printable()`. |
| `CliArgs` / `QfjMain` | The command line. |

Messages are built as plain `quickfix.Message` objects with fields set **by tag number**, not with
`quickfix.fix42.NewOrderSingle` / `quickfix.fix44.NewOrderSingle`. One implementation then serves
both versions; the version-specific parts are exactly three: `HandlInst(21)` (required by FIX 4.2's
`NewOrderSingle`, absent from 4.4's), `ExecTransType(20)` (FIX 4.2 only) and the `ExecType(150)`
value for a fill (`2` in 4.2, `F` in 4.4).

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

# a trader that logs on to 9880, runs the scenario, waits for the reports and exits
./gradlew :quickfixj-counterparty:run \
  --args="initiator --host localhost --port 9880 --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"

# the options
./gradlew :quickfixj-counterparty:run --args="--help"
```

Every message sent or received is printed with SOH shown as `|`:

```
-> 8=FIX.4.2|9=133|35=D|34=2|49=TRADER|52=...|56=VENUE|11=ORD-1|21=1|38=100|40=2|44=101.25|54=1|55=MSFT|59=0|60=...|10=076|
<- 8=FIX.4.2|9=172|35=8|34=2|49=VENUE|52=...|56=TRADER|6=0|11=ORD-1|14=0|17=EXEC-1|20=0|37=ORDER-1|38=100|39=0|...|150=0|151=100|10=248|
== sent scenario: [ORD-1, ORD-2, ORD-3, ORD-4, ORD-5]
== received 8 execution report(s) of an expected 8
```

The initiator exits 0 once the scenario has run and the replies have stopped arriving (or the
10-second reply window expires — a counterparty that does not answer is not an error). The acceptor
runs until Ctrl-C, and closes the engine **inside** the shutdown hook so the counterparty gets a
`Logout` rather than a dropped socket.

## The order scenario

`OrderScenario.DEFAULT` — five application messages, identifiers derived from a prefix so a test can
name them before anything runs:

```
35=D  11=ORD-1                MSFT buy 100 @ 101.25
35=D  11=ORD-2                MSFT buy 200 @ 102.50
35=D  11=ORD-3                MSFT buy 300 @ 103.75
35=G  11=ORD-4  41=ORD-1      MSFT buy 150 @ 101.75
35=F  11=ORD-5  41=ORD-2      MSFT buy 200
```

The replace and the cancel deliberately reference earlier orders. The AMPS bridge keys `fix.orders`
on `ClOrdID(11)` and `fix.order.state` on `OrderID(37)`, and `ExecutionReports` gives a replaced
order the **same** `OrderID` as the original — so this scenario is what makes the SOW keying
observable rather than trivially true.

Against `QfjAcceptor` it produces eight execution reports: an acknowledgement and a fill for each of
the three orders, one `Replaced` and one `Canceled`.

## Running the tests

```bash
./gradlew :quickfixj-counterparty:test              # 51 unit tests, no sockets
./gradlew :quickfixj-counterparty:integrationTest   # 8 tests: QuickFIX/J <-> QuickFIX/J on loopback
./gradlew :quickfixj-counterparty:build             # both (check depends on integrationTest)
```

* **Unit**: `SessionSettings` construction for both roles, the scenario's contents and identifiers,
  the message builders for both versions (including the required-field differences), the
  `ExecutionReport` builder (both versions, all three request types, `OrderID` continuity across a
  replace), command-line parsing including every error message, and the raw-FIX helpers.
* **Integration** (`QfjToQfjIT`, parameterised over both versions): the acceptor receives every
  scenario message with the right `ClOrdID`s; the initiator gets the eight execution reports with
  the right `ExecType`s and `OrderID` continuity; neither side emits a `Reject`, `BusinessMessageReject`
  or `OrderCancelReject`; closing the initiator logs the session out at the acceptor.

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
