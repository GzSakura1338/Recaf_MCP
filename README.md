<div align="center">

# Recaf MCP

> *「"Turn Recaf into an API that LLMs can call"」*

[![Java](https://img.shields.io/badge/Java-25-ED8106)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.10+-3776AB)](https://www.python.org/)
[![Recaf](https://img.shields.io/badge/Recaf-4.x-2D9CDB)](https://www.coley.software/recaf4/)
[![MCP](https://img.shields.io/badge/MCP-HTTP-8B5CF6)](https://modelcontextprotocol.io/)
[![QQ Group](https://img.shields.io/badge/QQ%20Group-Join-0099FF?logo=tencent-qq&logoColor=white)](https://qm.qq.com/q/KWcs2UBtYK)

<br>

[中文版](README_CN.md)

<br>

**Recaf 4 MCP bridge — operate a Recaf workspace via MCP protocol for JVM bytecode analysis and editing.**

<br>

A Java plugin runs inside Recaf exposing a local HTTP API; a Python MCP server turns that API into MCP tools consumable by LLM clients.

<br>

[Tools](#tools) · [Quick Start](#quick-start) · [Bytecode Editing](#bytecode-editing) · [Install](#install-plugin)

</div>

---

## Tools

Every tool below also accepts an optional `window` argument; see [Multiple Recaf Windows](#multiple-recaf-windows).

### Recaf Windows
`list_recaf_windows` `switch_recaf_window` `open_recaf_window`

### Workspace
`get_workspace_info` `open_workspace` `close_workspace` `add_supporting_resource` `list_supporting_resources` `fetch_current_class` `get_selected_text`

### Classes
`get_all_classes` `get_class_info` `get_class_source` `get_bytecode_of_class` `get_methods_of_class` `get_fields_of_class` `get_inner_classes` `get_annotations_of_class` `get_raw_class_bytes`

### Methods
`get_method_by_name` `get_method_bytecode` `get_method_info`

### Search
`search_classes_by_name` `search_members_by_name` `search_strings` `search_numbers` `search_instructions`

### Cross References
`get_xrefs_to_class` `get_xrefs_to_method` `get_xrefs_to_field` `get_callers_of_method` `get_callees_of_method` `get_overrides_of_method`

### Refactor
`rename_class` `rename_method` `rename_field` `rename_package` `rename_local_variable` `apply_mappings`

### Decompilers
`list_decompilers` `set_active_decompiler` `decompile_class_with`

### Inheritance
`get_superclasses` `get_interfaces` `get_direct_subclasses` `get_all_subclasses` `get_implementors`

### Resources & Export
`get_all_file_names` `get_file_content` `get_manifest` `get_strings_from_resources` `export_workspace` `get_modified_classes` `revert_class`

---

## Bytecode Editing

Full bytecode read/write: instruction-level editing, JASM text assembly, access flag editing, method/field CRUD, try-catch blocks, class byte replacement, workspace export.

### Instructions
`list_method_instructions` `get_method_bytecode` `replace_instruction` `insert_instruction` `remove_instruction`

### Method Body
`assemble_method` `replace_method_body`

### Access Flags
`edit_class_access` `edit_method_access` `edit_field_access`

### Method / Field CRUD
`add_method` `remove_method` `add_field` `remove_field`

### Other
`set_try_catch_blocks` `replace_class_bytes` `save_workspace`

---

## Requirements

- Java 25
- Python 3.10+
- `recaf.jar`

On Linux no X server / Xvfb is needed: Recaf has a native `--headless --idle` mode and the
plugin keeps serving its HTTP API there. See [docs/linux-headless.md](docs/linux-headless.md).

## Quick Start

### 1. Provide recaf.jar

The build looks for Recaf in this order:

1. `RECAF_JAR` env var
2. `libs/recaf.jar`
3. `../recaf/recaf.jar`

```powershell
$env:RECAF_JAR="D:\deobf\recaf\recaf.jar"
```

### 2. Build the Plugin

```powershell
.\gradlew.bat jar
```

Output: `build/libs/recaf-mcp-plugin-0.1.0.jar`

### 3. Install Plugin

Copy the jar to Recaf's plugin cache directory `%APPDATA%/Recaf/plugins/` and launch Recaf.

On successful load:

```
Recaf MCP plugin listening on http://127.0.0.1:8750
```

### 4. Install Python Dependencies

```powershell
pip install -r requirements.txt
```

### 5. Start the MCP Server

```powershell
python recaf_mcp_server.py --http --port 8751
```

Or use the launcher script:

```powershell
.\start_recaf_mcp_http.ps1
```

---

## Linux & Headless

On Linux (servers, containers, CI, ssh sessions without a desktop) three scripts are enough:

```bash
# 1. build the plugin, install it into Recaf's plugin directory, create .venv
./scripts/linux/install.sh

# 2. start headless Recaf (--headless --idle: no DISPLAY needed), optionally with a target jar
./scripts/linux/start_recaf_headless.sh start --input /path/to/target.jar

# 3. start the MCP server: http://127.0.0.1:8751/mcp
./scripts/linux/start_mcp_http.sh start
```

Both launchers support `start|stop|status|restart` and `--foreground`; logs go to `logs/`,
pid files to `.run/`. systemd user unit templates live in `contrib/systemd/`.

In headless mode the UI-bound tools (`fetch_current_class`, `get_selected_text`) answer with
`ui_available: false` and a reason instead of failing; everything else (classes, methods,
search, xrefs, renames, export, bytecode editing) works normally. `list_recaf_windows` reports a
`headless` flag per window.

Instead of starting processes by hand, run the MCP server with `--auto-start-recaf`
(or `RECAF_AUTO_START=1`): the first tool call that needs a window starts headless Recaf itself.
DSH users can install the ready-made configuration-only bundle in
[contrib/dsh-bundle/](contrib/dsh-bundle/); its tools are named `mcp__recaf__get_class_source` etc.

End-to-end self test:

```bash
./scripts/linux/smoke_test.sh --with-mcp
```

It starts a throw-away headless Recaf and verifies the plugin HTTP API plus the MCP tool layer.
Full guide, option tables, a Dockerfile and a troubleshooting table: [docs/linux-headless.md](docs/linux-headless.md).

---

## MCP Config

```json
{
  "mcpServers": {
    "recaf-mcp": {
      "url": "http://127.0.0.1:8751/mcp"
    }
  }
}
```

## Ports

| Component | Address |
|---|---|
| Plugin HTTP | `127.0.0.1:8750` (first Recaf), then the next free port up to `8769`, skipping `8751` |
| MCP HTTP | `127.0.0.1:8751/mcp` |

Health check:

```bash
curl http://127.0.0.1:8750/health      # ok
curl http://127.0.0.1:8750/instance    # pid, port, workspace, headless flag
```

```powershell
Invoke-WebRequest http://127.0.0.1:8750/health
```

---

## Multiple Recaf Windows

Several Recaf processes can run at once, each with its own jar. Each one loads the plugin, which binds the first free port from `8750`; the MCP server finds them all and numbers them in the order it first sees them. A number is never reused while the MCP server runs, so `2:client.jar` can not silently turn into a different jar.

| Tool | Purpose |
|---|---|
| `list_recaf_windows` | List windows as `1:client.jar`, `2:server.jar`, … and show the default one |
| `switch_recaf_window(window)` | Make a window the default for calls that do not pass `window` |
| `open_recaf_window(jar_path?, wait_seconds?)` | Start another Recaf, optionally opening a jar in it. The default window is left unchanged |

Every other tool takes an optional `window`: `"2"`, `"2:client.jar"`, or a unique part of the jar name. If a jar name is given with the number it must match, so a stale reference is rejected instead of editing the wrong jar.

- With exactly one window open, `window` can be omitted.
- With several open and no default chosen, a call fails with the list of windows instead of guessing.
- The default set by `switch_recaf_window` is shared by every client of the MCP server (the HTTP transport has no per-client session). When several agents work in parallel, have each pass `window` explicitly.
- `open_recaf_window` starts the new Recaf the same way an open window was started (same `java` and jar, read from its process). With no window open it uses `--recaf-jar`, then `RECAF_JAR`, then `libs/recaf.jar`.
- On Linux without `DISPLAY` the new process is started headless (`--headless --idle`, no X server). Force either mode with `--headless` / `--gui`, `--recaf-plugins-dir` and `--recaf-data-dir`.

Windows that still run an older plugin without `/instance` are found too, but cannot be cloned by `open_recaf_window`.

---

## Repository Structure

```text
.
├── src/                  # Java plugin source
├── mcp-server/           # Python MCP server
├── scripts/linux/        # Linux headless launchers, installer, self test
├── contrib/systemd/      # systemd user unit templates
├── contrib/dsh-bundle/   # DSH configuration-only bundle (stdio, auto-start)
├── docs/linux-headless.md# headless guide
├── libs/                 # recaf.jar goes here
├── recaf_mcp_server.py   # MCP server entry point
├── requirements.txt      # Python dependencies
├── .mcp.json             # Project MCP config
└── README.md
```

---

## Security

Binding to all interfaces exposes the MCP server with no authentication:

```powershell
python recaf_mcp_server.py --http --host 0.0.0.0
```

Use localhost by default, or place it behind a firewall / VPN.

---

<p align="center">
  <em>LLM Client -> MCP Server -> Recaf Plugin -> Recaf Workspace</em>
</p>
