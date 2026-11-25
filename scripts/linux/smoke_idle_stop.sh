#!/usr/bin/env bash
#
# Check the idle lifecycle: only the Python MCP server stays resident.
#
# Flow:
#   1. start the MCP server with --auto-start-recaf and a short --recaf-idle-stop
#   2. call a tool -> Recaf is started on demand
#   3. stay quiet past the timeout -> that Recaf is stopped, Python keeps running
#   4. call again -> a fresh Recaf is started
#   5. kill the server hard -> its Recaf is orphaned (the case a host reload leaves)
#   6. start a new server -> it adopts the orphan and recycles it while idle
#   7. stop the server -> nothing is left behind
#
#   ./scripts/linux/smoke_idle_stop.sh
#
# Exit code 0 means the Java side only exists while it is being used.

set -euo pipefail
# shellcheck source=scripts/linux/_common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

usage() {
  cat <<'EOF'
Usage: smoke_idle_stop.sh [options]

Options:
  --jar PATH          recaf.jar (default: $RECAF_JAR, libs/recaf.jar)
  --plugin-dir DIR    plugin jar directory (default: $RECAF_PLUGIN_DIR, build/libs)
  --port N            plugin port for the test instance (default: 8797)
  --mcp-port N        MCP HTTP port (default: plugin port + 1)
  --idle N            idle timeout in seconds (default: 10)
  --timeout N         seconds to wait for the MCP server (default: 60)
  --startup-wait N    seconds the auto-start may take (default: 180)
  --keep              keep the temporary data directory
  -h, --help          show this help
EOF
}

port="${RECAF_SMOKE_PORT:-8797}"
idle=10
timeout=60
startup_wait=180
jar=""
plugin_dir=""
keep=0

while (( $# )); do
  case "$1" in
    --jar)          jar="${2:?}"; shift 2 ;;
    --plugin-dir)   plugin_dir="${2:?}"; shift 2 ;;
    --port)         port="${2:?}"; shift 2 ;;
    --mcp-port)     mcp_port="${2:?}"; shift 2 ;;
    --idle)         idle="${2:?}"; shift 2 ;;
    --timeout)      timeout="${2:?}"; shift 2 ;;
    --startup-wait) startup_wait="${2:?}"; shift 2 ;;
    --keep)         keep=1; shift ;;
    -h|--help)      usage; exit 0 ;;
    *)              usage >&2; die "unknown argument: $1" ;;
  esac
done

mcp_port="${mcp_port:-$((port + 1))}"
java="$(resolve_java "${RECAF_JAVA:-}")" || die "java not found"
jar="$(resolve_recaf_jar "$jar")" || die "recaf.jar not found; pass --jar or set RECAF_JAR"
plugin_dir="$(resolve_plugin_dir "$plugin_dir")" || die "no plugin jar; build it with ./gradlew jar"
python_bin="$(resolve_python)" || die "python3 not found; run scripts/linux/install.sh first"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/recaf-mcp-idle.XXXXXX")"
mcp_log="$work_dir/mcp.log"
state_file="$work_dir/owned.json"
mcp_pid=""

cleanup() {
  local code=$?
  if [[ -n "$mcp_pid" ]] && pid_alive "$mcp_pid"; then
    kill -TERM "$mcp_pid" 2>/dev/null || true
    sleep 1
    if pid_alive "$mcp_pid"; then kill -KILL "$mcp_pid" 2>/dev/null || true; fi
  fi
  if (( keep )); then
    log "kept test data: $work_dir"
  else
    rm -rf "$work_dir"
  fi
  exit "$code"
}
trap cleanup EXIT

# pid_alive() also reports a zombie (already exited, not yet reaped) as alive;
# a stopped Recaf can linger like that for a moment, so look at its state.
proc_running() {
  local pid="$1" state
  [[ -n "$pid" ]] || return 1
  state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
  [[ -n "$state" && "$state" != Z* ]]
}

instance_pid() {
  curl -s -m 5 "http://127.0.0.1:$port/instance" 2>/dev/null \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("pid") or "")' 2>/dev/null || true
}

call_tool() {
  local tool="$1"
  "$python_bin" - "$mcp_port" "$tool" <<'PY'
import asyncio, json, sys
from fastmcp import Client

mcp_port, tool = sys.argv[1], sys.argv[2]

async def main():
    async with Client(f"http://127.0.0.1:{mcp_port}/mcp", timeout=300) as client:
        result = await client.call_tool(tool, {})
        data = result.data if hasattr(result, "data") else result.structuredContent
        print(json.dumps(data)[:160])

asyncio.run(main())
PY
}

