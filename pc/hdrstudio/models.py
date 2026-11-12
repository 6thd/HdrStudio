"""AI model registry and ONNX builders.

No PyTorch anywhere: the official checkpoints are read with a restricted unpickler (tensors
only, nothing executable) and the networks are written out as ONNX graphs by hand.

  general   Real-ESRGAN realesr-general-x4v3  (SRVGGNetCompact, 1.2 M params)  x4
  x2plus    RealESRGAN_x2plus                 (RRDBNet 23 blocks, 16.7 M)      x2
  x4plus    RealESRGAN_x4plus                 (RRDBNet 23 blocks, 16.7 M)      x4

All three are BSD-3-Clause (c) Xintao Wang, see models/LICENSE-Real-ESRGAN.txt.
"""
from __future__ import annotations

import collections
import hashlib
import pickle
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = ROOT / "models"


@dataclass(frozen=True)
class ModelSpec:
    id: str
    label: str
    scale: int
    tile: int          # core tile size in model-input pixels
    pad: int           # real neighbourhood carried around the core
    source: str        # file the ONNX graph is built from
    url: str | None
    note: str

    @property
    def onnx(self) -> Path:
        return MODEL_DIR / f"{self.id}.onnx"

    @property
    def window(self) -> int:
        return self.tile + 2 * self.pad


REGISTRY = {
    # receptive field of the 34-conv network is 34 px per side: pad 36 makes tiles exact
    "general": ModelSpec("general", "Real-ESRGAN General x4v3", 4, 256, 36, "srvgg_general_x4v3.bin", None,
                         "سريع ومحافظ: يستعيد التفاصيل دون اختلاق قوام"),
    "x2plus": ModelSpec("x2plus", "Real-ESRGAN x2plus", 2, 192, 32, "RealESRGAN_x2plus.pth",
                        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.1/RealESRGAN_x2plus.pth",
                        "جودة عالية للتكبير ×2 (1080p→4K)"),
    "x4plus": ModelSpec("x4plus", "Real-ESRGAN x4plus", 4, 128, 32, "RealESRGAN_x4plus.pth",
                        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth",
                        "أقصى تفاصيل، وأبطأ النماذج"),
}


# ------------------------------------------------------------------ checkpoint reading
class _Storage:
    def __init__(self, dtype, key):
        self.dtype, self.key = dtype, key


def load_state_dict(path: Path) -> dict:
    """Reads a PyTorch zip checkpoint without PyTorch. Any global other than the tensor
    rebuild helpers raises, so a hostile pickle cannot run code."""
    z = zipfile.ZipFile(path)
    prefix = [n for n in z.namelist() if n.endswith("data.pkl")][0][:-len("data.pkl")]
    dtypes = {"FloatStorage": np.float32, "HalfStorage": np.float16, "DoubleStorage": np.float64}

    class U(pickle.Unpickler):
        def find_class(self, mod, name):
            if mod == "collections" and name == "OrderedDict":
                return collections.OrderedDict
            if mod == "torch._utils" and name == "_rebuild_tensor_v2":
                def rebuild(storage, offset, size, stride, *a):
                    raw = np.frombuffer(z.read(prefix + "data/" + storage.key), storage.dtype)
                    if not size:
                        return raw[offset:offset + 1].reshape(())
                    return np.lib.stride_tricks.as_strided(
                        raw[offset:], size, [s * raw.itemsize for s in stride]).copy()
                return rebuild
            if mod == "torch" and name in dtypes:
                return name
            raise pickle.UnpicklingError(f"unexpected global {mod}.{name}")

        def persistent_load(self, pid):
            st = pid[1]
            st = st if isinstance(st, str) else getattr(st, "__name__", str(st))
            return _Storage(dtypes[st], pid[2])

    obj = U(z.open(prefix + "data.pkl")).load()
    for k in ("params_ema", "params"):
        if isinstance(obj, dict) and k in obj:
            return obj[k]
    return obj


