import struct

import pytest

from hdrstudio.platform_lab import compare, inspect_boxes


def test_boxes(tmp_path):
    path = tmp_path / "test.mp4"
    path.write_bytes(struct.pack(">I4s", 8, b"ftyp") +
                     struct.pack(">I4sQ", 1, b"moov", 16) +
                     struct.pack(">I4s", 0, b"mdat") + b"data")
    assert [b["size"] for b in inspect_boxes(path)] == [8, 16, 12]


@pytest.mark.parametrize("data", [b"x", struct.pack(">I4s", 4, b"mdat"),
                                  struct.pack(">I4s", 99, b"mdat"),
                                  struct.pack(">I4s", 1, b"mdat"),
                                  struct.pack(">I4s", 8, b"uuid")])
def test_invalid_boxes(tmp_path, data):
    path = tmp_path / "bad.mp4"
    path.write_bytes(data)
    with pytest.raises(ValueError):
        inspect_boxes(path)


def test_compare_does_not_claim_hdr_preservation():
    before = {"streams": [{"codec_type": "video", "color_transfer": "arib-std-b67"}]}
    after = {"streams": [{"codec_type": "video", "color_transfer": "bt709"}]}
    assert compare(before, after)["signals_equal"] is False
    assert compare(before, before)["hdr_preservation"] == "NOT_VERIFIED"
    assert compare({}, {})["signals_equal"] is None


def test_real_remux(tmp_path):
    import shutil
    import subprocess
    from hdrstudio.platform_lab import remux, packet_digest
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        pytest.skip("FFmpeg unavailable")
    source, output = tmp_path / "source.mp4", tmp_path / "output.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "color=size=64x64:rate=10:duration=1", "-c:v", "mpeg4",
                    str(source)], check=True)
    assert remux(source, output)["packet_payloads_equal"]
    assert packet_digest(source) == packet_digest(output)
    with pytest.raises(FileExistsError):
        remux(source, output)
    with pytest.raises(FileExistsError):
        remux(source, source)


@pytest.mark.parametrize("transfer,fmt,primaries,expected", [
    ("arib-std-b67", "yuv420p10le", "bt2020", "HLG"),
    ("smpte2084", "yuv420p10le", "bt2020", "PQ"),
    ("smpte2084", "yuv420p", "bt2020", "UNKNOWN"),
    ("arib-std-b67", "yuv420p10le", "bt709", "UNKNOWN"),
    ("bt709", "yuv420p", "bt709", "SDR_SIGNAL"),
])
def test_hdr_signaling(transfer, fmt, primaries, expected):
    from hdrstudio.platform_lab import hdr_signaling
    report = {"streams": [{"codec_type": "video", "color_transfer": transfer,
                           "pix_fmt": fmt, "color_primaries": primaries}]}
    assert hdr_signaling(report) == [
        {"declared_mode": expected, "visual_hdr": "NOT_VERIFIED"}]


# ---------------------------------------------------------------- review regressions (PR #8)
import shutil as _shutil
import subprocess as _subprocess

from hdrstudio import platform_lab as _lab
from hdrstudio.media import ToolError, run_checked


def _need_ffmpeg(*encoders):
    if not _shutil.which("ffmpeg") or not _shutil.which("ffprobe"):
        pytest.skip("FFmpeg unavailable")
    if encoders:
        listed = _subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True,
                                 text=True, timeout=30).stdout
        if not all(e in listed for e in encoders):
            pytest.skip("encoder unavailable: " + ", ".join(encoders))


def _ff(*args):
    _subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", *args],
                    check=True, timeout=120)


def test_compare_ignores_reencode_fields_but_reports_them():
    hdr = {"codec_type": "video", "pix_fmt": "yuv420p10le", "color_primaries": "bt2020",
           "color_transfer": "smpte2084", "color_space": "bt2020nc", "color_range": "tv"}
    before = {"streams": [{**hdr, "bit_rate": "40000000", "width": 3840, "height": 2160}]}
    after = {"streams": [{**hdr, "bit_rate": "10000000", "width": 1080, "height": 1920}]}
    result = compare(before, after)
    assert result["signals_equal"] is True
    assert result["stream_changes"][0]["bit_rate"] == ["40000000", "10000000"]
    assert "color_transfer" not in result["stream_changes"][0]


