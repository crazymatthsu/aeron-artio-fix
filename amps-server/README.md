# amps-server

The AMPS instance this project publishes into: flow configuration, the compose
file, a lifecycle script, and the container recipe. **No Java.** The bridge and
the integration suites are the clients; this module is the server.

```
amps-server/
├── docker-compose.yml              one service, every value from the environment
├── Containerfile                   build an image from a 60East release tarball
├── config/
│   ├── README.md                   what a flow is, and the two rules these files break
│   └── flows/artio-fix/amps-config.xml
├── scripts/amps.sh                 start | wait | status | logs | stop | down | restart | config | printenv
├── vendor/README.md                where the tarball goes (gitignored)
└── data/<flow>/{sow,journal,stats} runtime state, gitignored, created on first start
```

## Quick start

```bash
amps-server/scripts/amps.sh start     # up -d, then block until AMPS says it is ready
amps-server/scripts/amps.sh status
amps-server/scripts/amps.sh logs --tail 50
amps-server/scripts/amps.sh down      # stop and remove; the data directory stays
```

`start` prints the two addresses you need:

```
  admin:  http://localhost:8085/
  client: tcp://127.0.0.1:9007/amps/fix
```

The script works from any working directory - it derives every path from its
own location - so `./amps-server/scripts/amps.sh start` from the repository
root and `/abs/path/to/amps.sh start` from anywhere else do the same thing.

Gradle wrappers exist for the same commands, for people who never leave the
build tool: `./gradlew :amps-server:ampsStart`, `ampsWait`, `ampsStatus`,
`ampsLogs`, `ampsStop`, `ampsDown`, `ampsRestart`, `ampsPrintEnv`.

## The topics

Every message on this instance is **raw FIX**: SOH-separated `tag=value`,
exactly the bytes that came off the session, never re-encoded. AMPS parses FIX
natively, so keys and filters name **tag numbers**. The client selects the
parser in its URI - `tcp://host:9007/amps/fix` - not per topic; connect on
`/amps/json` and nothing will parse.

| Topic | Shape | Key | Carries | Journalled |
| --- | --- | --- | --- | --- |
| `fix.raw` | pub/sub, no SOW | - | every application message, in arrival order | **yes** - this is the tape; bookmark-replay from `EPOCH` reads it back |
| `fix.orders` | SOW | `/11` ClOrdID | `35=D`, `35=G`, `35=F` - one record per request | **yes** - a system of record, nothing derives it |
| `fix.execs` | SOW | `/17` ExecID | every `35=8` - the fills history, one record per report | **yes** - a system of record; `fix.order.state` derives from it |
| `fix.order.state` | SOW | `/37` OrderID | `35=8` only - the latest report per order, which *is* the order's state | no - replaying `fix.execs` rebuilds it exactly |
| `fix.admin` | pub/sub, no SOW | - | session traffic (`0/1/2/3/4/5/A`), only when the bridge is configured to publish it | no - operational noise; Artio's own log is the durable session record |

`fix.raw` and `fix.admin` are not declared in `<SOW>` because they need no
declaration: an AMPS topic outside `<SOW>` is created on first publish and
keeps no state. They appear in the config only as comments and, for `fix.raw`,
as a `<TransactionLog>` entry.

Two consequences worth knowing before you write a publisher:

* **Publishing to a SOW topic without its key field loses data silently.**
  Measured against 5.3.5.135 with `MessageType fix`, AMPS does *not* reject
  such a publish (which is what the folklore says): it accepts it, logs
  nothing, tells the publisher nothing, and files it under one degenerate key
  shared by every keyless message - so three of them leave **one** record, the
  third. `35=D` carries no tag 37, so it must never reach `fix.order.state`.
  The bridge's `TopicRouter` tag-presence rule is the only guard there is; the
  reasoning is written next to each topic in
  `config/flows/artio-fix/amps-config.xml`.
