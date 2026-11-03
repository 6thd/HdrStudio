package com.mojahed.hdrstudio.job;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.HevcBitstream;
import com.mojahed.hdrstudio.encode.StreamProbe;

import java.io.FileDescriptor;
import java.util.Locale;

/**
 * Re-opens the finished file and checks what is really in it - container tags, the HEVC
 * bitstream itself (SPS bit depth, VUI colour description, SEI metadata, parsed independently),
 * decoded pixel precision, frame count, duration, audio. Any HDR10 property that is wrong is a
 * HARD failure: the file is then not published as a success.
 */
public final class OutputVerifier {
    public static final class Report {
        public boolean ok;                 // all hard requirements met
        public long seiInjectedFrames;     // informational
        public final StringBuilder text = new StringBuilder();
        public final StringBuilder warnings = new StringBuilder();
        public final StringBuilder failures = new StringBuilder();
        public int videoFrames;
        public long videoDurUs, audioDurUs;
        public boolean hasAudio;

        void fail(String s) { failures.append("✗ ").append(s).append('\n'); }
    }

    /** @param meta HDR metadata that was configured (null for SDR) - SEI content must match it. */
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
            if (v < 0) { r.fail("لا يوجد مسار فيديو في الناتج"); return finish(r); }
            MediaFormat vf = ex.getTrackFormat(v);
            String mime = vf.getString(MediaFormat.KEY_MIME);
            r.text.append("الفيديو: ").append(mime).append(' ').append(vf.getInteger(MediaFormat.KEY_WIDTH)).append('×').append(vf.getInteger(MediaFormat.KEY_HEIGHT)).append('\n');

            // ---- 1. container colour tags (hard)
            int std = key(vf, MediaFormat.KEY_COLOR_STANDARD), tr = key(vf, MediaFormat.KEY_COLOR_TRANSFER), rg = key(vf, MediaFormat.KEY_COLOR_RANGE);
            boolean tagsOk = hdr ? (std == MediaFormat.COLOR_STANDARD_BT2020 && tr == MediaFormat.COLOR_TRANSFER_ST2084 && rg == MediaFormat.COLOR_RANGE_LIMITED)
                    : (std == MediaFormat.COLOR_STANDARD_BT709 && tr == MediaFormat.COLOR_TRANSFER_SDR_VIDEO && rg == MediaFormat.COLOR_RANGE_LIMITED);
            r.text.append("وسوم الحاوية: ").append(std).append('/').append(tr).append('/').append(rg).append(tagsOk ? " ✓" : " ✗").append('\n');
            if (!tagsOk) r.fail("وسوم الألوان في الحاوية لا تطابق " + (hdr ? "HDR10 (BT.2020/PQ/Limited)" : "SDR (BT.709/Limited)"));

