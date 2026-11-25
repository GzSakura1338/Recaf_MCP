#!/usr/bin/env python3
"""Measure the Python server's per-tool-call overhead and idle cost.

A fake Recaf plugin answers the few endpoints a tool call touches, so the
numbers measure this server (discovery, HTTP client, JSON) and not Recaf.

    python scripts/bench_python_overhead.py            # 200 calls
    python scripts/bench_python_overhead.py -n 1000

Reported per call: wall time (latency), CPU time (the part that costs battery
and steals a core), and the number of TCP connections opened (each new
connection is a syscall + handshake; a reused one is free). "list_windows" is
the status query a model usually calls first, reported warm (cached list) next
to what one cold scan of the port range costs. Idle cost is the CPU time burned
over one second with no call at all.
"""

import argparse
import asyncio
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# --module-path lets scripts/bench_ab.py point this harness at another checkout
# (the "before" side of an A/B run) instead of this one.
_MODULE_PATH = None
for _i, _arg in enumerate(sys.argv):
    if _arg == "--module-path" and _i + 1 < len(sys.argv):
        _MODULE_PATH = Path(sys.argv[_i + 1]).expanduser()
    elif _arg.startswith("--module-path="):
        _MODULE_PATH = Path(_arg.split("=", 1)[1]).expanduser()
sys.path.insert(0, str(_MODULE_PATH or Path(__file__).resolve().parents[1] / "mcp-server"))

from src.server import config, windows  # noqa: E402

WINDOW_PORT = 8790


class _Plugin(BaseHTTPRequestHandler):
    """Minimal stand-in for the plugin: /instance, /health, /class-info."""

    protocol_version = "HTTP/1.1"
    connections = 0

    def do_GET(self):  # noqa: N802 - http.server API
        self.close_connection = False
        path = self.path.split("?")[0]
        if path == "/instance":
            body = {"instance_id": "bench", "pid": os.getpid(), "port": WINDOW_PORT,
                    "has_workspace": True, "jar_name": "bench.jar", "headless": True}
        elif path == "/health":
            body = None
        elif path == "/workspace/info":
            body = {"has_workspace": True, "primary": {"path": "/tmp/bench.jar"}}
        elif path == "/class-info":
            body = {"name": "a/b/C", "access": 1, "super_name": "java/lang/Object",
                    "interfaces": [], "fields": 1, "methods": 2, "annotations": []}
        else:
            self._send(404, b'{"error": "not found"}', "application/json")
            return
        if body is None:
            self._send(200, b"ok", "text/plain")
        else:
            self._send(200, json.dumps(body).encode(), "application/json")

    def _send(self, code, raw, ctype):
        # One write: separate header/body writes get split into two segments and
        # then pay the 40ms delayed-ACK dance, which measures the kernel, not us.
        head = (f"HTTP/1.1 {code} {self.responses[code][0]}\r\n"
                f"Content-Type: {ctype}\r\nContent-Length: {len(raw)}\r\n"
                f"Connection: keep-alive\r\n\r\n").encode()
        self.wfile.write(head + raw)

    def log_message(self, *args):  # keep the benchmark output readable
        pass


def _serve(port: int):
    server = ThreadingHTTPServer(("127.0.0.1", port), _Plugin)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True, name="bench-plugin").start()
    return server


async def _calls(count: int) -> None:
    for _ in range(count):
        result = await config.get_from_recaf("/class-info", {"name": "a/b/C"})
        if not isinstance(result, dict) or result.get("name") != "a/b/C":
            raise SystemExit(f"unexpected plugin answer: {result!r}")


def _connections() -> int:
    try:
        return len(os.listdir(f"/proc/{os.getpid()}/fd"))
    except OSError:
        return 0


async def _measure(count: int) -> dict:
    # First call includes discovery; keep it out of the per-call average.
    await config.get_from_recaf("/class-info", {"name": "a/b/C"})
    fds_before = _connections()
    cpu_before = time.process_time()
    wall_before = time.perf_counter()
    await _calls(count)
    wall = time.perf_counter() - wall_before
    cpu = time.process_time() - cpu_before
    fds_after = _connections()
    if hasattr(config, "aclose"):
        await config.aclose()
    return {
        "calls": count,
        "wall_ms_per_call": round(wall / count * 1000, 3),
        "cpu_ms_per_call": round(cpu / count * 1000, 3),
        "cpu_us_per_call": round(cpu / count * 1_000_000, 1),
        "fds_delta": fds_after - fds_before,
    }


async def _list_windows(count: int) -> dict:
    """Cost of a status query (list_recaf_windows), excluding the first cold scan."""
    await windows.list_windows()
    cpu_before = time.process_time()
    wall_before = time.perf_counter()
    for _ in range(count):
        await windows.list_windows()
    wall = time.perf_counter() - wall_before
    cpu = time.process_time() - cpu_before
    windows.invalidate()          # a cold cache has to probe the whole port range
    scan_before = time.perf_counter()
    await windows.list_windows()
    scan_ms = (time.perf_counter() - scan_before) * 1000
    return {
        "calls": count,
        "wall_ms_per_call": round(wall / count * 1000, 3),
        "cpu_ms_per_call": round(cpu / count * 1000, 3),
        "cold_scan_ms": round(scan_ms, 3),
    }


async def _idle_cpu(seconds: float) -> dict:
    cpu_before = time.process_time()
    wall_before = time.perf_counter()
    await asyncio.sleep(seconds)
    cpu = time.process_time() - cpu_before
    wall = time.perf_counter() - wall_before
    return {"seconds": round(wall, 2), "cpu_ms": round(cpu * 1000, 2),
            "cpu_percent": round(cpu / wall * 100, 2)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-n", "--calls", type=int, default=200)
    parser.add_argument("-i", "--idle-seconds", type=float, default=1.0)
    parser.add_argument("--module-path", help="mcp-server directory to measure "
                        "(default: the one next to this script)")
    args = parser.parse_args()

    _serve(WINDOW_PORT)
    windows.configure("127.0.0.1", WINDOW_PORT, ())
    windows.invalidate()

    result = asyncio.run(_measure(args.calls))
    result["list_windows"] = asyncio.run(_list_windows(args.calls))
    result["idle"] = asyncio.run(_idle_cpu(args.idle_seconds))
    result["rss_mb"] = round(_rss_kb() / 1024, 1)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


def _rss_kb() -> int:
    try:
        with open("/proc/self/status", encoding="ascii") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])
    except OSError:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
