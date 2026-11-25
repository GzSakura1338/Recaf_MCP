"""
Recaf MCP Server - Window Registry

Every Recaf process running the plugin binds its own port (the first free one
from the base port). This module discovers them, gives each a stable number
("window 2"), and resolves which one a tool call should talk to.

Numbers are handed out in the order windows are first seen and never reused
while this server runs, so "2:client.jar" can not silently start meaning a
different jar after window 2 is closed.
"""

import asyncio
import atexit
import contextvars
import json
import os
import re
import shlex
import signal
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import httpx

PORT_SPAN = 20
RESERVED_PORTS = {8751}
# A discovered window list is reused for this long before it is probed again.
CACHE_TTL_SECONDS = 3.0
# ...and a window picked from an older list is still returned for this long: the
# tool call itself proves whether the window answers, and a connection failure
# invalidates the list and retries. Probing 20 ports per call is pure overhead.
OPTIMISTIC_TTL_SECONDS = 30.0
PROBE_TIMEOUT = httpx.Timeout(2.0, connect=0.3)
# Retry a failed idle stop this long after the attempt, instead of spinning.
IDLE_RETRY_SECONDS = 30.0
REPO_ROOT = Path(__file__).resolve().parents[3]

host = "127.0.0.1"
base_port = 8750
skip_ports = set(RESERVED_PORTS)
launch_jar: Optional[str] = None

# How a brand-new Recaf process is started. Everything here can be set from the
# command line (see recaf_mcp_server.py) or from the environment, which is what
# the Linux headless launcher and systemd units use.
#   headless: None = auto (headless on Linux when there is no DISPLAY), True/False = force
headless: Optional[bool] = None
plugin_dir: Optional[str] = None
data_dir: Optional[str] = None
java_binary: Optional[str] = None
extra_java_args: List[str] = []
launch_log: Optional[str] = None
# Start a headless Recaf automatically when a tool call finds no window at all.
# This is what lets an MCP host (DSH, Claude Code, ...) bring the whole chain up
# on its own: the host spawns this server, the server spawns Recaf.
auto_start: bool = False
auto_start_wait: int = 90
AUTO_START_RETRY_SECONDS = 60.0

# Stop Recaf again after this many seconds without a Recaf tool call (0 = keep it
# running). Only processes this server started are tracked, so a Recaf the user
# launched (GUI or scripts/linux) is never touched. This is what keeps a host
# session cheap: the Java side exists only while it is being used.
idle_stop_seconds: float = 0.0

# One Recaf at a time on Linux: a second instance only costs memory, and the
# headless host has no use for parallel windows. None = auto (single on Linux).
single_recaf: Optional[bool] = None

_auto_start_lock: Optional[asyncio.Lock] = None
_auto_start_failed_at = 0.0
_owned_windows: Dict[int, str] = {}
_owned_procs: Dict[int, Any] = {}
_last_activity = 0.0
_idle_thread: Optional[threading.Thread] = None
# Set by every tool call: the watchdog sleeps on it instead of waking up to look.
_idle_wakeup = threading.Event()

# One keep-alive client for the port probes, per event loop (a client is bound to
# the loop that created it). Creating a client is not free: httpx builds and fills
# an SSL context, which costs ~18ms of CPU for an http:// request that needs none.
_probe_http: Optional[httpx.AsyncClient] = None
_probe_http_loop: Any = None


def _log(message: str) -> None:
    """Diagnostics go to stderr: stdout carries the stdio MCP protocol."""
    print(f"[recaf-mcp] {message}", file=sys.stderr, flush=True)

requested_window = contextvars.ContextVar("recaf_window", default="")

_numbers: Dict[str, int] = {}
_next_number = 1
_active_window: Optional[int] = None
_cache: List[Dict[str, Any]] = []
_cache_time = 0.0

_SPEC = re.compile(r"^\s*#?(\d+)\s*(?:[:：\-]\s*(.*?))?\s*$")


class WindowError(Exception):
    def __init__(self, message: str, found: Optional[List[Dict[str, Any]]] = None):
        super().__init__(message)
        self.found = found or []

    def payload(self) -> Dict[str, Any]:
        out: Dict[str, Any] = {"error": str(self)}
        if self.found:
            out["windows"] = [describe(w) for w in self.found]
        return out


