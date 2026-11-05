package com.mojahed.hdrstudio.job;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.mojahed.hdrstudio.core.SourceInfo;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.Tex;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Decoder -> SurfaceTexture -> working-domain RGBA16F texture, one frame at a time on the GL
 * thread. Rendering a decoder buffer only after the previous frame was consumed gives natural
 * back-pressure. Colour conversion happens exactly once:
 *  - SDR 8-bit: driver Y'CbCr->RGB via samplerExternalOES (input_ext_rgb.frag)
 *  - 10-bit / HDR: raw Y'CbCr via EXT_YUV_target, converted in input_ext_yuv.frag
 */
public final class FrameSource {
    private final MediaExtractor ex = new MediaExtractor();
    private final MediaCodec dec;
    private final SurfaceTexture st;
    private final Surface surface;
    private final HandlerThread cbThread = new HandlerThread("frame-available");
    private final int oesTex;
    private final Object lock = new Object();
    private boolean frameAvailable, inputDone, outputDone;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final float[] stm = new float[16];
    private final SourceInfo src;
    private final boolean yuvPath;
    private final long endUs;
    public long ptsUs = -1;
    public int framesOut;
    /** Optional frame trace (export diagnostics): every decoded frame and those inside the range. */
    public com.mojahed.hdrstudio.encode.PipeTrace trace;

    public FrameSource(Context ctx, Uri uri, SourceInfo src, boolean yuvPath, long startUs, long endUs) throws IOException {
        this.src = src; this.yuvPath = yuvPath; this.endUs = endUs;
        ex.setDataSource(ctx, uri, null);
        ex.selectTrack(src.videoTrack);
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES20.glBindTexture(0x8D65, oesTex);
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        cbThread.start();
        st = new SurfaceTexture(oesTex);
        st.setDefaultBufferSize(src.width, src.height);
        st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture s) {
                synchronized (lock) { frameAvailable = true; lock.notifyAll(); }
            }
        }, new Handler(cbThread.getLooper()));
        surface = new Surface(st);
        MediaFormat f = ex.getTrackFormat(src.videoTrack);
        // keep raw orientation; the container carries the rotation (setOrientationHint)
        f.setInteger(MediaFormat.KEY_ROTATION, 0);
        String mime = f.getString(MediaFormat.KEY_MIME);
        if (src.dolbyVision) throw new IOException("Dolby Vision غير مدعوم كمصدر في هذا الإصدار");
        dec = MediaCodec.createDecoderByType(mime);
        dec.configure(f, surface, null, 0);
        dec.start();
    }

    /**
     * Decodes the next frame (pts >= start) and converts it into dst.
     * @return false at end of stream / end of range.
     */
    public boolean next(GlKit k, Tex dst, long startUs, java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        long stall = System.currentTimeMillis();
        while (!outputDone) {
            if (cancel.get()) throw new java.util.concurrent.CancellationException();
            if (!inputDone) {
                int i = dec.dequeueInputBuffer(2000);
                if (i >= 0) {
                    long t = ex.getSampleTime();
                    int n = (t < 0 || t > endUs) ? -1 : ex.readSampleData(dec.getInputBuffer(i), 0);
                    if (n < 0) {
                        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true;
                        if (trace != null) trace.event("المفكك: نهاية الإدخال عند عينة PTS " + t + (t > endUs ? " (بعد نهاية الفترة)" : ""));
                    }
                    else { dec.queueInputBuffer(i, 0, n, t, 0); ex.advance(); }
                }
            }
            int o = dec.dequeueOutputBuffer(info, 5000);
            if (o >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean wanted = info.size > 0 && info.presentationTimeUs >= startUs && info.presentationTimeUs <= endUs;
                if (trace != null) {
                    if (info.size > 0) trace.frame(com.mojahed.hdrstudio.encode.PipeTrace.DECODED, info.presentationTimeUs);
                    if (wanted) trace.frame(com.mojahed.hdrstudio.encode.PipeTrace.IN_RANGE, info.presentationTimeUs);
                    if (eos) trace.event("المفكك: EOS");
                }
                dec.releaseOutputBuffer(o, wanted);
                if (eos) outputDone = true;
                if (wanted) {
                    awaitFrame();
                    st.updateTexImage();
                    st.getTransformMatrix(stm);
                    convert(k, dst);
                    ptsUs = info.presentationTimeUs;
                    framesOut++;
                    return true;
                }
                stall = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stall > 15000) {
                throw new IOException("توقف فك الترميز (لا إطارات خلال 15 ثانية)");
            }
        }
        return false;
    }

    private void awaitFrame() throws IOException {
        long end = System.currentTimeMillis() + 5000;
        synchronized (lock) {
            while (!frameAvailable) {
                try { lock.wait(100); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                if (System.currentTimeMillis() > end) throw new IOException("انتهت مهلة انتظار إطار من المفكك");
            }
            frameAvailable = false;
        }
    }

    private void convert(GlKit k, Tex dst) {
        Map<String, Object> u = new HashMap<>();
        u.put("uTex", oesTex);
        u.put("uST", stm);
        if (yuvPath) {
            boolean ten = src.bitDepth >= 10;
            u.put("uCodeScale", ten ? 1023.984375f : 255f);
            u.put("uBitDepth", ten ? 10f : 8f);
            u.put("uFullRange", src.colorRange == MediaFormat.COLOR_RANGE_FULL);
            u.put("uMatrix", src.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ? 1 : src.colorStandard == MediaFormat.COLOR_STANDARD_BT709 ? 0 : 2);
            u.put("uTransfer", src.isPq() ? 1 : src.isHlg() ? 2 : 0);
            u.put("uSrc2020", src.is2020());
            u.put("uWorkDomain", src.isHdr() ? 1 : 0);
            u.put("uHlgPeak", 1000f);
            k.run("input_ext_yuv.frag", dst, u);
        } else {
            u.put("uSrc2020", src.is2020());
            k.run("input_ext_rgb.frag", dst, u);
        }
    }

    public void release() {
        try { dec.stop(); } catch (RuntimeException ignored) { }
        try { dec.release(); } catch (RuntimeException ignored) { }
        ex.release();
        surface.release();
        st.release();
        cbThread.quitSafely();
        GLES20.glDeleteTextures(1, new int[]{oesTex}, 0);
    }
}
