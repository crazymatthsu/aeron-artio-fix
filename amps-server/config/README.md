# amps-server/config

```
config/
└── flows/
    └── artio-fix/
        └── amps-config.xml     the only file a flow directory ever holds
```

## What a flow is

A **flow** is one named AMPS configuration: one topic set, one journal policy,
one instance identity. Adding a flow is adding a directory here with an
`amps-config.xml` in it; nothing else in the repository has to change, because
every consumer takes the flow as a parameter:

```bash
AMPS_FLOW=my-flow amps-server/scripts/amps.sh start
```
```java
AmpsComposeServer.start("my-flow");
```

The directory itself is what gets bind-mounted, onto `/amps/config` in the
container. That is why every flow directory holds exactly one file with
exactly one name: the in-container path is always
`/amps/config/amps-config.xml`, so the compose file's `command:` never varies
with the flow.

## Flows defined here

| Flow | Purpose |
| --- | --- |
| `artio-fix` | The instance the Artio bridge publishes into: `fix.raw`, `fix.orders`, `fix.execs`, `fix.order.state`, `fix.admin`. Read the comments in its `amps-config.xml`; they explain each topic and why it is or is not journalled. |

## Three rules these files break easily

1. **An XML comment may not contain a double hyphen.** These configs are
   mostly prose, and a `--` anywhere inside a comment makes the file invalid
   XML. AMPS rejects the whole config at startup, which is a slow and
   confusing way to find out, so `./gradlew :amps-server:check` parses every
   file and reports the offending line instead.
2. **No comment may quote the startup line `AMPS initialization completed`.**
   AMPS echoes this whole file into its own log at startup, comments included,
   about a second *before* it starts listening — so a comment containing that
   phrase makes every readiness check watching for it declare the server ready
   while it is still starting. `checkConfigXml` rejects any flow config that
   mentions the marker at all.
3. **A SOW publish that lacks the topic's key field is not rejected - it is
   swallowed.** The key is a FIX tag reference (`/11`, `/17`, `/37`), and which
   tags a FIX message carries depends on its `35=`. Route a `35=D` to a topic
   keyed `/37` and AMPS 5.3.5.135 accepts it, warns nobody, and stores it under
   a degenerate key that every other keyless message shares, each overwriting
   the last. The topic comments say which message types belong on which topic;
   the bridge's `TopicRouter` is the only thing that enforces it.

## Where the runtime state goes

Not here. AMPS runs with its working directory set to `/amps/data`, which is
bind-mounted from `amps-server/data/<flow>/` on the host, so `./sow`,
`./journal` and `./stats` in these configs resolve to
`amps-server/data/<flow>/{sow,journal,stats}`. That directory is gitignored
and safe to delete when the instance is down.
