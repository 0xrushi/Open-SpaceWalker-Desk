"""SpaceDesk wire protocol — Python mirror of the Kotlin :shared module.

Control channel: TCP, newline-delimited JSON, discriminated on "type".
Video channel:   UDP, 16-byte big-endian header + H.264 payload chunks.

This must stay byte-compatible with shared/src/main/java/.../VideoPacket.kt
and ControlMessages.kt (kotlinx.serialization with classDiscriminator="type").
"""

from __future__ import annotations

import json
import struct
from typing import Callable, Dict, List, Optional, Tuple

PROTOCOL_VERSION = 1
DEFAULT_CONTROL_PORT = 53210
NSD_SERVICE_TYPE = "_spacedesk._tcp.local."  # zeroconf form of "_spacedesk._tcp."
MAX_VIDEO_PAYLOAD = 1200
FRAME_REORDER_WINDOW = 8

MAGIC = 0x5D5C
HEADER_SIZE = 16
FLAG_KEYFRAME = 0x01
FLAG_CONFIG = 0x02

# magic u16 | flags u8 | reserved u8 | frameId u32 | seq u16 | total u16 | payloadLen u16 | reserved u16
_HEADER = struct.Struct(">HBBIHHHH")


# ---------------------------------------------------------------- control ---

def encode_msg(msg: dict) -> bytes:
    """Serializes a control message dict to one newline-terminated JSON line."""
    return (json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8")


def decode_msg(line: str) -> Optional[dict]:
    try:
        msg = json.loads(line)
        return msg if isinstance(msg, dict) and "type" in msg else None
    except (json.JSONDecodeError, ValueError):
        return None


def hello(device_name: str) -> dict:
    return {"type": "hello", "protocolVersion": PROTOCOL_VERSION, "deviceName": device_name}


def hello_ack(host_name: str, width: int, height: int, input_allowed: bool) -> dict:
    return {
        "type": "hello_ack",
        "protocolVersion": PROTOCOL_VERSION,
        "hostName": host_name,
        "screenWidth": width,
        "screenHeight": height,
        "inputAllowed": input_allowed,
    }


def start_stream(udp_port: int, max_w: int, max_h: int, fps: int = 30,
                 bitrate: int = 6_000_000) -> dict:
    return {
        "type": "start_stream",
        "videoUdpPort": udp_port,
        "maxWidth": max_w,
        "maxHeight": max_h,
        "fps": fps,
        "bitrateBps": bitrate,
    }


def video_config(width: int, height: int, fps: int) -> dict:
    return {"type": "video_config", "width": width, "height": height, "fps": fps}


def touch_event(action: str, x: float, y: float, time_ms: int,
                pointer_id: int = 0, pressure: float = 1.0) -> dict:
    """action: DOWN | MOVE | UP | CANCEL; x/y normalized 0..1 in stream space."""
    return {
        "type": "touch",
        "action": action,
        "pointerId": pointer_id,
        "x": x,
        "y": y,
        "pressure": pressure,
        "timeMs": time_ms,
    }


def nav_event(action: str) -> dict:
    """action: BACK | HOME | RECENTS"""
    return {"type": "nav", "action": action}


def text_event(text: str) -> dict:
    return {"type": "text", "text": text}


def input_msg(events: List[dict]) -> dict:
    return {"type": "input", "events": events}


def bye(reason: str = "") -> dict:
    return {"type": "bye", "reason": reason}


# ------------------------------------------------------------------ video ---

def packetize(frame: bytes, frame_id: int, keyframe: bool, config: bool) -> List[bytes]:
    """Splits one encoded frame into ready-to-send UDP datagrams."""
    flags = (FLAG_KEYFRAME if keyframe else 0) | (FLAG_CONFIG if config else 0)
    total = max(1, (len(frame) + MAX_VIDEO_PAYLOAD - 1) // MAX_VIDEO_PAYLOAD)
    packets = []
    for seq in range(total):
        chunk = frame[seq * MAX_VIDEO_PAYLOAD:(seq + 1) * MAX_VIDEO_PAYLOAD]
        header = _HEADER.pack(MAGIC, flags, 0, frame_id & 0xFFFFFFFF, seq, total, len(chunk), 0)
        packets.append(header + chunk)
    return packets


def parse_header(data: bytes) -> Optional[Tuple[int, int, int, int, int]]:
    """Returns (flags, frame_id, seq, total, payload_len) or None if invalid."""
    if len(data) < HEADER_SIZE:
        return None
    magic, flags, _, frame_id, seq, total, payload_len, _ = _HEADER.unpack_from(data)
    if magic != MAGIC or seq >= total or HEADER_SIZE + payload_len > len(data):
        return None
    return flags, frame_id, seq, total, payload_len


class FrameAssembler:
    """Reassembles frames from out-of-order/lossy UDP packets.

    Calls on_frame(frame_bytes, keyframe, config) for each complete frame.
    Stale incomplete frames are garbage-collected.
    """

    def __init__(self, on_frame: Callable[[bytes, bool, bool], None]):
        self._on_frame = on_frame
        self._pending: Dict[int, dict] = {}
        self._newest = -1

    def on_packet(self, data: bytes) -> None:
        parsed = parse_header(data)
        if parsed is None:
            return
        flags, frame_id, seq, total, payload_len = parsed
        self._newest = max(self._newest, frame_id)

        entry = self._pending.setdefault(
            frame_id, {"parts": [None] * total, "received": 0, "flags": flags},
        )
        if len(entry["parts"]) != total:
            return  # corrupt / mismatched
        if entry["parts"][seq] is None:
            entry["parts"][seq] = data[HEADER_SIZE:HEADER_SIZE + payload_len]
            entry["received"] += 1

        if entry["received"] == total:
            del self._pending[frame_id]
            self._on_frame(
                b"".join(entry["parts"]),
                bool(entry["flags"] & FLAG_KEYFRAME),
                bool(entry["flags"] & FLAG_CONFIG),
            )

        if len(self._pending) > FRAME_REORDER_WINDOW:
            cutoff = self._newest - FRAME_REORDER_WINDOW
            for fid in [f for f in self._pending if f < cutoff]:
                del self._pending[fid]


# ----------------------------------------------------------------- H.264 ----

def split_nals(au: bytes):
    """Yields (nal_type, nal_bytes_with_start_code) for an Annex-B access unit."""
    i = 0
    starts = []
    while True:
        pos3 = au.find(b"\x00\x00\x01", i)
        if pos3 == -1:
            break
        start = pos3 - 1 if pos3 > 0 and au[pos3 - 1] == 0 else pos3
        starts.append((start, pos3 + 3))
        i = pos3 + 3
    for idx, (start, body) in enumerate(starts):
        end = starts[idx + 1][0] if idx + 1 < len(starts) else len(au)
        yield au[body] & 0x1F, au[start:end]


def extract_config(au: bytes) -> Optional[bytes]:
    """Pulls SPS+PPS NALs out of an access unit, or None if absent."""
    parts = [nal for t, nal in split_nals(au) if t in (7, 8)]
    return b"".join(parts) if parts else None
