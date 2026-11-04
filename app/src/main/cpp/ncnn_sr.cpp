// Experimental SRVGGNetCompact (realesr-general-x4v3) via ncnn.
// Weights are the app's srvgg_general_x4v3.bin (float32). Same OIHW layout as tools/srvgg_reference.py.
// PixelShuffle(4) and the nearest x4 residual are applied here, not inside ncnn, using that file's formula.
// INT8 is not enabled. FP16 is a runtime flag only.
#include <android/log.h>
#include <jni.h>
#include <net.h>
#include <gpu.h>
#include <cpu.h>
#include "srvgg_ncnn_model.h"
#include <chrono>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hdrstudio-ncnn", __VA_ARGS__)

static std::string g_err;
static std::string g_device;
static bool g_gpu_ready = false;

struct NetHolder {
    // Destroyed after net. load_model() keeps pointers into model.
    std::vector<unsigned char> model;
    ncnn::Net net;
    bool vulkan = true;
    bool full_vulkan = false;
    int gpu_index = -1;
    std::string options;  // requested and effective precision options, device quirks
};

// Flags of NcnnSr.Opts.flags(). Keep in sync with NcnnSr.java.
enum {
    F_VULKAN = 1, F_FP16_STORAGE = 2, F_FP16_ARITH = 4, F_FP16_PACKED = 8,
    F_FP16_UNIFORM = 16, F_WINOGRAD = 32, F_SGEMM = 64, F_PACKING = 128
};

static std::string opt_str(const ncnn::Option& o) {
    std::ostringstream s;
    s << "vulkan=" << o.use_vulkan_compute
      << " fp16_storage=" << o.use_fp16_storage << " fp16_arith=" << o.use_fp16_arithmetic
      << " fp16_packed=" << o.use_fp16_packed << " fp16_uniform=" << o.use_fp16_uniform
      << " bf16_storage=" << o.use_bf16_storage << " int8=" << o.use_int8_inference
      << " image_storage=" << o.use_image_storage << " packing=" << o.use_packing_layout
      << " winograd=" << o.use_winograd_convolution << " sgemm=" << o.use_sgemm_convolution
      << " threads=" << o.num_threads;
    return s.str();
}

static void set_err(const std::string& s) { g_err = s; LOGI("%s", s.c_str()); }

static bool ensure_gpu() {
    if (g_gpu_ready) return ncnn::get_gpu_count() > 0;
    int r = ncnn::create_gpu_instance();
    g_gpu_ready = true;
    if (r != 0) { set_err("ncnn::create_gpu_instance failed (" + std::to_string(r) + ")"); return false; }
    int n = ncnn::get_gpu_count();
    if (n <= 0) { set_err("no Vulkan device (get_gpu_count=0). OpenGL ES does not imply this Vulkan path."); return false; }
    std::ostringstream o;
    for (int i = 0; i < n; i++) {
        const ncnn::GpuInfo& info = ncnn::get_gpu_device(i)->info;
        if (i) o << " | ";
        o << i << ":" << info.device_name()
          << " api=" << info.api_version()
          << " fp16_arith=" << info.support_fp16_arithmetic()
          << " fp16_store=" << info.support_fp16_storage()
          << " int8_arith=" << info.support_int8_arithmetic();
    }
    g_device = o.str();
    LOGI("vulkan %s", g_device.c_str());
    return true;
}


static void shuffle_residual(const ncnn::Mat& conv, const ncnn::Mat& in, float* out) {
    // conv and in are top-down (ncnn y=0 is the top row), matching tools/srvgg_reference.py.
    // out is bottom-up so it can be uploaded to a GL texture without another flip.
    // Residual is clamped to [0,1] as in sr_final.frag.
    const int w = in.w, h = in.h, W4 = w * 4, H4 = h * 4;
    for (int c = 0; c < 3; c++) {
        const ncnn::Mat src = in.channel(c);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float base = src[y * w + x];
                if (base < 0.f) base = 0.f;
                if (base > 1.f) base = 1.f;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        int oc = c * 16 + sy * 4 + sx;
                        float v = conv.channel(oc)[y * conv.w + x] + base;
                        int oy = y * 4 + sy, ox = x * 4 + sx;
                        int gy = H4 - 1 - oy;
                        out[(gy * W4 + ox) * 3 + c] = v;
                    }
                }
            }
        }
    }
}

