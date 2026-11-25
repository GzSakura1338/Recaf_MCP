#!/usr/bin/env bash
#
# End-to-end smoke test for headless Recaf + the recaf-mcp plugin on Linux.
#
# It starts a throw-away headless Recaf (own data dir, own port), then checks the
# HTTP API the MCP server uses:
#   /health, /instance (headless flag), /workspace/open, /all-classes, /class-source
# and, with --with-mcp, that the MCP server exposes those results as tools.
#
#   ./scripts/linux/smoke_test.sh                       # analyzes recaf.jar itself
#   ./scripts/linux/smoke_test.sh --input target.jar
#   ./scripts/linux/smoke_test.sh --no-build --keep     # reuse build, keep the log
#   ./scripts/linux/smoke_test.sh --with-mcp            # also test the MCP tool layer
#
# Exit code 0 means headless mode works on this machine.

set -euo pipefail
# shellcheck source=scripts/linux/_common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

usage() {
  cat <<'EOF'
Usage: smoke_test.sh [options]

Options:
  --input FILE        jar to open as the workspace (default: recaf.jar itself)
  --jar PATH          recaf.jar (default: $RECAF_JAR, libs/recaf.jar)
  --plugin-dir DIR    plugin jar directory (default: $RECAF_PLUGIN_DIR, build/libs)
  --port N            plugin port for the test instance (default: 8790)
  --timeout N         seconds to wait for the plugin (default: 120)
  --no-build          do not build the plugin first
  --with-mcp          also call the tools through the MCP server (.venv required)
  --keep              keep the temporary data dir and print its path
  -h, --help          show this help
EOF
}

port="${RECAF_SMOKE_PORT:-8790}"
timeout=120
input=""
jar=""
plugin_dir=""
build=1
with_mcp=0
keep=0

while (( $# )); do
  case "$1" in
    --input)      input="${2:?}"; shift 2 ;;
    --jar)        jar="${2:?}"; shift 2 ;;
    --plugin-dir) plugin_dir="${2:?}"; shift 2 ;;
    --port)       port="${2:?}"; shift 2 ;;
    --timeout)    timeout="${2:?}"; shift 2 ;;
    --no-build)   build=0; shift ;;
    --with-mcp)   with_mcp=1; shift ;;
    --keep)       keep=1; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            usage >&2; die "unknown argument: $1" ;;
  esac
done

java="$(resolve_java "${RECAF_JAVA:-}")" || die "java not found"
jar="$(resolve_recaf_jar "$jar")" || die "recaf.jar not found; pass --jar or set RECAF_JAR"
plugin_dir="$(resolve_plugin_dir "$plugin_dir")" || die "no plugin jar; build it with ./gradlew jar"

if (( build )); then
  log "building the plugin (./gradlew jar)"
  ( cd "$REPO_ROOT" && RECAF_JAR="$jar" ./gradlew jar --no-daemon -q )
  plugin_dir="$(resolve_plugin_dir "$plugin_dir")" || die "build did not produce build/libs/recaf-mcp-plugin*.jar"
fi

[[ -n "$input" ]] || input="$jar"
[[ -f "$input" ]] || die "input jar not found: $input"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/recaf-mcp-smoke.XXXXXX")"
log_file="$work_dir/recaf.log"
pid=""
mcp_pid=""

cleanup() {
  local code=$?
  if [[ -n "$mcp_pid" ]] && pid_alive "$mcp_pid"; then
    kill -TERM "$mcp_pid" 2>/dev/null || true
  fi
  if [[ -n "$pid" ]] && pid_alive "$pid"; then
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      pid_alive "$pid" || break
      sleep 0.5
    done
    if pid_alive "$pid"; then kill -KILL "$pid" 2>/dev/null || true; fi
  fi
  if (( keep )); then
    log "kept test data: $work_dir"
  else
    rm -rf "$work_dir"
  fi
  exit "$code"
}
trap cleanup EXIT

base="http://127.0.0.1:$port"

log "java:        $java"
log "recaf.jar:   $jar"
log "plugin dir:  $plugin_dir"
log "workspace:   $input"
log "test port:   $port"

# The plugin honours RECAF_MCP_PORT/RECAF_MCP_HOST, which is also what makes it
# possible to run several headless instances side by side.
RECAF_MCP_HOST=127.0.0.1 RECAF_MCP_PORT="$port" \
  "$java" -jar "$jar" --headless --idle -r "$plugin_dir" -d "$work_dir/data" \
  > "$log_file" 2>&1 < /dev/null &
