import com.mojahed.hdrstudio.ai.TilePlan;

import java.util.Locale;
import java.util.Random;

/**
 * The ncnn export tiling (TilePlan.run with per-tile cores into a sink, as NcnnSr.forwardTo and export run it) against an untiled forward
 * of a network with the SRVGGNetCompact x4 shape: 34 convolutions 3x3 with per-layer zero
 * padding, bias and PReLU, PixelShuffle(4) and a clamped nearest x4 residual. Few channels so
 * it runs on the JVM; the receptive radius (34) and the border behaviour are the real ones.
 * Per-pixel arithmetic order does not depend on the window, so the tiled result must be
 * bit-identical to the direct one. The 2.3.2 black-margin tiling is run too, to show the
 * test can see the bug it fixes.
 */
public class TileTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }

    static final int C = 4, LAYERS = 34, OUT = 48;
    static float[][] W = new float[LAYERS][], B = new float[LAYERS][], A = new float[LAYERS - 1][];

    static void init() {
        Random r = new Random(1234);
        for (int l = 0; l < LAYERS; l++) {
            int ic = l == 0 ? 3 : C, oc = l == LAYERS - 1 ? OUT : C;
            W[l] = new float[oc * ic * 9];
            for (int i = 0; i < W[l].length; i++) W[l][i] = (float) (r.nextGaussian() * 0.9 / Math.sqrt(ic * 9));
            B[l] = new float[oc];
            for (int i = 0; i < oc; i++) B[l][i] = (float) (0.05 + 0.1 * r.nextGaussian());
            if (l < LAYERS - 1) { A[l] = new float[oc]; for (int i = 0; i < oc; i++) A[l][i] = (float) (0.1 + 0.2 * r.nextDouble()); }
        }
    }

    interface FullNet { void forward(float[] in, int w, int h, float[] hr); }

    /** Untiled forward. in: w*h*3 interleaved, row order kept; out: 4w*4h*3. */
    static final FullNet NET = new FullNet() {
        @Override public void forward(float[] in, int w, int h, float[] hr) {
            int n = w * h;
            float[] x = new float[3 * n];
            for (int i = 0; i < n; i++) for (int c = 0; c < 3; c++) x[c * n + i] = in[i * 3 + c];
            int ic = 3;
            for (int l = 0; l < LAYERS; l++) {
                int oc = l == LAYERS - 1 ? OUT : C;
                float[] y = new float[oc * n];
                for (int o = 0; o < oc; o++) {
                    for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
                        float s = B[l][o];
                        for (int c = 0; c < ic; c++) for (int ky = -1; ky <= 1; ky++) {
                            int yy = py + ky;
                            if (yy < 0 || yy >= h) continue;      // per-layer zero padding at the window border
                            for (int kx = -1; kx <= 1; kx++) {
                                int xx = px + kx;
                                if (xx < 0 || xx >= w) continue;
                                s += W[l][((o * ic + c) * 3 + ky + 1) * 3 + kx + 1] * x[c * n + yy * w + xx];
                            }
                        }
                        if (l < LAYERS - 1 && s < 0) s *= A[l][o];
                        y[o * n + py * w + px] = s;
                    }
                }
                x = y; ic = oc;
            }
            int W4 = w * 4;
            for (int c = 0; c < 3; c++) for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
                float base = Math.max(0f, Math.min(1f, in[(py * w + px) * 3 + c]));
                for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++)
                    hr[((py * 4 + sy) * W4 + px * 4 + sx) * 3 + c] = x[(c * 16 + sy * 4 + sx) * n + py * w + px] + base;
            }
        }
    };

    static float[] image(int w, int h, long seed) {
        Random r = new Random(seed);
        float[] o = new float[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++)
            o[(y * w + x) * 3 + c] = (float) Math.min(1, Math.max(0, 0.5 + 0.3 * Math.sin(x * 0.31 + y * 0.17 + c) + 0.1 * r.nextGaussian()));
        return o;
    }

    static float[] direct(float[] in, int w, int h) { float[] o = new float[w * h * 48]; NET.forward(in, w, h, o); return o; }

    /** The native contract of forwardCore: run the window, write only its core as RGBA x4 (rows in window order). */
    static final TilePlan.CoreNet CORE = new TilePlan.CoreNet() {
        @Override public void forwardCore(java.nio.FloatBuffer window, int fw, int fh, int offX, int offY, int cw, int ch, java.nio.FloatBuffer core) {
            float[] win = new float[fw * fh * 3];
            window.position(0); window.get(win); window.position(0);
            float[] hr = new float[fw * fh * 48];
            NET.forward(win, fw, fh, hr);
            int W4 = fw * 4, cw4 = cw * 4;
            for (int y = 0; y < ch * 4; y++)
                for (int x = 0; x < cw4; x++) {
                    int s = ((offY * 4 + y) * W4 + offX * 4 + x) * 3, d = (y * cw4 + x) * 4;
                    core.put(d, hr[s]); core.put(d + 1, hr[s + 1]); core.put(d + 2, hr[s + 2]); core.put(d + 3, 1f);
                }
        }
    };

    /** Export's path: TilePlan.run with reused window/core buffers and a sink; source RGB (3) or RGBA (4). */
    static float[] tiled(float[] in, int w, int h, int t, int channels) {
        float[] src = in;
        if (channels == 4) { src = new float[w * h * 4]; for (int i = 0; i < w * h; i++) { src[i * 4] = in[i * 3]; src[i * 4 + 1] = in[i * 3 + 1]; src[i * 4 + 2] = in[i * 3 + 2]; src[i * 4 + 3] = 0.5f; } }
        TilePlan p = new TilePlan(w, h, t, 36);
        float[] o = new float[w * h * 48];
        p.run(src, channels, java.nio.FloatBuffer.allocate((int) p.windowFloats()), java.nio.FloatBuffer.allocate((int) p.coreFloats()),
                CORE, TilePlan.rgbArraySink(o, w), null, null);
        return o;
    }

    /** The 2.3.1/2.3.2 tiling: fixed (T+2P)^2 window, black outside the image, crop at P. */
    static float[] blackMargin(float[] rgb, int w, int h, int t) {
        int P = 36, F = t + 2 * P, W4 = w * 4;
        float[] out = new float[w * h * 48], win = new float[F * F * 3], hr = new float[F * F * 48];
        for (int y0 = 0; y0 < h; y0 += t) for (int x0 = 0; x0 < w; x0 += t) {
            int cw = Math.min(t, w - x0), ch = Math.min(t, h - y0);
            java.util.Arrays.fill(win, 0f);
            for (int wy = 0; wy < F; wy++) { int iy = y0 - P + wy; if (iy < 0 || iy >= h) continue;
                for (int wx = 0; wx < F; wx++) { int ix = x0 - P + wx; if (ix < 0 || ix >= w) continue;
                    System.arraycopy(rgb, (iy * w + ix) * 3, win, (wy * F + wx) * 3, 3); } }
            NET.forward(win, F, F, hr);
            for (int y = 0; y < ch * 4; y++)
                System.arraycopy(hr, ((P * 4 + y) * F * 4 + P * 4) * 3, out, ((y0 * 4 + y) * W4 + x0 * 4) * 3, cw * 12);
        }
        return out;
    }

    static double maxAbs(float[] a, float[] b) { double m = 0; for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i])); return m; }

    public static void main(String[] args) {
        init();
        // geometry over many sizes: every LR pixel covered once, window inside the image and <= F,
        // each core side has >= P real pixels or reaches the true border
        int combos = 0, bad = 0; String first = "";
        int[] tiles = {8, 16, 24, 64, 128, 256, 512};
        for (int w = 1; w <= 300; w += 7) for (int h = 1; h <= 200; h += 11) for (int t : tiles) {
            TilePlan p = new TilePlan(w, h, t, 36);
            int[] cov = new int[w * h];
            TilePlan.Tile tl = new TilePlan.Tile();
            String err = null;
            for (int i = 0; i < p.count(); i++) {
                p.tile(i, tl);
                for (int y = tl.y0; y < tl.y0 + tl.ch; y++) for (int x = tl.x0; x < tl.x0 + tl.cw; x++) cov[y * w + x]++;
                if (tl.cx0 < 0 || tl.cy0 < 0 || tl.cx0 + tl.fw > w || tl.cy0 + tl.fh > h) err = "window outside";
                if (tl.fw > p.f || tl.fh > p.f) err = "window > F";
                if (tl.offX() < 0 || tl.offY() < 0 || tl.offX() + tl.cw > tl.fw || tl.offY() + tl.ch > tl.fh) err = "core outside window";
                if (!(tl.cx0 == 0 || tl.offX() >= 36) || !(tl.cy0 == 0 || tl.offY() >= 36)) err = "low context";
                if (!(tl.cx0 + tl.fw == w || tl.cx0 + tl.fw - tl.x0 - tl.cw >= 36) || !(tl.cy0 + tl.fh == h || tl.cy0 + tl.fh - tl.y0 - tl.ch >= 36)) err = "high context";
            }
            for (int c : cov) if (c != 1) err = "coverage " + c;
            combos++;
            if (err != null) { bad++; if (first.isEmpty()) first = w + "x" + h + " T=" + t + ": " + err; }
        }
        check("tile_plan_geometry", bad == 0, combos + " size/tile combinations" + (first.isEmpty() ? "" : ", first: " + first));
        boolean threw = false;
        try { new TilePlan(10, 10, 64, 33); } catch (IllegalArgumentException e) { threw = true; }
        check("tile_plan_rejects_context_below_receptive_radius", threw, "context 33 < 34");

        int[][] cases = {{16, 12, 64}, {16, 12, 8}, {157, 113, 64}, {157, 113, 32}, {157, 113, 16}, {113, 157, 24}};
        for (int[] c : cases) {
            int w = c[0], h = c[1], t = c[2];
            float[] in = image(w, h, w * 1000L + h);
            float[] d = direct(in, w, h);
            float[] nt = tiled(in, w, h, t, 3);
            float[] nt4 = tiled(in, w, h, t, 4);
            float[] old = blackMargin(in, w, h, t);
            TilePlan p = new TilePlan(w, h, t, 36);
            double mNew = maxAbs(nt, d), mOld = maxAbs(old, d);
            check(String.format(Locale.US, "tiled_equals_direct_%dx%d_T%d", w, h, t), mNew == 0.0,
                    String.format(Locale.US, "%d tiles, window <= %dx%d, maxAbs %.3g (black-margin tiling: %.3g)", p.count(), p.maxWindowW(), p.maxWindowH(), mNew, mOld));
            check(String.format(Locale.US, "tiled_rgba_source_equals_direct_%dx%d_T%d", w, h, t), maxAbs(nt4, d) == 0.0,
                    String.format(Locale.US, "source straight from a GL read (RGBA), maxAbs %.3g", maxAbs(nt4, d)));
            check(String.format(Locale.US, "black_margin_tiling_detected_%dx%d_T%d", w, h, t), mOld > 1e-3,
                    String.format(Locale.US, "maxAbs %.3g", mOld));
        }
        System.out.println("TILE SUMMARY fails=" + fails);
        if (fails > 0) System.exit(1);
    }
}
