// Plain 1:1 texel copy (history buffers, previews).
uniform sampler2D uIn;
out vec4 o;
void main() { o = texelFetch(uIn, ivec2(gl_FragCoord.xy), 0); }
