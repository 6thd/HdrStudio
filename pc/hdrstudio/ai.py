"""AI super-resolution engine.

One ONNX model, several possible runtimes. Nothing is trusted because it loaded: every
runtime/precision candidate runs the same test window, is compared with the fp32 CPU result
(and, for the general model, with the reference vector shipped in the phone app), and the
fastest candidate that matches is used. The decision is cached per model + driver.

Tiling follows the phone app: a window of T + 2P model-input pixels is always the same size
(static shape), sits fully inside the image, and only its core (+ a 2 px margin for the
resampling filter) is used, so borders see zeros exactly like a full-frame run and interior
tiles see real neighbours.
"""
from __future__ import annotations

import json
import os
import struct
import time
from pathlib import Path

import numpy as np

from . import models

MARGIN = 2
CACHE = Path(os.environ.get("LOCALAPPDATA", str(models.ROOT))) / "HDRStudio" / "backends.json"
MAX_ERR, MEAN_ERR = 0.02, 0.002


class Cancelled(Exception):
    pass


# ------------------------------------------------------------------ runtimes
class _OpenVino:
    def __init__(self, onnx_path, window, device, precision):
        import openvino as ov
        core = _ov_core()
        m = core.read_model(str(onnx_path))
        m.reshape([1, 3, window, window])
        cfg = {"PERFORMANCE_HINT": "LATENCY"}
        if precision:
            cfg["INFERENCE_PRECISION_HINT"] = precision
        if device == "GPU":
            cfg["CACHE_DIR"] = str(CACHE.parent / "ov_cache")
        self.req = core.compile_model(m, device, cfg).create_infer_request()
        self.ov = ov

    def __call__(self, x):
        self.req.infer({0: x})
        return self.req.get_output_tensor(0).data


class _Ort:
    def __init__(self, onnx_path, window, provider):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.log_severity_level = 3
        if provider == "DmlExecutionProvider":
            so.enable_mem_pattern = False
            so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
            so.add_free_dimension_override_by_name("H", window)
            so.add_free_dimension_override_by_name("W", window)
        self.s = ort.InferenceSession(str(onnx_path), so, providers=[provider])
        if self.s.get_providers()[0] != provider:
            raise RuntimeError(f"{provider} غير متاح")

    def __call__(self, x):
        return self.s.run(None, {"input": x})[0]


_core = None


def _ov_core():
    global _core
    if _core is None:
        import openvino as ov
        _core = ov.Core()
    return _core


def candidates() -> list[dict]:
    """Every runtime this machine offers, most promising first."""
    out = []
    try:
        import onnxruntime as ort
        prov = ort.get_available_providers()
    except Exception:
        prov = []
    try:
        devs = _ov_core().available_devices
    except Exception:
        devs = []
    for p in ("TensorrtExecutionProvider", "CUDAExecutionProvider"):
        if p in prov:
            out.append({"rt": "ort", "provider": p, "label": p.replace("ExecutionProvider", "") + " (NVIDIA)"})
    for d in devs:
        if d.startswith("GPU"):
            name = _ov_core().get_property(d, "FULL_DEVICE_NAME")
            out.append({"rt": "ov", "device": d, "precision": "f16", "label": f"OpenVINO {name} · FP16"})
            out.append({"rt": "ov", "device": d, "precision": "f32", "label": f"OpenVINO {name} · FP32"})
    if "DmlExecutionProvider" in prov:
        out.append({"rt": "ort", "provider": "DmlExecutionProvider", "label": "DirectML (GPU)"})
    if "CPU" in devs:
        out.append({"rt": "ov", "device": "CPU", "precision": "", "label": "OpenVINO CPU"})
    if "CPUExecutionProvider" in prov:
        out.append({"rt": "ort", "provider": "CPUExecutionProvider", "label": "ONNX Runtime CPU"})
    return out


def _key(c):
    return "|".join(str(c.get(k, "")) for k in ("rt", "device", "provider", "precision"))


