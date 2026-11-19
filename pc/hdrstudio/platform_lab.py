"""Local platform diagnostics and standard stream-copy remux. No upload."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from . import mp4_tables
from .media import ToolError, run_checked

# Fields that carry the colour/HDR signal; everything else (bitrate, size, fps, tag) is
# expected to change on any platform re-encode and is reported separately.
COLOR_FIELDS = ("pix_fmt", "color_primaries", "color_transfer", "color_space", "color_range")
SIGNAL_FIELDS = ("codec_name", "profile", "codec_tag_string", "pix_fmt",
                 "width", "height", "color_primaries", "color_transfer",
                 "color_space", "color_range", "avg_frame_rate", "bit_rate")
# Codecs the MP4 muxer can carry with stream copy. Anything else is dropped from a remux
# and listed in the report, never converted (conversion would not be lossless).
MP4_CODECS = {
    "video": {"h264", "hevc", "av1", "vp9", "mpeg4", "mpeg2video", "mpeg1video"},
    "audio": {"aac", "mp3", "mp2", "ac3", "eac3", "alac", "flac", "opus", "dts"},
    "subtitle": {"mov_text"},
}
MAX_LISTED_BOXES = 64


def _tools(ffmpeg=None, ffprobe=None):
    from . import media
    return ffmpeg or media.ffmpeg(), ffprobe or media.ffprobe()


def _file(path):
    path = Path(str(path).strip().strip('"')).resolve(strict=True)
    if not path.is_file():
        raise ValueError(f"not a file: {path}")
    return path


def inspect_boxes(path):
    """Validate top-level ISO BMFF box boundaries, not sample tables."""
    return mp4_tables.top_level_boxes(path)


def structure(path):
    """MP4 structure as a result, never an exception: PASS, FAIL, NOT_VERIFIED or NOT_APPLICABLE."""
    if not mp4_tables.is_iso_bmff(path):
        return {"status": "NOT_APPLICABLE", "reason": "not_iso_bmff_container"}
    try:
        boxes = mp4_tables.top_level_boxes(path)
        result = mp4_tables.validate(path)
    except mp4_tables.LimitError as e:  # beyond the checker's bounds, not a defect in the file
        return {"status": "NOT_VERIFIED", "reason": "checker_limit", "detail": str(e)}
    except mp4_tables.TableError as e:
        return {"status": "FAIL", "error": str(e)}
    result["boxes"] = boxes[:MAX_LISTED_BOXES]
    result["box_count"] = len(boxes)
    return result


def probe(path, ffprobe="ffprobe"):
    result = run_checked([ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json",
                          str(Path(path).resolve())], timeout=60)
    return json.loads(result.stdout)


def video_signals(report):
    return [{key: stream.get(key) for key in SIGNAL_FIELDS}
            for stream in report.get("streams", [])
            if stream.get("codec_type") == "video" and
            not stream.get("disposition", {}).get("attached_pic")]


def compare(before, after):
    """Report observed signals, never infer platform playback or HDR quality."""
    a, b = video_signals(before), video_signals(after)
    colours = lambda streams: [tuple(s.get(k) for k in COLOR_FIELDS) for s in streams]
    changes = [{k: [x.get(k), y.get(k)] for k in SIGNAL_FIELDS
                if k not in COLOR_FIELDS and x.get(k) != y.get(k)} for x, y in zip(a, b)]
    result = {"before": a, "after": b,
              "signals_equal": colours(a) == colours(b) if a and b else None,
              "signals_compared": list(COLOR_FIELDS),
              "stream_changes": changes,
              "hdr_preservation": "NOT_VERIFIED"}
    if len(a) != len(b):
        result["video_stream_count"] = [len(a), len(b)]
    return result


def packet_digest(path, ffprobe="ffprobe"):
    """Hash ordered packet payload hashes for every stream; bounded output memory."""
    cmd = [ffprobe, "-v", "error", "-show_packets", "-show_data_hash", "sha256",
           "-show_entries", "packet=stream_index,data_hash", "-of", "compact=p=0",
           str(Path(path).resolve())]
    hashes = {}
    with tempfile.TemporaryFile() as output:
        run_checked(cmd, stdout=output, timeout=600)
        output.seek(0)
        for line in output:
            fields = dict(part.split(b"=", 1) for part in line.strip().split(b"|") if b"=" in part)
            index, digest = fields.get(b"stream_index"), fields.get(b"data_hash")
            if index is None or digest is None:
                raise ValueError("missing packet hash")
            hashes.setdefault(index, hashlib.sha256()).update(digest + b"\n")
    if not hashes:
        raise ValueError("no packets")
    return {k.decode(): v.hexdigest() for k, v in hashes.items()}


def decoded_digest(path, index, ffmpeg="ffmpeg"):
    """SHA-256 of one stream's fully decoded frames/samples (container framing excluded)."""
    result = run_checked([ffmpeg, "-nostdin", "-v", "error", "-i", str(Path(path).resolve()),
                          "-map", f"0:{index}", "-f", "streamhash", "-hash", "sha256", "-"],
                         timeout=3600)
    line = result.stdout.decode("ascii", "replace").strip().splitlines()[-1:]
    if not line or "SHA256=" not in line[0]:
        raise ValueError("no decoded hash")
    return line[0].split("SHA256=", 1)[1]


