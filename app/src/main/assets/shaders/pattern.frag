// Self-test pattern: a 4x4 grid of flat patches with known non-linear code values (uploaded by
// the CPU), plus a thin strip along the bottom image edge holding a fine grey ramp that spans
// only uRamp.y of signal (~64 codes at 10-bit). After encode -> decode, an 8-bit stage anywhere
// in the chain collapses that ramp to ~16 distinct values (multiples of 4 in 10-bit codes);
// a real 10-bit path keeps ~55. Patch sampling regions never touch the strip.
uniform vec3 uPatch[16];
uniform vec2 uSize;
uniform vec2 uRamp;          // start, span (signal units)
out vec4 o;
void main() {
    if (gl_FragCoord.y < uSize.y / 32.0) {
        o = vec4(vec3(uRamp.x + uRamp.y * gl_FragCoord.x / uSize.x), 1.0);
        return;
    }
    ivec2 g = ivec2(floor(gl_FragCoord.xy / uSize * 4.0));
    g = clamp(g, ivec2(0), ivec2(3));
    o = vec4(uPatch[g.y * 4 + g.x], 1.0);
}
