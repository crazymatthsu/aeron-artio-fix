# Integration testing and the demo

How an integration suite in this repository gets a real AMPS, why it is driven
the way it is, and what went wrong on the way there. Written from what was
actually run on 2026-09-06 against podman 5.8.2 / podman-compose 1.6.0 and
`localhost/amps-demo:5.3.5.135` on Apple Silicon.

The demo runbook is the second half of this document (section 9). It was run
end to end on 2026-09-07 and every block of output in it is real.

## 1. One AMPS, two callers

There is exactly one description of the AMPS instance -
`amps-server/docker-compose.yml` plus a flow directory under
`amps-server/config/flows/` - and two things that start it:

| Caller | For | Ports | Data |
| --- | --- | --- | --- |
| `amps-server/scripts/amps.sh` | a developer, by hand | fixed 9007 / 9008 / 8085 | `amps-server/data/<flow>/` |
| `com.demo.artio.testharness.AmpsComposeServer` | an `integrationTest` task | three free ports | `build/amps-it/<project>/` |

Both export the same variable names, so the two produce identical containers
apart from ports, name and data directory. That is the point: a test cannot
pass against a configuration nobody runs, and a config change cannot be
"tested" without also being the one a developer gets.

## 2. Driving podman compose

### The invocation that works

```
podman compose -p <project> -f <absolute path>/amps-server/docker-compose.yml up -d
podman compose -p <project> -f <absolute path>/amps-server/docker-compose.yml down -v
podman compose -p <project> -f <absolute path>/amps-server/docker-compose.yml ps
podman compose -p <project> -f <absolute path>/amps-server/docker-compose.yml config
```

with every `${VAR}` supplied through the **child process environment**, and the
container log read separately with `podman logs <container>`.

Fallback order when `podman compose version` fails: standalone
`podman-compose`, then `docker compose`. Both the script and the harness
implement the same ladder — and both then derive **the engine** from the rung
that succeeded, not from `CONTAINER_ENGINE`. Without that, a machine whose
podman compose shim is broken falls back to `docker compose` for `up` while
`logs`, `ps` and `restart` keep asking podman, which reports nothing at all and
looks exactly like a container that never started. The engine and the compose
command are one decision, made once.

The compose file path is always absolute, in both callers. `amps.sh` derives it
from `${BASH_SOURCE[0]}` and the harness from the repository root, so neither
depends on the working directory. The plan writes the invocation as
`-f amps-server/docker-compose.yml`; that is the same file, spelled
relatively, and it only works from the repository root.

### Quirks found

**podman 5's `compose` is a shim, and it announces itself on stderr.**
Every invocation prints:

```
>>>> Executing external compose provider "/usr/local/bin/podman-compose".
     Please see podman-compose(1) for how to disable this message. <<<<
```

It is noise, not a diagnostic. Both callers merge stderr into stdout and judge
success by **exit status only**; anything that treated stderr output as failure
would fail on every single call.

**`compose logs` is not a reliable way to read the log.** The compose file pins
`container_name`, so the container is not named `<project>_amps_1` and
podman-compose's own log lookup cannot be relied on. Both callers use
`podman logs <container_name>` instead - the name is ours, because the compose
file sets it - and that always works. The same applies to `restart`: the
harness calls `podman restart <name>` rather than `compose restart`.

**`container_name` must therefore be unique per instance.** The harness sets
`AMPS_CONTAINER_NAME` to the compose project name, `artio-amps-it-<port>`, so
two suites in one Gradle run cannot collide - a fixed `container_name` in a
compose file is a global namespace, and compose's own project isolation does
not save you from it.

**Environment variables reach compose through the process environment, not an
`.env` file.** There is no `.env` in this module. `amps.sh` `export`s them;
`AmpsComposeServer` puts them in the `ProcessBuilder` environment. `${VAR:?msg}`
in the compose file then fails with a sentence instead of the engine's "invalid
reference format" when one is missing.

**`down -v` on a project whose volumes are bind mounts is safe.** It removes
the container and the compose-created network; the host directories are
untouched, which is why `close()` deletes the data directory itself.

## 3. Readiness detection: two traps

**An open port is not readiness.** The engine's port forwarder accepts
connections as soon as the container exists, so a client that races it connects
and is then dropped mid-logon ("Socket closed"). AMPS is ready when it says so,
in its log.

**And matching the English phrase is not enough**, which is the trap this
repository found the hard way. AMPS echoes **the entire config file into its
own log at startup, comments included** - about 500 of the first 1600 lines on
5.3.5.135. So a config comment that quotes the startup-complete line appears in
the log roughly a second *before* the server is listening, and every reader
watching for the bare phrase is fooled by the file it is about to run.

