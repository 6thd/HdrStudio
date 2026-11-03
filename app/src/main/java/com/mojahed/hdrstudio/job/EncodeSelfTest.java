package com.mojahed.hdrstudio.job;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import com.mojahed.hdrstudio.core.ColorMath;
import com.mojahed.hdrstudio.core.DeviceCaps;
import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.HevcBitstream;
import com.mojahed.hdrstudio.encode.MuxerSink;
import com.mojahed.hdrstudio.encode.StreamProbe;
import com.mojahed.hdrstudio.encode.VideoEncoder;
import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.gl.Tex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Proves the export path on THIS device with THE EXPORT SETTINGS (encoder, size, fps, bitrate,
 * HDR metadata), instead of trusting tags or screen brightness. Each property gets its own
 * verdict:
 *   1. colour accuracy   - known patches -> GL -> encoder RGB->Y'CbCr -> HEVC -> decode -> fit of
 *                          matrix/range (+ compensation, re-verified, if the encoder differs);
 *   2. tags              - container colr box and decoder-reported colour aspects;
 *   3. bitstream         - SPS bit depth, VUI colour description, SEI mastering/CLL parsed
 *                          independently from the NAL units ({@link HevcBitstream});
 *   4. pixel precision   - a fine grey ramp (64 steps of 1/1023) must survive decode with a
 *                          coverage90 >= 26 codes (10-bit measured 34-43, an 8-bit stage 13-18;
 *                          see PrecisionMetric); needs a decoder that outputs P010.
 * The decoder used for tags/pixels is configured from the bitstream only (no container keys).
 * HDR10 is VERIFIED only when all four hold; PARTIAL when only (4) could not be measured.
 */
public final class EncodeSelfTest {
    public static final int VERSION = 5;
    public enum Verdict { PASS, CORRECTED, FAIL }
    public enum Hdr10 { VERIFIED, PARTIAL, FAIL, NOT_APPLICABLE }

    public static final class Result {
        // configuration the test ran with (must match the export)
        public boolean hdr;
        public String encoder = "";
        public int width, height;
        public float fps;
        public long bitrate;
        // verdicts
        public Verdict colour = Verdict.FAIL;
        public boolean containerTagsOk, decoderTagsOk, decoderTagsReported, bitstreamOk, pixelMeasured, pixelOk;
        public Hdr10 hdr10 = Hdr10.NOT_APPLICABLE;
        public float[] comp = ColorMath.IDENTITY3.clone();
        public float[] compOffset = new float[3];
        public double rmsCodes = -1;
        public int rampDistinct = -1;            // ramp coverage90 (see PrecisionMetric)
        public boolean seiInjected;              // SEI written by the app because the encoder did not
        public String detail = "";

        public boolean usable() {
            if (colour == Verdict.FAIL || !containerTagsOk || !decoderTagsOk) return false;
            if (!hdr) return decoderTagsReported;            // SDR (H.264 too): no independent parser -> need decoder evidence
            return bitstreamOk && (pixelOk || !pixelMeasured);
        }