def remux_plan(report):
    """Choose the streams MP4 can carry by stream copy; list the rest with a reason."""
    keep, dropped = [], []
    for s in report.get("streams", []):
        kind, codec = s.get("codec_type"), s.get("codec_name")
        reason = None
        if s.get("disposition", {}).get("attached_pic"):
            reason = "attached_picture"
        elif kind not in MP4_CODECS:
            reason = f"{kind}_stream_not_carried"
        elif codec not in MP4_CODECS[kind]:
            reason = "codec_not_supported_in_mp4"
        entry = {"index": s["index"], "codec_type": kind, "codec_name": codec}
        (dropped if reason else keep).append({**entry, **({"reason": reason} if reason else {})})
    # Never report success for an output that lost a whole kind of content: a source with
    # video must keep a video stream, and a source with audio must keep an audio stream.
    for kind in ("video", "audio"):
        lost = [d for d in dropped if d["codec_type"] == kind and d.get("reason") != "attached_picture"]
        if lost and not any(s["codec_type"] == kind for s in keep):
            codecs = ", ".join(sorted({d["codec_name"] or "unknown" for d in lost}))
            raise ValueError(f"MP4 stream copy would drop every {kind} stream ({codecs})")
    if not keep:
        raise ValueError("no stream that MP4 can carry by stream copy")
    return keep, dropped


def _publish(candidate, destination):
    """Exclusive creation: never overwrite. Hard link when possible, else exclusive copy
    (FAT32/exFAT and some network shares have no hard links)."""
    try:
        os.link(candidate, destination)
        return
    except FileExistsError:
        raise
    except OSError:
        pass
    with open(candidate, "rb") as src, open(destination, "xb") as dst:
        try:
            shutil.copyfileobj(src, dst, 1 << 20)
        except BaseException:
            dst.close()
            Path(destination).unlink(missing_ok=True)
            raise