The marker both callers match is therefore the line **including its numeric
message code**:

```
00-0015 AMPS initialization completed
```

And because that is a trap a future config comment could re-open in one
sentence, `:amps-server:checkConfigXml` **rejects any flow config that mentions
`AMPS initialization completed` at all**, comment or not. The build refuses the
config rather than leaving someone to debug a readiness check that fires a
second early.

Two further consequences, both learned by watching a wait loop time out on a
server that had been up for two minutes:

* **Probing the port costs five log lines a second.** Every accepted probe
  connection logs `New Client Connection`, a three-line client-info block, and
  `disconnected`. Polling once a second for two minutes buries the startup line
  under six hundred lines of the poller's own noise - so the log check comes
  *first* and the port check only confirms it, and neither caller uses a
  `--tail` window.
* **`grep -q` plus `set -o pipefail` is a silent trap in bash.** `grep -q`
  exits on the first match, `podman logs` takes SIGPIPE, podman exits non-zero,
  and `pipefail` reports the whole pipeline as failed - so the test says "not
  ready" at the exact moment it found the marker. `amps.sh` captures the log
  into a variable and pattern-matches it instead. (This does not reproduce under
  zsh, which is a good way to waste an afternoon.)

**A restart needs a *new* announcement.** The previous run's line is still in
the log afterwards, so `AmpsComposeServer.restart()` counts markers before the
bounce and waits for the count to increase. Testing for presence would return
instantly and hand back a server that is still starting.

**Fail fast on a crash loop.** While waiting, both callers check the container
is still running (`podman ps --format {{.Names}}`) and abort with the log
attached rather than waiting out the timeout. A config the server refuses is
the common cause, and the reason is in the log.

**Timings, for calibration.** A fresh start on this machine - emulated amd64,
empty data directory, 20MB of journal preallocated - reaches ready in about
**3-4 seconds** wall clock; the server itself reports `(1 seconds)`. The
harness allows three minutes anyway, because the first pull or a cold VM is a
different animal. The whole eleven-test integration suite, container start and
one restart included, runs in about **16 seconds**.

## 4. Isolation

Nothing a test does can disturb an instance started by hand, or another suite
in the same Gradle run.

* **Three free host ports** per instance, from `ServerSocket(0)`. All three
  sockets are held open until the last port is chosen, so the OS cannot hand
  the same number out twice within one start. No fixed port appears in any
  test.
* **Compose project name = container name = `artio-amps-it-<port>`.** Derived
  from the port, so it is unique for the same reason the port is, and a leaked
  container says which suite left it behind.
* **Data under `<repo>/build/amps-it/<project>/{sow,journal,stats}`**, created
  empty before start. Under `build/` for two reasons: `gradle clean` disposes
  of it, and it stays inside the directory a podman machine shares on macOS -
  a bind mount from outside the shared path fails in a way that looks like a
  permissions problem.
* **`:z` on bind mounts only on Linux.** SELinux hosts need the label; podman's
  macOS VM rejects it on a virtiofs mount.
* **Every port published on `127.0.0.1` only** — `127.0.0.1:${AMPS_PORT}:9007`
  and so on in the compose file. Isolation from the network, not just from
  other suites: an AMPS instance in this project has no authentication, and a
  hand-started one on a laptop in a coffee shop should not be a service.

## 5. Skip rules

There is no public AMPS server image - 60East ships the server as a licensed
release tarball - so "no image" is an ordinary state of a developer machine.
`./gradlew build` must stay green there and do real work where an image exists.

`AmpsComposeServer.unavailableReason()` returns a reason, in this order:

1. **`AMPS_IT=false`.** Checked first, before anything shells out, so the
   switch works on a machine with no podman at all.
2. **`CONTAINER_ENGINE`** (default `podman`) is not on `PATH`.
3. **No usable compose implementation**: `<engine> compose version` fails and
   neither `podman-compose` nor `docker compose` works.
4. **`<engine> image inspect $AMPS_IMAGE`** says the image is not known.

   `image inspect`, not `podman image exists`: the latter is a podman-only
   verb, so under `CONTAINER_ENGINE=docker` it fails on every machine and the
   whole suite skips for the wrong reason. `<engine>` is the engine of the
   compose rung that succeeded above. And only "not known" counts as a missing
   image — every **other** engine failure (a stopped podman machine exiting
   125, a refused socket) is reported verbatim as a check failure, because
   "there is no image here" and "the container engine is broken" want different
   things done about them.

