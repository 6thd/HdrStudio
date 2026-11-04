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
        r.append(com.mojahed.hdrstudio.core.BuildIdentity.describe(ctx)).append('\n');
        r.append("مكوّن ncnn: ").append(ncnnIdentity(ctx)).append('\n');
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
        saveAccepted(ctx, accepted);
        if (accepted.isEmpty()) {
            r.append("لا وضع Vulkan مقبول. قياس الأداء لم يُشغَّل.\n");
        } else {
            r.append("أوضاع Vulkan المقبولة للقياس التجريبي:");
            for (NcnnSr.Opts o : accepted) r.append(' ').append(o.label()).append(';');
            r.append('\n');
            perf(ctx, r, pm, accepted, progress);
        }
        r.append("الافتراضي يبقى GLSL. التصدير التجريبي عبر ncnn يستخدم ").append(exportMode(ctx).label())
                .append(". هذا الزر لا يغيّر أي إعداد. الأرقام من هذا التشغيل على هذا الجهاز فقط.\n");
        return r.toString();
    }

    /** Longest wait for the phone to cool before one engine pair is timed. */
    static final int COOL_WAIT_S = 300, COOL_POLL_S = 5;
    private static final String PREFS = "ncnn_compare";

    /**
     * Timing only, for the modes the full check of THIS build accepted (stored by run()). Lets the timing
     * start on a cool phone instead of right after the heavy matching checks. Nothing is re-accepted here.
     */
    public static String runPerfOnly(Context ctx, Progress progress) {
        StringBuilder r = new StringBuilder();
        r.append("قياس الأداء فقط — ليس نتيجة تصدير HDR\n");
        r.append(com.mojahed.hdrstudio.core.BuildIdentity.describe(ctx)).append('\n');
        r.append("الجهاز: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" API ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String keys = sp.getString("accepted", "");
        r.append("مكوّن ncnn: ").append(ncnnIdentity(ctx)).append('\n');
        if (!sameNcnn(ctx, sp, "acceptedNcnn", "versionCode")) {
            r.append("لا يوجد قبول محفوظ لمكوّن ncnn هذا (المكتبة الأصلية أو المسار تغيّر). شغّل «فحص بلاطات ncnn ثم المقارنة» أولاً.\n");
            return r.toString();
        }
        java.util.List<NcnnSr.Opts> modes = new java.util.ArrayList<>();
        for (String k : keys.split(",")) { NcnnSr.Opts o = optsOf(k); if (o != null) modes.add(o); }
        r.append("الأوضاع المقبولة في فحص هذه النسخة (").append(new java.util.Date(sp.getLong("time", 0))).append("):");
        for (NcnnSr.Opts o : modes) r.append(' ').append(o.label()).append(';');
        r.append('\n');
        if (modes.isEmpty()) { r.append("لا وضع Vulkan مقبول. لا قياس.\n"); return r.toString(); }
        perf(ctx, r, (PowerManager) ctx.getSystemService(Context.POWER_SERVICE), modes, progress);
        r.append("الافتراضي يبقى GLSL. هذا الزر لا يغيّر أي إعداد. الأرقام من هذا التشغيل على هذا الجهاز فقط.\n");
        return r.toString();
    }

    private static void saveAccepted(Context ctx, java.util.List<NcnnSr.Opts> accepted) {
        StringBuilder k = new StringBuilder();
        for (NcnnSr.Opts o : accepted) { if (k.length() > 0) k.append(','); k.append(keyOf(o)); }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("versionCode", versionCode(ctx)).putString("acceptedNcnn", ncnnIdentity(ctx)).putString("accepted", k.toString())
                .putLong("time", System.currentTimeMillis()).apply();
    }

    /**
     * The experimental ncnn export mode for this build: FP16 storage + FP32 arithmetic only if this build's
     * check accepted it and this build's timing confirmed it (see decide()); otherwise Vulkan FP32.
     * GLSL stays the default engine either way; this only picks ncnn's precision when ncnn is chosen.
     */
    public static NcnnSr.Opts exportMode(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sameNcnn(ctx, sp, "exportModeNcnn", "exportModeBuild") && "vulkanFp16Storage".equals(sp.getString("exportMode", "")))
            return NcnnSr.Opts.vulkanFp16Storage();
        return NcnnSr.Opts.vulkanFp32();
    }

    public static String exportModeReason(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sameNcnn(ctx, sp, "exportModeNcnn", "exportModeBuild")) return "لم يُقرَّر لمكوّن ncnn هذا بعد: Vulkan FP32";
        String from = sp.getString("exportModeNcnn", null) == null ? " (قرار قياس 2.3.7 على المكتبة الأصلية نفسها " + ncnnIdentity(ctx) + ")" : "";
        return sp.getString("exportModeWhy", "") + from;
    }

    /** Clean-run result of one mode's timing pair. */
    static final class Timed {
        NcnnSr.Opts mode;
        long glslMedNs = -1, ncnnMedNs = -1;
        int glslClean, ncnnClean;
    }

    static final int MIN_CLEAN = 3;

    /**
     * FP16 storage + FP32 arithmetic replaces FP32 as ncnn's experimental mode only if, in this build:
     * it was accepted by the check (it was timed only because it was), both modes have at least
     * MIN_CLEAN uninterrupted runs for each engine, and its clean median is below both Vulkan FP32's and
     * GLSL's in its own pair. Otherwise FP32 stays. Stored per build.
     */
    private static void decide(Context ctx, StringBuilder r, java.util.List<Timed> timed) {
        Timed f16 = null, f32 = null;
        for (Timed t : timed) { if ("vulkanFp16Storage".equals(keyOf(t.mode))) f16 = t; else if ("vulkanFp32".equals(keyOf(t.mode))) f32 = t; }
        String mode = "vulkanFp32", why;
        if (f16 == null || f32 == null) why = "لم يُقس الوضعان معاً في هذا التشغيل: يبقى Vulkan FP32.";
        else if (Math.min(Math.min(f16.glslClean, f16.ncnnClean), Math.min(f32.glslClean, f32.ncnnClean)) < MIN_CLEAN)
            why = String.format(Locale.US, "تكرارات سليمة أقل من %d لأحد المحركين: غير حاسم، يبقى Vulkan FP32.", MIN_CLEAN);
        else if (f16.ncnnMedNs < f32.ncnnMedNs && f16.ncnnMedNs < f16.glslMedNs) {
            mode = "vulkanFp16Storage";
            why = String.format(Locale.US, "تخزين FP16 مع حساب FP32: مقبول في فحص هذه النسخة، ووسيطه السليم %.2f ث أقل من Vulkan FP32 (%.2f ث) ومن GLSL في زوجه (%.2f ث).",
                    f16.ncnnMedNs / 1e9, f32.ncnnMedNs / 1e9, f16.glslMedNs / 1e9);
        } else why = String.format(Locale.US, "تخزين FP16 لم يكن أسرع من الاثنين (%.2f ث مقابل FP32 %.2f ث وGLSL %.2f ث): يبقى Vulkan FP32.",
                f16.ncnnMedNs / 1e9, f32.ncnnMedNs / 1e9, f16.glslMedNs / 1e9);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("exportModeBuild", versionCode(ctx)).putString("exportModeNcnn", ncnnIdentity(ctx))
                .putString("exportMode", mode).putString("exportModeWhy", why).apply();
        r.append("\nوضع ncnn التجريبي للتصدير لمكوّن ncnn هذا: ").append(optsOf(mode).label()).append(". ").append(why).append(" GLSL يبقى الافتراضي.\n");
    }

    /**
     * Identity of what was checked and timed: the ncnn Java path revision and the SHA-256 of the native library
     * inside the installed APK. Acceptance and the export-mode decision are valid for exactly this identity, so a
     * rebuild that does not touch ncnn keeps them and any change to the library or the path drops them.
     */
    private static String ncnnIdCache;

    public static synchronized String ncnnIdentity(Context ctx) {
        if (ncnnIdCache != null) return ncnnIdCache;
        String lib = "غير موجودة";
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(ctx.getApplicationInfo().sourceDir)) {
            java.util.zip.ZipEntry e = z.getEntry("lib/arm64-v8a/libhdrstudio_ncnn.so");
            if (e != null) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                try (java.io.InputStream in = z.getInputStream(e)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                }
                StringBuilder h = new StringBuilder();
                for (byte x : md.digest()) h.append(String.format(Locale.US, "%02x", x & 0xFF));
                lib = h.toString();
            }
        } catch (Exception ex) {
            lib = "تعذر القراءة: " + ex.getClass().getSimpleName();
        }
        ncnnIdCache = NcnnSr.PATH_REV + " lib " + lib;
        return ncnnIdCache;
    }

    /** 2.3.7 (versionCode 14) stored by version only; it ran this native library and path revision. */
    private static final String V237_ID = "ncnn-path-1 lib 3bf0be540cdb45f9f61334cd4935254e5188915d06ffe609ab4dcb1282136d77";

    private static boolean sameNcnn(Context ctx, android.content.SharedPreferences sp, String idKey, String versionKey) {
        String id = sp.getString(idKey, null), now = ncnnIdentity(ctx);
        if (id != null) return id.equals(now);
        return sp.getLong(versionKey, -1) == 14 && V237_ID.equals(now);
    }

    private static String keyOf(NcnnSr.Opts o) {
        return o.fp16Storage && !o.fp16Arith ? "vulkanFp16Storage" : !o.fp16Storage && !o.fp16Arith ? "vulkanFp32" : "other";
    }

    private static NcnnSr.Opts optsOf(String k) {
        if ("vulkanFp32".equals(k)) return NcnnSr.Opts.vulkanFp32();
        if ("vulkanFp16Storage".equals(k)) return NcnnSr.Opts.vulkanFp16Storage();
        return null;
    }

    private static long versionCode(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode(); }
        catch (Exception e) { return -2; }
    }

    static String thermalName(int s) {
        switch (s) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE(0)";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT(1)";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE(2)";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE(3)";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL(4)";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY(5)";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN(6)";
            default: return "?(" + s + ")";
        }
    }

    /** Status name plus Android's thermal headroom (1.0 = throttling starts; API 30+, NaN if the device does not report it). */
    static String thermal(PowerManager pm) {
        if (pm == null) return "غير متاح";
        String h = "";
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            float v = pm.getThermalHeadroom(0);
            h = Float.isNaN(v) ? " · headroom غير مُبلغ" : String.format(Locale.US, " · headroom %.2f", v);
        }
        return thermalName(pm.getCurrentThermalStatus()) + h;
    }

    /**
     * Waits (at most COOL_WAIT_S) until the thermal status is below MODERATE, so throttling does not bias
     * the timing. Reports the status at start and end and the time waited. Returns false if still hot.
     */
    public static boolean waitCool(PowerManager pm, StringBuilder r, Progress progress, String label) {
        if (pm == null) return true;
        String start = thermal(pm);
        long t0 = System.currentTimeMillis();
        while (thermalHot(pm)) {
            long waited = (System.currentTimeMillis() - t0) / 1000;
            if (waited >= COOL_WAIT_S) {
                r.append(String.format(Locale.US, "%s: لم يُقس. الحرارة عند البدء %s وبعد انتظار %d ث ما زالت %s (المطلوب أقل من MODERATE). أعد المحاولة بزر «قياس الأداء فقط» بعد أن يبرد الهاتف.\n",
                        label, start, waited, thermal(pm)));
                return false;
            }
            step(progress, String.format(Locale.US, "انتظار تبريد الهاتف قبل %s: %s · %d/%d ث", label, thermal(pm), waited, COOL_WAIT_S));
            try { Thread.sleep(COOL_POLL_S * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        long waited = (System.currentTimeMillis() - t0) / 1000;
        r.append(String.format(Locale.US, "%s: الحرارة عند البدء %s%s · عند القياس %s\n", label, start,
                waited > 0 ? String.format(Locale.US, " · انتظار تبريد %d ث (خارج زمن المعالجة)", waited) : " · بلا انتظار", thermal(pm)));
        return true;
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
            r.append("قبل القياس: ").append(jh.describe()).append(memLine(ram)).append(" · الحرارة ").append(thermal(pm)).append('\n');
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
            // FP16 storage first, then FP32 (2.3.6 ran them the other way round)
            java.util.List<NcnnSr.Opts> order = new java.util.ArrayList<>();
            for (NcnnSr.Opts o : modes) if (o.fp16Storage) order.add(o);
            for (NcnnSr.Opts o : modes) if (!o.fp16Storage) order.add(o);
            r.append("الترتيب:");
            for (NcnnSr.Opts o : order) r.append(' ').append(o.label()).append(';');
            r.append(" داخل كل وضع: GLSL ثم ncnn متناوبان في كل تكرار.\n");
            java.util.List<Timed> timed = new java.util.ArrayList<>();
            for (NcnnSr.Opts o : order) {
                if (!waitCool(pm, r, progress, o.label())) continue;   // waiting is reported separately, never inside a timed run
                step(progress, "زمن " + o.label());
                NcnnSr net = NcnnSr.open(ctx.getAssets(), o);
                try {
                    if (!net.loaded) { r.append(o.label()).append(": غير متاح: ").append(net.reason).append('\n'); continue; }
                    r.append("قبل ").append(o.label()).append(": ").append(MemoryBudget.JavaHeap.current().describe()).append(memLine(ram(ctx)))
                            .append(String.format(Locale.US, " · مخازن ncnn الأصلية %d MB", NcnnSr.bufferBytes(inW, inH, tile) >> 20)).append('\n');
                    Timed t = new Timed();
                    t.mode = o;
                    r.append(timeBoth(ctx, kit, glsl, net, img, inW, inH, tile, pm, t));
                    timed.add(t);
                } finally {
                    net.close();
                }
            }
            r.append("بعد القياس: ").append(MemoryBudget.JavaHeap.current().describe()).append(memLine(ram(ctx))).append('\n');
            decide(ctx, r, timed);
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

    /**
     * WARMUP, then REPS repetitions; each repetition times GLSL, then ncnn. For every timed run: wall-clock
     * start and end, thermal status, and whether the app stayed in the foreground with the screen on
     * (RunMonitor). An interrupted run is listed with its reason and left out of the clean median, not
     * dropped from the table. Medians over clean runs; all five times are printed.
     */
    private static String timeBoth(Context ctx, GlKit kit, SrEngine glsl, NcnnSr ncnn, float[] rgb, int w, int h, int tile, PowerManager pm, Timed out) {
        String t0Thermal = thermal(pm);
        Tex in, outG, outN;
        {
            float[] rgba = new float[w * h * 4];
            for (int i = 0; i < w * h; i++) { rgba[i * 4] = rgb[i * 3]; rgba[i * 4 + 1] = rgb[i * 3 + 1]; rgba[i * 4 + 2] = rgb[i * 3 + 2]; rgba[i * 4 + 3] = 1f; }
            in = kit.tex(w, h, true, asDirect(rgba));
        }
        outG = kit.tex(w * 4, h * 4, true);
        outN = kit.tex(w * 4, h * 4, true);
        long[] g = new long[REPS], n = new long[REPS];
        long[][] ph = new long[8][REPS];   // read, tiled, gather, pack, extract, shuffle, upload
        String[] gInt = new String[REPS], nInt = new String[REPS];
        StringBuilder rows = new StringBuilder();
        boolean fullVk = true;
        String check;
        try {
            for (int i = 0; i < WARMUP; i++) { glslOnce(kit, glsl, in, outG, tile); ncnnOnce(kit, ncnn, in, outN, w, h, tile, null); }
            for (int i = 0; i < REPS; i++) {
                String thA = thermalName(pm.getCurrentThermalStatus());
                com.mojahed.hdrstudio.core.RunMonitor.State a = com.mojahed.hdrstudio.core.RunMonitor.now(ctx);
                g[i] = glslOnce(kit, glsl, in, outG, tile);
                com.mojahed.hdrstudio.core.RunMonitor.State b = com.mojahed.hdrstudio.core.RunMonitor.now(ctx);
                gInt[i] = com.mojahed.hdrstudio.core.RunMonitor.interruption(a, b);
                long[] p = new long[3];
                NcnnSr.Forward f = ncnnOnce(kit, ncnn, in, outN, w, h, tile, p);
                com.mojahed.hdrstudio.core.RunMonitor.State c = com.mojahed.hdrstudio.core.RunMonitor.now(ctx);
                nInt[i] = com.mojahed.hdrstudio.core.RunMonitor.interruption(b, c);
                n[i] = p[0];
                ph[0][i] = p[1]; ph[1][i] = p[2]; ph[2][i] = f.tileCopyNs; ph[3][i] = f.packNs; ph[4][i] = f.extractNs; ph[5][i] = f.shuffleNs; ph[6][i] = f.mergeNs;
                fullVk &= f.fullVulkan;
                String thC = thermalName(pm.getCurrentThermalStatus());
                rows.append(String.format(Locale.US, "    تكرار %d: GLSL %s ث [%s–%s] %s · ncnn %s ث [%s–%s] %s · حرارة %s→%s\n", i + 1,
                        sec(g[i]), com.mojahed.hdrstudio.core.RunMonitor.clock(a.ms), com.mojahed.hdrstudio.core.RunMonitor.clock(b.ms), gInt[i] == null ? "سليم" : "منقطع: " + gInt[i],
                        sec(n[i]), com.mojahed.hdrstudio.core.RunMonitor.clock(b.ms), com.mojahed.hdrstudio.core.RunMonitor.clock(c.ms), nInt[i] == null ? "سليم" : "منقطع: " + nInt[i],
                        thA, thC));
            }
            check = placementCheck(kit, outG, outN, w, h, tile);
        } finally {
            kit.delete(in); kit.delete(outG); kit.delete(outN);
        }
        long[] gc = clean(g, gInt), nc = clean(n, nInt);
        out.glslClean = gc.length; out.ncnnClean = nc.length;
        out.glslMedNs = gc.length > 0 ? med(gc) : -1; out.ncnnMedNs = nc.length > 0 ? med(nc) : -1;
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "%s · حرارة قبل %s · بعد %s%s\n", ncnn.opts.label(), t0Thermal, thermal(pm), fullVk ? "" : " · ليس Vulkan بالكامل"));
        s.append("  الإعدادات الفعلية: ").append(ncnn.options).append('\n');
        s.append(rows);
        s.append(String.format(Locale.US, "  GLSL: الأزمنة %s · وسيط السليمة %s ث (%d/%d سليمة) · وسيط الكل %s ث\n", list(g), out.glslMedNs < 0 ? "—" : sec(out.glslMedNs), gc.length, REPS, sec(med(g))));
        s.append(String.format(Locale.US, "  ncnn: الأزمنة %s · وسيط السليمة %s ث (%d/%d سليمة) · وسيط الكل %s ث\n", list(n), out.ncnnMedNs < 0 ? "—" : sec(out.ncnnMedNs), nc.length, REPS, sec(med(n))));
        s.append(String.format(Locale.US,
                "    وسيط مراحل ncnn: قراءة GL %s · المقسّم %s (جمع النوافذ %s · تعبئة CPU %s · extract شاملاً النقل والمزامنة %s · PixelShuffle+المتبقي للقلب %s · رفع القلوب إلى GL %s) ms\n",
                ms(med(ph[0])), ms(med(ph[1])), ms(med(ph[2])), ms(med(ph[3])), ms(med(ph[4])), ms(med(ph[5])), ms(med(ph[6]))));
        s.append("  ").append(check).append('\n');
        if (out.glslMedNs > 0 && out.ncnnMedNs > 0) {
            double ratio = (double) out.ncnnMedNs / out.glslMedNs;
            s.append(String.format(Locale.US, "  النسبة ncnn/GLSL (وسيط السليمة): %.2f× — %s\n", ratio,
                    ratio < 1 ? "ncnn أقصر زمناً في هذا القياس" : "GLSL أقصر زمناً في هذا القياس"));
        } else s.append("  لا نسبة: لا تكرارات سليمة كافية لأحد المحركين.\n");
        return s.toString();
    }

    private static long[] clean(long[] v, String[] why) {
        int k = 0;
        for (String w : why) if (w == null) k++;
        long[] o = new long[k];
        for (int i = 0, j = 0; i < v.length; i++) if (why[i] == null) o[j++] = v[i];
        return o;
    }

    private static String sec(long ns) { return String.format(Locale.US, "%.2f", ns / 1e9); }

    private static String list(long[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) b.append(i == 0 ? "" : ", ").append(sec(v[i]));
        return b.toString();
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
