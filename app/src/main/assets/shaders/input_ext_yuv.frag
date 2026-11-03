#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
// Android only. HDR (and 10-bit) decode path: sample raw Y'CbCr (no driver colour conversion,
// no driver tone mapping), convert once in yuvToWorking().
//#include input_yuv_common
uniform __samplerExternal2DY2YEXT uTex;
uniform mat4 uST;
in vec2 vUv;
out vec4 o;
void main() {
    vec2 uv = (uST * vec4(vUv, 0.0, 1.0)).xy;
    o = vec4(yuvToWorking(texture(uTex, uv).xyz), 1.0);
}
