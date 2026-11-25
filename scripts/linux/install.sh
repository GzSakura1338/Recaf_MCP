#!/usr/bin/env bash
#
# One-shot Linux setup for Recaf MCP:
#   1. build the plugin jar (./gradlew jar)
#   2. copy it into Recaf's plugin directory
#   3. create .venv and install the Python dependencies
#
# Afterwards: ./scripts/linux/start_recaf_headless.sh start
#             ./scripts/linux/start_mcp_http.sh start
#
# Environment: RECAF_JAR, RECAF_PLUGIN_INSTALL_DIR, PYTHON.

set -euo pipefail
# shellcheck source=scripts/linux/_common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

usage() {
  cat <<'EOF'
Usage: install.sh [options]

Options:
  --jar PATH          recaf.jar to build against (default: $RECAF_JAR, libs/recaf.jar)
  --plugins-dir DIR   where to install the plugin jar
                      (default: $RECAF_PLUGIN_INSTALL_DIR or ${XDG_CONFIG_HOME:-~/.config}/Recaf/plugins)
  --no-build          skip the Gradle build and use the existing jar in build/libs
  --no-venv           skip creating .venv / installing Python dependencies
  --python PATH       python used for the venv (default: python3)
  -h, --help          show this help
EOF
}

plugins_dir="${RECAF_PLUGIN_INSTALL_DIR:-$(default_plugin_install_dir)}"
jar=""
build=1
make_venv=1
python_bin="${PYTHON:-python3}"

while (( $# )); do
  case "$1" in
    --jar)         jar="${2:?}"; shift 2 ;;
    --plugins-dir) plugins_dir="${2:?}"; shift 2 ;;
    --no-build)    build=0; shift ;;
    --no-venv)     make_venv=0; shift ;;
    --python)      python_bin="${2:?}"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
    *)             usage >&2; die "unknown argument: $1" ;;
  esac
done

require_cmd java

resolved_jar="$(resolve_recaf_jar "$jar")" || die "recaf.jar not found.
Download a Recaf 4 release for your architecture (e.g. recaf-4x-alpha-linux-aarch.jar),
put it at libs/recaf.jar, or pass --jar /path/to/recaf.jar / set RECAF_JAR."

log "recaf.jar: $resolved_jar"

if (( build )); then
  log "building the plugin (./gradlew jar)"
  ( cd "$REPO_ROOT" && RECAF_JAR="$resolved_jar" ./gradlew jar --no-daemon -q )
fi

plugin_jar="$(find "$REPO_ROOT/build/libs" -maxdepth 1 -name 'recaf-mcp-plugin*.jar' -print -quit 2>/dev/null || true)"
[[ -n "$plugin_jar" && -f "$plugin_jar" ]] || die "no plugin jar in build/libs; run without --no-build"
log "plugin jar: $plugin_jar"

mkdir -p "$plugins_dir"
cp -f "$plugin_jar" "$plugins_dir/"
log "installed into $plugins_dir"

if (( make_venv )); then
  command -v "$python_bin" >/dev/null 2>&1 || die "$python_bin not found; install python3 or pass --python"
  if [[ ! -x "$REPO_ROOT/.venv/bin/python" ]]; then
    log "creating .venv"
    "$python_bin" -m venv "$REPO_ROOT/.venv"
  fi
  log "installing Python dependencies"
  "$REPO_ROOT/.venv/bin/python" -m pip install --quiet --upgrade pip
  "$REPO_ROOT/.venv/bin/python" -m pip install --quiet -r "$REPO_ROOT/requirements.txt"
  log "python: $REPO_ROOT/.venv/bin/python"
fi

cat <<EOF

Installed.

Next steps:
  1. headless Recaf (no display needed):
       ./scripts/linux/start_recaf_headless.sh start --input /path/to/target.jar
  2. MCP server:
       ./scripts/linux/start_mcp_http.sh start
  3. point your MCP client at:
       {"mcpServers": {"recaf-mcp": {"url": "http://127.0.0.1:8751/mcp"}}}

Recaf also picks the plugin up from $plugins_dir at every start,
so a normal GUI Recaf on this machine already has the MCP API.
EOF