start_server() {
  RECAF_MCP_PORT="$port" RECAF_MCP_HOST=127.0.0.1 RECAF_MCP_STATE_FILE="$state_file" \
    "$python_bin" \
    "$REPO_ROOT/recaf_mcp_server.py" --http --port "$mcp_port" --recaf-port "$port" \
    --headless --auto-start-recaf --auto-start-wait "$startup_wait" --recaf-idle-stop "$idle" \
    --recaf-jar "$jar" --recaf-plugins-dir "$plugin_dir" --java "$java" \
    --recaf-data-dir "$work_dir/data" --recaf-launch-log "$work_dir/recaf-launch.log" \
    >> "$mcp_log" 2>&1 < /dev/null &
  mcp_pid=$!
  for _ in $(seq 1 $((timeout * 2))); do
    http_answers "http://127.0.0.1:$mcp_port/mcp" && break
    sleep 0.5
  done
  http_answers "http://127.0.0.1:$mcp_port/mcp" || { tail_log "$mcp_log" 40; die "MCP server did not start"; }
  log "MCP server is up (pid $mcp_pid)"
}

wait_for_exit() {
  local pid="$1" limit="$2" waited=0
  while (( waited < limit )); do
    proc_running "$pid" || return 0
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

log "java:       $java"
log "recaf.jar:  $jar"
log "plugin dir: $plugin_dir"
log "ports:      plugin $port, mcp $mcp_port, idle-stop ${idle}s"

if plugin_health_ok 127.0.0.1 "$port"; then
  die "something already answers on port $port; stop it or pick another --port"
fi

start_server

echo
echo "== 1. a tool call starts Recaf on demand"
call_tool get_workspace_info > /dev/null
first_pid="$(instance_pid)"
[[ -n "$first_pid" ]] || die "no Recaf answering on port $port after the tool call"
log "Recaf started: pid $first_pid"

echo
echo "== 2. stay quiet for $((idle + 12))s: Recaf should go away, Python should not"
waited=0
while (( waited < idle + 12 )); do
  sleep 2
  waited=$((waited + 2))
  if ! proc_running "$first_pid"; then break; fi
done
if proc_running "$first_pid"; then
  tail_log "$mcp_log" 40
  die "Recaf pid $first_pid is still running after ${waited}s idle (limit ${idle}s + one check interval)"
fi
log "Recaf pid $first_pid stopped after ~${waited}s idle"
pid_alive "$mcp_pid" || die "the MCP server exited too; only Recaf should stop"
plugin_health_ok 127.0.0.1 "$port" && die "plugin still answers on $port although Recaf is gone"
log "MCP server still running (pid $mcp_pid), no Java side left"

echo
echo "== 3. the next tool call starts a fresh Recaf"
call_tool get_workspace_info > /dev/null
second_pid="$(instance_pid)"
[[ -n "$second_pid" ]] || { tail_log "$mcp_log" 40; die "no Recaf after the second tool call"; }
[[ "$second_pid" != "$first_pid" ]] || die "expected a new Recaf process, got the old pid $second_pid"
log "Recaf restarted on demand: pid $second_pid"

echo
echo "== 4. a hard server crash leaves the Recaf behind"
kill -KILL "$mcp_pid" 2>/dev/null || true
wait "$mcp_pid" 2>/dev/null || true
mcp_pid=""
sleep 2
proc_running "$second_pid" || die "expected the orphaned Recaf to survive a SIGKILLed server"
log "orphan confirmed: Recaf pid $second_pid survived the killed server"

echo
echo "== 5. the next server adopts the orphan and recycles it"
start_server
grep -q "adopted Recaf pid $second_pid" "$mcp_log" \
  || warn "no adoption line in the log (maybe it was noticed later); continuing"
if ! wait_for_exit "$second_pid" $((idle + 20)); then
  tail_log "$mcp_log" 40
  die "the adopted Recaf pid $second_pid was not recycled"
fi
log "adopted Recaf pid $second_pid recycled after the idle timeout"
pid_alive "$mcp_pid" || die "the second MCP server exited unexpectedly"
log "second MCP server still running (pid $mcp_pid)"

echo
echo "== 6. stopping the server leaves nothing behind"
kill -TERM "$mcp_pid"
if ! wait_for_exit "$mcp_pid" 15; then
  kill -KILL "$mcp_pid" 2>/dev/null || true
fi
mcp_pid=""
plugin_health_ok 127.0.0.1 "$port" && die "a Recaf is still answering on $port"
if [[ -f "$state_file" ]]; then
  python3 - "$state_file" <<'PY'
import json, sys
entries = json.load(open(sys.argv[1])).get("pids") or []
raise SystemExit(0 if not entries else "state file still lists pids: %s" % entries)
PY
fi
log "no Recaf and no ownership state left"

echo
log "PASS: only the Python side stays resident; Recaf exists only while it is used"
