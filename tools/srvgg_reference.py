#!/usr/bin/env python3
"""NumPy reference forward pass of SRVGGNetCompact, reading the app's weight blob.
Mirrors realesrgan/archs/srvgg_arch.py (zero padding 1, PReLU, PixelShuffle(4), + nearest x4 base)."""
import numpy as np

def load_blob(path, num_conv=32, feat=64, scale=4):
    a = np.fromfile(path, '<f4'); off = 0; layers = []
    shapes = [(feat, 3)] + [(feat, feat)] * num_conv + [(3 * scale * scale, feat)]
    for i, (o, c) in enumerate(shapes):
        w = a[off:off + o * c * 9].reshape(o, c, 3, 3); off += o * c * 9
        b = a[off:off + o]; off += o
        p = None
        if i < len(shapes) - 1:
            p = a[off:off + feat]; off += feat
        layers.append((w, b, p))
    assert off == a.size, (off, a.size)
    return layers

def conv3x3(x, w, b):
    c, h, wd = x.shape
    xp = np.pad(x, ((0, 0), (1, 1), (1, 1)))
    out = np.zeros((w.shape[0], h, wd), np.float64)
    for ky in range(3):
        for kx in range(3):
            out += np.einsum('oc,chw->ohw', w[:, :, ky, kx], xp[:, ky:ky + h, kx:kx + wd], optimize=True)
    return out + b[:, None, None]

def forward(layers, x, scale=4):
    """x: float array (3,H,W) RGB in [0,1]. Returns (3,H*s,W*s)."""
    out = x.astype(np.float64)
    for w, b, p in layers:
        out = conv3x3(out, w, b)
        if p is not None:
            out = np.where(out >= 0, out, out * p[:, None, None])
    c, h, wd = out.shape
    co = c // (scale * scale)
    out = out.reshape(co, scale, scale, h, wd).transpose(0, 3, 1, 4, 2).reshape(co, h * scale, wd * scale)
    base = np.repeat(np.repeat(x, scale, 1), scale, 2)
    return out + base
