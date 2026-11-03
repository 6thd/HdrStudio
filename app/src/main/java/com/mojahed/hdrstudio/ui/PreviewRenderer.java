package com.mojahed.hdrstudio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import com.mojahed.hdrstudio.ai.SrEngine;
import com.mojahed.hdrstudio.core.ColorMath;
import com.mojahed.hdrstudio.core.SourceInfo;
import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.gl.Tex;
import com.mojahed.hdrstudio.job.FrameSource;
import com.mojahed.hdrstudio.stages.FramePipeline;
import com.mojahed.hdrstudio.stages.PipelineParams;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-frame before/after preview on a worker thread. Both images go through the same
 * SDR display transform so they are comparable on any screen; for an HDR10 output the "after"
 * image is a tone-mapped SDR approximation (stated in the UI). Temporal filtering needs
 * previous frames and is therefore not visible in a single-frame preview.
 */
public final class PreviewRenderer {
    public static final class Result {
        public Bitmap before, after;
        public long ms;
        public String note = "";
        public String error;
    }

    public static Result render(Context ctx, Uri uri, SourceInfo src, PipelineParams base, long timeUs, boolean hdrOut, int outW, int outH) {
        Result r = new Result();
        Egl egl = null;
        GlKit k = null;
        FrameSource fs = null;
        FramePipeline pipe = null;
        SrEngine sr = null;
        try {
            egl = new Egl(false);
            egl.makeCurrentOffscreen();
            k = new GlKit(new ShaderLoader(ctx.getAssets()));
            PipelineParams p = base.copy();
            p.workDomain = src.isHdr() ? 1 : 0;
            p.outMode = hdrOut ? 0 : 1;
            p.temporal = false;
            p.outW = outW; p.outH = outH;
            if (p.ai) {
                sr = new SrEngine(k, ctx.getAssets());
                if (!sr.selfTest(ctx.getAssets())) throw new IllegalStateException("AI " + sr.status);
            }
            Tex in = k.tex(src.width, src.height, false);
            fs = new FrameSource(ctx, uri, src, src.isHdr() || src.bitDepth > 8, timeUs, Long.MAX_VALUE);
            if (!fs.next(k, in, timeUs, new AtomicBoolean())) throw new IllegalStateException("لا يوجد إطار عند هذا الموضع");
            r.before = toBitmap(k, in, p.workDomain, p.workDomain == 1 ? p.srcPeak : 0, false);
            long t0 = System.nanoTime();
            pipe = new FramePipeline(k, src.width, src.height, p, sr);
            Tex out = pipe.process(in, 1 / 30.0);
            k.read(out, 0, 0, 1, 1);    // finish GPU work before stopping the clock
            r.ms = (System.nanoTime() - t0) / 1000000;
            r.after = toBitmap(k, out, hdrOut ? 1 : 0, hdrOut ? p.peak : 0, true);
            r.note = hdrOut ? "المعاينة SDR تقريبية لملف سيُصدَّر HDR10 (الإضاءات الحقيقية تظهر في المقطع أو بعد التصدير)" : "";
        } catch (Throwable t) {
            r.error = t.getMessage();
        } finally {
            if (fs != null) fs.release();
            if (pipe != null) pipe.release();
            if (sr != null) sr.release();
            if (k != null) k.release();
            if (egl != null) egl.release();
        }
        return r;
    }

    /** domain 0: SDR code values; 1: PQ code values (tone mapped to SDR for display). */
    private static Bitmap toBitmap(GlKit k, Tex t, int domain, float pqPeak, boolean isFinalCodes) {
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
