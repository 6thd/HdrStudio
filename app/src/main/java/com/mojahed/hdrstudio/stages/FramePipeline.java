package com.mojahed.hdrstudio.stages;

import android.opengl.GLES20;

import com.mojahed.hdrstudio.ai.NcnnSr;
import com.mojahed.hdrstudio.ai.SrEngine;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.Tex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-frame processing chain (GL thread). Mirrors FramePipeline in tools/harness/engine.js -
 * same shaders, same order, same decisions - so the desktop tests cover this logic.
 *
 *  input (E_SDR or E_PQ) -> deband -> [noise estimate] -> spatial denoise -> luma pyramid ->
 *  scene-cut analysis -> motion estimation -> { AI path: LR resize -> SR tiles -> blend ->
 *  temporal } | { classic path: temporal -> resize } -> sharpen -> hdr_map -> final (FP16 codes)
 */
public final class FramePipeline {
    public static final class Info {
        public boolean cut; public double sigma, sigmaPost, expand = Double.NaN, cutD, cutHd;
        public int tiles; public long aiMs, aiReadMs, aiExtractMs, aiUploadMs, totalMs;
    }

    /** Called between AI tiles (cancel / thermal checks); may throw to stop the frame. */
    public interface TileGate { void between(int done, int total); }

    /**
     * The input is a crop of a larger frame (preview, core.PreviewCrop): frame statistics come from
     * the whole frame and position-hashed patterns use the crop origin, so the crop is processed
     * exactly like the same pixels of a full-frame run.
     */
    public static final class Region {
        public final int x, y;
        public final double sigma, sigmaPost, expand;
        public Region(int x, int y, double sigma, double sigmaPost, double expand) {
            this.x = x; this.y = y; this.sigma = sigma; this.sigmaPost = sigmaPost; this.expand = expand;
        }
    }

    private final GlKit k;
    private final PipelineParams p;
    private final SrEngine sr;
    private NcnnSr ncnn;
    public final int srcW, srcH, outW, outH;
    private final Tex a, b, zero;
    private Tex[] lv = new Tex[4], lvPrev = new Tex[4];
    private final Tex[] mv = new Tex[4];
    private Tex mvMed, mvFinal;
    private Tex tmpH, o1, o2, lrTmp, lr, aiOut;
    private TileGate gate;
    private Region region;
    private final Tex fin;
    private final Tex[] hist = new Tex[2];
    private Tex histCur;
    private int histIdx;
    private final SceneAnalyzer scene = new SceneAnalyzer();
    private double sigma = 0.004, sigmaPost = 0.004;
    private int frame;
    private boolean lastCut = true;
    private final Map<String, Object> u = new HashMap<>();
    public final Info info = new Info();

    /**
     * Allocates only what the enabled stages use (see core.MemoryBudget, which mirrors this):
     * a, b at source size; luma pyramid; motion search + previous pyramid only with temporal;
     * tmpH/o1 only when resizing; o2 for the AI blend or as the second output-size scratch.
     */
    public FramePipeline(GlKit k, int srcW, int srcH, PipelineParams p, SrEngine sr) {
        this.k = k; this.p = p; this.sr = sr; this.srcW = srcW; this.srcH = srcH;
        this.outW = p.outW; this.outH = p.outH;
        a = k.tex(srcW, srcH, false); b = k.tex(srcW, srcH, false);
        zero = k.tex(1, 1, true, GlKit.floats(new float[4]));
        int w = srcW, h = srcH;
        for (int i = 0; i < 4; i++) {
            w = (w + 1) / 2; h = (h + 1) / 2;
            lv[i] = k.tex(w, h, false);
            if (p.temporal) { lvPrev[i] = k.tex(w, h, false); mv[i] = k.tex(w, h, true); }
        }
        if (p.temporal) { mvMed = k.tex(lv[1].w, lv[1].h, true); mvFinal = k.tex(lv[0].w, lv[0].h, true); }
        boolean resized = outW != srcW || outH != srcH;
        if (resized) { tmpH = k.tex(outW, srcH, false); o1 = k.tex(outW, outH, false); }
        if (resized || p.ai) o2 = k.tex(outW, outH, false);
        if (p.ai) {
            int lw = Math.max(8, Math.round(srcW * p.aiWorkScale)), lh = Math.max(8, Math.round(srcH * p.aiWorkScale));
            if (p.aiWorkScale != 1f) { lrTmp = k.tex(lw, srcH, false); lr = k.tex(lw, lh, false); }
            aiOut = k.tex(outW, outH, false);
        }
        fin = k.tex(outW, outH, true);
    }

    public Tex output() { return fin; }

    public void setTileGate(TileGate g) { gate = g; }

