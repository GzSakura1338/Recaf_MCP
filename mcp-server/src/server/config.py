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
from typing import Union, Dict, Any

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


def health_ping() -> Union[str, Dict[str, Any]]:
    """Synchronous startup check: list the Recaf windows that answer."""
    try:
        found = asyncio.run(windows.discover())
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
            async with httpx.AsyncClient(trust_env=False) as client:
                resp = await client.request(method, url, timeout=timeout, **kwargs)
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
