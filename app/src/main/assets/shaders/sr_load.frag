// AI stage, step 0: copy one tile of the low-resolution input (+ padding) into layer 0 of
// the feature array. Texels outside the image are written as 0, which reproduces exactly
// the zero padding the network sees at the image border in full-frame inference.
// Texture convention everywhere: row 0 = bottom image row (GL convention).
uniform sampler2D uLR;
uniform ivec2 uOrigin;     // LR coordinate of tile texel (0,0)
out vec4 o;
void main() {
    ivec2 q = uOrigin + ivec2(gl_FragCoord.xy);
    ivec2 sz = textureSize(uLR, 0);
    bool inside = all(greaterThanEqual(q, ivec2(0))) && all(lessThan(q, sz));
    o = inside ? vec4(clamp(texelFetch(uLR, q, 0).rgb, 0.0, 1.0), 0.0) : vec4(0.0);
}
