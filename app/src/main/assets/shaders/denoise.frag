// Spatial noise / compression-artefact reduction (luma + chroma bilateral), working in the
// non-linear signal domain (E_SDR or E_PQ), where code-value differences are roughly
// perceptual. Range weights scale with the measured noise sigma, so strong edges, text and
// line art are left alone while flat-area noise, ringing and mosquito noise are smoothed.
// uGrainKeep re-injects part of the removed fine detail to avoid the "plastic" look.
uniform sampler2D uIn;
uniform float uSigma;         // estimated noise sigma (signal units)
uniform float uLumaAmount;    // 0..1
uniform float uChromaAmount;  // 0..1
uniform float uGrainKeep;     // 0..1
uniform int uDomain;          // 0 E_SDR (BT.709 luma), 1 E_PQ (BT.2020 luma)
out vec4 o;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 mx = textureSize(uIn, 0) - 1;
    vec3 kc = uDomain == 1 ? LUMA2020 : LUMA709;
    vec3 c = texelFetch(uIn, p, 0).rgb;
    float y0 = dot(c, kc);
    vec2 cc0 = vec2(c.b - y0, c.r - y0);

    // Range sigma grows with the user amount; never below a small floor so a clean
    // source (sigma ~ 0) is left untouched.
    float sr = max(uSigma, 0.0015) * (1.0 + 2.5 * uLumaAmount);
    float src = max(uSigma, 0.0015) * (1.5 + 4.0 * uChromaAmount);

    float wy = 0.0, sy = 0.0;
    float wc = 0.0; vec2 sc = vec2(0.0);
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            vec3 s = texelFetch(uIn, clamp(p + ivec2(dx, dy), ivec2(0), mx), 0).rgb;
            float ys = dot(s, kc);
            float ds2 = float(dx * dx + dy * dy);
            float dl = ys - y0;
            float w = exp(-ds2 / 4.5 - dl * dl / (2.0 * sr * sr));
            sy += w * ys; wy += w;
            // chroma: wider spatial support (stride 2 -> 9x9 footprint)
            vec3 s2 = texelFetch(uIn, clamp(p + 2 * ivec2(dx, dy), ivec2(0), mx), 0).rgb;
            float y2 = dot(s2, kc);
            vec2 c2 = vec2(s2.b - y2, s2.r - y2);
            vec2 dcv = c2 - cc0;
            float dl2 = y2 - y0;
            float w2 = exp(-ds2 / 8.0 - (dot(dcv, dcv) + 0.5 * dl2 * dl2) / (2.0 * src * src));
            sc += w2 * c2; wc += w2;
        }
    }
    float yf = sy / wy;
    vec2 cf = sc / wc;
    float y1 = mix(y0, yf, uLumaAmount);
    y1 = y1 + uGrainKeep * (y0 - y1) * uLumaAmount;   // keep part of the fine texture
    vec2 c1 = mix(cc0, cf, uChromaAmount);
    // rebuild R'G'B' from (Y', B'-Y', R'-Y')
    float r = c1.y + y1;
    float b = c1.x + y1;
    float g = (y1 - kc.r * r - kc.b * b) / kc.g;
    o = vec4(r, g, b, 1.0);
}