* **`fix.orders` is keyed per request, not per chain.** A replaced order
  produces a second record under its new ClOrdID, with `41` pointing at the
  first. That is the audit shape and it is robust to out-of-order arrival.

## Where the data lands

`amps-server/data/<flow>/{sow,journal,stats}`, created on first start and
bind-mounted onto `/amps/data`. AMPS runs with that as its working directory,
so `./sow/fix-orders.sow` in the config is
`amps-server/data/artio-fix/sow/fix-orders.sow` on the host. It is gitignored,
`down` leaves it alone, and deleting it while the instance is down is how you
start clean.

Expect roughly 20MB of journal immediately: `MinJournalSize` is 10MB (the
floor - ask for less and AMPS resets it with a warning) and
`PreallocatedJournalFiles` is 2.

## Pointing at a different image

The default is `localhost/amps-demo:5.3.5.135`, the image the sibling
`amps-demo` project builds. Override it per invocation:

```bash
AMPS_IMAGE=localhost/artio-amps:5.3.5.135 amps-server/scripts/amps.sh start
```

To build one here instead, drop a 60East release tarball in `vendor/` and
follow the recipe in the header of [`Containerfile`](Containerfile). There is
no public AMPS server image; that is why nothing defaults to a registry.

## Everything the environment controls

All optional, all defaulted by `scripts/amps.sh`. Run
`amps-server/scripts/amps.sh printenv` to see what they resolved to without
touching a container.

| Variable | Default | |
| --- | --- | --- |
| `AMPS_IMAGE` | `localhost/amps-demo:5.3.5.135` | |
| `AMPS_FLOW` | `artio-fix` | selects `config/flows/<flow>` |
| `AMPS_PORT` | `9007` | host port for `amps/tcp` |
| `AMPS_WS_PORT` | `9008` | host port for websocket, needed by the admin SQL console |
| `AMPS_ADMIN_PORT` | `8085` | host port for the admin UI |
| `AMPS_PLATFORM` | `linux/amd64` | AMPS is x86_64 only; an Apple Silicon host emulates it |
| `AMPS_BIN` | `/opt/amps/bin/ampServer` | |
| `AMPS_CONTAINER_NAME` | `artio-amps` | |
| `AMPS_COMPOSE_PROJECT` | `artio-amps` | compose project name |
| `AMPS_WAIT_TIMEOUT` | `120` | seconds `wait` will block; emulation makes a first start slow |

All three ports are published on **`127.0.0.1` only**. `docker-compose.yml`
writes them as `127.0.0.1:${AMPS_PORT}:9007` and so on, so a demo AMPS with no
authentication is not reachable from the network the laptop is on. Change the
compose file if you genuinely need a remote client.

## Tests

```bash
./gradlew :amps-server:check
```

`checkConfigXml` parses every `config/flows/*/amps-config.xml` and, before
that, scans for two things a comment must not contain:

* a `--` inside an XML comment. XML forbids it and AMPS refuses the config for
  it, and these files are three-quarters explanatory prose, so it is the
  mistake they are most prone to. The scan runs first because the XML parser's
  own message for it names no line.
* the startup log line `AMPS initialization completed`, anywhere in the file.
  AMPS echoes its whole config into its own log at startup, comments included,
  about a second *before* it is listening — so a comment quoting the readiness
  marker makes every reader watching for that phrase declare the server ready
  while it is still starting. A config that mentions it is rejected here rather
  than debugged later; see [`../docs/05-integration-testing-and-demo.md`](../docs/05-integration-testing-and-demo.md)
  section 3.

## Integration tests do not use this instance

`amps-test-harness` starts its **own** container on free ports with its own
data directory, from this same compose file and this same flow. Nothing a test
does can disturb an instance you started by hand, and `./gradlew build` never
needs one running. See [`../amps-test-harness/README.md`](../amps-test-harness/README.md)
and [`../docs/05-integration-testing-and-demo.md`](../docs/05-integration-testing-and-demo.md).