def configure(recaf_host: str, recaf_base_port: int, extra_skip_ports=()) -> None:
    global host, base_port, skip_ports
    host = recaf_host
    base_port = recaf_base_port
    skip_ports = set(RESERVED_PORTS) | set(extra_skip_ports)
    invalidate()


def set_launch_jar(path: Optional[str]) -> None:
    global launch_jar
    launch_jar = path


def configure_launch(recaf_jar: Optional[str] = None, headless_mode: Optional[bool] = None,
                     plugins: Optional[str] = None, data: Optional[str] = None,
                     java: Optional[str] = None, java_args: Optional[List[str]] = None,
                     log: Optional[str] = None, auto: Optional[bool] = None,
                     auto_wait: Optional[int] = None, idle_stop: Optional[int] = None,
                     single: Optional[bool] = None) -> None:
    """Configure how open_recaf_window (and auto-start) launches a Recaf process."""
    global launch_jar, headless, plugin_dir, data_dir, java_binary, extra_java_args, launch_log
    global auto_start, auto_start_wait, idle_stop_seconds, single_recaf
    if recaf_jar is not None:
        launch_jar = recaf_jar
    if headless_mode is not None:
        headless = headless_mode
    if plugins is not None:
        plugin_dir = plugins
    if data is not None:
        data_dir = data
    if java is not None:
        java_binary = java
    if java_args is not None:
        extra_java_args = list(java_args)
    if log is not None:
        launch_log = log
    if auto is not None:
        auto_start = auto
    if auto_wait is not None:
        auto_start_wait = max(10, int(auto_wait))
    if idle_stop is not None:
        idle_stop_seconds = max(0.0, float(idle_stop))
    if single is not None:
        single_recaf = single


def single_mode() -> bool:
    """True when this server refuses to run a second Recaf (default on Linux)."""
    if single_recaf is not None:
        return single_recaf
    return sys.platform.startswith("linux")


def _headless_requested() -> bool:
    """True when a new Recaf should start with --headless --idle (no display needed)."""
    if headless is not None:
        return headless
    # Auto: a Linux box without a display cannot start Recaf's GUI at all, so
    # headless is the only thing that can work there.
    return sys.platform.startswith("linux") and not os.environ.get("DISPLAY")


def invalidate() -> None:
    global _cache_time
    _cache_time = 0.0


def _touch() -> None:
    """Record activity: any tool call pushes the idle deadline out."""
    global _last_activity
    _last_activity = time.monotonic()
    _idle_wakeup.set()


def owned_windows() -> Dict[int, str]:
    """PIDs (and labels) of Recaf processes this server started and may stop."""
    return dict(_owned_windows)


def _remember_owned(window: Dict[str, Any], process: Any = None) -> None:
    pid = window.get("pid")
    if not pid:
        return
    label = window.get("label") or f"port {window.get('port')}"
    _owned_windows[int(pid)] = label
    if process is not None:
        # Keep the handle so a stopped child can be reaped instead of lingering
        # as a zombie for the rest of the session.
        _owned_procs[int(pid)] = process
    _touch()
    if idle_stop_seconds > 0:
        _log(f"started Recaf pid {pid} ({label}); it stops after {int(idle_stop_seconds)}s without tool calls")
    _save_state()
    _start_idle_thread()


# --- ownership state ------------------------------------------------------
#
# A host reloads the MCP server (config change, reconnect) while the Java process
# it started keeps running. The pid list is kept on disk, so the next server
# instance can adopt those processes and still recycle them.

def _state_path() -> Path:
    override = os.environ.get("RECAF_MCP_STATE_FILE")
    if override:
        return Path(override).expanduser()
    base = os.environ.get("XDG_RUNTIME_DIR") or tempfile.gettempdir()
    return Path(base) / "recaf-mcp-owned.json"


def _save_state() -> None:
    try:
        path = _state_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"pids": [{"pid": pid, "label": label}
                                             for pid, label in _owned_windows.items()]}))
    except OSError:
        pass


