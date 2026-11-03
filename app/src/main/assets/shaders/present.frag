// Final quantisation to the encoder surface (10-bit RGBA1010102 for HDR, 8-bit for SDR).
// Optional encoder colour compensation, calibrated by the on-device self test: if the
// encoder's internal RGB->Y'CbCr conversion uses a different matrix/range than the one the
// file is tagged with, we pre-distort RGB so the resulting Y'CbCr is correct.
// TPDF dither of +-1 LSB hides banding without visible noise (static pattern -> compresses well).
uniform sampler2D uIn;
uniform mat3 uComp;
uniform vec3 uCompOffset;
uniform float uLsb;          // 1/1023 or 1/255
uniform bool uDither;
uniform bool uFlipY;         // true only when presenting to a top-down surface (not used for encoders)
out vec4 o;
void main() {
    ivec2 sz = textureSize(uIn, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (uFlipY) p.y = sz.y - 1 - p.y;
    vec3 c = texelFetch(uIn, p, 0).rgb;
    c = uComp * c + uCompOffset;
    if (uDither) c += vec3(tpdf(gl_FragCoord.xy), tpdf(gl_FragCoord.xy + 101.0), tpdf(gl_FragCoord.xy + 211.0)) * uLsb;
    o = vec4(clamp(c, 0.0, 1.0), 1.0);
}
