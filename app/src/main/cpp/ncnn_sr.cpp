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
    bool fp16 = false;
    bool vulkan = true;
    bool full_vulkan = false;
    int gpu_index = -1;
};

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
        JNIEnv* e, jclass, jbyteArray model, jboolean vulkan, jboolean fp16, jboolean winograd, jboolean sgemm, jboolean packing) {
    // vulkan=false is the CPU FP32 comparison baseline. Export only opens vulkan=true.
    const bool use_vk = vulkan == JNI_TRUE;
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
    h->fp16 = fp16 == JNI_TRUE;
    h->vulkan = use_vk;
    h->gpu_index = use_vk ? 0 : -1;
    ncnn::Option opt;
    opt.use_vulkan_compute = use_vk;
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = h->fp16;
    opt.use_fp16_arithmetic = h->fp16;
    opt.use_bf16_storage = false;
    opt.use_packing_layout = packing == JNI_TRUE;
    opt.use_winograd_convolution = winograd == JNI_TRUE;
    opt.use_sgemm_convolution = sgemm == JNI_TRUE;
    opt.use_int8_inference = false;
    opt.use_int8_packed = false;
    opt.use_int8_storage = false;
    opt.use_int8_arithmetic = false;
    opt.num_threads = use_vk ? 1 : ncnn::get_big_cpu_count();
    h->net.opt = opt;
    if (use_vk) h->net.set_vulkan_device(ncnn::get_gpu_device(0));
    std::string param = srvgg_ncnn_param();
    if (h->net.load_param_mem(param.c_str()) != 0) { set_err("load_param_mem failed"); delete h; return 0; }
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
    LOGI("net ready vulkan=%d fp16=%d full_vulkan=%d threads=%d", use_vk ? 1 : 0, h->fp16 ? 1 : 0, h->full_vulkan ? 1 : 0, opt.num_threads);
    return reinterpret_cast<jlong>(h);
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

} // extern C