# ------------------------------------------------------------------ graph writer
class _Graph:
    def __init__(self):
        from onnx import helper, numpy_helper
        self.h, self.nh = helper, numpy_helper
        self.nodes, self.inits, self.n = [], [], 0

    def name(self, p="t"):
        self.n += 1
        return f"{p}{self.n}"

    def const(self, arr, p="c"):
        nm = self.name(p)
        self.inits.append(self.nh.from_array(np.ascontiguousarray(arr), nm))
        return nm

    def op(self, typ, inputs, **attrs):
        out = self.name()
        self.nodes.append(self.h.make_node(typ, inputs, [out], **attrs))
        return out

    def conv(self, x, w, b, pad=1):
        return self.op("Conv", [x, self.const(w.astype(np.float32), "w"), self.const(b.astype(np.float32), "b")],
                       kernel_shape=list(w.shape[2:]), pads=[pad] * 4)

    def lrelu(self, x, a=0.2):
        return self.op("LeakyRelu", [x], alpha=a)

    def scale_add(self, x, s, y):
        return self.op("Add", [self.op("Mul", [x, self.const(np.float32(s))]), y])

    def up2(self, x):
        return self.op("Resize", [x, "", self.const(np.array([1, 1, 2, 2], np.float32))], mode="nearest",
                       coordinate_transformation_mode="asymmetric", nearest_mode="floor")

    def save(self, out_name, in_ch, path: Path, doc: str):
        import onnx
        from onnx import TensorProto
        h = self.h
        g = h.make_graph(self.nodes, "hdrstudio", [h.make_tensor_value_info("input", TensorProto.FLOAT, [1, in_ch, "H", "W"])],
                         [h.make_tensor_value_info(out_name, TensorProto.FLOAT, [1, 3, "HO", "WO"])], self.inits)
        m = h.make_model(g, opset_imports=[h.make_opsetid("", 17)], producer_name="HDR Studio")
        m.ir_version = 8
        m.doc_string = doc
        onnx.checker.check_model(m)
        tmp = path.with_suffix(".onnx.tmp")
        onnx.save(m, str(tmp))
        tmp.replace(path)


def build_srvgg(blob: Path, out: Path, num_conv=32, feat=64, scale=4):
    a = np.fromfile(blob, "<f4")
    off, g, x = 0, _Graph(), "input"
    shapes = [(feat, 3)] + [(feat, feat)] * num_conv + [(3 * scale * scale, feat)]
    for i, (o, c) in enumerate(shapes):
        w = a[off:off + o * c * 9].reshape(o, c, 3, 3); off += o * c * 9
        b = a[off:off + o]; off += o
        x = g.conv(x, w, b)
        if i < len(shapes) - 1:
            slope = a[off:off + feat].reshape(feat, 1, 1); off += feat
            x = g.op("PRelu", [x, g.const(slope.astype(np.float32), "s")])
    if off != a.size:
        raise ValueError("SRVGG blob layout mismatch")
    # nearest-x4 skip connection, expressed before the pixel shuffle: channel c*s*s+k <- input c
    skip = np.zeros((3 * scale * scale, 3, 1, 1), np.float32)
    for c in range(3):
        skip[c * scale * scale:(c + 1) * scale * scale, c] = 1
    base = g.conv("input", skip, np.zeros(3 * scale * scale, np.float32), pad=0)
    x = g.op("Add", [x, base])
    g.nodes.append(g.h.make_node("DepthToSpace", [x], ["output"], blocksize=scale, mode="CRD"))
    g.save("output", 3, out, "SRVGGNetCompact " + hashlib.sha256(a.tobytes()).hexdigest())


