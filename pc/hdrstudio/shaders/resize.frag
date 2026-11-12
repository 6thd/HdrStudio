// Separable Lanczos resize (one direction per pass) with anti-ringing.
// Downscaling widens the kernel by 1/scale (proper low-pass, no aliasing).
// Anti-ringing: the result is pulled toward the range of the two nearest source taps,
// which removes the dark/bright halos Lanczos produces next to hard edges.
uniform sampler2D uIn;
uniform int uDir;           // 0 horizontal, 1 vertical
uniform float uScale;       // output size / input size along uDir
uniform float uLobes;       // 2.0 or 3.0
uniform float uAntiring;    // 0..1
out vec4 o;

float sinc(float x) { x *= 3.14159265; return abs(x) < 1e-4 ? 1.0 : sin(x) / x; }

void main() {
    ivec2 isz = textureSize(uIn, 0);
    ivec2 op = ivec2(gl_FragCoord.xy);
    float fw = max(1.0, 1.0 / uScale);
    float support = uLobes * fw;
    float x = (float(uDir == 0 ? op.x : op.y) + 0.5) / uScale - 0.5;
    int i0 = int(floor(x - support)) + 1;
    int i1 = int(floor(x + support));
    int n = uDir == 0 ? isz.x : isz.y;
    vec3 acc = vec3(0.0); float wsum = 0.0;
    int near0 = int(floor(x));
    vec3 lo = vec3(1e9), hi = vec3(-1e9);
    for (int i = i0; i <= i1; i++) {
        float t = (float(i) - x) / fw;
        float w = sinc(t) * sinc(t / uLobes);
        int ic = clamp(i, 0, n - 1);
        ivec2 q = uDir == 0 ? ivec2(ic, op.y) : ivec2(op.x, ic);
        vec3 s = texelFetch(uIn, q, 0).rgb;
        acc += w * s; wsum += w;
        if (i == near0 || i == near0 + 1) { lo = min(lo, s); hi = max(hi, s); }
    }
    vec3 r = acc / wsum;
    o = vec4(mix(r, clamp(r, lo, hi), uAntiring), 1.0);
}
