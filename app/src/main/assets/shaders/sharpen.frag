// Final adaptive sharpening on luma only (no colour fringes), in the non-linear domain.
// Contrast-adaptive (AMD CAS-like): sharpening weight falls as local contrast rises, and the
// result is clamped to the local min/max -> no overshoot halos around text or hard edges.
// Gated by the noise level (flat noisy areas are not sharpened) and reduced on skin.
uniform sampler2D uIn;
uniform float uAmount;        // 0..1
uniform float uSigma;         // noise sigma
uniform float uSkinProtect;   // 0..1
uniform int uDomain;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 mx = textureSize(uIn, 0) - 1;
    vec3 kc = uDomain == 1 ? LUMA2020 : LUMA709;
    vec3 c = texelFetch(uIn, p, 0).rgb;
    float y = dot(c, kc);
    float n = dot(texelFetch(uIn, clamp(p + ivec2(0, 1), ivec2(0), mx), 0).rgb, kc);
    float s = dot(texelFetch(uIn, clamp(p + ivec2(0, -1), ivec2(0), mx), 0).rgb, kc);
    float e = dot(texelFetch(uIn, clamp(p + ivec2(1, 0), ivec2(0), mx), 0).rgb, kc);
    float w = dot(texelFetch(uIn, clamp(p + ivec2(-1, 0), ivec2(0), mx), 0).rgb, kc);
    float ne = dot(texelFetch(uIn, clamp(p + ivec2(1, 1), ivec2(0), mx), 0).rgb, kc);
    float nw = dot(texelFetch(uIn, clamp(p + ivec2(-1, 1), ivec2(0), mx), 0).rgb, kc);
    float se = dot(texelFetch(uIn, clamp(p + ivec2(1, -1), ivec2(0), mx), 0).rgb, kc);
    float sw = dot(texelFetch(uIn, clamp(p + ivec2(-1, -1), ivec2(0), mx), 0).rgb, kc);
    float mn = min(y, min(min(n, s), min(e, w)));
    float mxl = max(y, max(max(n, s), max(e, w)));
    float mn2 = min(mn, min(min(ne, nw), min(se, sw)));
    float mx2 = max(mxl, max(max(ne, nw), max(se, sw)));
    mn += mn2; mxl += mx2;                        // CAS-style soft min/max (x2 scale)
    float amp = sqrt(clamp(min(mn, 2.0 - mxl) / max(mxl, 1e-4), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, clamp(uAmount, 0.0, 1.0));
    float wgt = amp * peak;
    float ys = (y + wgt * (n + s + e + w)) / (1.0 + 4.0 * wgt);
    ys = clamp(ys, mn2, mx2);                     // hard no-overshoot
    float contrast = mx2 - mn2;
    float gate = smoothstep(2.0 * uSigma, 5.0 * uSigma + 0.004, contrast);
    float k = uAmount * gate * (1.0 - uSkinProtect * 0.7 * skinMask(c));
    o = vec4(c + (ys - y) * k, 1.0);
}
