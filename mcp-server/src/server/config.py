"""
Recaf MCP Server - Configuration Module

Manages HTTP client setup and communication with the Recaf 4.0.0 plugin.
Each Recaf process runs its own plugin HTTP server (first free port from
127.0.0.1:8750); src/server/windows.py discovers them and picks the one a call
is meant for.
"""

import asyncio
import logging
import httpx
import json
import sys
from typing import Optional, Union, Dict, Any

from . import windows

# Default Configuration — Recaf plugin is expected on 8750 by convention
# (jadx-mcp-server uses 8650, we shift up to avoid collision when both run).
RECAF_HOST = "127.0.0.1"
RECAF_PORT = 8750

logger = logging.getLogger("recaf-mcp-server")
if not logger.handlers:
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(message)s'))
    logger.addHandler(handler)
logger.setLevel(logging.ERROR)
logger.propagate = False

# One keep-alive HTTP client, reused for every tool call. Creating a client costs
# real CPU (httpx builds and fills an SSL context: ~18ms per call, even though
# this talks plain http:// to localhost), and a fresh client also means a fresh
# TCP connection per call. The client is bound to the event loop that made it, so
# it is rebuilt when the loop changes (tests, MCP host restarts a session).
_http: Optional[httpx.AsyncClient] = None
_http_loop: Any = None
HTTP_LIMITS = httpx.Limits(max_connections=8, max_keepalive_connections=4)


def _http_client() -> httpx.AsyncClient:
    """The shared client for the running event loop (created on first use)."""
    global _http, _http_loop
    loop = asyncio.get_running_loop()
    if _http is not None and not _http.is_closed and _http_loop is loop:
        return _http
    _http = httpx.AsyncClient(trust_env=False, limits=HTTP_LIMITS)
    _http_loop = loop
    return _http


async def aclose() -> None:
    """Close the shared client (used by tests and the startup health check)."""
    global _http, _http_loop
    client, _http = _http, None
    _http_loop = None
    if client is not None and not client.is_closed:
        await client.aclose()


def set_recaf_host(host: str):
    """Update the Recaf plugin host."""
    global RECAF_HOST
    RECAF_HOST = host
    windows.configure(RECAF_HOST, RECAF_PORT, windows.skip_ports)


def set_recaf_port(port: int):
    """Update the first Recaf plugin port to scan."""
    global RECAF_PORT
    RECAF_PORT = port
    windows.configure(RECAF_HOST, RECAF_PORT, windows.skip_ports)


def set_mcp_port(port: int):
    """Never probe the port this MCP server itself listens on."""
    windows.configure(RECAF_HOST, RECAF_PORT, windows.skip_ports | {port})


async def _health_ping() -> list:
    try:
        return await windows.discover()
    finally:
        # asyncio.run() below owns (and then discards) this event loop.
        await windows.aclose()


def health_ping() -> Union[str, Dict[str, Any]]:
    """Synchronous startup check: list the Recaf windows that answer."""
    try:
        found = asyncio.run(_health_ping())
    except Exception as e:
        logger.error(f"Health check failed: {e}")
        return {"error": str(e)}
    if not found:
        return {"error": "no Recaf window found"}
    return "ok: " + ", ".join(w["label"] for w in found)


async def _request(method: str, endpoint: str, timeout: int, **kwargs) -> Union[str, Dict[str, Any]]:
    """
    Send one request to the Recaf window this call is aimed at.

    JSON is parsed when possible; otherwise the raw text is returned as
    {"response": "..."}. Failures surface as {"error": "..."}.
    """
    for attempt in range(2):
        try:
            base = await windows.resolve_base()
        except windows.WindowError as e:
            return e.payload()

        url = f"{base}/{endpoint.lstrip('/')}"
        try:
            resp = await _http_client().request(method, url, timeout=timeout, **kwargs)
            resp.raise_for_status()
            try:
                return resp.json()
            except json.JSONDecodeError:
                return {"response": resp.text}

        except httpx.HTTPStatusError as e:
            error_msg = f"HTTP error {e.response.status_code}: {e.response.text}"
            logger.error(error_msg)
            return {"error": error_msg}

        except (httpx.ConnectError, httpx.ConnectTimeout) as e:
            # Nothing was sent, so retrying is safe. The window may simply have
            # closed since it was last discovered: rediscover and try again.
            windows.invalidate()
            if attempt == 0:
                continue
            error_msg = f"Recaf window at {base} is unreachable: {str(e)}"
            logger.error(error_msg)
            return {"error": error_msg}

        except Exception as e:
            error_msg = f"Unexpected error: {str(e)}"
            logger.error(error_msg)
            return {"error": error_msg}


async def get_from_recaf(endpoint: str, params: Dict[str, Any] = None) -> Union[str, Dict[str, Any]]:
    """GET against the selected Recaf window."""
    return await _request("GET", endpoint, 60, params=params or {})


async def post_to_recaf(endpoint: str, payload: Dict[str, Any] = None) -> Union[str, Dict[str, Any]]:
    """
    POST JSON to the selected Recaf window.

    Used for mutation endpoints (rename, apply-mappings, export) where passing
    large bodies as query params would be awkward.
    """
    return await _request("POST", endpoint, 120, json=payload or {})
