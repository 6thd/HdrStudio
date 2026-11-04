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

    /** Floats of the window buffer (RGB) the run needs: the largest context window. */
    public long windowFloats() { return 3L * maxWindowW() * maxWindowH(); }

    /** Floats of the core buffer (RGBA, x4) the run needs: the largest tile core. */
    public long coreFloats() { return 4L * (4L * Math.min(t, w)) * (4L * Math.min(t, h)); }

    /**
     * One window forward that writes only its core: (4cw)x(4ch) RGBA floats, rows in the window's
     * order, into core (position 0). The window holds fw*fh RGB floats.
     */
    public interface CoreNet {
        void forwardCore(java.nio.FloatBuffer window, int fw, int fh, int offX, int offY, int cw, int ch, java.nio.FloatBuffer core);
    }

    /** Receives each tile's core, already cropped: its place in the x4 output is (4 t.x0, 4 t.y0), size 4cw x 4ch. */
    public interface Sink { void core(Tile t, java.nio.FloatBuffer core); }

    /** Called between tiles (export uses it to keep the encoder fed). */
    public interface Between { void between(int done, int total); }

    /** Gather, network and sink time. */
    public static final class Stats { public long gatherNs, netNs, sinkNs; }

    /**
     * The tiled x4 forward used by export and by the checks. src has srcChannels floats per pixel
     * (3 = RGB, 4 = RGBA straight from a GL read; alpha is ignored). window and core are reused for
     * every tile and must hold windowFloats() and coreFloats(). No x4 frame is built here: each core
     * goes to the sink as soon as it exists.
     */
    public void run(float[] src, int srcChannels, java.nio.FloatBuffer window, java.nio.FloatBuffer core,
                    CoreNet net, Sink sink, Stats st, Between gate) {
        if (src.length < (long) w * h * srcChannels) throw new IllegalArgumentException("source " + src.length);
        if (window.capacity() < windowFloats() || core.capacity() < coreFloats())
            throw new IllegalArgumentException("buffers " + window.capacity() + "/" + core.capacity());
        float[] row = new float[maxWindowW() * 3];
        Tile t = new Tile();
        int n = count();
        for (int i = 0; i < n; i++) {
            tile(i, t);
            long c0 = System.nanoTime();
            for (int wy = 0; wy < t.fh; wy++) {
                int s0 = ((t.cy0 + wy) * w + t.cx0) * srcChannels;
                if (srcChannels == 3) System.arraycopy(src, s0, row, 0, t.fw * 3);
                else for (int x = 0; x < t.fw; x++) {
                    int s = s0 + x * srcChannels;
                    row[x * 3] = src[s]; row[x * 3 + 1] = src[s + 1]; row[x * 3 + 2] = src[s + 2];
                }
                window.position(wy * t.fw * 3);
                window.put(row, 0, t.fw * 3);
            }
            window.position(0);
            core.position(0);
            long c1 = System.nanoTime();
            net.forwardCore(window, t.fw, t.fh, t.offX(), t.offY(), t.cw, t.ch, core);
            long c2 = System.nanoTime();
            core.position(0);
            sink.core(t, core);
            long c3 = System.nanoTime();
            if (st != null) { st.gatherNs += c1 - c0; st.netNs += c2 - c1; st.sinkNs += c3 - c2; }
            if (gate != null && i + 1 < n) gate.between(i + 1, n);
        }
    }

    /** Sink that writes cores into an RGB x4 array (checks and tests; not for full frames in export). */
    public static Sink rgbArraySink(final float[] out, final int w) {
        return new Sink() {
            @Override public void core(Tile t, java.nio.FloatBuffer core) {
                int W4 = w * 4, cw4 = t.cw * 4;
                for (int y = 0; y < t.ch * 4; y++) {
                    int d = ((t.y0 * 4 + y) * W4 + t.x0 * 4) * 3;
                    int s = y * cw4 * 4;
                    for (int x = 0; x < cw4; x++) {
                        out[d + x * 3] = core.get(s + x * 4);
                        out[d + x * 3 + 1] = core.get(s + x * 4 + 1);
                        out[d + x * 3 + 2] = core.get(s + x * 4 + 2);
                    }
                }
            }
        };
    }
}
