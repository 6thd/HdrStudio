"""Read-only platform diagnostics. No upload or container mutation."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import subprocess


def inspect_boxes(path):
    """Validate top-level ISO BMFF box boundaries, not sample tables."""
    size = Path(path).stat().st_size
    boxes = []
    with open(path, "rb") as stream:
        offset = 0
        while offset < size:
            if size - offset < 8:
                raise ValueError("truncated box header")
            stream.seek(offset)
            length, kind = struct.unpack(">I4s", stream.read(8))
            header = 8
            if length == 1:
                if size - offset < 16:
                    raise ValueError("truncated extended size")
                length = struct.unpack(">Q", stream.read(8))[0]
                header = 16
            elif length == 0:
                length = size - offset
            if kind == b"uuid":
                header += 16
            if length < header or length > size - offset:
                raise ValueError("invalid box boundary")
            if len(boxes) >= 100000:
                raise ValueError("too many boxes")
            boxes.append({"type": kind.decode("latin-1"), "offset": offset, "size": length})
            offset += length
    return boxes


def probe(path, ffprobe="ffprobe"):
    result = subprocess.run(
        [ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json",
         str(Path(path).resolve())], capture_output=True, check=True, timeout=60)
    return json.loads(result.stdout)


def video_signals(report):
    fields = ("codec_name", "profile", "codec_tag_string", "pix_fmt",
              "width", "height", "color_primaries", "color_transfer",
              "color_space", "color_range", "avg_frame_rate", "bit_rate")
    return [{key: stream.get(key) for key in fields}
            for stream in report.get("streams", [])
            if stream.get("codec_type") == "video" and
            not stream.get("disposition", {}).get("attached_pic")]


def compare(before, after):
    """Report observed signals, never infer platform playback or HDR quality."""
    a, b = video_signals(before), video_signals(after)
    return {"before": a, "after": b,
            "signals_equal": a == b if a and b else None,
            "hdr_preservation": "NOT_VERIFIED"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("--compare")
    parser.add_argument("--ffprobe", default="ffprobe")
    args = parser.parse_args(argv)
    report = {"boxes": inspect_boxes(args.input),
              "probe": probe(args.input, args.ffprobe)}
    if args.compare:
        report["comparison"] = compare(report["probe"], probe(args.compare, args.ffprobe))
    print(json.dumps(report, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
