import inspect
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

import protocol
import android_tuning
from rumble import RumbleRelay


def load_receiver():
    path = str(Path(__file__).resolve().parents[1] / "receiver_app.pyw")
    loader = importlib.machinery.SourceFileLoader("test_receiver_app", path)
    module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
    with patch.dict(sys.modules, {"vgamepad": None}):
        loader.exec_module(module)
    return module


class RumbleTests(unittest.TestCase):
    def setUp(self):
        self.pad = Mock()
        self.relay = RumbleRelay(self.pad)
        self.callback = self.pad.register_notification.call_args.args[0]
        self.sock = Mock()
        self.peer = ("127.0.0.1", 5005)
        self.addCleanup(self.relay.close)

    def tick(self, seq, now, peer=None, **settings):
        self.relay.tick(self.sock, peer or self.peer,
                        {"seq": seq, "timestamp": now}, now, **settings)

    def motors(self, large, small):
        self.callback(None, None, large, small, 0, None)

    def feedback(self):
        return protocol.unpack_rumble(self.sock.sendto.call_args.args[0])

    def test_wire_bytes_match_android_fixture(self):
        expected = bytes.fromhex("5457524d4231fffffffe40934a0000000000c820")
        self.assertEqual(expected, protocol.pack_rumble(200, 32, 0xFFFFFFFE, 1234.5))
        self.assertEqual({"seq": 0xFFFFFFFE, "timestamp": 1234.5,
                          "large_motor": 200, "small_motor": 32}, protocol.unpack_rumble(expected))
        self.assertIsNone(protocol.unpack_rumble(expected[:-1]))
        self.assertIsNone(protocol.unpack_rumble(b"BADMAG" + expected[6:]))
        self.assertIsNone(protocol.unpack(expected))  # input format stays distinct

    def test_vgamepad_callback_signature(self):
        # vgamepad compares the entire signature, including parameter names.
        self.assertEqual("(client, target, large_motor, small_motor, led_number, user_data)",
                         str(inspect.signature(self.callback)))

    def test_tuned_wire_bytes_match_android_and_validate_boost(self):
        expected = bytes.fromhex("5457524d4232fffffffe40934a0000000000c820fa")
        self.assertEqual(expected, protocol.pack_rumble(200, 32, 0xFFFFFFFE, 1234.5, 250))
        self.assertEqual({"seq": 0xFFFFFFFE, "timestamp": 1234.5, "large_motor": 200,
                          "small_motor": 32, "boost": 250}, protocol.unpack_rumble(expected))
        for invalid in (expected[:-1], expected + b"\x00", b"BADMAG" + expected[6:],
                        expected[:-1] + b"\x00", expected[:-1] + b"\xff"):
            self.assertIsNone(protocol.unpack_rumble(invalid))
        self.assertEqual(100, protocol.unpack_rumble(protocol.pack_rumble(1, 2, 3, 4.0, 0))["boost"])
        self.assertEqual(250, protocol.unpack_rumble(protocol.pack_rumble(1, 2, 3, 4.0, 300))["boost"])

    def test_windows_toggle_stops_immediately_and_boost_updates_steady_feedback(self):
        self.motors(50, 10)
        self.tick(0, 1.0, boost=100)
        self.tick(1, 1.03, boost=250)
        self.assertEqual(2, self.sock.sendto.call_count)
        self.assertEqual(250, self.feedback()["boost"])
        self.tick(2, 1.031, enabled=False, boost=250)
        self.assertEqual((0, 0), (self.feedback()["large_motor"], self.feedback()["small_motor"]))
        self.tick(3, 1.06, enabled=True, boost=200)
        self.assertEqual((50, 10), (self.feedback()["large_motor"], self.feedback()["small_motor"]))
        self.assertEqual(200, self.feedback()["boost"])

    def test_windows_preview_expires_resumes_game_and_respects_toggle(self):
        self.motors(20, 30)
        self.tick(0, 1.0, boost=150, test_until=2.0)
        self.assertEqual((160, 100), (self.feedback()["large_motor"], self.feedback()["small_motor"]))
        self.tick(1, 2.01, boost=150, test_until=2.0)
        self.assertEqual((20, 30), (self.feedback()["large_motor"], self.feedback()["small_motor"]))
        self.tick(2, 2.011, enabled=False, boost=150, test_until=3.0)
        self.assertEqual((0, 0), (self.feedback()["large_motor"], self.feedback()["small_motor"]))

    def test_windows_preview_works_without_a_virtual_pad_and_sends_stop(self):
        relay = RumbleRelay(None)
        relay.tick(self.sock, self.peer, {"seq": 1, "timestamp": 1.0}, 1.0,
                   boost=150, test_until=2.0)
        self.assertEqual(160, self.feedback()["large_motor"])
        relay.tick(self.sock, self.peer, {"seq": 2, "timestamp": 2.0}, 2.0,
                   boost=150, test_until=2.0)
        self.assertEqual(0, self.feedback()["large_motor"])

    def test_changes_are_coalesced_but_stops_are_immediate(self):
        self.tick(0, 1.0)
        self.motors(80, 200)
        self.tick(1, 1.01)
        self.assertEqual(1, self.sock.sendto.call_count)
        self.tick(2, 1.025)
        self.assertEqual(200, self.feedback()["small_motor"])
        self.motors(0, 0)
        self.tick(3, 1.026)
        self.assertEqual(0, self.feedback()["small_motor"])
        self.assertEqual(3, self.sock.sendto.call_count)

    def test_keepalives_recover_lost_stop_and_do_not_reuse_input_sequences(self):
        self.motors(255, 0)
        self.tick(10, 1.0)
        self.tick(11, 1.05)
        self.assertEqual(1, self.sock.sendto.call_count)
        self.tick(12, 1.11)
        self.assertEqual(2, self.sock.sendto.call_count)
        self.motors(0, 0)
        self.tick(12, 1.12)
        self.assertEqual(2, self.sock.sendto.call_count)
        self.tick(13, 1.13)
        self.tick(14, 1.24)
        self.assertEqual(4, self.sock.sendto.call_count)
        self.assertEqual(0, self.feedback()["large_motor"])

    def test_peer_switch_and_sequence_wrap(self):
        self.motors(100, 25)
        self.tick(0xFFFFFFFF, 1.0)
        self.tick(0, 1.11)
        self.assertEqual(0, self.feedback()["seq"])
        other = ("127.0.0.1", 6006)
        self.tick(0, 1.12, other)
        self.assertEqual(other, self.sock.sendto.call_args.args[1])
        self.assertEqual(3, self.sock.sendto.call_count)

    def test_send_failure_retries_and_close_unregisters_once(self):
        self.sock.sendto.side_effect = OSError("network blip")
        self.tick(0, 1.0)
        self.sock.sendto.side_effect = None
        self.tick(0, 1.001)
        self.assertEqual(2, self.sock.sendto.call_count)
        self.relay.close()
        self.relay.close()
        self.pad.unregister_notification.assert_called_once()

    def test_dry_run_or_failed_subscription_does_not_send(self):
        for pad in (None, Mock(register_notification=Mock(side_effect=RuntimeError("unsupported")))):
            relay = RumbleRelay(pad)
            relay.tick(self.sock, self.peer, {"seq": 1, "timestamp": 1.0}, 1.0)
            self.assertEqual(pad is not None, relay.error is not None)
            relay.close()
        self.sock.sendto.assert_not_called()

    def test_real_udp_feedback_uses_input_timestamp(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as phone, \
                socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver:
            phone.bind(("127.0.0.1", 0))
            phone.settimeout(1)
            self.motors(220, 40)
            frame = protocol.unpack(protocol.pack(0.5, seq=123, timestamp=12.5))
            self.relay.tick(receiver, phone.getsockname(), frame, 999.0)
            feedback = protocol.unpack_rumble(phone.recv(100))
            self.assertEqual(12.5, feedback["timestamp"])
            self.assertEqual(123, feedback["seq"])
            self.assertEqual((220, 40), (feedback["large_motor"], feedback["small_motor"]))

    def test_receiver_loop_applies_windows_rumble_tuning_and_cancels_preview_on_disconnect(self):
        module = load_receiver()
        settings = {"deadzone": 0.0, "gamma": 1.0, "smooth": 0.0, "invert": False,
                    "range": None, "layout": [], "timeout": 0.25,
                    "game_rumble": True, "rumble_boost": 150}
        engine = module.ReceiverEngine(0, self.pad, settings)
        self.motors(50, 10)
        phase, now, drained = 0, 1.0, False

        def receive(_size):
            nonlocal drained
            if drained or phase == 5:
                raise BlockingIOError
            drained = True
            return protocol.pack(0.5, seq=phase, timestamp=now), self.peer

        def advance(_delay):
            nonlocal phase, now, drained
            phase += 1
            drained = False
            now += 0.3 if phase == 5 else 0.03
            if phase == 1:
                engine.settings = dict(settings, rumble_boost=250)
            elif phase == 2:
                engine.settings = dict(engine.settings, game_rumble=False)
            elif phase == 3:
                engine.settings = dict(engine.settings, game_rumble=True)
                engine.rumble_test_until = now + 1.0
            elif phase == 4:
                engine.rumble_test_until = now
            elif phase == 5:
                engine.rumble_test_until = now + 1.0
            elif phase == 6:
                engine._stop.set()

        self.sock.recvfrom.side_effect = receive
        with patch.object(module.time, "monotonic", side_effect=lambda: now), \
                patch.object(module.time, "sleep", side_effect=advance):
            engine._loop(self.pad, self.sock, Mock(), self.relay)
        feedback = [protocol.unpack_rumble(call.args[0]) for call in self.sock.sendto.call_args_list]
        self.assertEqual([(50, 10, 150), (50, 10, 250), (0, 0, 250),
                          (160, 100, 250), (50, 10, 250)],
                         [(f["large_motor"], f["small_motor"], f["boost"]) for f in feedback if f])
        self.assertFalse(engine.status["linked"])
        self.assertEqual(0.0, engine.status["steer"])
        self.assertEqual(0.0, engine.rumble_test_until)

    def test_gui_receiver_forwards_game_callback_and_stops_after_disconnect(self):
        # Load the real GUI engine without requiring ViGEmBus or opening a window.
        module = load_receiver()
        pad = Mock()
        settings = {"deadzone": 0.0, "gamma": 1.0, "smooth": 0.0,
                    "invert": False, "range": None, "layout": [], "timeout": 0.05}
        engine = module.ReceiverEngine(0, pad, settings)
        relay = RumbleRelay(pad)
        callback = pad.register_notification.call_args.args[0]
        callback(None, None, 120, 240, 0, None)
        self.addCleanup(relay.close)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver, \
                socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as phone:
            receiver.bind(("127.0.0.1", 0))
            receiver.setblocking(False)
            phone.bind(("127.0.0.1", 0))
            phone.settimeout(0.1)
            thread = threading.Thread(target=engine._loop, args=(pad, receiver, Mock(), relay))
            thread.start()
            try:
                timestamp = time.monotonic()
                phone.sendto(protocol.pack(0.25, seq=42, timestamp=timestamp), receiver.getsockname())
                feedback = None
                deadline = time.monotonic() + 1.0
                while feedback is None and time.monotonic() < deadline:
                    feedback = protocol.unpack_rumble(phone.recv(4096))
                self.assertIsNotNone(feedback)
                self.assertEqual((120, 240), (feedback["large_motor"], feedback["small_motor"]))
                self.assertEqual(42, feedback["seq"])
                self.assertEqual(timestamp, feedback["timestamp"])
                # Once input stops, no feedback can renew the phone's lease.
                while time.monotonic() < deadline and engine.status["linked"]:
                    time.sleep(0.005)
                self.assertFalse(engine.status["linked"])
                self.assertEqual(0.0, engine.status["steer"])
                with self.assertRaises(socket.timeout):
                    while True:
                        self.assertIsNone(protocol.unpack_rumble(phone.recv(4096)))
            finally:
                engine._stop.set()
                thread.join(1)
                self.assertFalse(thread.is_alive())


class ReceiverRumbleSettingsTests(unittest.TestCase):
    def setUp(self):
        self.module = load_receiver()
        self.app = self.module.ReceiverApp.__new__(self.module.ReceiverApp)
        tk = self.module.tk
        self.interpreter = tk.Tcl()
        for key, default in self.module.TUNING_DEFAULTS.items():
            var_type = tk.BooleanVar if isinstance(default, bool) else tk.DoubleVar
            setattr(self.app, key, var_type(master=self.interpreter, value=default))
        self.app.device = tk.StringVar(master=self.interpreter, value="android")
        self.app.port = tk.IntVar(master=self.interpreter, value=5005)
        self.app.profiles = {key: dict(self.module.TUNING_DEFAULTS) for key, _ in self.module.DEVICES}
        self.app._live_device = "android"
        self.app.phone_layout = Mock(layout=[])
        self.app.phone_tuning = android_tuning.TuningSync()
        self.app.tuning_reply = None
        self.app.engine = Mock(phone_tuning_request=None)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patcher = patch.object(self.module, "SETTINGS_FILE", str(Path(directory.name) / "settings.json"))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_android_rumble_settings_survive_save_and_profile_switches(self):
        self.app.game_rumble.set(False)
        self.app.rumble_boost.set(225)
        self.app.save_settings()
        self.app._apply_profile("mac")
        self.assertTrue(self.app.game_rumble.get())
        self.app.load_settings()
        self.app._apply_profile("android")
        self.assertFalse(self.app.game_rumble.get())
        self.assertEqual(225, self.app.rumble_boost.get())
        settings = self.app.settings()
        self.assertFalse(settings["game_rumble"])
        self.assertEqual(225, settings["rumble_boost"])

    def test_existing_settings_get_defaults_and_loaded_boost_is_bounded(self):
        Path(self.module.SETTINGS_FILE).write_text(json.dumps({"profiles": {"android": {"range": 60}}}))
        self.app.load_settings()
        self.app._apply_profile("android")
        self.assertTrue(self.app.game_rumble.get())
        self.assertEqual(150, self.app.rumble_boost.get())
        self.assertEqual(60, self.app.range.get())
        self.app.profiles["android"]["rumble_boost"] = 1000
        self.app._apply_profile("android")
        self.assertEqual(250, self.app.rumble_boost.get())

    def test_phone_tuning_updates_windows_sliders_and_persisted_android_profile(self):
        settings = dict(android_tuning.DEFAULTS, range=60, deadzone=0.1, gamma=2.0,
                        smooth=0.2, invert=True, game_rumble=False, rumble_boost=225)
        self.app.engine.phone_tuning_request = android_tuning.Request(bytes(16), 1, settings)
        self.app.sync_phone_tuning()
        for key, value in settings.items():
            self.assertEqual(value, getattr(self.app, key).get())
        self.assertEqual(1, self.app.settings()["tuning_reply"].acknowledged)
        stored = json.loads(Path(self.module.SETTINGS_FILE).read_text())
        self.assertEqual(settings, stored["profiles"]["android"])
        self.assertEqual(android_tuning.DEFAULTS, stored["profiles"]["mac"])
        # Repeated UDP edits must not undo a subsequent change made in Windows.
        self.app.range.set(70)
        self.app.sync_phone_tuning()
        self.assertEqual(70, self.app.range.get())
        self.assertEqual(70, self.app.tuning_reply.settings["range"])

    def test_mac_profile_ignores_phone_tuning(self):
        self.app._live_device = "mac"
        self.app.engine.phone_tuning_request = android_tuning.Request(
            bytes(16), 1, dict(android_tuning.DEFAULTS, gamma=3.0))
        self.app.sync_phone_tuning()
        self.assertEqual(1.5, self.app.gamma.get())
        self.assertIsNone(self.app.settings()["tuning_reply"])


if __name__ == "__main__":
    unittest.main()
