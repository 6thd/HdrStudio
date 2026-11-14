"""End-to-end export through the real pipeline: GPU shaders, FFmpeg encode, mux, verification,
and resuming after the process is killed. Needs FFmpeg and an OpenGL 3.3 context, so it is
skipped on machines without them (hosted CI runners have no GPU)."""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import pytest

from hdrstudio import media, rife

ROOT = Path(__file__).resolve().parent.parent
FRAMES, FPS, SIZE = 150, 30, "320x180"


def _have_ffmpeg():
    try:
        media.ffmpeg(), media.ffprobe()
        return True
    except FileNotFoundError:
        return False


def _have_gl():
    try:
        import moderngl
        moderngl.create_standalone_context(require=330).release()
        return True
    except Exception:
        return False


pytestmark = [
    pytest.mark.skipif(sys.platform != "win32", reason="the resume test kills a Windows process tree"),
    pytest.mark.skipif(not _have_ffmpeg(), reason="FFmpeg not installed"),
    pytest.mark.skipif(not _have_gl(), reason="no OpenGL 3.3 context"),
]


@pytest.fixture(scope="module")
def clip(tmp_path_factory):
    """A moving test pattern with a hard scene cut and a tone, 5 s at 30 fps."""
    out = tmp_path_factory.mktemp("src") / "source.mp4"
    half = FRAMES / FPS / 2
    r = media.run([media.ffmpeg(), "-v", "error", "-y",
                   "-f", "lavfi", "-i", f"testsrc2=s={SIZE}:r={FPS}:d={half}",
                   "-f", "lavfi", "-i", f"mandelbrot=s={SIZE}:r={FPS}", "-f", "lavfi", "-i", f"sine=f=440:d={half * 2}",
                   "-filter_complex", f"[1:v]trim=duration={half},setpts=PTS-STARTPTS[b];[0:v][b]concat=n=2:v=1[v]",
                   "-map", "[v]", "-map", "2:a", "-c:v", "libx264", "-crf", "12", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(out)])
    assert out.exists(), r.stderr.decode("utf-8", "replace")[-500:]
    return out


def _cmd(src, out, *extra):
    return [sys.executable, "-m", "hdrstudio", "export", str(src), "-o", str(out), "--quality", "off", *extra]


def _export(src, out, *extra):
    r = subprocess.run(_cmd(src, out, *extra), cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=900)
    lines = r.stdout.splitlines()
    assert "{" in lines, r.stdout[-1500:] + r.stderr[-1500:]
    start = len(lines) - 1 - lines[::-1].index("{")
    return json.loads("\n".join(lines[start:]))


def _luma(path):
    d = media.Decoder(media.probe(str(path)))
    frames = []
    while (f := d.read()) is not None:
        frames.append(f[0].astype(np.float32).copy())
    d.close()
    return frames


def _psnr(a, b, peak):
    return [10 * np.log10(peak ** 2 / max(1e-9, float(((x - y) ** 2).mean()))) for x, y in zip(a, b)]


def test_hdr10_export_is_valid(clip, tmp_path):
    rep = _export(clip, tmp_path / "hdr.mp4", "--preset", "tiktok_hdr")
    assert rep["ok"], rep["problems"]
    assert rep["frames"] == rep["expectedFrames"] == FRAMES
    assert (rep["primaries"], rep["transfer"], rep["matrix"], rep["range"]) == ("bt2020", "smpte2084", "bt2020nc", "tv")
    assert rep["audio"] == "aac"
    assert rep["seiPatched"] >= 1 and 1 <= rep["maxFall"] <= rep["maxCll"] <= 1000
    si = media.probe(str(tmp_path / "hdr.mp4"))
    assert si.is_hdr and si.bit_depth == 10 and si.max_cll == rep["maxCll"]
    assert not Path(str(tmp_path / "hdr.mp4") + ".hdrjob").exists()        # job directory removed on success
    t = rep["timing"]                                                     # where the time went, for every export
    assert t["framesThisRun"] == FRAMES and t["fps"] > 0 and t["peakMemoryMB"] > 0
    assert t["decode"] > 0 and t["encode"] > 0 and t["ai"] == 0 and t["interpolation"] == 0


