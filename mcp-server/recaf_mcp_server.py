#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [ "fastmcp>=3.0.2", "httpx" ]
# ///

"""
Recaf MCP Server — reverse engineering MCP for Recaf 4.0.0.

Mirrors the jadx-mcp-server architecture: this Python process hosts the
MCP tools and forwards each call as HTTP to the Recaf plugin running
inside the Recaf JavaFX process.

Default ports:
    Recaf plugin HTTP ..... 8750
    Recaf MCP (streamable). 8751
"""

import argparse
import functools
import inspect
import logging
import sys
from typing import Annotated
from fastmcp import FastMCP
from pydantic import Field
from src.banner import recaf_mcp_server_banner
from src.server import config, tools, windows

INSTRUCTIONS = (
    "Several Recaf windows (processes) can be open at once, each with its own jar. "
    "Call list_recaf_windows to see them as '<number>:<jar name>' (e.g. '2:client.jar'); "
    "open_recaf_window starts another one (optionally with a jar). "
    "Every other tool takes an optional `window` argument ('2' or '2:client.jar') to pick the "
    "window for that single call. switch_recaf_window sets a default that is shared by every "
    "client of this server, so when several agents work in parallel always pass `window` "
    "explicitly. With exactly one window open, `window` can be omitted. Tools that modify a "
    "workspace act only on the selected window."
)

mcp = FastMCP("Recaf MCP Plugin Reverse Engineering Server", instructions=INSTRUCTIONS)

WINDOW_DESCRIPTION = (
    "Recaf window to use: '<number>' or '<number>:<jar name>' as shown by list_recaf_windows. "
    "Omit to use the default set by switch_recaf_window (or the only open window)."
)


def recaf_tool(fn):
    """Register `fn` as an MCP tool and give it an optional `window` argument."""
    signature = inspect.signature(fn)
    window_type = Annotated[str, Field(description=WINDOW_DESCRIPTION)]
    window_param = inspect.Parameter(
        "window", inspect.Parameter.KEYWORD_ONLY, default="", annotation=window_type
    )

    @functools.wraps(fn)
    async def wrapper(*args, window: str = "", **kwargs):
        token = windows.requested_window.set(window or "")
        try:
            return await fn(*args, **kwargs)
        finally:
            windows.requested_window.reset(token)

    wrapper.__signature__ = signature.replace(
        parameters=[*signature.parameters.values(), window_param]
    )
    wrapper.__annotations__ = {**inspect.get_annotations(fn), "window": window_type}
    return mcp.tool()(wrapper)

# Bootstrap logger — always writes to stderr to keep stdout clean for stdio transport
logger = logging.getLogger("recaf-mcp-server.bootstrap")
if not logger.handlers:
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(handler)
logger.setLevel(logging.INFO)
logger.propagate = False


# Import tool modules so FastMCP decorators below can forward into them.
# The bare `import` form registers the submodule as an attribute of the
# `tools` package, matching the jadx-mcp-server style.
from src.server.tools import (
    workspace_tools,
    class_tools,
    method_tools,
    search_tools,
    xrefs_tools,
    refactor_tools,
    decompile_tools,
    inheritance_tools,
    resource_tools,
    export_tools,
    bytecode_edit_tools,
)


# =============================================================================
# Recaf windows
# =============================================================================

@mcp.tool()
async def list_recaf_windows() -> dict:
    """List every open Recaf window as '<number>:<jar name>' and which one is the default."""
    try:
        return await windows.list_windows()
    except windows.WindowError as e:
        return e.payload()


@mcp.tool()
async def switch_recaf_window(window: str) -> dict:
    """Make a Recaf window the default for calls without `window`. `window` is '2' or '2:client.jar'."""
    try:
        return await windows.switch(window)
    except windows.WindowError as e:
        return e.payload()


@mcp.tool()
async def open_recaf_window(jar_path: str = "", wait_seconds: int = 60) -> dict:
    """
    Start a new Recaf window (a separate Recaf process) and optionally open a jar in it.

    Returns the new window as '<number>:<jar name>'; pass that as `window` to the other tools.
    The default window is not changed. Waits up to `wait_seconds` for Recaf to finish loading.
    """
    return await tools.workspace_tools.open_recaf_window(jar_path, wait_seconds)


