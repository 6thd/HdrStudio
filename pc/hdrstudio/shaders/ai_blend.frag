// Blend the AI result with the conventionally processed image. Both inputs are already at
// the output resolution and in the same signal domain (E_SDR), so the mix is meaningful.
// Skin protection lowers the AI share on skin tones (generative detail on faces is the most
// visible failure of SR models: waxy skin or invented pores).
//
// Texture return: SR models trained on degraded video treat fine low-contrast texture (wood
// grain, fabric, skin) as noise and flatten it, which reads as a plastic image. The luma
// detail band the model removed (detail of the base minus detail of the AI image) is added
// back, soft-limited in amplitude so that only texture-level differences return: the large
// differences are the ringing, blocking and jagged edges the model was right to remove.
uniform sampler2D uBase;
uniform sampler2D uAi;
uniform float uAmount;        // 0..1
uniform float uSkinProtect;   // 0..1
uniform float uTexture;       // 0..1.5 share of the removed texture to return
uniform float uTexRadius;     // px: tap spacing of the detail band (about one source pixel)
uniform float uTexLimit;      // amplitude where the returned difference is halved
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy), hi = textureSize(uBase, 0) - 1;
    vec3 b = texelFetch(uBase, p, 0).rgb;
    vec3 a = texelFetch(uAi, p, 0).rgb;
    float amt = uAmount * (1.0 - uSkinProtect * 0.6 * skinMask(b));
    vec3 m = mix(b, a, amt);
    if (uTexture > 0.0) {
        float mb = 0.0, ma = 0.0, ws = 0.0;
        for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
            float w = exp(-0.5 * float(i * i + j * j));
            ivec2 q = clamp(p + ivec2(round(vec2(i, j) * uTexRadius)), ivec2(0), hi);
            mb += w * dot(texelFetch(uBase, q, 0).rgb, LUMA709);
            ma += w * dot(texelFetch(uAi, q, 0).rgb, LUMA709);
            ws += w;
        }
        float r = (dot(b, LUMA709) - mb / ws) - (dot(a, LUMA709) - ma / ws);
        float x = r / uTexLimit;
        r /= 1.0 + x * x;
        m += uTexture * amt * r;
    }
    o = vec4(m, 1.0);
}
