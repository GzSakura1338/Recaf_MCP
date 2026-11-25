#!/usr/bin/env bash
# Shared helpers for the Linux launcher scripts. Source this file, do not run it.
#
# Everything the scripts need to find (java, recaf.jar, the plugin jar, python)
# is resolved here, in one place, with the same precedence the build uses:
# explicit flag > environment variable > repository default.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RUN_DIR="${RECAF_MCP_RUN_DIR:-$REPO_ROOT/.run}"
LOG_DIR="${RECAF_MCP_LOG_DIR:-$REPO_ROOT/logs}"
# shellcheck disable=SC2034  # used by the scripts that source this file
HEADLESS_PID="$RUN_DIR/recaf-headless.pid"
# shellcheck disable=SC2034  # used by the scripts that source this file
HEADLESS_LOG="$LOG_DIR/recaf-headless.log"
# shellcheck disable=SC2034  # used by the scripts that source this file
MCP_PID="$RUN_DIR/mcp-http.pid"
# shellcheck disable=SC2034  # used by the scripts that source this file
MCP_LOG="$LOG_DIR/mcp-http.log"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
warn() { printf '[%s] warning: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '[%s] error: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

mkdir -p "$RUN_DIR" "$LOG_DIR"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

# --- java ---------------------------------------------------------------------

resolve_java() {
  local explicit="${1:-}"
  local candidate
  for candidate in "${explicit:-}" "${RECAF_JAVA:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  candidate="$(command -v java 2>/dev/null || true)"
  [[ -n "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

# --- recaf.jar ----------------------------------------------------------------

resolve_recaf_jar() {
  local explicit="${1:-}"
  local candidate
  for candidate in "${explicit:-}" "${RECAF_JAR:-}" "$REPO_ROOT/libs/recaf.jar" "$REPO_ROOT/../recaf/recaf.jar"; do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
      printf '%s\n' "$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
      return 0
    fi
  done
  return 1
}

# --- plugin jar ---------------------------------------------------------------

resolve_plugin_dir() {
  local explicit="${1:-}"
  local candidate
  for candidate in "${explicit:-}" "${RECAF_PLUGIN_DIR:-}" "${RECAF_EXTRA_PLUGINS:-}" "$REPO_ROOT/build/libs"; do
    [[ -n "$candidate" ]] || continue
    if compgen -G "$candidate/recaf-mcp-plugin*.jar" >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

# Recaf's own plugin directory, where a normal (GUI or headless) start picks the
# plugin up without -r. Matches RecafDirectoriesConfig: XDG_CONFIG_HOME/Recaf/plugins.
default_plugin_install_dir() {
  printf '%s\n' "${RECAF_PLUGIN_INSTALL_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/Recaf/plugins}"
}

# --- python -------------------------------------------------------------------

resolve_python() {
  local candidate
  for candidate in "$REPO_ROOT/.venv/bin/python" "$(command -v python3 2>/dev/null || true)" \
                   "$(command -v python 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

# --- http ---------------------------------------------------------------------

# True when $1 answers with HTTP 2xx/3xx/4xx (i.e. something is listening).
http_answers() {
  local url="$1" code
  code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$url" 2>/dev/null || true)"
  [[ -n "$code" && "$code" != "000" ]]
}

plugin_health_ok() {
  [[ "$(curl -s -m 3 "http://$1:$2/health" 2>/dev/null || true)" == "ok" ]]
}

# Print the first port in [base, base+20) that answers as a Recaf MCP plugin.
find_running_plugin() {
  local host="$1" base="$2" port
  for (( port = base; port < base + 20; port++ )); do
    if plugin_health_ok "$host" "$port"; then
      printf '%s\n' "$port"
      return 0
    fi
  done
  return 1
}

wait_plugin_health() {
  local host="$1" port="$2" timeout="$3" waited=0
  while (( waited < timeout )); do
    if plugin_health_ok "$host" "$port"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# --- process ------------------------------------------------------------------

pid_alive() {
  local pid="${1:-}"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

read_pid() {
  local file="$1"
  [[ -f "$file" ]] || return 1
  local pid
  pid="$(tr -d '[:space:]' < "$file")"
  [[ -n "$pid" ]] || return 1
  printf '%s\n' "$pid"
}

stop_pid_file() {
  local file="$1" name="$2" pid waited=0
  if ! pid="$(read_pid "$file")"; then
    log "$name is not running (no pid file at $file)"
    return 0
  fi
  if ! pid_alive "$pid"; then
    log "$name is not running (stale pid $pid)"
    rm -f "$file"
    return 0
  fi
  log "stopping $name (pid $pid)"
  kill -TERM "$pid" 2>/dev/null || true
  while (( waited < 20 )) && pid_alive "$pid"; do
    sleep 0.5
    waited=$((waited + 1))
  done
  if pid_alive "$pid"; then
    warn "$name did not exit after 10s, sending SIGKILL"
    kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$file"
  log "$name stopped"
}

tail_log() {
  local file="$1" lines="${2:-20}"
  if [[ -f "$file" ]]; then
    printf -- '--- last %s lines of %s ---\n' "$lines" "$file" >&2
    tail -n "$lines" "$file" >&2 || true
    printf -- '---\n' >&2
  fi
}