        public boolean matches(boolean hdr, String enc, int w, int h, double fps) {
            return this.hdr == hdr && encoder.equals(enc) && width == w && height == h && Math.abs(this.fps - fps) <= 0.01 * fps;
        }

        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(hdr ? "فحص مسار HDR10" : "فحص مسار SDR").append(String.format(Locale.US, " (%s · %d×%d · %.3f fps · %.1f Mbps)\n",
                    encoder, width, height, fps, bitrate / 1e6));
            b.append("• دقة الألوان: ").append(colour == Verdict.PASS ? "ناجح" : colour == Verdict.CORRECTED ? "ناجح بعد تصحيح مصفوفة المرمّز" : "فشل")
                    .append(rmsCodes >= 0 ? String.format(Locale.US, " (RMS %.2f code)", rmsCodes) : "").append('\n');
            b.append("• وسوم الحاوية: ").append(ok(containerTagsOk)).append(" · وسوم المفكك (من البتستريم): ")
                    .append(decoderTagsReported ? ok(decoderTagsOk) : "لم يبلغ عنها المفكك").append('\n');
            if (hdr) {
                b.append("• البتستريم (10-bit + VUI + SEI): ").append(ok(bitstreamOk)).append('\n');
                b.append("• دقة 10-bit في البكسلات: ").append(!pixelMeasured ? "غير قابل للقياس بهذا المفكك" : ok(pixelOk) + " (تغطية 90% بـ " + rampDistinct + " رمزاً، المطلوب ≥ " + com.mojahed.hdrstudio.encode.PrecisionMetric.MIN_COVERAGE90_FOR_10BIT + ")").append('\n');
                b.append("• بيانات SEI الثابتة: ").append(seiInjected ? "يكتبها التطبيق (المرمّز لا يكتبها)" : "يكتبها المرمّز").append('\n');
                b.append("الحكم: ").append(hdr10 == Hdr10.VERIFIED ? "HDR10 مثبت بالكامل" : hdr10 == Hdr10.PARTIAL
                        ? "HDR10 مثبت في البتستريم والألوان، دون إثبات 10-bit على مستوى البكسل" : "HDR10 غير مثبت – التصدير HDR ممنوع").append('\n');
            }
            return b.append(detail).toString().trim();
        }