def _load_state() -> List[Dict[str, Any]]:
    try:
        data = json.loads(_state_path().read_text())
    except (OSError, ValueError):
        return []
    entries = data.get("pids") if isinstance(data, dict) else None
    return entries if isinstance(entries, list) else []


def _looks_like_our_recaf(pid: int) -> bool:
    """Adopt only processes that are clearly a headless Recaf this tool started."""
    command = _proc_command_line(pid) or _ps_command_line(pid) or []
    if not command or "--headless" not in command:
        return False
    if not any(part.endswith("recaf.jar") for part in command):
        return False
    if plugin_dir and not any(part == plugin_dir for part in command):
        return False
    return True


def adopt_previous_windows() -> List[int]:
    """Take over Recaf processes a previous server instance left behind."""
    adopted: List[int] = []
    for entry in _load_state():
        try:
            pid = int(entry.get("pid") or 0)
        except (TypeError, ValueError):
            continue
        if pid <= 0 or pid in _owned_windows or not _looks_like_our_recaf(pid):
            continue
        _owned_windows[pid] = str(entry.get("label") or f"pid {pid}")
        adopted.append(pid)
        _log(f"adopted Recaf pid {pid} ({_owned_windows[pid]}) from a previous server instance")
    if adopted:
        # Give an adopted window a full idle window instead of stopping it at once.
        _touch()
        _start_idle_thread()
    _save_state()
    return adopted


def _start_idle_thread() -> None:
    """Watchdog thread: also works before an event loop exists (after adoption)."""
    global _idle_thread
    if idle_stop_seconds <= 0 or not _owned_windows:
        return
    if _idle_thread is not None and _idle_thread.is_alive():
        return
    _idle_thread = threading.Thread(target=_idle_loop, name="recaf-idle-stop", daemon=True)
    _idle_thread.start()
    _idle_wakeup.set()


def _should_stop_idle(now: Optional[float] = None) -> bool:
    if idle_stop_seconds <= 0 or not _owned_windows:
        return False
    return ((now if now is not None else time.monotonic()) - _last_activity) >= idle_stop_seconds


def _idle_loop() -> None:
    """Block until the idle deadline passes; no periodic wake-up at all.

    Activity sets _idle_wakeup, which shortens the wait to a re-computation of
    the remaining time, so a busy session costs one wake-up per tool call and an
    idle one costs exactly one wake-up (when the deadline is reached).
    """
    while _owned_windows:
        if idle_stop_seconds <= 0:
            return
        # _last_activity is 0 until the first call touches it: give a full idle
        # window instead of stopping a process that was just registered.
        last = _last_activity or time.monotonic()
        remaining = idle_stop_seconds - (time.monotonic() - last)
        if remaining > 0:
            if _idle_wakeup.wait(remaining):
                _idle_wakeup.clear()
            continue
        stopped = stop_owned_windows_now()
        if not stopped:
            # The process refused to die; do not spin on it.
            _idle_wakeup.wait(IDLE_RETRY_SECONDS)
            _idle_wakeup.clear()


async def stop_owned_windows() -> List[int]:
    """Stop every Recaf this server started (never the user's own windows)."""
    stopped: List[int] = []
    idle = int(max(0.0, time.monotonic() - _last_activity))
    for pid, label in list(_owned_windows.items()):
        if await asyncio.to_thread(_terminate_pid, pid):
            _owned_windows.pop(pid, None)
            _reap(pid)
            stopped.append(pid)
            _log(f"stopped Recaf pid {pid} ({label}) after {idle}s without tool calls")
        else:
            _log(f"could not stop Recaf pid {pid} ({label}); leaving it running")
    if stopped:
        _save_state()
        invalidate()
    return stopped


def _reap(pid: int) -> None:
    process = _owned_procs.pop(pid, None)
    if process is not None:
        try:
            process.poll()
        except Exception:  # noqa: BLE001 - reaping must never break shutdown
            pass


def stop_owned_windows_now() -> List[int]:
    """Synchronous stop for signal/exit paths, where no event loop is available."""
    stopped: List[int] = []
    for pid, label in list(_owned_windows.items()):
        _log(f"stopping Recaf pid {pid} ({label}): this MCP server is shutting down")
        if not _terminate_pid(pid, wait=3.0):
            _log(f"could not stop Recaf pid {pid} ({label})")
            continue
        _reap(pid)
        _owned_windows.pop(pid, None)
        stopped.append(pid)
    if stopped:
        _save_state()
        invalidate()
    return stopped


