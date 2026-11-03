package com.mojahed.hdrstudio.core;

import java.util.Locale;

/**
 * Exact frame rate as a reduced fraction (30000/1001 is not 30/1, 60/1 is not 60000/1001).
 * Average rates measured from container timestamps are snapped to a broadcast rate only when they
 * are within {@link #SNAP_REL} of it; anything else keeps millihertz precision. The same input
 * always gives the same fraction, so the self test and the export derive identical values.
 * Pure Java (unit-tested on the JVM).
 */
public final class FrameRate {
    public final long num, den;

    /** 0.02 %: separates 29.97 from 30 (0.1 % apart) while absorbing timestamp rounding (e.g. 1/600 s timescales). */
    public static final double SNAP_REL = 2e-4;

    public static final FrameRate FPS_23_976 = new FrameRate(24000, 1001), FPS_24 = new FrameRate(24, 1),
            FPS_25 = new FrameRate(25, 1), FPS_29_97 = new FrameRate(30000, 1001), FPS_30 = new FrameRate(30, 1),
            FPS_48 = new FrameRate(48, 1), FPS_50 = new FrameRate(50, 1), FPS_59_94 = new FrameRate(60000, 1001),
            FPS_60 = new FrameRate(60, 1), FPS_120 = new FrameRate(120, 1), FPS_119_88 = new FrameRate(120000, 1001),
            FPS_15 = new FrameRate(15, 1), FPS_12 = new FrameRate(12, 1), FPS_10 = new FrameRate(10, 1);
    private static final FrameRate[] STANDARD = {FPS_23_976, FPS_24, FPS_25, FPS_29_97, FPS_30, FPS_48, FPS_50, FPS_59_94, FPS_60,
            FPS_119_88, FPS_120, FPS_15, FPS_12, FPS_10};

    public FrameRate(long num, long den) {
        if (num <= 0 || den <= 0) throw new IllegalArgumentException("frame rate must be positive: " + num + "/" + den);
        long g = gcd(num, den);
        this.num = num / g; this.den = den / g;
    }

    public static FrameRate fromAverage(double fps) {
        if (!(fps > 0) || Double.isInfinite(fps)) throw new IllegalArgumentException("bad fps " + fps);
        for (FrameRate s : STANDARD) if (Math.abs(fps / s.fps() - 1) <= SNAP_REL) return s;
        return new FrameRate(Math.max(1, Math.round(fps * 1000)), 1000);
    }

    public static FrameRate parse(String s) {
        int i = s.indexOf('/');
        return i < 0 ? new FrameRate(Long.parseLong(s.trim()), 1) : new FrameRate(Long.parseLong(s.substring(0, i).trim()), Long.parseLong(s.substring(i + 1).trim()));
    }

    public double fps() { return (double) num / den; }

    /** Duration of frame index k measured from 0, in microseconds, without accumulated rounding: round(k * den * 1e6 / num). */
    public long ptsUs(long k) { return Math.floorDiv(k * den * 1_000_000L + num / 2, num); }

    public boolean isStandard() { for (FrameRate s : STANDARD) if (s.equals(this)) return true; return false; }

    public String label() { return String.format(Locale.US, "%.3f", fps()).replaceAll("0+$", "").replaceAll("\\.$", "") + " (" + this + ")"; }

    @Override public String toString() { return num + "/" + den; }
    @Override public boolean equals(Object o) { return o instanceof FrameRate && ((FrameRate) o).num == num && ((FrameRate) o).den == den; }
    @Override public int hashCode() { return Long.hashCode(num * 31 + den); }

    private static long gcd(long a, long b) { while (b != 0) { long t = a % b; a = b; b = t; } return a; }
}
