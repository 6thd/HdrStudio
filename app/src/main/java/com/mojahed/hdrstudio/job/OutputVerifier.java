package com.mojahed.hdrstudio.job;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.mojahed.hdrstudio.encode.Check;
import com.mojahed.hdrstudio.encode.Hdr10FileCheck;
import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.HevcBitstream;
import com.mojahed.hdrstudio.encode.PixelDiagnostics;
import com.mojahed.hdrstudio.encode.StreamProbe;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Re-opens the finished file and checks what is really in it - container tags, the HEVC
 * bitstream itself (SPS bit depth, VUI colour description, SEI metadata on every IRAP, parsed
 * independently), frame count, duration, audio, decodability. Every hard property is its own
 * {@link Check}; any FAIL means the file is not published as a success.
 * Decoded-pixel statistics of arbitrary content are a diagnostic only (black, flat or faded
 * pictures are valid 10-bit video with few codes); gradation precision is the path test's job.
 * These are facts about the file, not a statement about picture quality.
 */
public final class OutputVerifier {
    public static final class Report {
        public boolean ok;                 // no hard check failed
        public final List<Check> checks = new ArrayList<>();
        public final StringBuilder text = new StringBuilder();
        public final StringBuilder warnings = new StringBuilder();
        public final StringBuilder failures = new StringBuilder();
        public int videoFrames;
        public long videoDurUs, audioDurUs;
        public boolean hasAudio;
        public PixelDiagnostics.Result pixels;

        void add(Check c) {
            checks.add(c);
            if (c.failed()) failures.append("✗ ").append(c.what).append(": ").append(c.detail).append('\n');
        }
        void fail(String what, String s) { add(Check.fail(what, s)); }
    }