def _terminate_pid(pid: int, wait: float = 8.0) -> bool:
    """Ask one process to exit, then force it. True when it is gone or already was."""
    if sys.platform == "win32":
        try:
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                           capture_output=True, timeout=20, check=False)
            return True
        except (OSError, subprocess.SubprocessError):
            return False
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    except OSError:
        return False
    end = time.monotonic() + wait
    while time.monotonic() < end:
        time.sleep(0.2)
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        except OSError:
            return False
    try:
        os.kill(pid, signal.SIGKILL)
    except OSError:
        return False
    return True


@atexit.register
def _stop_owned_on_exit() -> None:
    """A stdio server owns its Recaf processes: do not leave Java behind."""
    stop_owned_windows_now()
    try:
        if not _owned_windows:
            _state_path().unlink(missing_ok=True)
    except OSError:
        pass


def describe(window: Dict[str, Any], active: Optional[int] = None) -> Dict[str, Any]:
    return {
        "window": window["window"],
        "label": window["label"],
        "jar_name": window.get("jar_name"),
        "jar_path": window.get("jar_path"),
        "has_workspace": window.get("has_workspace"),
        "port": window["port"],
        "pid": window.get("pid"),
        "headless": window.get("headless"),
        "active": window["window"] == active,
    }


def _label(number: int, info: Dict[str, Any]) -> str:
    jar_name = info.get("jar_name")
    if jar_name:
        return f"{number}:{jar_name}"
    return f"{number}:(no workspace)" if info.get("has_workspace") is False else f"{number}:(unknown)"


async def _probe(client: httpx.AsyncClient, port: int) -> Optional[Dict[str, Any]]:
    base = f"http://{host}:{port}"
    try:
        resp = await client.get(f"{base}/instance")
        if resp.status_code == 200:
            info = resp.json()
            return {
                "instance_id": info["instance_id"],
                "pid": info.get("pid"),
                "port": port,
                "base": base,
                "has_workspace": info.get("has_workspace"),
                "jar_name": info.get("jar_name"),
                "jar_path": info.get("jar_path"),
                "headless": info.get("headless"),
            }
        if resp.status_code != 404:
            return None

        health = await client.get(f"{base}/health")
        if health.status_code != 200 or health.text.strip() != "ok":
            return None
        legacy: Dict[str, Any] = {
            "instance_id": f"legacy-{port}",
            "port": port,
            "base": base,
            "has_workspace": None,
        }
        workspace = await client.get(f"{base}/workspace/info")
        if workspace.status_code == 200:
            data = workspace.json()
            legacy["has_workspace"] = bool(data.get("has_workspace"))
            path = (data.get("primary") or {}).get("path")
            if path:
                legacy["jar_path"] = path
                legacy["jar_name"] = re.split(r"[\\/]", path)[-1]
        return legacy
    except (httpx.HTTPError, ValueError, KeyError):
        return None


def _probe_client() -> httpx.AsyncClient:
    """Reuse one keep-alive client per event loop instead of one per scan."""
    global _probe_http, _probe_http_loop
    loop = asyncio.get_running_loop()
    if _probe_http is not None and not _probe_http.is_closed and _probe_http_loop is loop:
        return _probe_http
    _probe_http = httpx.AsyncClient(
        trust_env=False, timeout=PROBE_TIMEOUT,
        limits=httpx.Limits(max_connections=PORT_SPAN, max_keepalive_connections=PORT_SPAN),
    )
    _probe_http_loop = loop
    return _probe_http


async def aclose() -> None:
    """Close the shared probe client (used by tests and the startup health check)."""
    global _probe_http, _probe_http_loop
    client, _probe_http = _probe_http, None
    _probe_http_loop = None
    if client is not None and not client.is_closed:
        await client.aclose()