def remux(source, destination, ffmpeg="ffmpeg", ffprobe="ffprobe"):
    """Standard stream copy of the MP4-compatible streams; publish only after every kept
    stream is verified identical (packet payloads, or decoded content when the container
    must re-frame the bitstream, e.g. TS Annex B / ADTS into MP4)."""
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if destination.exists() or source == destination:
        raise FileExistsError(destination)
    if destination.suffix.lower() != ".mp4":
        raise ValueError("destination must be MP4")
    keep, dropped = remux_plan(probe(source, ffprobe))
    maps = [arg for s in keep for arg in ("-map", f"0:{s['index']}")]
    # MKV/TS HEVC would be written as hev1, which QuickTime, iOS and Safari do not play;
    # hvc1 matches the app's own exports and leaves the packet payloads unchanged.
    maps += [arg for i, s in enumerate(keep) if s["codec_name"] == "hevc"
             for arg in (f"-tag:{i}", "hvc1")]
    with tempfile.TemporaryDirectory(dir=destination.parent) as folder:
        candidate = Path(folder) / "output.mp4"
        run_checked([ffmpeg, "-nostdin", "-v", "error", "-n", "-i", str(source), *maps,
                     "-c", "copy", "-movflags", "+faststart", str(candidate)], timeout=600)
        mp4_tables.top_level_boxes(candidate)
        before, after = packet_digest(source, ffprobe), packet_digest(candidate, ffprobe)
        streams = []
        for out_index, s in enumerate(keep):
            entry = {"source_index": s["index"], "output_index": out_index,
                     "codec_type": s["codec_type"], "codec_name": s["codec_name"]}
            if before.get(str(s["index"])) == after.get(str(out_index)):
                entry["verified_by"] = "packet_payload"
            elif s["codec_type"] in ("video", "audio") and \
                    decoded_digest(source, s["index"], ffmpeg) == decoded_digest(candidate, out_index, ffmpeg):
                entry["verified_by"] = "decoded_content"
            else:
                raise ValueError(f"stream {s['index']} ({s['codec_name']}) changed in remux")
            streams.append(entry)
        _publish(candidate, destination)
    return {"packet_payloads_equal": all(s["verified_by"] == "packet_payload" for s in streams),
            "content_equal": True, "streams": streams, "dropped": dropped,
            "output": str(destination), "hdr_preservation": "NOT_VERIFIED"}


def decode_check(path, ffmpeg="ffmpeg"):
    """Decode the entire video locally; not a platform acceptance test."""
    try:
        run_checked([ffmpeg, "-nostdin", "-v", "error", "-xerror",
                     "-i", str(Path(path).resolve()), "-map", "0:V:0",
                     "-f", "null", "-"], timeout=600)
    except ToolError as e:
        return {"decode": "FAIL", "error": str(e), "platform_acceptance": "NOT_VERIFIED"}
    return {"decode": "PASS", "platform_acceptance": "NOT_VERIFIED"}


def hdr_signaling(report):
    """Classify declared stream signals, not actual luminance or SEI validity."""
    result = []
    for stream in video_signals(report):
        transfer = stream["color_transfer"]
        wide = stream["color_primaries"] == "bt2020"
        ten_bit = stream["pix_fmt"] in ("yuv420p10le", "yuv420p10be", "p010le", "p010be")
        mode = "UNKNOWN"
        if wide and ten_bit and transfer in ("smpte2084", "arib-std-b67"):
            mode = "PQ" if transfer == "smpte2084" else "HLG"
        elif transfer == "bt709":
            mode = "SDR_SIGNAL"
        result.append({"declared_mode": mode, "visual_hdr": "NOT_VERIFIED"})
    return result


def decoded_signals(path, ffprobe="ffprobe"):
    """Read decoder-reported frame properties separately from stream declarations."""
    cmd = [ffprobe, "-v", "error", "-select_streams", "V:0",
           "-read_intervals", "%+#32", "-show_frames",
           "-show_entries", "frame=pix_fmt,color_space,color_primaries,color_transfer,side_data_list",
           "-of", "json", str(Path(path).resolve())]
    frames = json.loads(run_checked(cmd, timeout=60).stdout).get("frames", [])
    return {"frames": frames, "scope": "first 32 packets only",
            "independent_bitstream_validation": "NOT_VERIFIED"}