`AmpsAssumptions.assumeAvailable()` turns that into a JUnit 5 assumption with
the reason as the skip message, which is why `amps-test-harness` puts JUnit on
its `api` configuration rather than `testImplementation`: the assumption is
part of the harness's surface, not of its own tests.

`AMPS_IT` only ever switches the suite **off**. `AMPS_IT=true` cannot force a
run on a machine with no engine; it would turn a clean skip into a confusing
failure.

## 6. Environment variables

Read by the harness and the script:

| Variable | Default | |
| --- | --- | --- |
| `AMPS_IMAGE` | `localhost/amps-demo:5.3.5.135` | must exist locally |
| `AMPS_PLATFORM` | `linux/amd64` | AMPS is x86_64 only; Apple Silicon emulates |
| `AMPS_BIN` | `/opt/amps/bin/ampServer` | |
| `CONTAINER_ENGINE` | `podman` | harness only |
| `AMPS_IT` | unset | `false` skips |
| `AMPS_FLOW` | `artio-fix` | script only; the harness takes the flow as an argument |

Exported *to* compose by both callers, and pointless to set yourself:
`AMPS_PORT`, `AMPS_WS_PORT`, `AMPS_ADMIN_PORT`, `AMPS_CONTAINER_NAME`,
`AMPS_CONFIG_VOLUME`, `AMPS_DATA_VOLUME`.

## 7. Gradle wiring, and the cached-green-build hazard

Every module that needs AMPS gets an `integrationTest` source set and task,
`check` depends on it, and `workingDir = rootProject.projectDir`.

The task declares `AMPS_IMAGE`, `AMPS_PLATFORM`, `CONTAINER_ENGINE` and
`AMPS_IT` as **inputs**, not merely forwards them. This repository sets
`org.gradle.caching=true`, and an environment variable that is only forwarded
is invisible to the cache key - so a run without an image caches an all-skipped
result and the next run *with* one restores it:

```
> Task :amps-test-harness:integrationTest FROM-CACHE
BUILD SUCCESSFUL
...11 tests, 11 skipped
```

A green build that ran nothing, which is the worst available outcome for a
suite designed to skip itself.

Declaring the inputs fixes the *wrong* restore but not the *empty* one: whether
this suite can do anything depends on podman being installed and running, the
image being present and three ports being free, none of which Gradle can see.
So the task is additionally excluded from the cache and from up-to-date checks
(`outputs.cacheIf { false }`, `outputs.upToDateWhen { false }`). Asking for it
runs it. For a unit suite that would be wasteful; for the one suite whose job
is to prove a container really starts, a cached pass is the failure mode it
exists to catch.

The signal to look for either way is **`0 skipped`**.

## 8. What the harness suite actually proves

`AmpsComposeServerIT` starts the `artio-fix` flow, publishes one hand-built FIX
4.2 NewOrderSingle to `fix.orders` (SOW, keyed `/11`) and `fix.raw` (pub/sub,
journalled), `publishFlush`es, and then asserts:

* a SOW query filtered `/11 = '<ClOrdID>'` returns **exactly one** record, and
  its bytes are **identical** to what was published, SOH separators included -
  AMPS does not re-encode or normalise a FIX payload;
* a filter that matches nothing returns nothing, so the assertion above cannot
  be passing because the filter is ignored;
* a bookmark subscription from `EPOCH` on `fix.raw` replays **1** message -
  the journal really is recording it;
* after `restart()`, both answers are unchanged - the SOW file and the journal
  are on a host bind mount and survive a bounce;
* the admin port is mapped and serves HTTP 200 (the third of the three free
  ports; nothing else here would notice if it were mapped wrongly).

Facts confirmed against 5.3.5.135 that later phases can rely on:

* **A FIX payload round-trips byte for byte** through a SOW topic. An assertion
  can compare against exactly what the publisher sent.
* **`publishFlush` is not optional.** Publishes are asynchronous; without it a
  SOW query races the publish and intermittently finds nothing.
* **The message type is a property of the connection, not the topic.**
  `tcp://host:9007/amps/fix`. Connect on `/amps/json` and the client connects
  and then silently parses nothing.

### 8.1 A correction the bridge phase needs

`docs/00` section 2 records, as a verified fact carried over from the
`amps-demo` project, that *"a SOW publish lacking its key field is rejected"*.
**On 5.3.5.135 with `MessageType fix`, it is not.** Probed directly against
this repository's `artio-fix` flow:

| What was published to `fix.order.state` (keyed `/37`) | What happened |
| --- | --- |
| one `35=8` **with** tag 37 | stored under its OrderID, as expected |
| one `35=8` **without** tag 37 | **accepted**. No exception at the client, no ack failure, nothing in the server log. Stored under a synthetic key. |
| three more `35=8` without tag 37 | all four keyless messages share **one** record. Only the last survives. |

