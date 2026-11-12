"""Frozen wire format between the Mac (sensor) and the Windows PC (virtual pad).

*** WIRE_VERSION 3 -- COPY THIS FILE TO BOTH MACHINES TOGETHER. ***

If the two copies differ, the receiver silently rejects every packet (version
mismatch) and you get a dead wheel with no error message. This is the one file
where a stale copy fails quietly instead of loudly.

v2 added gear_up / gear_down shift counters.
v3 replaced them with a bitmask of every Xbox pad button, so the Mac can bind
   any key to any button. The two triggers keep their own analog values.
"""

import struct
import time

WIRE_VERSION = 3
DEFAULT_PORT = 5005

# version, timestamp, steer, right_trigger, left_trigger, seq, buttons
#   steer              -1.0 (full left) .. +1.0 (full right), left stick X
#   right/left trigger  0.0 .. 1.0 (analog, ramped on the Mac)
#   buttons             bitmask, bit set = button HELD right now (see BUTTONS)
_PACKET = struct.Struct("!BdfffIH")
PACKET_SIZE = _PACKET.size

# Optional Windows -> Android feedback. This does not change the v3 input frame;
# older senders simply ignore it. Echo the phone's sequence and timestamp so it
# can reject reordered or delayed feedback using its own clock.
RUMBLE_MAGIC = b"TWRMB1"
_RUMBLE = struct.Struct("!6sIdBB")
RUMBLE_SIZE = _RUMBLE.size
# Tuned feedback carries the Windows boost alongside the motor strengths, so
# UDP loss/reordering cannot apply settings to the wrong feedback frame.
RUMBLE_TUNING_MAGIC = b"TWRMB2"
_RUMBLE_TUNING = struct.Struct("!6sIdBBB")
RUMBLE_TUNING_SIZE = _RUMBLE_TUNING.size
RUMBLE_MIN_BOOST = 100
RUMBLE_MAX_BOOST = 250
RUMBLE_DEFAULT_BOOST = 150

# Bit order of the `buttons` field. Names match pad_mapping / vgamepad.
BUTTONS = (
    "A", "B", "X", "Y",
    "LB", "RB",
    "VIEW", "MENU", "GUIDE",
    "LS", "RS",
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
)
BUTTON_BIT = {name: 1 << i for i, name in enumerate(BUTTONS)}

# WHY HELD STATE IS SAFE NOW:
# v2 sent counters because a shift is an event: sending "gear_up=True" at
# 100 Hz while a key is held must not mean 20 upshifts. With a real pad button
# that problem doesn't exist -- the receiver presses the button while the bit
# is set and releases it when it clears, exactly like a physical button held
# down. The game sees one press per key press. The Mac ignores keyboard
# auto-repeat, and the receiver's failsafe releases everything if packets stop.


def clamp(value, low, high):
    return low if value < low else high if value > high else value


def pack(steer, right_trigger=0.0, left_trigger=0.0, seq=0, buttons=0,
         timestamp=None):
    """Serialise one control frame. Returns bytes ready for sendto()."""
    if timestamp is None:
        timestamp = time.monotonic()
    return _PACKET.pack(
        WIRE_VERSION,
        timestamp,
        clamp(float(steer), -1.0, 1.0),
        clamp(float(right_trigger), 0.0, 1.0),
        clamp(float(left_trigger), 0.0, 1.0),
        seq & 0xFFFFFFFF,
        buttons & 0xFFFF,
    )


def unpack(data):
    """Deserialise one control frame.

    Returns a dict, or None if the packet is the wrong size or wrong version.
    Returning None rather than raising keeps the receive loop simple: bad
    packets are dropped and the failsafe handles the gap.
    """
    if len(data) != PACKET_SIZE:
        return None
    version, timestamp, steer, rt, lt, seq, buttons = _PACKET.unpack(data)
    if version != WIRE_VERSION:
        return None
    return {
        "timestamp": timestamp,
        "steer": steer,
        "right_trigger": rt,
        "left_trigger": lt,
        "seq": seq,
        "buttons": buttons,
    }


def pack_rumble(large_motor, small_motor, seq, timestamp, boost=None):
    """Game motor strengths (0..255), acknowledging a recent control frame."""
    values = (seq & 0xFFFFFFFF, timestamp, clamp(int(large_motor), 0, 255),
              clamp(int(small_motor), 0, 255))
    if boost is not None:
        return _RUMBLE_TUNING.pack(RUMBLE_TUNING_MAGIC, *values,
                                  clamp(int(boost), RUMBLE_MIN_BOOST, RUMBLE_MAX_BOOST))
    return _RUMBLE.pack(RUMBLE_MAGIC, *values)


def unpack_rumble(data):
    if len(data) == RUMBLE_TUNING_SIZE:
        magic, seq, timestamp, large, small, boost = _RUMBLE_TUNING.unpack(data)
        if magic != RUMBLE_TUNING_MAGIC or not RUMBLE_MIN_BOOST <= boost <= RUMBLE_MAX_BOOST:
            return None
        return {"seq": seq, "timestamp": timestamp,
                "large_motor": large, "small_motor": small, "boost": boost}
    if len(data) != RUMBLE_SIZE:
        return None
    magic, seq, timestamp, large, small = _RUMBLE.unpack(data)
    if magic != RUMBLE_MAGIC:
        return None
    return {"seq": seq, "timestamp": timestamp,
            "large_motor": large, "small_motor": small}
