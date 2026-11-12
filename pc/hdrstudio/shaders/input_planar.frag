// Desktop input: planar Y'CbCr exactly as the decoder produced it (no swscale colour
// conversion upstream). Chroma is interpolated here at its real position: MPEG-2/H.264/HEVC
// default siting, i.e. co-sited with luma horizontally and centred between rows vertically.
// The Y'CbCr -> working-domain conversion is the shared code of the phone app.
//#include input_yuv_common
uniform sampler2D uY;
uniform sampler2D uU;
uniform sampler2D uV;
uniform ivec2 uSub;         // chroma subsampling: (2,2) 4:2:0, (2,1) 4:2:2, (1,1) 4:4:4
out vec4 o;
void main() {
    float y = texelFetch(uY, ivec2(gl_FragCoord.xy), 0).r;
    vec2 pl = gl_FragCoord.xy - 0.5;                       // luma sample index
    vec2 c = vec2(uSub.x == 2 ? pl.x * 0.5 + 0.5 : pl.x + 0.5,
                  uSub.y == 2 ? (pl.y - 0.5) * 0.5 + 0.5 : pl.y + 0.5) / vec2(textureSize(uU, 0));
    o = vec4(yuvToWorking(vec3(y, texture(uU, c).r, texture(uV, c).r)), 1.0);
}
