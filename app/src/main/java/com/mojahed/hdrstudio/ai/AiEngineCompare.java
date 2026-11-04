package com.mojahed.hdrstudio.ai;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.GLES20;
import android.os.Debug;
import android.os.PowerManager;

import com.mojahed.hdrstudio.core.MemoryBudget;
import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.gl.Tex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Phone-side check of the ncnn tiled path ({@link NcnnTileCheck}) for CPU FP32, Vulkan FP32
 * and Vulkan FP16, then, only if all pass, GLSL vs the ncnn export path on a large frame.
 * Does not export, does not need HDR, and does not change the default engine (GLSL).
 * Desktop numbers must not be reported as Maleoon results.
 */
public final class AiEngineCompare {
    public interface Progress { void onProgress(String line); }

    /** Proposed before any default change. The report records them; it does not flip the engine. */
    public static final double PROPOSED_MAX_ABS = 0.02;
    public static final double PROPOSED_PSNR_DB = 40.0;

    private AiEngineCompare() {}

    public static String run(Context ctx) { return run(ctx, null); }

    public static String run(Context ctx, Progress progress) {
        StringBuilder r = new StringBuilder();
        r.append("فحص المسار المقسّم لـ ncnn — ليس نتيجة تصدير HDR\n");
        r.append("الجهاز: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" API ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        r.append("النموذج: realesr-general-x4v3 SRVGGNetCompact · الأوزان sha256=b21527dda6ac91a00574295259bbbd5690afa9334ca49416ed35244ee1a512d3 (لم تتغير)\n");
        r.append("ncnn: ").append(NcnnSr.NCNN_TAG).append(" · INT8 مغلق · صف 0 = أسفل GL\n");
        r.append(String.format(Locale.US,
                "التقسيم: نافذة سياق من الصورة الحقيقية فقط بطول min(T+2P، البعد)، P=%d ≥ مجال الاستقبال %d. لا هامش أسود: عند حدود الصورة تحشو الشبكة كل طبقة بنفسها كما في المباشر. إزاحة القص = (x0−cx0، y0−cy0)×4 من النافذة الفعلية.\n",
                NcnnSr.P, TilePlan.RECEPTIVE_RADIUS));
        r.append(String.format(Locale.US, "الحدود (لم تُوسَّع): maxAbs≤%.3f و PSNR≥%.0f dB لكل منطقة. المناطق على خرج ×4: زوايا وحواف (%d px من الحد) وفواصل (%d px من حد بلاطة داخلي) ومركز.\n",
                PROPOSED_MAX_ABS, PROPOSED_PSNR_DB, NcnnTileCheck.EDGE, NcnnTileCheck.SEAM));
        r.append("القبول لكل وضع على حدة: فشل وضع لا يمنع قياس وضع نجح. FP16 الكامل (تخزين وحساب) فشل في 2.3.3 ولا يُعاد هنا.\n");
        r.append("كل وضع يُرفض إن غيّر ncnn إعدادات الدقة المطلوبة على هذا الجهاز؛ لا رجوع صامت إلى FP16.\n");
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        java.util.List<NcnnSr.Opts> accepted = new java.util.ArrayList<>();
        try {
            java.util.List<NcnnTileCheck.Case> cases = NcnnTileCheck.load(ctx.getAssets());
            NcnnSr.Opts[] modes = {NcnnSr.Opts.cpuFp32(), NcnnSr.Opts.vulkanFp32(), NcnnSr.Opts.vulkanFp16Storage()};
            NcnnTileCheck.Result cpu = null;
            for (NcnnSr.Opts o : modes) {
                step(progress, "تحميل " + o.label());
                NcnnSr net = NcnnSr.open(ctx.getAssets(), o);
                try {
                    r.append('\n').append(o.label()).append(": ").append(net.summary()).append('\n');
                    NcnnTileCheck.Result res = NcnnTileCheck.run(r, net, cases, o.vulkan ? cpu : null, progress);
                    if (!o.vulkan) cpu = res;
                    r.append("قبول ").append(o.label()).append(res.pass ? ": ناجح\n" : ": فاشل\n");
                    if (res.pass && o.vulkan) accepted.add(o);
                    if (!res.pass && o.vulkan && net.loaded) {
                        step(progress, "تشخيص الطبقات " + o.label());
                        NcnnSr ref = NcnnSr.open(ctx.getAssets(), NcnnSr.Opts.cpuFp32());
                        try { if (ref.loaded) NcnnTileCheck.firstDivergentLayer(r, ref, net, cases); } finally { ref.close(); }
                    }
                } finally {
                    net.close();
                }
            }
            cpu = null;   // the comparison outputs are not needed by the timing below
        } catch (Throwable t) {
            r.append("توقف الفحص:\n").append(chain(t)).append(MemoryBudget.JavaHeap.current().describe()).append('\n');
        }
        r.append('\n');
        if (accepted.isEmpty()) {
            r.append("لا وضع Vulkan مقبول. قياس الأداء لم يُشغَّل.\n");
        } else {
            r.append("أوضاع Vulkan المقبولة للقياس التجريبي:");
            for (NcnnSr.Opts o : accepted) r.append(' ').append(o.label()).append(';');
            r.append('\n');
            perf(ctx, r, pm, accepted, progress);
        }
        r.append("الافتراضي يبقى GLSL. التصدير التجريبي عبر ncnn يستخدم ").append(NcnnSr.Opts.exportMode().label())
                .append(". هذا الزر لا يغيّر أي إعداد. الأرقام من هذا التشغيل على هذا الجهاز فقط.\n");
        return r.toString();
    }

    static final int WARMUP = 1, REPS = 5;

    /**
     * 1080p at workScale 0.5: the network input is 960x540, the output 3840x2160 (x4).
     * Both engines: the same input, the same tile core T (the largest that fits BOTH engines' memory plans),
     * from an RGBA16F texture to an RGBA16F texture. GLSL: sr.run + glFinish. ncnn: GL read, tiled forward
     * (gather, CPU pack, extract with its transfer and sync, PixelShuffle + residual of the core only), each
     * core uploaded to its place in the texture (glTexSubImage2D), glFinish. No x4 frame in Java memory.
     * Warm-up, then REPS runs interleaved GLSL/ncnn; median, min, max. No GC calls.
     */
    private static void perf(Context ctx, StringBuilder r, PowerManager pm, java.util.List<NcnnSr.Opts> modes, Progress progress) {
        Egl egl = null;
        GlKit kit = null;
        SrEngine glsl = null;
        try {
            step(progress, "تهيئة GLSL");
            egl = new Egl(false);
            egl.makeCurrentOffscreen();
            kit = new GlKit(new ShaderLoader(ctx.getAssets()));
            glsl = new SrEngine(kit, ctx.getAssets());
            boolean gok = glsl.selfTest(ctx.getAssets());
            r.append("\nالأداء — GLSL: ").append(glsl.status).append(gok ? "" : " (الفحص الذاتي لم يعلن الجاهزية)").append('\n');
            int inW = 960, inH = 540;
            long[] ram = ram(ctx);
            MemoryBudget.JavaHeap jh = MemoryBudget.JavaHeap.current();
            r.append("قبل القياس: ").append(jh.describe()).append(memLine(ram)).append('\n');
            MemoryBudget.Plan gPlan = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, ram[0], ram[1], false);
            MemoryBudget.Plan nPlan = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, ram[0], ram[1], true, jh);
            int tile = Math.min(gPlan.tile, nPlan.tile);
            TilePlan plan = new TilePlan(inW, inH, tile, NcnnSr.P);
            r.append(String.format(Locale.US,
                    "المصدر 1920x1080 بنصف دقة العمل (workScale 0.5): دخل الشبكة %dx%d، الخرج ×4 = %dx%d. البلاطة المشتركة T=%d (GLSL يسمح بـ %d وncnn بـ %d)، %d بلاطات، نافذة ncnn ≤%dx%d. إحماء %d ثم %d تكرارات متناوبة.\n",
                    inW, inH, inW * 4, inH * 4, tile, gPlan.tile, nPlan.tile, plan.count(), plan.maxWindowW(), plan.maxWindowH(), WARMUP, REPS));
            r.append("ميزانية GLSL: ").append(gPlan.describe()).append('\n');
            r.append("ميزانية ncnn: ").append(nPlan.describe()).append('\n');
            if (!gPlan.fits || !nPlan.fits) {
                r.append("توقف قبل الحجز: الذاكرة لا تكفي للمحركين بنفس الإعداد. ").append(!nPlan.fits ? "ncnn: " + nPlan.reason : "GLSL: " + gPlan.reason).append('\n');
                return;
            }
            // what this measurement itself holds in Java at once: input RGBA + its upload copy, then per run GL read + staging
            long javaNeed = 16L * inW * inH * 4;
            if (javaNeed > jh.avail() * MemoryBudget.JAVA_SHARE_PERCENT / 100) {
                r.append(String.format(Locale.US, "توقف قبل الحجز: القياس يحتاج %d MB من Java والمسموح %d%% من %d MB المتاحة.\n",
                        javaNeed >> 20, MemoryBudget.JAVA_SHARE_PERCENT, jh.avail() >> 20));
                return;
            }
            float[] img = pattern(5, inW, inH);
            for (NcnnSr.Opts o : modes) {
                if (thermalHot(pm)) { r.append(o.label()).append(": لم يُقس، الحرارة ليست منخفضة.\n"); continue; }
                step(progress, "زمن " + o.label());
                NcnnSr net = NcnnSr.open(ctx.getAssets(), o);
                try {
                    if (!net.loaded) { r.append(o.label()).append(": غير متاح: ").append(net.reason).append('\n'); continue; }
                    r.append("قبل ").append(o.label()).append(": ").append(MemoryBudget.JavaHeap.current().describe()).append(memLine(ram(ctx)))
                            .append(String.format(Locale.US, " · مخازن ncnn الأصلية %d MB", NcnnSr.bufferBytes(inW, inH, tile) >> 20)).append('\n');
                    r.append(timeBoth(kit, glsl, net, img, inW, inH, tile, pm));
                } finally {
                    net.close();
                }
            }
            r.append("بعد القياس: ").append(MemoryBudget.JavaHeap.current().describe()).append(memLine(ram(ctx))).append('\n');
            r.append("4K لا يُشغَّل من هذا الزر. أقصى نسيج GL ").append(GlKit.getInt(GLES20.GL_MAX_TEXTURE_SIZE)).append('\n');
        } catch (Throwable t) {
            r.append("توقف قياس الأداء:\n").append(chain(t)).append(MemoryBudget.JavaHeap.current().describe()).append('\n');
        } finally {
            if (glsl != null) glsl.release();
            if (kit != null) kit.release();
            if (egl != null) egl.release();
        }
    }

    private static long[] ram(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return new long[]{mi.availMem, mi.totalMem};
    }

    private static void step(Progress p, String s) { if (p != null) p.onProgress(s); }

    private static boolean thermalHot(PowerManager pm) {
        return pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
    }

    private static String memLine(long[] ram) {
        return String.format(Locale.US, " · native heap %d MB · النظام متاح %d من %d MB",
                Debug.getNativeHeapAllocatedSize() >> 20, ram[0] >> 20, ram[1] >> 20);
    }

    /** The whole cause chain, with the app's frames (where the allocation happened) and the first frames of each. */
    static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        java.util.Set<Throwable> seen = new java.util.HashSet<>();
        for (Throwable x = t; x != null && seen.add(x); x = x.getCause()) {
            b.append(x == t ? "  " : "  سببه: ").append(x.getClass().getName()).append(": ").append(x.getMessage()).append('\n');
            StackTraceElement[] st = x.getStackTrace();
            int shown = 0;
            for (int i = 0; i < st.length && shown < 8; i++) {
                if (i < 2 || st[i].getClassName().startsWith("com.mojahed.")) { b.append("    at ").append(st[i]).append('\n'); shown++; }
            }
        }
        return b.toString();
    }

    private static String timeBoth(GlKit kit, SrEngine glsl, NcnnSr ncnn, float[] rgb, int w, int h, int tile, PowerManager pm) {
        int t0Thermal = pm == null ? -1 : pm.getCurrentThermalStatus();
        Tex in, outG, outN;
        {
            float[] rgba = new float[w * h * 4];
            for (int i = 0; i < w * h; i++) { rgba[i * 4] = rgb[i * 3]; rgba[i * 4 + 1] = rgb[i * 3 + 1]; rgba[i * 4 + 2] = rgb[i * 3 + 2]; rgba[i * 4 + 3] = 1f; }
            in = kit.tex(w, h, true, asDirect(rgba));
        }
        outG = kit.tex(w * 4, h * 4, true);
        outN = kit.tex(w * 4, h * 4, true);
        long[] g = new long[REPS];
        long[][] n = new long[3][REPS];            // total, GL read, tiled run (includes the per-tile GL uploads)
        long[][] in2 = new long[5][REPS];          // gather, CPU pack, extract, shuffle (core), GL upload of cores
        boolean fullVk = true;
        String check;
        try {
            for (int i = 0; i < WARMUP; i++) { glslOnce(kit, glsl, in, outG, tile); ncnnOnce(kit, ncnn, in, outN, w, h, tile, null); }
            for (int i = 0; i < REPS; i++) {
                g[i] = glslOnce(kit, glsl, in, outG, tile);
                long[] ph = new long[3];
                NcnnSr.Forward f = ncnnOnce(kit, ncnn, in, outN, w, h, tile, ph);
                for (int k = 0; k < 3; k++) n[k][i] = ph[k];
                in2[0][i] = f.tileCopyNs; in2[1][i] = f.packNs; in2[2][i] = f.extractNs; in2[3][i] = f.shuffleNs; in2[4][i] = f.mergeNs;
                fullVk &= f.fullVulkan;
            }
            check = placementCheck(kit, outG, outN, w, h, tile);
        } finally {
            kit.delete(in); kit.delete(outG); kit.delete(outN);
        }
        int t1Thermal = pm == null ? -1 : pm.getCurrentThermalStatus();
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "%s · حرارة قبل %d بعد %d%s\n", ncnn.opts.label(), t0Thermal, t1Thermal, fullVk ? "" : " · ليس Vulkan بالكامل"));
        s.append("  الإعدادات الفعلية: ").append(ncnn.options).append('\n');
        s.append(String.format(Locale.US, "  GLSL نسيج→نسيج: وسيط %s · أدنى %s · أعلى %s ms\n", ms(med(g)), ms(min(g)), ms(max(g))));
        s.append(String.format(Locale.US, "  ncnn نسيج→نسيج: وسيط %s · أدنى %s · أعلى %s ms\n", ms(med(n[0])), ms(min(n[0])), ms(max(n[0]))));
        s.append(String.format(Locale.US,
                "    وسيط المراحل: قراءة GL %s · المقسّم %s (جمع النوافذ %s · تعبئة CPU %s · extract شاملاً النقل والمزامنة %s · PixelShuffle+المتبقي للقلب %s · رفع القلوب إلى GL %s) ms\n",
                ms(med(n[1])), ms(med(n[2])), ms(med(in2[0])), ms(med(in2[1])), ms(med(in2[2])), ms(med(in2[3])), ms(med(in2[4]))));
        s.append("  ").append(check).append('\n');
        double ratio = (double) med(n[0]) / Math.max(1, med(g));
        s.append(String.format(Locale.US, "  النسبة ncnn/GLSL (وسيط): %.2f× — %s\n", ratio,
                ratio < 1 ? "ncnn أقصر زمناً في هذا القياس" : "GLSL أقصر زمناً في هذا القياس"));
        return s.toString();
    }

    /**
     * Where the per-tile uploads landed: a 256x256 region of the x4 output that straddles an interior tile
     * boundary (or the centre if there is one tile), ncnn vs GLSL. Reported, not an acceptance gate.
     */
    private static String placementCheck(GlKit kit, Tex outG, Tex outN, int w, int h, int tile) {
        int bx = tile < w ? tile * 4 : w * 2, by = tile < h ? tile * 4 : h * 2;
        int x = Math.max(0, Math.min(w * 4 - 256, bx - 128)), y = Math.max(0, Math.min(h * 4 - 256, by - 128));
        float[] a = kit.read(outG, x, y, 256, 256), b = kit.read(outN, x, y, 256, 256);
        double m = 0, se = 0; int nn = 0;
        for (int i = 0; i < a.length; i++) { if ((i & 3) == 3) continue; double d = a[i] - b[i]; m = Math.max(m, Math.abs(d)); se += d * d; nn++; }
        double psnr = se == 0 ? 99 : 10 * Math.log10(nn / se);
        return String.format(Locale.US, "تحقق موضع الرفع: منطقة 256×256 عند (%d,%d) تعبر حد بلاطة، ncnn مقابل GLSL: maxAbs %.4f · PSNR %.1f dB", x, y, m, psnr);
    }

    /** sr.run + glFinish between two existing textures. */
    private static long glslOnce(GlKit kit, SrEngine sr, Tex in, Tex out, int tile) {
        GLES20.glFinish();
        long t0 = System.nanoTime();
        sr.run(in, out, tile);
        GLES20.glFinish();
        return System.nanoTime() - t0;
    }

    /** FramePipeline's ncnn path for one frame. ph (if not null): total, GL read, tiled run (with uploads) in ns. */
    private static NcnnSr.Forward ncnnOnce(final GlKit kit, NcnnSr ncnn, Tex in, final Tex out, int w, int h, int tile, long[] ph) {
        GLES20.glFinish();
        long t0 = System.nanoTime();
        float[] px = kit.read(in);
        long t1 = System.nanoTime();
        NcnnSr.Forward f = ncnn.forwardTo(px, 4, w, h, tile, new TilePlan.Sink() {
            @Override public void core(TilePlan.Tile t, FloatBuffer core) {
                kit.uploadRegion(out, t.x0 * 4, t.y0 * 4, t.cw * 4, t.ch * 4, core);
            }
        }, null);
        GLES20.glFinish();
        long t2 = System.nanoTime();
        if (ph != null) { ph[0] = t2 - t0; ph[1] = t1 - t0; ph[2] = t2 - t1; }
        return f;
    }

    private static long med(long[] a) { long[] b = a.clone(); java.util.Arrays.sort(b); return b[b.length / 2]; }
    private static long min(long[] a) { long m = Long.MAX_VALUE; for (long v : a) m = Math.min(m, v); return m; }
    private static long max(long[] a) { long m = Long.MIN_VALUE; for (long v : a) m = Math.max(m, v); return m; }
    private static String ms(long ns) { return String.format(Locale.US, "%.0f", ns / 1e6); }

    private static FloatBuffer asDirect(float[] rgba) {
        FloatBuffer fb = ByteBuffer.allocateDirect(rgba.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(rgba).position(0);
        return fb;
    }

    private static float[] pattern(int kind, int w, int h) {
        float[] o = new float[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r, g, b;
                switch (kind) {
                    case 0: r = 0.72f; g = 0.55f; b = 0.45f + 0.05f * y / h; break;
                    case 1: { float s = (float) Math.sin((x * 0.7 + y * 1.3)); r = g = b = 0.5f + 0.5f * s; break; }
                    case 2: r = g = b = (x % 8 == 0 || y % 8 == 0) ? 0.05f : 0.85f; break;
                    case 3: r = g = b = ((x / 3) % 5 == 0) ? 0.1f : 0.9f; break;
                    case 4: r = x / (float) w; g = y / (float) h; b = 0.5f; break;
                    default: {
                        int bx = x / 8, by = y / 8;
                        float base = ((bx * 13 + by * 7) % 10) / 10f;
                        r = g = b = base + ((x + y) & 1) * 0.02f;
                        break;
                    }
                }
                int i = (y * w + x) * 3;
                o[i] = r; o[i + 1] = g; o[i + 2] = b;
            }
        }
        return o;
    }

}
