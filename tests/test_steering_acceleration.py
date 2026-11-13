import unittest
from unittest.mock import Mock, patch

from app_common import SteeringAcceleration
import sender_app


class SteeringAccelerationTests(unittest.TestCase):
    # Matching scenarios and expected angles from Android's
    # SteeringAccelerationTest.kt, so both senders retain the same response.
    def test_fast_turns_travel_further_and_hold_in_either_direction(self):
        for direction in (-1.0, 1.0):
            slow, fast = SteeringAcceleration(90.0), SteeringAcceleration(90.0)
            slow.update(0.0, 0.0)
            fast.update(0.0, 0.0)
            for angle in range(1, 16):
                slow.update(direction * angle, 0.05)
                fast.update(direction * angle, 0.005)
            self.assertAlmostEqual(direction * 15.0, slow.update(direction * 15, 0.05))
            self.assertAlmostEqual(direction * 27.0, fast.update(direction * 15, 0.05))

    def test_slow_unwind_tapers_and_clears_boost_near_centre(self):
        steering = SteeringAcceleration(90.0)
        steering.update(0.0, 0.0)
        steering.update(15.0, 0.075)
        self.assertAlmostEqual(26.0, steering.update(14.0, 0.05))
        for angle in range(13, 5, -1):
            steering.update(float(angle), 0.05)
        self.assertTrue(5 < steering.update(5.0, 0.05) < 17)
        for angle in range(4, 0, -1):
            steering.update(float(angle), 0.05)
        self.assertEqual(0.4, steering.update(0.4, 0.05))
        self.assertEqual(1.4, steering.update(1.4, 0.05))

    def test_fast_unwind_does_not_reverse_before_crossing_centre(self):
        steering = SteeringAcceleration(90.0)
        steering.update(15.0, 0.0)
        self.assertAlmostEqual(9.6, steering.update(12.0, 0.015))
        self.assertGreater(steering.update(2.0, 0.01), 0)
        self.assertEqual(-2.0, steering.update(-2.0, 0.01))

    def test_resets_and_sample_gaps_clear_boost(self):
        steering = SteeringAcceleration(90.0)
        steering.update(0.0, 0.0)
        self.assertAlmostEqual(27.0, steering.update(15.0, 0.075))
        self.assertEqual(15.0, steering.update(15.0, 0.0))
        self.assertAlmostEqual(24.0, steering.update(20.0, 0.02))
        self.assertEqual(20.0, steering.update(20.0, 0.2))
        steering.update(25.0, 0.02)
        steering.reset()
        self.assertEqual(25.0, steering.update(25.0, 0.01))

    def test_correction_is_bounded_during_repeated_flicks(self):
        for direction in (-1.0, 1.0):
            steering = SteeringAcceleration(90.0)
            steering.update(0.0, 0.0)
            for angle in [15, 25, 14, 35, 13, 50, 70, 89, 95] * 10:
                virtual = direction * steering.update(direction * angle, 0.005)
                self.assertGreaterEqual(virtual, 0.5 * angle)
                self.assertLessEqual(virtual, angle + min(20.0, max(0.0, 90 - angle)))


class MacSteeringIntegrationTests(unittest.TestCase):
    def run_sender(self, samples, prepare=None, *, invert=False):
        """Run actual sender filtering/mapping/packets with a deterministic IMU."""
        keys = Mock()
        keys.state.return_value = (False, False, 0)
        with patch.object(sender_app.app_common, "low_latency_socket", return_value=Mock()):
            engine = sender_app.SenderEngine(keys)
        engine.settings = dict(range=45.0, min_cutoff=1.0, beta=0.3, invert=invert)
        engine.target = ("192.168.1.10", 5005)
        # Isolate acceleration from the already-established low-pass filter.
        engine._filter = Mock()
        engine._filter.update.side_effect = lambda value, dt: value
        imu = Mock()
        imu.orientation.return_value = (0.0, 0.0, 0.0)
        phase = 0
        states, packets = [], []
        now = lambda: 10.0 + samples[phase][0]

        def advance(_delay):
            nonlocal phase
            if engine._stop.is_set():
                return  # Exit sends neutral packets, with short sleeps.
            states.append(dict(engine.status))
            if phase + 1 == len(samples):
                engine._stop.set()
            else:
                phase += 1
                if prepare:
                    prepare(engine, phase)

        def send(target, steer, throttle, brake, seq, buttons):
            packets.append((target, steer))

        with patch.object(engine, "_raw_roll", side_effect=lambda *_: (samples[phase][1], "ok")), \
                patch.object(engine, "_read_echoes"), patch.object(engine, "_send", side_effect=send), \
                patch.object(sender_app.time, "monotonic", side_effect=now), \
                patch.object(sender_app.time, "sleep", side_effect=advance):
            engine._loop(imu)
        return states, packets

    def test_acceleration_reaches_packets_with_range_inversion_and_clamping(self):
        for invert in (False, True):
            states, packets = self.run_sender(
                [(0.0, 0.0), (0.075, 15.0), (0.08, 15.0), (0.085, 50.0)], invert=invert)
            sign = -1 if invert else 1
            self.assertAlmostEqual(sign * 27 / 45, states[1]["steer"])
            self.assertAlmostEqual(sign * 27 / 45, states[2]["steer"])
            self.assertEqual(sign, states[3]["steer"])
            self.assertEqual(15.0, states[1]["roll"])  # Debug angle stays physical.
            # Holding the same accelerated angle needn't produce a new packet
            # until the keepalive is due; changed values go out immediately.
            self.assertEqual(3, len(packets[:-10]))
            for index, (_, sent_steer) in zip((0, 1, 3), packets[:-10]):
                self.assertAlmostEqual(states[index]["steer"], sent_steer)
            self.assertTrue(all(steer == 0.0 for _, steer in packets[-10:]))

    def test_calibration_range_change_and_reconnect_clear_previous_boost(self):
        def calibrate(engine):
            engine.calibrate()

        def change_range(engine):
            engine.settings = dict(engine.settings, range=60.0)

        def reconnect(engine):
            engine.target = ("192.168.1.11", 5005)

        for change, expected in ((calibrate, 0.0), (change_range, 15 / 60), (reconnect, 15 / 45)):
            for reading in (15.0, None):
                with self.subTest(change=change.__name__, reading=reading):
                    def prepare(engine, phase):
                        if phase == 2:
                            change(engine)

                    states, _ = self.run_sender(
                        [(0.0, 0.0), (0.075, 15.0), (0.08, reading)], prepare)
                    self.assertAlmostEqual(27 / 45, states[1]["steer"])
                    self.assertAlmostEqual(expected, states[2]["steer"])


if __name__ == "__main__":
    unittest.main()
