package com.mojahed.hdrstudio.job;

import android.content.Context;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Compares the two A/B exports (GLSL, ncnn) of the same 1-second SDR range, and both with the source:
 * container facts (video frames, first/last timestamps, audio track and samples), the picture of each
 * frame (luma PSNR and max difference of A vs B on a fine grid, chroma difference), and motion:
 * the mean frame-to-frame luma change of source, A and B on one 320x180 grid, so a flicker or a
 * frozen/dropped frame shows as a jump that the source does not have. Reported, not a gate.
 */
final class AbCompare {
    static final int GW = 320, GH = 180;

    private AbCompare() {}

    static final class Track {
        long videoFrames = -1, firstUs = -1, lastUs = -1, audioSamples = -1, audioLastUs = -1;
        int w, h;
        String audio = "لا يوجد";
    }

    static Track container(Context ctx, Uri uri, long fromUs, long toUs) throws Exception {
        Track t = new Track();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int v = -1, a = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (m.startsWith("video/") && v < 0) v = i;
                if (m.startsWith("audio/") && a < 0) a = i;
            }
            if (v >= 0) {
                MediaFormat f = ex.getTrackFormat(v);
                t.w = f.getInteger(MediaFormat.KEY_WIDTH); t.h = f.getInteger(MediaFormat.KEY_HEIGHT);
                ex.selectTrack(v);
                if (fromUs > 0) ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                t.videoFrames = 0;
                for (long pts; (pts = ex.getSampleTime()) >= 0 && pts <= toUs; ex.advance()) {
                    if (pts < fromUs) continue;
                    if (t.firstUs < 0) t.firstUs = pts;
                    t.lastUs = Math.max(t.lastUs, pts);
                    t.videoFrames++;
                }
                ex.unselectTrack(v);
            }
            if (a >= 0) {
                MediaFormat f = ex.getTrackFormat(a);
                t.audio = f.getString(MediaFormat.KEY_MIME) + " " + (f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) + " Hz " : "")
                        + (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) + "ch" : "");
                ex.selectTrack(a);
                if (fromUs > 0) ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                t.audioSamples = 0;
                for (long pts; (pts = ex.getSampleTime()) >= 0 && pts <= toUs; ex.advance()) {
                    if (pts < fromUs) continue;
                    t.audioSamples++;
                    t.audioLastUs = Math.max(t.audioLastUs, pts);
                }
            }
        } finally {
            ex.release();
        }
        return t;
    }

    /** Decoded frames of [fromUs, toUs]: luma on the fine grid (step) and Y on the motion grid. */
    static final class Frames {
        final List<byte[]> fine = new ArrayList<>(), coarse = new ArrayList<>(), chroma = new ArrayList<>();
        final List<Long> pts = new ArrayList<>();
        int fw, fh, step;
    }

    static Frames decode(Context ctx, Uri uri, long fromUs, long toUs, int maxFineW) throws Exception {
        Frames out = new Frames();
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int v = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { v = i; break; }
            }
            if (v < 0) throw new IllegalStateException("لا مسار فيديو");
            MediaFormat f = ex.getTrackFormat(v);
            ex.selectTrack(v);
            if (fromUs > 0) ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            dec.configure(f, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false;
            long deadline = System.currentTimeMillis() + 120_000;
            // read a little past toUs so reordered (B-)frames up to toUs still come out; no overflow for an open range
            final long readLimit = toUs > Long.MAX_VALUE - 200_000 ? Long.MAX_VALUE : toUs + 100_000;
            while (System.currentTimeMillis() < deadline) {
                if (!inEos) {
                    int i = dec.dequeueInputBuffer(10_000);
                    if (i >= 0) {
                        long pts = ex.getSampleTime();
                        int n = pts < 0 || pts > readLimit ? -1 : ex.readSampleData(dec.getInputBuffer(i), 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                        else { dec.queueInputBuffer(i, 0, n, pts, 0); ex.advance(); }
                    }
                }
                int o = dec.dequeueOutputBuffer(info, 10_000);
                if (o >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0 && info.presentationTimeUs >= fromUs && info.presentationTimeUs <= toUs) {
                        Image img = dec.getOutputImage(o);
                        if (img != null) { sample(img, out, maxFineW); out.pts.add(info.presentationTimeUs); img.close(); }
                    }
                    dec.releaseOutputBuffer(o, false);
                    if (eos) break;
                }
            }
        } finally {
            if (dec != null) try { dec.stop(); } catch (Throwable ignored) { }
            if (dec != null) dec.release();
            ex.release();
        }
        return out;
    }

    private static void sample(Image img, Frames out, int maxFineW) {
        Image.Plane[] p = img.getPlanes();
        int w = img.getCropRect().width(), h = img.getCropRect().height();
        int x0 = img.getCropRect().left, y0 = img.getCropRect().top;
        if (out.step == 0) {
            out.step = Math.max(1, (w + maxFineW - 1) / maxFineW);
            out.fw = w / out.step; out.fh = h / out.step;
        }
        ByteBuffer Y = p[0].getBuffer(), U = p[1].getBuffer(), V = p[2].getBuffer();
        int ys = p[0].getRowStride(), yp = p[0].getPixelStride(), cs = p[1].getRowStride(), cp = p[1].getPixelStride();
        byte[] fine = new byte[out.fw * out.fh];
        for (int y = 0; y < out.fh; y++)
            for (int x = 0; x < out.fw; x++)
                fine[y * out.fw + x] = Y.get((y0 + y * out.step) * ys + (x0 + x * out.step) * yp);
        byte[] coarse = new byte[GW * GH];
        byte[] ch = new byte[GW * GH * 2];
        for (int y = 0; y < GH; y++) {
            int sy = y0 + (int) ((y + 0.5) * h / GH);
            for (int x = 0; x < GW; x++) {
                int sx = x0 + (int) ((x + 0.5) * w / GW);
                coarse[y * GW + x] = Y.get(sy * ys + sx * yp);
                int ci = (sy / 2) * cs + (sx / 2) * cp;
                ch[(y * GW + x) * 2] = U.get(ci);
                ch[(y * GW + x) * 2 + 1] = V.get((sy / 2) * p[2].getRowStride() + (sx / 2) * p[2].getPixelStride());
            }
        }
        out.fine.add(fine); out.coarse.add(coarse); out.chroma.add(ch);
    }

    /** Text report of A (GLSL) vs B (ncnn), each against the source's motion. */
    static String compare(Frames src, Frames a, Frames b) {
        StringBuilder r = new StringBuilder();
        int n = Math.min(a.fine.size(), b.fine.size());
        r.append(String.format(Locale.US, "إطارات مفكوكة: المصدر %d · GLSL %d · ncnn %d%s\n", src.coarse.size(), a.fine.size(), b.fine.size(),
                a.fine.size() == b.fine.size() ? "" : " · العدد مختلف: المقارنة على أول " + n));
        if (n == 0) return r.toString();
        boolean sameGrid = a.fw == b.fw && a.fh == b.fh;
        double[] psnr = new double[n];
        int worst = 0, maxAbsAll = 0;
        double chromaSum = 0;
        for (int i = 0; i < n; i++) {
            double se = 0; int m = 0, cnt = 0;
            if (sameGrid) {
                byte[] x = a.fine.get(i), y = b.fine.get(i);
                for (int k = 0; k < x.length; k++) { int d = (x[k] & 0xFF) - (y[k] & 0xFF); se += d * d; m = Math.max(m, Math.abs(d)); cnt++; }
            }
            psnr[i] = cnt == 0 ? Double.NaN : se == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 * cnt / se);
            if (!Double.isNaN(psnr[i]) && psnr[i] < psnr[worst]) worst = i;
            maxAbsAll = Math.max(maxAbsAll, m);
            byte[] ca = a.chroma.get(i), cb = b.chroma.get(i);
            double cs = 0;
            for (int k = 0; k < ca.length; k++) cs += Math.abs((ca[k] & 0xFF) - (cb[k] & 0xFF));
            chromaSum += cs / ca.length;
        }
        if (sameGrid) {
            double[] sorted = psnr.clone(); Arrays.sort(sorted);
            r.append(String.format(Locale.US, "الصورة GLSL مقابل ncnn (luma على شبكة %dx%d، خطوة %d): PSNR أدنى %.1f · وسيط %.1f · أعلى %.1f dB · أسوأ إطار %d (%.3f ث) · أقصى فرق %d من 255 · متوسط فرق اللون %.2f\n",
                    a.fw, a.fh, a.step, sorted[0], sorted[n / 2], sorted[n - 1], worst + 1, a.pts.get(worst) / 1e6, maxAbsAll, chromaSum / n));
        } else r.append("أبعاد الملفين مختلفة: لا مقارنة بكسل ببكسل.\n");
        // motion: mean |frame(t) - frame(t-1)| on the shared 320x180 grid
        double[] ms = motion(src.coarse), ma = motion(a.coarse), mb = motion(b.coarse);
        int k = Math.min(ms.length, Math.min(ma.length, mb.length));
        double worstA = 0, worstB = 0, worstAB = 0; int wa = 0, wb = 0, wab = 0;
        for (int i = 0; i < k; i++) {
            double da = Math.abs(ma[i] - ms[i]), db = Math.abs(mb[i] - ms[i]), dab = Math.abs(ma[i] - mb[i]);
            if (da > worstA) { worstA = da; wa = i; }
            if (db > worstB) { worstB = db; wb = i; }
            if (dab > worstAB) { worstAB = dab; wab = i; }
        }
        r.append(String.format(Locale.US, "الحركة (متوسط تغير luma بين إطارين متتاليين على 320x180، من 255): المصدر وسيط %.2f · GLSL %.2f · ncnn %.2f\n",
                medOf(ms), medOf(ma), medOf(mb)));
        r.append(String.format(Locale.US, "  أكبر ابتعاد عن حركة المصدر: GLSL %.2f عند الانتقال %d→%d · ncnn %.2f عند %d→%d · بين المحركين %.2f عند %d→%d\n",
                worstA, wa + 1, wa + 2, worstB, wb + 1, wb + 2, worstAB, wab + 1, wab + 2));
        r.append("  (قفزة كبيرة لا يملكها المصدر تعني وميضاً أو إطاراً مكرراً/مفقوداً؛ القيم مقاسة على الملف المضغوط)\n");
        return r.toString();
    }

    private static double[] motion(List<byte[]> f) {
        double[] m = new double[Math.max(0, f.size() - 1)];
        for (int i = 1; i < f.size(); i++) {
            byte[] p = f.get(i - 1), c = f.get(i);
            double s = 0;
            for (int k = 0; k < c.length; k++) s += Math.abs((c[k] & 0xFF) - (p[k] & 0xFF));
            m[i - 1] = s / c.length;
        }
        return m;
    }

    private static double medOf(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] s = v.clone(); Arrays.sort(s); return s[s.length / 2];
    }
}
