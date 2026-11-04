#!/usr/bin/env python3
"""Run ncnn 20250503 (the Windows x64 DLL of that tag) on a small FP32 input and
compare it with tools/srvgg_reference.py. Then repeat with the FP16 flags the app sets.

This is not a Maleoon 935F result. The DLL is the same tag as the APK's static library.
The weight bytes stay alive for the whole run: Net::load_model(mem) references them.
"""
import ctypes
import os
import sys

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import srvgg_reference as R

DLL = os.environ.get(
    "NCNN_DLL",
    r"C:\Users\ThinkPad\Documents\Cursor\_env\ncnn\win2022\ncnn-20250503-windows-vs2022-shared\x64\bin\ncnn.dll",
)
BLOB = os.path.join(ROOT, "app", "src", "main", "assets", "models", "srvgg_general_x4v3.bin")
FP32_LIMIT = 1e-4
FP16_LIMIT = 2e-2


def build_bin(weights):
    """Same layout as app/src/main/cpp/srvgg_ncnn_model.h."""
    o = [64] + [64] * 32 + [48]
    c = [3] + [64] * 32 + [64]
    parts = []
    off = 0
    flat = weights.reshape(-1)
    for i, (oc, ic) in enumerate(zip(o, c)):
        nw = oc * ic * 9
        parts.append(np.uint32(0).tobytes())  # ModelBin type 0: raw FP32 tag
        parts.append(flat[off:off + nw].astype("<f4").tobytes())
        off += nw
        parts.append(flat[off:off + oc].astype("<f4").tobytes())  # bias, type 1
        off += oc
        if i < 33:
            parts.append(flat[off:off + 64].astype("<f4").tobytes())
            off += 64
    blob = b"".join(parts)
    if off != flat.size or len(blob) != 4853184 + 34 * 4:
        raise SystemExit("bin layout size mismatch %d %d" % (off, len(blob)))
    return blob


def param():
    lines = ["7767517", "68 68", "Input in 0 1 data"]
    prev = "data"
    for L in range(33):
        oc, ic = 64, (3 if L == 0 else 64)
        lines.append("Convolution conv%d 1 1 %s c%d 0=%d 1=3 11=3 2=1 12=1 3=1 13=1 4=1 14=1 5=1 6=%d" % (L, prev, L, oc, oc * ic * 9))
        lines.append("PReLU prelu%d 1 1 c%d p%d 0=64" % (L, L, L))
        prev = "p%d" % L
    lines.append("Convolution conv33 1 1 %s c33 0=48 1=3 11=3 2=1 12=1 3=1 13=1 4=1 14=1 5=1 6=27648" % prev)
    return ("\n".join(lines) + "\n").encode("ascii")


def load_dll():
    os.add_dll_directory(os.path.dirname(DLL))
    dll = ctypes.CDLL(DLL)
    def fn(name, restype, *args):
        f = getattr(dll, name)
        f.restype = restype
        f.argtypes = list(args)
        return f
    P = ctypes.c_void_p
    dll.create = fn("ncnn_net_create", P)
    dll.destroy = fn("ncnn_net_destroy", None, P)
    dll.get_opt = fn("ncnn_net_get_option", P, P)
    dll.threads = fn("ncnn_option_get_num_threads", ctypes.c_int, P)
    dll.vulkan = fn("ncnn_option_get_use_vulkan_compute", ctypes.c_int, P)
    dll.load_param = fn("ncnn_net_load_param_memory", ctypes.c_int, P, ctypes.c_char_p)
    dll.load_model = fn("ncnn_net_load_model_memory", ctypes.c_int, P, P)
    dll.ex_create = fn("ncnn_extractor_create", P, P)
    dll.ex_destroy = fn("ncnn_extractor_destroy", None, P)
    dll.ex_input = fn("ncnn_extractor_input", ctypes.c_int, P, ctypes.c_char_p, P)
    dll.ex_extract = fn("ncnn_extractor_extract", ctypes.c_int, P, ctypes.c_char_p, ctypes.POINTER(P))
    dll.mat3 = fn("ncnn_mat_create_3d", P, ctypes.c_int, ctypes.c_int, ctypes.c_int, P)
    dll.mat_destroy = fn("ncnn_mat_destroy", None, P)
    dll.mat_w = fn("ncnn_mat_get_w", ctypes.c_int, P)
    dll.mat_h = fn("ncnn_mat_get_h", ctypes.c_int, P)
    dll.mat_c = fn("ncnn_mat_get_c", ctypes.c_int, P)
    dll.mat_ch = fn("ncnn_mat_get_channel_data", P, P, ctypes.c_int)
    dll.elemsize = fn("ncnn_mat_get_elemsize", ctypes.c_size_t, P)
    dll.elempack = fn("ncnn_mat_get_elempack", ctypes.c_int, P)
    dll.set_vk = fn("ncnn_net_set_vulkan_device", None, P, ctypes.c_int)
    dll.gpu_create = getattr(dll, "?create_gpu_instance@ncnn@@YAHPEBD@Z")
    dll.gpu_create.restype = ctypes.c_int
    dll.gpu_create.argtypes = [ctypes.c_char_p]
    dll.gpu_count = getattr(dll, "?get_gpu_count@ncnn@@YAHXZ")
    dll.gpu_count.restype = ctypes.c_int
    dll.gpu_info = getattr(dll, "?get_gpu_info@ncnn@@YAAEBVGpuInfo@1@H@Z")
    dll.gpu_info.restype = P
    dll.gpu_info.argtypes = [ctypes.c_int]
    dll.gpu_name = getattr(dll, "?device_name@GpuInfo@ncnn@@QEBAPEBDXZ")
    dll.gpu_name.restype = ctypes.c_char_p
    dll.gpu_name.argtypes = [P]
    dll.gpu_fp16a = getattr(dll, "?support_fp16_arithmetic@GpuInfo@ncnn@@QEBA_NXZ")
    dll.gpu_fp16a.restype = ctypes.c_bool
    dll.gpu_fp16a.argtypes = [P]
    dll.gpu_fp16s = getattr(dll, "?support_fp16_storage@GpuInfo@ncnn@@QEBA_NXZ")
    dll.gpu_fp16s.restype = ctypes.c_bool
    dll.gpu_fp16s.argtypes = [P]
    dll.gpu_destroy = getattr(dll, "?destroy_gpu_instance@ncnn@@YAXXZ")
    dll.gpu_destroy.restype = None
    return dll


