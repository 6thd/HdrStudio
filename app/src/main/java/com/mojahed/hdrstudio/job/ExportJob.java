package com.mojahed.hdrstudio.job;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.MediaStore;

import com.mojahed.hdrstudio.ai.SrEngine;
import com.mojahed.hdrstudio.core.DeviceCaps;
import com.mojahed.hdrstudio.core.FrameRate;
import com.mojahed.hdrstudio.core.MemoryBudget;
import com.mojahed.hdrstudio.core.SourceInfo;
import com.mojahed.hdrstudio.encode.AudioTrackSource;
import com.mojahed.hdrstudio.encode.Check;
import com.mojahed.hdrstudio.encode.EncodeConfig;
import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.MuxerSink;
import com.mojahed.hdrstudio.encode.VideoEncoder;
import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.gl.Tex;
import com.mojahed.hdrstudio.stages.FramePipeline;
import com.mojahed.hdrstudio.stages.PipelineParams;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One export: plan -> (optional) light-level analysis pass -> render+encode pass -> verify ->
 * publish. Nothing is reported as done before the file is complete, verified and committed;
 * pending MediaStore entries and temp files are removed on failure or cancel.
 */
public final class ExportJob implements Runnable {
    private final Context ctx;
    private final ExportRequest req;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final JobManager jm = JobManager.get();
    private JobManager.Snapshot snap = new JobManager.Snapshot();
    private long lastPublish;
    private double emaMs = -1;

    // plan
    private int outW, outH;
    private long bitrate;
    private DeviceCaps.Enc enc;
    private EncodeConfig config;
    private boolean hdrOut;
    private boolean pipelineMemReported;
    private final StringBuilder report = new StringBuilder();

    public ExportJob(Context ctx, ExportRequest req) { this.ctx = ctx.getApplicationContext(); this.req = req; }

    public void cancel() { cancel.set(true); }

    // ------------------------------------------------------------------ progress
    private void stage(String s) { snap.stage = s; publish(true); }

