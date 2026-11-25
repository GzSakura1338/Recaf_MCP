"""
Unit tests for the idle lifecycle: only the Python process stays resident.

Covers the idle decision, the watchdog thread, the ownership state file used to
adopt processes after a host reload, and the exit/signal cleanup.

    python -m unittest discover -s mcp-server/tests -v
"""

import json
import sys
import tempfile
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


class IdleStopTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.state = Path(self.tmp.name) / "owned.json"
        patcher = mock.patch.dict("os.environ", {"RECAF_MCP_STATE_FILE": str(self.state)})
        patcher.start()
        self.addCleanup(patcher.stop)

        windows._owned_windows.clear()
        windows._owned_procs.clear()
        windows.idle_stop_seconds = 0.0
        windows._last_activity = 0.0
        windows._idle_thread = None
        windows._state_written = False
        self.addCleanup(setattr, windows, "_state_written", False)
        self.addCleanup(windows._owned_windows.clear)
        self.addCleanup(windows._owned_procs.clear)
        self.addCleanup(setattr, windows, "idle_stop_seconds", 0.0)
        self.addCleanup(setattr, windows, "_last_activity", 0.0)
        self.addCleanup(setattr, windows, "_idle_thread", None)

    def test_configure_sets_idle_timeout(self):
        windows.configure_launch(idle_stop=45)
        self.assertEqual(windows.idle_stop_seconds, 45.0)
        windows.configure_launch(idle_stop=-5)
        self.assertEqual(windows.idle_stop_seconds, 0.0)

    def test_remember_owned_tracks_the_pid_and_state(self):
        windows._remember_owned(WINDOW)
        self.assertEqual(windows.owned_windows(), {4321: "1:(no workspace)"})
        self.assertGreater(windows._last_activity, 0.0)
        saved = json.loads(self.state.read_text())
        self.assertEqual(saved["pids"], [{"pid": 4321, "label": "1:(no workspace)"}])

    def test_should_stop_idle_respects_timeout_and_activity(self):
        windows.idle_stop_seconds = 30
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._last_activity = time.monotonic()
        self.assertFalse(windows._should_stop_idle())
        self.assertTrue(windows._should_stop_idle(now=windows._last_activity + 31))

    def test_should_stop_idle_is_off_when_disabled_or_unowned(self):
        windows.idle_stop_seconds = 0
        windows._owned_windows[4321] = "1:(no workspace)"
        self.assertFalse(windows._should_stop_idle(now=time.monotonic() + 10_000))
        windows.idle_stop_seconds = 30
        windows._owned_windows.clear()
        self.assertFalse(windows._should_stop_idle(now=time.monotonic() + 10_000))

    def test_stop_owned_windows_terminates_tracked_pids(self):
        windows.idle_stop_seconds = 30
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._save_state()
        seen = []
        with mock.patch.object(windows, "_terminate_pid", lambda pid, wait=8.0: seen.append(pid) or True):
            stopped = windows.stop_owned_windows_now()
        self.assertEqual(stopped, [4321])
        self.assertEqual(seen, [4321])
        self.assertEqual(windows.owned_windows(), {})
        self.assertEqual(json.loads(self.state.read_text())["pids"], [])

    def test_failed_terminate_keeps_the_pid_tracked(self):
        windows._owned_windows[4321] = "1:(no workspace)"
        with mock.patch.object(windows, "_terminate_pid", return_value=False):
            stopped = windows.stop_owned_windows_now()
        self.assertEqual(stopped, [])
        self.assertIn(4321, windows.owned_windows())

    def test_watchdog_thread_starts_only_when_usable(self):
        windows.idle_stop_seconds = 600
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._start_idle_thread()
        self.assertIsNotNone(windows._idle_thread)
        self.assertTrue(windows._idle_thread.is_alive())
        self.assertTrue(windows._idle_thread.daemon)
        first = windows._idle_thread
        windows._start_idle_thread()
        self.assertIs(windows._idle_thread, first)  # one thread, not one per window

        windows._idle_thread = None
        windows.idle_stop_seconds = 0
        windows._start_idle_thread()
        self.assertIsNone(windows._idle_thread)

    def test_adopt_previous_windows_takes_over_matching_processes(self):
        windows.idle_stop_seconds = 30
        self.state.write_text(json.dumps({"pids": [
            {"pid": 4321, "label": "1:(no workspace)"},
            {"pid": 9876, "label": "2:(no workspace)"},
        ]}))
        with mock.patch.object(windows, "_looks_like_our_recaf", side_effect=lambda pid: pid == 4321):
            adopted = windows.adopt_previous_windows()
        self.assertEqual(adopted, [4321])
        self.assertEqual(windows.owned_windows(), {4321: "1:(no workspace)"})
        saved = json.loads(self.state.read_text())
        self.assertEqual([entry["pid"] for entry in saved["pids"]], [4321])

    def test_looks_like_our_recaf_checks_the_command_line(self):
        windows.configure_launch(plugins="/opt/recaf/plugins")
        self.addCleanup(windows.configure_launch, None, None, None, None, None, [], None, None, None, None)
        ours = ["/usr/bin/java", "-jar", "/opt/recaf/recaf.jar", "--headless", "--idle",
                "-r", "/opt/recaf/plugins"]
        gui = ["/usr/bin/java", "-jar", "/opt/recaf/recaf.jar"]
        foreign = ["/usr/bin/java", "-jar", "/opt/recaf/recaf.jar", "--headless", "--idle",
                   "-r", "/somewhere/else"]
        with mock.patch.object(windows, "_proc_command_line", return_value=ours):
            self.assertTrue(windows._looks_like_our_recaf(1))
        with mock.patch.object(windows, "_proc_command_line", return_value=gui):
            self.assertFalse(windows._looks_like_our_recaf(2))
        with mock.patch.object(windows, "_proc_command_line", return_value=foreign):
            self.assertFalse(windows._looks_like_our_recaf(3))

    def test_state_file_names_its_owner(self):
        windows._remember_owned(WINDOW)
        self.assertEqual(json.loads(self.state.read_text())["owner"], __import__("os").getpid())

    def test_a_live_owner_blocks_adoption(self):
        # A second server (script, test, another host) must not steal and then
        # stop Recaf that the first server is still using.
        self.state.write_text(json.dumps({"owner": 1, "pids": [
            {"pid": 4321, "label": "1:(no workspace)"},
        ]}))
        with mock.patch.object(windows, "_looks_like_our_recaf", return_value=True):
            adopted = windows.adopt_previous_windows()
        self.assertEqual(adopted, [])
        self.assertEqual(windows.owned_windows(), {})
        self.assertEqual(json.loads(self.state.read_text())["owner"], 1)  # untouched

    def test_a_dead_owner_still_allows_adoption(self):
        self.state.write_text(json.dumps({"owner": 999_999_999, "pids": [
            {"pid": 4321, "label": "1:(no workspace)"},
        ]}))
        with mock.patch.object(windows, "_looks_like_our_recaf", return_value=True):
            adopted = windows.adopt_previous_windows()
        self.assertEqual(adopted, [4321])
        self.assertEqual(json.loads(self.state.read_text())["pids"], [{"pid": 4321, "label": "1:(no workspace)"}])

    def test_exit_keeps_a_state_file_it_did_not_write(self):
        self.state.write_text(json.dumps({"owner": 1, "pids": []}))
        windows._stop_owned_on_exit()
        self.assertTrue(self.state.exists())

    def test_exit_hook_stops_owned_windows_and_clears_state(self):
        windows._owned_windows[4321] = "1:(no workspace)"
        windows._save_state()
        terminated = []
        with mock.patch.object(windows, "_terminate_pid",
                               lambda pid, wait=8.0: terminated.append(pid) or True):
            windows._stop_owned_on_exit()
        self.assertEqual(terminated, [4321])
        self.assertEqual(windows.owned_windows(), {})
        self.assertFalse(self.state.exists())


if __name__ == "__main__":
    unittest.main()
