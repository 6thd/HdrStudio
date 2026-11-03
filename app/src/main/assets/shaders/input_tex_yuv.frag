// Desktop-harness twin of input_ext_yuv.frag: Y'CbCr comes from a regular texture
// (planes already upsampled to 4:4:4 by the harness). Same conversion code.
//#include input_yuv_common
uniform sampler2D uTex;
in vec2 vUv;
out vec4 o;
void main() {
    o = vec4(yuvToWorking(texelFetch(uTex, ivec2(gl_FragCoord.xy), 0).xyz), 1.0);
}
