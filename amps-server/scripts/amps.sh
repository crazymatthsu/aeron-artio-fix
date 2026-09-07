#!/usr/bin/env bash
#
# AMPS lifecycle for this repository, via podman compose.
#
#   amps-server/scripts/amps.sh start      up -d, then block until ready
#   amps-server/scripts/amps.sh wait       block until ready (startup log line, then port)
#   amps-server/scripts/amps.sh status     compose ps
#   amps-server/scripts/amps.sh logs [-f]  container log
#   amps-server/scripts/amps.sh stop       stop the container, keep the data
#   amps-server/scripts/amps.sh down       stop and remove it, keep the data
#   amps-server/scripts/amps.sh restart    down, then start (exercises recovery)
#   amps-server/scripts/amps.sh config     print the resolved compose YAML
#   amps-server/scripts/amps.sh printenv   print the AMPS_* values and exit,
#                                          touching no container
#
# Runs from any working directory: every path is derived from this script's
# own location, so `./amps-server/scripts/amps.sh start` and `cd /tmp &&
# /abs/path/amps.sh start` do the same thing.
#
# Environment (all optional, all defaulted here):
#   AMPS_IMAGE       container image           localhost/amps-demo:5.3.5.135
#   AMPS_FLOW        config/flows/<flow>       artio-fix
#   AMPS_PORT        host port for amps/tcp    9007
#   AMPS_WS_PORT     host port for websocket   9008
#   AMPS_ADMIN_PORT  host port for admin UI    8085
#   AMPS_BIN         server binary in image    /opt/amps/bin/ampServer
#   AMPS_PLATFORM    image platform            linux/amd64
#   AMPS_CONTAINER_NAME  container name        artio-amps
#   AMPS_WAIT_TIMEOUT    seconds for wait      120
#
# Data lands in amps-server/data/<flow>/{sow,journal,stats} on the host and is
# gitignored. `down` keeps it; delete the directory yourself to start clean.
#
# The image is not built here by default: it is the one amps-demo builds from
# a 60East release tarball. To build it in this repository instead, see
# amps-server/Containerfile and amps-server/vendor/README.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${MODULE_DIR}/docker-compose.yml"

die() { echo "error: $*" >&2; exit 1; }

# --- settings -----------------------------------------------------------
# Exported, not merely set: compose resolves the ${VAR} references in
# docker-compose.yml from this process's environment.
export AMPS_IMAGE="${AMPS_IMAGE:-localhost/amps-demo:5.3.5.135}"
export AMPS_PLATFORM="${AMPS_PLATFORM:-linux/amd64}"
export AMPS_BIN="${AMPS_BIN:-/opt/amps/bin/ampServer}"
export AMPS_PORT="${AMPS_PORT:-9007}"
export AMPS_WS_PORT="${AMPS_WS_PORT:-9008}"
export AMPS_ADMIN_PORT="${AMPS_ADMIN_PORT:-8085}"
export AMPS_CONTAINER_NAME="${AMPS_CONTAINER_NAME:-artio-amps}"

AMPS_FLOW="${AMPS_FLOW:-artio-fix}"
FLOW_DIR="${MODULE_DIR}/config/flows/${AMPS_FLOW}"
PROJECT="${AMPS_COMPOSE_PROJECT:-artio-amps}"
WAIT_TIMEOUT="${AMPS_WAIT_TIMEOUT:-120}"
DATA_DIR="${MODULE_DIR}/data/${AMPS_FLOW}"

# Only strings are resolved here. Whether the flow exists and whether the data
# directories are there is checked and done in cmd_start, the one command that
# needs either: `--help`, `printenv`, `status` and `logs` must neither create
# anything on disk nor refuse to run over a typo in AMPS_FLOW.

# SELinux hosts need :z on a bind mount or the container cannot read it. macOS
# does not, and podman's macOS VM rejects the label on a virtiofs mount, so the
# suffix is Linux-only.
# (The `|| true` is not decoration: under `set -e` a bare `[[ ]] && x` that
# tests false makes the whole list return 1 and kills the script on macOS.)
suffix=""
[[ "$(uname -s)" == "Linux" ]] && suffix=":z" || true
export AMPS_CONFIG_VOLUME="${FLOW_DIR}:/amps/config${suffix}"
export AMPS_DATA_VOLUME="${DATA_DIR}:/amps/data${suffix}"

