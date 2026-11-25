# DSH bundle: Recaf MCP over stdio

A configuration-only DSH bundle. It inserts `@deepseek-ai/dsh-mcp-client` with a
stdio entry, so DSH spawns `recaf_mcp_server.py` itself and that server starts a
headless Recaf when no window is running.

The MCP server exposes its tools as `mcp__recaf__<tool>`, for example
`mcp__recaf__get_class_source`, `mcp__recaf__search_strings`,
`mcp__recaf__rename_class`.

## Install

```bash
dsh plugin install /path/to/this/directory      # or Plugins > Add plugin in the GUI
```

Installing through `plugin_manager` (`action: install_bundle`, `target` = this
directory) does the same without a shell.

## Adjust the paths

`cordis.patch.yml` points at a specific checkout: the `.venv` python, the MCP
server entry point, `libs/recaf.jar`, `build/libs` (the plugin jar directory) and
`/usr/local/bin/java`. Change them to your clone before installing:

| Argument | Meaning |
|---|---|
| `command` / `args[0]` | interpreter and `recaf_mcp_server.py` |
| `--headless` | Recaf runs without a JavaFX UI (no X server needed) |
| `--auto-start-recaf` | start Recaf on the first tool call when none is running |
| `--recaf-jar` | the Recaf 4 release the plugin was built against |
| `--recaf-plugins-dir` | directory holding `recaf-mcp-plugin-*.jar` |
| `--java` | JDK 25 executable |

`startupTimeoutMs: 60000` covers the stdio probe plus tool sync;
`toolCallTimeoutMs: 180000` covers the first call, which also boots Recaf and can
import a large jar.

## Behaviour

- Nothing is spawned until the agent actually calls a Recaf tool: the MCP server
  starts immediately (stdio), Recaf starts on the first tool call that needs a
  window.
- The Recaf process is detached and keeps running after DSH exits, so the next
  session (or another agent) reuses the same window.
- One MCP server entry manages every Recaf window; `list_recaf_windows` /
  `switch_recaf_window` / `open_recaf_window` still apply.
- Turning the bundle off removes the tools; Recaf may keep running until stopped
  with `scripts/linux/start_recaf_headless.sh stop`.
