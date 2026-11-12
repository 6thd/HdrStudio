"""Frame interpolation (RIFE v4.6, MIT, (c) hzwer / ncnn port by nihui).

The network is read from the ncnn model files (flownet.param / flownet.bin) and written out as
an ONNX graph for the exact frame size, so it runs in the same runtimes as the SR models, in
float, on the final FP16 code values - no 8-bit PNG round trip through an external program.
As with the SR models every runtime is checked against the fp32 CPU result before it is used.
"""
from __future__ import annotations

import json
import struct
import time
from pathlib import Path

import numpy as np

from . import ai, models

RIFE_DIR = models.MODEL_DIR / "rife-v4.6"
ALIGN = 32
MAX_ERR, MEAN_ERR = 0.03, 0.002


def available() -> bool:
    return (RIFE_DIR / "flownet.param").exists() and (RIFE_DIR / "flownet.bin").exists()


def padded(n: int) -> int:
    return (n + ALIGN - 1) // ALIGN * ALIGN


# ------------------------------------------------------------------ ncnn -> ONNX
def _parse(param: Path):
    layers = []
    for line in param.read_text().split("\n")[2:]:
        t = line.split()
        if not t:
            continue
        ni, no = int(t[2]), int(t[3])
        kv = {}
        for x in t[4 + ni + no:]:
            k, v = x.split("=")
            kv[int(k)] = [float(a) for a in v.split(",")[1:]] if int(k) <= -23300 else float(v)
        layers.append((t[0], t[1], t[4:4 + ni], t[4 + ni:4 + ni + no], kv))
    return layers


class _Bin:
    def __init__(self, path: Path):
        self.raw, self.off = path.read_bytes(), 0

    def weights(self, n):
        tag = struct.unpack_from("<I", self.raw, self.off)[0]
        self.off += 4
        if tag == 0x01306B47:
            w = np.frombuffer(self.raw, "<f2", n, self.off).astype(np.float32)
            self.off = (self.off + n * 2 + 3) & ~3
        elif tag == 0:
            w = np.frombuffer(self.raw, "<f4", n, self.off).copy()
            self.off += n * 4
        else:
            raise ValueError(f"unsupported ncnn weight tag {tag:#x}")
        return w

    def floats(self, n):
        a = np.frombuffer(self.raw, "<f4", n, self.off).copy()
        self.off += n * 4
        return a


