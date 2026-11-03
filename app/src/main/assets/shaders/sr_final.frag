// AI stage: last convolution (64 -> 48) fused with PixelShuffle(4) and the nearest-x4 skip
// connection of SRVGGNetCompact. Each fragment is one high-resolution pixel; it evaluates
// only the 3 output channels that land on it. Weight row uRow0 + k holds, for sub-pixel
// k = dy*4 + dx (dy counted top-down as in PyTorch), the channels (k, 16+k, 32+k, unused).
uniform highp sampler2DArray uIn;
uniform highp sampler2D uW;
uniform int uRow0;
uniform vec4 uBias[16];
uniform highp sampler2D uLR;
uniform ivec2 uOrigin;      // LR coordinate of feature texel (0,0)
uniform ivec2 uFeatStart;   // feature texel of the first LR pixel of the output region
uniform ivec2 uHrOrigin;    // window coordinate of the first HR pixel of the output region
out vec4 o;
void main() {
    ivec2 h = ivec2(gl_FragCoord.xy) - uHrOrigin;
    ivec2 f = uFeatStart + h / 4;
    int dx = h.x % 4;
    int dy = 3 - (h.y % 4);           // bottom-up rows -> top-down sub-pixel row
    int k = dy * 4 + dx;
    int row = uRow0 + k;
    ivec2 fsz = textureSize(uIn, 0).xy;
    vec4 a = uBias[k];
    for (int ig = 0; ig < 16; ig++) {
        for (int t = 0; t < 9; t++) {
            ivec2 q = f + ivec2(t % 3 - 1, 1 - t / 3);
            if (q.x < 0 || q.y < 0 || q.x >= fsz.x || q.y >= fsz.y) continue;
            vec4 x = texelFetch(uIn, ivec3(q, ig), 0);
            int col = (ig * 9 + t) * 4;
            a += mat4(texelFetch(uW, ivec2(col, row), 0), texelFetch(uW, ivec2(col + 1, row), 0),
                      texelFetch(uW, ivec2(col + 2, row), 0), texelFetch(uW, ivec2(col + 3, row), 0)) * x;
        }
    }
    vec3 base = texelFetch(uLR, clamp(uOrigin + f, ivec2(0), textureSize(uLR, 0) - 1), 0).rgb;
    o = vec4(a.rgb + clamp(base, 0.0, 1.0), 1.0);
}
