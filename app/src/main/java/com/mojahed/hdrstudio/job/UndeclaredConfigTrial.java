package com.mojahed.hdrstudio.job;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.os.Debug;

import com.mojahed.hdrstudio.core.ColorMath;
import com.mojahed.hdrstudio.core.DeviceCaps;
import com.mojahed.hdrstudio.core.FrameRate;
import com.mojahed.hdrstudio.encode.EncodeConfig;
import com.mojahed.hdrstudio.encode.Hdr10FileCheck;
import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.HevcBitstream;
import com.mojahed.hdrstudio.encode.PathTestVerdict;
import com.mojahed.hdrstudio.encode.PrecisionMetric;
import com.mojahed.hdrstudio.encode.StreamProbe;
import com.mojahed.hdrstudio.encode.VideoEncoder;
import com.mojahed.hdrstudio.encode.MuxerSink;
import com.mojahed.hdrstudio.gl.EglDiag;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.gl.Tex;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Isolated experiment: bind EGL configs #24 and #25 (and FP16 #23) to a real Main10 encoder
 * Surface without requiring EGL_RECORDABLE_ANDROID. Not an approved export path. Surface
 * creation or a Main10 file is not enough; without a measured gradation the result stays
 * INCONCLUSIVE and export stays closed.
 */
public final class UndeclaredConfigTrial {
    private static final int[] CONFIG_IDS = {24, 25, 23};
    private static final int W = 640, H = 360, FRAMES = 8, TIMEOUT_MS = 8000;
    private static final double[][] PATCHES = new double[16][];
    static {
        for (int i = 0; i < 9; i++) { double g = 0.05 + 0.9 * i / 8.0; PATCHES[i] = new double[]{g, g, g}; }
        PATCHES[9] = new double[]{0.75, 0.10, 0.10};
        PATCHES[10] = new double[]{0.10, 0.75, 0.10};
        PATCHES[11] = new double[]{0.10, 0.10, 0.75};
        PATCHES[12] = new double[]{0.10, 0.70, 0.70};
        PATCHES[13] = new double[]{0.70, 0.10, 0.70};
        PATCHES[14] = new double[]{0.70, 0.70, 0.10};
        PATCHES[15] = new double[]{0.62, 0.48, 0.40};
    }

    private UndeclaredConfigTrial() {}

    public static String run(Context ctx, DeviceCaps caps) {
        StringBuilder log = new StringBuilder();
        log.append("=== experimental EGL config trial (NOT an approved export path) ===\n");
        log.append("configs: #24, #25, then FP16 #23. RECORDABLE is not required for this probe.\n");
        log.append("exportAllowed stays false regardless of surface/file success.\n");
        DeviceCaps.Enc enc = pickMain10(caps);
        if (enc == null) {
            log.append("no HEVC Main10 encoder at all. closed.\n");
            softwareEval(ctx, caps, log);
            closeEglAttempt(log, "no Main10 encoder");
            return log.toString();
        }
        log.append("trial encoder: ").append(enc.describe()).append('\n');
        log.append(EglDiag.encoderColorFormats(enc));

        EGLDisplay dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (dpy == null || dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
            log.append("eglInitialize failed ").append(EglDiag.takeErrorLine("")).append('\n');
            softwareEval(ctx, caps, log);
            closeEglAttempt(log, "eglInitialize failed");
            return log.toString();
        }
        boolean anySurface = false, anyFile = false, anyMeasured = false, anyTenBitProof = false;
        try {
            for (int id : CONFIG_IDS) {
                log.append("\n----- try config #").append(id).append(" -----\n");
                boolean[] flags = new boolean[4];
                tryOne(ctx, dpy, enc, id, log, flags);
                anySurface |= flags[0];
                anyFile |= flags[1];
                anyMeasured |= flags[2];
                anyTenBitProof |= flags[3];
            }
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglReleaseThread();
        }
        softwareEval(ctx, caps, log);
        if (!anySurface) closeEglAttempt(log, "eglCreateWindowSurface failed on #24/#25/#23");
        else if (!anyMeasured) closeEglAttempt(log, "surface and/or Main10 file existed but precision was not measured");
        else if (!anyTenBitProof) closeEglAttempt(log, "precision measured and did not prove 10-bit");
        else {
            log.append("EXPERIMENTAL: coverage90 passed on an undeclared config. This is NOT an approved path; HDR export stays closed until a RECORDABLE high-precision config or a licensed software 10-bit encoder is adopted.\n");
            log.append("النتيجة: تصدير HDR10 ممنوع بهذا المسار التجريبي.\n");
        }
        log.append("SDR export is unchanged (independent 8-bit RECORDABLE path). 8-bit was not used as an HDR stand-in.\n");
        return log.toString();
    }

