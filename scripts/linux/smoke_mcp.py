#!/usr/bin/env python3
"""
Smoke-test the MCP server from an MCP client's point of view.

Connects to a running Recaf MCP HTTP server (default http://127.0.0.1:8751/mcp)
and calls the tools a headless analysis session needs:

    list_recaf_windows, get_workspace_info, get_all_classes,
    get_class_source, get_selected_text (must degrade, not fail)

Exit code 0 means the whole chain works: MCP client -> MCP server -> plugin ->
headless Recaf. Used by scripts/linux/smoke_test.sh --with-mcp, but can be run
on its own.
"""

import argparse
import asyncio
import json
import sys

from fastmcp import Client


async def call(client, name, arguments=None):
    """Call a tool and return its structured payload (or parsed text)."""
    result = await client.call_tool(name, arguments or {})
    data = getattr(result, "data", None)
    if data is None:
        data = getattr(result, "structuredContent", None)
    if data is None:
        text = "\n".join(
            getattr(block, "text", "") for block in (getattr(result, "content", None) or [])
        ).strip()
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            data = text
    return data


def check(condition, message):
    if not condition:
        raise SystemExit(f"FAIL: {message}")
    print(f"ok: {message}")


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8751/mcp")
    parser.add_argument("--expect-classes", type=int, default=3)
    args = parser.parse_args()

    async with Client(args.url) as client:
        tools = await client.list_tools()
        names = {tool.name for tool in tools}
        check("get_workspace_info" in names, f"MCP server exposes {len(names)} tools")

        windows = await call(client, "list_recaf_windows")
        check(isinstance(windows, dict) and windows.get("windows"),
              f"list_recaf_windows -> {json.dumps(windows)[:200]}")

        info = await call(client, "get_workspace_info")
        check(isinstance(info, dict) and info.get("has_workspace") is True,
              f"get_workspace_info sees a workspace ({json.dumps(info)[:160]}...)")

        listing = await call(client, "get_all_classes", {"offset": 0, "count": args.expect_classes})
        classes = None
        if isinstance(listing, dict):
            # Pagination envelope: {"type": "paginated-list", "items": [...], ...}
            classes = listing.get("items") or listing.get("classes")
        check(bool(classes), f"get_all_classes returned {len(classes or [])} classes")

        class_name = classes[0]["name"]
        source = await call(client, "get_class_source", {"class_name": class_name})
        text = (source or {}).get("text") if isinstance(source, dict) else None
        check(bool(text and text.strip()), f"get_class_source({class_name}) -> {len(text or '')} chars")

        selection = await call(client, "get_selected_text")
        check(isinstance(selection, dict) and selection.get("ui_available") is False,
              "get_selected_text degrades in headless mode "
              f"({(selection or {}).get('reason', '')[:80]}...)")

    print("PASS: MCP tools work against headless Recaf")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
