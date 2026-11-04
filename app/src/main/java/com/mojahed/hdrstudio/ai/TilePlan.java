package com.mojahed.hdrstudio.ai;

/**
 * Tile geometry for the ncnn path. No Android APIs (tested by tools/jvmtests/TileTests.java,
 * and mirrored line for line by tools/ncnn/tile_check.py).
 *
 * The core [x0, x0+cw) x [y0, y0+ch) is cut on a grid of step T. Its context window is real
 * image only: min(F, size) pixels along each axis, F = T + 2P, placed to start P before the core
 * and then shifted to stay inside the image. Nothing outside the image is ever fed to the net:
 * at a true image border the network's own per-layer zero padding applies, exactly as in a
 * direct (untiled) run. Black margins differ from that after the first layer, because bias and
 * PReLU make non-zero features out of the black pixels.
 *
 * Every side of the core then has either >= P pixels of real context, or reaches the image
 * border. P = 36 covers the receptive radius 34 of SRVGGNetCompact (34 convolutions 3x3).
 * The output crop offset is (x0 - cx0, y0 - cy0) x4, taken from the window actually used.
 *
 * Rows are in whatever order the caller uses (the app is bottom-up); the plan is symmetric.
 */
public final class TilePlan {
    /** Receptive radius of SRVGGNetCompact x4v3: 1 + 32 + 1 convolutions of 3x3. */
    public static final int RECEPTIVE_RADIUS = 34;

    public final int w, h, t, p, f;
    public final int tilesX, tilesY;

    public TilePlan(int w, int h, int tileCore, int context) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("size " + w + "x" + h);
        if (context < RECEPTIVE_RADIUS) throw new IllegalArgumentException("context " + context + " < " + RECEPTIVE_RADIUS);
        this.w = w;
        this.h = h;
        this.t = Math.max(8, tileCore);
        this.p = context;
        this.f = t + 2 * p;
        this.tilesX = (w + t - 1) / t;
        this.tilesY = (h + t - 1) / t;
    }

    public int count() { return tilesX * tilesY; }

    /** Largest window side, for buffer sizing. */
    public int maxWindowW() { return Math.min(f, w); }
    public int maxWindowH() { return Math.min(f, h); }

    /** Start of the context window along one axis. Window length is min(F, size). */
    public static int contextStart(int coreStart, int size, int f, int p) {
        int span = Math.min(f, size);
        int c0 = coreStart - p;
        if (c0 < 0) c0 = 0;
        if (c0 + span > size) c0 = size - span;
        return c0;
    }

    /** One tile: core, context window, and the crop offset inside the window (LR pixels). */
    public static final class Tile {
        public int x0, y0, cw, ch;   // core in the image
        public int cx0, cy0, fw, fh; // context window in the image
        public int offX() { return x0 - cx0; }
        public int offY() { return y0 - cy0; }
    }

    /** Fills {@code out} for tile index i (row-major over the grid). */
    public Tile tile(int i, Tile out) {
        int tx = i % tilesX, ty = i / tilesX;
        out.x0 = tx * t;
        out.y0 = ty * t;
        out.cw = Math.min(t, w - out.x0);
        out.ch = Math.min(t, h - out.y0);
        out.fw = Math.min(f, w);
        out.fh = Math.min(f, h);
        out.cx0 = contextStart(out.x0, w, f, p);
        out.cy0 = contextStart(out.y0, h, f, p);
        return out;
    }

    /** One untiled forward of a window: in is fw*fh*3, out is (4fw)*(4fh)*3, same row order. */
    public interface Net { void forward(float[] window, int fw, int fh, float[] hr); }

    /** Called between tiles (export uses it to keep the encoder fed). */
    public interface Between { void between(int done, int total); }

    /** Gather and crop+merge time, the parts of tiling that are not the network. */
    public static final class Stats { public long gatherNs, mergeNs; }

    /**
     * The tiled x4 forward used by export. out must hold (4w)*(4h)*3 floats.
     * Each core's x4 pixels are written exactly once.
     */
    public void run(float[] rgb, Net net, float[] out, Stats st, Between gate) {
        int mw = maxWindowW(), mh = maxWindowH(), W4 = w * 4;
        float[] window = new float[mw * mh * 3];
        float[] hr = new float[mw * 4 * mh * 4 * 3];
        Tile t = new Tile();
        int n = count();
        for (int i = 0; i < n; i++) {
            tile(i, t);
            long c0 = System.nanoTime();
            for (int wy = 0; wy < t.fh; wy++)
                System.arraycopy(rgb, ((t.cy0 + wy) * w + t.cx0) * 3, window, wy * t.fw * 3, t.fw * 3);
            long c1 = System.nanoTime();
            net.forward(window, t.fw, t.fh, hr);
            long m0 = System.nanoTime();
            int hw = t.fw * 4, ox = t.offX() * 4, oy = t.offY() * 4;
            for (int y = 0; y < t.ch * 4; y++)
                System.arraycopy(hr, ((oy + y) * hw + ox) * 3, out, ((t.y0 * 4 + y) * W4 + t.x0 * 4) * 3, t.cw * 4 * 3);
            long m1 = System.nanoTime();
            if (st != null) { st.gatherNs += c1 - c0; st.mergeNs += m1 - m0; }
            if (gate != null && i + 1 < n) gate.between(i + 1, n);
        }
    }
}
