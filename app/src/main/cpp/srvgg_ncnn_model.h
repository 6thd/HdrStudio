#pragma once
// SRVGGNetCompact weights for ncnn 20250503.
// Convolution::load_model asks ModelBin for type 0 (auto). In this version a leading
// 0x00000000 tag means the following float32 values are raw FP32 (src/modelbin.cpp, f0 == 0).
// 0x01306B47 is the FP16 tag in this version, so it must not be used here.
// Bias and PReLU slopes use ModelBin type 1: raw float32 with no tag.
// Net::load_model(const unsigned char*) references that memory. It does not copy it.
// The caller keeps the buffer until the Net is destroyed.
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

inline void srvgg_append_bytes(std::vector<unsigned char>& bin, const void* p, size_t n)
{
    const unsigned char* b = static_cast<const unsigned char*>(p);
    bin.insert(bin.end(), b, b + n);
}

inline void srvgg_append_u32(std::vector<unsigned char>& bin, std::uint32_t v)
{
    srvgg_append_bytes(bin, &v, 4);
}

inline void srvgg_append_f32(std::vector<unsigned char>& bin, const float* p, size_t n)
{
    srvgg_append_bytes(bin, p, n * sizeof(float));
}

inline std::string srvgg_ncnn_param()
{
    std::string p = "7767517\n68 68\nInput in 0 1 data\n";
    std::string prev = "data";
    for (int L = 0; L < 33; L++)
    {
        int oc = 64;
        int ic = L == 0 ? 3 : 64;
        int wds = oc * ic * 9;
        std::string c = "c" + std::to_string(L);
        std::string r = "p" + std::to_string(L);
        p += "Convolution conv" + std::to_string(L) + " 1 1 " + prev + " " + c
            + " 0=" + std::to_string(oc) + " 1=3 11=3 2=1 12=1 3=1 13=1 4=1 14=1 5=1 6=" + std::to_string(wds) + "\n";
        p += "PReLU prelu" + std::to_string(L) + " 1 1 " + c + " " + r + " 0=64\n";
        prev = r;
    }
    p += "Convolution conv33 1 1 " + prev + " c33 0=48 1=3 11=3 2=1 12=1 3=1 13=1 4=1 14=1 5=1 6=27648\n";
    return p;
}

// Returns false and sets err when the blob is not the app weight file.
inline bool srvgg_build_ncnn_bin(const float* w, size_t nfloat, std::vector<unsigned char>& bin, std::string& err)
{
    if (nfloat != 1213296)
    {
        err = "weight float count " + std::to_string(nfloat) + " != 1213296";
        return false;
    }
    int o[34], c[34];
    o[0] = 64;
    c[0] = 3;
    for (int i = 1; i <= 32; i++)
    {
        o[i] = 64;
        c[i] = 64;
    }
    o[33] = 48;
    c[33] = 64;
    bin.clear();
    bin.reserve(4853184 + 34 * 4);
    size_t off = 0;
    for (int L = 0; L < 34; L++)
    {
        size_t nw = (size_t)o[L] * c[L] * 9;
        if (off + nw + (size_t)o[L] > nfloat)
        {
            err = "weight parse overflow";
            return false;
        }
        srvgg_append_u32(bin, 0u); // FP32 tag consumed by ModelBin type 0
        srvgg_append_f32(bin, w + off, nw);
        off += nw;
        srvgg_append_f32(bin, w + off, (size_t)o[L]); // bias, ModelBin type 1, no tag
        off += (size_t)o[L];
        if (L < 33)
        {
            if (off + 64 > nfloat)
            {
                err = "prelu parse overflow";
                return false;
            }
            srvgg_append_f32(bin, w + off, 64); // PReLU slopes, ModelBin type 1, no tag
            off += 64;
        }
    }
    if (off != nfloat)
    {
        err = "weight parse leftover";
        return false;
    }
    if (bin.size() != 4853184u + 34u * 4u)
    {
        err = "ncnn bin size " + std::to_string(bin.size());
        return false;
    }
    if ((reinterpret_cast<uintptr_t>(bin.data()) & 3u) != 0)
    {
        err = "ncnn bin is not 32-bit aligned";
        return false;
    }
    return true;
}