    /**
     * @param expectedFrames frames the export scheduled and encoded
     * @param meta HDR metadata that was configured (null for SDR) - SEI content must match it.
     */
    public static Report verify(FileDescriptor fd, boolean hdr, int expectedFrames, boolean expectAudio, HdrMetadata meta) {
        Report r = new Report();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(fd);
            int v = -1, a = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (v < 0 && m.startsWith("video/")) v = i;
                if (a < 0 && m.startsWith("audio/")) a = i;
            }
            if (v < 0) { r.fail("مسار الفيديو", "لا يوجد مسار فيديو في الناتج"); return finish(r); }
            MediaFormat vf = ex.getTrackFormat(v);
            String mime = vf.getString(MediaFormat.KEY_MIME);
            boolean hevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime);
            r.text.append("الفيديو: ").append(mime).append(' ').append(vf.getInteger(MediaFormat.KEY_WIDTH)).append('×').append(vf.getInteger(MediaFormat.KEY_HEIGHT)).append('\n');

            // ---- 1. container colour tags
            int std = key(vf, MediaFormat.KEY_COLOR_STANDARD), tr = key(vf, MediaFormat.KEY_COLOR_TRANSFER), rg = key(vf, MediaFormat.KEY_COLOR_RANGE);
            boolean tagsOk = hdr ? (std == MediaFormat.COLOR_STANDARD_BT2020 && tr == MediaFormat.COLOR_TRANSFER_ST2084 && rg == MediaFormat.COLOR_RANGE_LIMITED)
                    : (std == MediaFormat.COLOR_STANDARD_BT709 && tr == MediaFormat.COLOR_TRANSFER_SDR_VIDEO && rg == MediaFormat.COLOR_RANGE_LIMITED);
            r.add(Check.of(tagsOk, "وسوم الحاوية", std + "/" + tr + "/" + rg + " (المطلوب " + (hdr ? "BT.2020/PQ/Limited" : "BT.709/Limited") + ")"));

            // ---- 2. bitstream signalling (SPS/VUI) and first SEI
            ex.selectTrack(v);
            HevcBitstream.Info bi = null;
            if (hevc) {
                bi = StreamProbe.inspectBitstream(ex, vf, 3);
                r.text.append("البتستريم: ").append(bi.describe()).append('\n');
                if (hdr) r.add(Hdr10FileCheck.signalling(bi));
                else if (!bi.spsFound) r.fail("البتستريم (SPS/VUI)", "تعذر قراءة SPS");
                else if (bi.colourDescription && (bi.colourPrimaries != 1 || bi.transfer != 1 || bi.matrix != 1))
                    r.fail("البتستريم (SPS/VUI)", "وصف الألوان في VUI ليس BT.709");
                else {
                    r.add(Check.pass("البتستريم (SPS/VUI)", bi.bitDepthLuma + "-bit" + (bi.colourDescription ? " · BT.709" : " · بلا وصف ألوان")));
                    if (bi.bitDepthLuma != 8) r.warnings.append("ملف SDR بعمق ").append(bi.bitDepthLuma).append("-bit\n");
                }
            } else {
                r.text.append("البتستريم: ").append(mime).append(" - لا يوجد محلل مستقل لهذا الترميز (الاعتماد على وسوم الحاوية والمفكك)\n");
            }
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // ---- 3. decoded samples: diagnostic only
            if (hdr) {
                StreamProbe.PixelDepth pd = StreamProbe.probePixels(ex, vf, 3, null, 0);
                if (pd.measured()) {
                    r.pixels = pd.diagnostics();
                    r.text.append(r.pixels.describe()).append('\n');
                    if (r.pixels.outcome == PixelDiagnostics.Outcome.SUSPECT_8BIT) r.warnings.append(r.pixels.describe()).append('\n');
                } else {
                    r.text.append(pd.describe()).append(" (تشخيص فقط)\n");
                }
                ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            ex.unselectTrack(v);

            // ---- 4. every video sample: frame count, duration, SEI on every IRAP (HDR10)
            ex.selectTrack(v);
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            Hdr10FileCheck.IrapScan scan = hdr && hevc && meta != null ? new Hdr10FileCheck.IrapScan(meta) : null;
            ByteBuffer sb = scan != null ? ByteBuffer.allocate(maxInput(vf)) : null;
            long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
            int n = 0;
            while (ex.getSampleTime() >= 0) {
                long t = ex.getSampleTime(); first = Math.min(first, t); last = Math.max(last, t); n++;
                if (scan != null) {
                    sb.clear();
                    int len = ex.readSampleData(sb, 0);
                    if (len > 0) { byte[] au = new byte[len]; sb.position(0); sb.get(au, 0, len); scan.add(au); }
                }
                if (!ex.advance()) break;
            }
            ex.unselectTrack(v);
            if (hdr) {
                if (!hevc) r.fail("بيانات HDR الثابتة النهائية في الملف", "HDR10 يتطلب HEVC");
                else r.add(Hdr10FileCheck.finalMetadata(bi, meta, scan));
            }
            r.videoFrames = n;
            r.videoDurUs = n > 1 ? last - first : 0;
            r.add(Check.of(n == expectedFrames, "عدد الإطارات", String.format(Locale.US, "في الملف %d · المجدول والمرمَّز %d", n, expectedFrames)));
            if (a >= 0) {
                r.hasAudio = true;
                ex.selectTrack(a);
                long af = Long.MAX_VALUE, al = Long.MIN_VALUE;
                while (ex.getSampleTime() >= 0) { long t = ex.getSampleTime(); af = Math.min(af, t); al = Math.max(al, t); if (!ex.advance()) break; }
                ex.unselectTrack(a);
                r.audioDurUs = al - af;
                double drift = Math.abs((al - af) - r.videoDurUs) / 1000.0;
                r.add(Check.pass("الصوت", String.format(Locale.US, "بداية %.1f ms · فرق المدة عن الفيديو %.0f ms", af / 1000.0, drift)));
                if (drift > 250) r.warnings.append("مدة الصوت تختلف عن الفيديو بأكثر من 250ms (قد يكون من المصدر)\n");
            } else if (expectAudio) r.fail("الصوت", "مفقود رغم وجوده في المصدر");
            else r.text.append("الصوت: لا يوجد\n");

            // ---- 5. decodability
            ex.selectTrack(v);
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            String dr = decodeFirst(ex, vf);
            r.add(Check.of(dr.equals("ok"), "فك ترميز الإطار الأول", dr));
        } catch (Exception e) {
            r.fail("فحص الملف", "تعذر الفحص: " + e.getMessage());
        } finally {
            ex.release();
        }
        return finish(r);
    }

    private static Report finish(Report r) {
        r.ok = !r.checks.isEmpty() && r.failures.length() == 0;
        StringBuilder head = new StringBuilder("حقائق الملف (ليست حكماً على الجودة):\n");
        for (Check c : r.checks) head.append(c.line()).append('\n');
        r.text.insert(0, head);
        if (!r.ok) r.text.append("أسباب الرفض:\n").append(r.failures);
        return r;
    }

    private static int maxInput(MediaFormat f) {
        int m = f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
        return Math.max(m, 16 << 20);
    }

    private static int key(MediaFormat f, String k) { return f.containsKey(k) ? f.getInteger(k) : -1; }

    private static String decodeFirst(MediaExtractor ex, MediaFormat vf) {
        MediaCodec dec = null;
        try {
            dec = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME));
            dec.configure(vf, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long end = System.currentTimeMillis() + 5000;
            boolean in = false;
            while (System.currentTimeMillis() < end) {
                if (!in) {
                    int i = dec.dequeueInputBuffer(5000);
                    if (i >= 0) {
                        int n = ex.readSampleData(dec.getInputBuffer(i), 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); in = true; }
                        else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int o = dec.dequeueOutputBuffer(bi, 5000);
                if (o >= 0) { dec.releaseOutputBuffer(o, false); return "ok"; }
            }
            return "انتهت المهلة (5 ث) دون إطار";
        } catch (Exception e) {
            return "خطأ " + e.getMessage();
        } finally {
            if (dec != null) try { dec.stop(); dec.release(); } catch (RuntimeException ignored) { }
        }
    }
}
