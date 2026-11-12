// Motion-compensated recursive temporal filter (denoise + stabilisation of fine/AI detail).
// prevOut is warped with the estimated motion, then:
//  1. clamped to the current 3x3 neighbourhood: min/max box intersected with mean +- 1.25 std
//     (+- k*sigma noise tolerance) -> no ghost trails behind moving objects, no smearing of
//     newly revealed (dis-occluded) areas;
//  2. weighted by a confidence that drops when the residual exceeds the noise level, when the
//     clamp had to move the sample a lot, or when the block-matching cost of the motion vector
//     is high (occlusion / mis-alignment / flash).
// A real flash or lighting change makes the warped history fall outside the current range,
// so it is clamped away and the new light is kept rather than averaged out.
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uMv;        // vectors (xy) in the vector texture's pixel units
uniform float uMvScale;       // full-res pixels per vector-texture pixel
uniform float uSigma;         // noise sigma (signal units)
uniform float uStrength;      // 0..0.9 max history weight
uniform float uClampK;        // neighbourhood clamp tolerance in sigmas
uniform int uDomain;
out vec4 o;

void main() {
    vec2 size = vec2(textureSize(uCur, 0));
    ivec2 ip = ivec2(gl_FragCoord.xy);
    ivec2 mx = textureSize(uCur, 0) - 1;
    vec3 c = texelFetch(uCur, ip, 0).rgb;
    vec3 mn = c, mxv = c, sum = vec3(0.0), sum2 = vec3(0.0);
    for (int i = 0; i < 9; i++) {
        vec3 s = texelFetch(uCur, clamp(ip + ivec2(i % 3 - 1, i / 3 - 1), ivec2(0), mx), 0).rgb;
        mn = min(mn, s); mxv = max(mxv, s); sum += s; sum2 += s * s;
    }
    vec3 mean = sum / 9.0;
    vec3 sd = sqrt(max(sum2 / 9.0 - mean * mean, 0.0));
    vec4 mvs = texture(uMv, gl_FragCoord.xy / size);
    vec2 v = mvs.xy * uMvScale;
    vec3 h = texture(uPrev, (gl_FragCoord.xy + v) / size).rgb;
    float sig = max(uSigma, 0.002);
    float tau = uClampK * sig;
    vec3 lo = max(mn, mean - 1.25 * sd) - tau, hi = min(mxv, mean + 1.25 * sd) + tau;
    vec3 hc = clamp(h, lo, hi);
    vec3 kc = uDomain == 1 ? LUMA2020 : LUMA709;
    float moved = length(h - hc);
    float resid = abs(dot(hc - c, kc));
    // matching cost is measured on the half-resolution luma (noise ~ sigma/2 there)
    float wCost = 1.0 - smoothstep(1.2 * sig, 2.5 * sig, mvs.z);
    float w = exp(-pow(resid / (2.5 * sig), 2.0)) * exp(-pow(moved / (1.5 * sig), 2.0)) * wCost;
    o = vec4(mix(c, hc, uStrength * w), 1.0);
}
