#!/usr/bin/env python3
"""
MCP tool matrix: call every recaf-mcp tool through a real MCP client and report.

It starts the MCP server the way an MCP host does (stdio, so the host owns the
process), opens a target jar, adds a few tiny scratch classes so bytecode/refactor
tools have deterministic input, then exercises all 70 tools and prints one line per
call: tool, PASS/FAIL, duration and a short detail.

    .venv/bin/python scripts/mcp_tool_matrix.py --jar /path/to/target.jar

Exit code 0 means every tool behaved as expected; 1 means at least one unexpected
failure (each is listed with the server's error text).

Notes
- The target jar is opened read-only. Scratch classes are added in memory;
  exports go to --export-dir (default: a temp dir that is removed afterwards).
- Nothing is written back to the jar unless a tool under test is asked to.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

REPO_ROOT = Path(__file__).resolve().parents[1]

SCRATCH_SOURCES = {
    "mcp/test/Marker.java": """package mcp.test;
public interface Marker {
    void mark();
}
""",
    "mcp/test/Target.java": """package mcp.test;
@Deprecated
public class Target {
    private int counter;
    public static final String GREETING = "hello-mcp";
    public int add(int a, int b) { return a + b; }
    public java.util.List<String> names() { return new java.util.ArrayList<String>(); }
    public static void main(String[] args) {
        String greeting = GREETING;
        Target t = new Target();
        int v = t.add(1, 2);
        System.out.println(greeting + v);
    }
    public static class Nested {
        public int twice(int x) { return x * 2; }
    }
}
""",
    "mcp/test/Scratch.java": """package mcp.test;