So the failure mode is not a rejection the publisher can count. It is silent,
unbounded loss that presents as "the SOW looks short", with the journal (if the
topic is journalled) as the only surviving evidence.

Two consequences:

1. **`TopicRouter`'s "require this tag before routing here" rule is the only
   guard that exists.** Not a mirror of a server-side check - the server has
   none. It should be treated as a correctness requirement of the bridge and
   tested directly, and a message that matches no rule should be counted, not
   dropped quietly.
2. **`fix.raw` earns its keep.** It is unkeyed and journalled, so a message
   mis-routed into a keyless bucket is still recoverable from the tape.

### 8.2 Expect a regex warning at every startup

```
00-0007 Topic 'fix.orders' has regular expression characters which
        could cause unintentional misrouting of messages.
```

once per SOW topic. It is the dot in the name. Checked rather than assumed:
publishing to `fixZorders` does **not** land in `fix.orders` (the SOW query
returns nothing, and `fixZorders` is not a topic at all), so these names route
literally and the warning is noise. Do not rename the topics to silence it -
they are the names the plan, the bridge and every consumer use. `<Pattern>`,
unlike `<Name>`, really is a regex.

## 9. Demo runbook

Six commands, from the repository root, **one process at a time**. Everything
below is the real output of runs on 2026-09-07 (podman 5.8.2,
`localhost/amps-demo:5.3.5.135`, Corretto/Temurin 21 on Apple Silicon), trimmed
to the interesting lines. SOH is shown as `|`. Steps 3 to 6 were re-captured
when the execution reports were scripted into the scenario (`docs/07`), from a
run of the same application on a second port, so the runtime id there
(`bridge-acceptor-81490-1`) is not the one step 2 shows.

Prerequisite: the AMPS image exists locally. There is no public one — build it
from a 60East release tarball with `amps-server/Containerfile`, or reuse the
sibling `amps-demo` project's `localhost/amps-demo:5.3.5.135`, which is the
default.

### 1. AMPS

```bash
amps-server/scripts/amps.sh start
```

```
starting artio-amps
  flow:   artio-fix  (…/amps-server/config/flows/artio-fix/amps-config.xml)
  image:  localhost/amps-demo:5.3.5.135  (linux/amd64)
  ports:  9007 tcp / 9008 ws / 8085 admin
  data:   …/amps-server/data/artio-fix
>>>> Executing external compose provider "/usr/local/bin/podman-compose". …
waiting for AMPS on port 9007 .. ready
  admin:  http://localhost:8085/
  client: tcp://127.0.0.1:9007/amps/fix
```

The compose-provider banner on stderr is noise, not a diagnostic (section 2).
`ready` means the `00-0015 AMPS initialization completed` line appeared in the
container log, not merely that the port answers. About 4 seconds here.

The **admin UI** is now at <http://localhost:8085/> — topic counts, SOW sizes,
and an SQL console over the websocket transport. Useful as a second opinion on
everything `sowDump` prints.

### 2. The engine and the bridge

```bash
./gradlew :artio-spring-boot:bootRun
```

Wait for the acceptor-bound line — steps 3 to 5 need it:

```
INFO  AmpsClientConnection      connected to AMPS at tcp://localhost:9007/amps/fix as artio-bridge-1498374805331791
INFO  AmpsFixPublisher          bridge publishing to tcp://localhost:9007/amps/fix via [* -> fix.raw,
                                35=D -> fix.orders requires 11, 35=G -> fix.orders requires 11,
                                35=F -> fix.orders requires 11, 35=8 -> fix.execs requires 17,
                                35=8 -> fix.order.state requires 37]; ring buffer 4194304 bytes, overflow DROP_AND_COUNT
INFO  BridgePublisherLifecycle  amps publisher started: tcp://localhost:9007/amps/fix -> […]
INFO  ArtioRuntime              bridge-acceptor-52239-1 started: mode=ACCEPTOR FIX.4.2 0.0.0.0:9880 ARTIO->QFJ
                                dir=/var/folders/…/T/artio-bridge-acceptor-52239-1
INFO  ArtioRuntimeLifecycle     artio runtime started: acceptor listening bridge-acceptor-52239-1 FIX.4.2 on 0.0.0.0:9880 as ARTIO->QFJ
INFO  ArtioBridgeApplication    Started ArtioBridgeApplication in 0.891 seconds (process running for 0.995)
INFO  StatsLogger               stats logging every 5000ms
```

