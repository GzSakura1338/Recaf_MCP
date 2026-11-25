#!/usr/bin/env bash
#
# Start / stop a headless Recaf with the recaf-mcp plugin on Linux.
#
# Recaf 4 can run without its JavaFX UI ("recaf --headless --idle"), so a normal
# ssh session, container or CI runner needs no X server, no Xvfb and no VNC: the
# plugin's HTTP API is the only thing that has to work.
#
#   ./start_recaf_headless.sh start --input /path/to/target.jar
#   ./start_recaf_headless.sh status
#   ./start_recaf_headless.sh stop
#
# Environment: RECAF_JAR, RECAF_PLUGIN_DIR, RECAF_DATA_DIR, RECAF_JAVA,
#              RECAF_JVM_ARGS, RECAF_MCP_PORT (plugin base port, default 8750).

set -euo pipefail
# shellcheck source=scripts/linux/_common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

usage() {
  cat <<'EOF'
Usage: start_recaf_headless.sh [start|stop|status|restart] [options]

Actions:
  start (default)   launch Recaf with --headless --idle and wait for the plugin
  stop              stop the process recorded in .run/recaf-headless.pid
  status            show whether it runs and whether the plugin answers
  restart           stop, then start

Options:
  --jar PATH          recaf.jar            (default: $RECAF_JAR, libs/recaf.jar)
  --plugin-dir DIR    directory with recaf-mcp-plugin*.jar, passed as -r
                      (default: $RECAF_PLUGIN_DIR, build/libs)
  --data-dir DIR      Recaf data directory (-d). Default: $RECAF_DATA_DIR,
                      empty means Recaf's own default (~/.config/Recaf)
  --input FILE        workspace to load on startup (-i)
  --java PATH         java binary          (default: $RECAF_JAVA, JAVA_HOME, PATH)
  --jvm-args "ARGS"   extra JVM arguments  (default: $RECAF_JVM_ARGS), e.g. '-Xmx4g'
  --port N            plugin port, exported as RECAF_MCP_PORT (default: 8750)
  --timeout N         seconds to wait for /health (default: 90)
  --foreground        stay attached to Recaf's output instead of detaching
  -h, --help          show this help

The log goes to logs/recaf-headless.log; the pid to .run/recaf-headless.pid.
EOF
}

action="start"
service_host="${RECAF_MCP_BIND_HOST:-127.0.0.1}"
port="${RECAF_MCP_PORT:-8750}"
timeout=90
jar=""
plugin_dir=""
data_dir="${RECAF_DATA_DIR:-}"
input=""
java_bin="${RECAF_JAVA:-}"
jvm_args="${RECAF_JVM_ARGS:-}"
foreground=0

while (( $# )); do
  case "$1" in
    start|stop|status|restart) action="$1"; shift ;;
    --jar)          jar="${2:?}"; shift 2 ;;
    --plugin-dir)   plugin_dir="${2:?}"; shift 2 ;;
    --data-dir)     data_dir="${2:?}"; shift 2 ;;
    --input)        input="${2:?}"; shift 2 ;;
    --java)         java_bin="${2:?}"; shift 2 ;;
    --jvm-args)     jvm_args="${2:?}"; shift 2 ;;
    --port)         port="${2:?}"; shift 2 ;;
    --timeout)      timeout="${2:?}"; shift 2 ;;
    --foreground)   foreground=1; shift ;;
    -h|--help)      usage; exit 0 ;;
    *)              usage >&2; die "unknown argument: $1" ;;
  esac
done

plugin_url="http://$service_host:$port/health"

status_line() {
  local pid=""
  if pid="$(read_pid "$HEADLESS_PID")" && pid_alive "$pid"; then
    if plugin_health_ok "$service_host" "$port"; then
      printf 'running (pid %s), plugin ok at http://%s:%s\n' "$pid" "$service_host" "$port"
      curl -s -m 3 "http://$service_host:$port/instance" 2>/dev/null || true
      printf '\n'
    else
      printf 'running (pid %s), plugin NOT answering on port %s\n' "$pid" "$port"
      return 1
    fi
  else
    printf 'not running\n'
    return 1
  fi
}

start_recaf() {
  local java resolved_jar resolved_plugins
  java="$(resolve_java "$java_bin")" || die "java not found; pass --java /path/to/java or set JAVA_HOME"
  resolved_jar="$(resolve_recaf_jar "$jar")" || die "recaf.jar not found; pass --jar or set RECAF_JAR (e.g. libs/recaf.jar)"
  resolved_plugins="$(resolve_plugin_dir "$plugin_dir")" \
    || die "no recaf-mcp-plugin*.jar found; build it with './gradlew jar' or pass --plugin-dir"

  if [[ -n "$input" && ! -f "$input" ]]; then
    die "input workspace not found: $input"
  fi

  local local_pid=""
  if local_pid="$(read_pid "$HEADLESS_PID")" && pid_alive "$local_pid"; then
    log "headless Recaf already running (pid $local_pid)"
    status_line || true
    return 0
  fi

  # The plugin honours RECAF_MCP_PORT, so --port pins the port instead of only
  # predicting it (needed to run several headless instances side by side).
  export RECAF_MCP_HOST="$service_host"
  export RECAF_MCP_PORT="$port"

  local -a command=("$java")
  if [[ -n "$jvm_args" ]]; then
    # shellcheck disable=SC2206  # intentionally word-split: this is a JVM flag list
    command+=($jvm_args)
  fi
  command+=(-jar "$resolved_jar" --headless --idle -r "$resolved_plugins")
  if [[ -n "$data_dir" ]]; then
    command+=(-d "$data_dir")
  fi
  if [[ -n "$input" ]]; then
    command+=(-i "$input")
  fi

  if (( foreground )); then
    log "starting headless Recaf in the foreground"
    log "  ${command[*]}"
    exec "${command[@]}"
  fi

  log "starting headless Recaf (no display needed)"
  log "  ${command[*]}"
  : > "$HEADLESS_LOG"
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup "${command[@]}" >> "$HEADLESS_LOG" 2>&1 < /dev/null &
  else
    nohup "${command[@]}" >> "$HEADLESS_LOG" 2>&1 < /dev/null &
  fi
  local pid=$!
  printf '%s\n' "$pid" > "$HEADLESS_PID"

  if ! wait_plugin_health "$service_host" "$port" "$timeout"; then
    warn "plugin did not answer on $plugin_url within ${timeout}s"
    tail_log "$HEADLESS_LOG" 30
    if pid_alive "$pid"; then
      warn "Recaf is still running (pid $pid); check the log above, then 'start_recaf_headless.sh stop'"
    else
      rm -f "$HEADLESS_PID"
      die "Recaf exited before its plugin came up"
    fi
    exit 1
  fi

  log "ready: plugin on http://$service_host:$port (pid $pid)"
  log "log: $HEADLESS_LOG"
  log "next: ./scripts/linux/start_mcp_http.sh start"
}

case "$action" in
  start)   start_recaf ;;
  stop)    stop_pid_file "$HEADLESS_PID" "headless Recaf" ;;
  restart) stop_pid_file "$HEADLESS_PID" "headless Recaf"; start_recaf ;;
  status)  status_line ;;
esac
