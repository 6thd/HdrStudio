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
        public long input, pipeline, history, aiFixed, aiTiles, scratch, cpu, decoder, encoder, budget, hardLimit;
        public int tile;            // 0 = no AI
        /** Conservative budget is the operating limit. hardLimit (85% of free RAM) is reported only, never a permission. */
        public boolean withinBudget, fits;
        public boolean analysis;    // preview: whole-frame statistics pass is included
        /** True when aiTiles is the ncnn workspace estimate (not the GLSL tile textures). */
        public boolean ncnn;

        public long total() { return input + pipeline + history + aiFixed + aiTiles + scratch + cpu + decoder + encoder; }
        public long gpu() { return input + pipeline + history + aiFixed + aiTiles + scratch; }

        public String describe() {
            return String.format(Locale.US, "ذاكرة متوقعة: %d MB (GPU %d: معالجة %d · تاريخ زمني %d · AI %d%s · مفكك/مرمّز ~%d تقديراً%s) · الميزانية المحافظة %d MB%s",
                    mb(total()), mb(gpu()), mb(input + pipeline), mb(history), mb(aiFixed + aiTiles), tile > 0 ? " بلاطة " + tile : "",
                    mb(decoder + encoder), cpu > 0 ? " · صور " + mb(cpu) : "",
                    mb(budget), (ncnn ? " · ncnn: مساحة العمل وتكرار الأوزان ونقل CPU تقدير" : "") + (fits ? "" : " · فوق الميزانية المحافظة — لن يُشغَّل"));
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

    /**
     * ncnn workspace for one tile, sized as FP32 even when the run is FP16, plus a labeled
     * 64 MB driver slack. This is an estimate, not a measured allocation.
     */
    public static long ncnnWorkspace(int t) {
        long f = t + 2L * SR_PAD;
        long in = 4L * 3 * f * f;
        long feat = 4L * 64 * f * f * 2;
        long side = f * 4;
        long out = 4L * 3 * side * side;
        return in + feat + out + (64L << 20);
    }

    public static long decoderEstimate(int w, int h, boolean tenBit) { return 14L * w * h * 3 / 2 * (tenBit ? 2 : 1); }
    public static long encoderEstimate(int w, int h, boolean tenBit) { return 10L * w * h * 3 / 2 * (tenBit ? 2 : 1); }

    /** Conservative share of RAM for this job: half of what is free now, at most a third of all RAM. */
    public static long budget(long memAvail, long memTotal) {
        return Math.min(memAvail / 2, memTotal > 0 ? memTotal / 3 : Long.MAX_VALUE);
    }

    public static Plan plan(int srcW, int srcH, boolean srcTenBit, int outW, int outH, boolean outTenBit,
                            boolean temporal, boolean ai, float aiWorkScale, long memAvail, long memTotal) {
        return plan(srcW, srcH, srcTenBit, outW, outH, outTenBit, temporal, ai, aiWorkScale, memAvail, memTotal, false);
    }

    public static Plan plan(int srcW, int srcH, boolean srcTenBit, int outW, int outH, boolean outTenBit,
                            boolean temporal, boolean ai, float aiWorkScale, long memAvail, long memTotal, boolean ncnn) {
        Plan p = new Plan();
        p.input = tex(srcW, srcH);
        p.pipeline = pipeline(srcW, srcH, outW, outH, temporal, ai, aiWorkScale);
        p.history = history(srcW, srcH, outW, outH, temporal, ai);
        p.decoder = decoderEstimate(srcW, srcH, srcTenBit);
        p.encoder = encoderEstimate(outW, outH, outTenBit);
        if (ai && ncnn) {
            p.cpu += transferBytes(srcW, srcH, aiWorkScale);
            p.scratch += ncnnResampleTex(srcW, srcH, aiWorkScale, outW, outH);
        }
        chooseTile(p, ai, memAvail, memTotal, ncnn);
        return p;
    }

    /** RGBA32F download of the network input plus RGBA32F upload of the ×4 result. CPU, not GPU. */
    public static long transferBytes(int srcW, int srcH, float aiWorkScale) {
        int lw = Math.max(8, Math.round(srcW * aiWorkScale)), lh = Math.max(8, Math.round(srcH * aiWorkScale));
        return 16L * lw * lh + 16L * lw * 4L * lh * 4L;
    }

    /** Extra GL texture when the ×4 result is not already the output size. 0 when it is. */
    public static long ncnnResampleTex(int srcW, int srcH, float aiWorkScale, int outW, int outH) {
        int lw = Math.max(8, Math.round(srcW * aiWorkScale)), lh = Math.max(8, Math.round(srcH * aiWorkScale));
        return (lw * 4 != outW || lh * 4 != outH) ? tex(lw * 4L, lh * 4L) : 0;
    }

    /**
     * Peak RAM of a single-frame preview (no encoder, no temporal history). The decoded source
     * texture lives throughout. A whole-frame analysis pass, if requested, is released before the
     * process pipeline, so the two do not add. 8-bit readback and the two bitmaps are included.
     * Analysis is dropped when it alone would exceed the conservative budget (the crop then uses
     * its own statistics; that is no longer identical to a full-frame run).
     */
    public static Plan preview(int srcW, int srcH, boolean srcTenBit,
                               int procW, int procH, int procOutW, int procOutH,
                               boolean wantAnalysis, boolean ai, float aiWorkScale,
                               int visW, int visH, boolean hdrDisplay,
                               long memAvail, long memTotal) {
        return preview(srcW, srcH, srcTenBit, procW, procH, procOutW, procOutH, wantAnalysis, ai, aiWorkScale,
                visW, visH, hdrDisplay, memAvail, memTotal, false);
    }

    public static Plan preview(int srcW, int srcH, boolean srcTenBit,
                               int procW, int procH, int procOutW, int procOutH,
                               boolean wantAnalysis, boolean ai, float aiWorkScale,
                               int visW, int visH, boolean hdrDisplay,
                               long memAvail, long memTotal, boolean ncnn) {
        Plan p = new Plan();
        p.input = tex(srcW, srcH);
        p.decoder = decoderEstimate(srcW, srcH, srcTenBit) * 4 / 14;   // a seek holds fewer buffers than a long export
        p.cpu = 4L * visW * visH * 2;                                  // before/after ARGB bitmaps
        long analysisPipe = wantAnalysis ? pipeline(srcW, srcH, srcW, srcH, false, false, 1f) : 0;
        long processPipe = pipeline(procW, procH, procOutW, procOutH, false, ai, aiWorkScale);
        boolean cropped = procW != srcW || procH != srcH || procOutW != visW || procOutH != visH;
        p.scratch = (cropped ? tex(procW, procH) + tex(visW, visH) : 0) + 4L * visW * visH   // process input crop + vis crop + RGBA8 readback
                + (hdrDisplay ? tex(visW, visH) : 0);
        p.pipeline = processPipe;
        if (ai && ncnn) {
            p.cpu += transferBytes(procW, procH, aiWorkScale);
            p.scratch += ncnnResampleTex(procW, procH, aiWorkScale, procOutW, procOutH);
        }
        chooseTile(p, ai, memAvail, memTotal, ncnn);
        p.analysis = false;
        if (wantAnalysis && analysisPipe > 0) {
            long saved = p.pipeline;
            p.pipeline = Math.max(saved, analysisPipe);
            if (p.total() <= p.budget) p.analysis = true;
            else p.pipeline = saved;   // drop whole-frame statistics so the crop itself can still run
        }
        p.withinBudget = p.total() <= p.budget;
        p.fits = p.withinBudget;
        return p;
    }

    /** Conservative budget is the operating limit. 85% of free RAM is recorded, not a second gate. */
    static void chooseTile(Plan p, boolean ai, long memAvail, long memTotal, boolean ncnn) {
        p.budget = budget(memAvail, memTotal);
        p.hardLimit = memAvail > 0 ? memAvail * 85 / 100 : 0;
        p.ncnn = ai && ncnn;
        if (ai) {
            p.aiFixed = ncnn ? AI_WEIGHTS * 2 : AI_WEIGHTS;
            p.tile = TILES[TILES.length - 1];
            for (int t : TILES) {
                long tile = ncnn ? ncnnWorkspace(t) : aiTile(t);
                if (p.total() - p.aiTiles + tile <= p.budget) { p.tile = t; break; }
            }
            p.aiTiles = ncnn ? ncnnWorkspace(p.tile) : aiTile(p.tile);
        }
        p.withinBudget = p.total() <= p.budget;
        p.fits = p.withinBudget;
    }
}