    private static DeviceCaps.Enc pickMain10(DeviceCaps caps) {
        DeviceCaps.Enc best = caps.pickHdrEncoder(W, H, 30);
        if (best != null) return best;
        for (DeviceCaps.Enc e : caps.encoders)
            if (DeviceCaps.HEVC.equals(e.mime) && e.main10) return e;
        return null;
    }

    /** flags: [windowOk, fileOk, precisionMeasured] */
    private static void tryOne(Context ctx, EGLDisplay dpy, DeviceCaps.Enc enc, int configId, StringBuilder log, boolean[] flags) {
        EGLConfig cfg = findId(dpy, configId, log);
        if (cfg == null) return;
        int st = EglDiag.get(dpy, cfg, EGL14.EGL_SURFACE_TYPE);
        int rec = EglDiag.get(dpy, cfg, EglDiag.RECORDABLE);
        int es = EglDiag.get(dpy, cfg, EGL14.EGL_RENDERABLE_TYPE);
        log.append("WINDOW=").append((st & EGL14.EGL_WINDOW_BIT) != 0)
                .append(" RECORDABLE=").append(rec)
                .append(" ES3=").append((es & EglDiag.ES3) != 0).append('\n');
        if ((st & EGL14.EGL_WINDOW_BIT) == 0) {
            log.append("CLOSED this attempt: no WINDOW_BIT\n");
            return;
        }
        if ((es & EglDiag.ES3) == 0) {
            log.append("CLOSED this attempt: not GLES3\n");
            return;
        }
        EncodeConfig cfgEnc = VideoEncoder.plan(enc, true, W, H, FrameRate.FPS_30, 2_000_000, 1f);
        File file = new File(ctx.getCacheDir(), "trial_cfg" + configId + ".mp4");
        file.delete();
        EGLContext eglCtx = EGL14.EGL_NO_CONTEXT;
        EGLSurface win = EGL14.EGL_NO_SURFACE;
        VideoEncoder ve = null;
        MuxerSink mux = null;
        GlKit kit = null;
        HdrMetadata meta = EncodeSelfTest.sampleMetadata();
        try {
            EglDiag.takeError();
            eglCtx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            int ce = EglDiag.takeError();
            boolean ctxOk = eglCtx != null && eglCtx != EGL14.EGL_NO_CONTEXT;
            log.append("eglCreateContext: ").append(ctxOk ? "ok" : "FAIL").append(" ").append(EglDiag.errName(ce)).append('\n');
            if (!ctxOk) { log.append("CLOSED this attempt: no GLES3 context\n"); return; }

            mux = new MuxerSink(file.getAbsolutePath(), 0);
            try {
                ve = new VideoEncoder(enc, cfgEnc, meta, mux);
            } catch (Throwable t) {
                log.append("VideoEncoder configure FAIL: ").append(t.getMessage()).append('\n');
                log.append("CLOSED this attempt: encoder rejected Main10 surface format\n");
                return;
            }
            EglDiag.takeError();
            win = EGL14.eglCreateWindowSurface(dpy, cfg, ve.inputSurface, new int[]{EGL14.EGL_NONE}, 0);
            int we = EglDiag.takeError();
            boolean winOk = win != null && win != EGL14.EGL_NO_SURFACE;
            log.append("eglCreateWindowSurface(encoder): ").append(winOk ? "ok" : "FAIL")
                    .append(" ").append(EglDiag.errName(we)).append(" (0x").append(Integer.toHexString(we)).append(")\n");
            if (!winOk) {
                log.append("CLOSED this attempt: encoder Surface rejected this config (undeclared RECORDABLE)\n");
                return;
            }
            flags[0] = true;
            EglDiag.takeError();
            boolean cur = EGL14.eglMakeCurrent(dpy, win, win, eglCtx);
            log.append("eglMakeCurrent: ").append(cur ? "ok" : "FAIL").append(" ").append(EglDiag.takeErrorLine("")).append('\n');
            if (!cur) { log.append("CLOSED this attempt: makeCurrent failed\n"); return; }

            kit = new GlKit(new ShaderLoader(ctx.getAssets()));
            Tex fbo = kit.tex(cfgEnc.width, cfgEnc.height, true);
            float[] patch = new float[48];
            for (int i = 0; i < 16; i++) for (int ch = 0; ch < 3; ch++) patch[i * 3 + ch] = (float) PATCHES[i][ch];
            int drawn = 0;
            for (int f = 0; f < FRAMES; f++) {
                Map<String, Object> u = new HashMap<>();
                u.put("uPatch", patch); u.put("uSize", new float[]{cfgEnc.width, cfgEnc.height});
                u.put("uRamp", new float[]{400f / 1023f, 64f / 1023f});
                kit.run("pattern.frag", fbo, u);
                u = new HashMap<>();
                u.put("uIn", fbo); u.put("uComp", ColorMath.IDENTITY3); u.put("uCompOffset", new float[3]);
                u.put("uLsb", 1f / 1023); u.put("uDither", false); u.put("uFlipY", false);
                kit.runToSurface("present.frag", u, cfgEnc.width, cfgEnc.height);
                EGLExt.eglPresentationTimeANDROID(dpy, win, cfgEnc.fps.ptsUs(f) * 1000L);
                EglDiag.takeError();
                boolean sw = EGL14.eglSwapBuffers(dpy, win);
                int se = EglDiag.takeError();
                log.append("  draw+swap frame ").append(f).append(": ").append(sw ? "ok" : "FAIL")
                        .append(" ").append(EglDiag.errName(se)).append('\n');
                if (!sw) { log.append("CLOSED this attempt: swap failed\n"); return; }
                drawn++;
            }
            log.append("frames drawn: ").append(drawn).append('\n');
            ve.signalEndOfInput();
            boolean eos = ve.awaitEos(TIMEOUT_MS);
            log.append("encoder EOS: ").append(eos ? "ok" : "TIMEOUT").append(" encodedFrames=").append(ve.encodedFrames).append('\n');
            if (!eos || ve.encodedFrames <= 0) {
                log.append("CLOSED this attempt: encoder produced no frames\n");
                return;
            }
            mux.stop();
            flags[1] = true;
            log.append("Main10 file produced: yes (").append(file.length()).append(" bytes). not sufficient by itself.\n");
            inspectFile(file, cfgEnc, meta, log, flags);
        } catch (Throwable t) {
            log.append("CLOSED this attempt: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        } finally {
            if (kit != null) try { kit.release(); } catch (Throwable ignored) { }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (win != null && win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win);
            if (eglCtx != null && eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, eglCtx);
            if (ve != null) try { ve.release(); } catch (Throwable ignored) { }
            if (mux != null) try { mux.release(); } catch (Throwable ignored) { }
            file.delete();
        }
    }

    private static void inspectFile(File file, EncodeConfig cfgEnc, HdrMetadata meta, StringBuilder log, boolean[] flags) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(file.getAbsolutePath());
        MediaFormat tf = ex.getTrackFormat(0);
        log.append("container tags: standard=").append(intKey(tf, MediaFormat.KEY_COLOR_STANDARD))
                .append(" transfer=").append(intKey(tf, MediaFormat.KEY_COLOR_TRANSFER))
                .append(" range=").append(intKey(tf, MediaFormat.KEY_COLOR_RANGE)).append('\n');
        ex.selectTrack(0);
        HevcBitstream.Info bs = StreamProbe.inspectBitstream(ex, tf, 3);
        log.append("bitstream: ").append(bs.describe()).append('\n');
        ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        Hdr10FileCheck.IrapScan scan = new Hdr10FileCheck.IrapScan(meta);
        ByteBuffer sb = ByteBuffer.allocate(4 << 20);
        while (ex.getSampleTime() >= 0) {
            sb.clear();
            int n = ex.readSampleData(sb, 0);
            if (n > 0) { byte[] au = new byte[n]; sb.position(0); sb.get(au, 0, n); scan.add(au); }
            if (!ex.advance()) break;
        }
        log.append("SEI scan: iraps=").append(scan.iraps).append(" missing=").append(scan.missing)
                .append(" mismatched=").append(scan.mismatched).append(" ok=").append(scan.ok()).append('\n');
        ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        int y = cfgEnc.height - Math.max(1, cfgEnc.height / 64);
        StreamProbe.PixelDepth px = StreamProbe.probePixels(ex, tf, 3,
                new int[]{cfgEnc.width / 16, y, cfgEnc.width - cfgEnc.width / 16, y + 1}, y);
            log.append("gradation: ").append(px.describe()).append('\n');
            if (px.status == PathTestVerdict.PixelProbe.MEASURED) {
                flags[2] = true;
                boolean ok = PrecisionMetric.proves10bit(px.minCoverage90);
                flags[3] = ok;
                log.append("coverage90 ").append(ok ? "PASS" : "FAIL")
                    .append(" (").append(px.minCoverage90).append(")\n");
            } else {
                log.append("precision NOT measured (").append(px.status).append(") → INCONCLUSIVE; export stays closed.\n");
            }
        ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        decodeColour(ex, tf, cfgEnc.width, cfgEnc.height, log);
        ex.release();
    }

