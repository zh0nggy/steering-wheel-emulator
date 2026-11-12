import unittest
from unittest.mock import Mock, patch

import protocol
from test_rumble import load_receiver


class ReceiverSweepTests(unittest.TestCase):
    def setUp(self):
        self.module = load_receiver()
        self.make_engine()

    def make_engine(self, **tuning):
        settings = dict(self.module.TUNING_DEFAULTS, range=None, deadzone=0.0, gamma=1.0,
                        smooth=0.0, layout=[], timeout=0.25)
        settings.update(tuning)
        self.pad = Mock()
        self.engine = self.module.ReceiverEngine(0, self.pad, settings)
        self.app = self.module.ReceiverApp.__new__(self.module.ReceiverApp)
        self.app.engine = self.engine
        self.app.fake_btn = Mock()
        self.sock = Mock()
        self.rumble = Mock()
        self.peer = ("192.168.1.42", 12000)
        self.now = 10.0
        self.seq = 0
        self.packets = []
        self.samples = []

    def phone_frame(self, steer):
        self.packets.append((protocol.pack(steer, 0.6, 0.4, seq=self.seq,
                                           buttons=protocol.BUTTON_BIT["A"], timestamp=self.now), self.peer))
        self.seq += 1

    def run_loop(self, prepare, steps):
        phase = 0

        def receive(_size):
            if not self.packets:
                raise BlockingIOError
            return self.packets.pop(0)

        def advance(_delay):
            nonlocal phase
            self.samples.append(dict(
                self.engine.status,
                pad_steer=self.pad.left_joystick_float.call_args.kwargs["x_value_float"],
                peer=self.engine._sender_addr,
                layout=self.engine.phone_layout_snapshot,
                tuning=self.engine.phone_tuning_request,
            ))
            phase += 1
            if phase >= steps:
                self.engine._stop.set()
                return
            self.now = 10.0 + phase * 0.01
            prepare(phase)

        self.sock.recvfrom.side_effect = receive
        with patch.object(self.module.time, "monotonic", side_effect=lambda: self.now), \
                patch.object(self.module.time, "sleep", side_effect=advance):
            prepare(0)
            self.engine._loop(self.pad, self.sock, Mock(), self.rumble)

    def test_sweep_is_smooth_with_opposing_phone_input_and_keeps_phone_session(self):
        session = "0123456789abcdef0123456789abcdef"

        def prepare(phase):
            if phase == 1 or phase == 402:
                self.app.toggle_fake()
            if phase <= 401 or phase == 403:
                self.phone_frame(-0.95 if phase % 2 else 0.95)
            if phase == 1:
                self.packets += [
                    (self.module.app_common.PHONE_LAYOUT_MAGIC + f"{session}:0:[]".encode(), self.peer),
                    (b"TWTHI1" + bytes.fromhex(session), self.peer),
                ]

        self.run_loop(prepare, 431)
        sweep = self.samples[1:402]
        positions = [sample["pad_steer"] for sample in sweep]
        self.assertGreater(max(positions), 0.99)
        self.assertLess(min(positions), -0.99)
        self.assertLess(max(abs(b - a) for a, b in zip(positions, positions[1:])), 0.04)
        for sample in sweep:
            self.assertEqual((0.0, 0.0, ()), (sample["throttle"], sample["brake"], sample["held"]))
        for sample in self.samples[1:]:
            self.assertEqual(self.peer, sample["peer"])
            self.assertEqual((session, 0, []), sample["layout"])
            self.assertEqual(bytes.fromhex(session), sample["tuning"].session)
        # Stop restores the latest fresh phone frame even before another arrives.
        restored = self.samples[402]
        self.assertAlmostEqual(-0.95, restored["pad_steer"], places=6)
        self.assertAlmostEqual(0.6, restored["throttle"], places=6)
        self.assertAlmostEqual(0.4, restored["brake"], places=6)
        self.assertEqual(("A",), restored["held"])
        self.assertEqual(self.seq, self.samples[-1]["frames"])
        self.assertEqual(0, self.samples[-1]["dropped"])
        # The ordinary disconnect failsafe still applies after leaving the test.
        self.assertFalse(self.samples[-1]["linked"])
        self.assertEqual(0.0, self.samples[-1]["pad_steer"])
        self.assertEqual((), self.samples[-1]["held"])
        self.assertTrue(all(call.args[1] == self.peer for call in self.sock.sendto.call_args_list))
        self.assertTrue(all(not call.kwargs["enabled"] for call in self.rumble.tick.call_args_list
                            if 10.01 <= call.args[3] < 14.02))

    def test_sweep_works_without_sender_and_centres_after_stopping_if_sender_disconnected(self):
        for phone_was_connected in (False, True):
            with self.subTest(phone_was_connected=phone_was_connected):
                self.make_engine()

                def prepare(phase):
                    if phase == 0:
                        self.app.toggle_fake()
                        if phone_was_connected:
                            self.phone_frame(-0.9)
                    elif phase == 420:
                        self.app.toggle_fake()

                self.run_loop(prepare, 422)
                self.assertTrue(all(sample["linked"] for sample in self.samples[:420]))
                positions = [sample["pad_steer"] for sample in self.samples[:420]]
                self.assertGreater(max(positions), 0.99)
                self.assertLess(min(positions), -0.99)
                for sample in self.samples[420:]:
                    self.assertFalse(sample["linked"])
                    self.assertEqual((0.0, 0.0, 0.0, ()),
                                     (sample["pad_steer"], sample["throttle"], sample["brake"], sample["held"]))
                if not phone_was_connected:
                    self.sock.sendto.assert_not_called()
                    self.rumble.tick.assert_not_called()

    def test_switching_sources_does_not_carry_smoothing_or_held_controls(self):
        self.make_engine(smooth=0.5)

        def prepare(phase):
            if phase == 1 or phase == 3:
                self.app.toggle_fake()
            if phase < 3:
                self.phone_frame(-0.8)

        self.run_loop(prepare, 4)
        self.assertAlmostEqual(-0.4, self.samples[0]["pad_steer"], places=6)
        self.assertEqual(0.0, self.samples[1]["pad_steer"])
        self.assertGreater(self.samples[2]["pad_steer"], 0.0)
        self.assertAlmostEqual(-0.4, self.samples[3]["pad_steer"], places=6)
        self.assertEqual(("A",), self.samples[3]["held"])

    def test_sweep_button_can_start_receiver_and_stops_cleanly(self):
        self.app.engine = None
        self.app.toggle = Mock(side_effect=lambda: setattr(self.app, "engine", self.engine))
        with patch.object(self.module.time, "monotonic", return_value=10.0):
            self.app.toggle_fake()
        self.app.toggle.assert_called_once_with()
        self.assertTrue(self.app.sweeping)
        self.app.fake_btn.config.assert_called_with(text="Stop fake sweep")
        self.app.toggle_fake()
        self.assertFalse(self.app.sweeping)
        self.app.fake_btn.config.assert_called_with(text="Test: fake sweep")


if __name__ == "__main__":
    unittest.main()