            // ---- 2. bitstream (hard for HEVC)
            ex.selectTrack(v);
            if (mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                HevcBitstream.Info bi = StreamProbe.inspectBitstream(ex, vf, 3);
                r.text.append("البتستريم: ").append(bi.describe()).append('\n');
                if (!bi.spsFound) r.fail("تعذر قراءة SPS من البتستريم");
                else if (hdr) {
                    if (bi.bitDepthLuma != 10 || bi.bitDepthChroma != 10) r.fail("البتستريم ليس 10-bit (" + bi.bitDepthLuma + "-bit)");
                    if (!bi.colourDescription || bi.colourPrimaries != 9 || bi.transfer != 16 || bi.matrix != 9 || bi.fullRange)
                        r.fail("وصف الألوان في VUI ليس BT.2020/PQ/Limited");
                    if (!bi.mdcvFound) r.fail("رسالة SEI لشاشة الـmastering غير موجودة في البتستريم");
                    else if (meta != null && Math.abs(bi.mdcvMaxLum / 10000.0 - meta.masterPeakNits) > 1)
                        r.fail("قمة شاشة الـmastering في SEI (" + bi.mdcvMaxLum / 10000 + ") لا تطابق المضبوط (" + (int) meta.masterPeakNits + ")");
                    if (meta != null && meta.cllMeasured) {
                        if (!bi.cllFound) r.fail("قيم MaxCLL/MaxFALL المقاسة لم تُكتب في SEI");
                        else if (bi.maxCll != meta.maxCll || bi.maxFall != meta.maxFall)
                            r.fail("MaxCLL/MaxFALL في SEI (" + bi.maxCll + "/" + bi.maxFall + ") لا تطابق المقاس (" + meta.maxCll + "/" + meta.maxFall + ")");
                    } else if (bi.cllFound && (bi.maxCll != 0 || bi.maxFall != 0)) {
                        r.fail("SEI يحمل MaxCLL/MaxFALL غير مقاسة (" + bi.maxCll + "/" + bi.maxFall + ")");
                    }
                } else {
                    if (bi.bitDepthLuma != 8) r.warnings.append("ملف SDR بعمق ").append(bi.bitDepthLuma).append("-bit\n");
                    if (bi.colourDescription && (bi.colourPrimaries != 1 || bi.transfer != 1 || bi.matrix != 1))
                        r.fail("وصف الألوان في VUI ليس BT.709");
                }
            } else {
                r.text.append("البتستريم: ").append(mime).append(" - لا يوجد محلل مستقل لهذا الترميز (الاعتماد على وسوم الحاوية والمفكك)\n");
            }
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // ---- 3. decoded samples (HDR). On arbitrary content only a WEAK check is possible: it rules
            // out an 8-bit Y'CbCr stage (decoder/encoder side), not an 8-bit RGB stage before the
            // encoder - that is what the self-test ramp proves, with the same export settings.
            if (hdr) {
                StreamProbe.PixelDepth pd = StreamProbe.probePixels(ex, vf, 3, null, 0);
                r.text.append(pd.describe()).append('\n');
                if (pd.available) {
                    if (!pd.noEightBitYcbcrStage(0.25)) r.fail("عينات الناتج مضاعفات 4 (مرحلة 8-bit في Y'CbCr)");
                } else {
                    r.warnings.append("المفكك لا يخرج P010: لم تُفحص عينات الناتج\n");
                }
                ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            ex.unselectTrack(v);

            // ---- 4. frames, duration, audio
            ex.selectTrack(v);
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
            int n = 0;
            while (ex.getSampleTime() >= 0) { long t = ex.getSampleTime(); first = Math.min(first, t); last = Math.max(last, t); n++; if (!ex.advance()) break; }
            ex.unselectTrack(v);
            r.videoFrames = n;
            r.videoDurUs = n > 1 ? last - first : 0;
            r.text.append(String.format(Locale.US, "عدد الإطارات: %d (المعالَج %d)\n", n, expectedFrames));
            if (n != expectedFrames) r.fail("عدد الإطارات في الملف (" + n + ") لا يساوي عدد الإطارات المعالجة (" + expectedFrames + ")");
            if (a >= 0) {
                r.hasAudio = true;
                ex.selectTrack(a);
                long af = Long.MAX_VALUE, al = Long.MIN_VALUE;
                while (ex.getSampleTime() >= 0) { long t = ex.getSampleTime(); af = Math.min(af, t); al = Math.max(al, t); if (!ex.advance()) break; }
                ex.unselectTrack(a);
                r.audioDurUs = al - af;
                double drift = Math.abs((al - af) - r.videoDurUs) / 1000.0;
                r.text.append(String.format(Locale.US, "الصوت: بداية %.1f ms · فرق المدة عن الفيديو %.0f ms\n", af / 1000.0, drift));
                if (drift > 250) r.warnings.append("مدة الصوت تختلف عن الفيديو بأكثر من 250ms (قد يكون من المصدر)\n");
            } else {
                r.text.append("الصوت: لا يوجد\n");
                if (expectAudio) r.fail("الصوت مفقود رغم وجوده في المصدر");
            }
            // ---- 5. decodability
            ex.selectTrack(v);
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            String dr = decodeFirst(ex, vf);
            r.text.append("فك ترميز الإطار الأول: ").append(dr).append('\n');
            if (!dr.startsWith("ok")) r.fail("تعذر فك ترميز الملف الناتج");
        } catch (Exception e) {
            r.fail("تعذر فحص الملف: " + e.getMessage());
        } finally {
            ex.release();
        }
        return finish(r);
    }

    private static Report finish(Report r) {
        r.ok = r.failures.length() == 0;
        if (!r.ok) r.text.append("أسباب الرفض:\n").append(r.failures);
        return r;
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
            return "مهلة";
        } catch (Exception e) {
            return "خطأ " + e.getMessage();
        } finally {
            if (dec != null) try { dec.stop(); dec.release(); } catch (RuntimeException ignored) { }
        }
    }
}