# =============================================================================
# Workspace
# =============================================================================

@recaf_tool
async def get_workspace_info() -> dict:
    """Return metadata about the currently loaded Recaf workspace."""
    return await tools.workspace_tools.get_workspace_info()


@recaf_tool
async def open_workspace(path: str) -> dict:
    """Load a JAR/class/directory into Recaf as the primary workspace resource."""
    return await tools.workspace_tools.open_workspace(path)


@recaf_tool
async def close_workspace() -> dict:
    """Close the current workspace without exporting."""
    return await tools.workspace_tools.close_workspace()


@recaf_tool
async def add_supporting_resource(path: str) -> dict:
    """Attach a supporting library JAR to the workspace for inheritance/xref resolution."""
    return await tools.workspace_tools.add_supporting_resource(path)


@recaf_tool
async def list_supporting_resources() -> dict:
    """List all supporting resources (library JARs) attached to the workspace."""
    return await tools.workspace_tools.list_supporting_resources()


@recaf_tool
async def fetch_current_class() -> dict:
    """Fetch the class currently focused in the Recaf UI (active editor tab)."""
    return await tools.workspace_tools.fetch_current_class()


@recaf_tool
async def get_selected_text() -> dict:
    """Return the text currently selected in the active Recaf editor."""
    return await tools.workspace_tools.get_selected_text()


# =============================================================================
# Classes
# =============================================================================

@recaf_tool
async def get_all_classes(offset: int = 0, count: int = 0) -> dict:
    """Paginated listing of every class in the primary resource."""
    return await tools.class_tools.get_all_classes(offset, count)


@recaf_tool
async def get_class_info(class_name: str) -> dict:
    """Basic metadata for a class: access flags, supertypes, counts, annotations."""
    return await tools.class_tools.get_class_info(class_name)


@recaf_tool
async def get_class_source(class_name: str, decompiler: str = "") -> dict:
    """Decompile a class to Java source. Optional decompiler id overrides the default."""
    return await tools.class_tools.get_class_source(class_name, decompiler)


@recaf_tool
async def get_bytecode_of_class(class_name: str) -> dict:
    """Disassembled bytecode view of a class (Recaf's equivalent of smali)."""
    return await tools.class_tools.get_bytecode_of_class(class_name)


@recaf_tool
async def get_methods_of_class(class_name: str) -> dict:
    """List every method on a class with name, JVM descriptor, and access flags."""
    return await tools.class_tools.get_methods_of_class(class_name)


@recaf_tool
async def get_fields_of_class(class_name: str) -> dict:
    """List every field on a class with name, JVM descriptor, and access flags."""
    return await tools.class_tools.get_fields_of_class(class_name)


@recaf_tool
async def get_inner_classes(class_name: str) -> dict:
    """List inner/nested class names declared within the given class."""
    return await tools.class_tools.get_inner_classes(class_name)


@recaf_tool
async def get_annotations_of_class(class_name: str) -> dict:
    """All annotations (visible and invisible) attached to the class."""
    return await tools.class_tools.get_annotations_of_class(class_name)


@recaf_tool
async def get_raw_class_bytes(class_name: str) -> dict:
    """Base64-encoded raw class file bytes for external tooling."""
    return await tools.class_tools.get_raw_class_bytes(class_name)


# =============================================================================
# Methods
# =============================================================================

@recaf_tool
async def get_method_by_name(class_name: str, method_name: str, descriptor: str = "") -> dict:
    """Decompiled source of a single method (optionally disambiguated by descriptor)."""
    return await tools.method_tools.get_method_by_name(class_name, method_name, descriptor)


@recaf_tool
async def get_method_bytecode(class_name: str, method_name: str, descriptor: str = "") -> dict:
    """Disassembled bytecode for a single method (instructions, locals, try/catch)."""
    return await tools.method_tools.get_method_bytecode(class_name, method_name, descriptor)


@recaf_tool
async def get_method_info(class_name: str, method_name: str, descriptor: str = "") -> dict:
    """Method metadata: access, signature, exceptions, locals, line numbers."""
    return await tools.method_tools.get_method_info(class_name, method_name, descriptor)


# =============================================================================
# Search
# =============================================================================

