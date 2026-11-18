"""Tiny real HEVC Main10+AAC fixtures, independent of HDR Studio's GPU pipeline."""
import shutil
import subprocess

import pytest

from hdrstudio import hevc_sei, platform_lab
from hdrstudio.mp4_tables import validate


def hevc_encoder_available():
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        return False
    result = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"],
                            capture_output=True, text=True, timeout=30)
    return result.returncode == 0 and "libx265" in result.stdout


@pytest.mark.parametrize("trc,expected", [
    ("smpte2084", "PQ"), ("arib-std-b67", "HLG")
])
def test_real_hevc10_aac_and_remux(tmp_path, trc, expected):
    if not hevc_encoder_available():
        pytest.skip("FFmpeg HEVC encoder unavailable")
    source = tmp_path / (expected + ".mp4")
    target = tmp_path / (expected + "_copy.mp4")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
           "-f", "lavfi", "-i", "color=size=64x64:rate=8:duration=0.5",
           "-f", "lavfi", "-i", "sine=frequency=400:duration=0.5",
           "-map", "0:v:0", "-map", "1:a:0",
           "-c:v", "libx265", "-pix_fmt", "yuv420p10le",
           "-x265-params", "log-level=error",
           "-color_primaries", "bt2020", "-color_trc", trc,
           "-colorspace", "bt2020nc", "-c:a", "aac", "-b:a", "64k",
           str(source)]
    subprocess.run(cmd, check=True, timeout=120)
    report = platform_lab.probe(source)
    assert platform_lab.hdr_signaling(report)[0]["declared_mode"] == expected
    assert validate(source)["status"] == "PASS"
    trace = hevc_sei.inspect_hevc(source, max_frames=4)
    assert trace["status"] == "OBSERVED"
    assert "33" in trace["nal_types"]  # HEVC sequence parameter set
    assert platform_lab.remux(source, target)["packet_payloads_equal"]
    assert validate(target)["status"] == "PASS"
    assert platform_lab.packet_digest(source) == platform_lab.packet_digest(target)
