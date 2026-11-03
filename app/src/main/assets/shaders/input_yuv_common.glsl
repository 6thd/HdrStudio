// Shared by input_ext_yuv.frag (Android) and input_tex_yuv.frag (desktop harness).
// Converts sampled Y'CbCr exactly once, then maps the source transfer into the working domain.
uniform float uCodeScale;   // 255.0 for 8-bit, 1023.984375 for P010 via EXT_YUV_target
uniform float uBitDepth;    // 8.0 / 10.0
uniform bool  uFullRange;
uniform int   uMatrix;      // 0 BT.709, 1 BT.2020 NCL, 2 BT.601
uniform int   uTransfer;    // 0 SDR, 1 PQ, 2 HLG
uniform bool  uSrc2020;     // primaries BT.2020
uniform int   uWorkDomain;  // 0 E_SDR, 1 E_PQ
uniform float uHlgPeak;     // nominal HLG display peak (nits), 1000 per BT.2100 reference

vec3 yuvToWorking(vec3 yuv) {
    vec3 rgb = clamp(ycbcrToRgb(yuv, uCodeScale, uBitDepth, uFullRange, uMatrix), 0.0, 1.0);
    if (uWorkDomain == 1) {                       // HDR working domain: PQ / BT.2020
        if (uTransfer == 1) {
            if (!uSrc2020) rgb = pqEncode(M709_TO_2020 * pqDecode(rgb));
            return rgb;
        }
        if (uTransfer == 2) {
            vec3 nits = hlgToNits(rgb, uHlgPeak);
            if (!uSrc2020) nits = M709_TO_2020 * nits;
            return pqEncode(nits);
        }
        // SDR into HDR domain is not used by the pipeline (SDR stays in E_SDR until hdr_map).
        vec3 lin = sdrToLinear(rgb, 0);
        if (!uSrc2020) lin = M709_TO_2020 * lin;
        return pqEncode(lin * 203.0);
    }
    if (uSrc2020) rgb = linearToSdr(max(M2020_TO_709 * sdrToLinear(rgb, 0), 0.0), 0);
    return rgb;
}
