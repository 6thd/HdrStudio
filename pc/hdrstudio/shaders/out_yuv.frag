// Final quantisation, done once: R'G'B' code values [0,1] -> limited-range Y'CbCr integer
// codes for the encoder (which then performs no colour conversion at all).
// uPlane 0: luma at full resolution. uPlane 1: Cb, Cr at 4:2:0, filtered to the standard
// chroma position (co-sited horizontally: 1-2-1, centred vertically: 1-1).
// TPDF dither of +-1 LSB before rounding hides banding in gradients.
uniform sampler2D uIn;
uniform int uPlane;
uniform int uMatrix;        // 0 BT.709, 1 BT.2020 NCL
uniform float uBitDepth;    // 8.0 or 10.0
uniform bool uDither;
out uvec4 o;

uint quant(float code, vec2 seed) {
    if (uDither) code += tpdf(seed);
    return uint(clamp(floor(code + 0.5), 0.0, exp2(uBitDepth) - 1.0));
}
void main() {
    vec3 kc = uMatrix == 1 ? LUMA2020 : LUMA709;
    float k = exp2(uBitDepth - 8.0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 mx = textureSize(uIn, 0) - 1;
    if (uPlane == 0) {
        float y = dot(clamp(texelFetch(uIn, p, 0).rgb, 0.0, 1.0), kc);
        o = uvec4(quant(16.0 * k + 219.0 * k * y, gl_FragCoord.xy), 0u, 0u, 1u);
        return;
    }
    vec3 acc = vec3(0.0);
    for (int dy = 0; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            acc += (dx == 0 ? 0.25 : 0.125) * clamp(texelFetch(uIn, clamp(2 * p + ivec2(dx, dy), ivec2(0), mx), 0).rgb, 0.0, 1.0);
    float y = dot(acc, kc);
    float cb = (acc.b - y) / (2.0 * (1.0 - kc.b));
    float cr = (acc.r - y) / (2.0 * (1.0 - kc.r));
    o = uvec4(quant(128.0 * k + 224.0 * k * cb, gl_FragCoord.xy + 37.0),
              quant(128.0 * k + 224.0 * k * cr, gl_FragCoord.xy + 71.0), 0u, 1u);
}
