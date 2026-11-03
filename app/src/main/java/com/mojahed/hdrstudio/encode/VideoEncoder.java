package com.mojahed.hdrstudio.encode;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.mojahed.hdrstudio.core.DeviceCaps;

import java.nio.ByteBuffer;

/**
 * Surface-input video encoder running in asynchronous mode on its own thread, so draining the
 * output never depends on the GL thread (no swap/drain deadlock, real back-pressure: the GL
 * thread blocks in eglSwapBuffers only while the encoder is busy, never forever).
 */
public final class VideoEncoder {
    public static final class Config {
        public int width, height;
        public float fps;
        public long bitrate;
        public boolean hdr;              // HEVC Main10 + BT.2020/PQ + static metadata
        public String mime;
        public float iFrameSec = 1f;
        public HdrMetadata meta;         // hdr only
    }

    public interface Listener { void onSample(MediaCodec.BufferInfo info); }

    private final MediaCodec codec;
    private final HandlerThread thread;
    private final MuxerSink mux;
    private volatile Throwable error;
    private volatile boolean eos;
    private final Object eosLock = new Object();
    public final Surface inputSurface;
    public final String codecName;
    public final MediaFormat configured;
    public volatile long encodedFrames;
    /** Key frames into which the app inserted the HDR10 SEI because the encoder had not written it. */
    public volatile long seiInjectedFrames, seiFromEncoderFrames;
    private final byte[] hdrSei;
    private Listener listener;

    public VideoEncoder(DeviceCaps.Enc enc, Config c, MuxerSink mux) throws Exception {
        this.mux = mux;
        codecName = enc.name;
        MediaFormat f = buildFormat(enc, c);
        configured = f;
        hdrSei = c.hdr && c.meta != null && c.mime.equals(DeviceCaps.HEVC) ? c.meta.toSeiNal() : null;
        codec = MediaCodec.createByCodecName(enc.name);
        thread = new HandlerThread("encoder-out");
        thread.start();
        codec.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec mc, int i) { }
            @Override public void onOutputBufferAvailable(MediaCodec mc, int i, MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer b = mc.getOutputBuffer(i);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && info.size > 0 && b != null) {
                        b.position(info.offset); b.limit(info.offset + info.size);
                        if (hdrSei != null && (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            // HDR10 static metadata must be in the bitstream (SEI 137/144) on every IRAP.
                            // Many encoders write it from KEY_HDR_STATIC_INFO; if this one did not, insert it.
                            byte[] au = new byte[info.size];
                            b.get(au);
                            b.position(info.offset);
                            if (HevcBitstream.hasMdcv(au)) seiFromEncoderFrames++;
                            else {
                                byte[] withSei = HevcBitstream.insertBeforeFirstVcl(au, hdrSei);
                                if (withSei == null) throw new IllegalStateException("تعذر إدراج SEI في إطار HEVC (صيغة غير متوقعة)");
                                ByteBuffer nb = ByteBuffer.allocateDirect(withSei.length);
                                nb.put(withSei).flip();
                                b = nb;
                                info.set(0, withSei.length, info.presentationTimeUs, info.flags);
                                seiInjectedFrames++;
                            }
                        }
                        VideoEncoder.this.mux.writeVideo(b, info);
                        encodedFrames++;
                        if (listener != null) listener.onSample(info);
                    }
                    mc.releaseOutputBuffer(i, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) signalEos();
                } catch (Throwable t) { fail(t); }
            }
            @Override public void onError(MediaCodec mc, MediaCodec.CodecException e) { fail(e); }
            @Override public void onOutputFormatChanged(MediaCodec mc, MediaFormat of) {
                try {
                    // Carry the colour description we configured (and verified by the self test)
                    // into the container's colr box when the codec does not echo it.
                    copyIfMissing(of, MediaFormat.KEY_COLOR_STANDARD);
                    copyIfMissing(of, MediaFormat.KEY_COLOR_TRANSFER);
                    copyIfMissing(of, MediaFormat.KEY_COLOR_RANGE);
                    if (configured.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) && !of.containsKey(MediaFormat.KEY_HDR_STATIC_INFO))
                        of.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, configured.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO));
                    VideoEncoder.this.mux.onVideoFormat(of);
                } catch (Throwable t) { fail(t); }
            }
        }, new Handler(thread.getLooper()));
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (RuntimeException e) {
            codec.release(); thread.quitSafely();
            throw new IllegalStateException("رفض المرمّز الإعدادات: " + e.getMessage(), e);
        }
        inputSurface = codec.createInputSurface();
        codec.start();
    }

    private void copyIfMissing(MediaFormat of, String key) {
        if (!of.containsKey(key) && configured.containsKey(key)) of.setInteger(key, configured.getInteger(key));
    }

    public void setListener(Listener l) { listener = l; }

    public static MediaFormat buildFormat(DeviceCaps.Enc enc, Config c) {
        MediaFormat f = MediaFormat.createVideoFormat(c.mime, c.width, c.height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, enc.clampBitrate(c.bitrate));
        f.setFloat(MediaFormat.KEY_FRAME_RATE, c.fps);
        f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, c.iFrameSec);
        if (enc.caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        if (c.hdr) {
            f.setInteger(MediaFormat.KEY_PROFILE, enc.hdr10Profile ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    : MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            if (c.meta != null) f.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, c.meta.toStaticInfo());
        } else {
            if (c.mime.equals(DeviceCaps.AVC)) f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        return f;
    }

    private void fail(Throwable t) { if (error == null) error = t; signalEos(); }
    private void signalEos() { synchronized (eosLock) { eos = true; eosLock.notifyAll(); } }

    public void checkError() {
        Throwable t = error;
        if (t != null) throw new IllegalStateException("خطأ في الترميز: " + t.getMessage(), t);
    }

    public void signalEndOfInput() { codec.signalEndOfInputStream(); }

    /** Waits for the end-of-stream buffer (all frames written). */
    public boolean awaitEos(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (eosLock) {
            while (!eos) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return false;
                eosLock.wait(left);
            }
        }
        checkError();
        return true;
    }

    public void release() {
        try { codec.stop(); } catch (RuntimeException ignored) { }
        try { codec.release(); } catch (RuntimeException ignored) { }
        try { inputSurface.release(); } catch (RuntimeException ignored) { }
        thread.quitSafely();
    }
}
