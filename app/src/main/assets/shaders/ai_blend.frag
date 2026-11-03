// Blend the AI result with the conventionally processed image. Both inputs are already at
// the output resolution and in the same signal domain (E_SDR), so the mix is meaningful.
// Skin protection lowers the AI share on skin tones (generative detail on faces is the most
// visible failure of SR models: waxy skin or invented pores).
uniform sampler2D uBase;
uniform sampler2D uAi;
uniform float uAmount;        // 0..1
uniform float uSkinProtect;   // 0..1
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 b = texelFetch(uBase, p, 0).rgb;
    vec3 a = texelFetch(uAi, p, 0).rgb;
    float amt = uAmount * (1.0 - uSkinProtect * 0.6 * skinMask(b));
    o = vec4(mix(b, a, amt), 1.0);
}
