"""Export planning, the resumable export job, and the single-crop preview.

Resumability: the video is encoded as independent closed-GOP chunks of ~2 s. A finished chunk
is recorded in <output>.hdrjob/state.json; after an interruption the job decodes up to the
last finished chunk, warms the temporal filter up on the 3 frames before it, and continues.
HDR10 light levels are measured on the real output frames while exporting and written into
every content-light SEI when the chunks are joined, so no separate analysis pass is needed.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import queue
import shutil
import threading
import time
from concurrent.futures import Future
from dataclasses import dataclass, asdict, fields
from pathlib import Path

import numpy as np

from . import ai, media, models, rife
from . import hdr10plus
from .glkit import GlKit
from .pipeline import FramePipeline, Params

HLG_PEAK = 1000.0       # nominal display peak the HLG signal is written for (BT.2100)

PRESETS = {
    "tiktok_hdr": {"label": "TikTok HDR10", "codec": "hevc", "hdr": True, "bits": 10, "crf": 15, "maxShort": 2160, "maxFps": 60,
                   "note": "HEVC Main10 · PQ · BT.2020 — يرفع من الهاتف أو الحاسوب؛ تيك توك يعيد الترميز دائماً"},
    "tiktok_hlg": {"label": "TikTok HLG", "codec": "hevc", "hdr": True, "trc": "hlg", "bits": 10, "crf": 15, "maxShort": 2160, "maxFps": 60,
                   "note": "HEVC Main10 · HLG · BT.2020 — صيغة HDR التي تصوّر بها الهواتف؛ تظهر صحيحة أيضاً على شاشات SDR"},
    "tiktok_sdr": {"label": "TikTok SDR (H.264)", "codec": "h264", "hdr": False, "bits": 8, "crf": 14, "maxShort": 1080, "maxFps": 60,
                   "note": "H.264 High · BT.709 · 1080p — أعلى توافق"},
    "hdr10_master": {"label": "HDR10 رئيسي", "codec": "hevc", "hdr": True, "bits": 10, "crf": 13, "maxShort": 0, "maxFps": 0,
                     "note": "أرشيف بأعلى جودة، بلا حد للدقة"},
    "sdr_master": {"label": "SDR رئيسي", "codec": "hevc", "hdr": False, "bits": 10, "crf": 14, "maxShort": 0, "maxFps": 0,
                   "note": "HEVC · BT.709 بعشر بتات لتدرجات نظيفة"},
}
QUALITY = {
    "off": {"label": "بدون AI", "model": None, "scale": 0},
    "fast": {"label": "سريع", "model": "general", "scale": 0.5},
    "detail": {"label": "دقيق", "model": "general", "scale": 1.0},
    "high": {"label": "عالٍ", "model": "x2plus", "scale": 1.0},
    "max": {"label": "أقصى", "model": "x4plus", "scale": 1.0},
}


@dataclass
class Settings:
    input: str = ""
    output: str = ""
    preset: str = "tiktok_hdr"
    resolution: str = "source"      # source | x2 | 1080 | 1440 | 2160 (short side)
    quality: str = "fast"
    ai_amount: float = 0.7
    ai_skin_protect: float = 0.8
    ai_texture: float = 0.8         # share of the fine texture the model flattened that is returned
    allow_fp16: bool = False
    deband: bool = True
    denoise_luma: float = 0.3
    denoise_chroma: float = 0.5
    grain_keep: float = 0.25
    temporal: bool = True
    temporal_strength: float = 0.7
    sharpen: float = 0.35
    peak: float = 1000.0
    expand: float = 0.8
    vibrance: float = 0.1
    hdr_skin_protect: float = 0.6
    sdr_gamma: int = 0
    crf: float = 0.0                # 0 = preset default
    enc_preset: str = "slow"
    start: float = 0.0
    duration: float = 0.0           # 0 = to the end
    fps_mode: str = "source"        # source | x2 (RIFE frame interpolation)
    hdr10plus: bool = False         # HDR10+ scene metadata on top of HDR10 (needs an extra analysis pass)

    @classmethod
    def from_dict(cls, d):
        names = {f.name: f.type for f in fields(cls)}
        return cls(**{k: v for k, v in (d or {}).items() if k in names})


@dataclass
class Plan:
    params: Params
    enc: media.EncodeConfig
    model: str | None
    lr_w: int
    lr_h: int
    notes: list
    frames: int                     # frames written to the file
    interp: bool = False            # one generated frame between every two source frames
    src_fps: float = 30.0
    hdr10plus: bool = False         # HDR10+ scene metadata (PQ output only)


def _even(v):
    return max(2, int(round(v / 2)) * 2)


def plan(s: Settings, si: media.SourceInfo) -> Plan:
    pr = PRESETS[s.preset]
    notes = []
    short = min(si.width, si.height)
    target = {"source": short, "x2": short * 2}.get(s.resolution) or int(s.resolution)
    if pr["maxShort"] and target > pr["maxShort"]:
        target = pr["maxShort"]
        notes.append(f"الدقة محدودة بـ{target}p حسب الإعداد المختار")
    f = target / short
    out_w, out_h = _even(si.width * f), _even(si.height * f)
    q = QUALITY[s.quality]
    model = q["model"]
    if model and si.is_hdr:
        model = None
        notes.append("تحسين AI معطّل لمصادر HDR (النماذج مدرّبة على SDR)؛ يُطبَّق التحسين التقليدي")
    p = Params(work_domain=1 if si.is_hdr else 0, out_mode=0 if pr["hdr"] else 1, deband=s.deband,
               denoise_luma=s.denoise_luma, denoise_chroma=s.denoise_chroma, grain_keep=s.grain_keep, temporal=s.temporal,
               temporal_strength=s.temporal_strength, ai=bool(model), ai_amount=s.ai_amount, ai_skin_protect=s.ai_skin_protect, ai_texture=s.ai_texture,
               sharpen=s.sharpen, sdr_gamma=s.sdr_gamma, peak=s.peak, expand=s.expand, vibrance=s.vibrance,
               hdr_skin_protect=s.hdr_skin_protect, out_w=out_w, out_h=out_h)
    if si.is_hdr:
        p.src_peak = si.max_cll or si.master_peak or 1000.0
    lr_w = lr_h = 0
    if model:
        S = models.REGISTRY[model].scale
        ws = q["scale"] if short > 720 else 1.0
        ws = min(1.0, max(ws, out_w / (si.width * S)))    # the AI image is never smaller than the output
        p.ai_work_scale = ws
        lr_w, lr_h = max(8, round(si.width * ws)), max(8, round(si.height * ws))
        if out_w > lr_w * S:
            notes.append(f"الناتج أكبر من ×{S} للمصدر: النموذج يكبّر ×{S} والباقي Lanczos")
    fps, interp = si.fps, False
    if s.fps_mode == "x2":
        if float(fps) * 2 > 61:
            notes.append(f"المصدر {float(fps):.2f} fps: مضاعفته تتجاوز 60، فأُلغي توليد الإطارات")
        elif not rife.available():
            raise ValueError("نموذج RIFE غير موجود في models/rife-v4.6")
        else:
            fps, interp = si.fps * 2, True
    if pr["maxFps"] and float(fps) > pr["maxFps"]:
        notes.append(f"معدل الإطارات {float(fps):.2f} أعلى من حد الإعداد {pr['maxFps']}؛ يُحتفظ بالمعدل الأصلي")
    enc = media.EncodeConfig(codec=pr["codec"], hdr=pr["hdr"], bit_depth=pr["bits"], crf=s.crf or pr["crf"], preset=s.enc_preset,
                             width=out_w, height=out_h, fps=fps, peak=s.peak, trc=pr.get("trc", "pq"))
    if enc.hdr and enc.trc == "hlg" and p.peak > HLG_PEAK:
        p.peak = enc.peak = HLG_PEAK
        notes.append(f"HLG: قمة السطوع محدودة بـ{HLG_PEAK:g} nit (العرض المرجعي للصيغة)")
    dyn = bool(s.hdr10plus) and enc.hdr and enc.trc == "pq"
    if s.hdr10plus and not dyn:
        notes.append("HDR10+ يعمل مع إخراج HDR10 (PQ) فقط؛ أُهمل لهذا الإعداد")
    dur = si.duration - s.start
    if s.duration > 0:
        dur = min(dur, s.duration)
    # source frames first; interpolation writes exactly two frames for each of them
    src_frames = max(1, int(round(dur * float(si.fps)))) if (s.start > 0 or s.duration > 0) else si.frames
    return Plan(p, enc, model, lr_w, lr_h, notes, src_frames * 2 if interp else src_frames, interp, float(si.fps), hdr10plus=dyn)


def pick_backend(bench: dict, allow_fp16: bool) -> dict:
    good = [r for r in bench["results"] if r.get("ok") or (allow_fp16 and r.get("precision") == "f16" and "tileS" in r
                                                             and r["maxErr"] < 0.08 and r["meanErr"] < 0.004)]
    return min(good, key=lambda r: r["tileS"])


# ------------------------------------------------------------------ engine thread
class Engine:
    """Owns the GL context and the loaded AI model; everything GPU runs on its one thread."""

    def __init__(self, log=print):
        self.log = log
        self.q: queue.Queue = queue.Queue()
        self.k: GlKit | None = None
        self.sr: ai.SrEngine | None = None
        self.sr_key = None
        self.rife: rife.Interpolator | None = None
        self.busy = ""
        threading.Thread(target=self._loop, daemon=True, name="engine").start()

    def _loop(self):
        while True:
            fn, fut, label = self.q.get()
            self.busy = label
            try:
                if self.k is None:
                    self.k = GlKit()
                fut.set_result(fn(self))
            except BaseException as e:
                fut.set_exception(e)
            finally:
                self.busy = ""

    def submit(self, fn, label="") -> Future:
        fut = Future()
        self.q.put((fn, fut, label))
        return fut

    def model(self, model_id, allow_fp16, log=None):
        if not model_id:
            return None
        spec = models.ensure(model_id)
        bench = ai.benchmark(spec, log or self.log)
        backend = pick_backend(bench, allow_fp16)
        key = (model_id, ai._key(backend))
        if self.sr_key != key:
            self.sr = None              # free the previous network before loading the next one
            self.sr = ai.SrEngine(model_id, log or self.log, backend)
            self.sr_key = key
        return self.sr


def _interpolator(eng: Engine, w: int, h: int, log):
    if eng.rife is None or (eng.rife.w, eng.rife.h) != (w, h):
        eng.rife = None
        eng.rife = rife.Interpolator(w, h, log)
    return eng.rife


class _Input:
    """Uploads decoder planes and converts them once into the working domain."""

    def __init__(self, k: GlKit, si: media.SourceInfo, dec: media.Decoder, work_domain: int):
        self.k, self.si, self.dec = k, si, dec
        dt = "f1" if dec.bps == 1 else "nu2"
        self.y = k.tex(dec.w, dec.h, True, components=1, dtype=dt)
        self.u = k.tex(dec.cw, dec.ch, False, components=1, dtype=dt)
        self.v = k.tex(dec.cw, dec.ch, False, components=1, dtype=dt)
        self.out = k.tex(dec.w, dec.h)
        self.uni = {"uY": self.y, "uU": self.u, "uV": self.v, "uSub": si.sub,
                    "uCodeScale": 255.0 if dec.bps == 1 else 65535.0, "uBitDepth": float(si.bit_depth),
                    "uFullRange": si.full_range, "uMatrix": si.matrix, "uTransfer": si.transfer, "uSrc2020": si.src2020,
                    "uWorkDomain": work_domain, "uHlgPeak": 1000.0}

    def load(self, planes):
        for t, p in zip((self.y, self.u, self.v), planes):
            t.t.write(p.tobytes(), alignment=1)
        self.k.run("input_planar.frag", self.out, self.uni)
        return self.out

    def release(self):
        for t in (self.y, self.u, self.v, self.out):
            self.k.delete(t)


M2020_709 = np.array([[1.660491002, -0.587641139, -0.072849862], [-0.124550474, 1.132899897, -0.008349423],
                      [-0.018150763, -0.100578898, 1.118729660]], np.float32)


def to_display(rgb: np.ndarray, hdr: bool) -> np.ndarray:
    """Final code values -> 8-bit sRGB-ish picture for the UI (HDR is tone-mapped for viewing only)."""
    rgb = np.clip(rgb.astype(np.float32), 0, 1)
    if hdr:
        p = rgb ** (1 / 78.84375)
        nits = 10000 * (np.maximum(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p)) ** (1 / 0.1593017578125)
        lin = np.clip(nits @ M2020_709.T, 0, None) / 203.0
        lin = lin * (1 + lin / 16.0) / (1 + lin)        # extended Reinhard, white at 4x reference
        rgb = np.clip(lin, 0, 1) ** (1 / 2.4)
    return (rgb * 255 + 0.5).astype(np.uint8)


def _jpeg(arr: np.ndarray, fmt="JPEG") -> bytes:
    from PIL import Image
    b = io.BytesIO()
    Image.fromarray(arr).save(b, fmt, **({"quality": 90} if fmt == "JPEG" else {"compress_level": 3}))
    return b.getvalue()


# ------------------------------------------------------------------ preview
def preview(eng: Engine, s: Settings, t: float, cx: float, cy: float, size: int = 448) -> dict:
    """Before/after of one crop at output scale. 'Before' is the source resized with Lanczos only."""
    si = media.probe(s.input)
    pl = plan(s, si)
    f = pl.params.out_w / si.width
    cw = min(si.width, _even(size / f))
    ch = min(si.height, _even(size / f))
    x = int(min(max(cx * si.width - cw / 2, 0), si.width - cw)) // 2 * 2
    y = int(min(max(cy * si.height - ch / 2, 0), si.height - ch)) // 2 * 2
    dec = media.Decoder(si, start=max(0.0, t), crop=(x, y, cw, ch), max_frames=1)
    planes = dec.read()
    err = dec.close()
    if planes is None:
        raise ValueError("تعذّر قراءة الإطار: " + err)
    k = eng.k
    t0 = time.perf_counter()
    sr = eng.model(pl.model, s.allow_fp16)
    ow, oh = _even(cw * f), _even(ch * f)
    after_p = Params.from_dict({**pl.params.to_dict(), "out_w": ow, "out_h": oh, "out_mode": 1, "temporal": False})
    before_p = Params(work_domain=after_p.work_domain, out_mode=1, deband=False, denoise_luma=0, denoise_chroma=0, temporal=False,
                      ai=False, sharpen=0, vibrance=0, sdr_gamma=after_p.sdr_gamma, src_peak=after_p.src_peak, out_w=ow, out_h=oh)
    inp = _Input(k, si, dec, after_p.work_domain)
    src = inp.load(planes)
    out = {}
    t1 = time.perf_counter()
    for name, p in (("before", before_p), ("after", after_p)):
        fp = FramePipeline(k, cw, ch, p, sr)
        res = k.read(fp.process(src, 1 / 30))[..., :3]
        out[name] = _jpeg(to_display(res, False), "PNG")
        if name == "after":
            out["info"] = {k_: (round(v, 5) if isinstance(v, float) else v) for k_, v in fp.info.items()}
        fp.release()
    inp.release()
    out["ms"] = round((time.perf_counter() - t1) * 1000)
    out["loadMs"] = round((t1 - t0) * 1000)
    out["crop"] = [x, y, cw, ch]
    out["size"] = [ow, oh]
    out["estimate"] = estimate(pl, sr)
    out["notes"] = pl.notes
    return out


def rife_seconds(pl: Plan):
    """Measured RIFE time per generated frame at this output size, or None before its first use."""
    c = ai._load_cache().get(f"rife|{rife.padded(pl.params.out_w)}x{rife.padded(pl.params.out_h)}")
    return c["best"]["frameS"] if c and pl.interp else None


def estimate(pl: Plan, sr) -> dict:
    """Rough export time from the measured tile time (AI dominates when it is on)."""
    enc = pl.params.out_w * pl.params.out_h / 8.3e6 * 0.6 + 0.05
    per = (sr.seconds_per_frame(pl.lr_w, pl.lr_h) if sr else 0.0) + enc
    if pl.interp:       # per written frame: half are source frames, half are generated
        per = (per + (rife_seconds(pl) or 0.0) + enc) / 2
    return {"secPerFrame": round(per, 2), "frames": pl.frames, "totalSec": round(per * pl.frames),
            "backend": sr.backend["label"] if sr else "", "tiles": sr.tiles(pl.lr_w, pl.lr_h) if sr else 0}


# ------------------------------------------------------------------ export
def _unlink(f: Path, tries=20):
    """A killed run's encoder may hold its chunk for a moment while it exits."""
    for i in range(tries):
        try:
            f.unlink(missing_ok=True)
            return
        except PermissionError:
            time.sleep(0.5)
    raise RuntimeError(f"تعذّر حذف {f.name}: الملف مستخدم من برنامج آخر")


