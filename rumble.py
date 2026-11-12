"""Relay the virtual Xbox pad's game feedback to the active sender over UDP."""

import threading

import protocol


class RumbleRelay:
    # Coalesce rapid game updates; renew steady rumble before the phone's 250 ms
    # deadline. Zero-strength heartbeats also recover a lost stop packet.
    MIN_INTERVAL = 0.02
    KEEPALIVE = 0.1

    def __init__(self, pad):
        self.pad = pad
        self.error = None
        self._registered = False
        self._lock = threading.Lock()
        self._motors = (0, 0)
        self._peer = None
        self._last_seq = None
        self._last_sent = None
        self._sent_at = float("-inf")
        if pad is not None:
            try:
                pad.register_notification(self._on_rumble)
                self._registered = True
            except Exception as exc:
                # A feedback failure should not disable steering.
                self.error = f"{type(exc).__name__}: {exc}"

    def _on_rumble(self, client, target, large_motor, small_motor, led_number, user_data):
        # ViGEm calls on its own thread. Keep networking and GUI work out of it.
        with self._lock:
            self._motors = (large_motor, small_motor)

    def tick(self, sock, addr, frame, now):
        """Called by the receiver loop, only while the sender is linked."""
        if not self._registered or addr is None or frame is None:
            return
        if addr != self._peer:
            self._peer = addr
            self._last_seq = self._last_sent = None
            self._sent_at = float("-inf")
        # At most one feedback packet per input sequence makes reordering
        # unambiguous, including when the receiver restarts during a session.
        if frame["seq"] == self._last_seq:
            return
        with self._lock:
            motors = self._motors
        elapsed = now - self._sent_at
        if motors == self._last_sent and elapsed < self.KEEPALIVE:
            return
        if motors != (0, 0) and elapsed < self.MIN_INTERVAL:
            return
        try:
            sock.sendto(protocol.pack_rumble(*motors, frame["seq"], frame["timestamp"]), addr)
        except OSError:
            return  # Retry on the next loop; the phone expires old feedback.
        self._last_seq = frame["seq"]
        self._last_sent = motors
        self._sent_at = now

    def close(self):
        if self._registered:
            self.pad.unregister_notification()
            self._registered = False
