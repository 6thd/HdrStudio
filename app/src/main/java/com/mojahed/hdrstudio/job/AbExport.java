package com.mojahed.hdrstudio.job;

import android.content.Context;
import android.net.Uri;
import android.os.PowerManager;

import com.mojahed.hdrstudio.ai.AiEngineCompare;
import com.mojahed.hdrstudio.core.BuildIdentity;
import com.mojahed.hdrstudio.core.RunMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A/B export test: the same 1-second SDR range exported twice with the same settings, first with GLSL,
 * then with ncnn (this build's experimental mode), one after the other in the export service. Both
 * files go to Movies/HDR Studio (…_AB_<time>_GLSL.mp4 / _ncnn.mp4) so they can be watched. The report
 * has the build identity, each run's wall time, frame times, cooling wait (outside the timing),
 * interruptions, frames and audio, and AbCompare's picture and motion comparison.
 * Nothing here changes the default engine (GLSL).
 */
public final class AbExport {
    public static final long LENGTH_US = 1_000_000L;
    private final ExportRequest base;

    public AbExport(ExportRequest base) { this.base = base; }

    private ExportRequest variant(boolean ncnn, int tile) {
        ExportRequest q = new ExportRequest();
        q.uri = base.uri; q.src = base.src; q.preset = base.preset; q.targetShortSide = base.targetShortSide;
        q.bitrateOverride = base.bitrateOverride; q.keepAudio = base.keepAudio; q.selfTest = base.selfTest;
        q.measureLight = false; q.preview = false;
        q.startUs = base.startUs; q.endUs = base.endUs;
        q.params = base.params.copy();
        q.params.aiNcnn = ncnn;
        q.abTag = ncnn ? "ncnn" : "GLSL";
        q.tileOverride = tile;
        return q;
    }

    /** Runs both exports and the comparison; publishes progress and the final report through JobManager. */
    void run(Context ctx) {
        StringBuilder r = new StringBuilder();
        JobManager.Snapshot snap = new JobManager.Snapshot();
        snap.state = JobManager.State.RUNNING;
        snap.stage = "اختبار تصدير A/B";
        JobManager.get().publish(snap);
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        r.append("اختبار تصدير A/B — مقطع SDR مدته ثانية واحدة بالمحركين وبالإعدادات نفسها\n");
        r.append(BuildIdentity.describe(ctx)).append('\n');
        r.append(String.format(Locale.US, "المصدر: %dx%d · %.3f fps · %s · المدى %.3f–%.3f ث\n", base.src.width, base.src.height, base.src.avgFps,
                base.src.isHdr() ? "HDR" : "SDR", base.startUs / 1e6, base.endUs / 1e6));
        r.append(String.format(Locale.US, "الإعدادات: %s · AI دقة العمل %.2f · مقدار %.2f · زمني %s · الصوت %s\n", base.preset.label,
                base.params.aiWorkScale, base.params.aiAmount, base.params.temporal ? "نعم" : "لا", base.keepAudio ? "يُحفظ" : "يُحذف"));
        r.append("ncnn: ").append(AiEngineCompare.exportMode(ctx).label()).append(" — ").append(AiEngineCompare.exportModeReason(ctx)).append('\n');
        r.append("الترتيب: GLSL ثم ncnn. انتظار التبريد قبل كل تصدير خارج الزمن المقاس.\n");
        List<ExportJob> jobs = new ArrayList<>();
        int tile = 0;
        try {
            for (boolean ncnn : new boolean[]{false, true}) {
                String tag = ncnn ? "ncnn" : "GLSL";
                StringBuilder wait = new StringBuilder();
                if (!AiEngineCompare.waitCool(pm, wait, new AiEngineCompare.Progress() {
                    @Override public void onProgress(String line) { snap.stage = line; JobManager.get().publish(copy(snap)); }
                }, tag)) {
                    r.append(wait);
                    throw new IllegalStateException("الهاتف ما زال ساخناً قبل تصدير " + tag + ". أعد الاختبار بعد أن يبرد.");
                }
                r.append(wait);
                ExportRequest q = variant(ncnn, tile);
                ExportJob job = new ExportJob(ctx, q);
                JobManager.get().attach(job);
                RunMonitor.State a = RunMonitor.now(ctx);
                job.run();
                RunMonitor.State b = RunMonitor.now(ctx);
                jobs.add(job);
                String intr = RunMonitor.interruption(a, b);
                r.append(String.format(Locale.US, "\n[%s] %s · من %s إلى %s · %s\n", tag, job.state(), RunMonitor.clock(a.ms), RunMonitor.clock(b.ms),
                        intr == null ? "سليم (التطبيق في الواجهة والشاشة نشطة طوال التصدير)" : "منقطع: " + intr));
                if (job.state() != JobManager.State.DONE) {
                    r.append("فشل: ").append(job.error()).append('\n').append(job.report() == null ? "" : job.report()).append('\n');
                    throw new IllegalStateException("فشل تصدير " + tag);
                }
                if (!ncnn) tile = job.abTile;
                r.append(stats(job));
            }
            // ---- comparison
            snap.stage = "مقارنة الملفين بالمصدر";
            JobManager.get().publish(copy(snap));
            ExportJob ga = jobs.get(0), nb = jobs.get(1);
            r.append(String.format(Locale.US, "\nالزمن الكلي: GLSL %.1f ث · ncnn %.1f ث · النسبة ncnn/GLSL %.2f×\n", ga.wallMs / 1e3, nb.wallMs / 1e3,
                    nb.wallMs / (double) Math.max(1, ga.wallMs)));
            Uri ua = Uri.parse(ga.outputUri()), ub = Uri.parse(nb.outputUri());
            AbCompare.Track cs = AbCompare.container(ctx, base.uri, base.startUs, base.endUs);
            AbCompare.Track ca = AbCompare.container(ctx, ua, 0, Long.MAX_VALUE), cb = AbCompare.container(ctx, ub, 0, Long.MAX_VALUE);
            r.append(String.format(Locale.US, "عدد الإطارات: المصدر في المدى %d · GLSL %d · ncnn %d%s\n", cs.videoFrames, ca.videoFrames, cb.videoFrames,
                    ca.videoFrames == cb.videoFrames && ca.videoFrames == cs.videoFrames ? " (متطابقة)" : " (مختلفة)"));
            r.append(String.format(Locale.US, "مدة الفيديو (أول→آخر طابع): GLSL %.3f ث · ncnn %.3f ث\n", (ca.lastUs - ca.firstUs) / 1e6, (cb.lastUs - cb.firstUs) / 1e6));
            r.append(String.format(Locale.US, "الصوت: المصدر %s (%d عينة في المدى) · GLSL %s (%d عينة، آخر طابع %.3f ث) · ncnn %s (%d عينة، آخر طابع %.3f ث)%s\n",
                    cs.audio, cs.audioSamples, ca.audio, ca.audioSamples, ca.audioLastUs / 1e6, cb.audio, cb.audioSamples, cb.audioLastUs / 1e6,
                    ca.audioSamples == cb.audioSamples ? " (عدد العينات متطابق)" : " (عدد العينات مختلف)"));
            AbCompare.Frames fs = AbCompare.decode(ctx, base.uri, base.startUs, base.endUs, 960);
            AbCompare.Frames fa = AbCompare.decode(ctx, ua, 0, Long.MAX_VALUE, 960);
            AbCompare.Frames fb = AbCompare.decode(ctx, ub, 0, Long.MAX_VALUE, 960);
            r.append(AbCompare.compare(fs, fa, fb));
            r.append("الملفان في Movies/HDR Studio: ").append(ga.outputName).append(" و ").append(nb.outputName).append(" — شاهدهما للحكم البصري أثناء الحركة.\n");
            r.append("الافتراضي يبقى GLSL. هذا الاختبار لا يغيّر أي إعداد.\n");
            snap.state = JobManager.State.DONE;
            snap.stage = "اكتمل اختبار A/B";
        } catch (Throwable t) {
            snap.state = JobManager.State.FAILED;
            snap.stage = "فشل اختبار A/B";
            snap.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            r.append("\nتوقف: ").append(snap.error).append('\n');
        }
        snap.fraction = 1;
        snap.result = r.toString().trim();
        JobManager.Snapshot fin = copy(snap);
        fin.abFinal = true;
        JobManager.get().publish(fin);
    }

    private static String stats(ExportJob j) {
        List<Long> f = new ArrayList<>(j.frameMs);
        Collections.sort(f);
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "  الزمن الكلي %.1f ث · %d إطار مكتوب · %d عينة صوت · بلاطة %d\n", j.wallMs / 1e3, j.videoSamples, j.audioSamples, j.abTile));
        if (!f.isEmpty())
            s.append(String.format(Locale.US, "  زمن الإطار في حلقة المعالجة (فك + معالجة + عرض للمرمّز): أدنى %d · وسيط %d · أعلى %d ms\n",
                    f.get(0), f.get(f.size() / 2), f.get(f.size() - 1)));
        if (j.engine().equals("ncnn") && !j.aiMs.isEmpty()) {
            List<Long> a = new ArrayList<>(j.aiMs);
            Collections.sort(a);
            s.append(String.format(Locale.US, "  مرحلة AI لـ ncnn (شاملة glFinish): وسيط %d ms\n", a.get(a.size() / 2)));
        }
        String rep = j.report();
        if (rep != null) for (String line : rep.split("\n"))
            if (line.startsWith("أول إطار AI") || line.startsWith("مكتوب:") || line.startsWith("الصوت:") || line.startsWith("A/B:") || line.contains("بلاطة"))
                s.append("  ").append(line).append('\n');
        return s.toString();
    }

    private static JobManager.Snapshot copy(JobManager.Snapshot s) {
        JobManager.Snapshot c = new JobManager.Snapshot();
        c.state = s.state; c.stage = s.stage; c.detail = s.detail; c.fraction = s.fraction; c.result = s.result; c.error = s.error;
        return c;
    }
}
