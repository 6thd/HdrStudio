// 8x8 block reduction. Output texel: r = max, g = weighted mean, b = covered fraction.
// uMode 0: input is a previous reduction level
// uMode 1: input is final PQ code values -> per-pixel max(R,G,B) in nits (MaxCLL/MaxFALL)
// uMode 2: input is the working image -> Immerkaer noise operator |N * Y| (noise estimation)
uniform sampler2D uIn;
uniform int uMode;
uniform int uDomain;
out vec4 o;
float lumaAt(ivec2 q, ivec2 mx) { return lumaOf(texelFetch(uIn, clamp(q, ivec2(0), mx), 0).rgb, uDomain); }
void main() {
    ivec2 sz = textureSize(uIn, 0);
    ivec2 mx = sz - 1;
    ivec2 b = ivec2(gl_FragCoord.xy) * 8;
    float vmax = 0.0, sum = 0.0, wsum = 0.0;
    for (int y = 0; y < 8; y++) {
        for (int x = 0; x < 8; x++) {
            ivec2 q = b + ivec2(x, y);
            if (q.x >= sz.x || q.y >= sz.y) continue;
            if (uMode == 0) {
                vec4 s = texelFetch(uIn, q, 0);
                vmax = max(vmax, s.r); sum += s.g * s.b; wsum += s.b;
            } else if (uMode == 1) {
                vec3 n = pqDecode(texelFetch(uIn, q, 0).rgb);
                float m = max(n.r, max(n.g, n.b));
                vmax = max(vmax, m); sum += m; wsum += 1.0;
            } else {
                if (q.x < 1 || q.y < 1 || q.x >= mx.x || q.y >= mx.y) continue;
                float r = 4.0 * lumaAt(q, mx)
                        - 2.0 * (lumaAt(q + ivec2(1, 0), mx) + lumaAt(q - ivec2(1, 0), mx)
                               + lumaAt(q + ivec2(0, 1), mx) + lumaAt(q - ivec2(0, 1), mx))
                        + lumaAt(q + ivec2(1, 1), mx) + lumaAt(q + ivec2(-1, 1), mx)
                        + lumaAt(q + ivec2(1, -1), mx) + lumaAt(q + ivec2(-1, -1), mx);
                float a = abs(r);
                vmax = max(vmax, a); sum += a; wsum += 1.0;
            }
        }
    }
    o = vec4(vmax, wsum > 0.0 ? sum / wsum : 0.0, wsum / 64.0, 1.0);
}
