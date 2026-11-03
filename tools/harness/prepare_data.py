#!/usr/bin/env python3
"""Prepare harness inputs: decode stills/video with ffmpeg into float32 RGBA, bottom-up
(GL convention, identical to what the Android input stage produces for SDR BT.709 video:
full-range non-linear R'G'B'). Also builds synthetic sequences with known ground truth.
usage: prepare_data.py <media_dir> <data_dir>"""
import json, os, subprocess, sys
import numpy as np

media, data = sys.argv[1], sys.argv[2]
os.makedirs(data, exist_ok=True)
rng = np.random.default_rng(1234)

def ff_rgb(path, vf=None, frames=None, size=None):
    cmd = ['ffmpeg', '-v', 'error', '-i', path]
    if frames: cmd += ['-frames:v', str(frames)]
    filt = (vf + ',' if vf else '') + 'format=rgb48le'
    cmd += ['-vf', filt, '-f', 'rawvideo', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    probe = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
        'stream=width,height', '-of', 'json', path], capture_output=True).stdout)['streams'][0]
    w, h = size or (probe['width'], probe['height'])
    a = np.frombuffer(raw, '<u2').astype(np.float32) / 65535.0
    return a.reshape(-1, h, w, 3)

def save(name, rgb):           # rgb: (h,w,3) top-down float -> RGBA f32 bottom-up
    h, w, _ = rgb.shape
    out = np.ones((h, w, 4), np.float32); out[..., :3] = rgb
    np.ascontiguousarray(out[::-1]).tofile(os.path.join(data, name + '.f32'))
    return {'name': name, 'w': w, 'h': h}