    private static void decodeColour(MediaExtractor ex, MediaFormat tf, int w, int h, StringBuilder log) {
        String mime = tf.getString(MediaFormat.KEY_MIME);
        MediaCodec dec = null;
        try {
            dec = MediaCodec.createDecoderByType(mime);
            boolean p010 = false;
            for (int cf : dec.getCodecInfo().getCapabilitiesForType(mime).colorFormats) if (cf == 54) p010 = true;
            MediaFormat df = StreamProbe.bitstreamOnly(tf);
            df.setInteger(MediaFormat.KEY_COLOR_FORMAT, p010 ? 54 : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            log.append("decoder P010 output declared: ").append(p010).append('\n');
            dec.configure(df, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inDone = false, got = false;
            int bits = 8;
            double[][][] grid = new double[4][4][3];
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(1000);
                    if (i >= 0) {
                        int n = ex.readSampleData(dec.getInputBuffer(i), 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int o = dec.dequeueOutputBuffer(bi, 1000);
                if (o >= 0) {
                    if (bi.size > 0) {
                        Image img = dec.getOutputImage(o);
                        if (img != null) {
                            bits = img.getFormat() == ImageFormat.YCBCR_P010 ? 10 : 8;
                            sample(img, grid, w, h);
                            img.close();
                            got = true;
                        }
                    }
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(o, false);
                    if (eos) break;
                }
            }
            if (!got) {
                log.append("colour: NOT measured (no decoded frame) → INCONCLUSIVE\n");
                return;
            }
            log.append("decoded image bits: ").append(bits).append('\n');
            if (bits < 10) log.append("colour sampled from 8-bit decode — not proof of 10-bit encode precision.\n");
            double best = Double.POSITIVE_INFINITY;
            String bestD = "";
            double scale = (1 << bits) - 1;
            for (int flip = 0; flip < 2; flip++)
                for (int matrix = 0; matrix < 3; matrix++)
                    for (int fr = 0; fr < 2; fr++) {
                        double[][] aff = ColorMath.rgbToYuvAffine(matrix, fr == 1, bits);
                        double se = 0;
                        for (int py = 0; py < 4; py++)
                            for (int px = 0; px < 4; px++) {
                                int idx = (flip == 0 ? 3 - py : py) * 4 + px;
                                double[] e = ColorMath.apply(aff, PATCHES[idx]);
                                for (int c = 0; c < 3; c++) { double dlt = (e[c] - grid[py][px][c]) * scale; se += dlt * dlt; }
                            }
                        double rms = Math.sqrt(se / 48);
                        if (rms < best) {
                            best = rms;
                            bestD = (matrix == 1 ? "BT.2020" : matrix == 0 ? "BT.709" : "BT.601") + (fr == 1 ? " Full" : " Limited") + (flip == 1 ? " flipped" : "");
                        }
                    }
            log.append(String.format(Locale.US, "colour fit: %s RMS %.2f code at %d-bit (informational; does not open export)\n", bestD, best, bits));
        } catch (Throwable t) {
            log.append("colour decode FAIL: ").append(t.getMessage()).append(" → INCONCLUSIVE\n");
        } finally {
            if (dec != null) { try { dec.stop(); } catch (Throwable ignored) { } try { dec.release(); } catch (Throwable ignored) { } }
        }
    }

    private static void sample(Image img, double[][][] grid, int w, int h) {
        boolean sixteen = img.getFormat() == ImageFormat.YCBCR_P010;
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
                for (int c = 0; c < 3; c++) grid[py][px][c] = acc[c] / Math.max(1, cnt[c]) / max;
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

    private static EGLConfig findId(EGLDisplay d, int id, StringBuilder log) {
        int[] n = new int[1];
        EGL14.eglGetConfigs(d, null, 0, 0, n, 0);
        EGLConfig[] all = new EGLConfig[Math.max(n[0], 1)];
        EGL14.eglGetConfigs(d, all, 0, all.length, n, 0);
        for (int i = 0; i < n[0]; i++) {
            if (EglDiag.get(d, all[i], EGL14.EGL_CONFIG_ID) == id) {
                log.append("present: ").append(EglDiag.configLine(d, all[i])).append('\n');
                return all[i];
            }
        }
        log.append("config #").append(id).append(" not present on this device\n");
        return null;
    }

    private static void softwareEval(Context ctx, DeviceCaps caps, StringBuilder log) {
        log.append("\n=== software 10-bit encoder evaluation (not merged) ===\n");
        log.append("license: AOSP c2.android / OMX.google codecs are Apache-2.0 via Android. x265 is GPL-2 and is NOT bundled. Vendor Hisilicon encoder is proprietary.\n");
        DeviceCaps.Enc sw = null;
        for (DeviceCaps.Enc e : caps.encoders) {
            if (!DeviceCaps.HEVC.equals(e.mime)) continue;
            if (e.swOnly || (e.name != null && (e.name.contains("android") || e.name.contains("google") || e.name.contains("c2.android")))) {
                log.append("software HEVC candidate: ").append(e.describe()).append(" main10=").append(e.main10).append('\n');
                log.append(EglDiag.encoderColorFormats(e));
                if (e.main10) sw = e;
            }
        }
        if (sw == null) {
            log.append("no software HEVC Main10 encoder declared. A 10-bit software path would mean bundling x265 (GPL-2) or exporting HDR on a computer. Not merged.\n");
            return;
        }
        boolean p010 = false;
        for (int cf : sw.caps.colorFormats) if (cf == 54) p010 = true;
        if (!p010) {
            log.append(sw.name).append(" is Main10 but does not declare COLOR_FormatYUVP010. Will not feed it 8-bit Surface as an HDR stand-in. Not merged.\n");
            return;
        }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long avail0 = mi.availMem, pss0 = Debug.getPss() * 1024L;
        long t0 = System.nanoTime();
        MediaCodec c = null;
        int outFrames = 0;
        try {
            c = MediaCodec.createByCodecName(sw.name);
            MediaFormat f = MediaFormat.createVideoFormat(DeviceCaps.HEVC, 320, 240);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, 54);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 400_000);
            f.setFloat(MediaFormat.KEY_FRAME_RATE, 30f);
            f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f);
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inDone = false;
            int queued = 0;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    int i = c.dequeueInputBuffer(500);
                    if (i >= 0) {
                        ByteBuffer b = c.getInputBuffer(i);
                        b.clear();
                        fillP010Ramp(b, 320, 240, queued);
                        long pts = queued * 33_333L;
                        if (queued >= 4) {
                            c.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            c.queueInputBuffer(i, 0, b.position(), pts, 0);
                            queued++;
                        }
                    }
                }
                int o = c.dequeueOutputBuffer(bi, 500);
                if (o >= 0) {
                    if (bi.size > 0 && (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) outFrames++;
                    c.releaseOutputBuffer(o, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Throwable t) {
            log.append("software P010 micro-encode FAIL: ").append(t.getMessage()).append('\n');
        } finally {
            if (c != null) { try { c.stop(); } catch (Throwable ignored) { } try { c.release(); } catch (Throwable ignored) { } }
        }
        am.getMemoryInfo(mi);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        log.append(String.format(Locale.US, "software P010 micro-encode: %d output AU in %d ms · availMem %d→%d MB · PSS %+d MB\n",
                outFrames, ms, avail0 / (1 << 20), mi.availMem / (1 << 20), (Debug.getPss() * 1024L - pss0) / (1 << 20)));
        log.append("result: experiment only. not merged into export. HDR on phone stays closed unless a later review adopts this encoder after a full path test.\n");
    }

    private static void fillP010Ramp(ByteBuffer b, int w, int h, int frame) {
        // 10-bit codes in the top 10 bits of little-endian 16-bit (Android P010)
        int yStride = w * 2, uvStride = w * 2;
        for (int y = 0; y < h; y++) {
            int code = 64 + (y * 876 / Math.max(1, h - 1));
            int packed = code << 6;
            for (int x = 0; x < w; x++) {
                b.put((byte) (packed & 0xFF));
                b.put((byte) ((packed >> 8) & 0xFF));
            }
        }
        int uv = 512 << 6;
        for (int i = 0; i < h / 2 * w; i++) {
            b.put((byte) (uv & 0xFF));
            b.put((byte) ((uv >> 8) & 0xFF));
        }
        // silence unused
        if (frame < 0 || yStride < 0 || uvStride < 0) b.clear();
    }

    private static void closeEglAttempt(StringBuilder log, String why) {
        log.append("\nCLOSED current EGL HDR attempt: ").append(why).append('\n');
        log.append("النتيجة: تصدير HDR10 ممنوع. لم يُستخدم إدخال 8-bit بديلاً. تصدير SDR مستقل.\n");
        log.append("بديل مؤجّل: مسار برمجي 10-bit (ترخيص + قياس كامل) أو تصدير HDR على الكمبيوتر.\n");
    }

    private static int intKey(MediaFormat f, String k) { return f != null && f.containsKey(k) ? f.getInteger(k) : -1; }
}