Publisher first, engine second — that is the lifecycle ordering doing its job
(`docs/04`). `bridge-acceptor-**52239**-1` is the runtime id: name, mode, **PID**,
counter. The PID is there so two processes with the same name cannot delete
each other's live Aeron directory.

Then every five seconds, until a counterparty arrives:

```
INFO  StatsLogger  stats: accepted=0 published=0 pending=0 dropped=0 unroutable=0 errors=0 lost=0
                    bytes=0 ring=0/4194304 connected [fix.raw={published=0}, …]
INFO  StatsLogger  sessions: none logged on
```

**Plain-Java alternative.** `./gradlew :artio-amps-bridge:run` is the same
engine, the same publisher, the same threads, configured from
`artio-amps-bridge/bridge.properties` instead of `application.yml` and with a
hand-written shutdown hook instead of Spring's phases. Every later step is
identical. Use it to see that nothing on the message path is Spring's.

### 3. The other FIX engine

In a second terminal:

```bash
./gradlew :quickfixj-counterparty:run --args="initiator --host localhost --port 9880 \
    --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"
```

This is a **drop copy** session: fifteen application messages, all of them
*sent* — five order events and the ten execution reports those orders produced
on the venue the copy comes from (`docs/07`). Real output, timestamps trimmed:

```
-> 8=FIX.4.2|9=69|35=A|34=1|49=QFJ|52=20260907-22:34:11.031|56=ARTIO|98=0|108=10|141=Y|10=137|
<- 8=FIX.4.2|9=69|35=A|34=1|49=ARTIO|52=20260907-22:34:10.935|56=QFJ|98=0|108=10|141=Y|10=149|
== logon FIX.4.2:QFJ->ARTIO
-> 8=FIX.4.2|9=130|35=D|34=2|49=QFJ|…|56=ARTIO|11=ORD-1|21=1|38=100|40=2|44=101.25|54=1|55=MSFT|59=0|60=…|10=112|
-> 8=FIX.4.2|9=159|35=8|34=3|49=QFJ|…|56=ARTIO|6=0|11=ORD-1|14=0|17=EXEC-1|20=0|37=ORDER-1|38=100|39=0|54=1|55=MSFT|60=…|150=0|151=100|10=089|
-> 8=FIX.4.2|9=180|35=8|34=4|49=QFJ|…|56=ARTIO|6=101.25|11=ORD-1|14=50|17=EXEC-2|20=0|31=101.25|32=50|37=ORDER-1|38=100|39=1|54=1|55=MSFT|60=…|150=1|151=50|10=042|
-> 8=FIX.4.2|9=129|35=D|34=5|49=QFJ|…|56=ARTIO|11=ORD-2|21=1|38=200|40=2|44=102.5|54=1|55=MSFT|59=0|60=…|10=082|
-> 8=FIX.4.2|9=159|35=8|34=6|49=QFJ|…|56=ARTIO|6=0|11=ORD-2|14=0|17=EXEC-3|20=0|37=ORDER-2|38=200|39=0|54=1|55=MSFT|60=…|150=0|151=200|10=104|
-> 8=FIX.4.2|9=181|35=8|34=7|49=QFJ|…|56=ARTIO|6=102.5|11=ORD-2|14=100|17=EXEC-4|20=0|31=102.5|32=100|37=ORDER-2|38=200|39=1|54=1|55=MSFT|60=…|150=1|151=100|10=091|
-> 8=FIX.4.2|9=130|35=D|34=8|49=QFJ|…|56=ARTIO|11=ORD-3|21=1|38=300|40=2|44=103.75|54=1|55=MSFT|59=0|60=…|10=141|
-> 8=FIX.4.2|9=159|35=8|34=9|49=QFJ|…|56=ARTIO|6=0|11=ORD-3|14=0|17=EXEC-5|20=0|37=ORDER-3|38=300|39=0|54=1|55=MSFT|60=…|150=0|151=300|10=119|
-> 8=FIX.4.2|9=184|35=8|34=10|49=QFJ|…|56=ARTIO|6=103.75|11=ORD-3|14=150|17=EXEC-6|20=0|31=103.75|32=150|37=ORDER-3|38=300|39=1|54=1|55=MSFT|60=…|150=1|151=150|10=016|
-> 8=FIX.4.2|9=182|35=8|34=11|49=QFJ|…|56=ARTIO|6=103.75|11=ORD-3|14=300|17=EXEC-7|20=0|31=103.75|32=150|37=ORDER-3|38=300|39=2|54=1|55=MSFT|60=…|150=2|151=0|10=137|
-> 8=FIX.4.2|9=135|35=G|34=12|49=QFJ|…|56=ARTIO|11=ORD-4|21=1|38=150|40=2|41=ORD-1|44=101.75|54=1|55=MSFT|60=…|10=176|
-> 8=FIX.4.2|9=175|35=8|34=13|49=QFJ|…|56=ARTIO|6=101.25|11=ORD-4|14=50|17=EXEC-8|20=0|37=ORDER-1|38=150|39=1|41=ORD-1|54=1|55=MSFT|60=…|150=5|151=100|10=157|
-> 8=FIX.4.2|9=184|35=8|34=14|49=QFJ|…|56=ARTIO|6=101.5833|11=ORD-4|14=150|17=EXEC-9|20=0|31=101.75|32=100|37=ORDER-1|38=150|39=2|54=1|55=MSFT|60=…|150=2|151=0|10=247|
-> 8=FIX.4.2|9=115|35=F|34=15|49=QFJ|…|56=ARTIO|11=ORD-5|38=200|41=ORD-2|54=1|55=MSFT|60=…|10=056|
-> 8=FIX.4.2|9=174|35=8|34=16|49=QFJ|…|56=ARTIO|6=102.5|11=ORD-5|14=100|17=EXEC-10|20=0|37=ORDER-2|38=200|39=4|41=ORD-2|54=1|55=MSFT|60=…|150=4|151=0|10=101|
== sent scenario: [ORD-1, ORD-2, ORD-3, ORD-4, ORD-5]
== sent 15 message(s): 5 order event(s) and 10 execution report(s) [EXEC-1, EXEC-2, EXEC-3, EXEC-4, EXEC-5, EXEC-6, EXEC-7, EXEC-8, EXEC-9, EXEC-10]
== the peer answered with no execution reports, which is what a drop copy consumer does
-> 8=FIX.4.2|9=52|35=5|34=17|49=QFJ|…|56=ARTIO|10=123|
<- 8=FIX.4.2|9=51|35=5|34=2|49=ARTIO|…|56=QFJ|10=064|
== logout FIX.4.2:QFJ->ARTIO
```

