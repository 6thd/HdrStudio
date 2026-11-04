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
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean allPass = true;
        try {
            java.util.List<NcnnTileCheck.Case> cases = NcnnTileCheck.load(ctx.getAssets());
            NcnnSr.Opts[] engines = {NcnnSr.Opts.cpuFp32(), NcnnSr.Opts.production(false), NcnnSr.Opts.production(true)};
            for (NcnnSr.Opts o : engines) {
                step(progress, "تحميل " + o.label());
                NcnnSr net = NcnnSr.open(ctx.getAssets(), o);
                try {
                    r.append('\n').append(o.label()).append(": ").append(net.summary()).append('\n');
                    boolean ok = NcnnTileCheck.run(r, net, cases, progress);
                    r.append(o.label()).append(ok ? ": كل المقارنات ضمن الحد\n" : ": فشل\n");
                    allPass &= ok;
                } finally {
                    net.close();
                }
            }
        } catch (Throwable t) {
            allPass = false;
            r.append("توقف الفحص: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        }
        r.append('\n').append(allPass ? "النتيجة: المسار المقسّم يطابق المباشر في المحركات الثلاثة.\n"
                : "النتيجة: المسار المقسّم لم ينجح. قياس الأداء الكبير لم يُشغَّل.\n");
        if (allPass) perf(ctx, r, pm, progress);
        r.append("الافتراضي يبقى GLSL. هذا الزر لا يغيّره. الأرقام من هذا التشغيل على هذا الجهاز فقط.\n");
        return r.toString();
    }

    /** Large frame, after the tile check passed: GLSL vs the ncnn path export runs, including transfer, crop and merge. */
    private static void perf(Context ctx, StringBuilder r, PowerManager pm, Progress progress) {
        Egl egl = null;
        GlKit kit = null;
        SrEngine glsl = null;
        NcnnSr fp16 = null;
        try {
            step(progress, "تهيئة GLSL و Vulkan FP16");
            egl = new Egl(false);
            egl.makeCurrentOffscreen();
            kit = new GlKit(new ShaderLoader(ctx.getAssets()));
            glsl = new SrEngine(kit, ctx.getAssets());
            boolean gok = glsl.selfTest(ctx.getAssets());
            r.append("\nالأداء — GLSL: ").append(glsl.status).append(gok ? "" : " (الفحص الذاتي لم يعلن الجاهزية)").append('\n');
            fp16 = NcnnSr.open(ctx.getAssets(), true);
            r.append("محرك التصدير ncnn: ").append(fp16.summary()).append('\n');
            if (!fp16.loaded) return;
            int inW = 960, inH = 540;
            long[] ram = ram(ctx);
            MemoryBudget.Plan mem = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, ram[0], ram[1], true);
            r.append("1080p workScale 0.5 (دخل الشبكة ").append(inW).append('x').append(inH).append("، خرج ×4). الميزانية: ").append(mem.describe()).append('\n');
            if (thermalHot(pm)) { r.append("لم يُنفَّذ: الحرارة ليست منخفضة.\n"); return; }
            step(progress, "زمن 1080p");
            float[] big = pattern(5, inW, inH);
            r.append(timeBoth(kit, glsl, fp16, big, inW, inH, mem.tile, "1080p workScale 0.5", pm));
            r.append(String.format(Locale.US, "  ذاكرة بعد القياس: native heap %d MB · Java %d MB. GPU من تقدير الميزانية وليس قياساً.\n",
                    Debug.getNativeHeapAllocatedSize() >> 20,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
            MemoryBudget.Plan uhd = MemoryBudget.plan(3840, 2160, false, 3840, 2160, false, false, true, 0.5f, ram[0], ram[1], true);
            r.append("4K: ").append(uhd.describe()).append(" · أقصى نسيج GL ").append(GlKit.getInt(GLES20.GL_MAX_TEXTURE_SIZE))
                    .append(" · لم يُشغَّل في هذا الزر.\n");
        } catch (Throwable t) {
            r.append("توقف قياس الأداء: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        } finally {
            if (fp16 != null) fp16.close();
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

    /**
     * GLSL: upload + sr.run + glFinish. ncnn: the same steps FramePipeline runs for export,
     * from an RGBA16F texture back to an RGBA16F texture: GL read, RGB pack, tiled forward
     * (window gather, CPU pack, extract with its transfer and sync, shuffle, crop+merge),
     * RGBA pack and upload, then glFinish. Second run of each is reported.
     */
    private static String timeBoth(GlKit kit, SrEngine glsl, NcnnSr ncnn, float[] rgb, int w, int h, int tile, String label, PowerManager pm) {
        timeGlsl(kit, glsl, rgb, w, h, tile);
        long g1 = timeGlsl(kit, glsl, rgb, w, h, tile);
        float[] rgba = new float[w * h * 4];
        for (int i = 0; i < w * h; i++) { rgba[i * 4] = rgb[i * 3]; rgba[i * 4 + 1] = rgb[i * 3 + 1]; rgba[i * 4 + 2] = rgb[i * 3 + 2]; rgba[i * 4 + 3] = 1f; }
        Tex in = kit.tex(w, h, true, asDirect(rgba));
        Tex out = kit.tex(w * 4, h * 4, true);
        long[] ph = null;
        NcnnSr.Forward f = null;
        for (int run = 0; run < 2; run++) {
            GLES20.glFinish();
            long t0 = System.nanoTime();
            float[] px = kit.read(in);
            int n = w * h;
            float[] in3 = new float[n * 3];
            for (int i = 0; i < n; i++) { in3[i * 3] = px[i * 4]; in3[i * 3 + 1] = px[i * 4 + 1]; in3[i * 3 + 2] = px[i * 4 + 2]; }
            long t1 = System.nanoTime();
            f = ncnn.forward(in3, w, h, tile, null);
            long t2 = System.nanoTime();
            int hn = w * h * 16;
            float[] o4 = new float[hn * 4];
            for (int i = 0; i < hn; i++) { o4[i * 4] = f.rgb[i * 3]; o4[i * 4 + 1] = f.rgb[i * 3 + 1]; o4[i * 4 + 2] = f.rgb[i * 3 + 2]; o4[i * 4 + 3] = 1f; }
            kit.upload(out, asDirect(o4));
            GLES20.glFinish();
            long t3 = System.nanoTime();
            ph = new long[]{t1 - t0, t2 - t1, t3 - t2, t3 - t0};
        }
        kit.delete(in); kit.delete(out);
        long ms = 1_000_000L;
        TilePlan plan = new TilePlan(w, h, tile, NcnnSr.P);
        return String.format(Locale.US,
                "%s بلاطة %d (%d بلاطات، نافذة ≤%dx%d) حرارة %d:\n"
                        + "  GLSL %d ms (رفع+شبكة+glFinish، بلا قراءة)\n"
                        + "  ncnn مسار التصدير كلي %d ms = قراءة GL+تحويل %d · مقسّم %d · تحويل RGBA+رفع GL %d\n"
                        + "  داخل المقسّم: جمع النوافذ %d · تعبئة CPU %d · extract شاملاً النقل والمزامنة %d · PixelShuffle+المتبقي %d · قص ×4 ودمج %d ms · %s\n",
                label, tile, plan.count(), plan.maxWindowW(), plan.maxWindowH(), pm == null ? -1 : pm.getCurrentThermalStatus(),
                g1, ph[3] / ms, ph[0] / ms, ph[1] / ms, ph[2] / ms,
                f.tileCopyNs / ms, f.packNs / ms, f.extractNs / ms, f.shuffleNs / ms, f.mergeNs / ms,
                f.fullVulkan ? "Vulkan للطبقات" : "ليس Vulkan بالكامل");
    }

    /** Upload + sr.run + glFinish. Readback is outside the timed window. */
    private static long timeGlsl(GlKit kit, SrEngine sr, float[] rgb, int w, int h, int tile) {
        float[] rgba = new float[w * h * 4];
        for (int i = 0; i < w * h; i++) { rgba[i * 4] = rgb[i * 3]; rgba[i * 4 + 1] = rgb[i * 3 + 1]; rgba[i * 4 + 2] = rgb[i * 3 + 2]; rgba[i * 4 + 3] = 1f; }
        Tex in = kit.tex(w, h, true, asDirect(rgba));
        Tex out = kit.tex(w * 4, h * 4, true);
        GLES20.glFinish();
        long t0 = System.nanoTime();
        sr.run(in, out, tile);
        GLES20.glFinish();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        kit.delete(in); kit.delete(out);
        return ms;
    }

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