async def discover() -> List[Dict[str, Any]]:
    global _cache, _cache_time, _next_number
    ports = [p for p in range(base_port, base_port + PORT_SPAN) if p not in skip_ports]
    probed = await asyncio.gather(*(_probe(_probe_client(), p) for p in ports))

    found = [info for info in probed if info]
    for info in found:
        number = _numbers.get(info["instance_id"])
        if number is None:
            number = _next_number
            _next_number += 1
            _numbers[info["instance_id"]] = number
        info["window"] = number
        info["label"] = _label(number, info)
    found.sort(key=lambda info: info["window"])

    _cache = found
    _cache_time = time.monotonic()
    return found


async def _windows(refresh: bool = False) -> List[Dict[str, Any]]:
    if refresh or not _cache or time.monotonic() - _cache_time > CACHE_TTL_SECONDS:
        return await discover()
    return _cache


def _find(found: List[Dict[str, Any]], number: int) -> Optional[Dict[str, Any]]:
    return next((w for w in found if w["window"] == number), None)


def _pick(spec: str, found: List[Dict[str, Any]]) -> Dict[str, Any]:
    match = _SPEC.match(spec)
    if match:
        number = int(match.group(1))
        name = match.group(2) or ""
        window = _find(found, number)
        if window is None:
            raise WindowError(f"window {number} not found (closed, or never opened)", found)
        # Match the suffix against the label, not only the jar name: "1:(no workspace)"
        # is a valid label that a caller may have just read from list_recaf_windows.
        if name and name.lower() not in (window.get("label") or "").lower():
            raise WindowError(f"window {number} is now '{window['label']}', not '{name}'", found)
        return window

    needle = spec.lower()
    hits = [w for w in found if needle in (w.get("jar_name") or "").lower()]
    if len(hits) == 1:
        return hits[0]
    if hits:
        raise WindowError(f"'{spec}' matches several windows; use the window number", hits)
    raise WindowError(f"no window matches '{spec}'", found)


def _pick_default(found: List[Dict[str, Any]]) -> Dict[str, Any]:
    if _active_window is not None:
        window = _find(found, _active_window)
        if window is None:
            raise WindowError(f"the selected window {_active_window} is gone; call switch_recaf_window", found)
        return window
    if len(found) == 1:
        return found[0]
    if not found:
        raise WindowError("no Recaf window found; start Recaf with the recaf-mcp plugin loaded")
    raise WindowError(
        "several Recaf windows are open; pass window=... or call switch_recaf_window first", found
    )


def _optimistic_pick(spec: str) -> Optional[Dict[str, Any]]:
    """Pick from the last window list if it is still young enough to trust."""
    if not _cache or _cache_time <= 0.0:
        return None
    if time.monotonic() - _cache_time > OPTIMISTIC_TTL_SECONDS:
        return None
    try:
        return _pick(spec, _cache) if spec else _pick_default(_cache)
    except WindowError:
        return None


async def resolve(spec: str = "") -> Dict[str, Any]:
    spec = (spec or "").strip()
    _touch()
    window = _optimistic_pick(spec)
    if window is not None:
        return window
    try:
        return await _resolve_once(spec)
    except WindowError as e:
        # Only "nothing is running" is worth starting Recaf for. Several windows
        # or a stale default are answers the caller has to resolve itself.
        if not auto_start or spec or e.found:
            raise
        if not await _ensure_window():
            raise
        return await _resolve_once(spec)


async def _resolve_once(spec: str) -> Dict[str, Any]:
    for refresh in (False, True):
        found = await _windows(refresh)
        try:
            return _pick(spec, found) if spec else _pick_default(found)
        except WindowError:
            if refresh:
                raise
    raise AssertionError("unreachable")


async def _ensure_window() -> bool:
    """Start one Recaf (headless when the platform has no display) and wait for its plugin."""
    global _auto_start_lock, _auto_start_failed_at
    if time.monotonic() - _auto_start_failed_at < AUTO_START_RETRY_SECONDS:
        return False
    if not _headless_available_for_autostart():
        _log("no Recaf window found and no display to start one; start Recaf first")
        return False
    if _auto_start_lock is None:
        _auto_start_lock = asyncio.Lock()
    async with _auto_start_lock:
        if await discover():
            return True
        _log("no Recaf window found; starting one (auto-start)")
        try:
            window = await launch(auto_start_wait)
        except WindowError as e:
            _auto_start_failed_at = time.monotonic()
            _log(f"auto-start failed: {e}")
            return False
        _log(f"started {window['label']} (pid {window.get('pid')}, port {window['port']}"
             + (", headless)" if window.get("headless") else ")"))
        invalidate()
        return True


