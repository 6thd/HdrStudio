// Colour / dynamic-range mapping to the output signal.
//   SDR -> HDR10 : BT.1886 (or 2.2) EOTF -> linear BT.709 -> BT.2020 primaries ->
//                  inverse tone mapping (reference white at uRefWhite nits, selective highlight
//                  expansion above an adaptive knee, skin/midtone protection) -> peak soft-clip
//                  that desaturates instead of clipping (no hue shift) -> PQ.
//   SDR -> SDR   : identity transfer (no double EOTF/OETF) + optional vibrance.
//   PQ  -> PQ    : identity (HDR source keeps its light levels).
//   PQ  -> SDR   : BT.2390 EETF tone mapping on max(RGB) -> BT.709 gamut -> SDR OETF.
// Output: non-linear code values in [0,1] (pre-dither); present.frag quantises them.
uniform sampler2D uIn;
uniform float uDriveRadius; // px; >0: expansion gain from an edge-aware local mean of the drive
uniform int uInDomain;     // 0 E_SDR, 1 E_PQ
uniform int uOutMode;      // 0 HDR10 (PQ/BT.2020), 1 SDR (BT.709)
uniform int uSdrGamma;     // 0 BT.1886 2.4, 1 2.2
uniform float uRefWhite;   // nits that SDR 100% maps to (BT.2408: 203)
uniform float uPeak;       // target peak nits for expanded highlights
uniform float uExpand;     // 0..1 (already scene-adapted on the CPU)
uniform float uKnee;       // relative linear SDR level where expansion starts
uniform float uVibrance;   // -0.5..1
uniform float uSkinProtect;// 0..1
uniform float uSrcPeak;    // HDR source peak nits (for PQ -> SDR)
uniform float uSdrPeakNits;// HDR nits that map to SDR 100% in PQ -> SDR
out vec4 o;

vec3 desatToGamut(vec3 lin) {               // pull negative components back to >= 0
    float y = dot(lin, LUMA2020);
    float mn = min(lin.r, min(lin.g, lin.b));
    if (mn < 0.0 && y > 0.0) lin = y + (lin - y) * (y / (y - mn));
    return max(lin, 0.0);
}
vec3 vibrance(vec3 lin, vec3 lumaK, float amt, float skin) {
    float y = dot(lin, lumaK);
    float mx = max(lin.r, max(lin.g, lin.b));
    float mn = min(lin.r, min(lin.g, lin.b));
    float sat = mx > 1e-6 ? (mx - mn) / mx : 0.0;
    float k = 1.0 + amt * (1.0 - sat) * (1.0 - 0.7 * skin);
    vec3 r = y + (lin - y) * k;
    float rmn = min(r.r, min(r.g, r.b));
    if (rmn < 0.0) r = y + (r - y) * (y / max(y - rmn, 1e-6));
    return r;
}
// BT.2390 EETF on PQ values. srcPeak/dstPeak in nits, black assumed 0.
float eetf(float nits, float srcPeak, float dstPeak) {
    float e = pqEncode1(nits) / pqEncode1(srcPeak);
    float maxL = pqEncode1(dstPeak) / pqEncode1(srcPeak);
    if (maxL >= 1.0) return nits;
    float ks = 1.5 * maxL - 0.5;
    if (e > ks) {
        float t = (e - ks) / (1.0 - ks);
        float t2 = t * t, t3 = t2 * t;
        e = (2.0 * t3 - 3.0 * t2 + 1.0) * ks + (t3 - 2.0 * t2 + t) * (1.0 - ks) + (-2.0 * t3 + 3.0 * t2) * maxL;
    }
    return pqDecode1(min(e, 1.0) * pqEncode1(srcPeak));
}

void main() {
    vec3 e = texelFetch(uIn, ivec2(gl_FragCoord.xy), 0).rgb;
    if (uInDomain == 0) {
        float skin = skinMask(clamp(e, 0.0, 1.0));
        vec3 lin = sdrToLinear(e, uSdrGamma);                       // relative, BT.709
        if (uVibrance != 0.0) lin = vibrance(lin, LUMA709, uVibrance, skin);
        if (uOutMode == 1) { o = vec4(linearToSdr(lin, uSdrGamma), 1.0); return; }
        vec3 l2020 = M709_TO_2020 * lin;
        float y = dot(l2020, LUMA2020);
        float mx = max(l2020.r, max(l2020.g, l2020.b));
        float drive = min(mix(y, mx, 0.35), 1.0);
        if (uDriveRadius > 0.0) {
            // The expansion curve is steep (t*t up to the peak), so applied per pixel it multiplies
            // local contrast in highlights about 3x: the faint sharpening rim phones and messengers
            // leave around a head against a bright wall (3 % in the source) became a 12 % glow.
            // The gain is therefore taken from a joint-bilateral mean of the drive: pixels of the
            // same surface share one gain (their contrast is kept, not multiplied), while a small
            // specular or the far side of an edge differs by more than the range sigma and keeps its own.
            ivec2 p = ivec2(gl_FragCoord.xy), hi = textureSize(uIn, 0) - 1;
            float c = pow(drive, 1.0 / 2.4), acc = drive, wsum = 1.0;
            for (int i = 0; i < 16; i++) {
                float a = 6.2831853 * (float(i) + 0.5 * float(i / 8)) / 8.0;
                vec2 d = vec2(cos(a), sin(a)) * uDriveRadius * (i < 8 ? 0.5 : 1.0);
                vec3 q = sdrToLinear(texelFetch(uIn, clamp(p + ivec2(round(d)), ivec2(0), hi), 0).rgb, uSdrGamma);
                if (uVibrance != 0.0) q = vibrance(q, LUMA709, uVibrance, 0.0);
                q = M709_TO_2020 * q;
                float dq = min(mix(dot(q, LUMA2020), max(q.r, max(q.g, q.b)), 0.35), 1.0);
                float e2 = (pow(max(dq, 0.0), 1.0 / 2.4) - c) / 0.05;
                float w = exp(-0.5 * e2 * e2);
                acc += dq * w; wsum += w;
            }
            drive = acc / wsum;
        }
        float t = clamp((drive - uKnee) / max(1.0 - uKnee, 1e-3), 0.0, 1.0);
        float extra = (uPeak - uRefWhite) * uExpand * t * t * (1.0 - uSkinProtect * skin);
        float gain = drive > 1e-5 ? (uRefWhite * drive + extra) / (uRefWhite * drive) : 1.0;
        vec3 nits = l2020 * uRefWhite * gain;
        // peak soft-clip with chroma compression (luminance and hue preserved)
        float m = max(nits.r, max(nits.g, nits.b));
        float knee = 0.85 * uPeak;
        if (m > knee) {
            float mc = knee + (uPeak - knee) * tanh((m - knee) / (uPeak - knee));
            float yn = dot(nits, LUMA2020);
            nits = yn >= mc ? vec3(mc) : yn + (nits - yn) * ((mc - yn) / (m - yn));
        }
        o = vec4(pqEncode(max(nits, 0.0)), 1.0);
        return;
    }
    // HDR source (E_PQ)
    if (uOutMode == 0) { o = vec4(clamp(e, 0.0, 1.0), 1.0); return; }
    vec3 nits = pqDecode(e);
    float m = max(nits.r, max(nits.g, nits.b));
    if (m > 1e-4) nits *= eetf(m, uSrcPeak, uSdrPeakNits) / m;
    vec3 lin = desatToGamut(M2020_TO_709 * (nits / uSdrPeakNits));
    o = vec4(linearToSdr(lin, uSdrGamma), 1.0);
}