@recaf_tool
async def search_classes_by_name(
    pattern: str,
    use_regex: bool = False,
    case_sensitive: bool = True,
    offset: int = 0,
    count: int = 50,
) -> dict:
    """Find classes whose fully qualified name matches the given pattern."""
    return await tools.search_tools.search_classes_by_name(
        pattern, use_regex, case_sensitive, offset, count
    )


@recaf_tool
async def search_members_by_name(
    pattern: str,
    kind: str = "any",
    use_regex: bool = False,
    case_sensitive: bool = True,
    offset: int = 0,
    count: int = 50,
) -> dict:
    """Find methods and/or fields by name. `kind` is "method", "field", or "any"."""
    return await tools.search_tools.search_members_by_name(
        pattern, kind, use_regex, case_sensitive, offset, count
    )


@recaf_tool
async def search_strings(
    pattern: str,
    use_regex: bool = False,
    case_sensitive: bool = True,
    offset: int = 0,
    count: int = 50,
) -> dict:
    """Search string constants in code (LDC / constant pool)."""
    return await tools.search_tools.search_strings(
        pattern, use_regex, case_sensitive, offset, count
    )


@recaf_tool
async def search_numbers(value: str, offset: int = 0, count: int = 50) -> dict:
    """Search numeric constants (int/long/float/double) appearing in bytecode."""
    return await tools.search_tools.search_numbers(value, offset, count)


@recaf_tool
async def search_instructions(
    opcode: str = "",
    operand: str = "",
    class_filter: str = "",
    offset: int = 0,
    count: int = 50,
) -> dict:
    """Bytecode-level search by opcode mnemonic and/or operand substring."""
    return await tools.search_tools.search_instructions(
        opcode, operand, class_filter, offset, count
    )


# =============================================================================
# Cross-references
# =============================================================================

@recaf_tool
async def get_xrefs_to_class(class_name: str, offset: int = 0, count: int = 20) -> dict:
    """Find every location that references a class."""
    return await tools.xrefs_tools.get_xrefs_to_class(class_name, offset, count)


@recaf_tool
async def get_xrefs_to_method(
    class_name: str,
    method_name: str,
    descriptor: str = "",
    offset: int = 0,
    count: int = 20,
) -> dict:
    """Find every call site that invokes a method."""
    return await tools.xrefs_tools.get_xrefs_to_method(
        class_name, method_name, descriptor, offset, count
    )


@recaf_tool
async def get_xrefs_to_field(
    class_name: str,
    field_name: str,
    descriptor: str = "",
    offset: int = 0,
    count: int = 20,
) -> dict:
    """Find every read/write site for a field."""
    return await tools.xrefs_tools.get_xrefs_to_field(
        class_name, field_name, descriptor, offset, count
    )


@recaf_tool
async def get_callers_of_method(
    class_name: str,
    method_name: str,
    descriptor: str = "",
    offset: int = 0,
    count: int = 20,
) -> dict:
    """Alias for get_xrefs_to_method — call sites that invoke the method."""
    return await tools.xrefs_tools.get_callers_of_method(
        class_name, method_name, descriptor, offset, count
    )


@recaf_tool
async def get_callees_of_method(
    class_name: str,
    method_name: str,
    descriptor: str = "",
    offset: int = 0,
    count: int = 50,
) -> dict:
    """Methods invoked by the body of the given method (forward call edges)."""
    return await tools.xrefs_tools.get_callees_of_method(
        class_name, method_name, descriptor, offset, count
    )


@recaf_tool
async def get_overrides_of_method(
    class_name: str,
    method_name: str,
    descriptor: str = "",
    offset: int = 0,
    count: int = 50,
) -> dict:
    """All methods that override or implement the given virtual/interface method."""
    return await tools.xrefs_tools.get_overrides_of_method(
        class_name, method_name, descriptor, offset, count
    )


# =============================================================================
# Refactor
# =============================================================================

@recaf_tool
async def rename_class(class_name: str, new_name: str) -> dict:
    """Rename a class across the entire workspace."""
    return await tools.refactor_tools.rename_class(class_name, new_name)


@recaf_tool
async def rename_method(
    class_name: str,
    method_name: str,
    new_name: str,
    descriptor: str = "",
    cascade_overrides: bool = True,
) -> dict:
    """Rename a method. Cascades to overrides/implementations by default."""
    return await tools.refactor_tools.rename_method(
        class_name, method_name, new_name, descriptor, cascade_overrides
    )


