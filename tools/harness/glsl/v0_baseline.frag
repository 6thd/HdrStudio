// Test-only port of the v0 engine's fragment shader (baseline/HdrStudio_v0, HdrConverter.kt), unchanged
// maths, so v0 and v2 can be compared on the same inputs. Defaults = v0 UI defaults
// (expansion 80%, saturation 40% -> 1.2, sharpness 40% -> 0.6, contrast 30% -> 0.15).
uniform sampler2D uTex;
uniform float uExpand;
uniform float uSat;
uniform float uSharp;
uniform float uContrast;
out vec4 o;
const vec3 L709v0 = vec3(0.2126, 0.7152, 0.0722);
const vec3 L2020v0 = vec3(0.2627, 0.6780, 0.0593);
vec3 pqv0(vec3 y) {
    vec3 ym = pow(y, vec3(0.1593017578125));
    return pow((0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym), vec3(78.84375));
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy); ivec2 m = textureSize(uTex, 0) - 1;
    vec3 c = texelFetch(uTex, p, 0).rgb;
    vec3 n = texelFetch(uTex, clamp(p + ivec2(0, 1), ivec2(0), m), 0).rgb;
    vec3 s = texelFetch(uTex, clamp(p - ivec2(0, 1), ivec2(0), m), 0).rgb;
    vec3 e = texelFetch(uTex, clamp(p + ivec2(1, 0), ivec2(0), m), 0).rgb;
    vec3 w = texelFetch(uTex, clamp(p - ivec2(1, 0), ivec2(0), m), 0).rgb;
    vec3 blur = (n + s + e + w) * 0.25;
    c += clamp(c - blur, -0.12, 0.12) * uSharp;
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), uContrast);
    float luma = dot(c, L709v0);
    float chroma = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float k = 1.0 + (uSat - 1.0) * (1.0 - 0.6 * chroma);
    c = clamp(mix(vec3(luma), c, k), 0.0, 1.0);
    vec3 lin = pow(c, vec3(2.2));
    mat3 M = mat3(0.6274, 0.0691, 0.0164, 0.3293, 0.9195, 0.0880, 0.0433, 0.0114, 0.8956);
    lin = M * lin;
    float Y = clamp(dot(lin, L2020v0), 0.0, 1.0);
    float gain = 1.0 + uExpand * (1000.0 / 203.0 - 1.0) * Y * Y * Y;
    vec3 nits = lin * 203.0 * gain;
    o = vec4(pqv0(clamp(nits / 10000.0, 0.0, 1.0)), 1.0);
}