def _headless_available_for_autostart() -> bool:
    """A GUI Recaf cannot start without a display; headless can."""
    return _headless_requested() or bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


async def resolve_base() -> str:
    return (await resolve(requested_window.get()))["base"]


async def list_windows() -> Dict[str, Any]:
    _touch()
    found = await _windows(refresh=True)
    current = _find(found, _active_window) if _active_window is not None else None
    result: Dict[str, Any] = {
        "windows": [describe(w, _active_window) for w in found],
        "active": current["label"] if current else None,
        "single_recaf": single_mode(),
    }
    if not found and auto_start:
        # Listing should not spawn a JVM, but the model needs to know it can just call.
        result["auto_start"] = True
        result["hint"] = ("no Recaf window is running yet; the next tool call starts one "
                          "(headless when there is no display) and then runs")
    return result


async def switch(spec: str) -> Dict[str, Any]:
    _touch()
    if not (spec or "").strip():
        raise WindowError("missing 'window'", await _windows(refresh=True))
    global _active_window
    window = _pick(spec.strip(), await _windows(refresh=True))
    _active_window = window["window"]
    return {"active": window["label"], "window": describe(window, window["window"])}


def _java_executable() -> Optional[str]:
    """Locate java, preferring --java, then RECAF_JAVA, then JAVA_HOME, then PATH."""
    on_windows = sys.platform == "win32"
    if java_binary:
        candidate = Path(java_binary).expanduser()
        if candidate.is_file():
            return str(candidate)
        found = shutil.which(java_binary)
        if found:
            return found
        raise WindowError(f"java executable not found: {java_binary}")
    for name in ("javaw", "java") if on_windows else ("java",):
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            candidate = Path(java_home) / "bin" / (name + (".exe" if on_windows else ""))
            if candidate.is_file():
                return str(candidate)
        found = shutil.which(name)
        if found:
            return found
    return None


def _split_windows_command_line(command_line: str) -> List[str]:
    import ctypes
    from ctypes import wintypes

    shell32 = ctypes.windll.shell32
    shell32.CommandLineToArgvW.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(ctypes.c_int)]
    shell32.CommandLineToArgvW.restype = ctypes.POINTER(wintypes.LPWSTR)
    kernel32 = ctypes.windll.kernel32
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]

    argc = ctypes.c_int(0)
    argv = shell32.CommandLineToArgvW(command_line, ctypes.byref(argc))
    if not argv:
        return []
    try:
        return [argv[i] for i in range(argc.value)]
    finally:
        kernel32.LocalFree(ctypes.cast(argv, ctypes.c_void_p))


def _proc_command_line(pid: int) -> Optional[List[str]]:
    """Exact argv from /proc (Linux). Fails on hidepid mounts and non-Linux systems."""
    try:
        raw = Path(f"/proc/{int(pid)}/cmdline").read_bytes()
    except OSError:
        return None
    return [part.decode(errors="replace") for part in raw.split(b"\0") if part] or None