def build_rrdb(pth: Path, out: Path, scale: int, blocks=23):
    sd = load_state_dict(pth)
    W = lambda k: sd[k + ".weight"].astype(np.float32)
    B = lambda k: sd[k + ".bias"].astype(np.float32)
    g, x = _Graph(), "input"
    w0 = W("conv_first")
    if scale == 2:
        # the network starts with pixel_unshuffle(2): channel = c*4 + (dy*2+dx).
        # ONNX SpaceToDepth yields channel = (dy*2+dx)*3 + c, so the first conv's input
        # channels are permuted instead of reshaping the tensor.
        x = g.op("SpaceToDepth", [x], blocksize=2)
        perm = np.empty(12, np.int64)
        for c in range(3):
            for k in range(4):
                perm[k * 3 + c] = c * 4 + k
        w0 = w0[:, perm]
    elif scale != 4:
        raise ValueError("RRDB scale must be 2 or 4")
    first = g.conv(x, w0, B("conv_first"))
    x = first
    for i in range(blocks):
        rrdb_in = x
        for r in (1, 2, 3):
            p = f"body.{i}.rdb{r}."
            feats = [x]
            for c in (1, 2, 3, 4):
                inp = feats[0] if len(feats) == 1 else g.op("Concat", list(feats), axis=1)
                feats.append(g.lrelu(g.conv(inp, W(p + f"conv{c}"), B(p + f"conv{c}"))))
            x5 = g.conv(g.op("Concat", list(feats), axis=1), W(p + "conv5"), B(p + "conv5"))
            x = g.scale_add(x5, 0.2, x)
        x = g.scale_add(x, 0.2, rrdb_in)
    x = g.op("Add", [first, g.conv(x, W("conv_body"), B("conv_body"))])
    x = g.lrelu(g.conv(g.up2(x), W("conv_up1"), B("conv_up1")))
    x = g.lrelu(g.conv(g.up2(x), W("conv_up2"), B("conv_up2")))
    x = g.lrelu(g.conv(x, W("conv_hr"), B("conv_hr")))
    wl, bl = W("conv_last"), B("conv_last")
    g.nodes.append(g.h.make_node("Conv", [x, g.const(wl, "w"), g.const(bl, "b")], ["output"], kernel_shape=[3, 3], pads=[1] * 4))
    g.save("output", 3, out, f"RRDBNet x{scale} " + sha256(pth))


# ------------------------------------------------------------------ files
def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(spec: ModelSpec, progress=None) -> Path:
    dst = MODEL_DIR / spec.source
    if dst.exists():
        return dst
    if not spec.url:
        raise FileNotFoundError(f"ملف النموذج مفقود: {dst}")
    tmp = dst.with_suffix(dst.suffix + ".part")
    req = urllib.request.Request(spec.url, headers={"User-Agent": "HDRStudio"})
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
        total, got = int(r.headers.get("Content-Length") or 0), 0
        while True:
            chunk = r.read(1 << 18)
            if not chunk:
                break
            f.write(chunk); got += len(chunk)
            if progress:
                progress(got, total)
    if not zipfile.is_zipfile(tmp):
        tmp.unlink()
        raise IOError("الملف المنزَّل ليس checkpoint صالحاً")
    tmp.replace(dst)
    return dst


def status(model_id: str) -> dict:
    s = REGISTRY[model_id]
    return {"id": s.id, "label": s.label, "scale": s.scale, "note": s.note, "ready": s.onnx.exists(),
            "downloaded": (MODEL_DIR / s.source).exists(), "needsDownload": bool(s.url) and not (MODEL_DIR / s.source).exists()}


def ensure(model_id: str, progress=None, allow_download=True) -> ModelSpec:
    """Makes sure models/<id>.onnx exists, building it from the weights when needed."""
    spec = REGISTRY[model_id]
    if spec.onnx.exists():
        return spec
    src = MODEL_DIR / spec.source
    if not src.exists():
        if not allow_download:
            raise FileNotFoundError(f"نموذج {spec.label} غير منزَّل")
        src = download(spec, progress)
    if spec.id == "general":
        build_srvgg(src, spec.onnx, scale=spec.scale)
    else:
        build_rrdb(src, spec.onnx, spec.scale)
    return spec