def build_onnx(out: Path, w: int, h: int, timestep: float = 0.5):
    """w, h must be multiples of 32. Inputs in0, in1: (1,3,h,w) in [0,1]; output: the frame between."""
    g = models._Graph()
    blob = _Bin(RIFE_DIR / "flownet.bin")
    T = lambda name: name if name in ("in0", "in1") else "b_" + name
    g.inits.append(g.nh.from_array(np.full((1, 1, h, w), timestep, np.float32), T("in2")))
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    base = g.const(np.stack([xs * 2 / (w - 1) - 1, ys * 2 / (h - 1) - 1], -1)[None], "grid")
    gscale = g.const(np.array([2 / (w - 1), 2 / (h - 1)], np.float32), "gs")

    def emit(typ, ins, out_name, **attrs):
        g.nodes.append(g.h.make_node(typ, ins, [out_name], **attrs))

    for typ, name, ins, outs, p in _parse(RIFE_DIR / "flownet.param"):
        ins = [T(i) for i in ins]
        o = T(outs[0]) if outs else None
        if typ == "Input":
            continue
        if typ == "Split":
            for x in outs:
                emit("Identity", ins, T(x))
        elif typ in ("Convolution", "Deconvolution"):
            oc, k, s, pad = int(p[0]), int(p[1]), int(p.get(3, 1)), int(p.get(4, 0))
            n = int(p[6])
            wt = blob.weights(n).reshape(oc, n // (oc * k * k), k, k)
            bias = blob.floats(oc) if int(p.get(5, 0)) else np.zeros(oc, np.float32)
            act = int(p.get(9, 0))
            dst = o if not act else g.name()
            if typ == "Convolution":
                emit("Conv", [ins[0], g.const(wt, "w"), g.const(bias, "b")], dst, kernel_shape=[k, k], strides=[s, s], pads=[pad] * 4)
            else:   # ncnn stores [out][in][kh][kw]; ONNX ConvTranspose wants [in][out][kh][kw]
                emit("ConvTranspose", [ins[0], g.const(np.ascontiguousarray(wt.transpose(1, 0, 2, 3)), "w"), g.const(bias, "b")], dst,
                     kernel_shape=[k, k], strides=[s, s], pads=[pad] * 4)
            if act == 2:
                emit("LeakyRelu", [dst], o, alpha=float(p[-23310][0]))
            elif act:
                raise ValueError(f"activation {act}")
        elif typ == "ReLU":
            emit("LeakyRelu", ins, o, alpha=float(p.get(0, 0.0)))
        elif typ == "Sigmoid":
            emit("Sigmoid", ins, o)
        elif typ == "Concat":
            emit("Concat", ins, o, axis=1)
        elif typ == "PixelShuffle":
            emit("DepthToSpace", ins, o, blocksize=int(p[0]), mode="CRD")
        elif typ == "Interp":
            if int(p[0]) != 2:
                raise ValueError("Interp type")
            emit("Resize", [ins[0], "", g.const(np.array([1, 1, p[1], p[2]], np.float32))], o, mode="linear",
                 coordinate_transformation_mode="half_pixel")
        elif typ == "Crop":
            if p[-23311] != [0.0]:
                raise ValueError("Crop axis")
            emit("Slice", [ins[0], g.const(np.array([int(p[-23309][0])], np.int64)), g.const(np.array([int(p[-23310][0])], np.int64)),
                           g.const(np.array([1], np.int64))], o)
        elif typ == "BinaryOp":
            op, scalar = int(p.get(0, 0)), int(p.get(1, 0))
            a = ins[0]
            b = g.const(np.float32(p[2])) if scalar else ins[1]
            if op == 7:
                emit("Sub", [b, a], o)
            else:
                emit({0: "Add", 1: "Sub", 2: "Mul", 3: "Div"}[op], [a, b], o)
        elif typ == "Eltwise":
            c = p[-23301]
            x0 = g.op("Mul", [ins[0], g.const(np.float32(c[0]))])
            x1 = g.op("Mul", [ins[1], g.const(np.float32(c[1]))])
            emit("Add", [x0, x1], o)
        elif typ == "rife.Warp":
            # sample image at (x + flow_x, y + flow_y), bilinear, clamped to the border
            fl = g.op("Mul", [g.op("Transpose", [ins[1]], perm=[0, 2, 3, 1]), gscale])
            emit("GridSample", [ins[0], g.op("Add", [fl, base])], o, mode="bilinear", padding_mode="border", align_corners=1)
        else:
            raise ValueError(f"unsupported ncnn layer {typ}")
    if blob.off != len(blob.raw):
        raise ValueError("flownet.bin size mismatch")
    import onnx
    from onnx import TensorProto
    hh = g.h
    graph = hh.make_graph(g.nodes, "rife", [hh.make_tensor_value_info(n, TensorProto.FLOAT, [1, 3, h, w]) for n in ("in0", "in1")],
                          [hh.make_tensor_value_info("b_out0", TensorProto.FLOAT, [1, 3, h, w])], g.inits)
    m = hh.make_model(graph, opset_imports=[hh.make_opsetid("", 17)], producer_name="HDR Studio")
    m.ir_version = 8
    onnx.checker.check_model(m)
    tmp = out.with_suffix(".tmp")
    onnx.save(m, str(tmp))
    tmp.replace(out)


# ------------------------------------------------------------------ runtimes
class _Ov:
    def __init__(self, path, device, precision):
        core = ai._ov_core()
        cfg = {"PERFORMANCE_HINT": "LATENCY"}
        if precision:
            cfg["INFERENCE_PRECISION_HINT"] = precision
        if device == "GPU":
            cfg["CACHE_DIR"] = str(ai.CACHE.parent / "ov_cache")
        self.req = core.compile_model(core.read_model(str(path)), device, cfg).create_infer_request()

    def __call__(self, a, b):
        self.req.infer({"in0": a, "in1": b})
        return self.req.get_output_tensor(0).data


class _Ort:
    def __init__(self, path, provider):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.log_severity_level = 3
        if provider == "DmlExecutionProvider":
            so.enable_mem_pattern = False
        self.s = ort.InferenceSession(str(path), so, providers=[provider])
        if self.s.get_providers()[0] != provider:
            raise RuntimeError(f"{provider} غير متاح")

    def __call__(self, a, b):
        return self.s.run(None, {"in0": a, "in1": b})[0]


def _make(c, path):
    return _Ov(path, c["device"], c["precision"]) if c["rt"] == "ov" else _Ort(path, c["provider"])


def _test_pair(w, h):
    """Two frames of a moving textured scene (global pan + an object moving the other way)."""
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)

    def frame(dx):
        img = np.stack([0.5 + 0.35 * np.sin((x + dx) * 0.11 + y * 0.05), 0.5 + 0.35 * np.cos((x + dx) * 0.045 - y * 0.09),
                        0.5 + 0.3 * np.sin((x + dx + y) * 0.02)])
        cx, cy = w * 0.5 - dx * 2, h * 0.5
        m = ((x - cx) ** 2 + (y - cy) ** 2) < (min(w, h) * 0.12) ** 2
        img[:, m] = np.array([0.9, 0.2, 0.15], np.float32)[:, None]
        return np.clip(img, 0, 1).astype(np.float32)[None]
    return frame(0), frame(6)


