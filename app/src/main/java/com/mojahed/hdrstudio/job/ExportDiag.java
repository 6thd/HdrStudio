package com.mojahed.hdrstudio.job;

import android.content.Context;
import android.os.PowerManager;

import com.mojahed.hdrstudio.ai.AiEngineCompare;
import com.mojahed.hdrstudio.core.BuildIdentity;
import com.mojahed.hdrstudio.core.RunMonitor;
import com.mojahed.hdrstudio.encode.PipeTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Export diagnosis, short: the first FRAMES frames of the SDR range, twice.
 *  1. without AI (control; 2.3.10 showed it passes);
 *  2. GLSL as the export runs it (since 2.3.12 with glFinish before the final draw, as in every GLSL AI export).
 * Every frame: EGL binding and GL state of the final draw are checked against the encoder surface, the
 * results of eglPresentationTimeANDROID and eglSwapBuffers are checked (a refused frame is not counted as
 * sent), and after the swap the time the GPU still needed and whether the encoder put anything out within
 * 1.5 s are recorded. The end-of-stream wait is 15 s. The long-delay step of 2.3.10 is not repeated.
 * Nothing here changes encoding, colours, the model or acceptance.
 */
public final class ExportDiag implements JobManager.Batch {
    public static final int FRAMES = 5;
    private final ExportRequest base;

    public ExportDiag(ExportRequest base) { this.base = base; }

    /** End of a range that holds the first FRAMES frames at the source rate. */
    public static long endFor(long startUs, double fps) { return startUs + (long) Math.ceil(FRAMES * 1e6 / Math.max(1, fps)); }

    private ExportRequest variant(String tag, boolean ai) {
        ExportRequest q = new ExportRequest();
        q.uri = base.uri; q.src = base.src; q.preset = base.preset; q.targetShortSide = base.targetShortSide;
        q.bitrateOverride = base.bitrateOverride; q.keepAudio = base.keepAudio; q.selfTest = base.selfTest;
        q.measureLight = false; q.preview = false;
        q.startUs = base.startUs; q.endUs = base.endUs;
        q.params = base.params.copy();
        q.params.ai = ai;
        q.params.aiNcnn = false;
        q.abTag = tag;
        q.diagProbe = true;
        q.eosWaitMs = 15_000;
        return q;
    }

    private static final class Run { String name; ExportJob job; String interruption; }

