// Hierarchical block-matching motion estimation (one pyramid level).
// Convention: cur(p) ~= prev(p + v). Vectors are stored in this level's pixel units.
// Cost = zero-mean SAD over a (2h+1)^2 patch (robust to global brightness changes such as
// flashes / fades) + lambda * |v - predictor| (smoothness).
// Coarsest level: exhaustive search within +-uRadius. Finer levels: search +-uRadius around
// the up-scaled coarse vector (and optionally its 4 neighbours) plus the zero vector.
// Sub-pixel refinement by an equiangular (V) fit of the cost around the best integer vector.
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uCoarse;   // coarser level vectors, xy in coarse pixels
uniform bool uHasCoarse;
uniform bool uNeighbours;    // also try the 4 neighbouring coarse vectors
uniform int uRadius;
uniform int uHalf;           // patch half-size: 1 -> 3x3, 2 -> 5x5
uniform float uLambda;
out vec4 o;

ivec2 gMax;
float cv[25];
float curMean;

void loadCur(ivec2 p) {
    curMean = 0.0; int n = 0;
    for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
        if (abs(dx) > uHalf || abs(dy) > uHalf) continue;
        float v = texelFetch(uCur, clamp(p + ivec2(dx, dy), ivec2(0), gMax), 0).r;
        cv[n] = v; curMean += v; n++;
    }
    curMean /= float(n);
}
float cost(ivec2 p, ivec2 v) {
    float pv[25]; float pm = 0.0; int n = 0;
    for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
        if (abs(dx) > uHalf || abs(dy) > uHalf) continue;
        float s = texelFetch(uPrev, clamp(p + v + ivec2(dx, dy), ivec2(0), gMax), 0).r;
        pv[n] = s; pm += s; n++;
    }
    pm /= float(n);
    float s = 0.0;
    for (int i = 0; i < 25; i++) { if (i >= n) break; s += abs((cv[i] - curMean) - (pv[i] - pm)); }
    return s / float(n);
}
vec2 coarseAt(ivec2 c) {
    ivec2 cm = textureSize(uCoarse, 0) - 1;
    return texelFetch(uCoarse, clamp(c, ivec2(0), cm), 0).xy * 2.0;
}
void main() {
    gMax = textureSize(uCur, 0) - 1;
    ivec2 p = ivec2(gl_FragCoord.xy);
    loadCur(p);
    vec2 pred = vec2(0.0);
    vec2 cands[6];
    int nc = 1;
    cands[0] = vec2(0.0);
    if (uHasCoarse) {
        ivec2 c = p / 2;
        pred = coarseAt(c);
        cands[1] = pred; nc = 2;
        if (uNeighbours) {
            cands[2] = coarseAt(c + ivec2(1, 0));
            cands[3] = coarseAt(c + ivec2(-1, 0));
            cands[4] = coarseAt(c + ivec2(0, 1));
            cands[5] = coarseAt(c + ivec2(0, -1));
            nc = 6;
        }
    }
    float best = 1e9; ivec2 bv = ivec2(0);
    for (int k = 0; k < 6; k++) {
        if (k >= nc) break;
        ivec2 ctr = ivec2(floor(cands[k] + 0.5));
        for (int dy = -uRadius; dy <= uRadius; dy++) {
            for (int dx = -uRadius; dx <= uRadius; dx++) {
                ivec2 v = ctr + ivec2(dx, dy);
                float c = cost(p, v) + uLambda * length(vec2(v) - pred);
                if (c < best) { best = c; bv = v; }
            }
        }
    }
    float c0 = cost(p, bv);
    float cxm = cost(p, bv - ivec2(1, 0)), cxp = cost(p, bv + ivec2(1, 0));
    float cym = cost(p, bv - ivec2(0, 1)), cyp = cost(p, bv + ivec2(0, 1));
    // equiangular (V-shaped) fit: unbiased for SAD-type costs, unlike a parabola
    float ex = max(cxm, cxp) - c0, ey = max(cym, cyp) - c0;
    vec2 sub = vec2(ex > 1e-6 ? 0.5 * (cxm - cxp) / ex : 0.0,
                    ey > 1e-6 ? 0.5 * (cym - cyp) / ey : 0.0);
    // with noise the cost minimum is shallow and the fit returns random fractions (which would
    // blur a static background through bilinear warping): trust it only if the cost rises
    // clearly above the residual at the best match
    sub.x *= smoothstep(0.3, 0.6, ex / (ex + c0 + 1e-6));
    sub.y *= smoothstep(0.3, 0.6, ey / (ey + c0 + 1e-6));
    o = vec4(vec2(bv) + clamp(sub, -0.5, 0.5), c0, 1.0);
}
