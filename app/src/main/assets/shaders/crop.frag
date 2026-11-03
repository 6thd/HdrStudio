// Copy a window of uIn that starts at uOffset (crop preview).
uniform sampler2D uIn;
uniform ivec2 uOffset;
out vec4 o;
void main() { o = texelFetch(uIn, ivec2(gl_FragCoord.xy) + uOffset, 0); }
