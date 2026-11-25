#!/usr/bin/env python3
"""Compare the resident cost of two checkouts (an A/B of the Python side).

Both sides run the same harness (scripts/bench_python_overhead.py) with the same
interpreter, so the numbers differ only by server code. Typical use: measure a
commit against its parent to answer "how much did this optimization save?".

    git worktree add /tmp/before <commit>          # the "before" side
    python scripts/bench_ab.py /tmp/before .        # before, after

Prints a table of the measured metrics and the relative change (negative = the
optimization made it cheaper). The exit code is 0 as long as both runs produced
numbers; it does not judge whether the change was good.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

HARNESS = Path(__file__).resolve().parent / "bench_python_overhead.py"

# metric key -> (label, higher_is_better_is_a_regression)
METRICS = [
    (("cpu_ms_per_call",), "CPU per tool call (ms)"),
    (("wall_ms_per_call",), "latency per tool call (ms)"),
    (("list_windows", "wall_ms_per_call"), "list_recaf_windows (ms)"),
    (("list_windows", "cpu_ms_per_call"), "list_recaf_windows CPU (ms)"),
    (("list_windows", "cold_scan_ms"), "one cold port scan (ms)"),
    (("rss_mb",), "resident memory RSS (MB)"),
    (("idle", "cpu_percent"), "idle CPU (%)"),
]


def run(side: Path, calls: int, idle: float) -> dict:
    command = [sys.executable, str(HARNESS), "-n", str(calls), "-i", str(idle),
               "--module-path", str(side / "mcp-server")]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        raise SystemExit(f"{side} failed:\n{completed.stdout}{completed.stderr}")
    return json.loads(completed.stdout)


def dig(data: dict, path: tuple):
    for key in path:
        if not isinstance(data, dict) or key not in data:
            return None
        data = data[key]
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("before", type=Path, help="checkout with the old code")
    parser.add_argument("after", type=Path, help="checkout with the new code")
    parser.add_argument("-n", "--calls", type=int, default=200)
    parser.add_argument("-i", "--idle-seconds", type=float, default=1.0)
    args = parser.parse_args()

    before = run(args.before.resolve(), args.calls, args.idle_seconds)
    after = run(args.after.resolve(), args.calls, args.idle_seconds)

    print(f"\n{args.calls} calls per side, {args.idle_seconds:g}s idle sample\n")
    header = f"{'metric':30s} {'before':>10s} {'after':>10s} {'change':>10s} {'faster':>8s}"
    print(header)
    print("-" * len(header))
    for path, label in METRICS:
        old, new = dig(before, path), dig(after, path)
        if old is None or new is None:
            continue
        change = (new - old) / old * 100 if old else 0.0
        speedup = f"{old / new:.1f}x" if new else "-"
        print(f"{label:30s} {old:10.3f} {new:10.3f} {change:+9.1f}% {speedup:>8s}")
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