pid=$!

step() { printf '\n== %s\n' "$*"; }

step "waiting for the plugin ($base/health)"
if ! wait_plugin_health 127.0.0.1 "$port" "$timeout"; then
  tail_log "$log_file" 40
  die "plugin never answered within ${timeout}s"
fi
log "plugin is up"

step "GET /instance"
curl -sS -m 10 "$base/instance" -o "$work_dir/instance.json"
cat "$work_dir/instance.json"; printf '\n'
python3 - "$work_dir/instance.json" <<'PY'
import json, sys
info = json.load(open(sys.argv[1]))
assert info.get("headless") is True, f"expected headless=true, got {info.get('headless')!r}"
assert info.get("ui_available") is False, f"expected ui_available=false, got {info.get('ui_available')!r}"
print("ok: instance reports headless mode")
PY

step "POST /workspace/open"
python3 -c 'import json, sys; print(json.dumps({"path": sys.argv[1]}))' "$input" \
  > "$work_dir/open-request.json"
curl -sS -m 300 -H 'Content-Type: application/json' \
  --data @"$work_dir/open-request.json" "$base/workspace/open" -o "$work_dir/open.json"
python3 - "$work_dir/open.json" <<'PY'
import json, sys
out = json.load(open(sys.argv[1]))
assert out.get("ok") is True, f"workspace/open failed: {out}"
count = (out.get("primary") or {}).get("class_count") or 0
assert count > 0, f"expected classes in {out.get('primary')}"
print(f"ok: workspace opened, {count} classes")
PY

step "GET /all-classes"
curl -sS -m 120 "$base/all-classes?offset=0&limit=3" -o "$work_dir/classes.json"
python3 - "$work_dir/classes.json" <<'PY'
import json, sys
out = json.load(open(sys.argv[1]))
items = out.get("classes") or []
assert len(items) == 3, f"expected 3 classes, got {len(items)}"
print("ok: first classes:", ", ".join(c["name"] for c in items))
PY

step "GET /class-source on the first class"
first_class="$(python3 - "$work_dir/classes.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1]))["classes"][0]["name"].replace(".", "/"))
PY
)"
curl -sS -m 300 --get --data-urlencode "class_name=$first_class" \
  "$base/class-source" -o "$work_dir/source.json"
python3 - "$work_dir/source.json" <<'PY'
import json, sys
out = json.load(open(sys.argv[1]))
text = out.get("text") or ""
assert text.strip(), f"empty decompiled source: {out}"
print(f"ok: decompiled {out.get('class_name')} ({len(text)} chars)")
PY

step "GET /current-class (headless: no UI)"
code="$(curl -sS -m 10 -o "$work_dir/current-class.json" -w '%{http_code}' "$base/current-class")"
python3 - "$work_dir/current-class.json" "$code" <<'PY'
import json, sys
body, code = sys.argv[1], sys.argv[2]
assert code == "200", f"expected HTTP 200 for a headless UI call, got {code}: {open(body).read()[:300]}"
out = json.load(open(body))
assert out.get("ui_available") is False, out
assert out.get("class_name") is None, out
assert out.get("reason"), out
print("ok: UI tool degrades cleanly:", out["reason"].split(";")[0])
PY

if (( with_mcp )); then
  step "MCP tools over HTTP"
  python_bin="$(resolve_python)" || die "python3 not found; run scripts/linux/install.sh first"
  mcp_port=$((port + 1))
  mcp_log="$work_dir/mcp.log"
  log "starting MCP server on port $mcp_port"
  "$python_bin" "$REPO_ROOT/recaf_mcp_server.py" --http --port "$mcp_port" \
    --recaf-port "$port" >> "$mcp_log" 2>&1 < /dev/null &
  mcp_pid=$!
  for _ in $(seq 1 120); do
    http_answers "http://127.0.0.1:$mcp_port/mcp" && break
    sleep 0.5
  done
  if ! http_answers "http://127.0.0.1:$mcp_port/mcp"; then
    tail_log "$mcp_log" 40
    die "MCP server did not start on port $mcp_port"
  fi
  if ! "$python_bin" "$SCRIPT_DIR/smoke_mcp.py" --url "http://127.0.0.1:$mcp_port/mcp"; then
    tail_log "$mcp_log" 60
    die "MCP tool smoke test failed"
  fi
fi

step "result"
log "PASS: headless Recaf + recaf-mcp plugin work on this machine"
