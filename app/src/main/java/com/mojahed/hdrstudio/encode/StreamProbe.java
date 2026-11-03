package com.mojahed.hdrstudio.encode;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads what is really inside an encoded video track:
 *  - bitstream: SPS/VUI/SEI parsed by {@link HevcBitstream} from csd-0 and the first access units;
 *  - pixels: decodes frames to raw P010 (when the decoder offers it). On the self-test ramp the
 *    coverage90 metric decides 10-bit precision; on arbitrary content only a diagnostic is
 *    possible ({@link PixelDiagnostics}) - a black or flat picture is valid with few codes.
 *    A decoder error or timeout is reported as such, never as "no P010 output".
 */
public final class StreamProbe {
    private StreamProbe() {}

    /**
     * A decoder format holding only what the bitstream itself defines (mime, size, codec-specific
     * data), so colour aspects the decoder reports come from the bitstream VUI and not from the
     * container keys we wrote ourselves.
     */
    public static MediaFormat bitstreamOnly(MediaFormat tf) {
        MediaFormat f = MediaFormat.createVideoFormat(tf.getString(MediaFormat.KEY_MIME), tf.getInteger(MediaFormat.KEY_WIDTH), tf.getInteger(MediaFormat.KEY_HEIGHT));
        for (String k : new String[]{"csd-0", "csd-1", "csd-2"}) if (tf.containsKey(k)) f.setByteBuffer(k, tf.getByteBuffer(k));
        if (tf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, tf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        return f;
    }

    /** Collects csd buffers + first n samples of the selected track (extractor must have it selected). */
    public static HevcBitstream.Info inspectBitstream(MediaExtractor ex, MediaFormat f, int samples) {
        List<byte[]> bufs = new ArrayList<>();
        for (String k : new String[]{"csd-0", "csd-1", "csd-2"}) {
            if (f.containsKey(k)) {
                ByteBuffer b = f.getByteBuffer(k).duplicate();
                byte[] a = new byte[b.remaining()]; b.get(a); bufs.add(a);
            }
        }
        ByteBuffer s = ByteBuffer.allocate(16 << 20);
        for (int i = 0; i < samples; i++) {
            s.clear();
            int n = ex.readSampleData(s, 0);
            if (n < 0) break;
            byte[] a = new byte[n]; s.position(0); s.get(a, 0, n); bufs.add(a);
            if (!ex.advance()) break;
        }
        return HevcBitstream.inspect(bufs);
    }

    public static final class PixelDepth {
        /** MEASURED, UNSUPPORTED (no P010 output), ERROR / TIMEOUT (the measurement did not happen). */
        public PathTestVerdict.PixelProbe status = PathTestVerdict.PixelProbe.NOT_RUN;
        public int frames;
        public final long[] hist = new long[1024];   // decoded luma codes (whole-frame mode)
        public int distinctInRegion = -1;  // distinct luma codes in an optional region (self-test ramp), informative only
        public int minCoverage90 = -1;     // ramp: worst frame's coverage90 (decisive, see PrecisionMetric)
        public String note = "";

        public boolean measured() { return status == PathTestVerdict.PixelProbe.MEASURED; }

        public String describe() {
            switch (status) {
                case UNSUPPORTED: return "عمق البكسل: غير قابل للقياس (المفكك لا يخرج P010) " + note;
                case ERROR: return "عمق البكسل: فشل القياس بخطأ في المفكك " + note;
                case TIMEOUT: return "عمق البكسل: فشل القياس بانتهاء المهلة (" + frames + " إطار) " + note;
                case NOT_RUN: return "عمق البكسل: لم يُقس";
                default: break;
            }
            if (minCoverage90 >= 0)
                return String.format(Locale.US, "تدرج 10-bit بعد فك الترميز: %d رمزاً تغطي 90%% من العينات (المطلوب ≥ %d؛ مرحلة 8-bit تعطي نحو 13-18)",
                        minCoverage90, PrecisionMetric.MIN_COVERAGE90_FOR_10BIT);
            return diagnostics().describe();
        }
        /** Whole frames of arbitrary content: diagnostic only, never a pass condition. */
        public PixelDiagnostics.Result diagnostics() { return PixelDiagnostics.classify(hist); }
        /** Strong check (self-test ramp only): rules out an 8-bit stage anywhere before the encoder too. */
        public boolean rampProves10bit() { return measured() && minCoverage90 >= 0 && PrecisionMetric.proves10bit(minCoverage90); }
    }

    /**
     * Decodes up to maxFrames frames of the selected track and measures luma precision.
     * region: {x0, y0, x1, y1} in top-down image rows to count distinct codes in (or null).
     */
    public static PixelDepth probePixels(MediaExtractor ex, MediaFormat tf, int maxFrames, int[] region, int regionRow) {
        PixelDepth r = new PixelDepth();
        String mime = tf.getString(MediaFormat.KEY_MIME);
        MediaCodec dec = null;
        try {
            dec = MediaCodec.createDecoderByType(mime);
            boolean p010 = false;
            for (int cf : dec.getCodecInfo().getCapabilitiesForType(mime).colorFormats) if (cf == 54) p010 = true;
            if (!p010) { r.status = PathTestVerdict.PixelProbe.UNSUPPORTED; r.note = "(" + dec.getName() + ")"; return r; }
            MediaFormat f = bitstreamOnly(tf);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, 54 /* COLOR_FormatYUVP010 */);
            dec.configure(f, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean in = false, outEos = false;
            java.util.HashSet<Integer> distinct = new java.util.HashSet<>();
            int minCov = Integer.MAX_VALUE;
            long end = System.currentTimeMillis() + 15000;
            while (r.frames < maxFrames && System.currentTimeMillis() < end) {
                if (!in) {
                    int i = dec.dequeueInputBuffer(5000);
                    if (i >= 0) {
                        int n = ex.readSampleData(dec.getInputBuffer(i), 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); in = true; }
                        else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int o = dec.dequeueOutputBuffer(bi, 5000);
                if (o >= 0) {
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (bi.size > 0) {
                        Image img = dec.getOutputImage(o);
                        if (img != null) {
                            if (img.getFormat() != ImageFormat.YCBCR_P010) {
                                img.close(); dec.releaseOutputBuffer(o, false);
                                r.status = PathTestVerdict.PixelProbe.UNSUPPORTED; r.note = "(صيغة الإخراج " + img.getFormat() + ")"; return r;
                            }
                            Image.Plane y = img.getPlanes()[0];
                            ByteBuffer b = y.getBuffer();
                            int w = img.getWidth(), h = img.getHeight(), rs = y.getRowStride(), ps = y.getPixelStride();
                            if (region == null) {
                                for (int yy = 0; yy < h; yy += 3)
                                    for (int xx = 0; xx < w; xx += 3) {
                                        int off = yy * rs + xx * ps;
                                        r.hist[((b.get(off) & 0xFF) | ((b.get(off + 1) & 0xFF) << 8)) >> 6]++;
                                    }
                            } else {                     // only the given row segment (self-test ramp)
                                int[] codes = new int[region[2] - region[0]];
                                for (int xx = region[0]; xx < region[2]; xx++) {
                                    int off = regionRow * rs + xx * ps;
                                    int v = ((b.get(off) & 0xFF) | ((b.get(off + 1) & 0xFF) << 8)) >> 6;
                                    codes[xx - region[0]] = v;
                                    distinct.add(v); r.hist[v]++;
                                }
                                minCov = Math.min(minCov, PrecisionMetric.coverage90(codes));
                            }
                            img.close();
                            r.frames++;
                        }
                    }
                    dec.releaseOutputBuffer(o, false);
                    if (eos) { outEos = true; break; }
                }
            }
            if (r.frames >= maxFrames || (outEos && r.frames > 0)) r.status = PathTestVerdict.PixelProbe.MEASURED;
            else if (outEos) { r.status = PathTestVerdict.PixelProbe.ERROR; r.note = "(نهاية المسار دون أي إطار مفكوك)"; }
            else r.status = PathTestVerdict.PixelProbe.TIMEOUT;
            if (region != null) { r.distinctInRegion = distinct.size(); r.minCoverage90 = minCov == Integer.MAX_VALUE ? -1 : minCov; }
        } catch (Exception e) {
            r.status = PathTestVerdict.PixelProbe.ERROR;
            r.note = "(خطأ: " + e.getMessage() + ")";
        } finally {
            if (dec != null) try { dec.stop(); dec.release(); } catch (RuntimeException ignored) { }
        }
        return r;
    }
}
