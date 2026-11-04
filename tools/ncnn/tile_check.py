#!/usr/bin/env python3
"""Direct vs tiled ncnn forward of the app's SRVGG weights, on this machine's CPU (pip ncnn).

The tiling below is a line-for-line port of NcnnSr.forward (bottom-up rows, as in the app).
OLD = the 2.3.1/2.3.2 tiling: a fixed (T+2P)^2 window, black outside the image, crop at P.
NEW = the context window is clamped to the image, so the network zero-pads every layer at
      the true image edge exactly as the direct run does; the crop offset is x0-cx0, y0-cy0.

Regions are measured separately on the x4 output (LR coordinate = HR // 4):
  corner : within EDGE LR px of two image borders
  edge   : within EDGE LR px of one image border (corners excluded)
  seam   : within SEAM LR px of an interior tile boundary, not edge
  centre : everything else
Limits are the app's and are not changed: maxAbs <= 0.02 and PSNR >= 40 dB for every region.
Vulkan cannot be run here; the phone runs the same cases through NcnnTileCheck.
"""
import os
import sys
import tempfile

import numpy as np
import ncnn

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, os.path.join(ROOT, "tools", "ncnn"))
import numeric_check as NC  # build_bin, param (no DLL load at import)

P = 36
EDGE = 2
SEAM = 2
MAX_ABS = 0.02
PSNR_DB = 40.0
BLOB = os.path.join(ROOT, "app", "src", "main", "assets", "models", "srvgg_general_x4v3.bin")


def open_net(fp16, tmp):
    weights = np.fromfile(BLOB, "<f4")
    pp, bp = os.path.join(tmp, "m.param"), os.path.join(tmp, "m.bin")
    open(pp, "wb").write(NC.param())
    open(bp, "wb").write(NC.build_bin(weights))
    net = ncnn.Net()
    o = net.opt
    o.use_vulkan_compute = False
    o.use_fp16_packed = fp16
    o.use_fp16_storage = fp16
    o.use_fp16_arithmetic = fp16
    o.use_bf16_storage = False
    o.use_packing_layout = False
    o.use_winograd_convolution = True   # production options of NcnnSr.Opts.production
    o.use_sgemm_convolution = True
    o.use_int8_inference = False
    o.num_threads = 4
    assert net.load_param(pp) == 0
    assert net.load_model(bp) == 0
    return net


def native_forward(net, win, w, h):
    """nativeForward: bottom-up RGB interleaved in, bottom-up x4 RGB interleaved out."""
    img = win.reshape(h, w, 3)[::-1].transpose(2, 0, 1).copy()  # top-down CHW
    ex = net.create_extractor()
    ex.input("data", ncnn.Mat(img))
    rc, conv = ex.extract("c33")
    assert rc == 0
    feat = np.array(conv).reshape(48, h, w)
    out = NC.final_from_feat(feat, img)            # top-down (3, 4h, 4w), clamped base
    return out.transpose(1, 2, 0)[::-1].reshape(-1).astype(np.float32)


def tiled_old(net, rgb, w, h, T):
    F = T + 2 * P
    W4, H4 = w * 4, h * 4
    out = np.zeros(W4 * H4 * 3, np.float32)
    src = rgb.reshape(h, w, 3)
    for y0 in range(0, h, T):
        for x0 in range(0, w, T):
            cw, ch = min(T, w - x0), min(T, h - y0)
            win = np.zeros((F, F, 3), np.float32)
            ys, xs = max(0, y0 - P), max(0, x0 - P)
            ye, xe = min(h, y0 - P + F), min(w, x0 - P + F)
            win[ys - (y0 - P):ye - (y0 - P), xs - (x0 - P):xe - (x0 - P)] = src[ys:ye, xs:xe]
            hr = native_forward(net, win.reshape(-1), F, F).reshape(F * 4, F * 4, 3)
            o = out.reshape(H4, W4, 3)
            o[y0 * 4:(y0 + ch) * 4, x0 * 4:(x0 + cw) * 4] = hr[P * 4:(P + ch) * 4, P * 4:(P + cw) * 4]
    return out


def context(start, core, size, F):
    """Same as NcnnSr.contextStart: window of min(F, size) px holding [start-P, start+core+P) ∩ [0,size)."""
    span = min(F, size)
    c0 = start - P
    if c0 < 0:
        c0 = 0
    if c0 + span > size:
        c0 = size - span
    return c0, span