def trace_headers(path, ffmpeg="ffmpeg"):
    """Inspect coded headers via FFmpeg trace_headers, without rewriting input."""
    cmd = [ffmpeg, "-nostdin", "-v", "info", "-i", str(Path(path).resolve()),
           "-map", "0:V:0", "-c:v", "copy", "-bsf:v", "trace_headers",
           "-frames:v", "1", "-f", "null", "-"]
    keys = ("colour_primaries", "transfer_characteristics", "matrix_coefficients",
            "bit_depth_luma_minus8", "bit_depth_chroma_minus8")
    observed = []
    with tempfile.TemporaryFile() as log:
        run_checked(cmd, stdout=subprocess.DEVNULL, stderr=log, timeout=60)
        log.seek(0)
        for line in log:
            if b"trace_headers" in line and any(key.encode() in line for key in keys):
                if len(observed) >= 256:
                    raise ValueError("header trace exceeds limit")
                observed.append(line.decode("utf-8", errors="replace").strip())
    return {"header_lines": observed, "scope": "initial coded headers and first packet",
            "status": "OBSERVED" if observed else "NOT_VERIFIED",
            "full_stream_hdr_validation": "NOT_VERIFIED"}


def _section(fn, *args):
    """One failing optional check is recorded, not allowed to sink the whole report."""
    try:
        return fn(*args)
    except (ToolError, ValueError) as e:
        return {"status": "ERROR", "error": str(e)}


def _hevc_bitstream(path, streams, ffmpeg):
    if not streams or streams[0]["codec_name"] != "hevc":
        return {"status": "NOT_VERIFIED", "reason": "not_hevc"}
    from . import hevc_sei
    return _section(hevc_sei.inspect_hevc, path, ffmpeg)


def analyze(path, compare_path=None, *, trace=False, frame_signals=False, decode=False,
            ffmpeg=None, ffprobe=None):
    """The one Platform Lab report, shared by the desktop UI and the CLI."""
    ffmpeg, ffprobe = _tools(ffmpeg, ffprobe)
    path = _file(path)
    original = probe(path, ffprobe)
    streams = video_signals(original)
    report = {"structure": structure(path), "source": streams,
              "hdr_signaling": hdr_signaling(original)}
    if trace:
        report["coded_headers"] = _section(trace_headers, path, ffmpeg)
        report["hevc_bitstream"] = _hevc_bitstream(path, streams, ffmpeg)
    if frame_signals:
        report["decoded_signals"] = _section(decoded_signals, path, ffprobe)
    if decode:
        report["decode_check"] = decode_check(path, ffmpeg)
    if compare_path and str(compare_path).strip().strip('"'):
        # The comparison file (often a downloaded platform copy) may be missing or unreadable;
        # that must not discard the source report.
        try:
            other_path = _file(compare_path)
            other = probe(other_path, ffprobe)
        except (ToolError, ValueError, OSError) as e:
            report["comparison"] = {"status": "ERROR", "error": str(e),
                                    "hdr_preservation": "NOT_VERIFIED"}
            return report
        comparison = compare(original, other)
        comparison["after_hdr_signaling"] = hdr_signaling(other)
        comparison["after_structure"] = structure(other_path)
        if trace:
            comparison["after_bitstream"] = _hevc_bitstream(other_path, video_signals(other), ffmpeg)
        report["comparison"] = comparison
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("--compare")
    parser.add_argument("--decode-check", action="store_true")
    parser.add_argument("--frame-signals", action="store_true")
    parser.add_argument("--trace-headers", action="store_true")
    parser.add_argument("--remux", help="standard lossless MP4 copy")
    parser.add_argument("--ffmpeg")
    parser.add_argument("--ffprobe")
    args = parser.parse_args(argv)
    ffmpeg, ffprobe = _tools(args.ffmpeg, args.ffprobe)
    report = analyze(args.input, args.compare, trace=args.trace_headers,
                     frame_signals=args.frame_signals, decode=args.decode_check,
                     ffmpeg=ffmpeg, ffprobe=ffprobe)
    code = 0
    if args.remux:
        try:
            report["remux"] = remux(args.input, args.remux, ffmpeg, ffprobe)
        except (ToolError, ValueError, FileExistsError) as e:
            report["remux"] = {"status": "FAIL", "error": str(e)}
            code = 1
    print(json.dumps(report, indent=2, ensure_ascii=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
