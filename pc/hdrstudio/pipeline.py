"""Per-frame processing chain: the phone app's FramePipeline on desktop GL.

 input (E_SDR or E_PQ) -> deband -> [noise estimate] -> spatial denoise -> luma pyramid ->
 scene-cut analysis -> motion estimation -> { AI path: LR resize -> SR tiles -> blend ->
 temporal } | { classic path: temporal -> resize } -> sharpen -> hdr_map -> final (FP16 codes)

Same shaders, same order, same constants as stages/FramePipeline.java. The AI stage differs in
where the network runs (ONNX runtime instead of GLSL) and accepts x2 as well as x4 models.
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass, asdict, fields

import numpy as np

from .glkit import GlKit, Tex


@dataclass
class Params:
    work_domain: int = 0            # 0 E_SDR, 1 E_PQ (decided from the source)
    out_mode: int = 0               # 0 HDR10, 1 SDR
    deband: bool = True
    deband_threshold: float = 1.5 / 255
    deband_radius: float = 12.0
    denoise_luma: float = 0.3
    denoise_chroma: float = 0.5
    grain_keep: float = 0.25
    temporal: bool = True
    temporal_strength: float = 0.7
    ai: bool = False
    ai_amount: float = 0.7
    ai_work_scale: float = 1.0
    ai_skin_protect: float = 0.8
    ai_texture: float = 0.8
    ai_texture_limit: float = 0.03
    sharpen: float = 0.35
    sharpen_skin_protect: float = 0.5
    sdr_gamma: int = 0              # 0 BT.1886 2.4, 1 2.2
    ref_white: float = 203.0
    peak: float = 1000.0
    expand: float = 0.8
    knee: float = 0.5
    vibrance: float = 0.1
    hdr_skin_protect: float = 0.6
    src_peak: float = 1000.0
    sdr_peak_nits: float = 250.0
    out_w: int = 0
    out_h: int = 0

    def to_dict(self):
        return asdict(self)

    @classmethod
    def from_dict(cls, d):
        names = {f.name for f in fields(cls)}
        return cls(**{k: v for k, v in d.items() if k in names})


class SceneAnalyzer:
    """Scene-cut detection and scene statistics from the coarsest luma level (1/16 res)."""

    def __init__(self):
        self.prev = None
        self.expand = math.nan
        self.last_d = self.last_hd = self.bright_frac = 0.0

    def is_cut(self, thumb: np.ndarray) -> bool:
        p, self.prev = self.prev, thumb
        if p is None or p.shape != thumb.shape:
            self.last_d = self.last_hd = 0.0
            return True
        c = (thumb - thumb.mean()) / (thumb.std() + 0.02)
        q = (p - p.mean()) / (p.std() + 0.02)
        self.last_d = float(np.abs(c - q).mean())
        hc = np.bincount(np.clip(np.floor(thumb * 32), 0, 31).astype(np.int64).ravel(), minlength=32)
        hp = np.bincount(np.clip(np.floor(p * 32), 0, 31).astype(np.int64).ravel(), minlength=32)
        self.last_hd = float(np.abs(hc - hp).sum() / (2.0 * thumb.size))
        return self.last_d > 0.45

    def adapt_expand(self, thumb, user, knee, dt, cut, sdr_gamma) -> float:
        g = 2.2 if sdr_gamma == 1 else 2.4
        frac = float((np.clip(thumb, 0, 1) ** g > knee).mean())
        self.bright_frac = frac
        t = min(1.0, max(0.0, (frac - 0.10) / (0.45 - 0.10)))
        target = user * (1 - 0.6 * t * t * (3 - 2 * t))
        if cut or math.isnan(self.expand):
            self.expand = target
        else:
            self.expand += (target - self.expand) * (1 - math.exp(-dt / 0.4))
        return self.expand


class FramePipeline:
    def __init__(self, k: GlKit, src_w: int, src_h: int, p: Params, sr=None):
        self.k, self.p, self.sr = k, p, sr if p.ai else None
        self.src_w, self.src_h, self.out_w, self.out_h = src_w, src_h, p.out_w, p.out_h
        self.a, self.b = k.tex(src_w, src_h), k.tex(src_w, src_h)
        self.zero = k.tex(1, 1, True, np.zeros(4, "f2").tobytes())
        self.lv, self.lv_prev, self.mv = [], [None] * 4, [None] * 4
        w, h = src_w, src_h
        for i in range(4):
            w, h = (w + 1) // 2, (h + 1) // 2
            self.lv.append(k.tex(w, h))
            if p.temporal:
                self.lv_prev[i] = k.tex(w, h)
                self.mv[i] = k.tex(w, h, True)
        self.mv_med = self.mv_final = None
        if p.temporal:
            self.mv_med = k.tex(self.lv[1].w, self.lv[1].h, True)
            self.mv_final = k.tex(self.lv[0].w, self.lv[0].h, True)
        self.resized = (self.out_w, self.out_h) != (src_w, src_h)
        self.tmp_h = self.o1 = self.o2 = self.lr_tmp = self.lr = self.ai_out = self.sr_tex = None
        if self.resized:
            self.tmp_h, self.o1 = k.tex(self.out_w, src_h), k.tex(self.out_w, self.out_h)
        if self.resized or self.sr:
            self.o2 = k.tex(self.out_w, self.out_h)
        if self.sr:
            self.lr_w = max(8, round(src_w * p.ai_work_scale))
            self.lr_h = max(8, round(src_h * p.ai_work_scale))
            if (self.lr_w, self.lr_h) != (src_w, src_h):
                self.lr_tmp, self.lr = k.tex(self.lr_w, src_h), k.tex(self.lr_w, self.lr_h)
            self.ai_out = k.tex(self.out_w, self.out_h)
            n = (self.sr.spec.tile + 2 * 2) * self.sr.scale
            self.sr_tex = k.tex(n, n, True, components=3, dtype="f4")
        self.fin = k.tex(self.out_w, self.out_h, True)
        self.hist, self.hist_cur, self.hist_idx = [None, None], None, 0
        self.scene = SceneAnalyzer()
        self.sigma = self.sigma_post = 0.004
        self.frame = 0
        self.last_cut = True
        self.gate = None
        self.info = {}

    # ------------------------------------------------------------------ state (resume)
    def state(self):
        return {"sigma": self.sigma, "sigmaPost": self.sigma_post,
                "expand": None if math.isnan(self.scene.expand) else self.scene.expand}

    def restore(self, s):
        self.sigma, self.sigma_post = s.get("sigma", self.sigma), s.get("sigmaPost", self.sigma_post)
        if s.get("expand") is not None:
            self.scene.expand = s["expand"]

    # ------------------------------------------------------------------ helpers
    def _resize(self, src: Tex, dst: Tex, tmp: Tex):
        self.k.run("resize.frag", tmp, {"uIn": src, "uDir": 0, "uScale": tmp.w / src.w, "uLobes": 3.0, "uAntiring": 0.8})
        self.k.run("resize.frag", dst, {"uIn": tmp, "uDir": 1, "uScale": dst.h / tmp.h, "uLobes": 3.0, "uAntiring": 0.8})

    def _estimate_noise(self, src: Tex) -> float:
        """Immerkaer noise estimate: 8x8 block means of |N*Y|, 20th percentile of full blocks."""
        k = self.k
        r = k.tex((src.w + 7) // 8, (src.h + 7) // 8, True)
        k.run("reduce.frag", r, {"uIn": src, "uMode": 2, "uDomain": self.p.work_domain})
        px = k.read(r)
        k.delete(r)
        vals = np.sort(px[..., 1][px[..., 2] > 0.99])
        return float(vals[int(vals.size * 0.2)] * math.sqrt(math.pi / 2) / 6) if vals.size else 0.0

    def _pick(self, like: Tex, x, y) -> Tex:
        for t in (self.a, self.b, self.o1, self.o2):
            if t is not None and (t.w, t.h) == (like.w, like.h) and t is not x and t is not y:
                return t
        raise RuntimeError(f"no free {like.w}x{like.h} buffer")

    def _me(self, level, coarse, has_coarse, neighbours, radius, half, lam):
        self.k.run("me_search.frag", self.mv[level], {
            "uCur": self.lv[level], "uPrev": self.lv_prev[level], "uCoarse": coarse, "uHasCoarse": has_coarse,
            "uNeighbours": neighbours, "uRadius": radius, "uHalf": half, "uLambda": lam})

    def _temporal(self, cur: Tex, mv_to_target: float) -> Tex:
        if self.hist_cur is None:
            return cur
        dst = self._pick(cur, cur, self.hist_cur)
        p = self.p
        self.k.run("temporal.frag", dst, {
            "uCur": cur, "uPrev": self.hist_cur, "uMv": self.mv_final, "uMvScale": 2 * mv_to_target,
            "uSigma": self.sigma_post, "uStrength": p.temporal_strength, "uClampK": 1.5, "uDomain": p.work_domain})
        return dst

    def _keep_history(self, res: Tex):
        if self.hist[0] is None:
            self.hist = [self.k.tex(res.w, res.h), self.k.tex(res.w, res.h)]
        dst = self.hist[self.hist_idx]
        self.hist_idx ^= 1
        self.k.run("copy.frag", dst, {"uIn": res})
        self.hist_cur = dst

    def _run_ai(self, lr_in: Tex) -> int:
        k, sr, S = self.k, self.sr, self.sr.scale
        rgb = np.ascontiguousarray(k.read(lr_in)[..., :3])
        fx, fy = self.out_w / (lr_in.w * S), self.out_h / (lr_in.h * S)

        def emit(tile, rx0, ry0, x0, y0, cw, ch):
            th, tw = tile.shape[:2]
            self.sr_tex.t.write(tile.tobytes(), viewport=(0, 0, tw, th), alignment=1)
            tx0, tx1 = round(x0 * S * fx), round((x0 + cw) * S * fx)
            ty0, ty1 = round(y0 * S * fy), round((y0 + ch) * S * fy)
            if tx1 > tx0 and ty1 > ty0:
                k.run("sr_resample.frag", self.ai_out, {
                    "uSR": self.sr_tex, "uSrOrigin": (float(rx0 * S), float(ry0 * S)),
                    "uScale": (fx, fy), "uSrValid": (tw, th)}, scissor=(tx0, ty0, tx1 - tx0, ty1 - ty0))
        return sr.run(rgb, emit, self.gate)

    # ------------------------------------------------------------------ frame
    def process(self, inp: Tex, dt: float) -> Tex:
        t0 = time.perf_counter()
        k, p, info = self.k, self.p, {}
        cur = inp
        if p.deband:
            k.run("deband.frag", self.a, {
                "uIn": cur, "uThreshold": p.deband_threshold * (1.0 if p.work_domain == 0 else 0.25),
                "uRadius": p.deband_radius, "uIterations": 2, "uOrigin": (0.0, 0.0)})
            cur = self.a
        refresh = self.frame % 8 == 0 or self.last_cut
        if refresh:
            self.sigma = self._estimate_noise(cur)
        if p.denoise_luma > 0 or p.denoise_chroma > 0:
            dst = self.b if cur is self.a else self.a
            k.run("denoise.frag", dst, {
                "uIn": cur, "uSigma": self.sigma, "uLumaAmount": p.denoise_luma, "uChromaAmount": p.denoise_chroma,
                "uGrainKeep": p.grain_keep, "uDomain": p.work_domain})
            cur = dst
            if refresh:
                self.sigma_post = self._estimate_noise(cur)
        else:
            self.sigma_post = self.sigma
        info["sigma"], info["sigmaPost"] = self.sigma, self.sigma_post

        if p.temporal:
            self.lv, self.lv_prev = self.lv_prev, self.lv
        k.run("luma_down.frag", self.lv[0], {"uIn": cur, "uFromRgb": True, "uDomain": p.work_domain})
        for i in range(1, 4):
            k.run("luma_down.frag", self.lv[i], {"uIn": self.lv[i - 1], "uFromRgb": False, "uDomain": 0})
        thumb = np.ascontiguousarray(k.read(self.lv[3])[..., 0])
        cut = self.scene.is_cut(thumb)
        info["cut"], info["cutD"] = cut, self.scene.last_d
        self.last_cut = cut
        temporal = p.temporal and p.temporal_strength > 0 and not cut
        if temporal:
            self._me(3, self.zero, False, False, 4, 2, 0.002)
            self._me(2, self.mv[3], True, True, 1, 2, 0.002)
            self._me(1, self.mv[2], True, True, 1, 2, 0.004)
            k.run("mv_median.frag", self.mv_med, {"uIn": self.mv[1]})
            self._me(0, self.mv_med, True, False, 1, 1, 0.004)
            k.run("mv_median.frag", self.mv_final, {"uIn": self.mv[0]})
        expand = p.expand
        if p.work_domain == 0 and p.out_mode == 0:
            expand = self.scene.adapt_expand(thumb, p.expand, p.knee, dt, cut, p.sdr_gamma)
        info["expand"] = expand

        if self.sr:
            ta = time.perf_counter()
            lr_in = cur
            if self.lr is not None:
                self._resize(cur, self.lr, self.lr_tmp)
                lr_in = self.lr
            info["tiles"] = self._run_ai(lr_in)
            base = cur
            if self.resized:
                self._resize(cur, self.o1, self.tmp_h)
                base = self.o1
            k.run("ai_blend.frag", self.o2, {"uBase": base, "uAi": self.ai_out, "uAmount": p.ai_amount, "uSkinProtect": p.ai_skin_protect,
                                                 "uTexture": p.ai_texture, "uTexLimit": p.ai_texture_limit,
                                                 "uTexRadius": max(1.0, 0.75 * self.out_w / self.src_w)})
            res = self.o2
            info["aiMs"] = (time.perf_counter() - ta) * 1000
            if temporal:
                res = self._temporal(res, self.out_w / self.src_w)
            if p.temporal:
                self._keep_history(res)         # AI path: temporal + history at OUTPUT resolution
        else:
            res = cur                           # classic path: temporal at SOURCE resolution, then resize
            if temporal:
                res = self._temporal(res, 1.0)
            if p.temporal:
                self._keep_history(res)
            if self.resized:
                self._resize(res, self.o1, self.tmp_h)
                res = self.o1
        if p.sharpen > 0:
            dst = self._pick(res, res, self.hist_cur)
            k.run("sharpen.frag", dst, {"uIn": res, "uAmount": p.sharpen, "uSigma": self.sigma_post,
                                         "uSkinProtect": p.sharpen_skin_protect, "uDomain": p.work_domain})
            res = dst
        k.run("hdr_map.frag", self.fin, {
            "uIn": res, "uDriveRadius": self.out_h / 140.0, "uInDomain": p.work_domain, "uOutMode": p.out_mode, "uSdrGamma": p.sdr_gamma,
            "uRefWhite": p.ref_white, "uPeak": p.peak, "uExpand": expand, "uKnee": p.knee, "uVibrance": p.vibrance,
            "uSkinProtect": p.hdr_skin_protect, "uSrcPeak": p.src_peak, "uSdrPeakNits": p.sdr_peak_nits})
        self.frame += 1
        info["totalMs"] = (time.perf_counter() - t0) * 1000
        self.info = info
        return self.fin

    def measure_light(self, tex=None):
        """MaxCLL/MaxFALL contribution of an output frame (default: the last one): (max nits, mean nits).
        Exact GPU reduction."""
        k, cur, first, owned = self.k, tex or self.fin, True, None
        while True:
            nx = k.tex((cur.w + 7) // 8, (cur.h + 7) // 8, True, dtype="f4")
            k.run("reduce.frag", nx, {"uIn": cur, "uMode": 1 if first else 0, "uDomain": 1})
            k.delete(owned)
            owned = cur = nx
            first = False
            if cur.w <= 16 and cur.h <= 16:
                break
        px = k.read(cur).astype(np.float64)
        k.delete(owned)
        ws = px[..., 2].sum()
        return float(px[..., 0].max()), float((px[..., 1] * px[..., 2]).sum() / ws) if ws > 0 else 0.0

    def release(self):
        for t in (self.a, self.b, self.zero, self.mv_med, self.mv_final, self.tmp_h, self.o1, self.o2, self.lr_tmp,
                  self.lr, self.ai_out, self.sr_tex, self.fin, *self.hist, *self.lv, *self.lv_prev, *self.mv):
            self.k.delete(t)
