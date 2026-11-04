package com.mojahed.hdrstudio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import com.mojahed.hdrstudio.ai.NcnnSr;
import com.mojahed.hdrstudio.ai.SrEngine;
import com.mojahed.hdrstudio.core.ColorMath;
import com.mojahed.hdrstudio.core.MemoryBudget;
import com.mojahed.hdrstudio.core.PreviewCrop;
import com.mojahed.hdrstudio.core.SourceInfo;
import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.gl.Tex;
import com.mojahed.hdrstudio.job.FrameSource;
import com.mojahed.hdrstudio.stages.FramePipeline;
import com.mojahed.hdrstudio.stages.PipelineParams;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-frame before/after preview on a worker thread. Both images go through the same
 * SDR display transform so they are comparable on any screen; for an HDR10 output the "after"
 * image is a tone-mapped SDR approximation (stated in the UI). Temporal filtering needs
 * previous frames and is therefore disabled here — a 3-second clip uses the export's full settings.
 * Crop mode (core.PreviewCrop): a source-resolution window plus context margin is processed at the
 * export's aligned scale. With whole-frame statistics this matches the same region of a full-frame
 * spatial run (harness test preview_crop); it is not a claim about temporal export.
 */
public final class PreviewRenderer {
    /** Visible crop size in output pixels. */
    public static final int CROP_W = 960, CROP_H = 540;

    public static final class Result {
        public Bitmap before, after;
        public long ms, analysisMs;
        public String note = "";
        public String error;
        public PreviewCrop crop;
        public MemoryBudget.Plan mem;
    }

    /**
     * @param outW,outH  the export's aligned output size (EncodeConfig), not the pre-alignment planDims
     * @param memAvail,memTotal  same figures DeviceCaps reports to the export
     */
    public static Result render(Context ctx, Uri uri, SourceInfo src, PipelineParams base, long timeUs,
                                boolean hdrOut, int outW, int outH, boolean crop, long memAvail, long memTotal) {
        Result r = new Result();
        Egl egl = null;
        GlKit k = null;
        FrameSource fs = null;
        FramePipeline pipe = null;
        SrEngine sr = null;
        NcnnSr ncnn = null;
        try {
            PipelineParams p = base.copy();
            p.applySource(src, hdrOut);
            p.temporal = false;
            p.outW = outW; p.outH = outH;
            boolean ten = src.isHdr() || src.bitDepth > 8;
            PreviewCrop g = crop ? PreviewCrop.plan(src.width, src.height, outW, outH, p.ai, p.aiWorkScale, p.deband ? p.debandRadius : 0,
                    0.5, 0.5, CROP_W, CROP_H) : null;
            boolean full = g == null || g.fullFrame;
            int procW = full ? src.width : g.w, procH = full ? src.height : g.h;
            int procOutW = full ? outW : g.outW, procOutH = full ? outH : g.outH;
            int visW = full ? outW : g.visW, visH = full ? outH : g.visH;
            MemoryBudget.Plan mem = MemoryBudget.preview(src.width, src.height, ten,
                    procW, procH, procOutW, procOutH, !full, p.ai, p.aiWorkScale,
                    visW, visH, hdrOut, memAvail, memTotal, p.ai && p.aiNcnn);
            r.mem = mem;
            if (!mem.fits)
                throw new IllegalStateException("المعاينة فوق الميزانية المحافظة.\n" + mem.describe());
            if (p.ai) p.aiTile = mem.tile;

            egl = new Egl(false);
            egl.makeCurrentOffscreen();
            k = new GlKit(new ShaderLoader(ctx.getAssets()));
            if (p.ai && p.aiNcnn) {
                ncnn = NcnnSr.open(ctx.getAssets(), true);
                if (!ncnn.loaded || !ncnn.fullVulkan)
                    throw new IllegalStateException("ncnn/Vulkan غير متاح: " + ncnn.reason + "\nاختر محرك GLSL.");
            } else if (p.ai) {
                sr = new SrEngine(k, ctx.getAssets());
                if (!sr.selfTest(ctx.getAssets())) throw new IllegalStateException("AI " + sr.status);
            }
            Tex in = k.tex(src.width, src.height, false);
            fs = new FrameSource(ctx, uri, src, ten, timeUs, Long.MAX_VALUE);
            if (!fs.next(k, in, timeUs, new AtomicBoolean())) throw new IllegalStateException("لا يوجد إطار عند هذا الموضع");
            String hdrNote = hdrOut ? "المعاينة SDR تقريبية لملف سيُصدَّر HDR10 (الإضاءات الحقيقية تظهر في المقطع أو بعد التصدير). " : "";
            String temporalNote = "التنظيف الزمني يحتاج إطارات سابقة ولا يظهر في إطار واحد؛ مقطع الـ 3 ثوانٍ يستخدم إعداد التصدير كاملاً. ";
            if (full) {
                r.before = toBitmap(k, in, p.workDomain, p.workDomain == 1 ? p.srcPeak : 0);
                long t0 = System.nanoTime();
                pipe = new FramePipeline(k, src.width, src.height, p, sr);
                pipe.setNcnn(ncnn);
                Tex out = pipe.process(in, 1 / 30.0);
                k.read(out, 0, 0, 1, 1);
                r.ms = (System.nanoTime() - t0) / 1000000;
                r.after = toBitmap(k, out, hdrOut ? 1 : 0, hdrOut ? p.peak : 0);
                r.note = (crop && g == null ? "لا توجد شبكة بكسل مشتركة بين الدقتين تسمح بقص مطابق؛ عُرض الإطار كاملاً. " : "")
                        + temporalNote + hdrNote + mem.describe();
            } else {
                r.crop = g;
                FramePipeline.Region rg = null;
                if (mem.analysis) {
                    long ta = System.nanoTime();
                    PipelineParams an = p.copy();
                    an.ai = false; an.sharpen = 0; an.outW = src.width; an.outH = src.height;
                    FramePipeline analysis = new FramePipeline(k, src.width, src.height, an, null);
                    analysis.process(in, 1 / 30.0);
                    rg = new FramePipeline.Region(g.x0, g.y0, analysis.info.sigma, analysis.info.sigmaPost, analysis.info.expand);
                    analysis.release();
                    r.analysisMs = (System.nanoTime() - ta) / 1000000;
                }
                Tex b = crop(k, in, g.srcX, g.srcY, g.srcW, g.srcH);
                r.before = toBitmap(k, b, p.workDomain, p.workDomain == 1 ? p.srcPeak : 0);
                k.delete(b);
                Tex ct = crop(k, in, g.x0, g.y0, g.w, g.h);
                long t0 = System.nanoTime();
                PipelineParams cp = p.copy();
                cp.outW = g.outW; cp.outH = g.outH;
                pipe = new FramePipeline(k, g.w, g.h, cp, sr);
                pipe.setNcnn(ncnn);
                if (rg != null) pipe.setRegion(rg);
                Tex out = pipe.process(ct, 1 / 30.0);
                k.read(out, 0, 0, 1, 1);
                r.ms = (System.nanoTime() - t0) / 1000000;
                k.delete(ct);
                Tex vis = crop(k, out, g.visX, g.visY, g.visW, g.visH);
                r.after = toBitmap(k, vis, hdrOut ? 1 : 0, hdrOut ? p.peak : 0);
                k.delete(vis);
                String match = mem.analysis
                        ? String.format(Locale.US, "قص 1:1 من وسط الإطار: %d×%d من المصدر ← %d×%d في الناتج (أبعاد التصدير المحاذاة %d×%d)، عولج بهامش سياق %d بكسل وبإحصاءات الإطار كاملاً. يطابق نفس المنطقة من إطار كامل بنفس الإعدادات المكانية فقط (بدون تنظيف زمني). ",
                                g.srcW, g.srcH, g.visW, g.visH, outW, outH, g.margin)
                        : String.format(Locale.US, "قص 1:1 من وسط الإطار: %d×%d ← %d×%d بهامش %d بكسل. أُسقط تحليل الإطار كاملاً لضيق الذاكرة؛ الإحصاءات من القص نفسه فلا يُضمن التطابق مع إطار كامل. ",
                                g.srcW, g.srcH, g.visW, g.visH, g.margin);
                r.note = match + temporalNote + hdrNote + mem.describe();
            }
        } catch (Throwable t) {
            r.error = t.getMessage();
        } finally {
            if (fs != null) fs.release();
            if (pipe != null) pipe.release();
            if (ncnn != null) ncnn.close();
            if (sr != null) sr.release();
            if (k != null) k.release();
            if (egl != null) egl.release();
        }
        return r;
    }

