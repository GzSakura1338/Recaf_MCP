"""
Unit tests for single-Recaf mode (the Linux default): one instance at a time.

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
    "instance_id": "single-1",
    "pid": 5555,
    "port": 8750,
    "base": "http://127.0.0.1:8750",
    "has_workspace": False,
    "headless": True,
    "window": 1,
    "label": "1:Nyx.jar",
}


class SingleRecafTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        windows.configure_launch(single=None)
        self.addCleanup(windows.configure_launch, None, None, None, None, None, [], None, None, None, None)

    def test_linux_defaults_to_single(self):
        with mock.patch.object(windows.sys, "platform", "linux"):
            self.assertTrue(windows.single_mode())

    def test_other_platforms_default_to_multi(self):
        with mock.patch.object(windows.sys, "platform", "darwin"):
            self.assertFalse(windows.single_mode())

    def test_explicit_setting_wins(self):
        windows.configure_launch(single=False)
        with mock.patch.object(windows.sys, "platform", "linux"):
            self.assertFalse(windows.single_mode())
        windows.configure_launch(single=True)
        with mock.patch.object(windows.sys, "platform", "darwin"):
            self.assertTrue(windows.single_mode())

    async def test_launch_refuses_a_second_instance(self):
        windows.configure_launch(single=True)
        with mock.patch.object(windows, "_windows", mock.AsyncMock(return_value=[WINDOW])):
            windows._cache = [WINDOW]
            with self.assertRaises(windows.WindowError) as ctx:
                await windows.launch(5)
        message = str(ctx.exception)
        self.assertIn("single-Recaf", message)
        self.assertIn("1:Nyx.jar", message)
        self.assertIn("--multi-recaf", message)

    async def test_multi_mode_passes_the_guard(self):
        windows.configure_launch(single=False)
        with mock.patch.object(windows, "_windows", mock.AsyncMock(return_value=[WINDOW])), \
                mock.patch.object(windows, "_launch_command", return_value=["/usr/bin/java", "-jar", "x.jar"]), \
                mock.patch.object(windows.subprocess, "Popen", side_effect=OSError("boom")):
            windows._cache = [WINDOW]
            with self.assertRaises(windows.WindowError) as ctx:
                await windows.launch(5)
        self.assertIn("failed to start Recaf", str(ctx.exception))

    async def test_list_windows_reports_the_mode(self):
        windows.configure_launch(single=True)
        with mock.patch.object(windows, "_windows", mock.AsyncMock(return_value=[WINDOW])):
            listed = await windows.list_windows()
        self.assertTrue(listed["single_recaf"])
        self.assertEqual(len(listed["windows"]), 1)


if __name__ == "__main__":
    unittest.main()