@recaf_tool
async def rename_field(
    class_name: str,
    field_name: str,
    new_name: str,
    descriptor: str = "",
) -> dict:
    """Rename a field and update every read/write site."""
    return await tools.refactor_tools.rename_field(
        class_name, field_name, new_name, descriptor
    )


@recaf_tool
async def rename_package(old_package_name: str, new_package_name: str) -> dict:
    """Rename a package prefix and move every class under it."""
    return await tools.refactor_tools.rename_package(old_package_name, new_package_name)


@recaf_tool
async def rename_local_variable(
    class_name: str,
    method_name: str,
    variable_name: str,
    new_name: str,
    descriptor: str = "",
    index: int = -1,
) -> dict:
    """Rename a local variable in a method's LocalVariableTable (cosmetic)."""
    return await tools.refactor_tools.rename_local_variable(
        class_name, method_name, variable_name, new_name, descriptor, index
    )


@recaf_tool
async def apply_mappings(mappings_text: str, format: str = "tiny-v2") -> dict:
    """Bulk-apply a mapping file (Proguard/SRG/TSRG/Tiny v1-v2/Enigma)."""
    return await tools.refactor_tools.apply_mappings(mappings_text, format)


# =============================================================================
# Decompilers
# =============================================================================

@recaf_tool
async def list_decompilers() -> dict:
    """List decompilers registered in Recaf's DecompilerManager."""
    return await tools.decompile_tools.list_decompilers()


@recaf_tool
async def set_active_decompiler(name: str) -> dict:
    """Set the workspace-wide default decompiler (cfr / vineflower / procyon)."""
    return await tools.decompile_tools.set_active_decompiler(name)


@recaf_tool
async def decompile_class_with(class_name: str, decompiler: str) -> dict:
    """One-shot decompile with an explicit decompiler; workspace default unchanged."""
    return await tools.decompile_tools.decompile_class_with(class_name, decompiler)


# =============================================================================
# Inheritance
# =============================================================================

@recaf_tool
async def get_superclasses(class_name: str) -> dict:
    """Walk from the class up to java.lang.Object."""
    return await tools.inheritance_tools.get_superclasses(class_name)


@recaf_tool
async def get_interfaces(class_name: str) -> dict:
    """All interfaces implemented by the class (direct + transitive)."""
    return await tools.inheritance_tools.get_interfaces(class_name)


@recaf_tool
async def get_direct_subclasses(class_name: str, offset: int = 0, count: int = 50) -> dict:
    """Classes that directly extend the given class (one level down)."""
    return await tools.inheritance_tools.get_direct_subclasses(class_name, offset, count)


@recaf_tool
async def get_all_subclasses(class_name: str, offset: int = 0, count: int = 200) -> dict:
    """All transitive descendants of the given class."""
    return await tools.inheritance_tools.get_all_subclasses(class_name, offset, count)


@recaf_tool
async def get_implementors(interface_name: str, offset: int = 0, count: int = 200) -> dict:
    """All classes that implement the given interface."""
    return await tools.inheritance_tools.get_implementors(interface_name, offset, count)


# =============================================================================
# File resources (non-class files in the jar)
# =============================================================================

@recaf_tool
async def get_all_file_names(offset: int = 0, count: int = 0) -> dict:
    """Paginated list of every non-class file path in the primary resource."""
    return await tools.resource_tools.get_all_file_names(offset, count)


@recaf_tool
async def get_file_content(file_path: str) -> dict:
    """Retrieve the content of a file inside the primary resource."""
    return await tools.resource_tools.get_file_content(file_path)


@recaf_tool
async def get_manifest() -> dict:
    """Parsed META-INF/MANIFEST.MF (main attributes + per-entry attributes)."""
    return await tools.resource_tools.get_manifest()


@recaf_tool
async def get_strings_from_resources(offset: int = 0, count: int = 0) -> dict:
    """Extract printable strings from non-class binary resources."""
    return await tools.resource_tools.get_strings_from_resources(offset, count)


# =============================================================================
# Export
# =============================================================================

@recaf_tool
async def export_workspace(output_path: str, include_supporting: bool = False) -> dict:
    """Write the (possibly modified) primary resource back to disk."""
    return await tools.export_tools.export_workspace(output_path, include_supporting)


