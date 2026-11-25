"""
Unit tests for the low-overhead behaviour of the resident Python side.

The server sits idle between tool calls, so what matters is (a) a tool call must
not build an HTTP client or probe the port range again, and (b) the idle watchdog
must not wake up to look at the clock.

    python -m unittest discover -s mcp-server/tests -v
"""

import asyncio
import sys
import time
import unittest
from pathlib import Path
from unittest import mock

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from src.server import config, windows  # noqa: E402

WINDOW = {
    "instance_id": "low-1",
    "pid": 4321,
    "port": 8750,
    "base": "http://127.0.0.1:8750",
    "has_workspace": False,
    "headless": True,
    "window": 1,
    "label": "1:(no workspace)",
}


class _Reset(unittest.TestCase):
    def setUp(self):
        windows._cache = []
        windows._cache_time = 0.0
        windows._active_window = None
        windows._owned_windows.clear()
        windows._owned_procs.clear()
        windows.idle_stop_seconds = 0.0
        windows._last_activity = 0.0
        windows._idle_thread = None
        windows._idle_wakeup.clear()
        self.addCleanup(windows._owned_windows.clear)
        self.addCleanup(windows._owned_procs.clear)
        self.addCleanup(setattr, windows, "_cache", [])
        self.addCleanup(setattr, windows, "_cache_time", 0.0)
        self.addCleanup(setattr, windows, "_active_window", None)
        self.addCleanup(setattr, windows, "idle_stop_seconds", 0.0)
        self.addCleanup(setattr, windows, "_idle_thread", None)
        self.addCleanup(windows._idle_wakeup.clear)


class HttpClientReuseTest(unittest.TestCase):
    def tearDown(self):
        asyncio.run(config.aclose())

    def test_client_is_created_once_per_loop(self):
        async def check():
            first = config._http_client()
            second = config._http_client()
            self.assertIs(first, second)
            self.assertFalse(first.is_closed)

        asyncio.run(check())

    def test_client_is_rebuilt_after_close_and_loop_change(self):
        async def get_client():
            return config._http_client()

        first = asyncio.run(get_client())
        second = asyncio.run(get_client())
        self.assertIsNot(first, second)  # a client belongs to the loop that made it

        async def reopen():
            client = config._http_client()
            await config.aclose()
            self.assertTrue(client.is_closed)
            fresh = config._http_client()
            self.assertIsNot(fresh, client)
            await config.aclose()

        asyncio.run(reopen())


class ResolveCacheTest(_Reset):
    def test_fresh_cache_is_used_without_probing(self):
        windows._cache = [WINDOW]
        windows._cache_time = time.monotonic()
        calls = []

        async def fake_discover():
            calls.append(1)
            return [WINDOW]

        with mock.patch.object(windows, "discover", fake_discover):
            window = asyncio.run(windows.resolve())
        self.assertEqual(window["instance_id"], "low-1")
        self.assertEqual(calls, [])

    def test_stale_cache_is_probed_again(self):
        windows._cache = [WINDOW]
        windows._cache_time = time.monotonic() - windows.OPTIMISTIC_TTL_SECONDS - 1
        calls = []

        async def fake_discover():
            calls.append(1)
            return [WINDOW]

        with mock.patch.object(windows, "discover", fake_discover):
            window = asyncio.run(windows.resolve())
        self.assertEqual(window["instance_id"], "low-1")
        self.assertEqual(calls, [1])

    def test_invalidated_cache_is_probed_again(self):
        windows._cache = [WINDOW]
        windows._cache_time = 0.0
        windows.invalidate()
        calls = []

        async def fake_discover():
            calls.append(1)
            return [WINDOW]

        with mock.patch.object(windows, "discover", fake_discover):
            asyncio.run(windows.resolve())
        self.assertEqual(calls, [1])

    def test_unknown_window_spec_falls_back_to_a_probe(self):
        windows._cache = [WINDOW]
        windows._cache_time = time.monotonic()
        calls = []

        async def fake_discover():
            calls.append(1)
            return [WINDOW]

        with mock.patch.object(windows, "discover", fake_discover):
            with self.assertRaises(windows.WindowError):
                asyncio.run(windows.resolve("7"))
        self.assertEqual(calls, [1])


class IdleWatchdogTest(_Reset):
    def test_touch_wakes_the_watchdog(self):
        windows._idle_wakeup.clear()
        windows._touch()
        self.assertTrue(windows._idle_wakeup.is_set())

    def test_loop_stops_the_process_once_the_deadline_passes(self):
        windows.idle_stop_seconds = 0.05
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._last_activity = time.monotonic() - 1
        seen = []
        with mock.patch.object(windows, "_terminate_pid",
                              lambda pid, wait=8.0: seen.append(pid) or True):
            windows._idle_loop()  # returns as soon as nothing is owned any more
        self.assertEqual(seen, [4321])
        self.assertEqual(windows.owned_windows(), {})

    def test_loop_does_not_spin_after_a_failed_stop(self):
        windows.idle_stop_seconds = 0.05
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._last_activity = time.monotonic() - 1
        attempts = []
        with mock.patch.object(windows, "_terminate_pid",
                              lambda pid, wait=8.0: attempts.append(pid) or False):
            thread = __import__("threading").Thread(target=windows._idle_loop, daemon=True)
            thread.start()
            time.sleep(0.4)
            self.assertIn(4321, windows.owned_windows())
            self.assertEqual(len(attempts), 1)  # backed off instead of hammering it
            windows._owned_windows.clear()
            windows._idle_wakeup.set()
            thread.join(timeout=2)

    def test_loop_returns_at_once_when_idle_stop_is_off(self):
        windows.idle_stop_seconds = 0
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._idle_loop()


if __name__ == "__main__":
    unittest.main()
