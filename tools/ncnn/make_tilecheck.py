#!/usr/bin/env python3
"""Writes app/src/main/assets/models/srvgg_tilecheck.bin for NcnnTileCheck.

Format (little endian): int32 count, then per case: int32 w, int32 h, int32 hasRef,
float32 in[w*h*3] (RGB interleaved, row 0 = GL bottom row), and if hasRef
float32 ref[(4w)*(4h)*3] = tools/srvgg_reference.py on that input (same row order).
Inputs are tile_check.image(), so the desktop run and the phone run use the same pixels.
"""
import os
import struct
import sys

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, os.path.join(ROOT, "tools", "ncnn"))
import srvgg_reference as R
import tile_check as TC

CASES = [(16, 12, True), (157, 113, False)]


def main(out):
    layers = R.load_blob(TC.BLOB)
    parts = [struct.pack("<i", len(CASES))]
    for w, h, ref in CASES:
        rgb = TC.image(w, h, w * 1000 + h)
        parts.append(struct.pack("<iii", w, h, 1 if ref else 0))
        parts.append(rgb.astype("<f4").tobytes())
        if ref:
            td = rgb.reshape(h, w, 3)[::-1].transpose(2, 0, 1)
            exp = R.forward(layers, td).transpose(1, 2, 0)[::-1].reshape(-1)
            parts.append(exp.astype("<f4").tobytes())
    data = b"".join(parts)
    open(out, "wb").write(data)
    print(out, len(data))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "app", "src", "main", "assets", "models", "srvgg_tilecheck.bin"))
