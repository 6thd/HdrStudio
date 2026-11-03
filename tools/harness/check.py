#!/usr/bin/env python3
"""Independent checks of harness outputs (NumPy re-implementation of the standards, not of
the shaders). usage: check.py <data_dir> <out_dir> <step> ...
Steps: prep_yuv color sr motion temporal flash scenecut stills banding video"""
import json, os, subprocess, sys
import numpy as np

DATA, OUT = sys.argv[1], sys.argv[2]
MEDIA = os.environ.get('HDR_MEDIA') or os.path.join(os.path.dirname(DATA), 'media')   # source clips (run_ci.sh sets HDR_MEDIA)
REPORT = {}

def rd(name, w=None, h=None):
    a = np.fromfile(os.path.join(OUT, name), '<f4').reshape(-1, 4)
    if w: a = a.reshape(h, w, 4)[::-1]          # bottom-up -> top-down
    return a

# ---------------------------------------------------------------- standards
m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
def pq_enc(n):
    y = np.power(np.clip(n / 10000.0, 0, 1), m1); return np.power((c1 + c2 * y) / (1 + c3 * y), m2)
def pq_dec(e):
    p = np.power(np.clip(e, 0, 1), 1 / m2); return 10000 * np.power(np.maximum(p - c1, 0) / (c2 - c3 * p), 1 / m1)
M709_2020 = np.array([[0.627403896, 0.329283039, 0.043313065], [0.069097289, 0.919540395, 0.011362316], [0.016391439, 0.088013308, 0.895595253]])
M2020_709 = np.linalg.inv(M709_2020)
def hlg_inv(e):
    a, b, c = 0.17883277, 0.28466892, 0.55991073
    return np.where(e <= 0.5, e * e / 3, (np.exp((e - c) / a) + b) / 12)
def rgb_to_ycbcr_codes(rgb, kr, kb, bits, full=False):
    kg = 1 - kr - kb; y = kr * rgb[:, 0] + kg * rgb[:, 1] + kb * rgb[:, 2]
    cb = (rgb[:, 2] - y) / (2 * (1 - kb)); cr = (rgb[:, 0] - y) / (2 * (1 - kr))
    k = 2 ** (bits - 8)
    return np.stack([np.round(16 * k + 219 * k * y), np.round(128 * k + 224 * k * cb), np.round(128 * k + 224 * k * cr)], 1)
def ycbcr_codes_to_rgb(c, kr, kb, bits):
    k = 2 ** (bits - 8); y = (c[:, 0] - 16 * k) / (219 * k); cb = (c[:, 1] - 128 * k) / (224 * k); cr = (c[:, 2] - 128 * k) / (224 * k)
    r = y + 2 * (1 - kr) * cr; b = y + 2 * (1 - kb) * cb; g = (y - kr * r - kb * b) / (1 - kr - kb)
    return np.stack([r, g, b], 1)

def ok(name, cond, detail):
    REPORT[name] = {'pass': bool(cond), **detail}
    print(('PASS ' if cond else 'FAIL ') + name, json.dumps(detail, default=float))

def step_prep_yuv():
    rng = np.random.default_rng(5)
    rgb = np.clip(rng.random((256, 3)), 0, 1); rgb[:8] = [[0, 0, 0], [1, 1, 1], [.5, .5, .5], [.58, .58, .58], [1, 0, 0], [0, 1, 0], [0, 0, 1], [.75, .75, .75]]
    for nm, kr, kb, bits, scale in [('yuv_pq10', .2627, .0593, 10, 65535 / 64), ('yuv_hlg10', .2627, .0593, 10, 65535 / 64), ('yuv_sdr8', .2126, .0722, 8, 255)]:
        codes = rgb_to_ycbcr_codes(rgb, kr, kb, bits)
        v = np.ones((len(rgb), 4), np.float32); v[:, :3] = codes / scale
        v.astype('<f4').tofile(os.path.join(DATA, nm + '.f32'))
        np.save(os.path.join(DATA, nm + '_codes.npy'), codes)
    print('yuv inputs written')

