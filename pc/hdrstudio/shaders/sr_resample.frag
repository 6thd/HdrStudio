// AI stage: resample one AI-upscaled tile (x4 of the model input) to the output resolution.
// 2-D Catmull-Rom, widened by 1/scale when downscaling (proper low-pass), with
// anti-ringing against the nearest 2x2 source pixels. The tile carries a margin of real
// AI pixels around its core, so the filter footprint never crosses into padding -> no seams.
uniform sampler2D uSR;
uniform vec2 uSrOrigin;     // global SR coordinate of uSR texel (0,0)
uniform vec2 uScale;        // output pixels per SR pixel (x, y)
uniform ivec2 uSrValid;     // valid region of uSR (the texture may be larger than this tile)
out vec4 o;

float cr(float x) {
    x = abs(x);
    if (x < 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
    if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
    return 0.0;
}

void main() {
    ivec2 sz = uSrValid;
    vec2 s = (gl_FragCoord.xy) / uScale - 0.5 - uSrOrigin;   // local SR coordinate
    vec2 fw = max(vec2(1.0), 1.0 / uScale);
    vec2 sup = 2.0 * fw;
    ivec2 i0 = ivec2(floor(s - sup)) + 1;
    ivec2 i1 = ivec2(floor(s + sup));
    vec3 acc = vec3(0.0); float ws = 0.0;
    for (int y = i0.y; y <= i1.y; y++) {
        float wy = cr((float(y) - s.y) / fw.y);
        for (int x = i0.x; x <= i1.x; x++) {
            float w = wy * cr((float(x) - s.x) / fw.x);
            acc += w * texelFetch(uSR, clamp(ivec2(x, y), ivec2(0), sz - 1), 0).rgb;
            ws += w;
        }
    }
    vec3 r = acc / ws;
    ivec2 n = ivec2(floor(s));
    vec3 a = texelFetch(uSR, clamp(n, ivec2(0), sz - 1), 0).rgb;
    vec3 b = texelFetch(uSR, clamp(n + ivec2(1, 0), ivec2(0), sz - 1), 0).rgb;
    vec3 c = texelFetch(uSR, clamp(n + ivec2(0, 1), ivec2(0), sz - 1), 0).rgb;
    vec3 d = texelFetch(uSR, clamp(n + ivec2(1, 1), ivec2(0), sz - 1), 0).rgb;
    vec3 lo = min(min(a, b), min(c, d)), hi = max(max(a, b), max(c, d));
    // when downscaling the box of nearest taps is too tight; only anti-ring for >= 0.5 scale
    float ar = min(uScale.x, uScale.y) >= 0.99 ? 1.0 : 0.0;
    o = vec4(mix(r, clamp(r, lo, hi), ar), 1.0);
}