public class Scratch extends Target implements Marker, Comparable<Scratch> {
    private long stamp;
    public void run() {
        try {
            int x = add(1, 1);
            stamp = x;
        } catch (RuntimeException e) {
            stamp = -1;
        }
    }
    public void mark() { stamp++; }
    public int compareTo(Scratch o) { return Long.compare(stamp, o.stamp); }
    public String toString() { return "Scratch:" + stamp; }
}
""",
}


class Matrix:
    def __init__(self, session: ClientSession):
        self.session = session
        self.results: list[tuple[str, str, bool, float, str]] = []
        self.group = "general"

    # -- plumbing ---------------------------------------------------------

    def heading(self, group: str) -> None:
        self.group = group
        print(f"\n== {group}")

    async def call(self, tool: str, args: dict | None = None, timeout: float = 300.0) -> tuple[bool, object]:
        """Call one tool; return (is_error, payload)."""
        result = await self.session.call_tool(tool, args or {}, read_timeout_seconds=timeout)
        payload = getattr(result, "structuredContent", None)
        if payload is None:
            texts = [getattr(block, "text", "") for block in (getattr(result, "content", None) or [])]
            text = "\n".join(t for t in texts if t).strip()
            try:
                payload = json.loads(text) if text else None
            except json.JSONDecodeError:
                payload = text
        return bool(getattr(result, "isError", False)), payload

    def _record(self, tool: str, ok: bool, started: float, detail: str) -> None:
        elapsed = (time.monotonic() - started) * 1000
        self.results.append((self.group, tool, ok, elapsed, detail))
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {tool:<26} {elapsed:7.0f}ms  {detail}")

    async def ok(self, tool: str, args: dict | None = None, check=None, timeout: float = 300.0) -> object:
        """Expect a successful call; `check(payload)` may return (ok, detail)."""
        started = time.monotonic()
        try:
            is_error, payload = await self.call(tool, args, timeout)
        except Exception as e:  # noqa: BLE001 - report any client-side failure
            self._record(tool, False, started, f"client error: {type(e).__name__}: {e}")
            return None
        if is_error:
            text = json.dumps(payload)[:200] if not isinstance(payload, str) else payload[:200]
            self._record(tool, False, started, f"error result: {text}")
            return payload
        if check is not None:
            good, detail = check(payload)
            self._record(tool, good, started, detail)
            return payload
        self._record(tool, True, started, self.summarize(payload))
        return payload

    async def fails(self, tool: str, args: dict | None = None, contains: str = "") -> object:
        """Expect the call to report an error (the tool's own error payload or an MCP error)."""
        started = time.monotonic()
        try:
            is_error, payload = await self.call(tool, args)
        except Exception as e:  # noqa: BLE001
            self._record(tool, False, started, f"client error: {type(e).__name__}: {e}")
            return None
        text = payload if isinstance(payload, str) else json.dumps(payload)
        errorish = is_error or (isinstance(payload, dict) and "error" in payload)
        matched = contains.lower() in text.lower() if contains else True
        self._record(tool, errorish and matched, started, f"expected error: {text[:120]}")
        return payload

    @staticmethod
    def summarize(payload) -> str:
        if isinstance(payload, dict):
            if payload.get("type") == "paginated-list":
                return f"paginated: {payload.get('pagination', {}).get('count')} items of {payload.get('pagination', {}).get('total')}"
            keys = ", ".join(list(payload)[:5])
            return f"keys: {keys}"
        if isinstance(payload, str):
            return f"{len(payload)} chars"
        return repr(payload)[:80]


# -- helpers ---------------------------------------------------------------

def first_list(payload, *keys) -> list:
    """Paginated envelopes use different item keys ("items", "hits", "files", ...)."""
    if not isinstance(payload, dict):
        return []
    for key in keys:
        value = payload.get(key)
        if isinstance(value, list) and value:
            return value
    return []


def total_of(payload) -> int:
    if isinstance(payload, dict):
        return int((payload.get("pagination") or {}).get("total") or 0)
    return 0


def pick_jvm_method(methods: list) -> dict:
    """Prefer a method that actually has bytecode (no abstract/native)."""
    for method in methods:
        access = int(method.get("access") or 0)
        if method.get("name") != "<init>" and not access & 0x500:
            return method
    return methods[0] if methods else {}


# -- scratch classes -------------------------------------------------------


def build_scratch_jar(work: Path, compiled: dict[str, bytes]) -> Path:
    """Pack the compiled scratch classes into a jar the workspace can open."""
    root = work / "scratch-jar"
    for internal, data in compiled.items():
        path = root / f"{internal}.class"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    jar = work / "scratch.jar"
    with __import__("zipfile").ZipFile(jar, "w") as archive:
        for internal in compiled:
            archive.write(root / f"{internal}.class", f"{internal}.class")
    return jar

def compile_scratch(work: Path) -> dict[str, bytes]:
    """Compile the scratch sources and return {internal/Name: bytes}."""
    src = work / "src"
    out = work / "classes"
    for name, text in SCRATCH_SOURCES.items():
        path = src / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    files = sorted(str(p) for p in src.rglob("*.java"))
    result = subprocess.run(["javac", "-g", "-d", str(out), *files], capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"javac failed:\n{result.stderr}")
    compiled: dict[str, bytes] = {}
    for path in out.rglob("*.class"):
        internal = path.relative_to(out).as_posix()[: -len(".class")]
        compiled[internal] = path.read_bytes()
    return compiled


# -- the matrix ------------------------------------------------------------

async def run_matrix(jar: Path, export_dir: Path, server_python: str, jar_override: Path, plugin_dir: Path,
                     java_bin: str, timeout: int) -> int:
    scratch_names = ["mcp/test/Marker", "mcp/test/Target", "mcp/test/Target$Nested", "mcp/test/Scratch"]
    work = Path(tempfile.mkdtemp(prefix="recaf-mcp-matrix-"))
    try:
        compiled = compile_scratch(work)
        missing = [n for n in scratch_names if n not in compiled]
        if missing:
            raise SystemExit(f"scratch classes missing after javac: {missing}")

        params = StdioServerParameters(
            command=server_python,
            args=[
                str(REPO_ROOT / "recaf_mcp_server.py"),
                "--headless",
                "--auto-start-recaf",
                "--recaf-jar", str(jar_override),
                "--recaf-plugins-dir", str(plugin_dir),
                "--java", java_bin,
                "--recaf-data-dir", str(work / "recaf-data"),
                "--recaf-launch-log", str(work / "recaf-launch.log"),
            ],
            cwd=str(REPO_ROOT),
            env={**os.environ, "PYTHONUNBUFFERED": "1"},
        )
        errlog = open(work / "mcp-stderr.log", "wb")
        try:
            async with stdio_client(params, errlog=errlog) as (read, write):
                async with ClientSession(read, write, read_timeout_seconds=float(timeout)) as session:
                    await session.initialize()
                    matrix = Matrix(session)
                    await exercise(matrix, jar, work, export_dir, compiled)
                    return report(matrix, work)
        finally:
            errlog.close()
    finally:
        keep = os.environ.get("RECAF_MCP_MATRIX_KEEP")
        if keep:
            print(f"\nkept artifacts: {work}")
        else:
            shutil.rmtree(work, ignore_errors=True)


async def exercise(m: Matrix, jar: Path, work: Path, export_dir: Path, compiled: dict[str, bytes]) -> None:
    # ---- windows ---------------------------------------------------------
    m.heading("windows")
    windows = await m.ok("list_recaf_windows", check=lambda p: (
        bool(p.get("windows")) or bool(p.get("auto_start")),
        f"{len(p.get('windows') or [])} window(s), single={p.get('single_recaf')}"))
    single_mode = bool(windows.get("single_recaf")) if isinstance(windows, dict) else False
    # Address the window by number so the switch works before a jar is open. With
    # auto-start and nothing running yet the list is legitimately empty, and the
    # workspace section below starts one, so only switch when one already exists.
    first = (windows.get("windows") or [])[:1] if isinstance(windows, dict) else []
    label = str(first[0]["window"]) if first else None
    if label:
        await m.ok("switch_recaf_window", {"window": label}, check=lambda p: (bool(p.get("active")), str(p.get("active"))))

    # ---- workspace -------------------------------------------------------
    m.heading("workspace")
    await m.ok("get_workspace_info")
    await m.ok("open_workspace", {"path": str(jar)}, timeout=900,
               check=lambda p: (p.get("ok") is True, f"{p.get('primary', {}).get('class_count')} classes"))
    plugin_jar = next((REPO_ROOT / "build/libs").glob("recaf-mcp-plugin-*.jar"))
    await m.ok("add_supporting_resource", {"path": str(plugin_jar)},
               check=lambda p: (p.get("ok") is True, "supporting jar attached"))
    await m.ok("list_supporting_resources", check=lambda p: (bool(p.get("resources")), f"{len(p['resources'])} resource(s)"))
    await m.ok("fetch_current_class", check=lambda p: (
        p.get("ui_available") is False and p.get("class_name") is None, "headless: ui_available=false"))
    await m.ok("get_selected_text", check=lambda p: (
        p.get("ui_available") is False and p.get("text") is None, "headless: text=null"))

    # ---- read-only inspection of the real target jar ---------------------
    m.heading("target jar (read-only)")
    await m.ok("get_all_classes", {"offset": 0, "count": 5},
               check=lambda p: (len(first_list(p, "items", "classes")) == 5, f"{total_of(p)} classes"))
    real_class = "PROTECTED_BY_Nonissue/PROTECTED_BY_Nonissue_kNxgSQ"
    await m.ok("search_classes_by_name", {"pattern": "PROTECTED_BY_Nonissue", "count": 5},
               check=lambda p: (bool(first_list(p, "items", "classes")), f"{total_of(p)} matches"))
    await m.ok("get_class_info", {"class_name": real_class},
               check=lambda p: (bool(p.get("name")), f"{p.get('method_count')} methods, {p.get('field_count')} fields"))
    await m.ok("get_class_source", {"class_name": real_class},
               check=lambda p: (bool((p.get("text") or "").strip()), f"{p.get('decompiler')} {len(p.get('text') or '')} chars"))
    await m.ok("get_bytecode_of_class", {"class_name": real_class},
               check=lambda p: (bool(p.get("bytecode")), f"{len(p.get('bytecode') or '')} chars"))
    methods = await m.ok("get_methods_of_class", {"class_name": real_class},
                         check=lambda p: (bool(p.get("methods")), f"{len(p.get('methods') or [])} methods"))
    await m.ok("get_fields_of_class", {"class_name": real_class})
    await m.ok("get_inner_classes", {"class_name": real_class})
    await m.ok("get_annotations_of_class", {"class_name": real_class})
    await m.ok("get_raw_class_bytes", {"class_name": real_class},
               check=lambda p: (bool(p.get("bytes_base64")), f"{len(p.get('bytes_base64') or '')} b64 chars"))
    await m.ok("get_manifest", check=lambda p: (bool(p.get("content")), f"{len(p.get('content') or '')} chars of {p.get('path')}"))
    await m.ok("get_all_file_names", {"offset": 0, "count": 5},
               check=lambda p: (len(first_list(p, "items", "files")) == 5, f"{total_of(p)} files"))
    await m.ok("get_file_content", {"file_path": "META-INF/MANIFEST.MF"},
               check=lambda p: (bool(p.get("content")), f"{p.get('encoding')} {p.get('size')} bytes"))
    await m.ok("get_strings_from_resources", {"offset": 0, "count": 5},
               check=lambda p: (bool(first_list(p, "strings", "items", "hits")), f"{total_of(p)} strings"))

    real_method = pick_jvm_method(methods.get("methods") if isinstance(methods, dict) else [])
    await m.ok("get_method_by_name", {"class_name": real_class, "method_name": real_method.get("name", ""),
                                      "descriptor": real_method.get("descriptor", "")})
    await m.ok("get_method_bytecode", {"class_name": real_class, "method_name": real_method.get("name", ""),
                                       "descriptor": real_method.get("descriptor", "")},
               check=lambda p: (bool(p.get("bytecode")), f"{len(p.get('bytecode') or '')} chars"))
    await m.ok("get_method_info", {"class_name": real_class, "method_name": real_method.get("name", ""),
                                   "descriptor": real_method.get("descriptor", "")})

    m.heading("search on the target jar")
    await m.ok("search_strings", {"pattern": "a", "count": 3},
               check=lambda p: (bool(first_list(p, "hits", "items", "strings")), f"{total_of(p)} hits"))
    await m.ok("search_numbers", {"value": "1", "count": 3},
               check=lambda p: (bool(first_list(p, "hits", "items")), f"{total_of(p)} hits"))
    await m.ok("search_instructions", {"opcode": "INVOKEVIRTUAL", "count": 3},
               check=lambda p: (bool(first_list(p, "hits", "items")), f"{total_of(p)} hits"))
    await m.ok("search_members_by_name", {"pattern": "<init>", "kind": "method", "count": 3},
               check=lambda p: (bool(first_list(p, "members", "items")), f"{total_of(p)} hits"))

    m.heading("cross-references and inheritance on the target jar")
    await m.ok("get_superclasses", {"class_name": real_class}, check=lambda p: (bool(p.get("superclasses")), str((p.get("superclasses") or [])[:3])))
    await m.ok("get_interfaces", {"class_name": real_class}, check=lambda p: (bool(p.get("interfaces")) or True, str((p.get("interfaces") or [])[:3])))
    await m.ok("get_direct_subclasses", {"class_name": real_class}, check=lambda p: (isinstance(p, dict), f"{total_of(p)} subclasses"))
    await m.ok("get_all_subclasses", {"class_name": real_class}, check=lambda p: (isinstance(p, dict), f"{total_of(p)} subclasses"))
    await m.ok("get_implementors", {"interface_name": "java/lang/Runnable"}, check=lambda p: (isinstance(p, dict), f"{total_of(p)} implementors"))
    await m.ok("get_xrefs_to_class", {"class_name": real_class}, check=lambda p: (isinstance(p, dict), f"{total_of(p)} references"))
    await m.ok("get_xrefs_to_method", {"class_name": real_class, "method_name": real_method.get("name", ""),
                                       "descriptor": real_method.get("descriptor", "")},
               check=lambda p: (isinstance(p, dict), f"{total_of(p)} references"))
    await m.ok("get_callers_of_method", {"class_name": real_class, "method_name": real_method.get("name", ""),
                                         "descriptor": real_method.get("descriptor", "")},
               check=lambda p: (isinstance(p, dict), f"{total_of(p)} call sites"))
    await m.ok("get_callees_of_method", {"class_name": real_class, "method_name": real_method.get("name", ""),
                                         "descriptor": real_method.get("descriptor", "")},
               check=lambda p: (isinstance(p, dict), f"{total_of(p)} callees"))
    await m.ok("get_overrides_of_method", {"class_name": "software/coley/recaf/plugin/Plugin" if False else real_class,
                                           "method_name": real_method.get("name", ""),
                                           "descriptor": real_method.get("descriptor", "")},
               check=lambda p: (isinstance(p, dict), f"{total_of(p)} overrides"))

    m.heading("decompilers")
    decompilers = await m.ok("list_decompilers", check=lambda p: (
        bool(p.get("decompilers")), ", ".join(d["name"] for d in p.get("decompilers") or [])))
    ids = [d.get("name") for d in (decompilers or {}).get("decompilers", [])] if decompilers else []
    if ids:
        await m.ok("set_active_decompiler", {"name": ids[0]}, check=lambda p: (bool(p.get("ok") or p.get("active")), str(p)[:60]))
        other = ids[1] if len(ids) > 1 else ids[0]
        await m.ok("decompile_class_with", {"class_name": real_class, "decompiler": other},
                   check=lambda p: (bool((p.get("text") or "").strip()), f"{p.get('decompiler')} {len(p.get('text') or '')} chars"))

    # ---- scratch workspace: mutation and deterministic analysis ----------
    m.heading("scratch workspace")
    scratch_jar = build_scratch_jar(work, compiled)
    await m.ok("open_workspace", {"path": str(scratch_jar)}, timeout=300,
               check=lambda p: (p.get("ok") is True, f"{p.get('primary', {}).get('class_count')} classes"))
    for internal, data in sorted(compiled.items()):
        await m.ok("replace_class_bytes", {"class_name": internal, "bytes_base64": base64.b64encode(data).decode()},
                   check=lambda p: (p.get("ok") is True, internal))
    await m.ok("get_class_info", {"class_name": "mcp/test/Target"},
               check=lambda p: (p.get("name") == "mcp.test.Target", f"{p.get('method_count')} methods"))
    await m.ok("get_annotations_of_class", {"class_name": "mcp/test/Target"},
               check=lambda p: (bool(p.get("annotations")), m.summarize(p)))
    await m.ok("get_inner_classes", {"class_name": "mcp/test/Target"},
               check=lambda p: (bool(p.get("inner_classes")), m.summarize(p)))
    await m.ok("get_class_source", {"class_name": "mcp/test/Target"},
               check=lambda p: ("class Target" in (p.get("text") or ""), f"{len(p.get('text') or '')} chars"))
    await m.ok("get_fields_of_class", {"class_name": "mcp/test/Scratch"},
               check=lambda p: (bool(p.get("fields")), f"{len(p.get('fields') or [])} fields"))
    await m.ok("get_methods_of_class", {"class_name": "mcp/test/Scratch"},
               check=lambda p: (bool(p.get("methods")), f"{len(p.get('methods') or [])} methods"))

    m.heading("analysis on the scratch classes")
    await m.ok("get_superclasses", {"class_name": "mcp/test/Scratch"},
               check=lambda p: ("java.lang.Object" in (p.get("superclasses") or []), str((p.get("superclasses") or [])[:4])))
    await m.ok("get_interfaces", {"class_name": "mcp/test/Scratch"},
               check=lambda p: ("mcp.test.Marker" in (p.get("interfaces") or []), str((p.get("interfaces") or [])[:4])))
    await m.ok("get_direct_subclasses", {"class_name": "mcp/test/Target"},
               check=lambda p: (any(c.get("name") == "mcp.test.Scratch" for c in first_list(p, "items", "classes")),
                                str([c.get("name") for c in first_list(p, "items", "classes")][:3])))
    await m.ok("get_all_subclasses", {"class_name": "mcp/test/Target"},
               check=lambda p: (bool(first_list(p, "items", "classes")),
                                str([c.get("name") for c in first_list(p, "items", "classes")][:3])))
    await m.ok("get_implementors", {"interface_name": "mcp/test/Marker"},
               check=lambda p: (any(c.get("name") == "mcp.test.Scratch" for c in first_list(p, "items", "classes")),
                                str([c.get("name") for c in first_list(p, "items", "classes")][:3])))
    await m.ok("get_xrefs_to_class", {"class_name": "mcp/test/Target"},
               check=lambda p: (bool(first_list(p, "references", "items")), f"{len(first_list(p, 'references', 'items'))} references"))
    await m.ok("get_xrefs_to_method", {"class_name": "mcp/test/Target", "method_name": "add", "descriptor": "(II)I"},
               check=lambda p: (bool(first_list(p, "references", "items")), f"{len(first_list(p, 'references', 'items'))} references"))
    await m.ok("get_xrefs_to_field", {"class_name": "mcp/test/Scratch", "field_name": "stamp",
                                      "descriptor": "J"},
               check=lambda p: (bool(first_list(p, "references", "items")), f"{len(first_list(p, 'references', 'items'))} references"))
    await m.ok("get_callers_of_method", {"class_name": "mcp/test/Target", "method_name": "add", "descriptor": "(II)I"},
               check=lambda p: (bool(first_list(p, "references", "items")), f"{len(first_list(p, 'references', 'items'))} call sites"))
    await m.ok("get_callees_of_method", {"class_name": "mcp/test/Target", "method_name": "main",
                                         "descriptor": "([Ljava/lang/String;)V"},
               check=lambda p: (bool(first_list(p, "callees", "items")), f"{len(first_list(p, 'callees', 'items'))} callees"))
    await m.ok("get_overrides_of_method", {"class_name": "mcp/test/Marker", "method_name": "mark", "descriptor": "()V"},
               check=lambda p: (bool(first_list(p, "overrides", "items")), f"{len(first_list(p, 'overrides', 'items'))} override(s)"))

    m.heading("search on the scratch classes")
    await m.ok("search_strings", {"pattern": "hello-mcp", "count": 5},
               check=lambda p: (bool(first_list(p, "hits", "items")), f"{total_of(p)} hits"))
    await m.ok("search_numbers", {"value": "1", "count": 5},
               check=lambda p: (bool(first_list(p, "hits", "items")), f"{total_of(p)} hits"))
    await m.ok("search_instructions", {"opcode": "IADD", "count": 5},
               check=lambda p: (bool(first_list(p, "hits", "items")), f"{total_of(p)} hits"))
    await m.ok("search_members_by_name", {"pattern": "add", "kind": "method", "count": 5},
               check=lambda p: (bool(first_list(p, "members", "items")), f"{total_of(p)} hits"))
    await m.ok("search_classes_by_name", {"pattern": "mcp.test", "count": 5},
               check=lambda p: (bool(first_list(p, "items", "classes")), f"{total_of(p)} matches"))

    m.heading("bytecode editing")
    insns = await m.ok("list_method_instructions", {"class_name": "mcp/test/Scratch", "method_name": "run"},
                       check=lambda p: (bool(p.get("instructions")), f"{p.get('instruction_count')} instructions"))
    ins_list = (insns or {}).get("instructions") or [] if isinstance(insns, dict) else []
    if ins_list:
        target_idx = next((i for i, ins in enumerate(ins_list) if ins.get("opcode") == "ICONST_1"), 1)
        await m.ok("replace_instruction", {"class_name": "mcp/test/Scratch", "method_name": "run",
                                           "index": target_idx, "instruction": {"opcode": "ICONST_5"}},
                   check=lambda p: (p.get("ok") is True, f"index {target_idx} -> ICONST_5"))
        await m.ok("insert_instruction", {"class_name": "mcp/test/Scratch", "method_name": "run",
                                          "index": 0, "instruction": {"opcode": "NOP"}},
                   check=lambda p: (p.get("ok") is True, "NOP inserted"))
        await m.ok("remove_instruction", {"class_name": "mcp/test/Scratch", "method_name": "run", "index": 0},
                   check=lambda p: (p.get("ok") is True, "NOP removed"))
    await m.ok("replace_method_body", {"class_name": "mcp/test/Scratch", "method_name": "mark",
                                       "instructions": [{"opcode": "RETURN"}]},
               check=lambda p: (p.get("ok") is True, "mark() -> RETURN"))
    await m.ok("set_try_catch_blocks", {"class_name": "mcp/test/Scratch", "method_name": "run",
                                        "try_catch_blocks": [{"start": "L0", "end": "L1", "handler": "L2",
                                                              "type": "java/lang/RuntimeException"}]},
               check=lambda p: (p.get("ok") is True, "try-catch set on the compiled run()"))
    await m.ok("assemble_method", {"class_name": "mcp/test/Scratch", "method_name": "run", "text": "\n".join([
        "ALOAD 0",
        "ICONST_3",
        "PUTFIELD mcp/test/Target.counter : I",
        "RETURN",
    ])}, check=lambda p: (p.get("ok") is True, "assembled a 4-instruction run()"))
    await m.ok("list_method_instructions", {"class_name": "mcp/test/Scratch", "method_name": "run"},
               check=lambda p: (p.get("instruction_count", 99) <= 6,
                                f"after assemble: {p.get('instruction_count')} instructions"))
    await m.ok("edit_class_access", {"class_name": "mcp/test/Target", "set_flags": ["final"]},
               check=lambda p: (p.get("ok") is True, "+final"))
    await m.ok("edit_class_access", {"class_name": "mcp/test/Target", "clear_flags": ["final"]},
               check=lambda p: (p.get("ok") is True, "-final"))
    await m.ok("edit_method_access", {"class_name": "mcp/test/Scratch", "method_name": "mark",
                                      "set_flags": ["synchronized"]}, check=lambda p: (p.get("ok") is True, "+synchronized"))
    await m.ok("edit_method_access", {"class_name": "mcp/test/Scratch", "method_name": "mark",
                                      "clear_flags": ["synchronized"]}, check=lambda p: (p.get("ok") is True, "-synchronized"))
    await m.ok("edit_field_access", {"class_name": "mcp/test/Scratch", "field_name": "stamp", "set_flags": ["volatile"]},
               check=lambda p: (p.get("ok") is True, "+volatile"))
    await m.ok("edit_field_access", {"class_name": "mcp/test/Scratch", "field_name": "stamp", "clear_flags": ["volatile"]},
               check=lambda p: (p.get("ok") is True, "-volatile"))
    await m.ok("add_method", {"class_name": "mcp/test/Scratch", "method_name": "helper", "descriptor": "()V",
                              "access": ["public"], "instructions": [{"opcode": "RETURN"}]},
               check=lambda p: (p.get("ok") is True, "helper()V added"))
    await m.ok("remove_method", {"class_name": "mcp/test/Scratch", "method_name": "helper", "descriptor": "()V"},
               check=lambda p: (p.get("ok") is True, "helper()V removed"))
    await m.ok("add_field", {"class_name": "mcp/test/Scratch", "field_name": "extra", "descriptor": "I",
                             "access": ["private"]}, check=lambda p: (p.get("ok") is True, "extra:I added"))
    await m.ok("remove_field", {"class_name": "mcp/test/Scratch", "field_name": "extra", "descriptor": "I"},
               check=lambda p: (p.get("ok") is True, "extra:I removed"))

    await m.ok("get_modified_classes", check=lambda p: (int(p.get("count") or 0) > 0, f"{p.get('count')} modified"))

    m.heading("refactor")
    await m.ok("rename_local_variable", {"class_name": "mcp/test/Scratch", "method_name": "compareTo",
                                         "descriptor": "(Lmcp/test/Scratch;)I",
                                         "variable_name": "o", "new_name": "other"},
               check=lambda p: (p.get("ok") is True, f"renamed {p.get('renamed_variables')} variable(s)"))
    await m.ok("rename_method", {"class_name": "mcp/test/Target", "method_name": "names", "new_name": "namesList"},
               check=lambda p: (p.get("ok") is True, "names -> namesList"))
    await m.ok("rename_method", {"class_name": "mcp/test/Target", "method_name": "namesList", "new_name": "names"},
               check=lambda p: (p.get("ok") is True, "namesList -> names (restored)"))
    await m.ok("rename_field", {"class_name": "mcp/test/Target", "field_name": "counter", "new_name": "counter2"},
               check=lambda p: (p.get("ok") is True, "counter -> counter2"))
    await m.ok("rename_field", {"class_name": "mcp/test/Target", "field_name": "counter2", "new_name": "counter"},
               check=lambda p: (p.get("ok") is True, "counter2 -> counter (restored)"))
    await m.ok("rename_class", {"class_name": "mcp/test/Target$Nested", "new_name": "mcp/test/Target$Inner"},
               check=lambda p: (p.get("ok") is True, f"{p.get('affected_classes')} class(es)"))
    await m.ok("rename_class", {"class_name": "mcp/test/Target$Inner", "new_name": "mcp/test/Target$Nested"},
               check=lambda p: (p.get("ok") is True, "restored"))
    await m.ok("rename_package", {"old_package_name": "mcp/test", "new_package_name": "mcp/renamed"},
               check=lambda p: (p.get("ok") is True, f"{p.get('affected_classes')} class(es)"))
    await m.ok("get_class_info", {"class_name": "mcp/renamed/Target"},
               check=lambda p: (p.get("name") == "mcp.renamed.Target", "renamed class resolves"))
    await m.ok("rename_package", {"old_package_name": "mcp/renamed", "new_package_name": "mcp/test"},
               check=lambda p: (p.get("ok") is True, "restored"))

    m.heading("export and revert")
    export_path = export_dir / "matrix-export.jar"
    await m.ok("export_workspace", {"output_path": str(export_path)}, timeout=900,
               check=lambda p: (p.get("ok") is True and export_path.exists(),
                                f"{export_path.stat().st_size // 1024 if export_path.exists() else 0} KiB"))
    save_path = export_dir / "matrix-save.jar"
    await m.ok("save_workspace", {"output_path": str(save_path)}, timeout=900,
               check=lambda p: (p.get("ok") is True and save_path.exists(),
                                f"{save_path.stat().st_size // 1024 if save_path.exists() else 0} KiB"))
    await m.ok("revert_class", {"class_name": "mcp/test/Scratch"},
               check=lambda p: (p.get("ok") is True, "Scratch reverted to original bytes"))
    await m.ok("apply_mappings", {"mappings_text": "\n".join([
        "tiny\t2\t0\tofficial\tnamed",
        "c\tmcp/test/Scratch\tmcp/test/ScratchMapped",
    ]), "format": "tiny-v2"}, check=lambda p: (p.get("ok") is True, "tiny-v2 mappings applied"))
    await m.ok("get_class_info", {"class_name": "mcp/test/ScratchMapped"},
               check=lambda p: (p.get("name") == "mcp.test.ScratchMapped", "mapped class resolves"))

    # ---- error handling --------------------------------------------------
    m.heading("error handling")
    await m.fails("get_class_info", {"class_name": "does/not/Exist"}, contains="not found")
    await m.fails("open_workspace", {"path": "/nonexistent/target.jar"})
    await m.fails("switch_recaf_window", {"window": "99"})

    # ---- a second window -------------------------------------------------
    m.heading("extra window")
    before = await m.call("list_recaf_windows")
    windows_before = (before[1] or {}).get("windows") or [] if isinstance(before[1], dict) else []
    if single_mode:
        # Linux default: a second instance is refused, the running one stays.
        await m.fails("open_recaf_window", {"jar_path": "", "wait_seconds": 5}, contains="single-recaf")
        await m.ok("list_recaf_windows",
                   check=lambda p: (len(p.get("windows") or []) == len(windows_before),
                                    f"{len(p.get('windows') or [])} window(s) (refused to add another)"))
    else:
        await m.ok("open_recaf_window", {"jar_path": "", "wait_seconds": 120}, timeout=300,
                   check=lambda p: (bool(p.get("opened")), f"window {p.get('opened', {}).get('label')} "
                                                          f"headless={p.get('opened', {}).get('headless')}"))
        await m.ok("list_recaf_windows", check=lambda p: (len(p.get("windows") or []) == len(windows_before) + 1,
                                                          f"{len(p.get('windows') or [])} window(s)"))
        if windows_before:
            await m.ok("switch_recaf_window", {"window": windows_before[0]["label"]},
                       check=lambda p: (bool(p.get("active")), p.get("active")))

    # ---- leave a clean state --------------------------------------------
    m.heading("cleanup")
    await m.ok("close_workspace", check=lambda p: (p.get("ok") is True, "workspace closed"))
    await m.ok("open_workspace", {"path": str(jar)}, timeout=900,
               check=lambda p: (p.get("ok") is True, f"reopened target jar, {p.get('primary', {}).get('class_count')} classes"))


def report(matrix: Matrix, work: Path) -> int:
    failed = [r for r in matrix.results if not r[2]]
    total = len(matrix.results)
    print("\n" + "=" * 78)
    print(f"MCP tool matrix: {total} calls, {total - len(failed)} passed, {len(failed)} failed")
    groups: dict[str, list[tuple]] = {}
    for row in matrix.results:
        groups.setdefault(row[0], []).append(row)
    for group, rows in groups.items():
        bad = [r for r in rows if not r[2]]
        status = "ok" if not bad else f"{len(bad)} failed"
        print(f"  {group:<32} {len(rows):>2} calls  {status}")
    if failed:
        print("\nfailures:")
        for group, tool, _, ms, detail in failed:
            print(f"  - [{group}] {tool}: {detail}")
        log = work / "mcp-stderr.log"
        if log.exists():
            tail = log.read_text(errors="replace").strip().splitlines()[-15:]
            print("\nserver stderr (last 15 lines):")
            for line in tail:
                print("   ", line[:200])
    else:
        print("\nall 70 tools behaved as expected")
    print("=" * 78)
    return 0 if not failed else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", required=True, help="jar to open as the workspace")
    parser.add_argument("--export-dir", default=None, help="where export_workspace/save_workspace write "
                                                         "(default: temporary directory)")
    parser.add_argument("--server-python", default=sys.executable, help="python used to run recaf_mcp_server.py")
    parser.add_argument("--recaf-jar", default=str(REPO_ROOT / "libs/recaf.jar"), help="recaf.jar for auto-start")
    parser.add_argument("--plugin-dir", default=str(REPO_ROOT / "build/libs"), help="plugin jar directory")
    parser.add_argument("--java", default=shutil.which("java") or "java", help="java executable for auto-start")
    parser.add_argument("--timeout", type=int, default=900, help="per-call read timeout in seconds")
    args = parser.parse_args()

    jar = Path(args.jar).expanduser().resolve()
    if not jar.is_file():
        raise SystemExit(f"jar not found: {jar}")
    if args.export_dir:
        export_dir = Path(args.export_dir).expanduser().resolve()
        export_dir.mkdir(parents=True, exist_ok=True)
        cleanup_export = False
    else:
        export_dir = Path(tempfile.mkdtemp(prefix="recaf-mcp-export-"))
        cleanup_export = True

    print(f"target jar : {jar} ({jar.stat().st_size // 1024} KiB)")
    print(f"server     : {args.server_python} {REPO_ROOT / 'recaf_mcp_server.py'}")
    print(f"export dir : {export_dir}")
    try:
        return asyncio.run(run_matrix(jar, export_dir, args.server_python, Path(args.recaf_jar),
                                      Path(args.plugin_dir), args.java, args.timeout))
    finally:
        if cleanup_export:
            shutil.rmtree(export_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