    private void publish(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPublish < 400) return;
        lastPublish = now;
        JobManager.Snapshot c = new JobManager.Snapshot();
        c.state = snap.state; c.preview = req.preview; c.stage = snap.stage; c.detail = snap.detail; c.fraction = snap.fraction;
        c.fps = snap.fps; c.etaSec = snap.etaSec; c.msPerFrame = snap.msPerFrame; c.result = snap.result; c.error = snap.error;
        c.outputUri = snap.outputUri; c.outputPath = snap.outputPath; c.thermal = snap.thermal;
        jm.publish(c);
    }

    private void frameTick(long t0, int done, int total, int passIndex, int passes) {
        double ms = (System.nanoTime() - t0) / 1e6;
        emaMs = emaMs < 0 ? ms : emaMs * 0.9 + ms * 0.1;
        snap.msPerFrame = emaMs;
        snap.fps = 1000.0 / emaMs;
        int remainingThisPass = Math.max(0, total - done);
        // only the measured pass time is used; a later pass is estimated with this pass' rate only
        // when it runs the same work (analysis pass ~ render pass without encoding)
        snap.etaSec = (remainingThisPass + (long) (passes - passIndex - 1) * total) * emaMs / 1000.0;
        snap.fraction = (float) ((passIndex + done / (double) Math.max(1, total)) / passes);
        snap.detail = String.format(Locale.US, "%d / %d إطار · %.2f fps", done, total, snap.fps);
        publish(false);
    }

    private void thermal(PowerManager pm) throws InterruptedException {
        int st = pm.getCurrentThermalStatus();
        if (st >= PowerManager.THERMAL_STATUS_CRITICAL) {
            snap.thermal = "حرارة حرجة: إيقاف مؤقت للتبريد";
            publish(true);
            long until = System.currentTimeMillis() + 5 * 60_000;
            while (pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE && System.currentTimeMillis() < until) {
                if (cancel.get()) throw new CancellationException();
                Thread.sleep(2000);
            }
            snap.thermal = "";
        } else if (st >= PowerManager.THERMAL_STATUS_SEVERE) {
            snap.thermal = "الجهاز ساخن: تم إبطاء المعالجة";
            Thread.sleep(60);
        } else if (st >= PowerManager.THERMAL_STATUS_MODERATE) {
            snap.thermal = "حرارة متوسطة";
            Thread.sleep(10);
        } else snap.thermal = "";
    }

    /** Cancel and critical-heat pause between AI tiles, so a 4K frame does not run unchecked. */
    private void tileGate(PowerManager pm) {
        if (cancel.get()) throw new CancellationException();
        if (pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_CRITICAL) {
            try { thermal(pm); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        }
    }

    private FramePipeline newPipeline(GlKit kit, SourceInfo s, PipelineParams p, SrEngine sr, final PowerManager pm, MemoryBudget.Plan mem) {
        long before = kit.allocatedBytes();
        FramePipeline fp = new FramePipeline(kit, s.width, s.height, p, sr);
        fp.setTileGate(new FramePipeline.TileGate() {
            @Override public void between(int done, int total) { tileGate(pm); }
        });
        if (!pipelineMemReported) {
            pipelineMemReported = true;
            report.append(String.format(Locale.US, "ذاكرة GPU للمعالجة عند الإنشاء: %d MB (المتوقع %d MB؛ التاريخ الزمني وبلاطات AI تُحجز عند أول إطار)\n",
                    (kit.allocatedBytes() - before + (1 << 19)) >> 20, (mem.pipeline + (1 << 19)) >> 20));
        }
        return fp;
    }

    // ------------------------------------------------------------------ plan
    private void plan(DeviceCaps caps) {
        SourceInfo s = req.src;
        PipelineParams p = req.params;
        hdrOut = req.preset.hdr;
        p.applySource(s, hdrOut);
        Plan pl = planOutput(caps, s, req.preset, req.targetShortSide, req.bitrateOverride);
        if (pl.error != null) throw new IllegalStateException(pl.error);
        enc = pl.enc; config = pl.config; outW = config.width; outH = config.height; bitrate = config.bitrate;
        p.outW = outW; p.outH = outH;
        EncodeSelfTest.Result st = req.selfTest;
        if (st == null || !st.matches(config))
            throw new IllegalStateException("لم يُفحص مسار التصدير بهذا الإعداد بالضبط (" + config.describe() + "). شغّل الفحص لهذا الإعداد أولاً.");
        if (!st.usable())
            throw new IllegalStateException("فحص مسار التصدير لم يسمح بهذا الإعداد:\n" + st.verdict.summary());
        report.append("فحص مسار التصدير بهذا الإعداد:\n").append(st.verdict.summary()).append('\n');
        report.append(String.format(Locale.US, "الخطة: %d×%d → %s · %s · %s\n", s.width, s.height, config.describe(), req.preset.label,
                s.vfr ? "المصدر متغير المعدل: الطوابع الزمنية الأصلية تُحفظ، ومعدل الإطارات للمرمّز تلميح فقط" : "الطوابع الزمنية من المصدر"));
        if (outW != pl.rawW || outH != pl.rawH) report.append("تمت محاذاة الأبعاد لمتطلبات المرمّز (").append(pl.rawW).append('×').append(pl.rawH).append(")\n");
        if (req.bitrateOverride > 0 && bitrate != req.bitrateOverride)
            report.append(String.format(Locale.US, "معدل البت المطلوب %.2f Mbps حُدّ إلى مدى المرمّز: %.2f Mbps\n", req.bitrateOverride / 1e6, bitrate / 1e6));
    }

    /** Output plan shared by the export and the UI (the self test must run with exactly config). */
    public static final class Plan {
        public DeviceCaps.Enc enc; public int rawW, rawH; public FrameRate rate; public EncodeConfig config; public String error;
    }

    /** @param requestedBitrate bit/s before the encoder's clamp; <= 0 = the preset's starting point */
    public static Plan planOutput(DeviceCaps caps, SourceInfo s, com.mojahed.hdrstudio.encode.Presets preset, int targetShortSide, long requestedBitrate) {
        int[] d = planDims(s, preset, targetShortSide);
        return planFor(caps, preset, d[0], d[1], FrameRate.fromAverage(s.avgFps), requestedBitrate);
    }

    public static Plan planFor(DeviceCaps caps, com.mojahed.hdrstudio.encode.Presets preset, int rawW, int rawH, FrameRate rate, long requestedBitrate) {
        Plan pl = new Plan();
        pl.rawW = rawW; pl.rawH = rawH; pl.rate = rate;
        double fps = rate.fps();
        String mime = preset.hdr ? DeviceCaps.HEVC : preset.mime;
        DeviceCaps.Enc e = preset.hdr ? caps.pickHdrEncoder(rawW, rawH, fps) : caps.pickSdrEncoder(mime, rawW, rawH, fps);
        if (e == null) { pl.error = "لا يوجد مرمّز " + (preset.hdr ? "HEVC 10-bit" : mime) + " يدعم " + rawW + "×" + rawH; return pl; }
        pl.enc = e;
        long want = requestedBitrate > 0 ? requestedBitrate : preset.startBitrate(rawW, rawH, fps);
        pl.config = VideoEncoder.plan(e, preset.hdr, rawW, rawH, rate, want, preset.iFrameSec);
        if (!e.supports(pl.config.width, pl.config.height, fps))
            pl.error = String.format(Locale.US, "المرمّز %s لا يدعم %d×%d عند %s fps. اختر دقة أقل.", e.name, pl.config.width, pl.config.height, rate.label());
        return pl;
    }

    /** Output size before encoder alignment: square pixels, target short side, preset cap. */
    public static int[] planDims(SourceInfo s, com.mojahed.hdrstudio.encode.Presets preset, int targetShortSide) {
        int w = s.squareW(), h = s.height;
        int shortSide = Math.min(w, h);
        int target = targetShortSide > 0 ? targetShortSide : shortSide;
        if (preset.maxShortSide > 0) target = Math.min(target, preset.maxShortSide);
        double scale = (double) target / shortSide;
        return new int[]{(int) Math.round(w * scale) & ~1, (int) Math.round(h * scale) & ~1};
    }

    private void checkSpace(long durUs) {
        long need = (long) ((bitrate + 320_000) / 8.0 * (durUs / 1e6) * 1.1) + (20L << 20);
        File dir = req.preview ? ctx.getCacheDir() : Environment.getExternalStorageDirectory();
        long avail = new StatFs(dir.getPath()).getAvailableBytes();
        if (avail < need)
            throw new IllegalStateException(String.format(Locale.US, "المساحة غير كافية: يلزم نحو %d MB والمتاح %d MB. قلّل معدل البت أو المدة.",
                    need >> 20, avail >> 20));
    }

    // ------------------------------------------------------------------ run
    @Override
    public void run() {
        snap.state = JobManager.State.RUNNING;
        stage("التحضير");
        Uri pendingUri = null;
        File cacheOut = null;
        ParcelFileDescriptor pfd = null;
        Egl egl = null;
        GlKit kit = null;
        SrEngine sr = null;
        FramePipeline pipe = null;
        FrameSource fs = null;
        VideoEncoder ve = null;
        MuxerSink mux = null;
        AudioTrackSource audio = null;
        Egl.WindowSurface ws = null;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        try {
            DeviceCaps caps = DeviceCaps.collect(ctx);
            plan(caps);
            SourceInfo s = req.src;
            long start = req.startUs, end = req.preview ? req.endUs : Long.MAX_VALUE;
            long durUs = req.preview ? Math.min(end, s.lastPtsUs) - start : s.durationUs;
            int total = req.preview ? (int) Math.max(1, Math.round(durUs / 1e6 * s.avgFps)) : s.frameCount;
            checkSpace(durUs);
            PipelineParams p = req.params;
            boolean yuvPath = s.isHdr() || s.bitDepth > 8;
            MemoryBudget.Plan mem = MemoryBudget.plan(s.width, s.height, yuvPath, outW, outH, hdrOut,
                    p.temporal, p.ai, p.aiWorkScale, caps.memAvail, caps.memTotal);
            report.append(mem.describe()).append('\n');
            if (!mem.fits)
                throw new IllegalStateException("فوق الميزانية المحافظة لهذه الإعدادات (نصف الذاكرة المتاحة، وثلث الذاكرة الكلية كحد). أغلق التطبيقات الأخرى أو اختر دقة أقل يدوياً.\n" + mem.describe());
            final PowerManager pmFinal = pm;

            egl = new Egl(hdrOut);
            egl.makeCurrentOffscreen();
            kit = new GlKit(new ShaderLoader(ctx.getAssets()));
            if (p.ai) {
                stage("تحميل نموذج AI وفحصه");
                sr = new SrEngine(kit, ctx.getAssets());
                if (!sr.selfTest(ctx.getAssets())) throw new IllegalStateException("AI " + sr.status);
                p.aiTile = mem.tile;
                report.append("AI: ").append(sr.status).append(" · حجم البلاطة ").append(p.aiTile).append('\n');
            }
            HdrMetadata meta = null;
            int passes = 1;
            if (hdrOut) {
                meta = HdrMetadata.p3(p.workDomain == 1 ? Math.max(1000, p.srcPeak) : p.peak);
                if (req.measureLight) passes = 2;
            }
            Tex in = kit.tex(s.width, s.height, false);
            // ---------------- pass 1: exact MaxCLL / MaxFALL of the final pixels
            if (passes == 2) {
                stage("المرحلة 1/2: قياس الإضاءة (MaxCLL/MaxFALL)");
                pipe = newPipeline(kit, s, p, sr, pmFinal, mem);
                fs = new FrameSource(ctx, req.uri, s, yuvPath, start, end);
                double cll = 0, fall = 0;
                int n = 0;
                long prevPts = -1;
                while (true) {
                    long t0 = System.nanoTime();
                    if (!fs.next(kit, in, start, cancel)) break;
                    double dt = prevPts < 0 ? 1 / s.avgFps : (fs.ptsUs - prevPts) / 1e6;
                    prevPts = fs.ptsUs;
                    pipe.process(in, dt);
                    double[] l = pipe.measureLight();
                    cll = Math.max(cll, l[0]); fall = Math.max(fall, l[1]);
                    n++;
                    thermal(pm);
                    frameTick(t0, n, total, 0, 2);
                }
                fs.release(); fs = null;
                pipe.release(); pipe = null;
                meta.setMeasured((float) cll, (float) fall, "مقاس من كل بكسلات الناتج قبل الضغط");
                report.append(String.format(Locale.US, "قياس الإضاءة: MaxCLL %.0f · MaxFALL %.0f nit (%d إطار)\n", cll, fall, n));
            }
            // ---------------- output target
            stage(passes == 2 ? "المرحلة 2/2: المعالجة والترميز" : "المعالجة والترميز");
            String name = "HDRStudio_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + ".mp4";
            if (req.preview) {
                cacheOut = new File(ctx.getCacheDir(), "preview_clip.mp4");
                cacheOut.delete();
                pfd = ParcelFileDescriptor.open(cacheOut, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
            } else {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HDR Studio");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                pendingUri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (pendingUri == null) throw new IOException("تعذر إنشاء ملف في المعرض");
                pfd = ctx.getContentResolver().openFileDescriptor(pendingUri, "rw");
            }
            mux = new MuxerSink(pfd.getFileDescriptor(), s.rotation);
            long offsetUs = req.preview ? Math.max(0, req.startUs) : 0;
            if (s.audioTrack >= 0 && req.keepAudio) {
                stage("تجهيز الصوت");
                audio = AudioTrackSource.open(ctx, req.uri, s.audioTrack, ctx.getCacheDir(), start, end, offsetUs, cancel);
                mux.setAudioFormat(audio.format);
                report.append("الصوت: ").append(audio.mode.equals("copy") ? "نسخ كما هو" : "أعيد ترميزه إلى AAC (الصيغة الأصلية غير مدعومة في MP4)").append('\n');
            } else if (s.audioTrack >= 0) report.append("الصوت: حُذف بطلب المستخدم\n");
            stage(passes == 2 ? "المرحلة 2/2: المعالجة والترميز" : "المعالجة والترميز");
            ve = new VideoEncoder(enc, config, meta, mux);
            final AudioTrackSource au = audio;
            final MuxerSink mx = mux;
            if (au != null) ve.setListener(new VideoEncoder.Listener() {
                @Override public void onSample(android.media.MediaCodec.BufferInfo info) { au.pumpUntil(mx, info.presentationTimeUs + 500_000); }
            });
            ws = egl.createWindowSurface(ve.inputSurface, hdrOut);
            egl.makeCurrent(ws.surface);
            pipe = newPipeline(kit, s, p, sr, pmFinal, mem);
            fs = new FrameSource(ctx, req.uri, s, yuvPath, start, end);
            EncodeSelfTest.Result st = req.selfTest;
            Map<String, Object> u = new HashMap<>();
            u.put("uComp", st != null && st.usable() ? st.comp : com.mojahed.hdrstudio.core.ColorMath.IDENTITY3);
            u.put("uCompOffset", st != null && st.usable() ? st.compOffset : new float[3]);
            u.put("uLsb", hdrOut ? 1f / 1023 : 1f / 255);
            u.put("uDither", true); u.put("uFlipY", false);
            double cll = 0, fall = 0;
            int n = 0;
            long prevPts = -1;
            while (true) {
                long t0 = System.nanoTime();
                if (!fs.next(kit, in, start, cancel)) break;
                double dt = prevPts < 0 ? 1 / s.avgFps : (fs.ptsUs - prevPts) / 1e6;
                prevPts = fs.ptsUs;
                Tex out = pipe.process(in, dt);
                if (hdrOut && passes == 1 && n % 4 == 0) {
                    double[] l = pipe.measureLight();
                    cll = Math.max(cll, l[0]); fall = Math.max(fall, l[1]);
                }
                u.put("uIn", out);
                kit.runToSurface("present.frag", u, outW, outH);
                egl.swap(ws.surface, (fs.ptsUs - offsetUs) * 1000L);
                ve.checkError();
                n++;
                thermal(pm);
                frameTick(t0, n, total, passes - 1, passes);
            }
            stage("إنهاء الترميز");
            ve.signalEndOfInput();
            if (!ve.awaitEos(60_000)) throw new IllegalStateException("المرمّز لم ينهِ الملف خلال المهلة");
            if (audio != null) audio.pumpUntil(mux, Long.MAX_VALUE);
            if (!mux.stop()) throw new IllegalStateException("لم يُكتب أي إطار (المرمّز لم يبدأ)");
            report.append(String.format(Locale.US, "مكتوب: %d إطار فيديو · %d عينة صوت · %.1f MB\n", mux.videoSamples, mux.audioSamples, mux.bytes / 1e6));
            if (hdrOut) {
                report.append(meta.describe()).append('\n');
                if (passes == 1 && n > 0)
                    report.append(String.format(Locale.US, "تقدير لاحق (ربع الإطارات، غير مكتوب في الملف): MaxCLL≈%.0f · MaxFALL≈%.0f\n", cll, fall));
            }
            // ---------------- verify before publishing
            stage("فحص الملف الناتج");
            OutputVerifier.Report vr = OutputVerifier.verify(pfd.getFileDescriptor(), hdrOut, n, audio != null, meta);
            report.append(vr.text);
            if (vr.warnings.length() > 0) report.append("تنبيهات:\n").append(vr.warnings);
            if (!vr.ok) throw new IllegalStateException("رُفض الملف الناتج ولم يُحفظ:\n" + vr.failures);
            if (hdrOut) {
                if (ve.seiGuard != null) report.append("SEI 137/144: ").append(ve.seiGuard.describe()).append('\n');
                Check g = req.selfTest.verdict.gradation;
                report.append("دقة التدرج: ").append(g.passed() ? "اجتاز اختبار دقة التدرج بهذه الإعدادات في فحص المسار (لا تُقاس على محتوى الملف نفسه)"
                        : "لم تُقس على هذا الجهاز (المفكك لا يخرج P010)؛ التحقق يقتصر على البتستريم والألوان والوسوم والبيانات").append('\n');
            }
            pfd.close(); pfd = null;
            if (pendingUri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(pendingUri, v, null, null);
                snap.outputUri = pendingUri.toString();
                pendingUri = null;   // committed
                report.append("حُفظ في: Movies/HDR Studio/").append(name).append('\n');
            } else {
                snap.outputPath = cacheOut.getAbsolutePath();
            }
            snap.result = report.toString().trim();
            snap.state = JobManager.State.DONE;
            snap.fraction = 1;
            stage("اكتمل");
        } catch (CancellationException e) {
            snap.state = JobManager.State.CANCELLED;
            snap.error = "أُلغي التصدير";
            stage("أُلغي");
        } catch (Throwable t) {
            snap.state = JobManager.State.FAILED;
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            if (msg.contains("ENOSPC") || msg.toLowerCase(Locale.US).contains("no space")) msg = "امتلأت مساحة التخزين أثناء الكتابة";
            snap.error = msg;
            snap.result = report.toString().trim();
            stage("فشل");
        } finally {
            if (fs != null) try { fs.release(); } catch (Throwable ignored) { }
            if (pipe != null) try { pipe.release(); } catch (Throwable ignored) { }
            if (sr != null) try { sr.release(); } catch (Throwable ignored) { }
            if (ve != null) try { ve.release(); } catch (Throwable ignored) { }
            if (mux != null) mux.release();
            if (audio != null) audio.release();
            if (kit != null) try { kit.release(); } catch (Throwable ignored) { }
            if (egl != null) {
                if (ws != null) try { egl.makeCurrentOffscreen(); egl.destroySurface(ws.surface); } catch (Throwable ignored) { }
                try { egl.release(); } catch (Throwable ignored) { }
            }
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) { }
            if (pendingUri != null) {          // not committed -> remove the partial file
                try { ctx.getContentResolver().delete(pendingUri, null, null); } catch (RuntimeException ignored) { }
            }
            if (snap.state != JobManager.State.DONE && cacheOut != null) cacheOut.delete();
            new File(ctx.getCacheDir(), "audio_transcoded.m4a").delete();
            publish(true);
        }
    }

    /** Removes our own pending (never committed) MediaStore items left by a killed process. */
    public static void cleanupStalePending(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;   // QUERY_ARG_MATCH_PENDING needs API 30
        try {
            ContentResolver cr = ctx.getContentResolver();
            android.os.Bundle q = new android.os.Bundle();
            q.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY);
            q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?");
            q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{"%HDR Studio%"});
            try (android.database.Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Video.Media._ID}, q, null)) {
                while (c != null && c.moveToNext()) {
                    Uri u = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                    cr.delete(u, null, null);
                }
            }
        } catch (RuntimeException ignored) { }
    }
}