@recaf_tool
async def get_modified_classes() -> dict:
    """List classes that have been modified relative to their original bytes."""
    return await tools.export_tools.get_modified_classes()


@recaf_tool
async def revert_class(class_name: str) -> dict:
    """Discard in-memory modifications to a class and restore the original bytes."""
    return await tools.export_tools.revert_class(class_name)


# =============================================================================
# Bytecode Edit
# =============================================================================

@recaf_tool
async def list_method_instructions(
    class_name: str, method_name: str, descriptor: str = ""
) -> dict:
    """Return every instruction in a method as a flat JSON array with indices and opcodes."""
    return await tools.bytecode_edit_tools.list_method_instructions(
        class_name, method_name, descriptor
    )


@recaf_tool
async def replace_method_body(
    class_name: str,
    method_name: str,
    instructions: list,
    descriptor: str = "",
) -> dict:
    """
    Replace the entire body of a method.

    `instructions` is a list of dicts: {"opcode": "ALOAD", "args": [0]}, etc.
    Jump targets are integer indices. Labels/frames are auto-generated.
    """
    return await tools.bytecode_edit_tools.replace_method_body(
        class_name, method_name, instructions, descriptor
    )


@recaf_tool
async def replace_instruction(
    class_name: str,
    method_name: str,
    index: int,
    instruction: dict,
    descriptor: str = "",
) -> dict:
    """Replace a single instruction at the given index within a method."""
    return await tools.bytecode_edit_tools.replace_instruction(
        class_name, method_name, index, instruction, descriptor
    )


@recaf_tool
async def insert_instruction(
    class_name: str,
    method_name: str,
    index: int,
    instruction: dict,
    descriptor: str = "",
) -> dict:
    """Insert a new instruction at the given index (use count to append)."""
    return await tools.bytecode_edit_tools.insert_instruction(
        class_name, method_name, index, instruction, descriptor
    )


@recaf_tool
async def remove_instruction(
    class_name: str,
    method_name: str,
    index: int,
    descriptor: str = "",
) -> dict:
    """Remove the instruction at the given index from a method."""
    return await tools.bytecode_edit_tools.remove_instruction(
        class_name, method_name, index, descriptor
    )


@recaf_tool
async def assemble_method(
    class_name: str,
    method_name: str,
    text: str,
    descriptor: str = "",
) -> dict:
    """
    Assemble a method body from JASM-like text.

    One instruction per line: ALOAD 0, RETURN, etc.
    Supports labels (GOTO L0), try-catch, line numbers. Comments start with # or //.
    """
    return await tools.bytecode_edit_tools.assemble_method(
        class_name, method_name, text, descriptor
    )


@recaf_tool
async def edit_class_access(
    class_name: str,
    set_flags: list = None,
    clear_flags: list = None,
) -> dict:
    """Set or clear access flags on a class (public, private, static, final, etc.)."""
    return await tools.bytecode_edit_tools.edit_class_access(
        class_name, set_flags, clear_flags
    )


@recaf_tool
async def edit_method_access(
    class_name: str,
    method_name: str,
    set_flags: list = None,
    clear_flags: list = None,
    descriptor: str = "",
) -> dict:
    """Set or clear access flags on a method."""
    return await tools.bytecode_edit_tools.edit_method_access(
        class_name, method_name, set_flags, clear_flags, descriptor
    )


@recaf_tool
async def edit_field_access(
    class_name: str,
    field_name: str,
    set_flags: list = None,
    clear_flags: list = None,
    descriptor: str = "",
) -> dict:
    """Set or clear access flags on a field."""
    return await tools.bytecode_edit_tools.edit_field_access(
        class_name, field_name, set_flags, clear_flags, descriptor
    )


@recaf_tool
async def replace_class_bytes(class_name: str, bytes_base64: str) -> dict:
    """Replace a class entirely from base64-encoded class file bytes."""
    return await tools.bytecode_edit_tools.replace_class_bytes(class_name, bytes_base64)


@recaf_tool
async def add_method(
    class_name: str,
    method_name: str,
    descriptor: str,
    access: list = None,
    instructions: list = None,
) -> dict:
    """Add a new method to a class with optional instruction body."""
    return await tools.bytecode_edit_tools.add_method(
        class_name, method_name, descriptor, access, instructions
    )


