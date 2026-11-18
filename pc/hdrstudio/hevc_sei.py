"""Independent limited HEVC NAL/SEI inspection of extracted elementary stream.

Examines a bounded sample, not full-video conformance or display correctness.
"""
from __future__ import annotations

from pathlib import Path
import re
import subprocess
import tempfile

MAX_BYTES = 64 * 1024 * 1024
START = re.compile(b"\x00\x00\x00?\x01")


def nal_units(payload):
    marks = list(START.finditer(payload))
    for i, match in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(payload)
        nal = payload[match.end():end].rstrip(b"\x00")
        if len(nal) >= 2:
            yield nal


def unescape_rbsp(data):
    result = bytearray()
    zeros = 0
    for byte in data:
        if zeros >= 2 and byte == 3:
            zeros = 0
            continue
        result.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(result)


def parse_sei(data):
    """Parse SEI payload envelopes, including mastering display and CLL."""
    rbsp = unescape_rbsp(data)
    pos = 0
    events = []
    while pos < len(rbsp):
        if rbsp[pos:] in (b"\x80", b""):
            break
        kind = length = 0
        while pos < len(rbsp) and rbsp[pos] == 255:
            kind += 255
            pos += 1
        if pos >= len(rbsp):
            break
        kind += rbsp[pos]
        pos += 1
        while pos < len(rbsp) and rbsp[pos] == 255:
            length += 255
            pos += 1
        if pos >= len(rbsp):
            break
        length += rbsp[pos]
        pos += 1
        if length > len(rbsp) - pos:
            raise ValueError("truncated SEI payload")
        body = rbsp[pos:pos + length]
        pos += length
        if kind == 137 and len(body) >= 24:
            events.append({"type": "mastering_display_colour_volume"})
        elif kind == 144 and len(body) >= 4:
            events.append({"type": "content_light_level",
                           "max_cll": int.from_bytes(body[:2], "big"),
                           "max_fall": int.from_bytes(body[2:4], "big")})
        elif kind == 4 and len(body) >= 3 and body[:3] == b"\xb5\x00\x3c":
            events.append({"type": "itu_t_t35_provider_0x003c",
                           "hdr10plus": "CANDIDATE_NOT_VALIDATED"})
        if len(events) > 4096:
            raise ValueError("too many SEI messages")
    return events


def inspect_hevc(path, ffmpeg="ffmpeg", max_frames=8):
    if not 1 <= max_frames <= 16:
        raise ValueError("max_frames must be 1..16")
    cmd = [ffmpeg, "-nostdin", "-v", "error", "-i", str(Path(path).resolve()),
           "-map", "0:v:0", "-c:v", "copy", "-bsf:v", "hevc_mp4toannexb",
           "-frames:v", str(max_frames), "-f", "hevc", "-"]
    with tempfile.TemporaryFile() as stream:
        subprocess.run(cmd, stdout=stream, check=True, timeout=120)
        length = stream.tell()
        if length > MAX_BYTES:
            return {"status": "NOT_VERIFIED", "reason": "sample_exceeded_64MiB"}
        stream.seek(0)
        payload = stream.read(MAX_BYTES + 1)
    types, messages = {}, []
    for nal in nal_units(payload):
        kind = (nal[0] >> 1) & 63
        types[str(kind)] = types.get(str(kind), 0) + 1
        if kind in (39, 40):
            messages.extend(parse_sei(nal[2:]))
    return {"status": "OBSERVED" if types else "NOT_VERIFIED",
            "scope": f"up to {max_frames} encoded frames",
            "nal_types": types, "sei": messages,
            "sps_vui_colorimetry": "NOT_VERIFIED",
            "whole_video_hdr": "NOT_VERIFIED",
            "visual_hdr": "NOT_VERIFIED"}
