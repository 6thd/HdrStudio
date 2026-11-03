// 3x3 median of the vector field (per component): removes isolated wrong matches at 1/4 res
// and noisy sub-pixel jitter in the final 1/2-res field (which would blur static areas).
// The matching cost (z) is carried through from the centre texel.
uniform sampler2D uIn;
out vec4 o;
float med9(float a[9]) {
    for (int i = 0; i < 5; i++)
        for (int j = i + 1; j < 9; j++)
            if (a[j] < a[i]) { float t = a[i]; a[i] = a[j]; a[j] = t; }
    return a[4];
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 mx = textureSize(uIn, 0) - 1;
    float xs[9]; float ys[9];
    for (int i = 0; i < 9; i++) {
        vec4 s = texelFetch(uIn, clamp(p + ivec2(i % 3 - 1, i / 3 - 1), ivec2(0), mx), 0);
        xs[i] = s.x; ys[i] = s.y;
    }
    vec4 c = texelFetch(uIn, p, 0);
    o = vec4(med9(xs), med9(ys), c.z, 1.0);
}
