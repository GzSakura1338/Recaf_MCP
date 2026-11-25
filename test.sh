#!/usr/bin/env bash
#
# Quick local check: build the plugin, verify the Python entrypoint, and lint the
# Linux launcher scripts.
#
#   ./test.sh             # build + entrypoint + script syntax
#   ./test.sh --headless  # also run the headless end-to-end smoke test
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

run_headless=0
for arg in "$@"; do
  case "$arg" in
    --headless) run_headless=1 ;;
    -h|--help)  printf 'Usage: %s [--headless]\n' "$(basename "$0")"; exit 0 ;;
    *)          printf 'unknown argument: %s\n' "$arg" >&2; exit 2 ;;
  esac
done

python_bin=""
for candidate in "$ROOT_DIR/.venv/bin/python" python3 python; do
  if command -v "$candidate" >/dev/null 2>&1; then
    python_bin="$candidate"
    break
  fi
done

if [[ -z "${RECAF_JAR:-}" && ! -f "libs/recaf.jar" && ! -f "../recaf/recaf.jar" ]]; then
  echo "Missing recaf.jar. Set RECAF_JAR or place recaf.jar in libs/recaf.jar"
  exit 1
fi

echo "[1/5] Building plugin"
./gradlew jar

echo "[2/5] Checking Python entrypoint"
[[ -n "$python_bin" ]] || { echo "python3 not found" >&2; exit 1; }
"$python_bin" recaf_mcp_server.py --help >/dev/null

echo "[3/5] Checking Linux launcher scripts"
for script in scripts/linux/*.sh test.sh; do
  bash -n "$script"
done
"$python_bin" -m py_compile scripts/linux/smoke_mcp.py scripts/bench_python_overhead.py \
    scripts/mcp_tool_matrix.py
echo "  ok: scripts/linux/*.sh"

echo "[4/5] Python unit tests (launch, idle recycling, single instance)"
"$python_bin" -m unittest discover -s mcp-server/tests 2>&1 | tail -3

echo "[5/5] Done"
if (( run_headless )); then
  echo "Running the headless end-to-end smoke test"
  ./scripts/linux/smoke_test.sh --no-build --with-mcp
else
  echo "Headless end-to-end test: ./test.sh --headless   (or ./scripts/linux/smoke_test.sh --with-mcp)"
fi
echo "With Recaf running and the plugin loaded: curl http://127.0.0.1:8750/health"