@recaf_tool
async def remove_method(
    class_name: str, method_name: str, descriptor: str = ""
) -> dict:
    """Remove a method from a class."""
    return await tools.bytecode_edit_tools.remove_method(
        class_name, method_name, descriptor
    )


@recaf_tool
async def add_field(
    class_name: str,
    field_name: str,
    descriptor: str,
    access: list = None,
    signature: str = None,
    value=None,
) -> dict:
    """Add a new field to a class."""
    return await tools.bytecode_edit_tools.add_field(
        class_name, field_name, descriptor, access, signature, value
    )


@recaf_tool
async def remove_field(
    class_name: str, field_name: str, descriptor: str = ""
) -> dict:
    """Remove a field from a class."""
    return await tools.bytecode_edit_tools.remove_field(
        class_name, field_name, descriptor
    )


@recaf_tool
async def set_try_catch_blocks(
    class_name: str,
    method_name: str,
    try_catch_blocks: list,
    descriptor: str = "",
) -> dict:
    """
    Set try-catch blocks for a method.

    Each block: {"start": "L0", "end": "L1", "handler": "L2", "type": "java/lang/Exception"}
    """
    return await tools.bytecode_edit_tools.set_try_catch_blocks(
        class_name, method_name, try_catch_blocks, descriptor
    )


@recaf_tool
async def save_workspace(output_path: str) -> dict:
    """Export the (possibly modified) workspace back to disk."""
    return await tools.bytecode_edit_tools.save_workspace(output_path)


# =============================================================================
# Entrypoint
# =============================================================================

def main():
    parser = argparse.ArgumentParser("MCP Server for Recaf")
    parser.add_argument(
        "--http",
        help="Serve MCP Server over HTTP stream.",
        action="store_true",
        default=False,
    )
    parser.add_argument(
        "--host",
        help="Host address to bind for --http (default: 127.0.0.1, use 0.0.0.0 for remote access). "
             "WARNING: non-localhost binds expose the server over plain HTTP with no authentication.",
        default="127.0.0.1",
        type=str,
    )
    parser.add_argument(
        "--port",
        help="Port for --http (default: 8751)",
        default=8751,
        type=int,
    )
    parser.add_argument(
        "--recaf-port",
        help="First Recaf MCP plugin port to scan (default: 8750). Every Recaf window "
             "binds the first free port from there, so %d ports are scanned." % windows.PORT_SPAN,
        default=8750,
        type=int,
    )
    parser.add_argument(
        "--recaf-jar",
        help="recaf.jar used by open_recaf_window. Without it, a new window is started the same "
             "way as an already open one (same java and jar), then RECAF_JAR, libs/recaf.jar.",
        default=None,
        type=str,
    )
    parser.add_argument(
        "--recaf-host",
        help="Recaf MCP plugin host (default: 127.0.0.1). "
             "Security: non-localhost may expose the plugin to the network.",
        default="127.0.0.1",
        type=str,
    )
    args = parser.parse_args()

    config.set_recaf_host(args.recaf_host)
    config.set_recaf_port(args.recaf_port)
    config.set_mcp_port(args.port)
    windows.set_launch_jar(args.recaf_jar)

    if args.host not in ("127.0.0.1", "localhost", "::1"):
        logger.warning(
            "\n⚠️  SECURITY WARNING: Binding to non-localhost address '%s'.\n"
            "   The MCP server uses plain HTTP with NO authentication.\n"
            "   Anyone on the network can connect and use all MCP tools.\n"
            "   Only use this on trusted networks or behind a firewall.",
            args.host,
        )

    try:
        logger.info(recaf_mcp_server_banner())
    except Exception:
        logger.info(
            "[Recaf MCP Server] v0.1.0 | MCP Port: %s | Recaf Host: %s | Recaf Port: %s",
            args.port,
            args.recaf_host,
            args.recaf_port,
        )

    logger.info("Looking for Recaf windows...")
    result = config.health_ping()
    logger.info("Health check result: %s", result)

    if args.http:
        mcp.run(transport="streamable-http", host=args.host, port=args.port)
    else:
        mcp.run()


if __name__ == "__main__":
    main()