def _make(c, spec):
    if c["rt"] == "ov":
        return _OpenVino(spec.onnx, spec.window, c["device"], c["precision"])
    return _Ort(spec.onnx, spec.window, c["provider"])


def _test_window(n):
    """Deterministic picture-like window: gradients, texture, hard edges, mild noise."""
    y, x = np.mgrid[0:n, 0:n].astype(np.float32)
    img = np.stack([0.5 + 0.4 * np.sin(x * 0.21 + y * 0.07), 0.5 + 0.4 * np.cos(x * 0.05 - y * 0.17), (x + y) / (2 * n)])
    img[:, n // 4:n // 2, n // 3:n // 3 + n // 5] = np.array([0.95, 0.9, 0.1], np.float32)[:, None, None]
    img[:, (y // 8 + x // 8) % 2 == 0] *= 0.85
    rng = np.random.default_rng(7)
    return np.clip(img + rng.normal(0, 0.01, img.shape), 0, 1).astype(np.float32)[None]


def reference_check(spec) -> float | None:
    """General model only: the phone app's reference vector (NumPy forward, validated against
    ncnn). Confirms the ONNX graph itself, not just agreement between runtimes."""
    if spec.id != "general":
        return None
    raw = (models.MODEL_DIR / "srvgg_selftest.bin").read_bytes()
    w, h = struct.unpack_from("<ii", raw)
    a = np.frombuffer(raw, "<f4", offset=8)
    # rows are stored bottom-up (GL convention of the phone app)
    inp = a[:w * h * 3].reshape(h, w, 3)[::-1].transpose(2, 0, 1)[None]
    exp = a[w * h * 3:].reshape(h * 4, w * 4, 3)[::-1].transpose(2, 0, 1)
    import onnxruntime as ort
    s = ort.InferenceSession(str(spec.onnx), providers=["CPUExecutionProvider"])
    got = s.run(None, {"input": np.ascontiguousarray(inp)})[0][0]
    return float(np.abs(got - exp).max())


def _load_cache():
    try:
        return json.loads(CACHE.read_text("utf-8"))
    except Exception:
        return {}


def _fingerprint(spec):
    try:
        import openvino as ov
        import onnxruntime as ort
        v = f"{ov.__version__}|{ort.__version__}"
    except Exception:
        v = "?"
    st = spec.onnx.stat()
    return f"{spec.id}|{st.st_size}|{int(st.st_mtime)}|{spec.window}|{v}|" + ",".join(_key(c) for c in candidates())


def benchmark(spec, log=print, force=False) -> dict:
    """Tests every candidate; returns {'best': candidate, 'results': [...], 'refErr': ...}."""
    cache, fp = _load_cache(), _fingerprint(spec)
    if not force and cache.get(spec.id, {}).get("fp") == fp:
        return cache[spec.id]
    x = _test_window(spec.window)
    ref_err = reference_check(spec)
    if ref_err is not None and ref_err > 0.01:
        raise RuntimeError(f"نموذج ONNX لا يطابق المتجه المرجعي (خطأ {ref_err:.4f})")
    cands = candidates()
    cpu = next(c for c in reversed(cands) if "CPU" in c["label"])
    log(f"مرجع CPU لنموذج {spec.label} …")
    t = time.perf_counter()
    ref = np.array(_make(cpu, spec)(x), np.float32)
    cpu_s = time.perf_counter() - t
    px = spec.window * spec.window
    results = []
    for c in cands:
        r = dict(c)
        try:
            t = time.perf_counter()
            run = _make(c, spec)
            y = np.array(run(x), np.float32)
            r["loadS"] = round(time.perf_counter() - t, 2)
            t = time.perf_counter()
            run(x)
            r["tileS"] = round(time.perf_counter() - t, 3)
            d = np.abs(y - ref)
            r["maxErr"], r["meanErr"] = float(d.max()), float(d.mean())
            r["ok"] = bool(np.isfinite(y).all() and r["maxErr"] < MAX_ERR and r["meanErr"] < MEAN_ERR)
            r["mpxPerS"] = round(px / 1e6 / r["tileS"], 4)
            del run
        except Exception as e:
            r["ok"], r["error"] = False, str(e).splitlines()[0][:200]
        log(f"  {r['label']}: " + (f"{r['tileS']} ث/بلاطة، أقصى خطأ {r['maxErr']:.4f} {'✓' if r['ok'] else '✗ مرفوض'}"
                                   if "tileS" in r else "فشل: " + r.get("error", "")))
        results.append(r)
    good = [r for r in results if r["ok"]]
    if not good:
        raise RuntimeError("لا توجد خلفية تشغيل صالحة للنموذج")
    best = min(good, key=lambda r: r["tileS"])
    entry = {"fp": fp, "best": best, "results": results, "refErr": ref_err, "cpuRefS": round(cpu_s, 2), "window": spec.window}
    cache[spec.id] = entry
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=1), "utf-8")
    return entry


# ------------------------------------------------------------------ engine
class SrEngine:
    def __init__(self, model_id: str, log=print, backend: dict | None = None):
        self.spec = models.ensure(model_id)
        self.bench = benchmark(self.spec, log)
        self.backend = backend or self.bench["best"]
        self.run_tile = _make(self.backend, self.spec)
        self.scale = self.spec.scale
        self._buf = np.zeros((1, 3, self.spec.window, self.spec.window), np.float32)

    def tiles(self, w, h):
        T = self.spec.tile
        return ((w + T - 1) // T) * ((h + T - 1) // T)

    def seconds_per_frame(self, w, h):
        return self.tiles(w, h) * self.backend["tileS"]

    def run(self, rgb: np.ndarray, emit, gate=None) -> int:
        """rgb: (H, W, 3) float32 model input. emit(sr, rx0, ry0, x0, y0, cw, ch) receives each
        tile's SR pixels (h, w, 3) covering LR rect starting at (rx0, ry0) and the core rect."""
        T, P, F, S = self.spec.tile, self.spec.pad, self.spec.window, self.scale
        H, W = rgb.shape[:2]
        src = rgb
        if W < F or H < F:   # tiny image: replicate edges up to one window
            src = np.pad(rgb, ((0, max(0, F - H)), (0, max(0, F - W)), (0, 0)), mode="edge")
        SH, SW = src.shape[:2]
        total, done = self.tiles(W, H), 0
        for y0 in range(0, H, T):
            for x0 in range(0, W, T):
                cw, ch = min(T, W - x0), min(T, H - y0)
                ox = min(max(x0 - P, 0), SW - F)
                oy = min(max(y0 - P, 0), SH - F)
                np.copyto(self._buf[0], src[oy:oy + F, ox:ox + F].transpose(2, 0, 1))
                out = self.run_tile(self._buf)[0]
                rx0, ry0 = max(0, x0 - MARGIN), max(0, y0 - MARGIN)
                rx1, ry1 = min(W, x0 + cw + MARGIN), min(H, y0 + ch + MARGIN)
                sr = out[:, (ry0 - oy) * S:(ry1 - oy) * S, (rx0 - ox) * S:(rx1 - ox) * S]
                emit(np.ascontiguousarray(sr.transpose(1, 2, 0), np.float32), rx0, ry0, x0, y0, cw, ch)
                done += 1
                if gate and done < total:
                    gate(done, total)
        return total

    def upscale(self, rgb: np.ndarray) -> np.ndarray:
        """Whole image at native model scale (tests, tools)."""
        H, W = rgb.shape[:2]
        S = self.scale
        res = np.zeros((H * S, W * S, 3), np.float32)

        def emit(sr, rx0, ry0, x0, y0, cw, ch):
            res[y0 * S:(y0 + ch) * S, x0 * S:(x0 + cw) * S] = sr[(y0 - ry0) * S:(y0 - ry0 + ch) * S, (x0 - rx0) * S:(x0 - rx0 + cw) * S]
        self.run(rgb, emit)
        return res