    public void setNcnn(NcnnSr engine) { ncnn = engine; }

    public void setRegion(Region r) { region = r; }

    private Map<String, Object> U() { u.clear(); return u; }

    private void resize(Tex src, Tex dst, Tex tmp) {
        Map<String, Object> m = U();
        m.put("uIn", src); m.put("uDir", 0); m.put("uScale", (float) tmp.w / src.w); m.put("uLobes", 3f); m.put("uAntiring", 0.8f);
        k.run("resize.frag", tmp, m);
        m = U();
        m.put("uIn", tmp); m.put("uDir", 1); m.put("uScale", (float) dst.h / tmp.h); m.put("uLobes", 3f); m.put("uAntiring", 0.8f);
        k.run("resize.frag", dst, m);
    }

    /** Immerkaer noise estimate: 8x8 block means of |N*Y|, 20th percentile of full blocks. */
    private double estimateNoise(Tex src) {
        int w = (src.w + 7) / 8, h = (src.h + 7) / 8;
        Tex r = k.tex(w, h, true);
        Map<String, Object> m = U();
        m.put("uIn", src); m.put("uMode", 2); m.put("uDomain", p.workDomain);
        k.run("reduce.frag", r, m);
        float[] px = k.read(r);
        k.delete(r);
        float[] vals = new float[w * h];
        int n = 0;
        for (int i = 0; i < w * h; i++) if (px[i * 4 + 2] > 0.99f) vals[n++] = px[i * 4 + 1];
        if (n == 0) return 0;
        java.util.Arrays.sort(vals, 0, n);
        return vals[(int) (n * 0.2)] * Math.sqrt(Math.PI / 2) / 6;
    }

    private float[] readThumb() {
        float[] raw = k.read(lv[3]);
        float[] t = new float[lv[3].w * lv[3].h];
        for (int i = 0; i < t.length; i++) t[i] = raw[i * 4];
        return t;
    }

    /** Scratch buffer with the same size as 'like' that is neither x nor y. */
    private Tex pick(Tex like, Tex x, Tex y) {
        for (Tex t : new Tex[]{a, b, o1, o2})
            if (t != null && t.w == like.w && t.h == like.h && t != x && t != y) return t;
        throw new IllegalStateException("no free " + like.w + "x" + like.h + " buffer");
    }

