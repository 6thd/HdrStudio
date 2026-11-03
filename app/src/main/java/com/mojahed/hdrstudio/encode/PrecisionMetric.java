package com.mojahed.hdrstudio.encode;

import java.util.Arrays;

/**
 * Decides whether decoded 10-bit luma samples of the self-test grey ramp really carry 10-bit
 * precision. A ramp spanning ~55 luma codes is drawn in the GL output stage; an 8-bit stage
 * anywhere before the encoder (EGL surface, encoder input conversion) turns it into ~16 plateaus,
 * a 10-bit path into ~55 near-uniform steps. Counting merely the distinct values is fooled by
 * compression, which creates in-between values at the edges of 8-bit steps; instead we count how
 * many of the most frequent codes are needed to cover 90% of the samples ("coverage90").
 * Pure Java - thresholds are derived and tested on the JVM with x265-encoded ramps.
 */
public final class PrecisionMetric {
    private PrecisionMetric() {}

    /** @param codes decoded 10-bit luma codes along the ramp (any order, several rows allowed). */
    public static int coverage90(int[] codes) {
        if (codes.length == 0) return 0;
        int[] hist = new int[1024];
        for (int c : codes) hist[Math.max(0, Math.min(1023, c))]++;
        int[] sorted = hist.clone();
        Arrays.sort(sorted);
        long need = (long) Math.ceil(codes.length * 0.9), acc = 0;
        int n = 0;
        for (int i = 1023; i >= 0 && acc < need; i--) { acc += sorted[i]; n++; }
        return n;
    }

    /**
     * Measured with x265 (10-bit) on the exact self-test frame, one ramp row, 3 frames
     * (tools/jvmtests/run.sh, docs/TEST_REPORT.md):
     *   1080p @31 Mbps: 10-bit 43 / 8-bit stage 13     1080p @8 Mbps: 10-bit 34 / 8-bit stage 16-18
     *   2160p @124 Mbps: 10-bit 43 / 8-bit 13          2160p @31 Mbps: 10-bit 42 / 8-bit 13
     * Plain "distinct values" FAILS to separate them at 8 Mbps (10-bit 49 vs 8-bit stage 52).
     */
    public static final int RAMP_SIGNAL_STEPS = 64;          // signal span in 10-bit R'G'B' steps (uRamp.y = 64/1023)
    public static final int MIN_COVERAGE90_FOR_10BIT = 26;

    public static boolean proves10bit(int coverage90) { return coverage90 >= MIN_COVERAGE90_FOR_10BIT; }
}
