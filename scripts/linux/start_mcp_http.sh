#!/usr/bin/env bash
#
# Start / stop the Recaf MCP server over streamable HTTP on Linux.
#
# Linux counterpart of start_recaf_mcp_http.ps1: same defaults, same reuse
# behaviour, but it works over ssh, in containers and from systemd.
#
#   ./start_mcp_http.sh start            # http://127.0.0.1:8751/mcp
#   ./start_mcp_http.sh status
#   ./start_mcp_http.sh stop
#
# Environment: RECAF_HOST, RECAF_PORT, RECAF_MCP_HOST, RECAF_MCP_PORT,
#              RECAF_JAR, RECAF_PLUGIN_DIR, RECAF_DATA_DIR, RECAF_HEADLESS.

set -euo pipefail
# shellcheck source=scripts/linux/_common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

usage() {
  cat <<'EOF'
Usage: start_mcp_http.sh [start|stop|status|restart] [options]

Actions:
  start (default)   start the MCP HTTP server in the background (reuses a running one)
  stop              stop the process recorded in .run/mcp-http.pid
  status            show whether it runs and whether the Recaf plugin answers
  restart           stop, then start

Options:
  --host ADDR          MCP listen address      (default: 127.0.0.1)
  --port N             MCP listen port         (default: 8751)
  --recaf-host ADDR    Recaf plugin address    (default: 127.0.0.1)
  --recaf-port N       first Recaf plugin port (default: 8750)
  --headless           new Recaf processes start with --headless --idle
  --gui                new Recaf processes start as GUI windows
  --recaf-jar PATH     recaf.jar for open_recaf_window (default: $RECAF_JAR)
  --recaf-plugins-dir DIR  plugin directory for headless launches (-r)
  --recaf-data-dir DIR     Recaf data directory for headless launches (-d)
  --timeout N          seconds to wait for the MCP port (default: 30)
  --python PATH        python interpreter (default: .venv/bin/python, python3)
  --no-plugin-check    start even when the Recaf plugin is not answering yet
  --foreground         run in the foreground (Ctrl-C to stop)
  -h, --help           show this help

Logs: logs/mcp-http.log, pid: .run/mcp-http.pid.
EOF
}

action="start"
listen_host="${RECAF_MCP_HTTP_HOST:-127.0.0.1}"
listen_port="${RECAF_MCP_HTTP_PORT:-8751}"
recaf_host="${RECAF_HOST:-127.0.0.1}"
recaf_port="${RECAF_PORT:-8750}"
timeout=30
foreground=0
plugin_check=1
python_bin=""
server_args=()

while (( $# )); do
  case "$1" in
    start|stop|status|restart) action="$1"; shift ;;
    --host)              listen_host="${2:?}"; shift 2 ;;
    --port)              listen_port="${2:?}"; shift 2 ;;
    --recaf-host)        recaf_host="${2:?}"; shift 2 ;;
    --recaf-port)        recaf_port="${2:?}"; shift 2 ;;
    --headless)          server_args+=(--headless); shift ;;
    --gui)               server_args+=(--gui); shift ;;
    --recaf-jar)         server_args+=(--recaf-jar "${2:?}"); shift 2 ;;
    --recaf-plugins-dir) server_args+=(--recaf-plugins-dir "${2:?}"); shift 2 ;;
    --recaf-data-dir)    server_args+=(--recaf-data-dir "${2:?}"); shift 2 ;;
    --timeout)           timeout="${2:?}"; shift 2 ;;
    --no-plugin-check)   plugin_check=0; shift ;;
    --foreground)        foreground=1; shift ;;
    --python)            python_bin="${2:?}"; shift 2 ;;
    -h|--help)           usage; exit 0 ;;
    *)                   usage >&2; die "unknown argument: $1" ;;
  esac
done

mcp_url="http://$listen_host:$listen_port/mcp"
plugin_url="http://$recaf_host:$recaf_port/health"

status_line() {
  local pid=""
  if pid="$(read_pid "$MCP_PID")" && pid_alive "$pid"; then
    if http_answers "$mcp_url"; then
      printf 'running (pid %s), MCP at %s\n' "$pid" "$mcp_url"
    else
      printf 'running (pid %s), MCP port not answering at %s\n' "$pid" "$mcp_url"
      return 1
    fi
  else
    printf 'not running\n'
    return 1
  fi
  if plugin_health_ok "$recaf_host" "$recaf_port"; then
    printf 'Recaf plugin: ok (%s)\n' "$plugin_url"
  else
    printf 'Recaf plugin: NOT answering (%s) - start Recaf first\n' "$plugin_url"
  fi
  return 0
}

start_server() {
  local python
  if [[ -n "$python_bin" ]]; then
    [[ -x "$python_bin" ]] || die "python not executable: $python_bin"
    python="$python_bin"
  else
    python="$(resolve_python)" || die "python3 not found; create .venv (scripts/linux/install.sh) or install python3"
  fi

  if (( plugin_check )) && ! plugin_health_ok "$recaf_host" "$recaf_port"; then
    die "Recaf plugin is not reachable at $plugin_url.
Start Recaf (GUI) or a headless Recaf ('./scripts/linux/start_recaf_headless.sh start')
and confirm the plugin loaded, or pass --no-plugin-check to skip this check."
  fi

  local existing_pid=""
  if existing_pid="$(read_pid "$MCP_PID")" && pid_alive "$existing_pid"; then
    log "MCP server already running (pid $existing_pid)"
    status_line || true
    printf '%s\n' "$mcp_url"
    return 0
  fi

  local -a command=("$python" "$REPO_ROOT/recaf_mcp_server.py" --http
                    --host "$listen_host" --port "$listen_port"
                    --recaf-host "$recaf_host" --recaf-port "$recaf_port")
  if (( ${#server_args[@]} )); then
    command+=("${server_args[@]}")
  fi

  if (( foreground )); then
    log "starting MCP server in the foreground"
    log "  ${command[*]}"
    exec "${command[@]}"
  fi

  log "starting MCP server on $mcp_url"
  : > "$MCP_LOG"
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup "${command[@]}" >> "$MCP_LOG" 2>&1 < /dev/null &
  else
    nohup "${command[@]}" >> "$MCP_LOG" 2>&1 < /dev/null &
  fi
  local pid=$!
  printf '%s\n' "$pid" > "$MCP_PID"

  local waited=0
  while (( waited < timeout )); do
    if http_answers "$mcp_url"; then
      break
    fi
    sleep 0.5
    waited=$((waited + 1))
  done

  if ! http_answers "$mcp_url"; then
    warn "MCP server did not answer on $mcp_url within ${timeout}s"
    tail_log "$MCP_LOG" 30 >&2
    if pid_alive "$pid"; then
      warn "process is still running (pid $pid); check the log above"
    else
      rm -f "$MCP_PID"
      die "MCP server exited during startup"
    fi
    exit 1
  fi

  log "ready: MCP at $mcp_url (pid $pid)"
  log "log: $MCP_LOG"
  log "client config: {\"mcpServers\":{\"recaf-mcp\":{\"url\":\"$mcp_url\"}}}"
}

case "$action" in
  start)   start_server ;;
  stop)    stop_pid_file "$MCP_PID" "MCP server" ;;
  restart) stop_pid_file "$MCP_PID" "MCP server"; start_server ;;
  status)  status_line ;;
esac