def _lock(path: Path):
    """One process per job folder."""
    import msvcrt
    f = open(path, "a+b")
    try:
        f.seek(0)
        msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        f.close()
        raise RuntimeError("هذا التصدير يعمل الآن في نافذة أخرى") from None
    return f


class ExportJob:
    def __init__(self, s: Settings, log=print):
        self.s, self.log = s, log
        self.cancel = threading.Event()
        self.progress = {"state": "queued", "stage": "في الانتظار", "frame": 0, "total": 0}
        self.live: bytes | None = None
        self.live_n = 0
        self.report: dict | None = None
        self.error: str | None = None

    def _set(self, **kw):
        self.progress = {**self.progress, **kw}

    def _key(self, si, pl):
        st = os.stat(self.s.input)
        d = {k: v for k, v in asdict(self.s).items() if k != "output"}
        blob = json.dumps([d, st.st_size, int(st.st_mtime), pl.params.to_dict(), asdict(pl.enc) | {"fps": str(pl.enc.fps)}], sort_keys=True, default=str)
        return hashlib.sha256(blob.encode()).hexdigest()[:24]

    def run(self, eng: Engine):
        self._lock = None
        try:
            self._run(eng)
            self._set(state="done", stage="اكتمل")
        except ai.Cancelled:
            self._set(state="cancelled", stage="أُوقف — يمكن الاستئناف من حيث توقف")
        except Exception as e:
            self.error = str(e)
            self.log("خطأ: " + self.error)
            self._set(state="error", stage=self.error)
        finally:
            if self._lock:
                self._lock.close()

    def _check(self, *_):
        if self.cancel.is_set():
            raise ai.Cancelled()

    def _run(self, eng: Engine):
        s = self.s
        self._set(state="running", stage="فحص المصدر")
        si = media.probe(s.input)
        pl = plan(s, si)
        out = Path(s.output).resolve()      # the encoder runs in the job folder: relative paths would break
        out.parent.mkdir(parents=True, exist_ok=True)
        jd = Path(str(out) + ".hdrjob")
        jd.mkdir(exist_ok=True)
        self._lock = _lock(jd / "lock")
        state_f = jd / "state.json"
        key = self._key(si, pl)
        st = {"key": key, "chunks": [], "maxCll": 0.0, "maxFall": 0.0, "pipe": {}}
        if state_f.exists():
            try:
                old = json.loads(state_f.read_text("utf-8"))
                if old.get("key") == key and all((jd / c["file"]).exists() for c in old["chunks"]):
                    st = old
            except Exception:
                pass
        known = {c["file"] for c in st["chunks"]}
        for f in jd.glob("chunk_*"):
            if f.name not in known:         # partial chunk of an interrupted run
                _unlink(f)
        done = sum(c["frames"] for c in st["chunks"])
        if done:
            self.log(f"استئناف من الإطار {done}")
        for n in pl.notes:
            self.log("ملاحظة: " + n)

        self._set(stage="تجهيز نموذج AI" if pl.model else "تجهيز المعالجة", total=pl.frames, frame=done)
        sr = eng.model(pl.model, s.allow_fp16, self.log)
        if sr:
            self.log(f"خلفية AI: {sr.backend['label']} — {sr.backend['tileS']} ث/بلاطة، {sr.tiles(pl.lr_w, pl.lr_h)} بلاطة/إطار")
        k, p, enc_cfg = eng.k, pl.params, pl.enc
        interp = None
        if pl.interp:
            self._set(stage="تجهيز نموذج توليد الإطارات")
            interp = _interpolator(eng, p.out_w, p.out_h, self.log)
            self.log(f"توليد الإطارات: RIFE v4.6 على {interp.backend['label']} — {interp.backend['frameS']} ث/إطار")
        dyn = self._scene_metadata(eng, si, pl, jd, key) if pl.hdr10plus else None
        dec = media.Decoder(si, start=s.start, duration=s.duration or None)
        inp = _Input(k, si, dec, p.work_domain)
        pipe = FramePipeline(k, dec.w, dec.h, p, sr)
        pipe.gate = self._check
        yt = k.tex(p.out_w, p.out_h, True, components=1, dtype="u2")
        ct = k.tex(p.out_w // 2, p.out_h // 2, True, components=2, dtype="u2")
        mid_tex = k.tex(p.out_w, p.out_h, True, components=3, dtype="f4") if interp else None
        tw = min(640, p.out_w) // 2 * 2
        th = max(2, round(p.out_h * tw / p.out_w))
        thumb_tmp, thumb = k.tex(tw, p.out_h), k.tex(tw, th)
        yuv_u = {"uMatrix": 1 if enc_cfg.hdr else 0, "uBitDepth": float(enc_cfg.bit_depth), "uDither": True,
                 "uToHlg": 1 if enc_cfg.hdr and enc_cfg.trc == "hlg" else 0, "uHlgPeak": HLG_PEAK}
        # chunks hold an even number of frames, so with interpolation a chunk always ends on a
        # generated frame and a resume starts cleanly on a source frame
        chunk_frames = max(2, round(float(enc_cfg.fps) * 2) // 2 * 2)
        dt = 1.0 / pl.src_fps
        src_done = done // 2 if interp else done        # source frames already in finished chunks
        ctx = {"enc": None, "written": done, "new": 0, "live": 0.0}
        t_start = time.perf_counter()

        def emit(tex):
            """Measures, quantises and encodes one final frame."""
            if enc_cfg.hdr:
                mx, avg = pipe.measure_light(tex)
                st["maxCll"], st["maxFall"] = max(st["maxCll"], mx), max(st["maxFall"], avg)
            k.run("out_yuv.frag", yt, {"uIn": tex, "uPlane": 0, **yuv_u})
            k.run("out_yuv.frag", ct, {"uIn": tex, "uPlane": 1, **yuv_u})
            Y, C = k.read(yt, 1, "u2"), k.read(ct, 2, "u2")
            if enc_cfg.bit_depth == 8:
                Y, C = Y.astype(np.uint8), C.astype(np.uint8)
            if ctx["enc"] is None:
                name, meta_name = f"chunk_{len(st['chunks']):05d}", ""
                if dyn:
                    first = ctx["written"]
                    meta_name = name + ".json"
                    hdr10plus.chunk_json(dyn, [(j // 2 if interp else j) for j in range(first, min(first + chunk_frames, pl.frames))], jd / meta_name)
                ctx["enc"] = media.Encoder(enc_cfg, jd / (name + enc_cfg.ext), jd / "encoder.log", meta_name)
            ctx["enc"].write(Y.tobytes(), np.ascontiguousarray(C[..., 0]).tobytes(), np.ascontiguousarray(C[..., 1]).tobytes())
            ctx["written"] += 1
            ctx["new"] += 1
            if ctx["enc"].frames >= chunk_frames:
                self._close_chunk(ctx["enc"], st, pipe, state_f)
                ctx["enc"] = None
            now = time.perf_counter()
            el = now - t_start
            rate = ctx["new"] / el if el > 0 else 0
            left = max(0, pl.frames - ctx["written"])
            self._set(frame=ctx["written"], fps=round(rate, 3), eta=round(left / rate) if rate > 0 else None, elapsed=round(el),
                      cut=pipe.info.get("cut"), aiMs=round(pipe.info.get("aiMs", 0)), sigma=round(pipe.info.get("sigma", 0), 5))
            if now - ctx["live"] > 4 or ctx["new"] == 1:
                k.run("resize.frag", thumb_tmp, {"uIn": tex, "uDir": 0, "uScale": tw / p.out_w, "uLobes": 2.0, "uAntiring": 0.0})
                k.run("resize.frag", thumb, {"uIn": thumb_tmp, "uDir": 1, "uScale": th / p.out_h, "uLobes": 2.0, "uAntiring": 0.0})
                self.live = _jpeg(to_display(k.read(thumb)[..., :3], enc_cfg.hdr))
                self.live_n += 1
                ctx["live"] = time.perf_counter()

        idx, prev, fin = 0, None, None
        try:
            warm = min(3, src_done)
            if src_done - warm:
                self._set(stage="التقدّم إلى نقطة الاستئناف")
                dec.skip(src_done - warm)
            idx = src_done - warm
            self._set(stage="معالجة")
            while True:
                self._check()
                planes = dec.read()
                if planes is None:
                    break
                fin = pipe.process(inp.load(planes), dt)
                if idx < src_done:                  # warm-up of the temporal filter after a resume
                    idx += 1
                    if idx == src_done:
                        pipe.restore(st["pipe"])
                    continue
                if interp:
                    cur = k.read(fin, 3).copy()
                    if prev is not None:            # None: first frame, or its in-between is already in a finished chunk
                        self._check()
                        mid = prev if pipe.info.get("cut") else interp.middle(prev, cur)   # never blend across a scene cut
                        mid_tex.t.write(mid.tobytes(), alignment=1)
                        emit(mid_tex)
                    prev = cur
                emit(fin)
                idx += 1
            if interp and prev is not None:
                emit(fin)                           # the last frame is held once more: 2N frames, same duration
            if ctx["enc"] is not None:
                self._close_chunk(ctx["enc"], st, pipe, state_f)
                ctx["enc"] = None
        finally:
            if ctx["enc"] is not None:
                ctx["enc"].abort()
                try:
                    ctx["enc"].out.unlink()
                except OSError:
                    pass
            err = dec.close()
            pipe.release()
            inp.release()
            for t in (yt, ct, thumb_tmp, thumb, mid_tex):
                k.delete(t)
        total = sum(c["frames"] for c in st["chunks"])
        if total == 0:
            raise RuntimeError("لم يُقرأ أي إطار من المصدر. " + err)

        self._set(stage="دمج المقاطع وكتابة بيانات HDR10", frame=total, total=total)
        cll, fall = media.safe_cll(round(st["maxCll"]), round(st["maxFall"]))
        tmp = jd / ("out" + (out.suffix or ".mp4"))
        media.mux([jd / c["file"] for c in st["chunks"]], enc_cfg, si, tmp, start=s.start, frames=total)
        hdr10 = enc_cfg.hdr and enc_cfg.trc == "pq"
        patched = media.patch_cll(tmp, cll, fall) if hdr10 else 0
        self._set(stage="فحص الملف الناتج")
        rep = media.verify(tmp, enc_cfg, total, bool(si.audio_codec), want_hdr10plus=pl.hdr10plus)
        if hdr10:
            rep["measuredMaxCll"], rep["measuredMaxFall"], rep["seiPatched"] = cll, fall, patched
            if rep.get("maxCll") != cll or rep.get("maxFall") != fall:
                rep["problems"].append(f"MaxCLL/MaxFALL في الملف {rep.get('maxCll')}/{rep.get('maxFall')} ≠ المقاس {cll}/{fall}")
                rep["ok"] = False
        rep["seconds"] = round(time.perf_counter() - t_start)
        rep["output"] = str(out)
        self.report = rep
        if not rep["ok"]:
            bad = out.with_name(out.stem + ".rejected" + out.suffix)
            shutil.move(str(tmp), bad)
            self._lock.close()
            shutil.rmtree(jd, ignore_errors=True)     # a retry must not reuse the chunks of a rejected file
            raise RuntimeError("الملف الناتج لم يجتز الفحص: " + "؛ ".join(rep["problems"]) + f" (حُفظ للفحص: {bad.name})")
        os.replace(tmp, out)
        self._lock.close()
        shutil.rmtree(jd, ignore_errors=True)
        self.log(f"تم: {out} — {total} إطار في {rep['seconds']} ث")

    def _scene_metadata(self, eng, si, pl, jd, key):
        """HDR10+ analysis pass (see hdr10plus.py): scene statistics of the whole clip, measured
        once without AI at reduced size and kept in the job folder for resumed runs."""
        cache = jd / "hdr10plus.json"
        try:
            old = json.loads(cache.read_text("utf-8"))
            if old.get("key") == key:
                return old["meta"]
        except Exception:
            pass
        s, k = self.s, eng.k
        aw, ah = hdr10plus.analysis_size(pl.params.out_w, pl.params.out_h)
        ap = Params.from_dict({**pl.params.to_dict(), "out_w": aw, "out_h": ah, "ai": False, "temporal": False})
        dec = media.Decoder(si, start=s.start, duration=s.duration or None)
        inp = _Input(k, si, dec, ap.work_domain)
        pipe = FramePipeline(k, dec.w, dec.h, ap, None)
        acc, total = hdr10plus.Accumulator(), max(1, pl.frames // (2 if pl.interp else 1))
        try:
            while True:
                self._check()
                planes = dec.read()
                if planes is None:
                    break
                fin = pipe.process(inp.load(planes), 1.0 / pl.src_fps)
                acc.add(hdr10plus.FrameStats(k.read(fin)[..., :3]), bool(pipe.info.get("cut")))
                if acc.frames % 10 == 0:
                    self._set(stage=f"تحليل المشاهد لبيانات HDR10+ ({acc.frames}/{total})")
        finally:
            dec.close()
            pipe.release()
            inp.release()
        if not acc.frames:
            raise RuntimeError("تعذّر تحليل المصدر لبيانات HDR10+")
        meta = acc.finish()
        cache.write_text(json.dumps({"key": key, "meta": meta}), "utf-8")
        self.log(f"HDR10+: {len(meta['scenes'])} مشهد من {acc.frames} إطاراً")
        return meta

    def _close_chunk(self, enc, st, pipe, state_f):
        enc.close()
        st["chunks"].append({"file": enc.out.name, "frames": enc.frames})
        st["pipe"] = pipe.state()
        tmp = state_f.with_suffix(".tmp")
        tmp.write_text(json.dumps(st), "utf-8")
        os.replace(tmp, state_f)