def step_color():
    N = 1024; e = np.linspace(0, 1, N)
    r0 = rd('ramp_e0.f32')[:, 0]
    FP16 = 1 / 2048.0   # RGBA16F storage step at 1.0 (= 0.5 LSB of 10-bit); intermediate textures are FP16
    ok('sdr_to_hdr_expand0_is_bt2408_mapping', np.abs(r0 - pq_enc(203 * e ** 2.4)).max() <= FP16,
       {'max_err_pq': np.abs(r0 - pq_enc(203 * e ** 2.4)).max(), 'white_nits': pq_dec(r0[-1:])[0], 'pq_of_white': r0[-1]})
    for nm in ['ramp_e1', 'ramp_e1_g22']:
        r = rd(nm + '.f32')[:, 0]; n = pq_dec(r)
        gamma = 2.2 if 'g22' in nm else 2.4
        below = e ** gamma <= 0.5
        mono = np.all(np.diff(r) >= -1e-6)
        mid = (np.abs(n[below] - 203 * e[below] ** gamma) / np.maximum(203 * e[below] ** gamma, 1)).max()
        ok(nm + '_monotonic_midtones_kept_peak_bounded', mono and mid < 0.005 and n.max() <= 1000.5,
           {'monotonic': mono, 'max_midtone_rel_dev': mid, 'peak_nits': n.max(), 'nits_at_75pct': float(np.interp(0.75, e, n)), 'nits_at_90pct': float(np.interp(0.9, e, n))})
    sid = rd('ramp_sdr_identity.f32')[:, 0]
    ok('sdr_to_sdr_identity_no_double_transfer', np.abs(sid - e).max() <= FP16, {'max_err': np.abs(sid - e).max()})
    rin = rd('rand_in.f32')[:, :3]; rs = rd('rand_sdr.f32')[:, :3]
    ok('sdr_to_sdr_identity_random_colours', np.abs(rs - rin).max() <= FP16, {'max_err': np.abs(rs - rin).max()})
    for nm in ['rand_hdr_e1', 'rand_hdr_vib']:
        h = rd(nm + '.f32')[:, :3]; nits = pq_dec(h)
        lin_in = M709_2020 @ (rin ** 2.4).T
        def chroma(v):          # normalised chromaticity in BT.2020 linear
            s = v.sum(0); return v / np.maximum(s, 1e-9)
        sel = lin_in.sum(0) > 0.02
        dc = np.abs(chroma(nits.T)[:, sel] - chroma(lin_in)[:, sel]).max(0)
        # hue angle in the opponent plane, saturated colours only
        def hue(v):
            v = M2020_709 @ v; return np.arctan2(np.sqrt(3) * (v[1] - v[2]), 2 * v[0] - v[1] - v[2])
        sat = (lin_in.max(0) - lin_in.min(0)) / np.maximum(lin_in.max(0), 1e-9) > 0.3
        dh = np.abs(np.angle(np.exp(1j * (hue(nits.T) - hue(lin_in)))))[sel & sat]
        ok(nm + '_hue_preserved_no_negative', dh.max() < 0.02 and nits.min() >= 0 and nits.max() <= 1000.5,
           {'max_hue_shift_rad': dh.max(), 'chromaticity_shift_p99': np.percentile(dc, 99), 'peak_nits': nits.max(), 'min': nits.min()})
    t = rd('pq_to_sdr.f32')[:, 0]; nin = np.linspace(0, 1000, N)
    # BT.2390 EETF has zero slope at the source peak by design; "no hard clip" = still below 1.0 at 500 nits
    ok('pq_to_sdr_monotonic_no_hard_clip', np.all(np.diff(t) >= -1e-6) and t.max() <= 1.0 and np.interp(500, nin, t) < 0.995,
       {'sdr_at_500nits': float(np.interp(500, nin, t)), 'sdr_at_100nits': float(np.interp(100, nin, t)), 'sdr_at_203nits': float(np.interp(203, nin, t)), 'sdr_at_1000nits': t[-1]})
    pp = rd('pq_to_pq.f32')[:, 0]; pin = pq_enc(nin)
    ok('pq_to_pq_identity', np.abs(pp - pin).max() <= FP16, {'max_err': np.abs(pp - pin).max()})
    for nm, kr, kb, bits, tr in [('yuv_pq10', .2627, .0593, 10, 'pq'), ('yuv_hlg10', .2627, .0593, 10, 'hlg'), ('yuv_sdr8', .2126, .0722, 8, 'sdr')]:
        codes = np.load(os.path.join(DATA, nm + '_codes.npy')); got = rd(nm + '_out.f32')[:, :3]
        rgb = np.clip(ycbcr_codes_to_rgb(codes, kr, kb, bits), 0, 1)
        if tr == 'hlg':
            s = hlg_inv(rgb); ys = np.maximum(s @ np.array([.2627, .6780, .0593]), 1e-6)
            exp = pq_enc(1000 * ys[:, None] ** 0.2 * s)
        else:
            exp = rgb
        err = np.abs(got - exp).max()
        tol = 1.5 / 1023 * (1 if bits == 10 else 4)
        ok(nm + '_ycbcr_to_working_once', err < tol, {'max_err': err, 'tolerance': tol,
           'white': got[1].tolist(), 'black': got[0].tolist()})
    d = rd('present_dither.f32')[:, 0]
    ok('present_tpdf_dither', abs(d.mean() - 0.5) < 2e-5 and d.max() - d.min() <= 2 / 1023 + 1e-6,
       {'mean': d.mean(), 'std_lsb': d.std() * 1023, 'range_lsb': (d.max() - d.min()) * 1023})

def step_sr():
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
    ref = np.load(os.path.join(os.path.dirname(DATA), 'ref_baboon_crop.npy')).transpose(1, 2, 0)
    h, w = ref.shape[:2]
    got = rd('sr_crop_out.f32', w, h)[..., :3]
    err = np.abs(got - ref)
    ok('ai_glsl_matches_reference_network', err.max() < 3e-3, {'max_abs_err': err.max(), 'mean_abs_err': err.mean(), 'max_err_8bit_codes': err.max() * 255})
    W, H = 96 * 4, 80 * 4
    a = rd('srt_t128_f1.f32', W, H); b = rd('srt_t24_f1.f32', W, H)
    ok('ai_tiling_seamless_x4', np.abs(a - b).max() < 2e-3, {'max_diff_tiles_24_vs_single': np.abs(a - b).max()})
    a = rd('srt_t128_f05.f32', W // 2, H // 2); b = rd('srt_t24_f05.f32', W // 2, H // 2)
    ok('ai_tiling_seamless_resampled_0.5', np.abs(a - b).max() < 2e-3, {'max_diff': np.abs(a - b).max()})
    a = rd('srt_t128_f075.f32', 288, 240); b = rd('srt_t40_f075.f32', 288, 240)
    ok('ai_tiling_seamless_resampled_0.75', np.abs(a - b).max() < 2e-3, {'max_diff': np.abs(a - b).max()})

def bilinear(img, x, y):
    h, w = img.shape[:2]
    x = np.clip(x, 0, w - 1); y = np.clip(y, 0, h - 1)
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int)
    x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    return (img[y0, x0] * (1 - fx) + img[y0, x1] * fx) * (1 - fy) + (img[y1, x0] * (1 - fx) + img[y1, x1] * fx) * fy

