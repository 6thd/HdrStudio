import json

import numpy as np

from hdrstudio import hdr10plus as hp
from hdrstudio import media


def pq(nits):
    y = np.asarray(nits, np.float64) / 10000
    return ((0.8359375 + 18.8515625 * y ** 0.1593017578125) / (1 + 18.6875 * y ** 0.1593017578125)) ** 78.84375


def frame(nits_rgb, n=4096):
    return np.tile(pq(nits_rgb).astype(np.float32), (n, 1))


def test_pq_round_trip():
    for v in (0.1, 1, 100, 203, 1000, 4000):
        assert abs(hp.pq_to_nits(pq(v)) - v) / v < 1e-6


def test_scene_values_in_tenths_of_a_nit():
    dark, bright = hp.FrameStats(frame([50, 100, 20])), hp.FrameStats(frame([300, 100, 800]))
    e = hp.scene_entry([dark, bright])["LuminanceParameters"]
    assert e["MaxScl"] == [3000, 1000, 8000]                         # per-channel maximum over the scene
    assert abs(e["AverageRGB"] - (100 + 800) / 2 * 10) <= 10          # mean of max(R,G,B)
    d = e["LuminanceDistributions"]
    assert d["DistributionIndex"] == hp.PERCENTILES and len(d["DistributionValues"]) == 9
    assert d["DistributionValues"] == sorted(d["DistributionValues"])
    assert abs(d["DistributionValues"][0] - 1000) < 30 and abs(d["DistributionValues"][-1] - 8000) < 200


def test_values_capped_at_10000_nits():
    e = hp.scene_entry([hp.FrameStats(np.ones((16, 3), np.float32))])["LuminanceParameters"]
    assert e["MaxScl"] == [100000] * 3 and max(e["LuminanceDistributions"]["DistributionValues"]) <= 100000


def test_scenes_split_at_cuts_only():
    stats = [hp.FrameStats(frame([100, 100, 100]))] * 3 + [hp.FrameStats(frame([900, 900, 900]))] * 2
    meta = hp.build(stats, [False, False, False, True, False])
    assert meta["frameScene"] == [0, 0, 0, 1, 1] and len(meta["scenes"]) == 2
    assert meta["scenes"][0]["LuminanceParameters"]["MaxScl"][0] == 1000
    assert meta["scenes"][1]["LuminanceParameters"]["MaxScl"][0] == 9000


def test_chunk_file_structure(tmp_path):
    stats = [hp.FrameStats(frame([100, 100, 100]))] * 3 + [hp.FrameStats(frame([900, 900, 900]))] * 2
    meta = hp.build(stats, [False, False, False, True, False])
    f = tmp_path / "c.json"
    # frame doubling: every source frame appears twice; the chunk starts inside the first scene
    hp.chunk_json(meta, [1, 1, 2, 2, 3, 3, 4, 4, 9], f)
    doc = json.loads(f.read_text("utf-8"))
    assert doc["JSONInfo"] == {"HDR10plusProfile": "A", "Version": "1.0"}
    info = doc["SceneInfo"]
    assert [e["SequenceFrameIndex"] for e in info] == list(range(9))
    assert [e["SceneId"] for e in info] == [0, 0, 0, 0, 1, 1, 1, 1, 1]
    assert [e["SceneFrameIndex"] for e in info] == [0, 1, 2, 3, 0, 1, 2, 3, 4]
    assert doc["SceneInfoSummary"] == {"SceneFirstFrameIndex": [0, 4], "SceneFrameNumbers": [4, 5]}
    assert info[-1]["LuminanceParameters"]["MaxScl"][0] == 9000        # index past the end uses the last frame
    assert "BezierCurveData" not in info[0] and info[0]["NumberOfWindows"] == 1


def test_statistics_are_kept_per_scene_not_per_frame():
    """An hour of video is ~100000 frames: the analysis must not keep a histogram for each."""
    import tracemalloc
    acc = hp.Accumulator()
    one = frame([120, 80, 40], n=64)
    for i in range(50):                                # warm up allocator and caches
        acc.add(hp.FrameStats(one), cut=False)
    tracemalloc.start()
    before = tracemalloc.get_traced_memory()[0]
    for i in range(4000):
        acc.add(hp.FrameStats(one), cut=(i == 2000))
    grown = tracemalloc.get_traced_memory()[0] - before
    tracemalloc.stop()
    meta = acc.finish()
    assert len(meta["scenes"]) == 2 and len(meta["frameScene"]) == 4050
    assert grown < 400_000, f"{grown} bytes for 4000 frames (8 KiB per frame would be 32 MB)"


def test_accumulator_matches_scene_entry():
    a, b = hp.FrameStats(frame([50, 100, 20])), hp.FrameStats(frame([300, 100, 800]))
    acc = hp.Accumulator()
    acc.add(a, False)
    acc.add(b, False)
    assert acc.finish()["scenes"][0] == hp.scene_entry([a, b])


def test_analysis_size():
    assert hp.analysis_size(3840, 2160) == (960, 540)
    assert hp.analysis_size(1080, 1920) == (540, 960)
    assert hp.analysis_size(478, 850) == (478, 850)                    # never enlarged


def test_encoder_arguments_for_hlg_and_hdr10plus():
    from fractions import Fraction
    media._exe["ffmpeg"] = media._exe.get("ffmpeg", "ffmpeg")
    cfg = dict(width=1920, height=1080, fps=Fraction(30), codec="hevc", hdr=True, bit_depth=10, crf=15, preset="slow", peak=1000.0)
    hlg = media.encoder_args(media.EncodeConfig(**cfg, trc="hlg"), "o.mp4")
    x = hlg[hlg.index("-x265-params") + 1].split(":")
    assert "transfer=arib-std-b67" in x and "colorprim=bt2020" in x
    assert "hdr10=1" not in x and not any(p.startswith(("max-cll", "master-display", "dhdr10")) for p in x)
    assert hlg[hlg.index("-color_trc") + 1] == "arib-std-b67"
    dyn = media.encoder_args(media.EncodeConfig(**cfg), "o.mp4", "chunk_00000.json")
    assert "dhdr10-info=chunk_00000.json" in dyn[dyn.index("-x265-params") + 1].split(":")
