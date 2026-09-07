# Integration testing and the demo

How an integration suite in this repository gets a real AMPS, why it is driven
the way it is, and what went wrong on the way there. Written from what was
actually run on 2026-09-06 against podman 5.8.2 / podman-compose 1.6.0 and
`localhost/amps-demo:5.3.5.135` on Apple Silicon.

The demo runbook is the second half of this document and is filled in at
phase 5.

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
implement the same ladder.

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
different animal. The whole seven-test integration suite, container start and
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
4. **`podman image exists $AMPS_IMAGE`** says no.

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
...7 tests, 7 skipped
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

## Demo runbook (filled in phase 5)