extern "C" {

JNIEXPORT jstring JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeLastError(JNIEnv* e, jclass) {
    return e->NewStringUTF(g_err.c_str());
}
JNIEXPORT jstring JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeDevice(JNIEnv* e, jclass) {
    ensure_gpu();
    return e->NewStringUTF(g_device.c_str());
}

JNIEXPORT jlong JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeCreate(
        JNIEnv* e, jclass, jbyteArray model, jint flags) {
    // Vulkan off is the CPU FP32 comparison baseline only. Export opens Vulkan FP32.
    const bool use_vk = (flags & F_VULKAN) != 0;
    if (use_vk && !ensure_gpu()) return 0;
    g_err.clear();
    jbyte* bytes = e->GetByteArrayElements(model, nullptr);
    jsize n = e->GetArrayLength(model);
    if (n != 4853184) { set_err("model blob size " + std::to_string(n) + " != 4853184"); e->ReleaseByteArrayElements(model, bytes, JNI_ABORT); return 0; }
    std::vector<float> weights(n / 4);
    std::memcpy(weights.data(), bytes, n);
    e->ReleaseByteArrayElements(model, bytes, JNI_ABORT);
    NetHolder* h = new NetHolder();
    std::string berr;
    if (!srvgg_build_ncnn_bin(weights.data(), weights.size(), h->model, berr)) { set_err(berr); delete h; return 0; }
    h->vulkan = use_vk;
    h->gpu_index = use_vk ? 0 : -1;
    ncnn::Option opt;
    opt.use_vulkan_compute = use_vk;
    opt.use_fp16_storage = (flags & F_FP16_STORAGE) != 0;
    opt.use_fp16_arithmetic = (flags & F_FP16_ARITH) != 0;
    opt.use_fp16_packed = (flags & F_FP16_PACKED) != 0;
    opt.use_fp16_uniform = (flags & F_FP16_UNIFORM) != 0;  // ncnn's default is true; set explicitly
    opt.use_bf16_storage = false;
    opt.use_image_storage = false;
    opt.use_packing_layout = (flags & F_PACKING) != 0;
    opt.use_winograd_convolution = (flags & F_WINOGRAD) != 0;
    opt.use_sgemm_convolution = (flags & F_SGEMM) != 0;
    opt.use_int8_inference = false;
    opt.use_int8_packed = false;
    opt.use_int8_storage = false;
    opt.use_int8_arithmetic = false;
    opt.use_int8_uniform = false;
    opt.num_threads = use_vk ? 1 : ncnn::get_big_cpu_count();
    const ncnn::Option requested = opt;
    h->net.opt = opt;
    if (use_vk) h->net.set_vulkan_device(ncnn::get_gpu_device(0));
    std::string param = srvgg_ncnn_param();
    if (h->net.load_param_mem(param.c_str()) != 0) { set_err("load_param_mem failed"); delete h; return 0; }
    // load_param sanitizes net.opt in place for the device. What is left is what runs.
    const ncnn::Option& eff = h->net.opt;
    std::ostringstream o;
    o << "requested: " << opt_str(requested) << " | effective: " << opt_str(eff);
    if (use_vk) {
        const ncnn::GpuInfo& gi = ncnn::get_gpu_device(0)->info;
        o << " | device fp16_storage=" << gi.support_fp16_storage() << " fp16_arith=" << gi.support_fp16_arithmetic()
          << " fp16_uniform=" << gi.support_fp16_uniform() << " bug_implicit_fp16_arith=" << gi.bug_implicit_fp16_arithmetic();
    }
    h->options = o.str();
    // No silent change of precision: refuse the mode instead of running a different one.
    if (eff.use_vulkan_compute != requested.use_vulkan_compute
            || eff.use_fp16_storage != requested.use_fp16_storage
            || eff.use_fp16_arithmetic != requested.use_fp16_arithmetic
            || eff.use_fp16_packed != requested.use_fp16_packed
            || eff.use_fp16_uniform != requested.use_fp16_uniform
            || eff.use_bf16_storage || eff.use_int8_inference) {
        set_err("ncnn changed the precision options for this device; mode refused. " + h->options);
        delete h;
        return 0;
    }
    // References h->model. The vector is a member and outlives the Net.
    int consumed = h->net.load_model(h->model.data());
    if (consumed != (int)h->model.size()) {
        set_err("load_model consumed " + std::to_string(consumed) + " of " + std::to_string(h->model.size()));
        delete h;
        return 0;
    }
    h->full_vulkan = use_vk;
    if (use_vk) {
        for (size_t i = 0; i < h->net.layers().size(); i++) {
            const ncnn::Layer* layer = h->net.layers()[i];
            if (layer->type != "Input" && !layer->support_vulkan) {
                h->full_vulkan = false;
                set_err(std::string("CPU fallback layer ") + layer->name + " type=" + layer->type);
            }
        }
    }
    LOGI("net ready full_vulkan=%d %s", h->full_vulkan ? 1 : 0, h->options.c_str());
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeOptions(JNIEnv* e, jclass, jlong handle) {
    NetHolder* h = reinterpret_cast<NetHolder*>(handle);
    return e->NewStringUTF(h ? h->options.c_str() : "");
}

JNIEXPORT void JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<NetHolder*>(handle);
}

JNIEXPORT jboolean JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeFullVulkan(JNIEnv*, jclass, jlong handle) {
    NetHolder* h = reinterpret_cast<NetHolder*>(handle);
    return h && h->full_vulkan ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeForward(
        JNIEnv* e, jclass, jlong handle, jfloatArray inArr, jint w, jint hgt, jfloatArray outArr, jlongArray phaseNs) {
    NetHolder* h = reinterpret_cast<NetHolder*>(handle);
    if (!h || w <= 0 || hgt <= 0) { set_err("bad forward args"); return -1; }
    const int n = w * hgt;
    if (e->GetArrayLength(inArr) < n * 3 || e->GetArrayLength(outArr) < n * 16 * 3) {
        set_err("forward buffer size"); return -2;
    }
    jfloat* in = e->GetFloatArrayElements(inArr, nullptr);
    jfloat* out = e->GetFloatArrayElements(outArr, nullptr);
    auto t0 = std::chrono::steady_clock::now();
    // GL / Java buffers are bottom-up. ncnn treats y=0 as the top row.
    ncnn::Mat input(w, hgt, 3);
    for (int c = 0; c < 3; c++) {
        float* dst = input.channel(c);
        for (int y = 0; y < hgt; y++) {
            int gy = hgt - 1 - y;
            for (int x = 0; x < w; x++)
                dst[y * w + x] = in[(gy * w + x) * 3 + c];
        }
    }
    auto t1 = std::chrono::steady_clock::now();
    ncnn::Extractor ex = h->net.create_extractor();
    ex.set_light_mode(true);
    if (ex.input("data", input) != 0) { set_err("extractor.input failed"); e->ReleaseFloatArrayElements(inArr, in, JNI_ABORT); e->ReleaseFloatArrayElements(outArr, out, 0); return -3; }
    ncnn::Mat conv;
    int er = ex.extract("c33", conv);  // type 0: cast to FP32 and unpack to elempack 1
    if (er == 0 && conv.elempack != 1) {
        ncnn::Mat unpacked;
        ncnn::convert_packing(conv, unpacked, 1, h->net.opt);
        conv = unpacked;
    }
    auto t2 = std::chrono::steady_clock::now();
    if (er != 0 || conv.c != 48 || conv.elempack != 1 || conv.elemsize != 4) {
        set_err("extract c33 failed rc=" + std::to_string(er) + " c=" + std::to_string(conv.c)
                + " pack=" + std::to_string(conv.elempack) + " esize=" + std::to_string(conv.elemsize));
        e->ReleaseFloatArrayElements(inArr, in, JNI_ABORT);
        e->ReleaseFloatArrayElements(outArr, out, 0);
        return -4;
    }
    shuffle_residual(conv, input, out);
    auto t3 = std::chrono::steady_clock::now();
    e->ReleaseFloatArrayElements(inArr, in, JNI_ABORT);
    e->ReleaseFloatArrayElements(outArr, out, 0);
    if (phaseNs && e->GetArrayLength(phaseNs) >= 3) {
        jlong ph[3];
        ph[0] = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
        // extract() waits until the GPU result is in CPU memory. This is not GPU-compute alone.
        ph[1] = std::chrono::duration_cast<std::chrono::nanoseconds>(t2 - t1).count();
        ph[2] = std::chrono::duration_cast<std::chrono::nanoseconds>(t3 - t2).count();
        e->SetLongArrayRegion(phaseNs, 0, 3, ph);
    }
    return h->full_vulkan ? 1 : 2;
}

// One intermediate blob (c0..c33, p0..p32) for the per-layer diagnostic. in: bottom-up RGB like nativeForward.
// out: top-down CHW floats. Returns the element count, or a negative error. Ported from 2.3.2-test's NcnnMatchProbe tool.
JNIEXPORT jint JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeLayer(
        JNIEnv* e, jclass, jlong handle, jfloatArray inArr, jint w, jint hgt, jstring blobName, jfloatArray outArr) {
    NetHolder* h = reinterpret_cast<NetHolder*>(handle);
    if (!h || w <= 0 || hgt <= 0) { set_err("bad layer args"); return -1; }
    if (e->GetArrayLength(inArr) < w * hgt * 3) { set_err("layer input size"); return -2; }
    const char* name = e->GetStringUTFChars(blobName, nullptr);
    std::string blob(name);
    e->ReleaseStringUTFChars(blobName, name);
    jfloat* in = e->GetFloatArrayElements(inArr, nullptr);
    ncnn::Mat input(w, hgt, 3);
    for (int c = 0; c < 3; c++) {
        float* dst = input.channel(c);
        for (int y = 0; y < hgt; y++) {
            int gy = hgt - 1 - y;
            for (int x = 0; x < w; x++) dst[y * w + x] = in[(gy * w + x) * 3 + c];
        }
    }
    e->ReleaseFloatArrayElements(inArr, in, JNI_ABORT);
    ncnn::Extractor ex = h->net.create_extractor();
    if (ex.input("data", input) != 0) { set_err("extractor.input failed"); return -3; }
    ncnn::Mat m;
    if (ex.extract(blob.c_str(), m) != 0 || m.empty()) { set_err("extract " + blob + " failed"); return -4; }
    if (m.elempack != 1) { ncnn::Mat u; ncnn::convert_packing(m, u, 1, h->net.opt); m = u; }
    if (m.elemsize != 4) { set_err("extract " + blob + " elemsize " + std::to_string(m.elemsize)); return -5; }
    const int n = m.c * m.h * m.w;
    if (e->GetArrayLength(outArr) < n) { set_err("layer output size"); return -6; }
    std::vector<float> flat((size_t)n);
    for (int c = 0; c < m.c; c++) std::memcpy(flat.data() + (size_t)c * m.h * m.w, (const float*)m.channel(c), sizeof(float) * m.h * m.w);
    e->SetFloatArrayRegion(outArr, 0, n, flat.data());
    return n;
}

} // extern C
