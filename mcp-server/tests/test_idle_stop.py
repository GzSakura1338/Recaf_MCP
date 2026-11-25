"""
Unit tests for the idle lifecycle: only the Python process stays resident.

With an idle timeout the server stops the Recaf processes it started, keeps the
user's own windows alone, and cleans up when it exits.

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

from src.server import windows  # noqa: E402

WINDOW = {
    "instance_id": "idle-1",
    "pid": 4321,
    "port": 8750,
    "base": "http://127.0.0.1:8750",
    "has_workspace": False,
    "headless": True,
    "window": 1,
    "label": "1:(no workspace)",
}


class IdleStopTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        windows._owned_windows.clear()
        windows.idle_stop_seconds = 0.0
        windows._last_activity = 0.0
        windows._idle_task = None
        self.addCleanup(windows._owned_windows.clear)
        self.addCleanup(setattr, windows, "idle_stop_seconds", 0.0)
        self.addCleanup(setattr, windows, "_last_activity", 0.0)
        self.addCleanup(setattr, windows, "_idle_task", None)

    def test_configure_sets_idle_timeout(self):
        windows.configure_launch(idle_stop=45)
        self.assertEqual(windows.idle_stop_seconds, 45.0)
        windows.configure_launch(idle_stop=-5)
        self.assertEqual(windows.idle_stop_seconds, 0.0)

    def test_remember_owned_tracks_the_pid(self):
        windows._remember_owned(WINDOW)
        self.assertEqual(windows.owned_windows(), {4321: "1:(no workspace)"})
        self.assertGreater(windows._last_activity, 0.0)

    async def test_stop_owned_windows_terminates_only_owned_pids(self):
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._owned_windows[9876] = "2:(no workspace)"
        seen = []

        def fake_terminate(pid, wait=8.0):
            seen.append(pid)
            return True

        with mock.patch.object(windows, "_terminate_pid", fake_terminate):
            stopped = await windows.stop_owned_windows()
        self.assertEqual(sorted(stopped), [4321, 9876])
        self.assertEqual(sorted(seen), [4321, 9876])
        self.assertEqual(windows.owned_windows(), {})

    async def test_failed_terminate_keeps_the_pid_tracked(self):
        windows._owned_windows[4321] = "1:(no workspace)"
        with mock.patch.object(windows, "_terminate_pid", return_value=False):
            stopped = await windows.stop_owned_windows()
        self.assertEqual(stopped, [])
        self.assertIn(4321, windows.owned_windows())

    async def test_watchdog_stops_after_idle_timeout(self):
        windows.idle_stop_seconds = 30
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._last_activity = time.monotonic() - 31
        terminated = []
        with mock.patch.object(windows, "IDLE_CHECK_INTERVAL", 0.01), \
                mock.patch.object(windows, "_terminate_pid", lambda pid, wait=8.0: terminated.append(pid) or True):
            await windows._idle_loop()
        self.assertEqual(terminated, [4321])
        self.assertEqual(windows.owned_windows(), {})

    async def test_watchdog_keeps_a_busy_window(self):
        windows.idle_stop_seconds = 30
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._last_activity = time.monotonic()
        with mock.patch.object(windows, "IDLE_CHECK_INTERVAL", 0.01), \
                mock.patch.object(windows, "_terminate_pid", side_effect=AssertionError("must not stop")):
            task = asyncio.create_task(windows._idle_loop())
            await asyncio.sleep(0.05)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        self.assertIn(4321, windows.owned_windows())

    async def test_watchdog_is_started_only_when_enabled(self):
        windows.idle_stop_seconds = 0
        windows._remember_owned(WINDOW)
        self.assertIsNone(windows._idle_task)

        windows.idle_stop_seconds = 60
        windows._remember_owned(WINDOW)
        self.assertIsNotNone(windows._idle_task)
        windows._idle_task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await windows._idle_task

    async def test_exit_hook_stops_owned_windows(self):
        windows._owned_windows[4321] = "1:(no workspace)"
        terminated = []
        with mock.patch.object(windows, "_terminate_pid",
                               lambda pid, wait=8.0: terminated.append(pid) or True):
            windows._stop_owned_on_exit()
        self.assertEqual(terminated, [4321])
        self.assertEqual(windows.owned_windows(), {})


if __name__ == "__main__":
    unittest.main()
