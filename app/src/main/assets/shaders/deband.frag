// Gradient-preserving debanding (f3kdb/mpv style), run before any HDR expansion so that
// 8-bit steps in skies are not stretched into visible bands. Works on smooth areas only:
// a pixel is replaced by the average of 4 random-offset samples only when every sample is
// within uThreshold of it, so edges and texture are untouched. Output stays in FP16, the
// final quantisation happens once at the very end (present.frag) with TPDF dither.
uniform sampler2D uIn;
uniform float uThreshold;   // in signal units (e.g. 1.5/255 for 8-bit SDR)
uniform float uRadius;      // pixels
uniform int uIterations;
uniform vec2 uOrigin;       // frame position of texel (0,0): a crop gets the same random pattern
out vec4 o;
void main() {
    vec2 size = vec2(textureSize(uIn, 0));
    vec2 p = gl_FragCoord.xy;
    vec2 g = p + uOrigin;
    vec3 c = texelFetch(uIn, ivec2(p), 0).rgb;
    float thr = uThreshold;
    for (int i = 1; i <= uIterations; i++) {
        float fi = float(i);
        float ang = hash12(g + vec2(fi * 31.0, 7.0)) * 6.2831853;
        float rad = uRadius * fi / float(uIterations) * (0.5 + 0.5 * hash12(g + vec2(3.0, fi * 13.0)));
        vec2 d = vec2(cos(ang), sin(ang)) * rad;
        vec2 e = vec2(-d.y, d.x);
        vec3 s1 = texture(uIn, (p + d) / size).rgb;
        vec3 s2 = texture(uIn, (p - d) / size).rgb;
        vec3 s3 = texture(uIn, (p + e) / size).rgb;
        vec3 s4 = texture(uIn, (p - e) / size).rgb;
        vec3 avg = 0.25 * (s1 + s2 + s3 + s4);
        vec3 diff = max(max(abs(s1 - c), abs(s2 - c)), max(abs(s3 - c), abs(s4 - c)));
        float m = max(max(diff.r, diff.g), diff.b);
        if (m < thr * 2.0 && max(max(abs(avg - c).r, abs(avg - c).g), abs(avg - c).b) < thr) {
            c = avg;
        } else {
            break;
        }
        thr *= 0.8;
    }
    o = vec4(c, 1.0);
}
