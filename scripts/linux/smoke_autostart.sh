#!/usr/bin/env bash
#
# Check that an MCP host can start everything by itself: no Recaf running, the MCP
# server is started (as DSH does over stdio) and the first tool call brings up a
# headless Recaf, opens a workspace and answers.
#
#   ./scripts/linux/smoke_autostart.sh
#   ./scripts/linux/smoke_autostart.sh --input target.jar --port 8795
#
# Exit code 0 means --auto-start-recaf works on this machine.

set -euo pipefail
# shellcheck source=scripts/linux/_common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

usage() {
  cat <<'EOF'
Usage: smoke_autostart.sh [options]

Options:
  --input FILE      jar the auto-started Recaf should open (default: recaf.jar)
  --jar PATH        recaf.jar (default: $RECAF_JAR, libs/recaf.jar)
  --plugin-dir DIR  plugin jar directory (default: $RECAF_PLUGIN_DIR, build/libs)
  --port N          plugin port for the test instance (default: 8795)
  --mcp-port N      MCP HTTP port (default: plugin port + 1)
  --timeout N       seconds to wait for the MCP server (default: 60)
  --wait N          seconds the auto-start may take (default: 180)
  --keep            keep the temporary data directory
  -h, --help        show this help
EOF
}

port="${RECAF_SMOKE_PORT:-8795}"
timeout=60
wait_seconds=180
input=""
jar=""
plugin_dir=""
keep=0

while (( $# )); do
  case "$1" in
    --input)      input="${2:?}"; shift 2 ;;
    --jar)        jar="${2:?}"; shift 2 ;;
    --plugin-dir) plugin_dir="${2:?}"; shift 2 ;;
    --port)       port="${2:?}"; shift 2 ;;
    --mcp-port)   mcp_port="${2:?}"; shift 2 ;;
    --timeout)    timeout="${2:?}"; shift 2 ;;
    --wait)       wait_seconds="${2:?}"; shift 2 ;;
    --keep)       keep=1; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            usage >&2; die "unknown argument: $1" ;;
  esac
done

mcp_port="${mcp_port:-$((port + 1))}"
java="$(resolve_java "${RECAF_JAVA:-}")" || die "java not found"
jar="$(resolve_recaf_jar "$jar")" || die "recaf.jar not found; pass --jar or set RECAF_JAR"
plugin_dir="$(resolve_plugin_dir "$plugin_dir")" || die "no plugin jar; build it with ./gradlew jar"
python_bin="$(resolve_python)" || die "python3 not found; run scripts/linux/install.sh first"
[[ -n "$input" ]] || input="$jar"
[[ -f "$input" ]] || die "input jar not found: $input"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/recaf-mcp-autostart.XXXXXX")"
mcp_log="$work_dir/mcp.log"
launch_log="$work_dir/recaf-launch.log"
mcp_pid=""
recaf_pid=""

cleanup() {
  local code=$?
  if [[ -n "$recaf_pid" ]] && pid_alive "$recaf_pid"; then
    kill -TERM "$recaf_pid" 2>/dev/null || true
  fi
  if [[ -n "$mcp_pid" ]] && pid_alive "$mcp_pid"; then
    kill -TERM "$mcp_pid" 2>/dev/null || true
  fi
  if (( keep )); then
    log "kept test data: $work_dir"
  else
    rm -rf "$work_dir"
  fi
  exit "$code"
}
trap cleanup EXIT

log "java:       $java"
log "recaf.jar:  $jar"
log "plugin dir: $plugin_dir"
log "workspace:  $input"
log "ports:      plugin $port, mcp $mcp_port"

if plugin_health_ok 127.0.0.1 "$port"; then
  die "something is already answering on port $port; stop it or pick another --port"
fi

log "starting the MCP server with --headless --auto-start-recaf (no Recaf running)"
RECAF_MCP_PORT="$port" RECAF_MCP_HOST=127.0.0.1 "$python_bin" \
  "$REPO_ROOT/recaf_mcp_server.py" --http --port "$mcp_port" --recaf-port "$port" \
  --headless --auto-start-recaf --auto-start-wait "$wait_seconds" \
  --recaf-jar "$jar" --recaf-plugins-dir "$plugin_dir" --java "$java" \
  --recaf-data-dir "$work_dir/data" --recaf-launch-log "$launch_log" \
  >> "$mcp_log" 2>&1 < /dev/null &
mcp_pid=$!

for _ in $(seq 1 $((timeout * 2))); do
  http_answers "http://127.0.0.1:$mcp_port/mcp" && break
  sleep 0.5
done
http_answers "http://127.0.0.1:$mcp_port/mcp" || { tail_log "$mcp_log" 40; die "MCP server did not start"; }
log "MCP server is up"

echo
echo "== first tool call (should start Recaf by itself)"
"$python_bin" - "$mcp_port" "$input" "$wait_seconds" <<'PY'
import asyncio, json, sys
from fastmcp import Client

mcp_port, jar, wait_seconds = sys.argv[1], sys.argv[2], sys.argv[3]

async def call(client, name, args=None):
    result = await client.call_tool(name, args or {})
    return result.data

async def main():
    async with Client(f"http://127.0.0.1:{mcp_port}/mcp",
                      timeout=max(60, int(wait_seconds) + 60)) as client:
        listing = await call(client, "list_recaf_windows")
        assert listing.get("windows") == [], f"expected no windows yet, got {listing}"
        print("ok: no Recaf window before the call:", json.dumps(listing)[:200])

        opened = await call(client, "open_workspace", {"path": jar})
        assert opened.get("ok") is True, f"open_workspace failed: {opened}"
        print(f"ok: workspace opened by the auto-started Recaf, {opened['primary']['class_count']} classes")

        windows = await call(client, "list_recaf_windows")
        first = windows["windows"][0]
        assert first.get("headless") is True, f"auto-started window should be headless: {first}"
        print("ok: auto-started window:", json.dumps(first)[:200])

        classes = await call(client, "get_all_classes", {"offset": 0, "count": 2})
        items = classes.get("items") or classes.get("classes") or []
        assert items, f"no classes: {classes}"
        print("ok: get_all_classes ->", ", ".join(c["name"] for c in items))

asyncio.run(main())
PY

echo
if plugin_health_ok 127.0.0.1 "$port"; then
  recaf_pid="$(curl -s -m 5 "http://127.0.0.1:$port/instance" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("pid") or "")')"
fi
log "PASS: the MCP server started headless Recaf by itself (pid ${recaf_pid:-unknown})"