Three lines are worth reading twice:

* **`150=1` then `150=2`** on `ORD-3`, and `39=1` then `39=2`: a partial fill and
  then the fill. On FIX 4.4 both would be `150=F` and only `OrdStatus` would
  tell them apart. `20=0` is `ExecTransType`, which FIX 4.2 requires and FIX 4.4
  removed.
* **`6=101.5833`** on `EXEC-9`: `AvgPx` is the quantity-weighted average of both
  fills of `ORD-1`, `(50 × 101.25 + 100 × 101.75) / 150`, not the last price.
* **`== the peer answered with no execution reports`** is the correct result.
  `:artio-engine` is a receiving gateway: it hands every inbound message to a
  sink and answers session-level traffic (there is the `35=5` logout), but
  nothing in it books or fills an order — and on a drop copy nothing should.
  Point the same command at `quickfixj-counterparty acceptor`, which is a venue,
  and the line reads `the peer answered with 8 execution report(s)`.

Meanwhile in the bootRun terminal (this capture is from `bootJar`'s output run
as `java -jar` on a second port — the same application, the same lines):

```
INFO  ArtioRuntime  bridge-acceptor-81490-1 acquired acceptor[FIX.4.2 ARTIO<->QFJ id=1]
INFO  StatsLogger   stats: accepted=16 published=40 pending=0 dropped=0 unroutable=0 errors=0 lost=0
                    bytes=7409 ring=0/4194304 connected [fix.raw={published=15}, fix.orders={published=3},
                    fix.orders={published=1}, fix.orders={published=1}, fix.execs={published=10},
                    fix.order.state={published=10}]
INFO  StatsLogger   sessions: [acceptor[FIX.4.2 ARTIO<->QFJ id=1]]
INFO  ArtioRuntime  bridge-acceptor-81490-1: acceptor[FIX.4.2 ARTIO<->QFJ id=1] disconnected: LOGOUT
```

The numbers to read:

* **`accepted=16`** — every inbound message, admin included: fifteen application
  messages plus the `Logon` (the `Logout` a second later makes it 17). Admin
  messages are counted and then skipped, because
  `bridge.publish-admin-messages` is `false`.
* **`published=40`** — publishes, **summed over topics**: fifteen to `fix.raw`,
  five to `fix.orders` (3 × `35=D`, 1 × `35=G`, 1 × `35=F` — three separate
  routes, which is why the topic appears three times in the list), ten to
  `fix.execs` and ten to `fix.order.state`. One message on three topics is three
  publishes.
* **`dropped=0 unroutable=0 lost=0 pending=0`** — the three ways a message can
  fail to arrive, and the backlog. All zero: nothing was lost, every `35=8`
  carried both tag 17 and tag 37, and the ring is drained.