def test_run_checked_reports_stderr_and_hides_console(monkeypatch):
    from hdrstudio import media
    _need_ffmpeg()
    seen = {}
    real = _subprocess.run

    def spy(*args, **kwargs):
        seen.update(kwargs)
        return real(*args, **kwargs)
    monkeypatch.setattr(media.subprocess, "run", spy)
    with pytest.raises(ToolError) as err:
        run_checked(["ffprobe", "-v", "error", "/no/such/file.mp4"], timeout=30)
    assert "No such file" in str(err.value) or "no/such/file" in str(err.value)
    assert seen["creationflags"] == media.NO_WINDOW
    assert isinstance(err.value, RuntimeError)  # handled by the API without a traceback


def test_publish_falls_back_without_hard_links(tmp_path, monkeypatch):
    source, target = tmp_path / "a.mp4", tmp_path / "b.mp4"
    source.write_bytes(b"payload")

    def no_links(*_):
        raise OSError("hard links not supported")
    monkeypatch.setattr(_lab.os, "link", no_links)
    _lab._publish(source, target)
    assert target.read_bytes() == b"payload"
    with pytest.raises(FileExistsError):
        _lab._publish(source, target)


def test_analyze_mkv_and_bad_comparison_keep_the_report(tmp_path):
    _need_ffmpeg()
    mkv, bad = tmp_path / "in.mkv", tmp_path / "published.mp4"
    _ff("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=1", "-c:v", "mpeg4", str(mkv))
    _ff("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=1", "-c:v", "mpeg4",
        "-movflags", "+faststart", str(bad))
    data = bytearray(bad.read_bytes())
    stco = data.find(b"stco")
    data[stco + 12:stco + 16] = (10 ** 9).to_bytes(4, "big")  # first chunk offset beyond the file
    bad.write_bytes(bytes(data))
    report = _lab.analyze(mkv, '"%s"' % bad, frame_signals=True, decode=True)
    assert report["structure"]["status"] == "NOT_APPLICABLE"
    assert report["source"][0]["codec_name"] == "mpeg4"
    assert report["decode_check"]["decode"] == "PASS"
    assert report["comparison"]["after_structure"]["status"] == "FAIL"
    assert report["comparison"]["after"][0]["codec_name"] == "mpeg4"  # probe still reported


def test_remux_drops_unsupported_subtitles(tmp_path):
    _need_ffmpeg()
    subs, mkv, out = tmp_path / "s.srt", tmp_path / "in.mkv", tmp_path / "out.mp4"
    subs.write_text("1\n00:00:00,000 --> 00:00:00,500\nhello\n", encoding="utf-8")
    _ff("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=1", "-i", str(subs),
        "-map", "0", "-map", "1", "-c:v", "mpeg4", "-c:s", "ass", str(mkv))
    result = _lab.remux(mkv, out)
    assert [d["codec_name"] for d in result["dropped"]] == ["ass"]
    assert [s["codec_type"] for s in result["streams"]] == ["video"]
    assert result["content_equal"] and out.is_file()


def test_remux_ts_verifies_reframed_streams_by_content(tmp_path):
    _need_ffmpeg("libx264")
    ts, out = tmp_path / "in.ts", tmp_path / "out.mp4"
    _ff("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=1",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:v", "libx264", "-c:a", "aac", "-shortest", str(ts))
    result = _lab.remux(ts, out)
    assert result["content_equal"] is True
    assert result["packet_payloads_equal"] is False  # Annex B / ADTS are re-framed for MP4
    assert {s["verified_by"] for s in result["streams"]} == {"decoded_content"}


def test_cover_art_is_not_taken_as_the_video(tmp_path):
    _need_ffmpeg("libx265", "mjpeg")
    cover, video, both = tmp_path / "c.jpg", tmp_path / "v.mp4", tmp_path / "both.mp4"
    _ff("-f", "lavfi", "-i", "color=red:size=32x32", "-frames:v", "1", str(cover))
    _ff("-f", "lavfi", "-i", "color=size=64x64:rate=8:duration=0.5", "-c:v", "libx265",
        "-x265-params", "log-level=error", str(video))
    _ff("-i", str(cover), "-i", str(video), "-map", "0", "-map", "1", "-c", "copy",
        "-disposition:v:0", "attached_pic", str(both))
    report = _lab.analyze(both, trace=True, frame_signals=True)
    assert report["source"][0]["codec_name"] == "hevc"
    assert report["hevc_bitstream"]["status"] == "OBSERVED"
    assert report["decoded_signals"]["frames"]