def set_flags(opt, fp16, vulkan=False):
    # Offsets checked against ncnn_option_get_num_threads / get_use_vulkan_compute
    # on ncnn-20250503-windows-vs2022-shared x64. Each flag is one byte.
    flags = {
        60: 0,  # winograd, off so the result is the direct convolution
        61: 0,  # sgemm
        62: 0,  # int8 inference
        63: 1 if vulkan else 0,
        64: 0,  # bf16
        65: 0,  # fp16 packed
        66: 1 if fp16 else 0,
        67: 1 if fp16 else 0,
        68: 0,  # int8 packed
        69: 0,
        70: 0,
        71: 0,  # packing layout
    }
    for off, val in flags.items():
        ctypes.c_ubyte.from_address(opt + off).value = val


def read_mat(dll, mat):
    w, h, c = dll.mat_w(mat), dll.mat_h(mat), dll.mat_c(mat)
    es, ep = dll.elemsize(mat), dll.elempack(mat)
    if ep != 1:
        raise RuntimeError("elempack %d" % ep)
    out = np.empty((c, h, w), np.float32)
    for i in range(c):
        ptr = dll.mat_ch(mat, i)
        if es == 4:
            out[i] = np.frombuffer(ctypes.string_at(ptr, h * w * 4), dtype=np.float32).reshape(h, w)
        elif es == 2:
            out[i] = np.frombuffer(ctypes.string_at(ptr, h * w * 2), dtype=np.float16).astype(np.float32).reshape(h, w)
        else:
            raise RuntimeError("elemsize %d" % es)
    return out, es, ep


def forward(dll, net, image, out_name=b"c33"):
    c, h, w = image.shape
    mat = dll.mat3(w, h, c, None)
    for i in range(c):
        ctypes.memmove(dll.mat_ch(mat, i), np.ascontiguousarray(image[i]).ctypes.data, h * w * 4)
    ex = dll.ex_create(net)
    if dll.ex_input(ex, b"data", mat) != 0:
        raise RuntimeError("extractor input failed")
    got = ctypes.c_void_p()
    rc = dll.ex_extract(ex, out_name, ctypes.byref(got))
    dll.ex_destroy(ex)
    dll.mat_destroy(mat)
    if rc != 0:
        raise RuntimeError("extract failed %d" % rc)
    feat, es, ep = read_mat(dll, got)
    dll.mat_destroy(got)
    return feat, es, ep


def hold(data):
    return (ctypes.c_char * len(data)).from_buffer_copy(data), len(data)


def open_net(dll, par, blob, nbytes, fp16, vulkan=False):
    net = dll.create()
    opt = dll.get_opt(net)
    set_flags(opt, fp16, vulkan)
    if vulkan:
        dll.set_vk(net, 0)
    if dll.vulkan(opt) != (1 if vulkan else 0):
        raise RuntimeError("vulkan flag did not stick")
    if dll.load_param(net, par) != 0:
        raise RuntimeError("load_param failed")
    consumed = dll.load_model(net, ctypes.cast(blob, ctypes.c_void_p))
    if consumed != nbytes:
        raise RuntimeError("load_model consumed %d of %d" % (consumed, nbytes))
    return net


def final_from_feat(feat, image):
    c, h, w = feat.shape
    co = 3
    scale = 4
    shuffled = feat.reshape(co, scale, scale, h, w).transpose(0, 3, 1, 4, 2).reshape(co, h * scale, w * scale)
    base = np.repeat(np.repeat(np.clip(image, 0, 1), scale, 1), scale, 2)
    return shuffled + base


