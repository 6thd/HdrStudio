#!/usr/bin/env python3
"""Desktop check of the ncnn conversion layout. Not a Maleoon 935F result.

Confirms the float blob matches tools/srvgg_reference.py and the channel index used by
app/src/main/cpp/ncnn_sr.cpp (PixelShuffle + nearest residual).
"""
import hashlib, os, sys
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import srvgg_reference as R

BLOB = os.path.join(ROOT, "app", "src", "main", "assets", "models", "srvgg_general_x4v3.bin")

def main():
    data = open(BLOB, "rb").read()
    print("sha256", hashlib.sha256(data).hexdigest())
    print("bytes", len(data))
    assert len(data) == 4853184
    layers = R.load_blob(BLOB)
    assert len(layers) == 34
    # a 4x4 ramp: pixelshuffle formula must match the reference forward
    rng = np.random.RandomState(0)
    x = rng.rand(3, 4, 4).astype(np.float32)
    ref = R.forward(layers, x)
    # replay convs then the C++ shuffle
    out = x.astype(np.float64)
    for w, b, p in layers:
        out = R.conv3x3(out, w, b)
        if p is not None:
            out = np.where(out >= 0, out, out * p[:, None, None])
    c, h, w = out.shape
    assert (c, h, w) == (48, 4, 4)
    got = np.zeros((3, 16, 16), np.float64)
    for ch in range(3):
        for y in range(h):
            for x0 in range(w):
                base = np.clip(x[ch, y, x0], 0, 1)
                for sy in range(4):
                    for sx in range(4):
                        oc = ch * 16 + sy * 4 + sx
                        got[ch, y * 4 + sy, x0 * 4 + sx] = out[oc, y, x0] + base
    err = np.max(np.abs(got - ref))
    print("shuffle_vs_reference_max_abs", err)
    if err > 1e-6:
        sys.exit(1)
    print("PASS layout")

if __name__ == "__main__":
    main()