# --- which compose implementation is on this box? -----------------------
#
# podman 5's `podman compose` is a shim that execs an external provider
# (podman-compose here) and prints a banner about it on stderr; that banner is
# noise, not a failure. podman-compose direct, then docker's, are the
# fallbacks.
compose_command() {
    if command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
        echo "podman compose"; return
    fi
    if command -v podman-compose >/dev/null 2>&1; then
        echo "podman-compose"; return
    fi
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        echo "docker compose"; return
    fi
    die "no compose implementation on PATH (tried podman compose, podman-compose, docker compose)"
}

# Resolved lazily: printenv and --help must work on a box with no compose tool,
# since checking the resolved values before installing one is the point of them.
COMPOSE=()
ENGINE=""
ensure_compose() {
    if [[ ${#COMPOSE[@]} -eq 0 ]]; then
        local chosen
        # `|| exit 1`, because the `die` inside compose_command runs in the
        # $(...) subshell: it ends that subshell, not this script. Read straight
        # into the array with `read <<< "$(...)"`, the failure status is
        # discarded, COMPOSE is empty, and the next command line starts with
        # "-p", which bash reports as "-p: command not found".
        chosen="$(compose_command)" || exit 1
        read -ra COMPOSE <<< "${chosen}"
        # The engine binary is whichever one the chosen compose drives, so
        # `logs`, `ps` and `wait` look where the container actually is:
        # podman-compose is podman's, and the other two name their engine in
        # their first word. Asking "is podman installed?" instead sent `logs`
        # to podman for a container that docker compose had started.
        case "${COMPOSE[0]}" in
            podman-compose) ENGINE="podman" ;;
            *)              ENGINE="${COMPOSE[0]}" ;;
        esac
        COMPOSE+=(-p "${PROJECT}" -f "${COMPOSE_FILE}")
    fi
}

cmd_start() {
    ensure_compose
    [[ -f "${FLOW_DIR}/amps-config.xml" ]] || die "no such flow '${AMPS_FLOW}': expected ${FLOW_DIR}/amps-config.xml
available flows: $(ls "${MODULE_DIR}/config/flows" 2>/dev/null | tr '\n' ' ')"
    mkdir -p "${DATA_DIR}/sow" "${DATA_DIR}/journal" "${DATA_DIR}/stats"
    echo "starting ${AMPS_CONTAINER_NAME}"
    echo "  flow:   ${AMPS_FLOW}  (${FLOW_DIR}/amps-config.xml)"
    echo "  image:  ${AMPS_IMAGE}  (${AMPS_PLATFORM})"
    echo "  ports:  ${AMPS_PORT} tcp / ${AMPS_WS_PORT} ws / ${AMPS_ADMIN_PORT} admin"
    echo "  data:   ${DATA_DIR}"
    "${COMPOSE[@]}" up -d
    echo
    cmd_wait
    echo "  admin:  http://localhost:${AMPS_ADMIN_PORT}/"
    echo "  client: tcp://127.0.0.1:${AMPS_PORT}/amps/fix"
}

cmd_stop() {
    ensure_compose
    "${COMPOSE[@]}" stop
    echo "stopped (data in ${DATA_DIR} is untouched)"
}

cmd_down() {
    ensure_compose
    "${COMPOSE[@]}" down
    echo "removed (data in ${DATA_DIR} is untouched)"
}

cmd_restart() {
    cmd_down
    cmd_start
}

cmd_status() {
    ensure_compose
    "${COMPOSE[@]}" ps
}

# Read the log from the ENGINE, not from compose. podman-compose's `logs`
# needs the project's own bookkeeping and has been seen to return nothing at
# all for a container it started under a fixed container_name; `podman logs
# <name>` always works, and the name is ours because docker-compose.yml pins
# it. Same reasoning in AmpsComposeServer.logs().
cmd_logs() {
    ensure_compose
    "${ENGINE}" logs "$@" "${AMPS_CONTAINER_NAME}"
}

# The server's own startup-complete line, INCLUDING its numeric message code.
#
# The code is not belt and braces. AMPS echoes the entire config file into the
# log before it starts, comments and all, so matching on the bare English
# phrase also matches any config comment that mentions it - about a second too
# early, from a server that is not yet listening. Verified against 5.3.5.135:
# the echo of amps-config.xml is roughly 500 of the first 1600 log lines.
READY_MARKER="00-0015 AMPS initialization completed"

# Readiness is two conditions, and the port alone is a trap: the engine's port
# forwarder accepts connections as soon as the container exists, so a client
# that races it connects and is then dropped mid-logon. AMPS is ready when it
# says so.
#
# The LOG is checked first and the port second, which is the opposite of the
# obvious order, because every probe connection AMPS accepts costs five lines
# in the log ("New Client Connection", the client info block, "disconnected").
# Probing once a second for two minutes buries the startup line under six
# hundred lines of our own noise - which is also why this greps the whole log
# rather than a --tail window.
#
# Both conditions are tested on CAPTURED OUTPUT rather than through a pipe
# into `grep -q`. With `set -o pipefail`, grep's early exit on a match sends
# SIGPIPE to `podman logs`, podman exits non-zero, and the pipeline as a whole
# reports failure - so the test says "not ready" at the exact moment it found
# the marker, and waits out the full timeout on a server that came up in one
# second. It is a silent, correct-looking loop that never terminates early.
cmd_wait() {
    ensure_compose
    local timeout="${1:-${WAIT_TIMEOUT}}"
    local deadline=$(( SECONDS + timeout ))
    local log running
    printf 'waiting for AMPS on port %s ' "${AMPS_PORT}"
    while (( SECONDS < deadline )); do
        # Is the container still there? Asked on EVERY iteration, not only
        # while the marker is missing: a server that announced readiness and
        # then died (a journal it cannot write, a licence it will not accept)
        # keeps the marker in its log, and a check confined to the not-ready
        # branch waited out the whole timeout on a corpse. Fail fast instead.
        running="$("${ENGINE}" ps --format '{{.Names}}' 2>/dev/null || true)"
        if [[ $'\n'"${running}"$'\n' != *$'\n'"${AMPS_CONTAINER_NAME}"$'\n'* ]]; then
            echo " container is not running"
            cmd_logs --tail 30 2>&1 || true
            return 1
        fi
        log="$(cmd_logs 2>&1 || true)"
        if [[ "${log}" == *"${READY_MARKER}"* ]]; then
            if (exec 3<>"/dev/tcp/127.0.0.1/${AMPS_PORT}") 2>/dev/null; then
                echo " ready"
                return 0
            fi
        fi
        printf '.'
        sleep 1
    done
    echo " timed out after ${timeout}s"
    echo "  check '${BASH_SOURCE[0]} logs'"
    return 1
}

cmd_config() {
    ensure_compose
    "${COMPOSE[@]}" config
}

cmd_printenv() {
    echo "AMPS_FLOW=${AMPS_FLOW}   (${FLOW_DIR}/amps-config.xml)"
    echo "compose project=${PROJECT}   file=${COMPOSE_FILE}"
    echo
    local name
    for name in AMPS_IMAGE AMPS_PLATFORM AMPS_BIN AMPS_PORT AMPS_WS_PORT \
                AMPS_ADMIN_PORT AMPS_CONTAINER_NAME AMPS_CONFIG_VOLUME \
                AMPS_DATA_VOLUME; do
        echo "${name}=${!name:-}"
    done
}

# The header comment above is the usage text; keeping one copy means it cannot
# drift from the commands it documents.
usage() {
    awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
    start)    shift; cmd_start "$@" ;;
    stop)     shift; cmd_stop "$@" ;;
    down)     shift; cmd_down "$@" ;;
    restart)  shift; cmd_restart "$@" ;;
    status)   shift; cmd_status "$@" ;;
    logs)     shift; cmd_logs "$@" ;;
    wait)     shift; cmd_wait "$@" ;;
    config)   shift; cmd_config "$@" ;;
    printenv) shift; cmd_printenv "$@" ;;
    ""|-h|--help|help) usage ;;
    *)        die "unknown command '$1' (try --help)" ;;
esac
