#extension GL_OES_EGL_image_external_essl3 : require
// Android only. SDR decode path: the GPU driver performs Y'CbCr->R'G'B' once, using the
// buffer dataspace the decoder set from the bitstream VUI. We do NOT convert again here.
// Output: E_SDR (BT.709 primaries, non-linear, full range), or primaries-converted if the
// source declares BT.2020 SDR (rare).
uniform samplerExternalOES uTex;
uniform mat4 uST;              // SurfaceTexture transform (crop + orientation of the buffer)
uniform bool uSrc2020;         // SDR signal with BT.2020 primaries
in vec2 vUv;
out vec4 o;
void main() {
    vec2 uv = (uST * vec4(vUv, 0.0, 1.0)).xy;
    vec3 e = clamp(texture(uTex, uv).rgb, 0.0, 1.0);
    if (uSrc2020) e = linearToSdr(max(M2020_TO_709 * sdrToLinear(e, 0), 0.0), 0);
    o = vec4(e, 1.0);
}
