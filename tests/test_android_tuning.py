import struct
import unittest
from unittest.mock import Mock, patch

import android_tuning as tuning
import protocol
from test_rumble import load_receiver


SESSION = bytes.fromhex("0123456789abcdef0123456789abcdef")
VALUES = dict(tuning.DEFAULTS, rumble_boost=153)
PHONE_FIXTURE = bytes.fromhex(
    "5457545048310123456789abcdef0123456789abcdef00000000000000042d050096009902")
WINDOWS_FIXTURE = bytes.fromhex(
    "5457545043310123456789abcdef0123456789abcdef000000000000000400000000000000092d050096009902")


class TuningTests(unittest.TestCase):
    def test_wire_matches_android_fixtures_and_legacy_probe_fits_input_buffer(self):
        hello = b"TWTHI1" + SESSION
        self.assertLessEqual(len(hello), protocol.PACKET_SIZE)
        self.assertEqual(tuning.Request(SESSION), tuning.unpack_request(hello))
        self.assertEqual(tuning.Request(SESSION, 4, VALUES), tuning.unpack_request(PHONE_FIXTURE))
        self.assertEqual(WINDOWS_FIXTURE, tuning.pack_reply(tuning.Reply(SESSION, 4, 9, VALUES)))
        self.assertIsNone(protocol.unpack(PHONE_FIXTURE))
        for packet in (PHONE_FIXTURE[:-1], PHONE_FIXTURE + b"\0", WINDOWS_FIXTURE,
                       b"BADMAG" + PHONE_FIXTURE[6:], PHONE_FIXTURE[:22] + b"\xff" * 8 + PHONE_FIXTURE[30:]):
            self.assertIsNone(tuning.unpack_request(packet))

    def test_invalid_settings_do_not_become_partial_updates(self):
        for offset, value in ((30, 9), (30, 91), (31, 31), (32, 255),
                              (34, 91), (35, 99), (35, 251), (36, 4)):
            data = bytearray(PHONE_FIXTURE)
            data[offset] = value
            self.assertIsNone(tuning.unpack_request(data))

    def test_connection_keeps_windows_profile_until_phone_has_pending_edits(self):
        sync = tuning.TuningSync()
        windows = dict(tuning.DEFAULTS, range=70, invert=True)
        reply = sync.sync(tuning.Request(SESSION), windows)
        self.assertEqual(windows, reply.settings)
        self.assertEqual(0, reply.acknowledged)
        phone = tuning.Request(SESSION, 1, VALUES)
        reply = sync.sync(phone, windows)
        self.assertEqual(VALUES, reply.settings)
        self.assertEqual(1, reply.acknowledged)
        self.assertEqual(reply, sync.sync(phone, VALUES))
        # Windows edits win over retransmitted or out-of-order phone updates.
        newer = sync.sync(phone, windows)
        self.assertEqual(windows, newer.settings)
        self.assertGreater(newer.revision, reply.revision)
        self.assertEqual(newer, sync.sync(tuning.Request(SESSION), windows))
        # A new connection has an independent revision sequence.
        reply = sync.sync(tuning.Request(bytes(16), 1, VALUES), windows)
        self.assertEqual(VALUES, reply.settings)

    def test_receiver_accepts_only_active_phone_edits_without_counting_them_as_input(self):
        module = load_receiver()
        settings = dict(tuning.DEFAULTS, layout=[], timeout=0.25, layout_proposal=None)
        engine = module.ReceiverEngine(0, None, settings)
        peer = ("127.0.0.1", 5006)
        stranger = ("127.0.0.1", 5007)
        sock = Mock()
        phase, now = 0, 1.0
        packets = []
        sync = tuning.TuningSync()

        def receive(_size):
            if not packets:
                raise BlockingIOError
            return packets.pop(0)

        def advance(_delay):
            nonlocal phase, now, packets
            phase += 1
            now += 0.11
            reply = sync.sync(engine.phone_tuning_request, engine.settings)
            if reply is not None:
                engine.settings = dict(engine.settings, **reply.settings, tuning_reply=reply)
            if phase == 1:
                packets = [(b"TWTHI1" + SESSION, peer), (PHONE_FIXTURE, stranger)]
            elif phase == 2:
                self.assertEqual(tuning.Request(SESSION), engine.phone_tuning_request)
                packets = [(protocol.pack(0.25, seq=1), peer), (PHONE_FIXTURE, peer)]
            elif phase == 3:
                packets = [(b"TWTHI1" + SESSION, peer)]  # old hello cannot replace revision 4
            elif phase == 4:
                # Another sender must not inherit the previous phone's request/reply.
                packets = [(protocol.pack(0.0, seq=0), stranger)]
            else:
                engine._stop.set()

        packets = [(protocol.pack(0.25, seq=0), peer)]
        sock.recvfrom.side_effect = receive
        with patch.object(module.time, "monotonic", side_effect=lambda: now), \
                patch.object(module.time, "sleep", side_effect=advance):
            engine._loop(None, sock, Mock(), Mock())
        replies = [(call.args[0], call.args[1]) for call in sock.sendto.call_args_list
                   if call.args[0].startswith(tuning.WINDOWS_MAGIC)]
        self.assertTrue(any(data[22:30] == struct.pack("!q", 4) for data, _ in replies))
        self.assertTrue(all(addr == peer for _, addr in replies))
        self.assertEqual(153, engine.settings["rumble_boost"])
        self.assertIsNone(engine.phone_tuning_request)
        self.assertEqual(3, engine.status["frames"])


if __name__ == "__main__":
    unittest.main()
