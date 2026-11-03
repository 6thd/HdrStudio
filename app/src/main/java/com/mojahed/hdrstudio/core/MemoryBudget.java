package com.mojahed.hdrstudio.core;

import java.util.Locale;

/**
 * Memory plan of one export, before anything is allocated. GPU buffers are counted exactly as
 * FramePipeline / SrEngine allocate them (RGBA16F = 8 bytes per texel, only for enabled stages);
 * codec buffers are an ESTIMATE (the driver decides; we assume 14 decoder and 10 encoder frames).
 * The AI tile is the largest of {@link #TILES} that keeps the total inside a conservative budget
 * (phones share RAM between CPU and GPU); the work resolution is never reduced automatically.
 * Pure Java (unit-tested on the JVM).
 */
public final class MemoryBudget {
    public static final int[] TILES = {512, 256, 128, 64};
    public static final int SR_PAD = 36, SR_MARGIN = 2;
    public static final long AI_WEIGHTS = tex(576, 34 * 16);

    public static final class Plan {
        public long input, pipeline, history, aiFixed, aiTiles, decoder, encoder, budget, hardLimit;
        public int tile;            // 0 = no AI
        public boolean withinBudget, fits;

        public long total() { return input + pipeline + history + aiFixed + aiTiles + decoder + encoder; }
        public long gpu() { return input + pipeline + history + aiFixed + aiTiles; }

        public String describe() {
            return String.format(Locale.US, "ذاكرة متوقعة: %d MB (GPU %d: معالجة %d · تاريخ زمني %d · AI %d%s · مفكك/مرمّز ~%d تقديراً) · الميزانية %d MB%s",
                    mb(total()), mb(gpu()), mb(input + pipeline), mb(history), mb(aiFixed + aiTiles), tile > 0 ? " بلاطة " + tile : "",
                    mb(decoder + encoder), mb(budget), withinBudget ? "" : fits ? " · فوق الميزانية المحافظة (أصغر بلاطة)" : " · لا تكفي الذاكرة");
        }
    }

    private MemoryBudget() {}

    public static long tex(long w, long h) { return 8L * w * h; }
    static long mb(long b) { return (b + (1 << 19)) >> 20; }

    /** Textures of FramePipeline for these settings (excluding the temporal history and SR tiles). */
    public static long pipeline(int srcW, int srcH, int outW, int outH, boolean temporal, boolean ai, float aiWorkScale) {
        long b = 2 * tex(srcW, srcH) + 8;                       // a, b, zero
        int w = srcW, h = srcH, l0w = 0, l0h = 0, l1w = 0, l1h = 0;
        for (int i = 0; i < 4; i++) {
            w = (w + 1) / 2; h = (h + 1) / 2;
            if (i == 0) { l0w = w; l0h = h; }
            if (i == 1) { l1w = w; l1h = h; }
            b += tex(w, h) * (temporal ? 3 : 1);                  // lv (+ lvPrev, mv)
        }
        if (temporal) b += tex(l1w, l1h) + tex(l0w, l0h);         // mvMed, mvFinal
        boolean resized = outW != srcW || outH != srcH;
        if (resized) b += tex(outW, srcH) + tex(outW, outH);      // tmpH, o1
        if (resized || ai) b += tex(outW, outH);                  // o2
        if (ai) {
            b += tex(outW, outH);                                  // aiOut
            if (aiWorkScale != 1f) {
                int lw = Math.max(8, Math.round(srcW * aiWorkScale)), lh = Math.max(8, Math.round(srcH * aiWorkScale));
                b += tex(lw, srcH) + tex(lw, lh);                  // lrTmp, lr
            }
        }
        return b + tex(outW, outH);                               // fin
    }

    /** Two history frames at the resolution of the temporal stage: output for AI, source for the classic path. */
    public static long history(int srcW, int srcH, int outW, int outH, boolean temporal, boolean ai) {
        return temporal ? 2 * (ai ? tex(outW, outH) : tex(srcW, srcH)) : 0;
    }

    /** SR tile buffers: two 16-layer feature arrays of (T + 2P)^2 and the x4 tile result. */
    public static long aiTile(int t) {
        long f = t + 2L * SR_PAD;
        return 2 * 16 * tex(f, f) + tex((t + 2L * SR_MARGIN) * 4, (t + 2L * SR_MARGIN) * 4);
    }

    public static long decoderEstimate(int w, int h, boolean tenBit) { return 14L * w * h * 3 / 2 * (tenBit ? 2 : 1); }
    public static long encoderEstimate(int w, int h, boolean tenBit) { return 10L * w * h * 3 / 2 * (tenBit ? 2 : 1); }

    /** Conservative share of RAM for this job: half of what is free now, at most a third of all RAM. */
    public static long budget(long memAvail, long memTotal) {
        return Math.min(memAvail / 2, memTotal > 0 ? memTotal / 3 : Long.MAX_VALUE);
    }

    public static Plan plan(int srcW, int srcH, boolean srcTenBit, int outW, int outH, boolean outTenBit,
                            boolean temporal, boolean ai, float aiWorkScale, long memAvail, long memTotal) {
        Plan p = new Plan();
        p.input = tex(srcW, srcH);
        p.pipeline = pipeline(srcW, srcH, outW, outH, temporal, ai, aiWorkScale);
        p.history = history(srcW, srcH, outW, outH, temporal, ai);
        p.decoder = decoderEstimate(srcW, srcH, srcTenBit);
        p.encoder = encoderEstimate(outW, outH, outTenBit);
        p.budget = budget(memAvail, memTotal);
        p.hardLimit = memAvail * 85 / 100;
        if (ai) {
            p.aiFixed = AI_WEIGHTS;
            p.tile = TILES[TILES.length - 1];
            for (int t : TILES) if (p.total() - p.aiTiles + aiTile(t) <= p.budget) { p.tile = t; break; }
            p.aiTiles = aiTile(p.tile);
        }
        p.withinBudget = p.total() <= p.budget;
        p.fits = p.total() <= p.hardLimit;
        return p;
    }
}