def tiled_new(net, rgb, w, h, T):
    F = T + 2 * P
    W4, H4 = w * 4, h * 4
    out = np.zeros(W4 * H4 * 3, np.float32).reshape(H4, W4, 3)
    src = rgb.reshape(h, w, 3)
    for y0 in range(0, h, T):
        for x0 in range(0, w, T):
            cw, ch = min(T, w - x0), min(T, h - y0)
            cx0, fw = context(x0, cw, w, F)
            cy0, fh = context(y0, ch, h, F)
            assert cx0 <= x0 and x0 + cw <= cx0 + fw and cy0 <= y0 and y0 + ch <= cy0 + fh
            # each side of the core has >= P px of real image, or reaches the true image edge
            for lo, core, c0, span, size in ((x0, cw, cx0, fw, w), (y0, ch, cy0, fh, h)):
                assert c0 == 0 or lo - c0 >= P, (lo, c0)
                assert c0 + span == size or c0 + span - (lo + core) >= P
            win = np.ascontiguousarray(src[cy0:cy0 + fh, cx0:cx0 + fw]).reshape(-1)
            hr = native_forward(net, win, fw, fh).reshape(fh * 4, fw * 4, 3)
            ox, oy = (x0 - cx0) * 4, (y0 - cy0) * 4
            out[y0 * 4:(y0 + ch) * 4, x0 * 4:(x0 + cw) * 4] = hr[oy:oy + ch * 4, ox:ox + cw * 4]
    return out.reshape(-1)


def masks(w, h, T):
    ys, xs = np.mgrid[0:h * 4, 0:w * 4] // 4
    ex = (xs < EDGE) | (xs >= w - EDGE)
    ey = (ys < EDGE) | (ys >= h - EDGE)
    corner = ex & ey
    edge = (ex | ey) & ~corner
    seam = np.zeros_like(corner)
    for b in range(T, w, T):
        seam |= (xs >= b - SEAM) & (xs < b + SEAM)
    for b in range(T, h, T):
        seam |= (ys >= b - SEAM) & (ys < b + SEAM)
    seam &= ~(ex | ey)
    centre = ~(ex | ey | seam)
    return {"corner": corner, "edge": edge, "seam": seam, "centre": centre}


def score(a, b, m=None):
    a = a.reshape(-1, 3)
    b = b.reshape(-1, 3)
    if m is not None:
        a, b = a[m.reshape(-1)], b[m.reshape(-1)]
    if a.size == 0:
        return None
    if not (np.isfinite(a).all() and np.isfinite(b).all()):
        return (float("inf"), 0.0, a.shape[0])
    d = np.abs(a.astype(np.float64) - b)
    mse = float((d ** 2).mean())
    psnr = 99.0 if mse == 0 else 10 * np.log10(1.0 / mse)
    return (float(d.max()), psnr, a.shape[0])


def ok(s):
    return s is None or (s[0] <= MAX_ABS and s[1] >= PSNR_DB)


def report(label, got, ref, w, h, T):
    allok = True
    s = score(got, ref)
    allok &= ok(s)
    line = "  %-34s all maxAbs %.6f PSNR %6.2f" % (label, s[0], s[1])
    for k, m in masks(w, h, T).items():
        r = score(got, ref, m)
        if r is None:
            line += " | %s -" % k
            continue
        allok &= ok(r)
        line += " | %s %.6f/%.1f n=%d" % (k, r[0], r[1], r[2])
    print(line + ("  PASS" if allok else "  FAIL"))
    return allok


def image(w, h, seed):
    rng = np.random.default_rng(seed)
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    base = np.stack([xs / max(1, w - 1), ys / max(1, h - 1), 0.5 + 0.4 * np.sin(xs * 0.37 + ys * 0.21)], -1)
    edges = ((xs // 5 + ys // 7) % 2) * 0.25
    img = np.clip(0.75 * base + edges[..., None] + rng.normal(0, 0.04, base.shape), 0, 1)
    return img.astype(np.float32).reshape(-1)   # bottom-up rows (row 0 = bottom), as in the app


def main():
    import srvgg_reference as R
    layers = R.load_blob(BLOB)
    cases = [(16, 12, [64, 8]), (157, 113, [64, 32, 16])]
    fails = 0
    with tempfile.TemporaryDirectory() as tmp:
        for fp16 in (False, True):
            net = open_net(fp16, tmp)
            tag = "CPU FP16" if fp16 else "CPU FP32"
            for (w, h, tiles) in cases:
                rgb = image(w, h, w * 1000 + h)
                direct = native_forward(net, rgb, w, h)
                print("%s %dx%d" % (tag, w, h))
                if w * h <= 400:
                    td = rgb.reshape(h, w, 3)[::-1].transpose(2, 0, 1)
                    ref = R.forward(layers, td).transpose(1, 2, 0)[::-1].reshape(-1)
                    fails += not report("direct vs NumPy reference", direct, ref, w, h, 10 ** 6)
                for T in tiles:
                    nt = ((w + T - 1) // T) * ((h + T - 1) // T)
                    if not fp16:
                        old = tiled_old(net, rgb, w, h, T)
                        report("OLD tiled T=%d (%d tiles) vs direct" % (T, nt), old, direct, w, h, T)
                    new = tiled_new(net, rgb, w, h, T)
                    fails += not report("NEW tiled T=%d (%d tiles) vs direct" % (T, nt), new, direct, w, h, T)
    print("RESULT", "PASS" if fails == 0 else "FAIL %d" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