        private static String ok(boolean b) { return b ? "✓" : "✗"; }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("hdr", hdr).put("encoder", encoder).put("w", width).put("h", height).put("fps", fps).put("bitrate", bitrate)
                    .put("colour", colour.name()).put("ct", containerTagsOk).put("dt", decoderTagsOk).put("dr", decoderTagsReported).put("bs", bitstreamOk)
                    .put("pm", pixelMeasured).put("po", pixelOk).put("hdr10", hdr10.name()).put("rms", rmsCodes).put("ramp", rampDistinct).put("sei", seiInjected).put("detail", detail);
            JSONArray c = new JSONArray(); for (float v : comp) c.put(v); o.put("comp", c);
            JSONArray f = new JSONArray(); for (float v : compOffset) f.put(v); o.put("off", f);
            return o;
        }

        static Result fromJson(JSONObject o) throws Exception {
            Result r = new Result();
            r.hdr = o.getBoolean("hdr"); r.encoder = o.getString("encoder"); r.width = o.getInt("w"); r.height = o.getInt("h");
            r.fps = (float) o.getDouble("fps"); r.bitrate = o.getLong("bitrate"); r.colour = Verdict.valueOf(o.getString("colour"));
            r.containerTagsOk = o.getBoolean("ct"); r.decoderTagsOk = o.getBoolean("dt"); r.decoderTagsReported = o.optBoolean("dr"); r.bitstreamOk = o.getBoolean("bs");
            r.pixelMeasured = o.getBoolean("pm"); r.pixelOk = o.getBoolean("po"); r.hdr10 = Hdr10.valueOf(o.getString("hdr10"));
            r.rmsCodes = o.getDouble("rms"); r.rampDistinct = o.getInt("ramp"); r.seiInjected = o.optBoolean("sei"); r.detail = o.getString("detail");
            JSONArray c = o.getJSONArray("comp"); for (int i = 0; i < 9; i++) r.comp[i] = (float) c.getDouble(i);
            JSONArray f = o.getJSONArray("off"); for (int i = 0; i < 3; i++) r.compOffset[i] = (float) f.getDouble(i);
            return r;
        }
    }

    private static final double[][] PATCHES_RGB = new double[16][];
    static {
        for (int i = 0; i < 9; i++) { double g = 0.05 + 0.9 * i / 8.0; PATCHES_RGB[i] = new double[]{g, g, g}; }
        PATCHES_RGB[9] = new double[]{0.75, 0.10, 0.10};
        PATCHES_RGB[10] = new double[]{0.10, 0.75, 0.10};
        PATCHES_RGB[11] = new double[]{0.10, 0.10, 0.75};
        PATCHES_RGB[12] = new double[]{0.10, 0.70, 0.70};
        PATCHES_RGB[13] = new double[]{0.70, 0.10, 0.70};
        PATCHES_RGB[14] = new double[]{0.70, 0.70, 0.10};
        PATCHES_RGB[15] = new double[]{0.62, 0.48, 0.40};
    }

    static String prefKey(boolean hdr, String enc, int w, int h, double fps) {
        return String.format(Locale.US, "st_v%d_%s_%s_%dx%d_%.3f_%d", VERSION, hdr ? "hdr" : "sdr", enc, w, h, fps, Build.FINGERPRINT.hashCode());
    }

    public static Result load(Context ctx, boolean hdr, String enc, int w, int h, double fps) {
        String s = ctx.getSharedPreferences("selftest", Context.MODE_PRIVATE).getString(prefKey(hdr, enc, w, h, fps), null);
        if (s == null) return null;
        try { return Result.fromJson(new JSONObject(s)); } catch (Exception e) { return null; }
    }

    private static void store(Context ctx, Result r) {
        try {
            ctx.getSharedPreferences("selftest", Context.MODE_PRIVATE).edit()
                    .putString(prefKey(r.hdr, r.encoder, r.width, r.height, r.fps), r.toJson().toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Runs on a worker thread with the exact export configuration. */
    public static Result run(Context ctx, DeviceCaps.Enc enc, boolean hdr, int w, int h, float fps, long bitrate) {
        Result r = new Result();
        r.hdr = hdr; r.width = w; r.height = h; r.fps = fps; r.bitrate = bitrate;
        r.encoder = enc == null ? "" : enc.name;
        if (enc == null) { r.detail = "لا يوجد مرمّز مناسب"; r.hdr10 = hdr ? Hdr10.FAIL : Hdr10.NOT_APPLICABLE; return r; }
        StringBuilder log = new StringBuilder();
        try {
            Measure m1 = encodeAndMeasure(ctx, enc, r, ColorMath.IDENTITY3, new float[3], log);
            r.containerTagsOk = m1.containerOk;
            // decoder configured from the bitstream only: a mismatch is a failure; "not reported" is
            // not evidence either way (the HDR decision then rests on the independent SPS/VUI parse)
            r.decoderTagsOk = m1.decoderOk || !m1.decoderReported;
            r.decoderTagsReported = m1.decoderReported;
            int targetMatrix = hdr ? 1 : 0;
            Fit best = fit(m1);
            log.append("أفضل مطابقة للمصفوفة: ").append(best.describe()).append(String.format(Locale.US, " (RMS %.2f code عند %d-bit)", best.rms, m1.bits)).append('\n');
            double tol = m1.bits == 10 ? 3.0 : 1.6;
            Measure fin = m1;
            if (best.flipped) {
                log.append("الصورة في الملف مقلوبة رأسياً – المسار غير صالح\n");
                r.rmsCodes = best.rms;
            } else if (best.rms <= tol && best.matrix == targetMatrix && !best.full) {
                r.colour = Verdict.PASS; r.rmsCodes = best.rms;
            } else if (best.rms <= tol) {
                double[][] target = ColorMath.rgbToYuvAffine(targetMatrix, false, m1.bits);
                double[][] actual = ColorMath.rgbToYuvAffine(best.matrix, best.full, m1.bits);
                float[][] c = ColorMath.compensation(target, actual);
                Measure m2 = encodeAndMeasure(ctx, enc, r, c[0], c[1], log);
                Fit f2 = fit(m2);
                log.append("بعد التصحيح: ").append(f2.describe()).append(String.format(Locale.US, " (RMS %.2f code)", f2.rms)).append('\n');
                r.rmsCodes = f2.rms;
                if (f2.matrix == targetMatrix && !f2.full && f2.rms <= tol && !f2.flipped) {
                    r.colour = Verdict.CORRECTED; r.comp = c[0]; r.compOffset = c[1]; fin = m2;
                }
            } else r.rmsCodes = best.rms;
            if (hdr) {
                HevcBitstream.Info bi = fin.bitstream;
                boolean seiOk = bi != null && bi.mdcvFound && Math.abs(bi.mdcvMaxLum / 10000.0 - fin.meta.masterPeakNits) <= 1
                        && bi.cllFound && bi.maxCll == fin.meta.maxCll && bi.maxFall == fin.meta.maxFall;
                r.bitstreamOk = bi != null && bi.isHdr10Signal() && seiOk;
                if (bi != null && !seiOk) log.append("بيانات SEI غير موجودة أو لا تطابق ما ضُبط في المرمّز\n");
                r.pixelMeasured = fin.pixels.available;
                r.rampDistinct = fin.pixels.minCoverage90;
                r.pixelOk = fin.pixels.rampProves10bit();
                r.seiInjected = fin.seiInjected;
                if (!r.usable()) r.hdr10 = Hdr10.FAIL;
                else r.hdr10 = r.pixelMeasured ? Hdr10.VERIFIED : Hdr10.PARTIAL;
            }
        } catch (Throwable t) {
            log.append("خطأ: ").append(t.getMessage()).append('\n');
            if (hdr) r.hdr10 = Hdr10.FAIL;
        }
        r.detail = log.toString().trim();
        store(ctx, r);
        return r;
    }

    // ------------------------------------------------------------------ internals
    private static final class Measure {
        int bits = 8;
        double[][][] grid = new double[4][4][3];   // [row top-down][col][Y,Cb,Cr] normalised codes
        boolean containerOk, decoderOk, decoderReported;
        HevcBitstream.Info bitstream;
        StreamProbe.PixelDepth pixels = new StreamProbe.PixelDepth();
        HdrMetadata meta;
        boolean seiInjected;
    }

    private static final class Fit {
        int matrix; boolean full; double rms; boolean flipped;
        String describe() { return (matrix == 1 ? "BT.2020" : matrix == 0 ? "BT.709" : "BT.601") + (full ? " Full" : " Limited") + (flipped ? " (مقلوب رأسياً!)" : ""); }
    }

    private static Fit fit(Measure m) {
        Fit best = null;
        double scale = (1 << m.bits) - 1;
        for (int flip = 0; flip < 2; flip++)
            for (int matrix = 0; matrix < 3; matrix++)
                for (int fr = 0; fr < 2; fr++) {
                    double[][] aff = ColorMath.rgbToYuvAffine(matrix, fr == 1, m.bits);
                    double se = 0;
                    for (int py = 0; py < 4; py++)
                        for (int px = 0; px < 4; px++) {
                            int idx = (flip == 0 ? 3 - py : py) * 4 + px;
                            double[] e = ColorMath.apply(aff, PATCHES_RGB[idx]);
                            for (int c = 0; c < 3; c++) { double d = (e[c] - m.grid[py][px][c]) * scale; se += d * d; }
                        }
                    Fit f = new Fit();
                    f.matrix = matrix; f.full = fr == 1; f.rms = Math.sqrt(se / 48); f.flipped = flip == 1;
                    if (best == null || f.rms < best.rms) best = f;
                }
        return best;
    }

    private static Measure encodeAndMeasure(Context ctx, DeviceCaps.Enc enc, Result cfg, float[] comp, float[] off, StringBuilder log) throws Exception {
        boolean hdr = cfg.hdr;
        int w = cfg.width, h = cfg.height;
        Measure m = new Measure();
        File file = new File(ctx.getCacheDir(), "selftest_" + (hdr ? "hdr" : "sdr") + ".mp4");
        file.delete();
        Egl egl = new Egl(hdr);
        MuxerSink mux = new MuxerSink(file.getAbsolutePath(), 0);
        VideoEncoder.Config c = new VideoEncoder.Config();
        c.width = w; c.height = h; c.fps = cfg.fps; c.bitrate = cfg.bitrate; c.hdr = hdr; c.mime = hdr ? DeviceCaps.HEVC : enc.mime;
        if (hdr) {                                    // same metadata shape as a measured export
            m.meta = HdrMetadata.p3(1000);
            m.meta.setMeasured(987, 321, "self-test");
            c.meta = m.meta;
        }
        VideoEncoder ve = null;
        GlKit k = null;
        Egl.WindowSurface ws = null;
        boolean pqTagged = false;
        try {
            ve = new VideoEncoder(enc, c, mux);
            ws = egl.createWindowSurface(ve.inputSurface, hdr);
            pqTagged = ws.pqTagged;
            egl.makeCurrent(ws.surface);
            k = new GlKit(new ShaderLoader(ctx.getAssets()));
            Tex fbo = k.tex(w, h, true);
            float[] patch = new float[48];
            for (int i = 0; i < 16; i++) for (int ch = 0; ch < 3; ch++) patch[i * 3 + ch] = (float) PATCHES_RGB[i][ch];
            int frames = Math.max(15, Math.round(cfg.fps / 2));
            long frameNs = Math.round(1e9 / cfg.fps);
            for (int f = 0; f < frames; f++) {
                Map<String, Object> u = new HashMap<>();
                u.put("uPatch", patch); u.put("uSize", new float[]{w, h}); u.put("uRamp", new float[]{400f / 1023f, 64f / 1023f});
                k.run("pattern.frag", fbo, u);
                u = new HashMap<>();
                u.put("uIn", fbo); u.put("uComp", comp); u.put("uCompOffset", off); u.put("uLsb", hdr ? 1f / 1023 : 1f / 255);
                u.put("uDither", false); u.put("uFlipY", false);
                k.runToSurface("present.frag", u, w, h);
                egl.swap(ws.surface, f * frameNs);
            }
            ve.signalEndOfInput();
            if (!ve.awaitEos(20000)) throw new IllegalStateException("المرمّز لم ينهِ الملف التجريبي");
            mux.stop();
            m.seiInjected = ve.seiInjectedFrames > 0;
        } finally {
            if (k != null) k.release();
            if (ve != null) ve.release();
            mux.release();
            if (ws != null) egl.destroySurface(ws.surface);
            egl.release();
        }
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(file.getAbsolutePath());
        MediaFormat tf = ex.getTrackFormat(0);
        int wantStd = hdr ? MediaFormat.COLOR_STANDARD_BT2020 : MediaFormat.COLOR_STANDARD_BT709;
        int wantTr = hdr ? MediaFormat.COLOR_TRANSFER_ST2084 : MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
        m.containerOk = intKey(tf, MediaFormat.KEY_COLOR_STANDARD) == wantStd && intKey(tf, MediaFormat.KEY_COLOR_TRANSFER) == wantTr
                && intKey(tf, MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_LIMITED;
        log.append("وسوم الحاوية: ").append(tags(tf)).append(m.containerOk ? " ✓" : " ✗").append('\n');
        ex.selectTrack(0);
        String mime = tf.getString(MediaFormat.KEY_MIME);
        if (hdr && MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) {
            m.bitstream = StreamProbe.inspectBitstream(ex, tf, 3);
            log.append("البتستريم: ").append(m.bitstream.describe()).append('\n');
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int y = h - Math.max(1, h / 64);
            m.pixels = StreamProbe.probePixels(ex, tf, 3, new int[]{w / 16, y, w - w / 16, y + 1}, y);
            log.append(m.pixels.describe()).append('\n');
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        // decode the last frame for the colour fit (P010 when offered, else flexible 8-bit)
        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        boolean p010 = false;
        for (int cf : dec.getCodecInfo().getCapabilitiesForType(mime).colorFormats) if (cf == 54) p010 = true;
        MediaFormat df = StreamProbe.bitstreamOnly(tf);   // decoder sees no container colour keys
        df.setInteger(MediaFormat.KEY_COLOR_FORMAT, hdr && p010 ? 54 : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        dec.configure(df, null, null, 0);
        dec.start();
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean inDone = false, got = false;
        MediaFormat outFmt = null;
        long deadline = System.currentTimeMillis() + 20000;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(5000);
                    if (i >= 0) {
                        int n = ex.readSampleData(dec.getInputBuffer(i), 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int o = dec.dequeueOutputBuffer(bi, 5000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFmt = dec.getOutputFormat();
                if (o >= 0) {
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (bi.size > 0) {
                        Image img = dec.getOutputImage(o);
                        if (img != null) { sample(img, m, w, h); img.close(); got = true; }
                    }
                    dec.releaseOutputBuffer(o, false);
                    if (eos) break;
                }
            }
            if (outFmt == null) outFmt = dec.getOutputFormat();
        } finally {
            dec.stop(); dec.release(); ex.release();
            file.delete();
        }
        if (!got) throw new IllegalStateException("تعذر قراءة الإطار المفكوك");
        m.decoderReported = intKey(outFmt, MediaFormat.KEY_COLOR_STANDARD) >= 0 || intKey(outFmt, MediaFormat.KEY_COLOR_TRANSFER) >= 0;
        m.decoderOk = intKey(outFmt, MediaFormat.KEY_COLOR_STANDARD) == wantStd && intKey(outFmt, MediaFormat.KEY_COLOR_TRANSFER) == wantTr
                && intKey(outFmt, MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_LIMITED;
        log.append("وسوم المفكك (من البتستريم فقط): ").append(tags(outFmt)).append(m.decoderOk ? " ✓" : " ✗").append('\n');
        if (hdr) log.append("سطح EGL موسوم BT.2020 PQ: ").append(pqTagged ? "نعم" : "لا (الألوان تُقاس مباشرة أعلاه)").append('\n');
        return m;
    }

    private static int intKey(MediaFormat f, String k) { return f != null && f.containsKey(k) ? f.getInteger(k) : -1; }

    private static String tags(MediaFormat f) {
        return "standard=" + intKey(f, MediaFormat.KEY_COLOR_STANDARD) + " transfer=" + intKey(f, MediaFormat.KEY_COLOR_TRANSFER) + " range=" + intKey(f, MediaFormat.KEY_COLOR_RANGE);
    }

    /** Average Y, Cb, Cr over the centre half of each of the 16 patches. Rows are top-down in Image. */
    private static void sample(Image img, Measure m, int w, int h) {
        boolean sixteen = img.getFormat() == ImageFormat.YCBCR_P010;
        m.bits = sixteen ? 10 : 8;
        Image.Plane[] pl = img.getPlanes();
        for (int py = 0; py < 4; py++) {
            for (int px = 0; px < 4; px++) {
                int x0 = px * w / 4 + w / 16, x1 = (px + 1) * w / 4 - w / 16;
                int y0 = py * h / 4 + h / 16, y1 = (py + 1) * h / 4 - h / 16;
                double[] acc = new double[3];
                int[] cnt = new int[3];
                for (int y = y0; y < y1; y += 2) {
                    for (int x = x0; x < x1; x += 2) {
                        acc[0] += read(pl[0], x, y, sixteen); cnt[0]++;
                        acc[1] += read(pl[1], x / 2, y / 2, sixteen); cnt[1]++;
                        acc[2] += read(pl[2], x / 2, y / 2, sixteen); cnt[2]++;
                    }
                }
                double max = sixteen ? 1023.0 : 255.0;
                for (int c = 0; c < 3; c++) m.grid[py][px][c] = acc[c] / cnt[c] / max;
            }
        }
    }

    private static int read(Image.Plane p, int x, int y, boolean sixteen) {
        ByteBuffer b = p.getBuffer();
        int off = y * p.getRowStride() + x * p.getPixelStride();
        if (!sixteen) return b.get(off) & 0xFF;
        int v = (b.get(off) & 0xFF) | ((b.get(off + 1) & 0xFF) << 8);
        return v >> 6;
    }
}
