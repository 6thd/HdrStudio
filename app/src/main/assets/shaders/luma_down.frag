// Builds one level of the luma pyramid used for motion estimation and scene analysis.
// uFromRgb: level 1 is built from the working RGB image (luma of the non-linear signal),
// further levels from the previous luma level. 2x2 box average (edge-clamped).
uniform sampler2D uIn;
uniform bool uFromRgb;
uniform int uDomain;
out vec4 o;
float L(ivec2 q) {
    ivec2 mx = textureSize(uIn, 0) - 1;
    vec4 s = texelFetch(uIn, clamp(q, ivec2(0), mx), 0);
    return uFromRgb ? lumaOf(s.rgb, uDomain) : s.r;
}
void main() {
    ivec2 q = ivec2(gl_FragCoord.xy) * 2;
    float v = 0.25 * (L(q) + L(q + ivec2(1, 0)) + L(q + ivec2(0, 1)) + L(q + ivec2(1, 1)));
    o = vec4(v, 0.0, 0.0, 1.0);
}