def step_motion():
    """Motion is judged by what the temporal filter needs: how well the previous frame,
    warped with our vectors, predicts the current one (aperture-ambiguous directions along
    edges are harmless). Compared with the true motion and with no motion compensation."""
    W, H = 320, 240
    cur = np.fromfile(os.path.join(DATA, 'pan_clean_03.f32'), '<f4').reshape(H, W, 4)[..., :3]   # bottom-up, like GL
    prev = np.fromfile(os.path.join(DATA, 'pan_clean_02.f32'), '<f4').reshape(H, W, 4)[..., :3]
    v = np.fromfile(os.path.join(OUT, 'mv_lvl0.f32'), '<f4').reshape(H // 2, W // 2, 4)[..., :2] * 2
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    # vector field sampled bilinearly at full resolution (as texture() does in temporal.frag)
    vf = bilinear(v, (xx + 0.5) / 2 - 0.5, (yy + 0.5) / 2 - 0.5)
    est = bilinear(prev, xx + vf[..., 0], yy + vf[..., 1])
    tru = bilinear(prev, xx + 2, yy - 1)
    m = (slice(12, -12), slice(12, -12))
    p_est, p_true, p_zero = psnr(est[m], cur[m]), psnr(tru[m], cur[m]), psnr(prev[m], cur[m])
    med = np.median(v[6:-6, 6:-6].reshape(-1, 2), 0)
    # integer true motion -> perfect warp (inf dB); we require a large gain over no compensation
    ok('motion_compensation_quality_vs_true_motion', p_est > p_zero + 8 and np.linalg.norm(med - [2, -1]) < 0.3,
       {'psnr_warp_estimated_db': p_est, 'psnr_warp_true_motion_db': 'inf (integer shift)', 'psnr_no_compensation_db': p_zero, 'median_vector_px': med.tolist()})

def psnr(a, b): return 10 * np.log10(1 / np.mean((a - b) ** 2))

def step_temporal():
    m = json.load(open(os.path.join(DATA, 'meta.json')))
    for seq in ['pan', 'obj']:
        s = m['seqs'][seq]; w, h, n = s['w'], s['h'], s['n']
        clean = [np.fromfile(os.path.join(DATA, '%s_clean_%02d.f32' % (seq, i)), '<f4').reshape(h, w, 4)[::-1, :, :3] for i in range(n)]
        res = {}
        for mode in ['off', 'spatial', 'temporal', 'both']:
            outs = [rd('tmp_%s_%s_%02d.f32' % (seq, mode, i), w, h)[..., :3] for i in range(n)]
            res[mode] = [psnr(o, c) for o, c in zip(outs, clean)]
            if seq == 'obj':
                # ghosting: region the object just left (trail), last frames
                tr = []
                for i in range(4, n):
                    x0 = s['x0'] + s['speed'] * i
                    y0, y1 = s['y']
                    tr.append(np.abs(outs[i][y0:y1, max(0, x0 - 24):x0] - clean[i][y0:y1, max(0, x0 - 24):x0]).mean())
                res[mode + '_trail_mae'] = float(np.mean(tr))
        summary = {k: (float(np.mean(v[4:])) if isinstance(v, list) else v) for k, v in res.items()}
        if seq == 'pan':
            ok('temporal_denoise_improves_psnr_on_moving_pan', summary['temporal'] > summary['off'] + 2.0 and summary['both'] >= summary['spatial'],
               {'psnr_db_frames4+': summary})
        else:
            ok('temporal_no_ghost_trail_behind_fast_object', summary['temporal_trail_mae'] < summary['off_trail_mae'] * 1.05,
               {'psnr_db_frames4+': {k: v for k, v in summary.items() if 'trail' not in k},
                'trail_mae_off': summary['off_trail_mae'], 'trail_mae_temporal': summary['temporal_trail_mae']})

def step_resize_temporal():
    j = json.load(open(os.path.join(OUT, 'resize_temporal.json')))
    n = json.load(open(os.path.join(DATA, 'meta.json')))['seqs']['pan']['n']
    out = {}
    for tag, d in j.items():
        w, h = d['w'], d['h']
        ref = [rd('rt_%s_ref_%02d.f32' % (tag, i), w, h)[..., :3] for i in range(n)]
        r = {}
        for mode in ['off', 'temporal']:
            fr = [rd('rt_%s_%s_%02d.f32' % (tag, mode, i), w, h)[..., :3] for i in range(n)]
            r[mode] = float(np.mean([psnr(fr[i], ref[i]) for i in range(4, n)]))
            # border band (where wrong-size buffers would show up first)
            r[mode + '_border'] = float(np.mean([psnr(np.concatenate([fr[i][:6].reshape(-1, 3), fr[i][-6:].reshape(-1, 3), fr[i][:, :6].reshape(-1, 3), fr[i][:, -6:].reshape(-1, 3)]),
                                                      np.concatenate([ref[i][:6].reshape(-1, 3), ref[i][-6:].reshape(-1, 3), ref[i][:, :6].reshape(-1, 3), ref[i][:, -6:].reshape(-1, 3)])) for i in range(4, n)]))
        out[tag] = r
    # a 2x downscale already averages away ~6 dB of noise, so less is left for the temporal stage: require > +1.0 dB
    good = all(v['temporal'] > v['off'] + 1.0 and v['temporal_border'] > v['off_border'] - 0.5 for v in out.values())
    ok('temporal_with_resolution_change_up_and_down', good, {'psnr_db_vs_clean_same_pipeline': out})

def step_flash():
    m = json.load(open(os.path.join(DATA, 'meta.json'))); s = m['seqs']['flash']; w, h = s['w'], s['h']
    j = json.load(open(os.path.join(OUT, 'flash.json')))
    ins = [np.fromfile(os.path.join(DATA, 'flash_%02d.f32' % i), '<f4').reshape(h, w, 4)[..., :3] for i in range(s['n'])]
    outs = [rd('flash_out_%02d.f32' % i)[:, :3].reshape(h, w, 3)[::-1] for i in range(s['n'])]
    rel = [outs[i].mean() / ins[i][::-1].mean() for i in range(s['n'])]
    cuts = [x['cut'] for x in j]
    ok('flash_preserved_not_averaged_and_not_a_cut', abs(rel[6] - 1) < 0.02 and abs(rel[7] - 1) < 0.02 and not any(cuts[1:]),
       {'out_over_in_mean_frame6': rel[6], 'frame7': rel[7], 'cut_flags': cuts, 'structure_d': [x['d'] for x in j]})

def step_scenecut():
    j = json.load(open(os.path.join(OUT, 'scenecut.json')))
    ours = [r[0] for r in j if r[1] and r[0] > 0]
    src = os.path.join(MEDIA, 'Megamind.avi')
    if not os.path.exists(src): raise FileNotFoundError(src)
    out = subprocess.run(['ffmpeg', '-v', 'error', '-i', src, '-vf', "select='gt(scene,0.3)',metadata=print:file=-", '-an', '-f', 'null', '-'], capture_output=True, text=True).stdout
    ref = []
    for line in out.split('\n'):
        if line.startswith('frame:'):
            ref.append(int(line.split()[0].split(':')[1]))
    # ffmpeg 'frame:N' in metadata print counts *selected* frames; use pts_time instead
    pts = [float(l.split('pts_time:')[1]) for l in out.split('\n') if 'pts_time:' in l]
    fps = 25.0
    pr = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=r_frame_rate', '-of', 'csv=p=0', src], capture_output=True, text=True).stdout.strip()
    num, den = pr.split('/'); fps = float(num) / float(den)
    refidx = sorted(set(int(round(t * fps)) for t in pts))
    tp = [r for r in refidx if any(abs(r - o) <= 1 for o in ours)]
    fp = [o for o in ours if not any(abs(r - o) <= 1 for r in refidx)]
    ok('scene_cut_detection_vs_ffmpeg_scene_score', len(tp) >= 0.8 * max(1, len(refidx)) and len(fp) <= max(2, 0.25 * len(ours)),
       {'ffmpeg_cuts(scene>0.3)': refidx, 'ours': ours, 'matched': len(tp), 'false_positive': fp})

def _eetf(n, src=1000.0, dst=250.0):
    e = pq_enc(n) / pq_enc(np.array(src)); mL = pq_enc(np.array(dst)) / pq_enc(np.array(src)); ks = 1.5 * mL - 0.5
    t = np.clip((e - ks) / (1 - ks), 0, 1)
    p = (2 * t**3 - 3 * t**2 + 1) * ks + (t**3 - 2 * t**2 + t) * (1 - ks) + (-2 * t**3 + 3 * t**2) * mL
    return pq_dec(np.minimum(np.where(e > ks, p, e), 1) * pq_enc(np.array(src)))

def sdr_as_pq(src):
    """Reference rendering of an SDR BT.709 image inside the PQ/BT.2020 container (no expansion),
    so 'before' and 'after' go through the same viewing transform."""
    return pq_enc(203.0 * np.einsum('ij,hwj->hwi', M709_2020, np.clip(src, 0, 1) ** 2.4))

def tonemap_preview(pqrgb):
    """PQ BT.2020 -> SDR for viewing review images: the app's own PQ->SDR mapping
    (BT.2390 EETF on max(RGB), hue preserving, BT.709 gamut, 2.2). Same transform for before/after."""
    nits = pq_dec(pqrgb)
    m = nits.max(-1, keepdims=True)
    nits = nits * np.where(m > 1e-4, _eetf(m) / np.maximum(m, 1e-4), 1)
    lin = np.einsum('ij,hwj->hwi', M2020_709, nits / 250.0)
    y = lin @ np.array([0.2126, 0.7152, 0.0722]); mn = lin.min(-1)
    k = np.where(mn < 0, y / np.maximum(y - mn, 1e-6), 1)[..., None]
    lin = y[..., None] + (lin - y[..., None]) * k
    return np.clip(lin, 0, 1) ** (1 / 2.2)

def save_png(path, rgb):
    h, w, _ = rgb.shape
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb48le', '-s', '%dx%d' % (w, h), '-i', '-', path],
                   input=(np.clip(rgb, 0, 1) * 65535).astype('<u2').tobytes(), check=True)

