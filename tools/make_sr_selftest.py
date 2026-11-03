#!/usr/bin/env python3
"""Generates assets/models/srvgg_selftest.bin: a small deterministic input and the expected
x4 output of the reference network (srvgg_reference.py, validated against the ncnn runtime).
The app runs its GLSL implementation on this input at start-up of the AI stage and only
reports the model as READY if the result matches. Layout (little-endian):
  int32 w, int32 h, float32 input[h][w][3] (bottom-up rows), float32 expected[4h][4w][3] (bottom-up)"""
import struct, sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import srvgg_reference as R
w, h = 20, 16
y, x = np.mgrid[0:h, 0:w].astype(np.float64)
img = np.stack([0.5 + 0.4 * np.sin(x * 0.9 + y * 0.3), 0.5 + 0.4 * np.cos(x * 0.4 - y * 0.7), (x + y) / (w + h)], 0)
img[:, 6:10, 8:13] = [[[0.95]], [[0.9]], [[0.1]]]          # a hard-edged patch
img = np.clip(img, 0, 1).astype(np.float32)
out = R.forward(R.load_blob(sys.argv[1]), img).astype(np.float32)
with open(sys.argv[2], 'wb') as f:
    f.write(struct.pack('<ii', w, h))
    f.write(np.ascontiguousarray(img.transpose(1, 2, 0)[::-1]).tobytes())
    f.write(np.ascontiguousarray(out.transpose(1, 2, 0)[::-1]).tobytes())
print('selftest', w, h, 'out range', out.min(), out.max())
