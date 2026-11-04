// Experimental SRVGGNetCompact (realesr-general-x4v3) via ncnn.
// Weights are the app's srvgg_general_x4v3.bin (float32). Same OIHW layout as tools/srvgg_reference.py.
// PixelShuffle(4) and the nearest x4 residual are applied here, not inside ncnn, using that file's formula.
// INT8 is not enabled. FP16 is a runtime flag only.
#include <android/log.h>
#include <jni.h>
#include <net.h>
#include <gpu.h>
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
    ncnn::Net net;
    bool fp16 = false;
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

static std::string make_param() {
    // 1 input + 33 (conv+prelu) + final conv = 68 layers
    // blobs: data + 33 conv + 33 prelu + final = 68
    std::ostringstream p;
    p << "7767517\n68 68\n";
    p << "Input in 0 1 data\n";
    std::string prev = "data";
    for (int L = 0; L < 33; L++) {
        int oc = 64, ic = L == 0 ? 3 : 64;
        int wds = oc * ic * 9;
        std::string c = "c" + std::to_string(L), r = "p" + std::to_string(L);
        p << "Convolution conv" << L << " 1 1 " << prev << " " << c
          << " 0=" << oc << " 1=3 11=3 2=1 12=1 3=1 13=1 4=1 14=1 5=1 6=" << wds << "\n";
        p << "PReLU prelu" << L << " 1 1 " << c << " " << r << " 0=64\n";
        prev = r;
    }
    p << "Convolution conv33 1 1 " << prev << " c33 0=48 1=3 11=3 2=1 12=1 3=1 13=1 4=1 14=1 5=1 6=27648\n";
    return p.str();
}

static bool build_bin(const unsigned char* bytes, int nbytes, std::vector<float>& bin) {
    if (nbytes != 4853184) { set_err("model blob size " + std::to_string(nbytes) + " != 4853184"); return false; }
    std::vector<float> w(nbytes / 4);
    std::memcpy(w.data(), bytes, nbytes);
    int o[34], c[34];
    o[0] = 64; c[0] = 3;
    for (int i = 1; i <= 32; i++) { o[i] = 64; c[i] = 64; }
    o[33] = 48; c[33] = 64;
    size_t off = 0;
    bin.clear();
    for (int L = 0; L < 34; L++) {
        size_t nw = (size_t)o[L] * c[L] * 9;
        if (off + nw + o[L] > w.size()) { set_err("weight parse overflow"); return false; }
        bin.insert(bin.end(), w.begin() + off, w.begin() + off + nw);
        off += nw;
        bin.insert(bin.end(), w.begin() + off, w.begin() + off + o[L]);
        off += o[L];
        if (L < 33) {
            if (off + 64 > w.size()) { set_err("prelu parse overflow"); return false; }
            bin.insert(bin.end(), w.begin() + off, w.begin() + off + 64);
            off += 64;
        }
    }
    if (off != w.size()) { set_err("weight parse leftover"); return false; }
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

JNIEXPORT jlong JNICALL Java_com_mojahed_hdrstudio_ai_NcnnSr_nativeCreate(JNIEnv* e, jclass, jbyteArray model, jboolean fp16) {
    if (!ensure_gpu()) return 0;
    jbyte* bytes = e->GetByteArrayElements(model, nullptr);
    jsize n = e->GetArrayLength(model);
    std::vector<float> bin;
    bool ok = build_bin(reinterpret_cast<const unsigned char*>(bytes), n, bin);
    e->ReleaseByteArrayElements(model, bytes, JNI_ABORT);
    if (!ok) return 0;
    NetHolder* h = new NetHolder();
    h->fp16 = fp16 == JNI_TRUE;
    h->gpu_index = 0;
    ncnn::VulkanDevice* vk = ncnn::get_gpu_device(0);
    ncnn::Option opt;
    opt.use_vulkan_compute = true;
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = h->fp16;
    opt.use_fp16_arithmetic = h->fp16;
    opt.use_packing_layout = false;
    opt.use_int8_inference = false;
    opt.use_int8_packed = false;
    opt.use_int8_storage = false;
    opt.use_int8_arithmetic = false;
    opt.num_threads = 1;
    h->net.opt = opt;
    h->net.set_vulkan_device(vk);
    std::string param = make_param();
    if (h->net.load_param_mem(param.c_str()) != 0) { set_err("load_param_mem failed"); delete h; return 0; }
    const unsigned char* mem = reinterpret_cast<const unsigned char*>(bin.data());
    if (h->net.load_model(mem) != 0) { set_err("load_model failed"); delete h; return 0; }
    h->full_vulkan = true;
    for (size_t i = 0; i < h->net.layers().size(); i++) {
        const ncnn::Layer* layer = h->net.layers()[i];
        if (layer->type != "Input" && !layer->support_vulkan) {
            h->full_vulkan = false;
            set_err(std::string("CPU fallback layer ") + layer->name + " type=" + layer->type);
        }
    }
    LOGI("net ready fp16=%d full_vulkan=%d", h->fp16 ? 1 : 0, h->full_vulkan ? 1 : 0);
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
    int er = ex.extract("c33", conv);
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
        ph[1] = std::chrono::duration_cast<std::chrono::nanoseconds>(t2 - t1).count();
        ph[2] = std::chrono::duration_cast<std::chrono::nanoseconds>(t3 - t2).count();
        e->SetLongArrayRegion(phaseNs, 0, 3, ph);
    }
    return h->full_vulkan ? 1 : 2;
}

} // extern C