    @Override public void run(Context ctx) {
        StringBuilder r = new StringBuilder();
        JobManager.Snapshot snap = new JobManager.Snapshot();
        snap.state = JobManager.State.RUNNING;
        snap.stage = "تشخيص التصدير";
        JobManager.get().publish(snap);
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        r.append("تشخيص التصدير القصير — أول ").append(FRAMES).append(" إطارات: بلا AI، ثم GLSL بمسار التصدير الفعلي\n");
        r.append(BuildIdentity.describe(ctx)).append('\n');
        r.append(String.format(Locale.US, "المصدر: %dx%d · %.3f fps · المدى %.3f–%.3f ث · الإعداد %s · الصوت %s\n", base.src.width, base.src.height,
                base.src.avgFps, base.startUs / 1e6, base.endUs / 1e6, base.preset.label, base.keepAudio ? "يُحفظ" : "يُحذف"));
        r.append("كل إطار: فحص سياق EGL وسطحي الرسم والقراءة وحالة GL قبل التسليم، ونتيجة eglPresentationTimeANDROID وeglSwapBuffers، ")
                .append("ثم زمن اكتمال GPU بعد التسليم وظهور خرج المرمّز خلال ").append(ExportJob.PROBE_OUT_MS).append(" ms. انتظار EOS 15 ث.\n");
        List<Run> runs = new ArrayList<>();
        try {
            runs.add(export(ctx, pm, snap, r, "1 بلا AI", variant("DIAG_noAI", false)));
            runs.add(export(ctx, pm, snap, r, "2 GLSL (مسار التصدير الفعلي)", variant("DIAG_GLSL", true)));
            r.append("\nالخلاصة (ملاحظات مقاسة، لا أسباب مثبتة):\n");
            for (Run x : runs) {
                PipeTrace t = x.job.trace;
                r.append(String.format(Locale.US, "  %s: %s · %.1f ث", x.name, x.job.state(), x.job.wallMs / 1e3));
                if (t != null) {
                    r.append(String.format(Locale.US, " · داخل الفترة %d · عولجت %d · أُرسلت %d · إعداد %d · خرجت من المرمّز %d · كُتبت %d",
                            t.count(PipeTrace.IN_RANGE), t.count(PipeTrace.PROCESSED), t.count(PipeTrace.SENT), t.count(PipeTrace.ENC_CONFIG),
                            t.count(PipeTrace.ENC_OUT), t.count(PipeTrace.WRITTEN)));
                    String loss = t.firstLoss();
                    if (loss != null) r.append(" · ").append(loss);
                }
                if (x.job.state() != JobManager.State.DONE) r.append(" · ").append(x.job.error());
                if (x.interruption != null) r.append(" · منقطع: ").append(x.interruption);
                r.append('\n');
            }
            r.append("لم يتغير أي إعداد. الملفات الناجحة في Movies/HDR Studio باسم ‎…AB…DIAG…‎، والمرفوضة داخل مساحة التطبيق فقط.\n");
            snap.state = JobManager.State.DONE;
            snap.stage = "اكتمل تشخيص التصدير";
        } catch (Throwable t) {
            snap.state = JobManager.State.FAILED;
            snap.stage = "توقف تشخيص التصدير";
            snap.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            r.append("\nتوقف: ").append(snap.error).append('\n');
        }
        snap.fraction = 1;
        snap.result = r.toString().trim();
        JobManager.Snapshot fin = copy(snap);
        fin.abFinal = true;
        JobManager.get().publish(fin);
    }

    private Run export(Context ctx, PowerManager pm, final JobManager.Snapshot snap, StringBuilder r, String name, ExportRequest q) throws Exception {
        StringBuilder wait = new StringBuilder();
        if (!AiEngineCompare.waitCool(pm, wait, new AiEngineCompare.Progress() {
            @Override public void onProgress(String line) { snap.stage = line; JobManager.get().publish(copy(snap)); }
        }, name)) {
            r.append(wait);
            throw new IllegalStateException("الهاتف ما زال ساخناً قبل الخطوة " + name + ". أعد التشخيص بعد أن يبرد.");
        }
        r.append(wait);
        Run x = new Run();
        x.name = name;
        x.job = new ExportJob(ctx, q);
        JobManager.get().attach(x.job);
        RunMonitor.State a = RunMonitor.now(ctx);
        x.job.run();
        RunMonitor.State b = RunMonitor.now(ctx);
        x.interruption = RunMonitor.interruption(a, b);
        r.append(String.format(Locale.US, "\n===== [%s] %s · من %s إلى %s · %s =====\n", name, x.job.state(), RunMonitor.clock(a.ms), RunMonitor.clock(b.ms),
                x.interruption == null ? "سليم (التطبيق في الواجهة والشاشة نشطة)" : "منقطع: " + x.interruption));
        if (x.job.state() != JobManager.State.DONE) r.append("الخطأ: ").append(x.job.error()).append('\n');
        long med = median(x.job.frameMs);
        if (med > 0) r.append(String.format(Locale.US, "وسيط زمن الإطار في الحلقة: %d ms (%d إطار)\n", med, x.job.frameMs.size()));
        if (x.job.report() != null) r.append(x.job.report()).append('\n');
        if (x.job.state() == JobManager.State.CANCELLED) throw new java.util.concurrent.CancellationException("أُلغي التشخيص");
        return x;
    }

    static long median(List<Long> v) {
        if (v == null || v.isEmpty()) return -1;
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static JobManager.Snapshot copy(JobManager.Snapshot s) {
        JobManager.Snapshot c = new JobManager.Snapshot();
        c.state = s.state; c.stage = s.stage; c.detail = s.detail; c.fraction = s.fraction; c.result = s.result; c.error = s.error;
        return c;
    }
}
