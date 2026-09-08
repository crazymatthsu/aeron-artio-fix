# 07 - Execution reports on the drop copy stream

## 1. What was wrong with the demo

The demo in the root README is:

```bash
./gradlew :artio-spring-boot:bootRun                          # Artio acceptor on 9880 + the AMPS bridge
./gradlew :quickfixj-counterparty:run --args="initiator --host localhost --port 9880 \
    --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"
```

The QuickFIX/J side is a **drop copy** session. It does not send orders to a venue and wait to be
filled: it sends copies of an order session's traffic - `35=D`, `35=G`, `35=F` and `35=8` - to a
consumer. The Artio acceptor is that consumer. It receives, it publishes to AMPS, and it correctly
answers nothing.

So no execution report was missing from the Artio side. One was missing from the **sender**:
`OrderScenario` scripts five messages, all of them order events, and not a single `35=8`. The
drop copy therefore shows orders being placed, amended and cancelled, and never shows one being
acknowledged or filled - the traffic that on a real drop copy feed outnumbers everything else.

It also leaves two thirds of the bridge unexercised. `bridge.properties` already routes `35=8`:

```
bridge.route[3].msgType=8   topic=fix.execs        requiredTag=17   (SOW key /17, ExecID)
bridge.route[4].msgType=8   topic=fix.order.state  requiredTag=37   (SOW key /37, OrderID)
```

Nothing in the demo has ever produced a message that matches either rule, so `fix.execs` and
`fix.order.state` are always empty, and the one thing the `/37` keying exists to show - an order
record that is overwritten in place as the order progresses - has never been visible.

## 2. The fix: script the reports into the stream

`OrderScenario` is already documented as "the scripted order flow every demo and every bridge test
replays". Script the execution reports into it, as ordinary steps sent by the same initiator. No
new module, no responder, no change to the Artio side, which keeps doing exactly what a drop copy
consumer should do.

The scenario grows from 5 messages to 15: the same five order events, each followed by the
execution reports that order event would produce on the venue the copy comes from.

| # | 35 | 11 ClOrdID | 41 OrigClOrdID | 37 OrderID | 17 ExecID | 150 ExecType | 39 OrdStatus | 38 OrderQty | 32 LastQty | 31 LastPx | 151 LeavesQty | 14 CumQty | 6 AvgPx | |
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

Reading the table:

* **Order ack**, **partial fill**, **fully filled**, **amend ack** and **cancel ack** each appear,
  which is what the demo was missing.
* An order keeps one `OrderID(37)` for its whole life. `ORD-4` amends `ORD-1`, so its reports carry
  `ORDER-1`; `ORD-5` cancels `ORD-2`, so its report carries `ORDER-2`. Three orders, three
  `OrderID`s, ten reports.
* `ORD-3` runs the common path to completion on its own: ack, partial fill, fill.
* `ORD-1` is half filled, then amended up to 150, then completed. `AvgPx` on row 13 is the
  quantity-weighted average of both fills, `(50 x 101.25 + 100 x 101.75) / 150 = 101.5833`, not the
  last price. Rounded to four decimal places.
* `ORD-2` is half filled and then cancelled, so its cancel acknowledgement reports `CumQty=100` and
  `LeavesQty=0` rather than a clean zero.
* `ExecType(150)` for a trade is version dependent: FIX 4.2 distinguishes `1` (partial fill) from
  `2` (fill), FIX 4.4 replaced both with `F` (Trade) and leaves the distinction to `OrdStatus`.
  `ExecTransType(20)=0` is required on every FIX 4.2 report and does not exist in FIX 4.4. Both
  differences already have a home in `QfjVersion`.

## 3. What it does to the AMPS side

With the default routing rules, the fifteen messages become forty publishes:

| Topic | SOW key | Publishes | Records |
| :-- | :-- | --: | --: |
| `fix.raw` | none, journalled | 15 | n/a |
| `fix.orders` | `/11` | 5 | 5 |
| `fix.execs` | `/17` | 10 | 10 |
| `fix.order.state` | `/37` | 10 | 3 |

`fix.order.state` is the interesting one and the reason the topic exists: ten publishes collapse
into three records, one per order, each holding that order's latest state. After the run,
`ORDER-1` reads `Filled`, `ORDER-2` reads `Canceled` and `ORDER-3` reads `Filled`.

## 4. Shape of the change

All of it is in `quickfixj-counterparty`.

* `OrderScenario` gains a `Report` step alongside `NewOrder`, `Replace` and `Cancel`, carrying the
  fields in the table above. `steps()` returns the fifteen-step stream. `MESSAGE_COUNT` becomes 15;
  `ORDER_COUNT` (5) and `EXECUTION_REPORT_COUNT` (10) are added, and `clOrdId(int)` ranges over
  `ORDER_COUNT`, not over the message count.
* `OrderScenario.ORDERS_ONLY` keeps the old five-event stream, for the tests that are about order
  routing rather than about the drop copy.
* `Orders.toMessage` builds a `35=8` for a `Report` step, using the version's `ExecType` mapping and
  writing `ExecTransType` only on FIX 4.2.
* `QfjMain` reports what it sent rather than waiting for replies that a drop copy consumer will
  never send, and `--scenario` accepts `orders` (the full stream, unchanged spelling so the README
  command still works), `drop-copy` (a synonym) and `orders-only`.

`ExecutionReports`, which turns an inbound order into replies for the QuickFIX/J **acceptor** mode,
is a different feature for a different topology and is left alone.
