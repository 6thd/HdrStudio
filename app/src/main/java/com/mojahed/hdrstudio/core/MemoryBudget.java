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
        /** ncnn only: Java heap the path holds at once, the heap available when planned, and whether it fits (a separate limit from RAM). */
        public long javaNeed, javaAvail = -1;
        public boolean javaFits = true;
        /** ncnn only: native RAM of the tiled path (reusable buffers, ncnn CPU mats, Vulkan staging), inside aiTiles. */
        public long nativeNeed;
        /** Why the plan does not fit, or empty. */
        public String reason = "";

        public long total() { return input + pipeline + history + aiFixed + aiTiles + scratch + cpu + decoder + encoder; }
        public long gpu() { return input + pipeline + history + aiFixed + aiTiles + scratch; }

        public String describe() {
            return String.format(Locale.US, "ذاكرة متوقعة: %d MB (GPU %d: معالجة %d · تاريخ زمني %d · AI %d%s · مفكك/مرمّز ~%d تقديراً%s) · الميزانية المحافظة %d MB%s",
                    mb(total()), mb(gpu()), mb(input + pipeline), mb(history), mb(aiFixed + aiTiles), tile > 0 ? " بلاطة " + tile : "",
                    mb(decoder + encoder), cpu > 0 ? " · صور " + mb(cpu) : "",
                    mb(budget), (ncnn ? String.format(Locale.US, " · ncnn: GPU/مساحة عمل %d + native %d MB تقدير · Java %d من المتاح %s MB",
                            mb(aiTiles - nativeNeed), mb(nativeNeed), mb(javaNeed), javaAvail < 0 ? "غير محدد" : String.valueOf(mb(javaAvail))) : "")
                            + (fits ? "" : " · لا يتسع — لن يُشغَّل: " + reason));
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

    /** The Java heap limit and use at planning time. UNLIMITED leaves the Java check out (JVM tests, GLSL). */
    public static final class JavaHeap {
        public static final JavaHeap UNLIMITED = new JavaHeap(Long.MAX_VALUE, 0);
        public final long max, used;
        public JavaHeap(long max, long used) { this.max = max; this.used = used; }
        public long avail() { return max == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, max - used); }
        /** This process now: Runtime.maxMemory() is the growth limit (largeHeap is not assumed). */
        public static JavaHeap current() {
            Runtime r = Runtime.getRuntime();
            return new JavaHeap(r.maxMemory(), r.totalMemory() - r.freeMemory());
        }
        public String describe() {
            return max == Long.MAX_VALUE ? "Java: غير محدد" : String.format(Locale.US, "Java: الحد %d MB · المستخدم %d MB · المتاح %d MB", mb(max), mb(used), mb(avail()));
        }
    }

    /** Share of the free Java heap the ncnn path may plan to hold (the rest is for the app and GC headroom). */
    public static final int JAVA_SHARE_PERCENT = 50;

    /**
     * Java heap the ncnn path holds at once per frame: GlKit.read's float[] of the RGBA network input and its
     * direct staging buffer (on ART a direct ByteBuffer lives in the Java heap), plus one window row.
     * No x4 frame: each tile core goes from native memory straight into the GL texture.
     */
    public static long ncnnJava(int lw, int lh, int t) {
        return 16L * lw * lh * 2 + 12L * Math.min(t + 2L * SR_PAD, lw) + 4096;
    }

    /**
     * Native RAM of the ncnn tiled path for tile t on an lw x lh input: the reusable window (RGB) and core
     * (RGBA x4) buffers, ncnn's CPU input Mat and its 48-channel c33 download, and the same two again as
     * Vulkan host-visible staging (assumed, not measured). JNI copies: none, the buffers are direct.
     */
    public static long ncnnNative(int lw, int lh, int t) {
        long fw = Math.min(t + 2L * SR_PAD, lw), fh = Math.min(t + 2L * SR_PAD, lh);
        long window = 12L * fw * fh;
        long core = 16L * (4L * Math.min(t, lw)) * (4L * Math.min(t, lh));
        long mats = 12L * fw * fh + 4L * 48 * fw * fh;
        return window + core + 2 * mats;
    }

    /** ncnn GPU workspace for the real window of an lw x lh input (two 64-channel FP32 blobs, input, c33), + 64 MB driver slack. */
    public static long ncnnWorkspace(int t, int lw, int lh) {
        long fw = Math.min(t + 2L * SR_PAD, lw), fh = Math.min(t + 2L * SR_PAD, lh);
        return 4L * fw * fh * (3 + 64 * 2 + 48) + (64L << 20);
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
        return plan(srcW, srcH, srcTenBit, outW, outH, outTenBit, temporal, ai, aiWorkScale, memAvail, memTotal, ncnn, JavaHeap.UNLIMITED);
    }

    /** As above, with the Java heap as its own limit for the ncnn path (pass JavaHeap.current() on the device). */
    public static Plan plan(int srcW, int srcH, boolean srcTenBit, int outW, int outH, boolean outTenBit,
                            boolean temporal, boolean ai, float aiWorkScale, long memAvail, long memTotal, boolean ncnn, JavaHeap java) {
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
        chooseTile(p, ai, memAvail, memTotal, ncnn, lw(srcW, aiWorkScale), lw(srcH, aiWorkScale), java);
        return p;
    }

    static int lw(int size, float aiWorkScale) { return Math.max(8, Math.round(size * aiWorkScale)); }

    /** RGBA32F download of the network input (GlKit.read: float[] + staging). The x4 result is uploaded per tile from native memory. */
    public static long transferBytes(int srcW, int srcH, float aiWorkScale) {
        int lw = Math.max(8, Math.round(srcW * aiWorkScale)), lh = Math.max(8, Math.round(srcH * aiWorkScale));
        return 16L * lw * lh * 2;
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
        return preview(srcW, srcH, srcTenBit, procW, procH, procOutW, procOutH, wantAnalysis, ai, aiWorkScale,
                visW, visH, hdrDisplay, memAvail, memTotal, ncnn, JavaHeap.UNLIMITED);
    }

    public static Plan preview(int srcW, int srcH, boolean srcTenBit,
                               int procW, int procH, int procOutW, int procOutH,
                               boolean wantAnalysis, boolean ai, float aiWorkScale,
                               int visW, int visH, boolean hdrDisplay,
                               long memAvail, long memTotal, boolean ncnn, JavaHeap java) {
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
        chooseTile(p, ai, memAvail, memTotal, ncnn, lw(procW, aiWorkScale), lw(procH, aiWorkScale), java);
        p.analysis = false;
        if (wantAnalysis && analysisPipe > 0) {
            long saved = p.pipeline;
            p.pipeline = Math.max(saved, analysisPipe);
            if (p.total() <= p.budget) p.analysis = true;
            else p.pipeline = saved;   // drop whole-frame statistics so the crop itself can still run
        }
        p.withinBudget = p.total() <= p.budget;
        p.fits = p.withinBudget && p.javaFits;
        return p;
    }

    /**
     * Conservative budget is the operating limit. 85% of free RAM is recorded, not a second gate.
     * ncnn: the tile is the largest of TILES for which RAM (GPU workspace + native buffers + the rest of the
     * plan) fits the budget AND the Java part fits its own share of the free Java heap. The tile shrinks with
     * memory; the work resolution and the precision mode never change here.
     */
    static void chooseTile(Plan p, boolean ai, long memAvail, long memTotal, boolean ncnn, int lw, int lh, JavaHeap java) {
        p.budget = budget(memAvail, memTotal);
        p.hardLimit = memAvail > 0 ? memAvail * 85 / 100 : 0;
        p.ncnn = ai && ncnn;
        if (ai) {
            p.aiFixed = ncnn ? AI_WEIGHTS * 2 : AI_WEIGHTS;
            long javaShare = java.avail() == Long.MAX_VALUE ? Long.MAX_VALUE : java.avail() * JAVA_SHARE_PERCENT / 100;
            p.javaAvail = java.avail() == Long.MAX_VALUE ? -1 : java.avail();
            p.tile = 0;
            for (int t : TILES) {
                long tile = ncnn ? ncnnWorkspace(t, lw, lh) + ncnnNative(lw, lh, t) : aiTile(t);
                boolean ram = p.total() - p.aiTiles + tile <= p.budget;
                boolean jv = !ncnn || ncnnJava(lw, lh, t) <= javaShare;
                if (ram && jv) { p.tile = t; break; }
            }
            int t = p.tile > 0 ? p.tile : TILES[TILES.length - 1];
            p.nativeNeed = ncnn ? ncnnNative(lw, lh, t) : 0;
            p.aiTiles = ncnn ? ncnnWorkspace(t, lw, lh) + p.nativeNeed : aiTile(t);
            p.javaNeed = ncnn ? ncnnJava(lw, lh, t) : 0;
            p.javaFits = !ncnn || p.javaNeed <= javaShare;
            if (p.tile == 0) p.tile = t;
        }
        p.withinBudget = p.total() <= p.budget;
        p.fits = p.withinBudget && p.javaFits;
        p.reason = !p.javaFits ? String.format(Locale.US, "Java heap: يلزم %d MB والمسموح %d%% من المتاح %d MB", mb(p.javaNeed), JAVA_SHARE_PERCENT, mb(p.javaAvail))
                : !p.withinBudget ? "فوق الميزانية المحافظة لذاكرة النظام" : "";
    }

}