def _ps_command_line(pid: int) -> Optional[List[str]]:
    """Fallback argv for Linux when /proc/<pid>/cmdline is not readable."""
    try:
        out = subprocess.run(
            ["ps", "-o", "args=", "-p", str(int(pid))],
            capture_output=True, timeout=10, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    text = out.stdout.decode("utf-8", errors="replace").strip()
    if not text:
        return None
    try:
        return shlex.split(text) or None
    except ValueError:
        return text.split() or None


def _command_line_of(pid: int) -> Optional[List[str]]:
    if sys.platform != "win32":
        return _proc_command_line(pid) or _ps_command_line(pid)

    script = (
        "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
        f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').CommandLine"
    )
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    command_line = out.stdout.decode("utf-8", errors="replace").strip()
    return _split_windows_command_line(command_line) or None if command_line else None


def _jar_command(jar: str) -> List[str]:
    java = _java_executable()
    if java is None:
        raise WindowError("java was not found; set JAVA_HOME or put java on PATH")
    return [java, *extra_java_args, "-jar", jar]


def _headless_command(jar: str) -> List[str]:
    """
    Build the native headless Recaf command line.

    Recaf 4 can skip its JavaFX UI entirely (``--headless``) and keep running in
    the foreground for plugin-hosted services (``--idle``). That is what makes a
    display-less Linux box usable: no X server, no Xvfb, no Monocle hacks.
    ``-r`` points Recaf at the plugin jar directory and ``-d`` overrides the data
    directory, so several headless Recaf processes can run side by side.
    """
    command = _jar_command(jar)
    command += ["--headless", "--idle"]
    if plugin_dir:
        command += ["-r", plugin_dir]
    if data_dir:
        command += ["-d", data_dir]
    return command


def _window_command(jar: str) -> List[str]:
    return _headless_command(jar) if _headless_requested() else _jar_command(jar)


def _launch_command(found: List[Dict[str, Any]]) -> List[str]:
    if launch_jar:
        return _window_command(launch_jar)

    # Copying an open window's command line keeps its java, jar and mode. Only a
    # window started in the mode we want is copied, so an explicit --headless /
    # --gui always wins, and a headless window keeps its -r / -d flags.
    want_headless = _headless_requested()
    for window in found:
        pid = window.get("pid")
        if not pid:
            continue
        command = _command_line_of(pid)
        if not command:
            continue
        if ("--headless" in command) == want_headless:
            return command

    for candidate in (os.environ.get("RECAF_JAR"), REPO_ROOT / "libs" / "recaf.jar",
                      REPO_ROOT.parent / "recaf" / "recaf.jar"):
        if candidate and Path(candidate).is_file():
            return _window_command(str(candidate))
    if _headless_requested():
        raise WindowError(
            "cannot tell how to start headless Recaf: no recaf.jar was found; start the MCP server "
            "with --recaf-jar <path to recaf.jar> (and --recaf-plugins-dir <dir with the plugin jar>)"
        )
    raise WindowError(
        "cannot tell how to start Recaf: no open window reports its command line and no recaf.jar "
        "was found; start the MCP server with --recaf-jar <path to recaf.jar>"
    )


def _launch_cwd(command: List[str]) -> Optional[str]:
    if "-jar" in command:
        index = command.index("-jar") + 1
        if index < len(command):
            parent = Path(command[index]).parent
            return str(parent) if parent.is_dir() else None
    return None


def _detached_kwargs() -> Dict[str, Any]:
    if sys.platform == "win32":
        return {"creationflags": subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP}
    return {"start_new_session": True}


async def launch(wait_seconds: int = 60) -> Dict[str, Any]:
    before = {w["instance_id"] for w in await _windows(refresh=True)}
    if single_mode() and before:
        running = sorted(_cache, key=lambda w: w["window"])
        labels = ", ".join(w["label"] for w in running)
        raise WindowError(
            f"single-Recaf mode: {labels} is already running; reuse it with window=... "
            f"or stop it first (start the server with --multi-recaf to allow several)"
        )
    command = await asyncio.to_thread(_launch_command, list(_cache))
    stdout = subprocess.DEVNULL
    stderr = subprocess.DEVNULL
    log_handle = None
    if launch_log:
        try:
            log_handle = open(launch_log, "ab")
            stdout = stderr = log_handle
        except OSError:
            log_handle = None
    try:
        process = subprocess.Popen(
            command, cwd=_launch_cwd(command), stdin=subprocess.DEVNULL, stdout=stdout,
            stderr=stderr, close_fds=True, **_detached_kwargs(),
        )
    except OSError as e:
        raise WindowError(f"failed to start Recaf ({command[0]}): {e}")
    finally:
        if log_handle is not None:
            log_handle.close()

    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        await asyncio.sleep(0.5)
        for window in await discover():
            if window["instance_id"] not in before:
                _remember_owned(window, process)
                return window
        if process.poll() is not None:
            raise WindowError(f"Recaf exited with code {process.returncode} before its plugin came up; "
                              f"command: {command}")
    raise WindowError(f"Recaf (pid {process.pid}) was started but its plugin did not answer within "
                      f"{wait_seconds}s; it may still be loading, check list_recaf_windows again")