def main():
    dll = load_dll()
    print("dll", DLL)
    # one known convolution: weight 2, bias 0.5, input 1 -> 2.5. Proves the FP32 tag.
    tiny_param = b"7767517\n2 2\nInput in 0 1 data\nConvolution conv 1 1 data out 0=1 1=1 11=1 2=1 12=1 3=1 13=1 4=0 14=0 5=1 6=1\n"
    tiny_blob, tiny_n = hold(np.uint32(0).tobytes() + np.float32(2).tobytes() + np.float32(0.5).tobytes())
    tiny = open_net(dll, tiny_param, tiny_blob, tiny_n, False)
    tiny_out, _, _ = forward(dll, tiny, np.ones((1, 1, 1), np.float32), b"out")
    dll.destroy(tiny)
    print("tiny_conv", float(tiny_out.reshape(-1)[0]))
    if abs(float(tiny_out.reshape(-1)[0]) - 2.5) > 1e-5:
        raise SystemExit("FP32 tag rejected: expected 2.5")

    weights = np.fromfile(BLOB, "<f4")
    layers = R.load_blob(BLOB)
    blob, nbytes = hold(build_bin(weights))
    par = param()
    rng = np.random.RandomState(1)
    image = rng.rand(3, 8, 8).astype(np.float32)
    ref = R.forward(layers, image).astype(np.float32)

    net = open_net(dll, par, blob, nbytes, False)
    feat, _, _ = forward(dll, net, image)
    got = final_from_feat(feat, image)
    err32 = float(np.max(np.abs(got - ref)))
    print("fp32_max_abs", err32)
    dll.destroy(net)
    if err32 > FP32_LIMIT:
        raise SystemExit("FP32 ncnn does not match the reference")

    # The same buffer is still the one load_model referenced. Wiping it and running
    # again must change the result, otherwise this loader copied and the comment is wrong.
    wiped = open_net(dll, par, blob, nbytes, False)
    ctypes.memset(blob, 0xFF, nbytes)
    wiped_feat, _, _ = forward(dll, wiped, image)
    wiped_err = float(np.max(np.abs(final_from_feat(wiped_feat, image) - ref)))
    dll.destroy(wiped)
    print("after_wipe_max_abs", wiped_err)
    if wiped_err < 1e-3:
        raise SystemExit("wiping the buffer did not change the output; the reference contract was not observed")
    print("weights_are_referenced")

    fresh, fresh_n = hold(build_bin(weights))
    net16 = open_net(dll, par, fresh, fresh_n, True)
    feat16, es16, _ = forward(dll, net16, image)
    err16 = float(np.max(np.abs(final_from_feat(feat16, image) - ref)))
    dll.destroy(net16)
    print("cpu_fp16_flags_max_abs", err16)
    print("cpu_fp16_elemsize", es16)
    if abs(err16 - err32) < 1e-12:
        print("cpu_fp16_flags_did_not_change_fp32")
    if dll.gpu_create(None) != 0 or dll.gpu_count() < 1:
        raise SystemExit("host Vulkan device missing; FP16 arithmetic was not executed")
    ginfo = dll.gpu_info(0)
    print("vulkan_device", dll.gpu_name(ginfo).decode())
    print("vulkan_fp16_storage", bool(dll.gpu_fp16s(ginfo)), "vulkan_fp16_arithmetic", bool(dll.gpu_fp16a(ginfo)))
    vk32 = open_net(dll, par, fresh, fresh_n, False, True)
    feat_vk32, es_vk32, ep_vk32 = forward(dll, vk32, image)
    err_vk32 = float(np.max(np.abs(final_from_feat(feat_vk32, image) - ref)))
    dll.destroy(vk32)
    print("vulkan_fp32_max_abs", err_vk32, "elemsize", es_vk32, "elempack", ep_vk32)
    if err_vk32 > FP32_LIMIT:
        raise SystemExit("Vulkan FP32 ncnn does not match the reference")
    held16, n16 = hold(build_bin(weights))
    vk16 = open_net(dll, par, held16, n16, True, True)
    feat_vk16, es_vk16, ep_vk16 = forward(dll, vk16, image)
    err_vk16 = float(np.max(np.abs(final_from_feat(feat_vk16, image) - ref)))
    dll.destroy(vk16)
    dll.gpu_destroy()
    print("vulkan_fp16_max_abs", err_vk16, "elemsize", es_vk16, "elempack", ep_vk16)
    if err_vk16 > FP16_LIMIT:
        raise SystemExit("Vulkan FP16 ncnn is outside %.3f" % FP16_LIMIT)
    if es_vk16 != 4 or ep_vk16 != 1:
        raise SystemExit("FP16 extract is not float32 elempack 1; the app rejects that layout")
    print("PASS numeric_ncnn_20250503")
    print("host_vulkan_is_not_maleoon")


if __name__ == "__main__":
    main()
