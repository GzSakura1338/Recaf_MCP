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
import contextvars
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import httpx

PORT_SPAN = 20
RESERVED_PORTS = {8751}
CACHE_TTL_SECONDS = 3.0
REPO_ROOT = Path(__file__).resolve().parents[3]

host = "127.0.0.1"
base_port = 8750
skip_ports = set(RESERVED_PORTS)
launch_jar: Optional[str] = None

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


def invalidate() -> None:
    global _cache_time
    _cache_time = 0.0


def describe(window: Dict[str, Any], active: Optional[int] = None) -> Dict[str, Any]:
    return {
        "window": window["window"],
        "label": window["label"],
        "jar_name": window.get("jar_name"),
        "jar_path": window.get("jar_path"),
        "has_workspace": window.get("has_workspace"),
        "port": window["port"],
        "pid": window.get("pid"),
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


async def discover() -> List[Dict[str, Any]]:
    global _cache, _cache_time, _next_number
    ports = [p for p in range(base_port, base_port + PORT_SPAN) if p not in skip_ports]
    timeout = httpx.Timeout(2.0, connect=0.3)
    async with httpx.AsyncClient(trust_env=False, timeout=timeout) as client:
        probed = await asyncio.gather(*(_probe(client, p) for p in ports))

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
        if name and name.lower() not in (window.get("jar_name") or "").lower():
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


async def resolve(spec: str = "") -> Dict[str, Any]:
    spec = (spec or "").strip()
    for refresh in (False, True):
        found = await _windows(refresh)
        try:
            return _pick(spec, found) if spec else _pick_default(found)
        except WindowError:
            if refresh:
                raise
    raise AssertionError("unreachable")


async def resolve_base() -> str:
    return (await resolve(requested_window.get()))["base"]


async def list_windows() -> Dict[str, Any]:
    found = await _windows(refresh=True)
    current = _find(found, _active_window) if _active_window is not None else None
    return {
        "windows": [describe(w, _active_window) for w in found],
        "active": current["label"] if current else None,
    }


async def switch(spec: str) -> Dict[str, Any]:
    if not (spec or "").strip():
        raise WindowError("missing 'window'", await _windows(refresh=True))
    global _active_window
    window = _pick(spec.strip(), await _windows(refresh=True))
    _active_window = window["window"]
    return {"active": window["label"], "window": describe(window, window["window"])}


def _java_executable() -> Optional[str]:
    on_windows = sys.platform == "win32"
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


def _command_line_of(pid: int) -> Optional[List[str]]:
    if sys.platform != "win32":
        try:
            raw = Path(f"/proc/{int(pid)}/cmdline").read_bytes()
        except OSError:
            return None
        return [part.decode(errors="replace") for part in raw.split(b"\0") if part] or None

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
    return [java, "-jar", jar]


def _launch_command(found: List[Dict[str, Any]]) -> List[str]:
    if launch_jar:
        return _jar_command(launch_jar)
    for window in found:
        if window.get("pid"):
            command = _command_line_of(window["pid"])
            if command:
                return command
    for candidate in (os.environ.get("RECAF_JAR"), REPO_ROOT / "libs" / "recaf.jar",
                      REPO_ROOT.parent / "recaf" / "recaf.jar"):
        if candidate and Path(candidate).is_file():
            return _jar_command(str(candidate))
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
    command = await asyncio.to_thread(_launch_command, list(_cache))
    try:
        process = subprocess.Popen(
            command, cwd=_launch_cwd(command), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, close_fds=True, **_detached_kwargs(),
        )
    except OSError as e:
        raise WindowError(f"failed to start Recaf ({command[0]}): {e}")

    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        await asyncio.sleep(1.0)
        for window in await discover():
            if window["instance_id"] not in before:
                return window
        if process.poll() is not None:
            raise WindowError(f"Recaf exited with code {process.returncode} before its plugin came up; "
                              f"command: {command}")
    raise WindowError(f"Recaf (pid {process.pid}) was started but its plugin did not answer within "
                      f"{wait_seconds}s; it may still be loading, check list_recaf_windows again")
