package com.mojahed.hdrstudio.stages;

/**
 * Scene-cut detection and scene statistics from the coarsest luma pyramid level (1/16 res).
 * Mirrors SceneAnalyzer in tools/harness/engine.js (tested there against ffmpeg's scene score,
 * a flash sequence and a moving pan). Control signals only - nothing here is written to the
 * file as a measurement.
 */
public final class SceneAnalyzer {
    private float[] prev;
    private double expand = Double.NaN;
    public double lastD, lastHd, lastBrightFrac;

    private static double[] stats(float[] a) {
        double m = 0;
        for (float v : a) m += v;
        m /= a.length;
        double s = 0;
        for (float v : a) s += (v - m) * (v - m);
        return new double[]{m, Math.sqrt(s / a.length)};
    }

    public void reset() { prev = null; expand = Double.NaN; }

    /** @return true when thumb starts a new shot (also true for the first frame). */
    public boolean isCut(float[] thumb) {
        float[] p = prev;
        prev = thumb;
        if (p == null || p.length != thumb.length) { lastD = 0; lastHd = 0; return true; }
        double[] c = stats(thumb), q = stats(p);
        double d = 0;
        for (int i = 0; i < thumb.length; i++)
            d += Math.abs((thumb[i] - c[0]) / (c[1] + 0.02) - (p[i] - q[0]) / (q[1] + 0.02));
        d /= thumb.length;
        int[] hc = new int[32], hp = new int[32];
        for (int i = 0; i < thumb.length; i++) {
            hc[Math.min(31, Math.max(0, (int) Math.floor(thumb[i] * 32)))]++;
            hp[Math.min(31, Math.max(0, (int) Math.floor(p[i] * 32)))]++;
        }
        double hd = 0;
        for (int i = 0; i < 32; i++) hd += Math.abs(hc[i] - hp[i]);
        hd /= 2.0 * thumb.length;
        lastD = d; lastHd = hd;
        return d > 0.45;
    }

    /** SDR->HDR expansion adapted to the share of bright area, smoothed (tau 0.4 s), reset on cuts. */
    public double adaptExpand(float[] thumb, double user, double knee, double dtSec, boolean cut, int sdrGamma) {
        double g = sdrGamma == 1 ? 2.2 : 2.4;
        int bright = 0;
        for (float v : thumb) if (Math.pow(Math.min(Math.max(v, 0), 1), g) > knee) bright++;
        double frac = (double) bright / thumb.length;
        lastBrightFrac = frac;
        double t = Math.min(1, Math.max(0, (frac - 0.10) / (0.45 - 0.10)));
        double target = user * (1 - 0.6 * t * t * (3 - 2 * t));
        if (cut || Double.isNaN(expand)) expand = target;
        else expand += (target - expand) * (1 - Math.exp(-dtSec / 0.4));
        return expand;
    }
}
