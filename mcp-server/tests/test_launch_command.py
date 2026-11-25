"""
Unit tests for the headless launch plumbing in src/server/windows.py.

These cover the part that decides *how* a new Recaf process is started, which is
where Linux headless support lives. No Recaf, no display and no network needed:

    python -m unittest discover -s mcp-server/tests -v
"""

import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from src.server import windows  # noqa: E402


class HeadlessCommandTest(unittest.TestCase):
    def setUp(self):
        self.java = shutil.which("java") or "java"
        windows.configure_launch(
            plugins="/opt/recaf/plugins",
            data="/tmp/recaf-data",
            java=self.java,
            java_args=["-Xmx2g", "-Dfile.encoding=UTF-8"],
        )
        windows.launch_jar = None
        windows.headless = None
        self.addCleanup(windows.configure_launch, None, None, None, None, None, [], None)
        self.addCleanup(setattr, windows, "launch_jar", None)
        self.addCleanup(setattr, windows, "headless", None)

    def test_headless_command_has_idle_and_plugin_dir(self):
        windows.headless = True
        command = windows._headless_command("/opt/recaf/recaf.jar")
        self.assertEqual(command[0], self.java)
        self.assertEqual(command[1:3], ["-Xmx2g", "-Dfile.encoding=UTF-8"])
        self.assertIn("-jar", command)
        self.assertEqual(command[command.index("-jar") + 1], "/opt/recaf/recaf.jar")
        self.assertIn("--headless", command)
        self.assertIn("--idle", command)
        self.assertEqual(command[command.index("-r") + 1], "/opt/recaf/plugins")
        self.assertEqual(command[command.index("-d") + 1], "/tmp/recaf-data")

    def test_gui_command_has_no_headless_flags(self):
        windows.headless = False
        command = windows._window_command("/opt/recaf/recaf.jar")
        self.assertNotIn("--headless", command)
        self.assertNotIn("--idle", command)
        self.assertIn("-Xmx2g", command)

    @unittest.skipIf(sys.platform == "win32", "DISPLAY-based auto detection is Linux only")
    def test_auto_detection_follows_display(self):
        windows.headless = None
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("DISPLAY", None)
            self.assertTrue(windows._headless_requested())
            os.environ["DISPLAY"] = ":0"
            self.assertFalse(windows._headless_requested())

    def test_explicit_mode_wins_over_display(self):
        windows.headless = True
        with mock.patch.dict(os.environ, {"DISPLAY": ":0"}):
            self.assertTrue(windows._headless_requested())
        windows.headless = False
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("DISPLAY", None)
            self.assertFalse(windows._headless_requested())

    def test_launch_command_reuses_a_window_in_the_same_mode(self):
        windows.headless = True
        gui_command = [self.java, "-jar", "/opt/recaf/recaf.jar"]
        headless_command = [self.java, "-jar", "/opt/recaf/recaf.jar", "--headless", "--idle"]
        found = [{"pid": 1, "instance_id": "a"}, {"pid": 2, "instance_id": "b"}]
        with mock.patch.object(windows, "_command_line_of", side_effect=[gui_command, headless_command]):
            self.assertEqual(windows._launch_command(found), headless_command)

    def test_launch_command_falls_back_to_env_jar(self):
        windows.headless = True
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "recaf.jar"
            jar.write_bytes(b"")
            with mock.patch.dict(os.environ, {"RECAF_JAR": str(jar)}):
                command = windows._launch_command([])
        self.assertIn("--headless", command)
        self.assertIn(str(jar), command)

    def test_launch_command_errors_when_no_jar_is_known(self):
        windows.headless = True
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("RECAF_JAR", None)
            with mock.patch.object(windows, "REPO_ROOT", Path("/nonexistent")):
                with self.assertRaises(windows.WindowError) as ctx:
                    windows._launch_command([])
        self.assertIn("headless", str(ctx.exception).lower())

    def test_describe_exposes_headless_flag(self):
        window = {
            "window": 1, "label": "1:client.jar", "port": 8750, "instance_id": "x",
            "jar_name": "client.jar", "jar_path": "/tmp/client.jar", "has_workspace": True,
            "pid": 42, "headless": True,
        }
        described = windows.describe(window)
        self.assertTrue(described["headless"])
        self.assertEqual(described["label"], "1:client.jar")


class CommandLineFallbackTest(unittest.TestCase):
    @unittest.skipIf(sys.platform == "win32", "Linux process inspection only")
    def test_current_process_command_line_is_readable(self):
        command = windows._command_line_of(os.getpid())
        self.assertTrue(command)
        self.assertTrue(any("python" in part.lower() for part in command[:2]))


if __name__ == "__main__":
    unittest.main()
