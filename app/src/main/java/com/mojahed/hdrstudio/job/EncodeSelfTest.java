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
import com.mojahed.hdrstudio.encode.Check;
import com.mojahed.hdrstudio.encode.EncodeConfig;
import com.mojahed.hdrstudio.encode.Hdr10FileCheck;
import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.HevcBitstream;
import com.mojahed.hdrstudio.encode.PathTestVerdict;
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
 * Proves the export path on THIS device with THE EXPORT CONFIGURATION ({@link EncodeConfig}:
 * encoder, profile, aligned size, exact frame rate, clamped bitrate, bitrate mode, GOP, colour),
 * instead of trusting tags or screen brightness. Each property gets its own PASS / FAIL /
 * INCONCLUSIVE verdict ({@link PathTestVerdict}):
 *   1. colour accuracy   - known patches -> GL -> encoder RGB->Y'CbCr -> HEVC -> decode -> fit of
 *                          matrix/range (+ compensation, re-verified, if the encoder differs);
 *   2. tags              - container colr box and decoder-reported colour aspects;
 *   3. bitstream         - SPS bit depth, VUI colour description parsed from the NAL units;
 *   4. metadata transport- SEI 137/144 on every IRAP carry exactly the configured SAMPLE values
 *                          (the export's final values are verified in the exported file itself);
 *   5. gradation         - a fine grey ramp (64 steps of 1/1023) must survive decode with a
 *                          coverage90 >= 26 codes (see PrecisionMetric); needs P010 decoder output.
 *                          A decoder error or timeout is a FAIL, not "unmeasurable".
 * The decoder used for tags/pixels is configured from the bitstream only (no container keys).
 * A stored result applies only to an identical configuration, test version and system build.
 */
public final class EncodeSelfTest {
    public static final int VERSION = 8;
    public enum Colour { PASS, CORRECTED, FAIL }

    public static final class Result {
        public EncodeConfig config;              // the configuration the test ran with (must equal the export's)
        public String systemId = "";
        public final PathTestVerdict verdict = new PathTestVerdict();
        public Colour colour = Colour.FAIL;
        public float[] comp = ColorMath.IDENTITY3.clone();
        public float[] compOffset = new float[3];
        public double rmsCodes = -1;
        public int rampCoverage90 = -1;
        public String seiSource = "";
        public String detail = "";

        public boolean hdr() { return config != null && config.hdr; }
        public boolean usable() { return verdict.exportAllowed(); }
        public boolean matches(EncodeConfig c) { return config != null && c != null && config.canonical().equals(c.canonical()); }

        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(hdr() ? "فحص مسار HDR10" : "فحص مسار SDR").append(" (").append(config == null ? "?" : config.describe()).append(")\n");
            b.append(verdict.summary()).append('\n');
            if (!seiSource.isEmpty()) b.append("مصدر SEI: ").append(seiSource).append('\n');
            return b.append(detail).toString().trim();
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("cfg", config.canonical()).put("sys", systemId).put("hdr", verdict.hdr).put("colourMode", colour.name())
                    .put("rms", rmsCodes).put("ramp", rampCoverage90).put("sei", seiSource).put("detail", detail)
                    .put("gprobe", verdict.gradationProbe.name());
            JSONArray ch = new JSONArray();
            for (Check c : new Check[]{verdict.colour, verdict.containerTags, verdict.decoderTags, verdict.bitstream, verdict.metadataTransport, verdict.gradation})
                ch.put(new JSONObject().put("v", c.verdict.name()).put("w", c.what).put("d", c.detail));
            o.put("checks", ch);
            JSONArray c = new JSONArray(); for (float v : comp) c.put(v); o.put("comp", c);
            JSONArray f = new JSONArray(); for (float v : compOffset) f.put(v); o.put("off", f);
            return o;
        }

        static Result fromJson(JSONObject o, EncodeConfig expect, String systemId) throws Exception {
            if (!o.getString("cfg").equals(expect.canonical()) || !o.getString("sys").equals(systemId)) return null;
            Result r = new Result();
            r.config = expect; r.systemId = systemId;
            r.verdict.hdr = o.getBoolean("hdr");
            r.colour = Colour.valueOf(o.getString("colourMode"));
            r.rmsCodes = o.getDouble("rms"); r.rampCoverage90 = o.getInt("ramp"); r.seiSource = o.optString("sei"); r.detail = o.getString("detail");
            JSONArray ch = o.getJSONArray("checks");
            Check[] c = new Check[6];
            for (int i = 0; i < 6; i++) {
                JSONObject j = ch.getJSONObject(i);
                c[i] = Check.restore(Check.Verdict.valueOf(j.getString("v")), j.getString("w"), j.getString("d"));
            }
            r.verdict.colour = c[0]; r.verdict.containerTags = c[1]; r.verdict.decoderTags = c[2];
            r.verdict.bitstream = c[3]; r.verdict.metadataTransport = c[4]; r.verdict.gradation = c[5];
            r.verdict.gradationProbe = PathTestVerdict.PixelProbe.valueOf(o.optString("gprobe", "NOT_RUN"));
            JSONArray cm = o.getJSONArray("comp"); for (int i = 0; i < 9; i++) r.comp[i] = (float) cm.getDouble(i);
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

    /** Sample metadata used only to prove that SEI 137/144 reach the file unchanged. */
    static HdrMetadata sampleMetadata() {
        HdrMetadata m = HdrMetadata.p3(1000);
        m.setMeasured(987, 321, "قيم نموذجية لفحص المسار");
        return m;
    }

    public static String systemId() { return Build.FINGERPRINT; }

    public static Result load(Context ctx, EncodeConfig cfg) {
        if (cfg == null) return null;
        String s = ctx.getSharedPreferences("selftest", Context.MODE_PRIVATE).getString(cfg.resultKey(VERSION, systemId()), null);
        if (s == null) return null;
        try { return Result.fromJson(new JSONObject(s), cfg, systemId()); } catch (Exception e) { return null; }
    }

    private static void store(Context ctx, Result r) {
        try {
            ctx.getSharedPreferences("selftest", Context.MODE_PRIVATE).edit()
                    .putString(r.config.resultKey(VERSION, r.systemId), r.toJson().toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Runs on a worker thread with the exact export configuration. */
    public static Result run(Context ctx, DeviceCaps.Enc enc, EncodeConfig cfg) {
        Result r = new Result();
        r.config = cfg; r.systemId = systemId();
        boolean hdr = cfg.hdr;
        PathTestVerdict v = r.verdict;
        v.hdr = hdr;
        if (enc == null || !enc.name.equals(cfg.encoder)) { r.detail = "المرمّز غير متوفر"; return r; }
        StringBuilder log = new StringBuilder();
        try {
            Measure m1 = encodeAndMeasure(ctx, enc, cfg, ColorMath.IDENTITY3, new float[3], log);
            int targetMatrix = hdr ? 1 : 0;
            Fit best = fit(m1);
            log.append("أفضل مطابقة للمصفوفة: ").append(best.describe()).append(String.format(Locale.US, " (RMS %.2f code عند %d-bit)", best.rms, m1.bits)).append('\n');
            double tol = m1.bits == 10 ? 3.0 : 1.6;
            Measure fin = m1;
            if (best.flipped) {
                log.append("الصورة في الملف مقلوبة رأسياً – المسار غير صالح\n");
                r.rmsCodes = best.rms;
            } else if (best.rms <= tol && best.matrix == targetMatrix && !best.full) {
                r.colour = Colour.PASS; r.rmsCodes = best.rms;
            } else if (best.rms <= tol) {
                double[][] target = ColorMath.rgbToYuvAffine(targetMatrix, false, m1.bits);
                double[][] actual = ColorMath.rgbToYuvAffine(best.matrix, best.full, m1.bits);
                float[][] c = ColorMath.compensation(target, actual);
                Measure m2 = encodeAndMeasure(ctx, enc, cfg, c[0], c[1], log);
                Fit f2 = fit(m2);
                log.append("بعد التصحيح: ").append(f2.describe()).append(String.format(Locale.US, " (RMS %.2f code)", f2.rms)).append('\n');
                r.rmsCodes = f2.rms;
                if (f2.matrix == targetMatrix && !f2.full && f2.rms <= tol && !f2.flipped) {
                    r.colour = Colour.CORRECTED; r.comp = c[0]; r.compOffset = c[1]; fin = m2;
                }
            } else r.rmsCodes = best.rms;
            String rms = r.rmsCodes >= 0 ? String.format(Locale.US, "RMS %.2f code", r.rmsCodes) : "";
            v.colour = r.colour == Colour.PASS ? Check.pass("دقة الألوان", rms)
                    : r.colour == Colour.CORRECTED ? Check.pass("دقة الألوان", "بعد تصحيح مصفوفة المرمّز، أعيد التحقق · " + rms)
                    : Check.fail("دقة الألوان", best.describe() + " · " + rms);
            v.containerTags = Check.of(fin.containerOk, "وسوم الحاوية", fin.containerTags);
            // decoder configured from the bitstream only: a mismatch is a failure; "not reported" is
            // not evidence either way (the HDR decision then rests on the independent SPS/VUI parse)
            v.decoderTags = !fin.decoderReported ? Check.inconclusive("وسوم المفكك (من البتستريم فقط)", "لم يبلغ عنها المفكك")
                    : Check.of(fin.decoderOk, "وسوم المفكك (من البتستريم فقط)", fin.decoderTags);
            if (hdr) {
                v.bitstream = Hdr10FileCheck.signalling(fin.bitstream);
                Check t = Hdr10FileCheck.finalMetadata(fin.bitstream, fin.meta, fin.scan);
                v.metadataTransport = Check.restore(t.verdict, "نقل بيانات HDR الثابتة (قيم نموذجية 1000 nit · 987/321)", t.detail);
                v.setGradation(fin.pixels.status, fin.pixels.minCoverage90, fin.pixels.note);
                r.rampCoverage90 = fin.pixels.minCoverage90;
                r.seiSource = fin.seiSource;
            }
        } catch (com.mojahed.hdrstudio.gl.GlException e) {
            v.markNotRunBecauseEgl(e.getMessage());
            log.append(e.getMessage()).append('\n');
        } catch (Throwable t) {
            if (isEglInitFailure(t)) v.markNotRunBecauseEgl(t.getMessage());
            log.append("خطأ: ").append(t.getMessage()).append('\n');
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
        String containerTags = "", decoderTags = "";
        HevcBitstream.Info bitstream;
        Hdr10FileCheck.IrapScan scan;
        StreamProbe.PixelDepth pixels = new StreamProbe.PixelDepth();
        HdrMetadata meta;
        String seiSource = "";
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

    private static Measure encodeAndMeasure(Context ctx, DeviceCaps.Enc enc, EncodeConfig cfg, float[] comp, float[] off, StringBuilder log) throws Exception {
        boolean hdr = cfg.hdr;
        int w = cfg.width, h = cfg.height;
        Measure m = new Measure();
        File file = new File(ctx.getCacheDir(), "selftest_" + (hdr ? "hdr" : "sdr") + ".mp4");
        file.delete();
        Egl egl = new Egl(hdr);
        MuxerSink mux = new MuxerSink(file.getAbsolutePath(), 0);
        if (hdr) m.meta = sampleMetadata();           // same metadata shape as a measured export
        VideoEncoder ve = null;
        GlKit k = null;
        Egl.WindowSurface ws = null;
        boolean pqTagged = false;
        try {
            ve = new VideoEncoder(enc, cfg, m.meta, mux);
            ws = egl.createWindowSurface(ve.inputSurface, hdr);
            pqTagged = ws.pqTagged;
            egl.makeCurrent(ws.surface);
            k = new GlKit(new ShaderLoader(ctx.getAssets()));
            Tex fbo = k.tex(w, h, true);
            float[] patch = new float[48];
            for (int i = 0; i < 16; i++) for (int ch = 0; ch < 3; ch++) patch[i * 3 + ch] = (float) PATCHES_RGB[i][ch];
            int frames = (int) Math.max(15, Math.round(cfg.fps.fps() / 2));
            for (int f = 0; f < frames; f++) {
                Map<String, Object> u = new HashMap<>();
                u.put("uPatch", patch); u.put("uSize", new float[]{w, h}); u.put("uRamp", new float[]{400f / 1023f, 64f / 1023f});
                k.run("pattern.frag", fbo, u);
                u = new HashMap<>();
                u.put("uIn", fbo); u.put("uComp", comp); u.put("uCompOffset", off); u.put("uLsb", hdr ? 1f / 1023 : 1f / 255);
                u.put("uDither", false); u.put("uFlipY", false);
                k.runToSurface("present.frag", u, w, h);
                egl.swap(ws.surface, cfg.fps.ptsUs(f) * 1000L);
            }
            ve.signalEndOfInput();
            if (!ve.awaitEos(20000)) throw new IllegalStateException("المرمّز لم ينهِ الملف التجريبي");
            mux.stop();
            if (ve.seiGuard != null) m.seiSource = ve.seiGuard.describe();
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
        m.containerTags = tags(tf);
        ex.selectTrack(0);
        String mime = tf.getString(MediaFormat.KEY_MIME);
        if (hdr && MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) {
            m.bitstream = StreamProbe.inspectBitstream(ex, tf, 3);
            log.append("البتستريم: ").append(m.bitstream.describe()).append('\n');
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            m.scan = new Hdr10FileCheck.IrapScan(m.meta);
            ByteBuffer sb = ByteBuffer.allocate(16 << 20);
            while (ex.getSampleTime() >= 0) {
                sb.clear();
                int n = ex.readSampleData(sb, 0);
                if (n > 0) { byte[] au = new byte[n]; sb.position(0); sb.get(au, 0, n); m.scan.add(au); }
                if (!ex.advance()) break;
            }
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
        m.decoderTags = tags(outFmt);
        if (hdr) log.append("سطح EGL موسوم BT.2020 PQ: ").append(pqTagged ? "نعم" : "لا (الألوان تُقاس مباشرة أعلاه)").append('\n');
        return m;
    }

    private static boolean isEglInitFailure(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof com.mojahed.hdrstudio.gl.GlException) return true;
            String m = x.getMessage();
            if (m != null && (m.contains("RGBA1010102") || m.contains("no offscreen EGL") || m.contains("eglGetDisplay")))
                return true;
        }
        return false;
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
