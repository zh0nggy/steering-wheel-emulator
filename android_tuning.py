"""Optional, acknowledged Android tuning channel alongside v3 driving frames."""

from dataclasses import dataclass
import math
import struct

DEFAULTS = {"range": 45.0, "deadzone": 0.05, "gamma": 1.5, "smooth": 0.0,
            "rumble_boost": 150, "invert": False, "game_rumble": True}
HELLO_MAGIC = b"TWTHI1"
PHONE_MAGIC = b"TWTPH1"
WINDOWS_MAGIC = b"TWTPC1"
_HELLO = struct.Struct("!6s16s")  # Fits even a legacy receiver's driving-frame buffer.
_PHONE = struct.Struct("!6s16sqBBHBBB")
_WINDOWS = struct.Struct("!6s16sqqBBHBBB")
_LIMITS = ((10, 90), (0, 30), (100, 300), (0, 90), (100, 250))
_NUMBERS = (("range", 1), ("deadzone", 100), ("gamma", 100),
            ("smooth", 100), ("rumble_boost", 1))


def _values(settings):
    values = []
    for (key, scale), (low, high) in zip(_NUMBERS, _LIMITS):
        value = settings.get(key, DEFAULTS[key])
        if not isinstance(value, (int, float)) or not math.isfinite(value):
            value = DEFAULTS[key]
        values.append(round(min(high, max(low, value * scale))))
    flags = int(bool(settings.get("invert", False))) | (int(bool(settings.get("game_rumble", True))) << 1)
    return (*values, flags)


def _settings(values):
    if any(not low <= value <= high for value, (low, high) in zip(values, _LIMITS)):
        return None
    if values[5] not in range(4):
        return None
    settings = {key: value / scale for (key, scale), value in zip(_NUMBERS, values)}
    settings.update(invert=bool(values[5] & 1), game_rumble=bool(values[5] & 2))
    return settings


@dataclass(frozen=True)
class Request:
    session: bytes
    revision: int = 0
    settings: dict | None = None


@dataclass(frozen=True)
class Reply:
    session: bytes
    acknowledged: int
    revision: int
    settings: dict


def unpack_request(data):
    if len(data) == _HELLO.size:
        magic, session = _HELLO.unpack(data)
        return Request(session) if magic == HELLO_MAGIC else None
    if len(data) != _PHONE.size:
        return None
    magic, session, revision, *values = _PHONE.unpack(data)
    settings = _settings(values)
    if magic != PHONE_MAGIC or revision < 0 or settings is None:
        return None
    return Request(session, revision, settings)


def pack_reply(reply):
    return _WINDOWS.pack(WINDOWS_MAGIC, reply.session, reply.acknowledged,
                         reply.revision, *_values(reply.settings))


class TuningSync:
    """Apply each phone edit once, so retries cannot undo later Windows edits."""

    def __init__(self):
        self.session = None
        self.acknowledged = -1
        self.revision = 0
        self.previous = None

    def sync(self, request, settings):
        if request is None:
            return None
        current = _settings(_values(settings))
        if request.session != self.session:
            self.session = request.session
            self.acknowledged = -1
            self.revision = 0
            self.previous = None
        if request.revision > self.acknowledged:
            self.acknowledged = request.revision
            if request.revision > 0 and request.settings is not None:
                current = dict(request.settings)
        state = (self.acknowledged, current)
        if state != self.previous:
            self.revision += 1
            self.previous = state
        return Reply(self.session, self.acknowledged, self.revision, current)