def test_hlg_export_is_valid(clip, tmp_path):
    rep = _export(clip, tmp_path / "hlg.mp4", "--preset", "tiktok_hlg")
    assert rep["ok"], rep["problems"]
    assert (rep["primaries"], rep["transfer"], rep["matrix"]) == ("bt2020", "arib-std-b67", "bt2020nc")
    assert rep["frames"] == FRAMES and media.probe(str(tmp_path / "hlg.mp4")).transfer == 2


def test_hdr10plus_on_every_frame_and_per_scene(clip, tmp_path):
    out = tmp_path / "dyn.mp4"
    rep = _export(clip, out, "--preset", "tiktok_hdr", "--set", "hdr10plus=true")
    assert rep["ok"], rep["problems"]
    assert rep["hdr10plus"]["maxSclNits"] > 0 and rep["seiPatched"] >= 1
    r = media.run([media.ffprobe(), "-v", "error", "-select_streams", "v:0", "-show_frames", "-show_entries", "frame=side_data_list",
                   "-print_format", "json", str(out)])
    keys = []
    for fr in json.loads(r.stdout)["frames"]:
        sd = [s for s in fr.get("side_data_list", []) if "2094-40" in s.get("side_data_type", "")]
        assert sd, "a frame without HDR10+ metadata"
        keys.append((sd[0]["maxscl"], sd[0]["average_maxrgb"]))
    assert len(keys) == FRAMES
    changes = [i for i in range(1, FRAMES) if keys[i] != keys[i - 1]]
    assert changes == [FRAMES // 2], "metadata must change at the scene cut and nowhere else"


def test_relative_output_path(clip, tmp_path):
    """`-o exports/out.mp4` from another working directory, with and without HDR10+ metadata."""
    env = {**os.environ, "PYTHONPATH": str(ROOT)}
    for name, extra in (("plain.mp4", []), ("dyn.mp4", ["--set", "hdr10plus=true"])):
        r = subprocess.run([sys.executable, "-m", "hdrstudio", "export", str(clip), "-o", f"exports/{name}", "--quality", "off",
                            "--duration", "1", *extra], cwd=tmp_path, env=env, capture_output=True, text=True, encoding="utf-8", timeout=600)
        assert (tmp_path / "exports" / name).exists(), r.stdout[-800:]
        assert '"ok": true' in r.stdout
    assert not (tmp_path / "exports" / "exports").exists()


def test_sdr_export_is_valid(clip, tmp_path):
    rep = _export(clip, tmp_path / "sdr.mp4", "--preset", "tiktok_sdr")
    assert rep["ok"], rep["problems"]
    assert rep["frames"] == FRAMES and rep["transfer"] == "bt709" and rep["primaries"] == "bt709"


@pytest.mark.skipif(not rife.available(), reason="RIFE weights not present")
def test_frame_doubling_keeps_duration(clip, tmp_path):
    rep = _export(clip, tmp_path / "x2.mp4", "--preset", "tiktok_hdr", "--fps", "x2", "--duration", "2")
    assert rep["ok"], rep["problems"]
    assert rep["frames"] == 2 * 2 * FPS
    assert abs(rep["duration"] - 2.0) < 0.1


def test_resume_after_kill_matches_uninterrupted(clip, tmp_path):
    out_a, out_b = tmp_path / "resumed.mp4", tmp_path / "straight.mp4"
    p = subprocess.Popen(_cmd(clip, out_a, "--preset", "tiktok_hdr"), cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    state = Path(str(out_a) + ".hdrjob") / "state.json"
    deadline = time.time() + 600
    while p.poll() is None and not state.exists() and time.time() < deadline:
        time.sleep(0.02)
    if p.poll() is not None:
        pytest.skip("the export finished before it could be interrupted on this machine")
    subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
    p.wait()
    done = sum(c["frames"] for c in json.loads(state.read_text("utf-8"))["chunks"])
    assert 0 < done < FRAMES and not out_a.exists()

    rep = _export(clip, out_a, "--preset", "tiktok_hdr")                    # resumes from the job directory
    assert rep["ok"], rep["problems"]
    assert rep["frames"] == FRAMES
    assert _export(clip, out_b, "--preset", "tiktok_hdr")["ok"]

    a, b = _luma(out_a), _luma(out_b)
    assert len(a) == len(b) == FRAMES
    psnr = _psnr(a, b, 1023)
    assert min(psnr[:done]) > 60, "frames recorded before the kill must be unchanged"
    assert min(psnr[done:]) > 40, "frames after the resume point must match an uninterrupted export"
