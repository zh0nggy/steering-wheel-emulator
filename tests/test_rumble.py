import inspect
import importlib.machinery
import importlib.util
from pathlib import Path
import socket
import sys
import threading
import time
import unittest
from unittest.mock import Mock, patch

import protocol
from rumble import RumbleRelay


class RumbleTests(unittest.TestCase):
    def setUp(self):
        self.pad = Mock()
        self.relay = RumbleRelay(self.pad)
        self.callback = self.pad.register_notification.call_args.args[0]
        self.sock = Mock()
        self.peer = ("127.0.0.1", 5005)
        self.addCleanup(self.relay.close)

    def tick(self, seq, now, peer=None):
        self.relay.tick(self.sock, peer or self.peer,
                        {"seq": seq, "timestamp": now}, now)

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

    def test_gui_receiver_forwards_game_callback_and_stops_after_disconnect(self):
        # Load the real GUI engine without requiring ViGEmBus or opening a window.
        path = str(Path(__file__).resolve().parents[1] / "receiver_app.pyw")
        loader = importlib.machinery.SourceFileLoader("test_receiver_app", path)
        module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
        with patch.dict(sys.modules, {"vgamepad": None}):
            loader.exec_module(module)
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


if __name__ == "__main__":
    unittest.main()
