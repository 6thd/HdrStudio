package com.mojahed.hdrstudio.encode;

import java.util.Locale;

/**
 * Diagnostic look at decoded 10-bit luma codes of an arbitrary exported file. It is NOT a pass
 * condition: ordinary content (a black opening, flat colours, a still title) legitimately uses
 * only a few codes, often all multiples of 4 (black = 64), and is perfectly valid 10-bit video.
 *
 * What it can say:
 *  - INCONCLUSIVE: the sampled content has too few levels / too narrow a range to show anything;
 *  - CONSISTENT: codes that are not multiples of 4 are present. After lossy compression this does
 *    NOT prove the absence of an 8-bit stage (the encoder itself creates in-between codes);
 *  - SUSPECT_8BIT: many levels over a wide range, yet (almost) every code is a multiple of 4 -
 *    typical of an 8-bit Y'CbCr stage after the encoder (e.g. a decoder path). Reported as a
 *    warning; the strong precision evidence is the path test's ramp (PrecisionMetric).
 * Pure Java (unit-tested on the JVM with x265 streams).
 */
public final class PixelDiagnostics {
    public enum Outcome { CONSISTENT, INCONCLUSIVE, SUSPECT_8BIT }

    /** Content must span at least this many distinct codes and this code range to be informative (= 16+ 8-bit steps). */
    public static final int MIN_DISTINCT = 32, MIN_SPAN = 64;
    public static final double SUSPECT_MAX_FRACTION = 0.02, CONSISTENT_MIN_FRACTION = 0.05;

    public static final class Result {
        public Outcome outcome = Outcome.INCONCLUSIVE;
        public long samples;
        public int distinct, min = -1, max = -1;
        public double fracNonMultipleOf4;

        public String describe() {
            String stats = String.format(Locale.US, "%d عينة · %d رمزاً مختلفاً · المدى %d–%d · %.1f%% ليست مضاعفات 4", samples, distinct, min, max, fracNonMultipleOf4 * 100);
            switch (outcome) {
                case CONSISTENT: return "تشخيص البكسلات (ليس شرط نجاح): متسق مع 10-bit بعد الضغط، ولا يثبت غياب مرحلة 8-bit قبل المرمّز (" + stats + ")";
                case SUSPECT_8BIT: return "تشخيص البكسلات: تنبيه — محتوى متنوع ومعظم الرموز مضاعفات 4، وهو نمط مرحلة 8-bit بعد المرمّز (" + stats + ")";
                default: return "تشخيص البكسلات: غير حاسم — المحتوى المفحوص لا يحوي مستويات كافية (" + stats + ")";
            }
        }
    }

    private PixelDiagnostics() {}

    /** @param hist histogram of decoded 10-bit luma codes (length 1024). */
    public static Result classify(long[] hist) {
        Result r = new Result();
        long nonMul = 0;
        for (int c = 0; c < hist.length; c++) {
            if (hist[c] == 0) continue;
            r.samples += hist[c]; r.distinct++;
            if (r.min < 0) r.min = c;
            r.max = c;
            if ((c & 3) != 0) nonMul += hist[c];
        }
        if (r.samples == 0) return r;
        r.fracNonMultipleOf4 = (double) nonMul / r.samples;
        boolean informative = r.distinct >= MIN_DISTINCT && r.max - r.min >= MIN_SPAN;
        if (informative && r.fracNonMultipleOf4 < SUSPECT_MAX_FRACTION) r.outcome = Outcome.SUSPECT_8BIT;
        else if (r.fracNonMultipleOf4 >= CONSISTENT_MIN_FRACTION && r.distinct >= 8) r.outcome = Outcome.CONSISTENT;
        else r.outcome = Outcome.INCONCLUSIVE;
        return r;
    }

    public static Result classify(int[] codes) {
        long[] h = new long[1024];
        for (int c : codes) h[Math.max(0, Math.min(1023, c))]++;
        return classify(h);
    }
}
