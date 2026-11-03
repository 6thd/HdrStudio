#!/usr/bin/env python3
"""Builds the exact self-test frame (16 patches + fine grey ramp strip), once through a 10-bit
path and once through an 8-bit stage, encodes both with x265 (10-bit) at a high and a low
bitrate, decodes them and writes the ramp row's 10-bit luma codes (3 frames each) as int16 LE.
Consumed by BitstreamTests (PrecisionMetric thresholds). usage: make_ramp_streams.py <out_dir>"""
import os, subprocess, sys
import numpy as np
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
w, h, fps = 1920, 1080, 30
def frame(eight):
    img = np.zeros((h, w, 3))
    pat = [[g, g, g] for g in np.linspace(0.05, 0.95, 9)] + [[.75, .1, .1], [.1, .75, .1], [.1, .1, .75], [.1, .7, .7], [.7, .1, .7], [.7, .7, .1], [.62, .48, .40]]
    for gy in range(4):
        for gx in range(4):
            img[h - (gy + 1) * h // 4:h - gy * h // 4, gx * w // 4:(gx + 1) * w // 4] = pat[gy * 4 + gx]
    x = (np.arange(w) + 0.5) / w
    img[h - h // 32:, :, :] = (400 / 1023 + 64 / 1023 * x)[None, :, None]       # same as pattern.frag
    return np.round(img * 255) / 255 if eight else np.round(img * 1023) / 1023
for eight in (False, True):
    for br in (w * h * fps // 2, w * h * fps // 8):
        raw = (frame(eight) * 65535).astype('<u2').tobytes() * 6
        hevc = os.path.join(out, 'ramp.hevc')
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb48le', '-s', f'{w}x{h}', '-r', str(fps), '-i', '-',
                        '-vf', 'scale=out_color_matrix=bt2020nc:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le',
                        '-c:v', 'libx265', '-preset', 'fast', '-b:v', str(br), '-x265-params', 'log-level=error', '-f', 'hevc', hevc], input=raw, check=True)
        dec = subprocess.run(['ffmpeg', '-v', 'error', '-i', hevc, '-f', 'rawvideo', '-pix_fmt', 'yuv420p10le', '-'], capture_output=True, check=True).stdout
        a = np.frombuffer(dec, '<u2'); fsz = w * h * 3 // 2; row = h - max(1, h // 64)
        rows = [a[(k + 2) * fsz:(k + 2) * fsz + w * h].reshape(h, w)[row, w // 16:w - w // 16] for k in range(3)]
        np.concatenate(rows).astype('<i2').tofile(os.path.join(out, 'ramp_%s_%dM.i16' % ('8bit' if eight else '10bit', br // 1000000)))
print('ramp rows written')