def benchmark(path: Path, w: int, h: int, log=print) -> dict:
    cache = ai._load_cache()
    key = f"rife|{w}x{h}"
    fp = f"{path.stat().st_size}|" + ",".join(ai._key(c) for c in ai.candidates())
    if cache.get(key, {}).get("fp") == fp:
        return cache[key]
    a, b = _test_pair(w, h)
    cands = ai.candidates()
    cpu = next(c for c in reversed(cands) if "CPU" in c["label"])
    log(f"مرجع CPU لنموذج RIFE {w}×{h} …")
    ref = np.array(_make(cpu, path)(a, b), np.float32)
    results = []
    for c in cands:
        r = dict(c)
        try:
            t = time.perf_counter()
            run = _make(c, path)
            y = np.array(run(a, b), np.float32)
            r["loadS"] = round(time.perf_counter() - t, 2)
            t = time.perf_counter()
            run(a, b)
            r["frameS"] = round(time.perf_counter() - t, 3)
            d = np.abs(y - ref)
            r["maxErr"], r["meanErr"] = float(d.max()), float(d.mean())
            r["ok"] = bool(np.isfinite(y).all() and r["maxErr"] < MAX_ERR and r["meanErr"] < MEAN_ERR)
            del run
        except Exception as e:
            r["ok"], r["error"] = False, str(e).splitlines()[0][:200]
        log(f"  {r['label']}: " + (f"{r['frameS']} ث/إطار، أقصى خطأ {r['maxErr']:.4f} {'✓' if r['ok'] else '✗ مرفوض'}"
                                   if "frameS" in r else "فشل: " + r.get("error", "")))
        results.append(r)
    good = [r for r in results if r["ok"]]
    if not good:
        raise RuntimeError("لا توجد خلفية تشغيل صالحة لنموذج RIFE")
    entry = {"fp": fp, "best": min(good, key=lambda r: r["frameS"]), "results": results}
    cache = ai._load_cache()
    cache[key] = entry
    ai.CACHE.parent.mkdir(parents=True, exist_ok=True)
    ai.CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=1), "utf-8")
    return entry


class Interpolator:
    """Middle frame of two (H, W, 3) float32 frames in the final signal domain."""

    def __init__(self, w: int, h: int, log=print):
        if not available():
            raise FileNotFoundError("نموذج RIFE غير موجود في models/rife-v4.6")
        self.w, self.h, self.pw, self.ph = w, h, padded(w), padded(h)
        path = models.MODEL_DIR / f"rife_v46_{self.pw}x{self.ph}.onnx"
        if not path.exists():
            build_onnx(path, self.pw, self.ph)
        self.bench = benchmark(path, self.pw, self.ph, log)
        self.backend = self.bench["best"]
        self.run = _make(self.backend, path)
        self._a = np.zeros((1, 3, self.ph, self.pw), np.float32)
        self._b = np.zeros_like(self._a)

    def _load(self, buf, img):
        c = np.clip(img, 0.0, 1.0).transpose(2, 0, 1)
        buf[0, :, :self.h, :self.w] = c
        if self.pw > self.w:
            buf[0, :, :self.h, self.w:] = c[:, :, -1:]
        if self.ph > self.h:
            buf[0, :, self.h:, :] = buf[0, :, self.h - 1:self.h, :]

    def middle(self, a: np.ndarray, b: np.ndarray) -> np.ndarray:
        self._load(self._a, a)
        self._load(self._b, b)
        out = self.run(self._a, self._b)
        return np.ascontiguousarray(out[0, :, :self.h, :self.w].transpose(1, 2, 0), np.float32)