    private static Tex crop(GlKit k, Tex src, int x, int y, int w, int h) {
        Tex t = k.tex(w, h, false);
        Map<String, Object> u = new HashMap<>();
        u.put("uIn", src); u.put("uOffset", new int[]{x, y});
        k.run("crop.frag", t, u);
        return t;
    }

    /** domain 0: SDR code values; 1: PQ code values (tone mapped to SDR for display). */
    private static Bitmap toBitmap(GlKit k, Tex t, int domain, float pqPeak) {
        Tex src = t;
        Tex tmp = null;
        if (domain == 1) {
            tmp = k.tex(t.w, t.h, false);
            Map<String, Object> u = new HashMap<>();
            u.put("uIn", t); u.put("uInDomain", 1); u.put("uOutMode", 1); u.put("uSdrGamma", 1);
            u.put("uSrcPeak", Math.max(pqPeak, 400f)); u.put("uSdrPeakNits", 250f);
            u.put("uRefWhite", 203f); u.put("uPeak", 1000f); u.put("uExpand", 0f); u.put("uKnee", 0.5f); u.put("uVibrance", 0f); u.put("uSkinProtect", 0f);
            k.run("hdr_map.frag", tmp, u);
            src = tmp;
        }
        Tex t8 = k.tex8(t.w, t.h);
        Map<String, Object> u = new HashMap<>();
        u.put("uIn", src); u.put("uComp", ColorMath.IDENTITY3); u.put("uCompOffset", new float[3]); u.put("uLsb", 1f / 255); u.put("uDither", false); u.put("uFlipY", false);
        k.run("present.frag", t8, u);
        int[] px = k.readArgbTopDown(t8);
        k.delete(t8);
        if (tmp != null) k.delete(tmp);
        return Bitmap.createBitmap(px, t.w, t.h, Bitmap.Config.ARGB_8888);
    }
}
