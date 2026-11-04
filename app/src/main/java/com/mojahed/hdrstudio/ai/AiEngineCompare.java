package com.mojahed.hdrstudio.ai;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.os.Debug;
import android.os.PowerManager;

import com.mojahed.hdrstudio.core.MemoryBudget;
import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.gl.Tex;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Phone-side GLSL vs ncnn comparison. Does not export, does not need HDR, and does not
 * change the default engine. Desktop numbers must not be reported as Maleoon results.
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
        r.append("مقارنة محرك AI — ليست نتيجة تصدير HDR\n");
        r.append("الجهاز: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" API ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        r.append("النموذج: realesr-general-x4v3 SRVGGNetCompact\n");
        r.append("الأوزان: srvgg_general_x4v3.bin sha256=b21527dda6ac91a00574295259bbbd5690afa9334ca49416ed35244ee1a512d3\n");
        r.append("مصدر الأوزان: لم يُثبت بعد مقابل realesr-general-x4v3.pth الرسمي (انظر docs/AI_MODEL.md).\n");
        r.append("ncnn: ").append(NcnnSr.NCNN_TAG).append(" BSD-3-Clause. INT8 مغلق. صف 0 = أسفل GL.\n");
        r.append(String.format(Locale.US, "حدود مقترحة قبل تغيير الافتراضي: maxAbs≤%.3f و PSNR≥%.0f dB مقابل GLSL. لم تُستخدم لتغيير الافتراضي.\n",
                PROPOSED_MAX_ABS, PROPOSED_PSNR_DB));
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        Egl egl = null;
        GlKit kit = null;
        SrEngine glsl = null;
        NcnnSr fp16 = null;
        NcnnSr fp32 = null;
        try {
            step(progress, "تهيئة GLSL");
            egl = new Egl(false);
            egl.makeCurrentOffscreen();
            kit = new GlKit(new ShaderLoader(ctx.getAssets()));
            long g0 = System.nanoTime();
            glsl = new SrEngine(kit, ctx.getAssets());
            boolean gok = glsl.selfTest(ctx.getAssets());
            long glslLoad = (System.nanoTime() - g0) / 1_000_000L;
            r.append("GLSL تحميل+فحص ذاتي: ").append(glslLoad).append(" ms · ").append(glsl.status).append('\n');
            step(progress, "تهيئة ncnn");
            long n0 = System.nanoTime();
            fp16 = NcnnSr.open(ctx.getAssets(), true);
            fp32 = NcnnSr.open(ctx.getAssets(), false);
            r.append("ncnn FP16: ").append(fp16.summary()).append('\n');
            r.append("ncnn FP32: ").append(fp32.summary()).append('\n');
            r.append("تحميل ncnn (الجلستان): ").append((System.nanoTime() - n0) / 1_000_000L).append(" ms\n");
            if (!fp16.loaded) {
                r.append("توقف: Vulkan/ncnn غير متاح. محرك GLSL يبقى المتاح. لا fallback صامت.\n");
                return r.toString();
            }
            SelfVec sv = loadSelf(ctx.getAssets());
            step(progress, "جودة القص المرجعي");
            float[] gSelf = glslImage(kit, glsl, sv.in, sv.w, sv.h, 64);
            NcnnSr.Forward nSelf16 = fp16.forward(sv.in, sv.w, sv.h, 64, null);
            NcnnSr.Forward nSelf32 = fp32.loaded ? fp32.forward(sv.in, sv.w, sv.h, 64, null) : null;
            r.append("قص الفحص الذاتي ").append(sv.w).append('x').append(sv.h).append(" → ").append(sv.w * 4).append('x').append(sv.h * 4).append('\n');
            r.append("  GLSL مقابل المرجع المخزّن: ").append(diff("ref", gSelf, sv.exp)).append('\n');
            r.append("  ncnn FP16 مقابل GLSL: ").append(diff("fp16", nSelf16.rgb, gSelf)).append(nSelf16.fullVulkan ? "" : " · CPU fallback").append('\n');
            if (nSelf32 != null) r.append("  ncnn FP32 مقابل المرجع: ").append(diff("fp32", nSelf32.rgb, sv.exp)).append('\n');
            r.append("  بلاطة 24 مقابل 64 (FP16): ").append(seam(fp16, sv.in, sv.w, sv.h)).append('\n');
            if (!gok) r.append("  فحص GLSL الذاتي لم يعلن الجاهزية؛ المقارنة سُجّلت كما هي.\n");

            String[] names = {"skin", "hair", "lines", "text", "gradient", "blocks"};
            for (int i = 0; i < names.length; i++) {
                step(progress, "نمط " + names[i]);
                float[] img = pattern(i, 48, 48);
                float[] g = glslImage(kit, glsl, img, 48, 48, 64);
                NcnnSr.Forward n = fp16.forward(img, 48, 48, 64, null);
                r.append("نمط ").append(names[i]).append(" 48x48: ").append(diff(names[i], n.rgb, g)).append('\n');
            }
            step(progress, "وميض مقطع قصير");
            r.append(flicker(kit, glsl, fp16));

            step(progress, "زمن قص صغير");
            r.append(timeBoth(kit, glsl, fp16, pattern(2, 96, 96), 96, 96, 64, "small 96x96", pm));
            int inW = 960, inH = 540;
            long[] ram = ram(ctx);
            MemoryBudget.Plan mem = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, ram[0], ram[1], true);
            r.append("1080p workScale 0.5 (دخل الشبكة ").append(inW).append('x').append(inH).append("، خرج ×4). تقدير الميزانية: ").append(mem.describe()).append('\n');
            if (thermalHot(pm)) {
                r.append("1080p لم يُنفَّذ: الحرارة ليست منخفضة بعد القص الصغير.\n");
            } else {
                if (!mem.fits) r.append("تصدير 1080p إلى 4K فوق الميزانية المحافظة؛ القياس التالي للإطار المعزول فقط.\n");
                step(progress, "زمن 1080p");
                float[] big = pattern(5, inW, inH);
                r.append(timeBoth(kit, glsl, fp16, big, inW, inH, mem.tile, "1080p workScale 0.5", pm));
                float[] gc = glslImage(kit, glsl, crop(big, inW, inH, 32, 32), 32, 32, 64);
                NcnnSr.Forward nc = fp16.forward(crop(big, inW, inH, 32, 32), 32, 32, 64, null);
                r.append("  جودة مركز 32x32 من إطار 1080p: ").append(diff("center", nc.rgb, gc)).append('\n');
                r.append(String.format(Locale.US, "  ذاكرة مقاسة بعد 1080p: native heap %d MB · Java %d MB. تقدير GPU من الميزانية أعلاه وليس قياساً.\n",
                        Debug.getNativeHeapAllocatedSize() >> 20,
                        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
            }
            MemoryBudget.Plan uhd = MemoryBudget.plan(3840, 2160, false, 3840, 2160, false, false, true, 0.5f, ram[0], ram[1], true);
            int maxTex = GlKit.getInt(GLES20.GL_MAX_TEXTURE_SIZE);
            r.append("4K: تقدير ").append(uhd.describe()).append(" · أقصى نسيج GL ").append(maxTex).append('\n');
            if (!uhd.fits || maxTex < 7680 || thermalHot(pm)) {
                r.append("4K لم يُنفَّذ: ").append(!uhd.fits ? "فوق الميزانية" : maxTex < 7680 ? "نسيج GL أصغر من خرج ×4" : "الحرارة مرتفعة").append(".\n");
            } else {
                r.append("4K لم يُشغَّل تلقائياً في هذا الزر بعد 1080p حتى لا يُطيل الجلسة؛ الميزانية تسمح بإعادة الطلب لاحقاً.\n");
            }
            r.append("الافتراضي يبقى GLSL. هذه الأرقام من هذا التشغيل على هذا الجهاز فقط.\n");
        } catch (Throwable t) {
            r.append("توقف الاختبار: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        } finally {
            if (fp16 != null) fp16.close();
            if (fp32 != null) fp32.close();
            if (glsl != null) glsl.release();
            if (kit != null) kit.release();
            if (egl != null) egl.release();
        }
        return r.toString();
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

    private static String timeBoth(GlKit kit, SrEngine glsl, NcnnSr ncnn, float[] rgb, int w, int h, int tile, String label, PowerManager pm) {
        timeGlsl(kit, glsl, rgb, w, h, tile);
        long g1 = timeGlsl(kit, glsl, rgb, w, h, tile);
        ncnn.forward(rgb, w, h, tile, null);
        long n0 = System.nanoTime();
        NcnnSr.Forward f = ncnn.forward(rgb, w, h, tile, null);
        long n1 = (System.nanoTime() - n0) / 1_000_000L;
        long net = f.networkNs / 1_000_000L;
        long xfer = (f.packNs + f.shuffleNs + f.tileCopyNs) / 1_000_000L;
        return String.format(Locale.US,
                "%s بلاطة %d حرارة %d: GLSL %d ms (يشمل الرسم، بلا قراءة) · ncnn كلي %d ms · شبكة %d ms · نقل/تعبئة %d ms · بلاطات %d · %s\n",
                label, tile, pm == null ? -1 : pm.getCurrentThermalStatus(), g1, n1, net, xfer, f.tiles,
                f.fullVulkan ? "Vulkan للطبقات" : "ليس Vulkan بالكامل");
    }

    private static String flicker(GlKit kit, SrEngine glsl, NcnnSr ncnn) {
        double acc = 0;
        float[] prevG = null, prevN = null;
        int n = 0;
        for (int f = 0; f < 6; f++) {
            float[] img = movingBar(48, 48, f * 4);
            float[] g = glslImage(kit, glsl, img, 48, 48, 64);
            float[] o = ncnn.forward(img, 48, 48, 64, null).rgb;
            if (prevG != null) {
                double dg = 0, dn = 0;
                for (int i = 0; i < g.length; i++) { dg += Math.abs(g[i] - prevG[i]); dn += Math.abs(o[i] - prevN[i]); }
                acc += Math.abs(dg - dn) / g.length;
                n++;
            }
            prevG = g; prevN = o;
        }
        return String.format(Locale.US, "فرق حركة 6 إطارات (متوسط |ΔGLSL−Δncnn|): %.5f\n", n == 0 ? -1 : acc / n);
    }

    private static String seam(NcnnSr net, float[] rgb, int w, int h) {
        float[] a = net.forward(rgb, w, h, 24, null).rgb;
        float[] b = net.forward(rgb, w, h, 64, null).rgb;
        return diff("seam", a, b);
    }

    private static String diff(String name, float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double max = 0, se = 0;
        for (int i = 0; i < n; i++) {
            double d = a[i] - b[i];
            max = Math.max(max, Math.abs(d));
            se += d * d;
        }
        double mse = se / Math.max(1, n);
        double psnr = mse <= 1e-12 ? 99 : 10 * Math.log10(1.0 / mse);
        boolean ok = max <= PROPOSED_MAX_ABS && psnr >= PROPOSED_PSNR_DB;
        return String.format(Locale.US, "maxAbs %.5f PSNR %.2f dB %s", max, psnr, ok ? "ضمن الحد المقترح" : "خارج الحد المقترح");
    }

    private static float[] glslImage(GlKit kit, SrEngine sr, float[] rgb, int w, int h, int tile) {
        float[] rgba = new float[w * h * 4];
        for (int i = 0; i < w * h; i++) { rgba[i * 4] = rgb[i * 3]; rgba[i * 4 + 1] = rgb[i * 3 + 1]; rgba[i * 4 + 2] = rgb[i * 3 + 2]; rgba[i * 4 + 3] = 1f; }
        Tex in = kit.tex(w, h, true, asDirect(rgba));
        Tex out = kit.tex(w * 4, h * 4, true);
        sr.run(in, out, tile);
        GLES20.glFinish();
        float[] got = kit.read(out);
        kit.delete(in); kit.delete(out);
        float[] rgbOut = new float[w * h * 16 * 3];
        for (int i = 0; i < w * h * 16; i++) { rgbOut[i * 3] = got[i * 4]; rgbOut[i * 3 + 1] = got[i * 4 + 1]; rgbOut[i * 3 + 2] = got[i * 4 + 2]; }
        return rgbOut;
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

    private static float[] crop(float[] rgb, int w, int h, int cw, int ch) {
        int x0 = Math.max(0, w / 2 - cw / 2), y0 = Math.max(0, h / 2 - ch / 2);
        float[] o = new float[cw * ch * 3];
        for (int y = 0; y < ch; y++)
            System.arraycopy(rgb, ((y0 + y) * w + x0) * 3, o, y * cw * 3, cw * 3);
        return o;
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

    private static float[] movingBar(int w, int h, int shift) {
        float[] o = pattern(4, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (Math.abs(x - (8 + shift)) < 3) {
                    int i = (y * w + x) * 3;
                    o[i] = o[i + 1] = o[i + 2] = 0.95f;
                }
            }
        }
        return o;
    }

    private static final class SelfVec { float[] in, exp; int w, h; }

    private static SelfVec loadSelf(AssetManager am) throws Exception {
        SelfVec s = new SelfVec();
        try (InputStream in = am.open("models/srvgg_selftest.bin")) {
            DataInputStream din = new DataInputStream(in);
            byte[] hdr = new byte[8];
            din.readFully(hdr);
            ByteBuffer hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            s.w = hb.getInt(); s.h = hb.getInt();
            byte[] rest = new byte[(s.w * s.h * 3 + s.w * s.h * 48) * 4];
            din.readFully(rest);
            ByteBuffer rb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
            int n = s.w * s.h;
            s.in = new float[n * 3];
            for (int i = 0; i < n; i++) { s.in[i * 3] = rb.getFloat(); s.in[i * 3 + 1] = rb.getFloat(); s.in[i * 3 + 2] = rb.getFloat(); }
            s.exp = new float[n * 16 * 3];
            for (int i = 0; i < n * 16; i++) { s.exp[i * 3] = rb.getFloat(); s.exp[i * 3 + 1] = rb.getFloat(); s.exp[i * 3 + 2] = rb.getFloat(); }
        }
        return s;
    }
}
