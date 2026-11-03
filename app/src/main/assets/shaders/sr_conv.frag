// AI stage: one 3x3 convolution layer of SRVGGNetCompact (Real-ESRGAN realesr-general-x4v3)
// + PReLU, computing 4 output groups (16 channels) per pass via 4 render targets.
// Features live in a 2D texture array, 4 channels per layer (RGBA16F storage, fp32 maths).
// Weights texture (RGBA16F, values are exactly the fp16 weights of the model):
//   row  = uRow0 + output group (0..3 within this pass)
//   col  = (inGroup*9 + tap)*4 + j  -> texel j = weights from input channel j of the group
//                                     to the 4 output channels of the row's group.
// Zero padding: neighbours outside uValid (image area within this tile) read as 0, and
// texels outside uValid are written as 0 -> identical to full-frame inference.
uniform highp sampler2DArray uIn;
uniform highp sampler2D uW;
uniform int uGin;          // number of input groups (1 for the RGB layer, 16 otherwise)
uniform int uRow0;
uniform vec4 uBias[4];
uniform vec4 uSlope[4];
uniform ivec4 uValid;      // x0, y0, x1, y1 (exclusive) in tile texel coordinates
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
layout(location = 2) out vec4 o2;
layout(location = 3) out vec4 o3;

bool inValid(ivec2 q) { return q.x >= uValid.x && q.y >= uValid.y && q.x < uValid.z && q.y < uValid.w; }

vec4 prelu(vec4 a, vec4 s) { return max(a, 0.0) + s * min(a, 0.0); }

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (!inValid(p)) { o0 = vec4(0.0); o1 = vec4(0.0); o2 = vec4(0.0); o3 = vec4(0.0); return; }
    vec4 a0 = uBias[0], a1 = uBias[1], a2 = uBias[2], a3 = uBias[3];
    for (int ig = 0; ig < 16; ig++) {
        if (ig >= uGin) break;
        for (int t = 0; t < 9; t++) {
            // PyTorch tap (ky, kx) reads input row y+ky-1 counted top-down; our rows are bottom-up.
            ivec2 q = p + ivec2(t % 3 - 1, 1 - t / 3);
            if (!inValid(q)) continue;
            vec4 x = texelFetch(uIn, ivec3(q, ig), 0);
            int col = (ig * 9 + t) * 4;
            a0 += mat4(texelFetch(uW, ivec2(col, uRow0), 0), texelFetch(uW, ivec2(col + 1, uRow0), 0),
                       texelFetch(uW, ivec2(col + 2, uRow0), 0), texelFetch(uW, ivec2(col + 3, uRow0), 0)) * x;
            a1 += mat4(texelFetch(uW, ivec2(col, uRow0 + 1), 0), texelFetch(uW, ivec2(col + 1, uRow0 + 1), 0),
                       texelFetch(uW, ivec2(col + 2, uRow0 + 1), 0), texelFetch(uW, ivec2(col + 3, uRow0 + 1), 0)) * x;
            a2 += mat4(texelFetch(uW, ivec2(col, uRow0 + 2), 0), texelFetch(uW, ivec2(col + 1, uRow0 + 2), 0),
                       texelFetch(uW, ivec2(col + 2, uRow0 + 2), 0), texelFetch(uW, ivec2(col + 3, uRow0 + 2), 0)) * x;
            a3 += mat4(texelFetch(uW, ivec2(col, uRow0 + 3), 0), texelFetch(uW, ivec2(col + 1, uRow0 + 3), 0),
                       texelFetch(uW, ivec2(col + 2, uRow0 + 3), 0), texelFetch(uW, ivec2(col + 3, uRow0 + 3), 0)) * x;
        }
    }
    o0 = prelu(a0, uSlope[0]); o1 = prelu(a1, uSlope[1]);
    o2 = prelu(a2, uSlope[2]); o3 = prelu(a3, uSlope[3]);
}
