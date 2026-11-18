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