### 4. Did it arrive?

```bash
./gradlew :artio-amps-bridge:sowDump --args="--topic fix.orders"
```

```
SOW fix.orders on tcp://localhost:9007/amps/fix
  [1] 8=FIX.4.2|9=130|35=D|34=2|49=QFJ|52=20260907-22:34:11.092|56=ARTIO|11=ORD-1|21=1|38=100|40=2|44=101.25|54=1|55=MSFT|59=0|60=…|10=112|
  [2] 8=FIX.4.2|9=129|35=D|34=5|49=QFJ|52=20260907-22:34:11.095|56=ARTIO|11=ORD-2|21=1|38=200|40=2|44=102.5|54=1|55=MSFT|59=0|60=…|10=082|
  [3] 8=FIX.4.2|9=130|35=D|34=8|49=QFJ|52=20260907-22:34:11.098|56=ARTIO|11=ORD-3|21=1|38=300|40=2|44=103.75|54=1|55=MSFT|59=0|60=…|10=141|
  [4] 8=FIX.4.2|9=135|35=G|34=12|49=QFJ|52=20260907-22:34:11.102|56=ARTIO|11=ORD-4|21=1|38=150|40=2|41=ORD-1|44=101.75|54=1|55=MSFT|60=…|10=176|
  [5] 8=FIX.4.2|9=115|35=F|34=15|49=QFJ|52=20260907-22:34:11.103|56=ARTIO|11=ORD-5|38=200|41=ORD-2|54=1|55=MSFT|60=…|10=056|
5 record(s) in fix.orders
```

**Five records for three orders**, and that is the design. `fix.orders` is
keyed on `/11` (`ClOrdID`), and a cancel/replace or a cancel carries a *new*
`ClOrdID` with the previous one in tag 41 — so the topic holds one record per
*request*, which is the audit shape and is robust to out-of-order arrival. The
sequence numbers 2, 5, 8, 12, 15 are the gaps where the execution reports went;
they carry the same `ClOrdID`s but only `35=D`, `35=G` and `35=F` are routed
here.

Compare the bytes with what QuickFIX/J printed in step 3: identical, checksum
included. AMPS stores a FIX payload verbatim; nothing on this path re-encodes.

`fix.execs` holds all ten reports, one per `ExecID`:

```bash
./gradlew :artio-amps-bridge:sowDump --args="--topic fix.execs"
```

```
SOW fix.execs on tcp://localhost:9007/amps/fix
  [1] 8=FIX.4.2|9=159|35=8|34=3|49=QFJ|…|6=0|11=ORD-1|14=0|17=EXEC-1|20=0|37=ORDER-1|38=100|39=0|54=1|55=MSFT|60=…|150=0|151=100|10=089|
  [2] 8=FIX.4.2|9=180|35=8|34=4|49=QFJ|…|6=101.25|11=ORD-1|14=50|17=EXEC-2|20=0|31=101.25|32=50|37=ORDER-1|38=100|39=1|…|150=1|151=50|10=042|
  …
  [9] 8=FIX.4.2|9=184|35=8|34=14|49=QFJ|…|6=101.5833|11=ORD-4|14=150|17=EXEC-9|20=0|31=101.75|32=100|37=ORDER-1|38=150|39=2|…|150=2|151=0|10=247|
  [10] 8=FIX.4.2|9=174|35=8|34=16|49=QFJ|…|6=102.5|11=ORD-5|14=100|17=EXEC-10|20=0|37=ORDER-2|38=200|39=4|41=ORD-2|…|150=4|151=0|10=101|
10 record(s) in fix.execs
```

And `fix.order.state`, keyed on `/37`, is the one the whole routing table exists
for: **ten publishes, three records**, one per order, each overwritten in place
as its order progressed.

```bash
./gradlew :artio-amps-bridge:sowDump --args="--topic fix.order.state"
```

```
SOW fix.order.state on tcp://localhost:9007/amps/fix
  [1] 8=FIX.4.2|9=184|35=8|34=14|49=QFJ|…|6=101.5833|11=ORD-4|14=150|17=EXEC-9|20=0|31=101.75|32=100|37=ORDER-1|38=150|39=2|…|150=2|151=0|10=247|
  [2] 8=FIX.4.2|9=174|35=8|34=16|49=QFJ|…|6=102.5|11=ORD-5|14=100|17=EXEC-10|20=0|37=ORDER-2|38=200|39=4|41=ORD-2|…|150=4|151=0|10=101|
  [3] 8=FIX.4.2|9=182|35=8|34=11|49=QFJ|…|6=103.75|11=ORD-3|14=300|17=EXEC-7|20=0|31=103.75|32=150|37=ORDER-3|38=300|39=2|…|150=2|151=0|10=137|
3 record(s) in fix.order.state
```

