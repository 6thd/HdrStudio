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
 * Export diagnosis: the same one-second SDR range exported step by step to isolate where frames are lost.
 *  1. without AI (decoder → pipeline → encoder → muxer only);
 *  2. with GLSL AI (only if 1 produced a file; otherwise the loss is not caused by AI and the test stops);
 *  3. only if 1 passed and 2 failed: without AI again, but waiting after each frame as long as a GLSL frame
 *     took (no GPU work), to tell "slow frames" apart from "the AI work itself".
 * Every run carries its frame trace; a rejected file is kept in the app's own storage with its report.
 * Nothing here changes a setting, the colours, the model or the acceptance rules.
 */
public final class ExportDiag implements JobManager.Batch {
    public static final long LENGTH_US = 1_000_000L;
    static final long MAX_DELAY_MS = 12_000;
    private final ExportRequest base;

    public ExportDiag(ExportRequest base) { this.base = base; }

    private ExportRequest variant(String tag, boolean ai, long delayMs) {
        ExportRequest q = new ExportRequest();
        q.uri = base.uri; q.src = base.src; q.preset = base.preset; q.targetShortSide = base.targetShortSide;
        q.bitrateOverride = base.bitrateOverride; q.keepAudio = base.keepAudio; q.selfTest = base.selfTest;
        q.measureLight = false; q.preview = false;
        q.startUs = base.startUs; q.endUs = base.endUs;
        q.params = base.params.copy();
        q.params.ai = ai;
        q.params.aiNcnn = false;
        q.abTag = tag;
        q.diagFrameDelayMs = delayMs;
        return q;
    }

    private static final class Run {
        String name; ExportJob job; String interruption;
    }

    @Override public void run(Context ctx) {
        StringBuilder r = new StringBuilder();
        JobManager.Snapshot snap = new JobManager.Snapshot();
        snap.state = JobManager.State.RUNNING;
        snap.stage = "تشخيص التصدير";
        JobManager.get().publish(snap);
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        r.append("تشخيص التصدير — المقطع نفسه (ثانية SDR) على مراحل لعزل مكان ضياع الإطارات\n");
        r.append(BuildIdentity.describe(ctx)).append('\n');
        r.append(String.format(Locale.US, "المصدر: %dx%d · %.3f fps · المدى %.3f–%.3f ث · الإعداد %s · الصوت %s\n", base.src.width, base.src.height,
                base.src.avgFps, base.startUs / 1e6, base.endUs / 1e6, base.preset.label, base.keepAudio ? "يُحفظ" : "يُحذف"));
        r.append(String.format(Locale.US, "مهلة انعدام التقدم: يتوقف التصدير إذا أُرسل %d إطاراً للمرمّز ومرّت %d ث على أولها دون أي إطار خارج منه.\n",
                ExportJob.STALL_FRAMES, ExportJob.STALL_MS / 1000));
        List<Run> runs = new ArrayList<>();
        try {
            Run a = export(ctx, pm, snap, r, "1 بلا AI", variant("DIAG_noAI", false, 0));
            runs.add(a);
            if (a.job.state() != JobManager.State.DONE) {
                r.append("\nتوقف التشخيص بعد الخطوة 1: الفشل يحدث دون AI، فمكانه في مسار الفك/المعالجة/الترميز/الكتابة نفسه، لا في AI.\n");
            } else {
                Run b = export(ctx, pm, snap, r, "2 GLSL", variant("DIAG_GLSL", true, 0));
                runs.add(b);
                if (b.job.state() != JobManager.State.DONE) {
                    long delay = median(b.job.frameMs);
                    if (delay <= 0) r.append("\nالخطوة 3 لم تُشغّل: لا زمن إطار مقاس من خطوة GLSL.\n");
                    else {
                        delay = Math.min(delay, MAX_DELAY_MS);
                        r.append(String.format(Locale.US, "\nالخطوة 3: بلا AI مع انتظار %d ms بعد كل إطار (وسيط زمن إطار GLSL، بحد أقصى %d ms)، دون عمل على GPU.\n",
                                delay, MAX_DELAY_MS));
                        runs.add(export(ctx, pm, snap, r, "3 بلا AI + انتظار", variant("DIAG_noAI_delay", false, delay)));
                    }
                }
            }
            r.append("\nالخلاصة (ملاحظات مقاسة، لا أسباب مثبتة):\n");
            for (Run x : runs) {
                PipeTrace t = x.job.trace;
                r.append(String.format(Locale.US, "  %s: %s · %.1f ث", x.name, x.job.state(), x.job.wallMs > 0 ? x.job.wallMs / 1e3 : 0));
                if (t != null) {
                    r.append(String.format(Locale.US, " · داخل الفترة %d · عولجت %d · أُرسلت %d · خرجت من المرمّز %d · كُتبت %d · صوت %d",
                            t.count(PipeTrace.IN_RANGE), t.count(PipeTrace.PROCESSED), t.count(PipeTrace.SENT), t.count(PipeTrace.ENC_OUT),
                            t.count(PipeTrace.WRITTEN), t.count(PipeTrace.AUDIO)));
                    String loss = t.firstLoss();
                    if (loss != null) r.append(" · ").append(loss);
                }
                if (x.interruption != null) r.append(" · منقطع: ").append(x.interruption);
                r.append('\n');
            }
            r.append("لم يتغير أي إعداد. الملفات الناجحة في Movies/HDR Studio باسم ‎…_AB_…_DIAG_…‎، والمرفوضة داخل مساحة التطبيق فقط.\n");
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
