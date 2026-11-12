// ============================================================================
// HDR Studio - shared colour library (GLSL ES 3.00).
// Prepended to every fragment shader, on Android and in the desktop test
// harness (tools/harness). Keep it free of samplers/uniforms.
//
// Signal domains used in the pipeline (see docs/COLOR_PIPELINE.md):
//   "E_SDR" : BT.709 primaries, BT.709/BT.1886 non-linear R'G'B', full range [0,1]
//   "E_PQ"  : BT.2020 primaries, SMPTE ST 2084 (PQ) non-linear R'G'B', full range [0,1]
//   linear  : relative (SDR, 1.0 = SDR peak) or absolute nits (HDR)
// ============================================================================

// GLSL matrices are column-major: mat3(col0, col1, col2).
const mat3 M709_TO_2020 = mat3(
    0.627403896, 0.069097289, 0.016391439,
    0.329283039, 0.919540395, 0.088013308,
    0.043313065, 0.011362316, 0.895595253);
const mat3 M2020_TO_709 = mat3(
     1.660491002, -0.124550474, -0.018150763,
    -0.587641139,  1.132899897, -0.100578898,
    -0.072849862, -0.008349423,  1.118729660);

const vec3 LUMA709  = vec3(0.2126, 0.7152, 0.0722);
const vec3 LUMA2020 = vec3(0.2627, 0.6780, 0.0593);

// ---------------------------------------------------------------- PQ (ST 2084)
const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;

// nits (0..10000) -> PQ code value (0..1)
vec3 pqEncode(vec3 nits) {
    vec3 y = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), vec3(PQ_M2));
}
float pqEncode1(float nits) { return pqEncode(vec3(nits)).r; }

// PQ code value (0..1) -> nits
vec3 pqDecode(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / PQ_M2));
    return 10000.0 * pow(max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p), vec3(1.0 / PQ_M1));
}
float pqDecode1(float e) { return pqDecode(vec3(e)).r; }

// ---------------------------------------------------------------- HLG (BT.2100)
const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;

vec3 hlgInverseOetf(vec3 e) {           // -> scene linear 0..1
    vec3 lo = e * e / 3.0;
    vec3 hi = (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0;
    return mix(lo, hi, step(0.5, e));
}
// HLG signal -> display nits on a nominal display of peak Lw (BT.2100 OOTF).
vec3 hlgToNits(vec3 e, float Lw) {
    vec3 s = hlgInverseOetf(clamp(e, 0.0, 1.0));
    float gamma = 1.2 + 0.42 * log(Lw / 1000.0) / log(10.0);
    float ys = max(dot(LUMA2020, s), 1e-6);
    return Lw * pow(ys, gamma - 1.0) * s;
}

// ---------------------------------------------------------------- SDR transfer
// SDR code value -> linear relative light (1.0 = SDR peak/white).
// gammaMode 0: BT.1886 display EOTF with zero black (pure power 2.4, ITU-R BT.2408 SDR->HDR mapping)
// gammaMode 1: pure power 2.2 (typical phone/sRGB-like viewing)
vec3 sdrToLinear(vec3 e, int gammaMode) {
    vec3 c = clamp(e, 0.0, 1.0);
    return gammaMode == 1 ? pow(c, vec3(2.2)) : pow(c, vec3(2.4));
}
vec3 linearToSdr(vec3 l, int gammaMode) {
    vec3 c = clamp(l, 0.0, 1.0);
    return gammaMode == 1 ? pow(c, vec3(1.0 / 2.2)) : pow(c, vec3(1.0 / 2.4));
}

// ---------------------------------------------------------------- YCbCr
// Y'CbCr (normalised sampler values) -> R'G'B'.
// codeScale: value the sampler returns 1.0 for, expressed in codes (255 for 8-bit,
//            1023.984375 for P010 sampled through EXT_YUV_target).
// bitDepth : 8 or 10 (defines the limited-range code points).
// matrixId : 0 = BT.709, 1 = BT.2020 NCL, 2 = BT.601
vec3 ycbcrToRgb(vec3 yuv, float codeScale, float bitDepth, bool fullRange, int matrixId) {
    float k = pow(2.0, bitDepth - 8.0);
    vec3 code = yuv * codeScale;
    float y, cb, cr;
    if (fullRange) {
        float maxc = pow(2.0, bitDepth) - 1.0;
        y = code.x / maxc; cb = (code.y - 128.0 * k) / maxc; cr = (code.z - 128.0 * k) / maxc;
    } else {
        y = (code.x - 16.0 * k) / (219.0 * k);
        cb = (code.y - 128.0 * k) / (224.0 * k);
        cr = (code.z - 128.0 * k) / (224.0 * k);
    }
    float kr = 0.2126, kb = 0.0722;
    if (matrixId == 1) { kr = 0.2627; kb = 0.0593; }
    else if (matrixId == 2) { kr = 0.299; kb = 0.114; }
    float kg = 1.0 - kr - kb;
    float r = y + 2.0 * (1.0 - kr) * cr;
    float b = y + 2.0 * (1.0 - kb) * cb;
    float g = (y - kr * r - kb * b) / kg;
    return vec3(r, g, b);
}

// ---------------------------------------------------------------- helpers
float lumaOf(vec3 c, int domain) { return dot(c, domain == 1 ? LUMA2020 : LUMA709); }

// Soft skin-tone likelihood in a non-linear R'G'B' signal (0..1).
// Based on the classic YCbCr skin cluster; deliberately broad and smooth so it
// only *reduces* processing strength, never switches anything hard.
float skinMask(vec3 e) {
    float y = dot(e, LUMA709);
    float cb = (e.b - y) / 1.8556;
    float cr = (e.r - y) / 1.5748;
    // centre of skin cluster around cb=-0.07, cr=+0.10 (normalised), elongated along hue
    vec2 d = vec2(cb + 0.075, cr - 0.105);
    float rx = dot(d, vec2(0.80, 0.60)) / 0.085;
    float ry = dot(d, vec2(-0.60, 0.80)) / 0.040;
    float m = exp(-0.5 * (rx * rx + ry * ry));
    return m * smoothstep(0.08, 0.20, y) * (1.0 - smoothstep(0.92, 1.0, y));
}

// Integer hash -> [0,1). Stateless, stable per pixel (static dither pattern).
float hash12(vec2 p) {
    uvec2 q = uvec2(ivec2(p)) * uvec2(1597334673u, 3812015801u);
    uint n = (q.x ^ q.y) * 1597334673u;
    n ^= n >> 15; n *= 2246822519u; n ^= n >> 13;
    return float(n & 0x00FFFFFFu) / 16777216.0;
}
// Triangular-PDF dither in [-1, 1) LSB.
float tpdf(vec2 p) { return hash12(p) + hash12(p + vec2(17.0, 59.0)) - 1.0; }
