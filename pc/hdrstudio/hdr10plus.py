"""HDR10+ (SMPTE ST 2094-40) dynamic metadata, profile A.

HDR10 carries one pair of light levels for the whole file. HDR10+ adds, per scene, the brightest
value of each channel, the average and the distribution of pixel brightness, so a display that
cannot reach the mastering peak tone-maps each scene on its own instead of the whole film at once.

The values must be constant inside a scene (a display follows them, so per-frame values would
make the picture pump), and x265 needs them before it encodes a frame. They are therefore
measured in a separate fast pass: the same pipeline without the AI model, at reduced size, which
changes detail but not the brightness distribution. x265 then reads one JSON file per chunk
(`--dhdr10-info`), in the format of Samsung's reference tools and hdr10plus_tool.

Units: 0.1 nit, capped at 100000 (10000 nits). Verified by round trip: values written here are
read back unchanged by ffprobe from the encoded stream."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

PERCENTILES = [1, 5, 10, 25, 50, 75, 90, 95, 99]
BINS = 1024
ANALYSIS_SHORT_SIDE = 540

_M1, _M2, _C1, _C2, _C3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875


def pq_to_nits(e):
    p = np.clip(np.asarray(e, np.float64), 0, 1) ** (1 / _M2)
    return 10000 * (np.maximum(p - _C1, 0) / (_C2 - _C3 * p)) ** (1 / _M1)


_BIN_NITS = pq_to_nits((np.arange(BINS) + 0.5) / BINS)


class FrameStats:
    """Brightness statistics of one final PQ frame."""

    def __init__(self, rgb_pq: np.ndarray):
        rgb = np.clip(rgb_pq.reshape(-1, 3).astype(np.float32), 0, 1)
        m = rgb.max(axis=1)                                   # PQ is monotonic: max code = max light
        self.hist = np.bincount(np.minimum((m * BINS).astype(np.int32), BINS - 1), minlength=BINS).astype(np.int64)
        self.max_rgb = pq_to_nits(rgb.max(axis=0))           # per channel, nits
        self.sum_nits = float(pq_to_nits(m).sum())
        self.pixels = int(m.size)


def scene_entry(frames: list) -> dict:
    """One scene's metadata from the statistics of its frames."""
    hist = np.sum([f.hist for f in frames], axis=0)
    cdf = np.cumsum(hist) / max(1, hist.sum())
    unit = lambda nits: int(min(100000, max(0, round(float(nits) * 10))))
    values = [unit(_BIN_NITS[min(BINS - 1, int(np.searchsorted(cdf, p / 100.0)))]) for p in PERCENTILES]
    max_scl = np.max([f.max_rgb for f in frames], axis=0)
    avg = sum(f.sum_nits for f in frames) / max(1, sum(f.pixels for f in frames))
    return {"LuminanceParameters": {
        "AverageRGB": unit(avg),
        "LuminanceDistributions": {"DistributionIndex": PERCENTILES, "DistributionValues": values},
        "MaxScl": [unit(v) for v in max_scl]},
        "NumberOfWindows": 1, "TargetedSystemDisplayMaximumLuminance": 0}


def build(stats: list, cuts: list) -> dict:
    """stats: FrameStats per source frame. cuts: True where a frame starts a new scene."""
    scenes, start = [], 0
    for i in range(1, len(stats) + 1):
        if i == len(stats) or cuts[i]:
            scenes.append((start, i))
            start = i
    frame_scene = [0] * len(stats)
    entries = []
    for n, (a, b) in enumerate(scenes):
        entries.append(scene_entry(stats[a:b]))
        frame_scene[a:b] = [n] * (b - a)
    return {"scenes": entries, "frameScene": frame_scene}


def chunk_json(meta: dict, source_frames: list, path: Path, version: str = "1.0"):
    """Writes the x265 file for one chunk. source_frames: the source frame index behind every
    frame of the chunk, in display order (a generated in-between frame names the frame before it)."""
    last = len(meta["frameScene"]) - 1
    info, first, counts, prev, k = [], [], [], None, 0
    for seq, src in enumerate(source_frames):
        sid = meta["frameScene"][min(max(src, 0), last)]
        if sid != prev:
            first.append(seq)
            counts.append(0)
            prev, k = sid, 0
        counts[-1] += 1
        info.append({**meta["scenes"][sid], "SceneFrameIndex": k, "SceneId": len(first) - 1, "SequenceFrameIndex": seq})
        k += 1
    doc = {"JSONInfo": {"HDR10plusProfile": "A", "Version": "1.0"}, "SceneInfo": info,
           "SceneInfoSummary": {"SceneFirstFrameIndex": first, "SceneFrameNumbers": counts},
           "ToolInfo": {"Tool": "HDR Studio", "Version": version}}
    Path(path).write_text(json.dumps(doc), "utf-8")


def analysis_size(out_w: int, out_h: int) -> tuple:
    f = min(1.0, ANALYSIS_SHORT_SIDE / min(out_w, out_h))
    even = lambda v: max(2, int(round(v * f / 2)) * 2)
    return even(out_w), even(out_h)
