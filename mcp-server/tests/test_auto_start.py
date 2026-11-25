"""
Unit tests for the auto-start path: a tool call with no Recaf window launches one.

These fake the process launch (no Java, no network) and check the decision logic in
src/server/windows.py:

    python -m unittest discover -s mcp-server/tests -v
"""

import sys
import unittest
from pathlib import Path
from unittest import mock

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from src.server import windows  # noqa: E402

WINDOW = {
    "instance_id": "auto-1",
    "pid": 4242,
    "port": 8750,
    "base": "http://127.0.0.1:8750",
    "has_workspace": False,
    "jar_name": None,
    "jar_path": None,
    "headless": True,
    "window": 1,
    "label": "1:(no workspace)",
}


class AutoStartTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        windows.configure_launch(auto=False, auto_wait=5)
        self.addCleanup(windows.configure_launch, None, None, None, None, None, [], None, None, None)
        self.addCleanup(setattr, windows, "_auto_start_failed_at", 0.0)
        self.addCleanup(setattr, windows, "_auto_start_lock", None)
        self.addCleanup(setattr, windows, "headless", None)
        self.available = [WINDOW]

        async def fake_discover():
            return list(self.available)

        async def fake_launch(wait_seconds=60):
            self.launched.append(wait_seconds)
            self.available = [WINDOW] if self.launch_succeeds else []
            if not self.launch_succeeds:
                raise windows.WindowError("boom")
            return WINDOW

        self.launched = []
        self.launch_succeeds = True
        self.patches = [
            mock.patch.object(windows, "discover", fake_discover),
            mock.patch.object(windows, "_windows", lambda refresh=False: fake_discover()),
            mock.patch.object(windows, "launch", fake_launch),
        ]
        for patch in self.patches:
            patch.start()
            self.addCleanup(patch.stop)

    async def test_no_window_and_auto_start_off_raises(self):
        self.available = []
        with self.assertRaises(windows.WindowError):
            await windows.resolve("")
        self.assertEqual(self.launched, [])

    async def test_auto_start_launches_once_and_returns_the_window(self):
        self.available = []
        windows.configure_launch(auto=True, auto_wait=20)
        resolved = await windows.resolve("")
        self.assertEqual(resolved["port"], 8750)
        self.assertEqual(self.launched, [20])

    async def test_auto_start_is_skipped_when_a_window_exists(self):
        windows.configure_launch(auto=True)
        await windows.resolve("")
        self.assertEqual(self.launched, [])

    async def test_auto_start_is_skipped_for_explicit_window_specs(self):
        self.available = []
        windows.configure_launch(auto=True)
        with self.assertRaises(windows.WindowError):
            await windows.resolve("2")
        self.assertEqual(self.launched, [])

    async def test_failed_auto_start_does_not_retry_immediately(self):
        self.available = []
        self.launch_succeeds = False
        windows.configure_launch(auto=True)
        with self.assertRaises(windows.WindowError):
            await windows.resolve("")
        self.assertEqual(len(self.launched), 1)
        # A second call inside the retry window must not spawn another process.
        with self.assertRaises(windows.WindowError):
            await windows.resolve("")
        self.assertEqual(len(self.launched), 1)

    async def test_auto_start_refuses_without_display_and_without_headless(self):
        self.available = []
        windows.configure_launch(auto=True, headless_mode=False)
        with mock.patch.dict("os.environ", {}, clear=False):
            for name in ("DISPLAY", "WAYLAND_DISPLAY"):
                windows.os.environ.pop(name, None)
            with self.assertRaises(windows.WindowError):
                await windows.resolve("")
        self.assertEqual(self.launched, [])


if __name__ == "__main__":
    unittest.main()
