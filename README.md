<div align="center">

# Recaf MCP

> *Turn Recaf 4 into an API that LLMs can call*

[![Java](https://img.shields.io/badge/Java-25-ED8106)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.10+-3776AB)](https://www.python.org/)
[![Recaf](https://img.shields.io/badge/Recaf-4.x-2D9CDB)](https://www.coley.software/recaf4/)
[![MCP](https://img.shields.io/badge/MCP-stdio%20%7C%20HTTP-8B5CF6)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-Apache--2.0-3DA639)](LICENSE)
[![QQ Group](https://img.shields.io/badge/QQ%20Group-Join-0099FF?logo=tencent-qq&logoColor=white)](https://qm.qq.com/q/KWcs2UBtYK)

[中文](README_CN.md) · [Headless guide](docs/linux-headless.md) · [Publishing checklist](PUBLISHING.md)

</div>

---

## What this is

**Recaf MCP** exposes [Recaf 4](https://github.com/Col-E/Recaf) as an MCP server with **70 tools**:
list classes, decompile methods, search strings and instructions, follow cross-references,
rename in bulk, export a jar — or read and write bytecode directly (instruction-level edits,
JASM assembly, access flags, method/field CRUD, try-catch blocks, class byte replacement).

```text
MCP client (DSH / Claude Code / IDE)
        │  stdio (host spawns the process)  or streamable HTTP
        ▼
Python MCP server  ──HTTP──▶  Recaf plugin (inside the Recaf process)
  127.0.0.1:8751                 127.0.0.1:8750
        │                             │
        └── auto-start ──────────────▶ recaf --headless --idle
```

- The **plugin** is a JDK-only HTTP server inside Recaf that exposes workspace, search,
  decompilation and ASM editing.
- The **MCP server** maps that HTTP API to MCP tools and discovers, numbers and switches
  between Recaf windows.
- **Recaf 4 ships a native headless mode** (`--headless --idle`), so Linux servers, containers
  and CI need no X server and no Xvfb — only this plugin.

## Features

| Feature | Details |
|---|---|
| 70 tools | workspace, classes, methods, search, xrefs, renames, decompilers, inheritance, resources, export, bytecode editing |
| Native headless | `recaf --headless --idle`, no `DISPLAY`; UI-bound tools degrade cleanly |
| Auto-start | the MCP server starts a headless Recaf when no window is running (`--auto-start-recaf`) |
| Idle recycling | only Python stays resident: a Recaf this server started is stopped after N idle seconds (`--recaf-idle-stop`) and started again on the next call |
| Single instance | one Recaf at a time by default on Linux: `open_recaf_window` refuses a second one (`--multi-recaf` lifts that) |
| Multiple windows | one server drives several Recaf processes, addressed as `<number>:<jar name>` |
| Three entry points | stdio (host-owned), streamable HTTP (long-running service), ready-made DSH bundle |
| Scripts for both platforms | Windows PowerShell and Linux bash: start, stop, status, install, self test |
| Tested | 70-tool matrix (110 calls), Python unit tests, headless smoke tests, auto-start test |

## Requirements

- **JDK 25** (Recaf 4 and the plugin target)
- **Python 3.10+** (`fastmcp>=3.0.2`, `httpx`, `requests`)
- **`recaf.jar`**: `libs/recaf.jar` is tracked in this repo (Recaf 4.0.0-alpha, linux-aarch build).
  On another platform, download the matching jar from
  [Recaf Releases](https://github.com/Col-E/Recaf/releases) and replace it, or point `RECAF_JAR` at yours.

| Scenario | Needs a display | Entry point |
|---|---|---|
| Linux headless (server / container / CI) | No | `scripts/linux/install.sh` |
| Windows or Linux desktop GUI | Yes | `gradlew jar` + Recaf plugin directory |
| Host-launched MCP server | No | stdio config / `contrib/dsh-bundle` |

## Quick start

### A. Linux headless (shortest path)

```bash
# 1) build the plugin, install it into ~/.config/Recaf/plugins, create .venv
./scripts/linux/install.sh

# 2) start headless Recaf (optionally opening a jar)
./scripts/linux/start_recaf_headless.sh start --input /path/to/target.jar

# 3) start the MCP server
./scripts/linux/start_mcp_http.sh start
```

```bash
./scripts/linux/start_recaf_headless.sh status   # status
./scripts/linux/start_mcp_http.sh status
./scripts/linux/start_recaf_headless.sh stop     # stop
```

Logs land in `logs/`, pid files in `.run/`. Full option tables, systemd units, a Dockerfile and
troubleshooting live in **[docs/linux-headless.md](docs/linux-headless.md)**.

### B. Windows / desktop GUI

```powershell
# 1) build the plugin (uses the tracked libs/recaf.jar, or point RECAF_JAR elsewhere)
.\gradlew.bat jar
# 2) copy the jar into %APPDATA%\Recaf\plugins\ and start Recaf
# 3) install Python dependencies and start the MCP server
pip install -r requirements.txt
.\start_recaf_mcp_http.ps1
```

On successful load Recaf logs:

```text
Recaf MCP plugin listening on http://127.0.0.1:8750
```

### C. Let the MCP host start everything (stdio / DSH)

The host spawns the MCP server, and the server spawns Recaf — one config does both:

```json
{
  "mcpServers": {
    "recaf": {
      "type": "stdio",
      "command": "/path/to/Recaf_MCP/.venv/bin/python",
      "args": [
        "/path/to/Recaf_MCP/recaf_mcp_server.py",
        "--headless", "--auto-start-recaf",
        "--recaf-jar", "/path/to/Recaf_MCP/libs/recaf.jar",
        "--recaf-plugins-dir", "/path/to/Recaf_MCP/build/libs"
      ]
    }
  }
}
```

DSH users can install the bundled configuration-only plugin:

```bash
dsh plugin install "/path/to/Recaf_MCP/contrib/dsh-bundle"
# or Web UI: Plugins → Add plugin → pick that directory
```

Tools appear as `mcp__recaf__get_class_source` and friends. The first call takes a few seconds
extra while Recaf boots and imports the jar.

### MCP client config (HTTP)

```json
{
  "mcpServers": {
    "recaf-mcp": {
      "type": "http",
      "url": "http://127.0.0.1:8751/mcp"
    }
  }
}
```

## Tool overview (70)

| Group | Count | Tools |
|---|---|---|
| Windows | 3 | `list_recaf_windows` (reports `single_recaf`) `switch_recaf_window` `open_recaf_window` (refused in single mode) |
| Workspace | 7 | `get_workspace_info` `open_workspace` `close_workspace` `add_supporting_resource` `list_supporting_resources` `fetch_current_class` `get_selected_text` |
| Classes | 9 | `get_all_classes` `get_class_info` `get_class_source` `get_bytecode_of_class` `get_methods_of_class` `get_fields_of_class` `get_inner_classes` `get_annotations_of_class` `get_raw_class_bytes` |
| Methods | 3 | `get_method_by_name` `get_method_bytecode` `get_method_info` |
| Search | 5 | `search_classes_by_name` `search_members_by_name` `search_strings` `search_numbers` `search_instructions` |
| Cross references | 6 | `get_xrefs_to_class` `get_xrefs_to_method` `get_xrefs_to_field` `get_callers_of_method` `get_callees_of_method` `get_overrides_of_method` |
| Renames | 6 | `rename_class` `rename_method` `rename_field` `rename_package` `rename_local_variable` `apply_mappings` |
| Decompilers | 3 | `list_decompilers` `set_active_decompiler` `decompile_class_with` |
| Inheritance | 5 | `get_superclasses` `get_interfaces` `get_direct_subclasses` `get_all_subclasses` `get_implementors` |
| Resources | 4 | `get_all_file_names` `get_file_content` `get_manifest` `get_strings_from_resources` |
| Export | 3 | `export_workspace` `get_modified_classes` `revert_class` |
| Bytecode editing | 16 | `list_method_instructions` `replace_instruction` `insert_instruction` `remove_instruction` `assemble_method` `replace_method_body` `edit_class_access` `edit_method_access` `edit_field_access` `add_method` `remove_method` `add_field` `remove_field` `set_try_catch_blocks` `replace_class_bytes` `save_workspace` |

A typical session:

```text
get_all_classes → get_class_source → get_method_bytecode
  → replace_method_body / assemble_method → get_modified_classes
  → export_workspace (or save_workspace) → revert_class (to undo)
```

## Headless mode

With no display, everything runs on Recaf's native headless mode:

- Launch: `recaf.jar --headless --idle -r <plugin dir> -d <data dir> [-i <jar>]`
- The plugin serves its HTTP API as usual; `/instance` reports `headless: true`, `ui_available: false`
- `fetch_current_class` and `get_selected_text` need the editor UI: they answer
  `ui_available: false` with a reason instead of failing, and every other tool works normally
- `--auto-start-recaf`: when a tool call finds no window at all, the server starts one
  (headless when there is no display), waits for `/health`, then runs the call. A failed attempt
  is not retried for 60 s
- `--recaf-idle-stop N`: that process is stopped after N seconds without a Recaf tool call, and
  when the server exits, so **only the Python process stays resident**; the next call starts a
  fresh Recaf (a few seconds). Only processes started by this server are touched — a Recaf you
  launched yourself (GUI or scripts) is never stopped. Ownership is recorded in
  `recaf-mcp-owned.json` (with an `owner` pid), so a server reloaded by the host adopts what its
  predecessor left behind, while processes owned by a *still running* server are left alone

Option tables, systemd units, Dockerfile, ports and troubleshooting:
**[docs/linux-headless.md](docs/linux-headless.md)** (Chinese; the commands are language-neutral).

## Single instance and multiple windows

**Linux runs one Recaf at a time by default**: with a window already up, `open_recaf_window`
returns an error (reuse it or stop it), and auto-start only ever fires when nothing is running.
A second JVM only costs a few hundred MB of heap. Opt out when you really need several:

```bash
python recaf_mcp_server.py --http --multi-recaf   # or RECAF_SINGLE=0
```

`scripts/linux/start_recaf_headless.sh` follows the same rule: if a plugin already answers on
ports 8750–8769 it refuses to start another Recaf.

Outside single mode (or with `--multi-recaf`) the multi-window model below still applies:

Several Recaf processes can run at once (GUI or headless); each loads the plugin and binds the
first free port from `8750`.

| Tool | Purpose |
|---|---|
| `list_recaf_windows` | Lists windows as `1:client.jar`, `2:server.jar`, … with the default window and a `headless` flag |
| `switch_recaf_window(window)` | Makes a window the default for calls that omit `window` |
| `open_recaf_window(jar_path?, wait_seconds?)` | Starts another Recaf (same mode as `--headless/--gui` or the open window), optionally opening a jar |

Every other tool accepts an optional `window`: `"2"`, `"2:client.jar"`, or a unique part of the
jar name. A stale reference is rejected instead of editing the wrong jar.

## Resident cost

While idle it does one thing: wait for MCP requests. Measured on 8-core ARM Linux over loopback
(`scripts/bench_python_overhead.py`):

| Metric | Value | Why |
|---|---|---|
| Idle CPU | 0.0–0.3% | The watchdog never polls: one `threading.Event` wait until the idle deadline |
| Resident memory (RSS) | ~36 MB | One HTTP client for the whole session instead of one per call |
| CPU per tool call | ~0.8 ms | Was 30 ms (a fresh `httpx.AsyncClient` per call: ~18 ms just to build the TLS context) |
| Latency per tool call | ~0.8 ms | Keep-alive reuses the connection, no handshake per call |
| Port probes per call | 0 | Resolution is cached for 30 s; a failed request invalidates and retries |
| Listing windows (`list_recaf_windows`) | 0.002 ms | A status query answers from the 3 s cache; one cold scan costs ~29 ms |

How:

- Every tool call shares one keep-alive `httpx.AsyncClient` (rebuilt only when the event loop changes); port discovery reuses one too.
- Window resolution is optimistic: the last window list is reused for 30 s without touching the port range, and only a failed request triggers `invalidate()` + retry — so it can never hit the wrong window.
- Listing windows does not force a rescan, and a single probe may burn 0.75 s instead of 2 s, so a stalled foreign service in the port range cannot slow a scan down.
- Idle recycling is event-driven: a tool call `set()`s the event to recompute the deadline, no call means one long wait; a failed stop backs off 30 s instead of spinning.
- Reproduce it: `python scripts/bench_python_overhead.py` (a fake plugin answers `/instance`, `/health`, `/class-info`; the script reports CPU, memory and connections per call, the window-list cost, and idle CPU).

## Configuration reference

### MCP server flags

| Flag | Default | Meaning |
|---|---|---|
| `--http` | off | Serve MCP over streamable HTTP; otherwise stdio |
| `--host` / `--port` | `127.0.0.1` / `8751` | MCP listen address and port (non-localhost has no auth, see [Security](#security)) |
| `--recaf-host` / `--recaf-port` | `127.0.0.1` / `8750` | Plugin address and scan start (20 ports) |
| `--recaf-jar` | `$RECAF_JAR`, `libs/recaf.jar` | recaf.jar used by `open_recaf_window` and auto-start |
| `--headless` / `--gui` | auto | Mode for new processes; default is headless on Linux without `DISPLAY` |
| `--recaf-plugins-dir` | `$RECAF_PLUGIN_DIR`, `build/libs` | `-r` passed to headless Recaf |
| `--recaf-data-dir` | `$RECAF_DATA_DIR` | `-d` passed to headless Recaf |
| `--java` / `--java-args` | `$RECAF_JAVA` / `$RECAF_JAVA_ARGS` | Java binary and extra JVM flags |
| `--auto-start-recaf` | off | Start Recaf when no window exists (`--no-auto-start-recaf` forces it off) |
| `--auto-start-wait` | `90` | Seconds to wait for the plugin of an auto-started Recaf |
| `--single-recaf` / `--multi-recaf` | single on Linux | Whether several Recaf instances may run; Linux defaults to one |
| `--recaf-idle-stop` | `0` (never) | Stop Recaf processes this server started after N seconds without a Recaf tool call; `0` keeps them |
| `--recaf-launch-log` | `$RECAF_LAUNCH_LOG` | Append new-process output to a file (handy for headless debugging) |

### Environment variables

| Variable | Effect |
|---|---|
| `RECAF_JAR` | recaf.jar path (overrides `libs/recaf.jar`) |
| `RECAF_PLUGIN_DIR` / `RECAF_PLUGIN_INSTALL_DIR` | plugin directory / install target |
| `RECAF_DATA_DIR`, `RECAF_JAVA`, `RECAF_JVM_ARGS` | data dir, java binary, JVM flags for headless instances |
| `RECAF_MCP_HOST` / `RECAF_MCP_PORT` | plugin's own listen address / base port (read by the plugin) |
| `RECAF_HOST` / `RECAF_PORT` | plugin address / base port as seen by the MCP server |
| `RECAF_HEADLESS`, `RECAF_AUTO_START`, `RECAF_AUTO_START_WAIT` | headless and auto-start switches |
| `RECAF_IDLE_STOP` | idle-stop seconds (same as `--recaf-idle-stop`) |
| `RECAF_SINGLE` | `1`/`0` overrides the single-instance default |
| `RECAF_MCP_HTTP_HOST` / `RECAF_MCP_HTTP_PORT` | MCP listen address / port used by the Linux scripts |

### Ports

| Component | Address |
|---|---|
| Plugin HTTP | `127.0.0.1:8750` for the first Recaf, then the next free port up to `8769`, skipping `8751` |
| MCP HTTP | `127.0.0.1:8751/mcp` |

Health checks:

```bash
curl http://127.0.0.1:8750/health      # ok
curl http://127.0.0.1:8750/instance    # pid / port / workspace / headless flag
```

## Testing

| Command | What it does |
|---|---|
| `./test.sh` | build + Python entry point + script syntax + unit tests |
| `./test.sh --headless` | also runs the headless end-to-end smoke test |
| `./scripts/linux/smoke_test.sh --with-mcp` | throw-away headless Recaf: `/health`, `/instance`, open a jar, decompile, headless degradation, then calls tools through an MCP client |
| `./scripts/linux/smoke_autostart.sh` | zero windows → first tool call starts Recaf and opens a workspace |
| `./scripts/linux/smoke_idle_stop.sh` | Python only: on-demand start, idle recycling, restart on the next call, cleanup on exit |
| `.venv/bin/python scripts/mcp_tool_matrix.py --jar <jar>` | **full matrix**: stdio server, every one of the 70 tools (110 calls), one PASS/FAIL line each |

Example matrix run (`Nyx.jar`, a 75 MB Fabric mod):

```text
MCP tool matrix: 110 calls, 110 passed, 0 failed
  windows / workspace / target jar (read-only) / search / xrefs+inheritance
  decompilers / scratch workspace / bytecode editing / refactor / export
all 70 tools behaved as expected
```

## Known limitations

1. `get_modified_classes` reports 0 after `rename_package` / `apply_mappings`, although the classes
   differ from the original jar: Recaf's mapping applier rebuilds the bundle and clears its dirty history.
2. `assemble_method` with `LABEL` / `TRYCATCH` text directives produces invalid frames
   (ASM `NegativeArraySizeException`). Label-free bodies work; for try-catch use
   `set_try_catch_blocks` on a method that already has label nodes.
3. `search_strings` only scans `LDC` string constants — `invokedynamic` concat recipes are not hits.
4. In headless mode `fetch_current_class` / `get_selected_text` have no data; that is expected.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Missing recaf.jar` | `libs/recaf.jar` is tracked; for another platform point `RECAF_JAR` at the matching jar |
| `plugin did not answer within 90s` | check `logs/recaf-headless.log`; usually heap (`--jvm-args -Xmx4g`) or a jar built for another architecture |
| `no Recaf window found` | Recaf is not running or the plugin is not loaded; `curl 127.0.0.1:8750/health` should return `ok`, or use `--auto-start-recaf` |
| `current-class` returns `ui_available: false` | expected in headless mode; pass `class_name` explicitly |
| `Address already in use` | ports 8750–8769 are exhausted; stop extra instances |
| MCP client shows no tools | check the `--http`/stdio config and the `mcpServers` entry; in DSH the tools appear in a new session |

## Security

The MCP server and the plugin speak **plain HTTP without authentication**:

```bash
python recaf_mcp_server.py --http --host 0.0.0.0   # ⚠️ exposes it to the network
```

The default binds to `127.0.0.1`. Do not bind to other interfaces unless it sits behind a
firewall or VPN.

## Repository layout

```text
.
├── src/                      # Java plugin (HTTP routes + ASM bytecode editing)
├── mcp-server/               # Python MCP server (tool definitions + window discovery)
├── scripts/
│   ├── linux/                # headless launchers, installer, smoke tests
│   ├── mcp_tool_matrix.py    # full tool matrix (70 tools, 109 calls)
│   └── bench_python_overhead.py  # resident-overhead benchmark
├── contrib/
│   ├── dsh-bundle/           # DSH configuration-only bundle (stdio + auto-start)
│   └── systemd/              # systemd user unit templates
├── docs/linux-headless.md    # headless guide
├── libs/recaf.jar            # pinned build dependency
├── recaf_mcp_server.py       # MCP server entry point
├── requirements.txt
├── gradlew / build.gradle.kts
└── README.md / README_CN.md
```

## Development

```bash
./gradlew jar                                  # build the plugin (needs libs/recaf.jar)
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
./test.sh --headless                           # build + unit tests + headless end-to-end
```

- The plugin targets Java 25 (`build.gradle.kts` toolchain) with the Gradle 9.4.1 wrapper.
- Unit tests live in `mcp-server/tests/` and cover headless launch flags plus auto-start decisions.
- Release steps: [PUBLISHING.md](PUBLISHING.md).

## License and credits

[Apache-2.0](LICENSE).

This is a Recaf adaptation of [jadx-mcp-server](https://github.com/zinja-coder/jadx-mcp-server)
by Jafar Pathan / zinja-coder, keeping its MCP architecture and tool naming. Recaf itself is by
[Col-E](https://github.com/Col-E).

<div align="center">
<sub>LLM client → MCP server → Recaf plugin → Recaf workspace</sub>
</div>