    /** @param analysisOnly skip expensive stages not needed to measure light levels (never used for AI). */
    public Tex process(Tex input, double dtSec) {
        long t0 = System.nanoTime();
        Tex cur = input;
        Map<String, Object> m;
        if (p.deband) {
            m = U(); m.put("uIn", cur); m.put("uThreshold", p.workDomain == 0 ? p.debandThreshold : p.debandThreshold * 0.25f);
            m.put("uRadius", p.debandRadius); m.put("uIterations", 2);
            m.put("uOrigin", region == null ? new float[2] : new float[]{region.x, region.y});
            k.run("deband.frag", a, m); cur = a;
        }
        if (region != null) sigma = region.sigma;
        else if (frame % 8 == 0 || lastCut) sigma = estimateNoise(cur);
        info.sigma = sigma;
        if (p.denoiseLuma > 0 || p.denoiseChroma > 0) {
            Tex dst = cur == a ? b : a;
            m = U(); m.put("uIn", cur); m.put("uSigma", (float) sigma); m.put("uLumaAmount", p.denoiseLuma);
            m.put("uChromaAmount", p.denoiseChroma); m.put("uGrainKeep", p.grainKeep); m.put("uDomain", p.workDomain);
            k.run("denoise.frag", dst, m); cur = dst;
            if (region != null) sigmaPost = region.sigmaPost;
            else if (frame % 8 == 0 || lastCut) sigmaPost = estimateNoise(cur);
        } else sigmaPost = sigma;
        info.sigmaPost = sigmaPost;

        if (p.temporal) { Tex[] t = lv; lv = lvPrev; lvPrev = t; }
        m = U(); m.put("uIn", cur); m.put("uFromRgb", true); m.put("uDomain", p.workDomain);
        k.run("luma_down.frag", lv[0], m);
        for (int i = 1; i < 4; i++) { m = U(); m.put("uIn", lv[i - 1]); m.put("uFromRgb", false); k.run("luma_down.frag", lv[i], m); }
        float[] thumb = readThumb();
        boolean cut = scene.isCut(thumb);
        info.cut = cut; info.cutD = scene.lastD; info.cutHd = scene.lastHd;
        lastCut = cut;
        boolean temporal = p.temporal && p.temporalStrength > 0 && !cut;
        if (temporal) {
            me(3, zero, false, false, 4, 2, 0.002f);
            me(2, mv[3], true, true, 1, 2, 0.002f);
            me(1, mv[2], true, true, 1, 2, 0.004f);
            m = U(); m.put("uIn", mv[1]); k.run("mv_median.frag", mvMed, m);
            me(0, mvMed, true, false, 1, 1, 0.004f);
            m = U(); m.put("uIn", mv[0]); k.run("mv_median.frag", mvFinal, m);
        }
        if (p.workDomain == 0 && p.outMode == 0)
            info.expand = region != null ? region.expand : scene.adaptExpand(thumb, p.expand, p.knee, dtSec, cut, p.sdrGamma);
        boolean resized = outW != srcW || outH != srcH;
        Tex res;
        if (p.ai && p.aiNcnn) {
            if (ncnn == null || !ncnn.loaded)
                throw new IllegalStateException("محرك ncnn/Vulkan غير جاهز" + (ncnn == null ? "" : ": " + ncnn.reason) + ". اختر محرك GLSL.");
            long ta = System.nanoTime();
            Tex lrIn = cur;
            if (lr != null) { resize(cur, lr, lrTmp); lrIn = lr; }
            GLES20.glFinish();
            long tr0 = System.nanoTime();
            float[] px = k.read(lrIn);
            info.aiReadMs = (System.nanoTime() - tr0) / 1000000;
            int n = lrIn.w * lrIn.h;
            float[] rgb = new float[n * 3];
            for (int i = 0; i < n; i++) { rgb[i * 3] = px[i * 4]; rgb[i * 3 + 1] = px[i * 4 + 1]; rgb[i * 3 + 2] = px[i * 4 + 2]; }
            NcnnSr.Forward f = ncnn.forward(rgb, lrIn.w, lrIn.h, p.aiTile, gate);
            info.aiExtractMs = f.extractNs / 1_000_000L;
            info.tiles = f.tiles;
            if (!f.fullVulkan) throw new IllegalStateException("ncnn ليس Vulkan بالكامل: " + ncnn.reason);
            int hrW = lrIn.w * 4, hrH = lrIn.h * 4;
            float[] rgba = new float[hrW * hrH * 4];
            for (int i = 0; i < hrW * hrH; i++) { rgba[i * 4] = f.rgb[i * 3]; rgba[i * 4 + 1] = f.rgb[i * 3 + 1]; rgba[i * 4 + 2] = f.rgb[i * 3 + 2]; rgba[i * 4 + 3] = 1f; }
            FloatBuffer fb = ByteBuffer.allocateDirect(rgba.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(rgba).position(0);
            long tu = System.nanoTime();
            if (aiOut.w == hrW && aiOut.h == hrH) k.upload(aiOut, fb);
            else {
                Tex big = k.tex(hrW, hrH, true, fb);
                m = U();
                m.put("uSR", big); m.put("uSrOrigin", new float[]{0f, 0f});
                m.put("uScale", new float[]{aiOut.w / (float) hrW, aiOut.h / (float) hrH});
                m.put("uSrValid", new int[]{hrW, hrH});
                k.run("sr_resample.frag", aiOut, m);
                k.delete(big);
            }
            GLES20.glFinish();
            info.aiUploadMs = (System.nanoTime() - tu) / 1000000;
            info.aiMs = (System.nanoTime() - ta) / 1000000;
            Tex base = cur;
            if (resized) { resize(cur, o1, tmpH); base = o1; }
            m = U(); m.put("uBase", base); m.put("uAi", aiOut); m.put("uAmount", p.aiAmount); m.put("uSkinProtect", p.aiSkinProtect);
            k.run("ai_blend.frag", o2, m);
            res = o2;
            if (temporal) res = temporalPass(res, (double) outW / srcW);
            if (p.temporal) keepHistory(res);
        } else if (p.ai && sr != null) {
            long ta = System.nanoTime();
            Tex lrIn = cur;
            if (lr != null) { resize(cur, lr, lrTmp); lrIn = lr; }
            final TileGate g = gate;
            info.tiles = sr.run(lrIn, aiOut, p.aiTile, g == null ? null : new SrEngine.TileCallback() {
                @Override public void onTile(int done, int total) { if (done < total) { k.flush(); g.between(done, total); } }
            });
            Tex base = cur;
            if (resized) { resize(cur, o1, tmpH); base = o1; }
            m = U(); m.put("uBase", base); m.put("uAi", aiOut); m.put("uAmount", p.aiAmount); m.put("uSkinProtect", p.aiSkinProtect);
            k.run("ai_blend.frag", o2, m);
            res = o2;
            info.aiMs = (System.nanoTime() - ta) / 1000000;
            if (temporal) res = temporalPass(res, (double) outW / srcW);
            if (p.temporal) keepHistory(res);          // AI path: temporal + history at OUTPUT resolution
        } else {
            // classic path: temporal + history at SOURCE resolution, then resize
            res = cur;
            if (temporal) res = temporalPass(res, 1);
            if (p.temporal) keepHistory(res);
            if (resized) { resize(res, o1, tmpH); res = o1; }
        }
        if (p.sharpen > 0) {
            Tex dst = pick(res, res, histCur);
            m = U(); m.put("uIn", res); m.put("uAmount", p.sharpen); m.put("uSigma", (float) sigmaPost);
            m.put("uSkinProtect", p.sharpenSkinProtect); m.put("uDomain", p.workDomain);
            k.run("sharpen.frag", dst, m); res = dst;
        }
        m = U();
        m.put("uIn", res); m.put("uInDomain", p.workDomain); m.put("uOutMode", p.outMode); m.put("uSdrGamma", p.sdrGamma);
        m.put("uRefWhite", p.refWhite); m.put("uPeak", p.peak);
        m.put("uExpand", Double.isNaN(info.expand) ? p.expand : (float) info.expand);
        m.put("uKnee", p.knee); m.put("uVibrance", p.vibrance); m.put("uSkinProtect", p.hdrSkinProtect);
        m.put("uSrcPeak", p.srcPeak); m.put("uSdrPeakNits", p.sdrPeakNits);
        k.run("hdr_map.frag", fin, m);
        frame++;
        info.totalMs = (System.nanoTime() - t0) / 1000000;
        return fin;
    }

    private void me(int level, Tex coarse, boolean hasCoarse, boolean neighbours, int radius, int half, float lambda) {
        Map<String, Object> m = U();
        m.put("uCur", lv[level]); m.put("uPrev", lvPrev[level]); m.put("uCoarse", coarse); m.put("uHasCoarse", hasCoarse);
        m.put("uNeighbours", neighbours); m.put("uRadius", radius); m.put("uHalf", half); m.put("uLambda", lambda);
        k.run("me_search.frag", mv[level], m);
    }

    private Tex temporalPass(Tex cur, double mvToTarget) {
        if (histCur == null) return cur;
        if (histCur.w != cur.w || histCur.h != cur.h) throw new IllegalStateException("temporal history size mismatch");
        Tex dst = pick(cur, cur, histCur);
        Map<String, Object> m = U();
        m.put("uCur", cur); m.put("uPrev", histCur); m.put("uMv", mvFinal); m.put("uMvScale", (float) (2 * mvToTarget));
        m.put("uSigma", (float) sigmaPost); m.put("uStrength", p.temporalStrength); m.put("uClampK", 1.5f); m.put("uDomain", p.workDomain);
        k.run("temporal.frag", dst, m);
        return dst;
    }

    private void keepHistory(Tex res) {
        if (hist[0] == null) { hist[0] = k.tex(res.w, res.h, false); hist[1] = k.tex(res.w, res.h, false); }
        Tex dst = hist[histIdx];
        histIdx ^= 1;
        Map<String, Object> m = U(); m.put("uIn", res);
        k.run("copy.frag", dst, m);
        histCur = dst;
    }

    /** MaxCLL/MaxFALL contribution of the last output frame: {max nits, mean nits}. Exact GPU reduction. */
    public double[] measureLight() {
        Tex cur = fin;
        boolean first = true;
        Tex owned = null;
        do {
            int w = (cur.w + 7) / 8, h = (cur.h + 7) / 8;
            Tex nx = k.tex(w, h, true);
            Map<String, Object> m = U(); m.put("uIn", cur); m.put("uMode", first ? 1 : 0); m.put("uDomain", 1);
            k.run("reduce.frag", nx, m);
            if (owned != null) k.delete(owned);
            owned = nx; cur = nx; first = false;
        } while (cur.w > 16 || cur.h > 16);
        float[] px = k.read(cur);
        k.delete(owned);
        double mx = 0, sum = 0, ws = 0;
        for (int i = 0; i < cur.w * cur.h; i++) { mx = Math.max(mx, px[i * 4]); sum += px[i * 4 + 1] * px[i * 4 + 2]; ws += px[i * 4 + 2]; }
        return new double[]{mx, ws > 0 ? sum / ws : 0};
    }

    public void release() {
        Tex[] all = {a, b, zero, mvMed, mvFinal, tmpH, o1, o2, lrTmp, lr, aiOut, fin, hist[0], hist[1]};
        for (Tex t : all) k.delete(t);
        for (int i = 0; i < 4; i++) { k.delete(lv[i]); k.delete(lvPrev[i]); k.delete(mv[i]); }
    }
}