`ORDER-1` reads `39=2` Filled — it was acknowledged, half filled, amended and
completed, and only the last of its four reports survives. `ORDER-2` reads
`39=4` Canceled with `14=100` still on it, because it traded 100 of its 200
before the cancel. `ORDER-3` reads Filled. Three orders, three records, the
current state of each: that is what a SOW key is for, and until the reports were
scripted into the scenario nothing in this repository demonstrated it.

### 5. The tape

```bash
./gradlew :artio-amps-bridge:sowDump --args="--replay fix.raw"
```

```
journal replay of fix.raw on tcp://localhost:9007/amps/fix from the epoch
  [1] 8=FIX.4.2|…|35=D|…|11=ORD-1|…|60=20260907-02:56:16.803|10=126|
  …
  [41] 8=FIX.4.2|9=135|35=G|34=12|49=QFJ|52=20260907-22:34:11.102|56=ARTIO|11=ORD-4|…|10=176|
  [42] 8=FIX.4.2|9=175|35=8|34=13|49=QFJ|52=20260907-22:34:11.102|56=ARTIO|…|17=EXEC-8|…|37=ORDER-1|…|150=5|151=100|10=157|
  [43] 8=FIX.4.2|9=184|35=8|34=14|49=QFJ|52=20260907-22:34:11.103|56=ARTIO|6=101.5833|…|17=EXEC-9|…|150=2|151=0|10=247|
  [44] 8=FIX.4.2|9=115|35=F|34=15|49=QFJ|52=20260907-22:34:11.103|56=ARTIO|11=ORD-5|…|10=056|
  [45] 8=FIX.4.2|9=174|35=8|34=16|49=QFJ|52=20260907-22:34:11.103|56=ARTIO|…|17=EXEC-10|…|39=4|41=ORD-2|…|150=4|151=0|10=101|
45 message(s) in the fix.raw journal
```

**Forty-five, not fifteen** — this data directory had earlier runs in it.
`amps.sh down` removes the container and leaves
`amps-server/data/<flow>/` alone, so the journal accumulates across
start/down cycles. That is the point of a tape, and it is also the thing to
know before asserting on a count by hand: delete the data directory while AMPS
is down to start clean. (The integration suites never see this: each gets its
own directory under `build/` and deletes it on close.)

`fix.raw` has no SOW declaration, so `--topic fix.raw` finds nothing there by
design; the journal is the only way to read it back after the fact. `fix.admin`
has neither, so seeing admin traffic means subscribing while it happens.

### 6. Stop

`Ctrl-C` in the bootRun terminal, or from anywhere:

```bash
kill $(pgrep -f com.demo.artio.boot.ArtioBridgeApplication)
```

```
INFO  ArtioRuntime              bridge-acceptor-81490-1 closed
INFO  ArtioRuntimeLifecycle     artio runtime closed
INFO  AmpsFixPublisher          bridge closed: accepted=17 published=40 pending=0 dropped=0 unroutable=0
                                errors=0 lost=0 bytes=7409 ring=0/4194304 DISCONNECTED […]
INFO  BridgePublisherLifecycle  amps publisher drained and flushed: accepted=17 published=40 pending=0 …
```

**Those two lines, in that order, are what to check**: engine closed *first*, so
the ring buffer stopped filling; publisher drained and flushed *second*, with
`pending=0 dropped=0`, so everything the session sent reached AMPS. The reverse
order, or a missing second line, means the shutdown was cut short.

Gradle then reports:

```
> Task :artio-spring-boot:bootRun FAILED
> Process 'command '…/bin/java'' finished with non-zero exit value 143
BUILD FAILED in 1m 31s
```

**143 is expected and is not a failure.** A JVM killed by SIGTERM exits
`128 + 15` however cleanly its shutdown ran; Gradle has no way to tell that
apart from a crash. The four log lines above are the evidence, not the exit
code. The same applies to `:artio-amps-bridge:run`.

```bash
amps-server/scripts/amps.sh down
```

```
removed (data in …/amps-server/data/artio-fix is untouched)
```

### Leaving nothing behind

```bash
podman ps -a --filter name=artio-amps    # no rows
pgrep -f artio-spring-boot               # no output
```

Both were empty after this run. Artio's own directories live under
`java.io.tmpdir` and are deleted on close, so the repository is untouched
except for `amps-server/data/artio-fix/`, which is gitignored and is deleted
by hand when you want a fresh journal.