meta = {'stills': [], 'seqs': {}}
for f in ['baboon.jpg', 'messi5.jpg', 'fruits.jpg', 'building.jpg', 'OST_009.png', '0014.jpg', 'ADE_val_00000114.jpg', 'lena.jpg']:
    img = ff_rgb(os.path.join(media, f), vf='scale=in_range=pc:out_range=pc')[0]
    h, w = img.shape[:2]
    if w * h > 640 * 480:             # keep SwiftShader run times reasonable
        s = (640 * 480 / (w * h)) ** 0.5
        img = ff_rgb(os.path.join(media, f), vf='scale=%d:%d:flags=lanczos' % (int(w * s) // 2 * 2, int(h * s) // 2 * 2), size=(int(w * s) // 2 * 2, int(h * s) // 2 * 2))[0]
    meta['stills'].append(save('still_' + os.path.splitext(f)[0], img))

# Review crops (content classes: face/skin, fur/hair, architecture lines, text-like detail)
meta['crops'] = []
for nm, f, box in [('crop_face', 'lena.jpg', (180, 200, 160, 160)), ('crop_fur', 'baboon.jpg', (60, 300, 160, 160)),
                   ('crop_lines', 'building.jpg', (300, 120, 192, 144)), ('crop_messi', 'messi5.jpg', (180, 40, 192, 144))]:
    img = ff_rgb(os.path.join(media, f))[0]
    x, y, w, h = box
    meta['crops'].append(save(nm, img[y:y + h, x:x + w]))

# SR crop used by the numerical reference test (top-down crop -> bottom-up file)
crop = np.load(os.path.join(os.path.dirname(data), 'in_baboon_crop.npy')).transpose(1, 2, 0)
meta['sr_crop'] = save('sr_crop', crop)
bab = ff_rgb(os.path.join(media, 'baboon.jpg'))[0]
meta['sr_tile_img'] = save('sr_tile_img', bab[100:180, 60:156])    # 96x80 LR, several tiles

# Synthetic pan with known motion + noise, and a fast object over texture
big = ff_rgb(os.path.join(media, 'building.jpg'))[0]
W, H, N = 320, 240, 12
clean, noisy, shifts = [], [], []
for i in range(N):
    dx, dy = 2 * i, i                     # image content moves by (-2,-1) px per frame
    c = big[40 + dy:40 + dy + H, 40 + dx:40 + dx + W]
    clean.append(c); noisy.append(np.clip(c + rng.normal(0, 0.03, c.shape), 0, 1))
for i in range(N):
    save('pan_clean_%02d' % i, clean[i]); save('pan_noisy_%02d' % i, noisy[i])
meta['seqs']['pan'] = {'w': W, 'h': H, 'n': N, 'motion_px_per_frame': [-2, -1], 'noise_sigma': 0.03}

tex = ff_rgb(os.path.join(media, 'baboon.jpg'))[0][100:100 + H, 100:100 + W]
for i in range(N):
    c = tex.copy()
    x0 = 20 + 12 * i                       # bright square moving right 12 px/frame
    c[90:150, x0:x0 + 60] = [0.95, 0.85, 0.2]
    save('obj_clean_%02d' % i, c); save('obj_noisy_%02d' % i, np.clip(c + rng.normal(0, 0.02, c.shape), 0, 1))
meta['seqs']['obj'] = {'w': W, 'h': H, 'n': N, 'speed': 12, 'y': [90, 150], 'size': 60, 'x0': 20}

# Flash: static noisy scene, frames 6-7 brightened x1.6 (in linear light)
for i in range(N):
    c = big[0:H, 0:W]
    if i in (6, 7): c = np.clip((c ** 2.4 * 1.6) ** (1 / 2.4), 0, 1)
    save('flash_%02d' % i, np.clip(c + rng.normal(0, 0.02, c.shape), 0, 1))
meta['seqs']['flash'] = {'w': W, 'h': H, 'n': N, 'flash_frames': [6, 7]}

# Banding: 8-bit quantised sky gradient
Hs, Ws = 360, 160
g = np.linspace(0.62, 0.80, Hs)[:, None, None] * np.array([0.80, 0.90, 1.00])[None, None, :]
g = np.repeat(g, Ws, 1)
save('sky8', np.round(g * 255) / 255)
meta['sky8'] = {'w': Ws, 'h': Hs}

# Megamind (has shot cuts) for scene-cut detection, half resolution
pr = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height', '-of', 'json', os.path.join(media, 'Megamind.avi')], capture_output=True).stdout)['streams'][0]
mw, mh = pr['width'] // 2, pr['height'] // 2
mm = ff_rgb(os.path.join(media, 'Megamind.avi'), vf='scale=%d:%d' % (mw, mh), size=(mw, mh))
mm = mm.reshape(-1, mh, mw, 3)
for i in range(mm.shape[0]): save('mm_%03d' % i, mm[i])
meta['seqs']['megamind'] = {'w': mw, 'h': mh, 'n': int(mm.shape[0])}

# vtest (people walking, static camera, compressed) first 48 frames for the end-to-end run
vt = ff_rgb(os.path.join(media, 'vtest.avi'), frames=48)
for i in range(vt.shape[0]): save('vt_%02d' % i, vt[i])
meta['seqs']['vtest'] = {'w': vt.shape[2], 'h': vt.shape[1], 'n': int(vt.shape[0]), 'fps': 10}

# 4K frames (upscaled content - no native 4K sample is reachable from this environment)
uhd = ff_rgb(os.path.join(media, 'building.jpg'), vf='scale=3840:2160:flags=lanczos', size=(3840, 2160))[0]
meta['uhd_sdr'] = save('uhd_sdr', uhd)
# 4K PQ (HDR10) source as 10-bit limited BT.2020 Y'CbCr codes (P010 normalisation), made from the same image
lin = uhd ** 2.4 * 203.0
lin[1000:1200, 1600:2200] *= 4.0                         # a real highlight region (~800 nit)
m709to2020 = np.array([[0.627403896, 0.329283039, 0.043313065], [0.069097289, 0.919540395, 0.011362316], [0.016391439, 0.088013308, 0.895595253]])
n2020 = np.einsum('ij,hwj->hwi', m709to2020, lin)
yq = np.power(np.clip(n2020 / 1e4, 0, 1), 0.1593017578125); pq = np.power((0.8359375 + 18.8515625 * yq) / (1 + 18.6875 * yq), 78.84375)
kr, kb = 0.2627, 0.0593; Y = kr * pq[..., 0] + (1 - kr - kb) * pq[..., 1] + kb * pq[..., 2]
codes = np.stack([np.round(64 + 876 * Y), np.round(512 + 896 * (pq[..., 2] - Y) / (2 * (1 - kb))), np.round(512 + 896 * (pq[..., 0] - Y) / (2 * (1 - kr)))], -1)
meta['uhd_pq_yuv'] = save('uhd_pq_yuv', (codes / (65535 / 64)).astype(np.float32))
np.save(os.path.join(data, 'uhd_pq_expected.npy'), pq[::-1].astype(np.float32))     # bottom-up like the textures
json.dump(meta, open(os.path.join(data, 'meta.json'), 'w'), indent=1)
print(json.dumps({k: (v if k != 'stills' else [s['name'] for s in v]) for k, v in meta.items()}, indent=1))
