package com.mojahed.hdrstudio.core;

/**
 * Geometry of the source-resolution crop preview: a window of the source plus a context margin
 * is processed with the export's exact scale factors, and the margin is cut away afterwards.
 * The result equals the same region of a full-frame run when
 *  - the margin covers the receptive field of every stage (deband radius, denoise 5x5, AI
 *    34 conv layers at work resolution plus its LR resize, Lanczos resize, sharpen 3x3);
 *  - the crop edges are pixels whose position maps to whole pixels at every resolution in the
 *    chain (source, AI work resolution, output), so every resampler sees the same phase;
 *  - frame statistics (noise sigma, brightness expansion) come from the whole frame
 *    (FramePipeline.Region), and position-hashed patterns use the crop origin.
 * Mirrored by previewCrop() in tools/harness/engine.js (tested against each other on the JVM).
 */
public final class PreviewCrop {
    /** Source rectangle processed (crop + margin). */
    public final int x0, y0, w, h;
    /** Output size of that rectangle (same scale as the export). */
    public final int outW, outH;
    /** Visible region inside the processed output. */
    public final int visX, visY, visW, visH;
    /** Visible region in source pixels (the "before" image). */
    public final int srcX, srcY, srcW, srcH;
    public final int margin;
    public final boolean fullFrame;

    private PreviewCrop(int x0, int y0, int w, int h, int outW, int outH, int visX, int visY, int visW, int visH,
                        int srcX, int srcY, int srcW, int srcH, int margin, boolean fullFrame) {
        this.x0 = x0; this.y0 = y0; this.w = w; this.h = h; this.outW = outW; this.outH = outH;
        this.visX = visX; this.visY = visY; this.visW = visW; this.visH = visH;
        this.srcX = srcX; this.srcY = srcY; this.srcW = srcW; this.srcH = srcH; this.margin = margin; this.fullFrame = fullFrame;
    }

    static long gcd(long a, long b) { while (b != 0) { long t = a % b; a = b; b = t; } return a; }
    static long lcm(long a, long b) { return a / gcd(a, b) * b; }

    /** Source pixels that change the output at distance d: sum of the stage radii (source px), plus safety. */
    public static int margin(int srcW, int outW, boolean ai, float workScale, float debandRadius) {
        double down = Math.max(1.0, (double) srcW / outW);          // source px per output px when downscaling
        double perOut = (double) srcW / outW;
        double r = Math.ceil(debandRadius) + 1 + 2                    // deband, denoise 5x5
                + 4 * down                                            // Lanczos 3 lobes (+1)
                + 2 * perOut;                                         // sharpen 3x3 at output resolution
        if (ai) r += 38.0 / workScale + 4 * Math.max(1.0, 1.0 / workScale);   // 34 conv layers (+4), LR resize
        return (int) Math.ceil(r) + 4;
    }

    /** Grid step: positions that are whole pixels at source, AI work and output resolution. */
    static long step(int src, int out, boolean ai, float workScale) {
        long q = src / gcd(src, out);
        if (ai && workScale != 1f) {
            int lr = Math.max(8, Math.round(src * workScale));
            q = lcm(q, src / gcd(src, lr));
        }
        return q;
    }

    /**
     * @param fx, fy  focus point (0..1 of the frame)
     * @param visOutW, visOutH visible size in output pixels
     * @return the crop, a full-frame plan when the window covers (nearly) everything, or null when the
     *         sizes have no common pixel grid small enough for an exact crop (caller previews the full frame).
     */
    public static PreviewCrop plan(int srcW, int srcH, int outW, int outH, boolean ai, float workScale, float debandRadius,
                                   double fx, double fy, int visOutW, int visOutH) {
        long qx = step(srcW, outW, ai, workScale), qy = step(srcH, outH, ai, workScale);
        int vw = (int) Math.min(srcW, roundUp((long) Math.ceil((double) visOutW * srcW / outW), qx));
        int vh = (int) Math.min(srcH, roundUp((long) Math.ceil((double) visOutH * srcH / outH), qy));
        if (vw >= srcW * 9L / 10 && vh >= srcH * 9L / 10)
            return new PreviewCrop(0, 0, srcW, srcH, outW, outH, 0, 0, outW, outH, 0, 0, srcW, srcH, 0, true);
        if (qx * 4 > vw || qy * 4 > vh) return null;
        int m = margin(srcW, outW, ai, workScale, debandRadius), my = margin(srcH, outH, ai, workScale, debandRadius);
        int vx = place(fx, srcW, vw, qx), vy = place(fy, srcH, vh, qy);
        int x0 = (int) Math.max(0, roundDown(vx - m, qx)), y0 = (int) Math.max(0, roundDown(vy - my, qy));
        int x1 = (int) Math.min(srcW, roundUp(vx + vw + m, qx)), y1 = (int) Math.min(srcH, roundUp(vy + vh + my, qy));
        int ox0 = scale(x0, srcW, outW), oy0 = scale(y0, srcH, outH);
        int ovx = scale(vx, srcW, outW), ovy = scale(vy, srcH, outH);
        return new PreviewCrop(x0, y0, x1 - x0, y1 - y0, scale(x1, srcW, outW) - ox0, scale(y1, srcH, outH) - oy0,
                ovx - ox0, ovy - oy0, scale(vx + vw, srcW, outW) - ovx, scale(vy + vh, srcH, outH) - ovy,
                vx, vy, vw, vh, Math.max(m, my), false);
    }

    static int place(double f, int size, int v, long q) {
        long c = Math.round(Math.max(0, Math.min(1, f)) * size - v / 2.0);
        long hi = roundDown(size - v, q);
        return (int) Math.max(0, Math.min(hi, roundDown(c, q)));
    }

    static long roundDown(long v, long q) { return Math.floorDiv(v, q) * q; }
    static long roundUp(long v, long q) { return -Math.floorDiv(-v, q) * q; }
    /** Exact: x is a multiple of the grid step or the frame edge. */
    static int scale(long x, int src, int out) { return (int) (x * out / src); }

    @Override public String toString() {
        return fullFrame ? "full " + w + "x" + h : String.format(java.util.Locale.US, "src %d,%d %dx%d -> %dx%d vis %d,%d %dx%d margin %d",
                x0, y0, w, h, outW, outH, visX, visY, visW, visH, margin);
    }
}