def step_stills(jname='stills.json'):
    j = json.load(open(os.path.join(OUT, jname)))
    m = json.load(open(os.path.join(DATA, 'meta.json'))); st = {s['name']: s for s in m['stills'] + m.get('crops', [])}
    os.makedirs(os.path.join(OUT, 'png'), exist_ok=True)
    for key, v in j.items():
        cfg = next(c for c in ['same_classic', 'same_ai50', 'same_ai100', 'ai_half', 'classic', 'ai'] if key.endswith('_' + c))
        name = key[:-(len(cfg) + 1)]
        s = st[name]; w, h = v['w'], v['h']
        out = rd('still_%s_%s_%dx%d.f32' % (name, cfg, w, h), w, h)[..., :3]
        src = np.fromfile(os.path.join(DATA, name + '.f32'), '<f4').reshape(s['h'], s['w'], 4)[::-1, :, :3]
        if (w, h) != (s['w'], s['h']):
            src = np.repeat(np.repeat(src, w // s['w'], 0), h // s['h'], 1)   # nearest, for side-by-side only
        src_prev = tonemap_preview(sdr_as_pq(src))                  # same viewing transform for both
        pair = np.concatenate([src_prev, np.ones((h, 6, 3)), tonemap_preview(out)], 1)
        save_png(os.path.join(OUT, 'png', '%s_%s.png' % (name, cfg)), pair)
    print('pngs in', os.path.join(OUT, 'png'))

def step_stills_same():
    step_stills('stills_same.json')
    # one sheet per crop: original | v0 engine | v2 classic | v2 AI 50% | v2 AI 100%  (same SDR viewing transform)
    m = json.load(open(os.path.join(DATA, 'meta.json')))
    for c in m['crops']:
        w, h = c['w'], c['h']
        src = np.fromfile(os.path.join(DATA, c['name'] + '.f32'), '<f4').reshape(h, w, 4)[::-1, :, :3]
        tiles = [tonemap_preview(sdr_as_pq(src))]
        v0 = os.path.join(OUT, 'still_%s_v0_%dx%d.f32' % (c['name'], w, h))
        if os.path.exists(v0): tiles.append(tonemap_preview(rd(os.path.basename(v0), w, h)[..., :3]))
        for cfg in ['same_classic', 'same_ai50', 'same_ai100']:
            tiles.append(tonemap_preview(rd('still_%s_%s_%dx%d.f32' % (c['name'], cfg, w, h), w, h)[..., :3]))
        sep = np.ones((h, 4, 3))
        sheet = np.concatenate(sum([[t, sep] for t in tiles], [])[:-1], 1)
        sheet = np.repeat(np.repeat(sheet, 2, 0), 2, 1)          # x2 nearest for inspection
        save_png(os.path.join(OUT, 'png', 'same_%s.png' % c['name']), sheet)

def step_uhd():
    j = {'sdr_to_hdr10': json.load(open(os.path.join(OUT, 'uhd_sdr.json')))}
    o = rd('uhd_sdr_out.f32')[:, :3]
    nits = pq_dec(o)
    ok('uhd_4k_sdr_to_hdr10_runs', j['sdr_to_hdr10']['w'] == 3840 and j['sdr_to_hdr10']['h'] == 2160 and np.isfinite(o).all() and nits.max() <= 1000.5,
       {'out': [j['sdr_to_hdr10']['w'], j['sdr_to_hdr10']['h']], 'peak_nits': float(nits.max()), 'mean_nits': float(nits.mean()), 'swiftshader_ms': j['sdr_to_hdr10']['ms']})
    exp = np.load(os.path.join(DATA, 'uhd_pq_expected.npy')).reshape(-1, 3)
    p = rd('uhd_pq_out.f32')[:, :3]
    err = np.abs(p - exp) * 1023
    ok('uhd_4k_hdr10_source_kept_in_pq', np.percentile(err, 99.9) < 2.5 and np.isfinite(p).all(),
       {'p50_err_10bit_codes': float(np.median(err)), 'p99.9_err': float(np.percentile(err, 99.9)), 'max_err': float(err.max()),
        'source_peak_nits': float(pq_dec(exp).max()), 'output_peak_nits': float(pq_dec(p).max()), 'note': 'no enhancement enabled: PQ in -> PQ out; chroma 4:4:4 in this test'})

SRVGG_MAC_PER_LR_PX = 3 * 64 * 9 + 32 * 64 * 64 * 9 + 64 * 48 * 9      # conv_first + 32 body + conv_last

def ssim_y(a, b):
    """SSIM of BT.709 luma, 7x7 box window (Wang et al. constants)."""
    k = np.array([0.2126, 0.7152, 0.0722]); x = a @ k; y = b @ k
    def box(z):
        c = np.cumsum(np.cumsum(np.pad(z, ((1, 0), (1, 0))), 0), 1)
        return (c[7:, 7:] - c[:-7, 7:] - c[7:, :-7] + c[:-7, :-7]) / 49
    mx, my = box(x), box(y); vx = box(x * x) - mx ** 2; vy = box(y * y) - my ** 2; cxy = box(x * y) - mx * my
    c1, c2 = 0.01 ** 2, 0.03 ** 2
    return float((((2 * mx * my + c1) * (2 * cxy + c2)) / ((mx ** 2 + my ** 2 + c1) * (vx + vy + c2))).mean())

def step_same_res_eval():
    m = json.load(open(os.path.join(DATA, 'meta.json'))); j = json.load(open(os.path.join(OUT, 'same_res_eval.json')))
    cfgs = ['classic', 'ai100', 'ai50', 'ai25', 'model100', 'model50', 'model25']
    table, complete = {}, True
    os.makedirs(os.path.join(OUT, 'png'), exist_ok=True)
    for d in m['degraded']:
        g, r = d['deg'], d['ref']; w, h = g['w'], g['h']
        ref = np.fromfile(os.path.join(DATA, r['name'] + '.f32'), '<f4').reshape(h, w, 4)[::-1, :, :3]
        deg = np.fromfile(os.path.join(DATA, g['name'] + '.f32'), '<f4').reshape(h, w, 4)[::-1, :, :3]
        row = {'degraded_input': {'psnr': psnr(deg, ref), 'ssim': ssim_y(deg, ref)}}
        tiles = [ref, deg]
        for c in cfgs:
            f = 'sre_%s_%s.f32' % (g['name'], c)
            if not os.path.exists(os.path.join(OUT, f)): complete = False; continue
            o = rd(f, w, h)[..., :3]
            if not np.isfinite(o).all(): complete = False
            row[c] = {'psnr': psnr(o, ref), 'ssim': ssim_y(o, ref), 'swiftshader_ms': j[g['name'] + '_' + c]['ms']}
            if c in ('classic', 'ai25', 'ai50', 'ai100'): tiles.append(o)
        table[g['name']] = row
        sep = np.ones((h, 4, 3))
        save_png(os.path.join(OUT, 'png', 'same_res_eval_%s.png' % g['name']), np.repeat(np.repeat(np.concatenate(sum([[t, sep] for t in tiles], [])[:-1], 1), 2, 0), 2, 1))
    mean = {c: {'psnr': float(np.mean([t[c]['psnr'] for t in table.values() if c in t])), 'ssim': float(np.mean([t[c]['ssim'] for t in table.values() if c in t]))}
            for c in ['degraded_input'] + cfgs}
    cost = {('ai%d' % s): {'mac_per_output_px': SRVGG_MAC_PER_LR_PX * (s / 100) ** 2,
                           'tmac_per_4k_frame': SRVGG_MAC_PER_LR_PX * (s / 100) ** 2 * 3840 * 2160 / 1e12} for s in (100, 50, 25)}
    ok('same_res_eval_complete', complete and len(table) == len(m['degraded']),
       {'mean': mean, 'cost': cost, 'per_crop': table,
        'note': 'ground truth = original crop; degradation = 2x box down + bilinear up + noise 3/255 + 8-bit. Measurement, not a pass/fail of quality.'})

def step_preview_crop():
    j = json.load(open(os.path.join(OUT, 'preview_crop.json')))
    os.makedirs(os.path.join(OUT, 'png'), exist_ok=True)
    for nm, v in j.items():
        g = v['geom']; w, h = g['visW'], g['visH']
        full = rd('pc_%s_full.f32' % nm, w, h)[..., :3]
        crop = rd('pc_%s_crop.f32' % nm, w, h)[..., :3]
        naive = rd('pc_%s_naive.f32' % nm, w, h)[..., :3]
        d = np.abs(full - crop).max(); dn = np.abs(full - naive)
        stats_ok = v.get('analysis_stats_equal_full_run', True)   # older runs used the full pipeline's own stats
        ok('preview_crop_equals_full_frame_' + nm, d < 2e-3 and np.isfinite(crop).all() and stats_ok,
           {'max_diff_pq': float(d), 'analysis_stats_equal_full_run': v.get('analysis_stats_equal_full_run'),
            'naive_crop_max_diff_pq': float(dn.max()), 'naive_crop_p99_diff': float(np.percentile(dn, 99)),
            'margin_src_px': g['margin'], 'processed_fraction_of_frame': v['processed_fraction'],
            'tiles_full': v.get('tiles_full'), 'tiles_crop': v.get('tiles_crop')})
        sep = np.ones((h, 4, 3))
        save_png(os.path.join(OUT, 'png', 'preview_crop_%s.png' % nm),
                 np.concatenate([tonemap_preview(full), sep, tonemap_preview(crop), sep, tonemap_preview(naive)], 1))

def step_visual():
    # visual sheets exist for every crop (judged by eye, not by a number)
    m = json.load(open(os.path.join(DATA, 'meta.json')))
    need = [os.path.join(OUT, 'png', 'same_%s.png' % c['name']) for c in m['crops']]
    ok('visual_comparison_sheets_rendered', all(os.path.exists(f) for f in need), {'sheets': [os.path.basename(f) for f in need]})

def step_banding():
    m = json.load(open(os.path.join(DATA, 'meta.json'))); d = m['sky8']; w, h = d['w'], d['h']
    res = {}
    for nm in ['nodeband', 'deband']:
        o = rd('band_%s.f32' % nm, w, h)[..., :3]
        col = o[:, w // 2, 2] * 1023                         # blue channel, 10-bit PQ codes
        steps = np.abs(np.diff(col))
        res[nm] = {'max_step_10bit_codes': steps.max(), 'p99_step': np.percentile(steps, 99),
                   'monotonic': bool(np.all(np.diff(col) <= 1e-3) or np.all(np.diff(col) >= -1e-3))}
    ok('deband_reduces_hdr_step_size', res['deband']['max_step_10bit_codes'] < 0.6 * res['nodeband']['max_step_10bit_codes'], res)

def step_video():
    m = json.load(open(os.path.join(DATA, 'meta.json'))); s = m['seqs']['vtest']; w, h, n = s['w'], s['h'], s['n']
    j = json.load(open(os.path.join(OUT, 'video.json')))
    frames = [rd('vt_out_%02d.f32' % i, w, h)[..., :3] for i in range(n)]
    # independent MaxCLL / MaxFALL from the saved frames
    mx = [pq_dec(f).max(-1) for f in frames]
    maxcll = max(x.max() for x in mx); maxfall = max(x.mean() for x in mx)
    gcll = max(c['max'] for c in j['cll']); gfall = max(c['mean'] for c in j['cll'])
    ok('maxcll_maxfall_gpu_measurement_matches_numpy', abs(gcll - maxcll) / maxcll < 0.005 and abs(gfall - maxfall) / maxfall < 0.005,
       {'gpu_maxcll': gcll, 'numpy_maxcll': maxcll, 'gpu_maxfall': gfall, 'numpy_maxfall': maxfall})
    # encode HEVC Main10 HDR10 with x265 using the *measured* light levels, then verify file
    cll, fall = int(round(maxcll)), int(round(maxfall))
    raw = b''.join((np.clip(f, 0, 1) * 65535).astype('<u2').tobytes() for f in frames)
    path = os.path.join(OUT, 'vtest_hdr10.mp4')
    x265 = ('colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited:hdr10=1:repeat-headers=1:'
            'master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,50):max-cll=%d,%d' % (cll, fall))
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb48le', '-s', '%dx%d' % (w, h), '-r', '10', '-i', '-',
                    '-vf', 'scale=out_color_matrix=bt2020nc:out_range=tv,format=yuv420p10le', '-c:v', 'libx265', '-preset', 'fast', '-crf', '16',
                    '-x265-params', x265, '-tag:v', 'hvc1', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv', path],
                   input=raw, check=True)
    pr = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames', '-read_intervals', '%+#1',
                                    '-show_entries', 'stream=codec_name,profile,pix_fmt,color_range,color_space,color_transfer,color_primaries,nb_frames:frame=side_data_list',
                                    '-of', 'json', path], capture_output=True, text=True).stdout)
    st = pr['streams'][0]; sd = pr['frames'][0].get('side_data_list', [])
    tags_ok = st['profile'] == 'Main 10' and st['pix_fmt'] == 'yuv420p10le' and st['color_transfer'] == 'smpte2084' and st['color_primaries'] == 'bt2020' and st['color_space'] == 'bt2020nc' and st['color_range'] == 'tv'
    cl = [x for x in sd if x.get('side_data_type') == 'Content light level metadata']
    md = [x for x in sd if x.get('side_data_type') == 'Mastering display metadata']
    # natural frames: report quality only (x265 CRF16 + 4:2:0 chroma subsampling dominate)
    dec = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-vf', 'scale=in_color_matrix=bt2020nc:in_range=tv:out_range=pc,format=rgb48le', '-f', 'rawvideo', '-'], capture_output=True, check=True).stdout
    dd = np.frombuffer(dec, '<u2').reshape(-1, h, w, 3).astype(np.float32) / 65535
    nat_psnr = psnr(dd[5], frames[5])
    # flat patches through the same tagging/encode/decode chain: catches range/matrix/transfer errors
    pw, ph = 256, 256
    vals = np.array([pq_enc(np.array([n])) [0] for n in [0, 1, 10, 50, 100, 203, 400, 1000]])
    pat = np.zeros((ph, pw, 3), np.float32)
    cols = [(v, v, v) for v in vals] + [(pq_enc(np.array([300]))[0], 0.2, 0.2), (0.2, pq_enc(np.array([300]))[0], 0.2), (0.2, 0.2, pq_enc(np.array([300]))[0])]
    for i, c in enumerate(cols[:16]):
        pat[(i // 4) * 64:(i // 4 + 1) * 64, (i % 4) * 64:(i % 4 + 1) * 64] = c
    ppath = os.path.join(OUT, 'patches_hdr10.mp4')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb48le', '-s', '%dx%d' % (pw, ph), '-r', '10', '-i', '-',
                    '-vf', 'scale=out_color_matrix=bt2020nc:out_range=tv,format=yuv420p10le', '-c:v', 'libx265', '-preset', 'fast', '-crf', '4',
                    '-x265-params', x265, '-tag:v', 'hvc1', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv', ppath],
                   input=((np.clip(pat, 0, 1) * 65535).astype('<u2').tobytes()) * 3, check=True)
    pd = subprocess.run(['ffmpeg', '-v', 'error', '-i', ppath, '-frames:v', '1', '-vf', 'scale=in_color_matrix=bt2020nc:in_range=tv:out_range=pc,format=rgb48le', '-f', 'rawvideo', '-'], capture_output=True, check=True).stdout
    pdd = np.frombuffer(pd, '<u2').reshape(ph, pw, 3).astype(np.float32) / 65535
    perr = max(np.abs(pdd[(i // 4) * 64 + 16:(i // 4) * 64 + 48, (i % 4) * 64 + 16:(i % 4) * 64 + 48].mean((0, 1)) - np.array(c)).max() for i, c in enumerate(cols[:16])) * 1023
    ok('hdr10_file_tags_metadata_and_pixels', tags_ok and cl and md and int(cl[0]['max_content']) == cll and perr < 1.5 and dd.shape[0] == n,
       {'stream': st, 'content_light': cl, 'mastering': md[:1], 'decoded_frames': int(dd.shape[0]),
        'flat_patch_max_err_10bit_codes': perr, 'natural_frame_psnr_db_after_x265': nat_psnr})
    cp = os.environ.get('HEVC_INSPECT_CP')
    if cp:
        annexb = os.path.join(OUT, 'vtest_hdr10.hevc')
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', path, '-c', 'copy', '-bsf:v', 'hevc_mp4toannexb', '-f', 'hevc', annexb], check=True)
        mine = json.loads(subprocess.run(['java', '-cp', cp, 'HevcInspect', annexb], capture_output=True, text=True, check=True).stdout.strip().splitlines()[-1])
        agree = (mine['bitDepth'] == 10 and mine['primaries'] == 9 and mine['transfer'] == 16 and mine['matrix'] == 9 and not mine['fullRange']
                 and mine['mdcv'] and mine['cll'] and mine['maxCll'] == int(cl[0]['max_content']) and mine['maxFall'] == int(cl[0]['max_average'])
                 and st['pix_fmt'] == 'yuv420p10le' and st['color_transfer'] == 'smpte2084')
        ok('app_hevc_parser_agrees_with_ffprobe', agree, {'app_parser': mine, 'ffprobe_cll': cl[0]})
    prev = [tonemap_preview(frames[i]) for i in (0, 24, 47)]
    src = [np.fromfile(os.path.join(DATA, 'vt_%02d.f32' % i), '<f4').reshape(h, w, 4)[::-1, :, :3] for i in (0, 24, 47)]
    os.makedirs(os.path.join(OUT, 'png'), exist_ok=True)
    save_png(os.path.join(OUT, 'png', 'vtest_f24_before_after.png'), np.concatenate([tonemap_preview(sdr_as_pq(src[1])), np.ones((h, 6, 3)), prev[1]], 1))

JVM_REQUIRED = [
    'A_black_opener_new_INCONCLUSIVE', 'A_black_opener_v2.1_rejected', 'A_natural_new_CONSISTENT', 'A_natural_8bit_stage_new_SUSPECT_8BIT',
    'A_hard_signalling_hdr10_pass', 'A_hard_signalling_8bit_fail', 'B_bitrate_change_invalidates', 'B_resolution_change_invalidates',
    'B_fps_30_vs_29.97_invalidates', 'B_fps_60_vs_59.94_distinct', 'B_v2.1_accepted_29.97_result_for_30fps', 'B_v2.1_ignored_bitrate',
    'B_sample_values_not_accepted_as_final_measured', 'B_pts_no_accumulated_error', 'c1_both_missing_inserted_annexb',
    'c1_both_missing_inserted_lengthprefixed', 'c2_mdcv_only_inserts_only_cll_annexb', 'c3_cll_only_inserts_only_mdcv_annexb',
    'c4_both_present_correct_unchanged_annexb', 'c5a_encoder_cll_conflicts_rejected_annexb', 'c5b_duplicate_conflicting_rejected_annexb',
    'framing_independent_result', 'ffmpeg_decodes_sei_case1_both_missing', 'ffprobe_reads_sei_sei_case2_mdcv_only',
    'ffprobe_reads_sei_sei_case3_cll_only',     'D_decoder_ERROR_is_fail_not_partial', 'D_decoder_TIMEOUT_is_fail_not_partial',
    'D_not_run_blocks_export', 'D_v2.1_decoder_error_counted_as_partial_success', 'D_no_fully_proven_wording',
    'source_settings_shared_peak_from_maxcll', 'source_settings_shared_peak_from_mastering', 'source_settings_shared_ai_blocked_on_hdr',
    'mem_smallest_tile_over_conservative_budget_is_refused', 'mem_preview_plan_smaller_than_export_and_includes_bitmaps']

def step_jvm():
    """Records every line of this run's JVM test log (tools/jvmtests/run.sh) as its own check."""
    log = os.environ.get('JVM_LOG') or os.path.join(OUT, 'jvm_tests.log')
    lines = open(log, encoding='utf-8', errors='replace').read().splitlines()
    summary = None
    for ln in lines:
        for tag in ('PASS ', 'FAIL ', 'MISSING '):
            if ln.startswith(tag):
                name = ln[len(tag):].split(' ', 1)[0]
                REPORT['jvm:' + name] = {'pass': tag == 'PASS ', 'status': tag.strip(), 'line': ln[:300]}
        if ln.startswith('JVM SUMMARY'): summary = ln
    ok('jvm_summary_all_pass_none_missing', summary is not None and ' fail=0 ' in summary + ' ' and ' missing=0 ' in summary + ' ' and 'exit=0' in summary,
       {'summary': summary, 'log': log})

REQUIRED = ['jvm_summary_all_pass_none_missing'] + ['jvm:' + n for n in JVM_REQUIRED] + [
    'sdr_to_hdr_expand0_is_bt2408_mapping', 'ramp_e1_monotonic_midtones_kept_peak_bounded', 'ramp_e1_g22_monotonic_midtones_kept_peak_bounded',
    'sdr_to_sdr_identity_no_double_transfer', 'sdr_to_sdr_identity_random_colours', 'rand_hdr_e1_hue_preserved_no_negative',
    'rand_hdr_vib_hue_preserved_no_negative', 'pq_to_sdr_monotonic_no_hard_clip', 'pq_to_pq_identity', 'yuv_pq10_ycbcr_to_working_once',
    'yuv_hlg10_ycbcr_to_working_once', 'yuv_sdr8_ycbcr_to_working_once', 'present_tpdf_dither', 'ai_glsl_matches_reference_network',
    'ai_tiling_seamless_x4', 'ai_tiling_seamless_resampled_0.5', 'ai_tiling_seamless_resampled_0.75', 'motion_compensation_quality_vs_true_motion',
    'temporal_denoise_improves_psnr_on_moving_pan', 'temporal_no_ghost_trail_behind_fast_object', 'temporal_with_resolution_change_up_and_down',
    'flash_preserved_not_averaged_and_not_a_cut', 'scene_cut_detection_vs_ffmpeg_scene_score', 'deband_reduces_hdr_step_size',
    'maxcll_maxfall_gpu_measurement_matches_numpy', 'hdr10_file_tags_metadata_and_pixels', 'app_hevc_parser_agrees_with_ffprobe',
    'uhd_4k_sdr_to_hdr10_runs', 'uhd_4k_hdr10_source_kept_in_pq', 'visual_comparison_sheets_rendered',
    'preview_crop_equals_full_frame_classic_up15', 'preview_crop_equals_full_frame_ai_same', 'preview_crop_equals_full_frame_ai_x2_half',
    'same_res_eval_complete']

def source_fingerprint():
    import hashlib
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
    h = hashlib.sha256()
    for d in ['app/src/main/assets', 'app/src/main/java', 'tools/harness']:
        for dp, dn, fn in sorted(os.walk(os.path.join(root, d))):
            dn.sort()
            for f in sorted(fn):
                if f.endswith(('.json', '.log')) or 'node_modules' in dp: continue
                fp = os.path.join(dp, f)
                h.update(os.path.relpath(fp, root).encode()); h.update(open(fp, 'rb').read())
    return h.hexdigest()

def step_verify():
    """Final gate: every required check must be present in THIS run's report and pass."""
    missing = [k for k in REQUIRED if k not in REPORT_ALL]
    failed = [k for k, v in REPORT_ALL.items() if not v['pass']]          # any failure, required or not
    print('REQUIRED %d · present %d · failed %d · missing %d' % (len(REQUIRED), len(REQUIRED) - len(missing), len(failed), len(missing)))
    for k in missing: print('MISSING', k)
    for k in failed: print('FAILED ', k)
    if missing or failed: sys.exit(1)
    print('ALL REQUIRED CHECKS PASS')

if __name__ == '__main__':
    # one report per output directory; a run must start from an empty directory (run_ci.sh does)
    rp = os.path.join(OUT, 'report.json')
    REPORT_ALL = json.load(open(rp))['checks'] if os.path.exists(rp) else {}
    for st in sys.argv[3:]:
        if st == 'verify': continue
        try:
            globals()['step_' + st]()
        except Exception as e:                      # a crashing step is a recorded failure, not a silent gap
            import traceback; traceback.print_exc()
            REPORT['step_%s_crashed' % st] = {'pass': False, 'error': repr(e)}
            print('FAIL step %s crashed: %r' % (st, e))
        REPORT_ALL.update(REPORT)
        REPORT.clear()
    json.dump({'source_sha256': source_fingerprint(), 'out_dir': OUT, 'checks': REPORT_ALL}, open(rp, 'w'), indent=1, default=float)
    if 'verify' in sys.argv[3:]:
        step_verify()
