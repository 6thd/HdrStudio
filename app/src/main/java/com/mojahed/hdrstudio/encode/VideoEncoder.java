package com.mojahed.hdrstudio.encode;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.mojahed.hdrstudio.core.DeviceCaps;
import com.mojahed.hdrstudio.core.FrameRate;

import java.nio.ByteBuffer;

/**
 * Surface-input video encoder running in asynchronous mode on its own thread, so draining the
 * output never depends on the GL thread (no swap/drain deadlock, real back-pressure: the GL
 * thread blocks in eglSwapBuffers only while the encoder is busy, never forever).
 * Configured only from an {@link EncodeConfig} - the same description the path test ran with.
 */
public final class VideoEncoder {
    public interface Listener { void onSample(MediaCodec.BufferInfo info); }

    private final MediaCodec codec;
    private final HandlerThread thread;
    private final MuxerSink mux;
    private volatile Throwable error;
    private volatile boolean eos;
    private final Object eosLock = new Object();
    public final Surface inputSurface;
    public final String codecName;
    public final EncodeConfig config;
    public final MediaFormat configured;
    public volatile long encodedFrames;
    /** HEVC HDR10 only: validates encoder-written SEI 137/144 and adds the missing ones on IRAPs. */
    public final HdrSeiGuard seiGuard;
    private Listener listener;

    public VideoEncoder(DeviceCaps.Enc enc, EncodeConfig c, HdrMetadata meta, MuxerSink mux) throws Exception {
        if (!enc.name.equals(c.encoder)) throw new IllegalArgumentException("config is for " + c.encoder + ", not " + enc.name);
        if (c.hdr && meta == null) throw new IllegalArgumentException("HDR10 needs metadata");
        this.mux = mux;
        codecName = enc.name;
        config = c;
        MediaFormat f = buildFormat(c, meta);
        configured = f;
        seiGuard = c.hdr && c.mime.equals(DeviceCaps.HEVC) ? new HdrSeiGuard(meta) : null;
        codec = MediaCodec.createByCodecName(enc.name);
        thread = new HandlerThread("encoder-out");
        thread.start();
        codec.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec mc, int i) { }
            @Override public void onOutputBufferAvailable(MediaCodec mc, int i, MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer b = mc.getOutputBuffer(i);
                    boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (info.size > 0 && b != null) {
                        b.position(info.offset); b.limit(info.offset + info.size);
                        if (seiGuard != null) {
                            byte[] au = new byte[info.size];
                            b.get(au);
                            b.position(info.offset);
                            if (cfg) seiGuard.onCodecConfig(au);
                            else {
                                byte[] out = seiGuard.onAccessUnit(au);
                                if (out != au) {
                                    ByteBuffer nb = ByteBuffer.allocateDirect(out.length);
                                    nb.put(out).flip();
                                    b = nb;
                                    info.set(0, out.length, info.presentationTimeUs, info.flags);
                                }
                            }
                        }
                        if (!cfg) {
                            VideoEncoder.this.mux.writeVideo(b, info);
                            encodedFrames++;
                            if (listener != null) listener.onSample(info);
                        }
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
                    if (seiGuard != null) {
                        for (String k : new String[]{"csd-0", "csd-1", "csd-2"}) {
                            if (!of.containsKey(k)) continue;
                            ByteBuffer cb = of.getByteBuffer(k).duplicate();
                            byte[] csd = new byte[cb.remaining()];
                            cb.get(csd);
                            seiGuard.onCodecConfig(csd);
                        }
                    }
                    VideoEncoder.this.mux.onVideoFormat(of);
                } catch (Throwable t) { fail(t); }
            }
        }, new Handler(thread.getLooper()));
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (RuntimeException e) {
            codec.release(); thread.quitSafely();
            throw new IllegalStateException("مرحلة الفشل: VideoEncoder.configure\n" + describeFormat(f) + describeThrowable(e), e);
        }
        try {
            inputSurface = codec.createInputSurface();
            codec.start();
        } catch (RuntimeException e) {
            codec.release(); thread.quitSafely();
            throw new IllegalStateException("مرحلة الفشل: VideoEncoder.createInputSurface/start\n" + describeThrowable(e), e);
        }
    }

    /** The format handed to configure(), key by key, for the diagnostic report (ported from 2.3.2-test). */
    public static String describeFormat(MediaFormat f) {
        if (f == null) return "MediaFormat: null\n";
        StringBuilder b = new StringBuilder();
        b.append("MediaFormat: ").append(f).append('\n');
        line(b, f, "mime", false);
        line(b, f, "width", true);
        line(b, f, "height", true);
        line(b, f, "color-format", true);
        line(b, f, "bitrate", true);
        line(b, f, "frame-rate", false);
        line(b, f, "i-frame-interval", false);
        line(b, f, "bitrate-mode", true);
        line(b, f, "profile", true);
        line(b, f, "color-standard", true);
        line(b, f, "color-transfer", true);
        line(b, f, "color-range", true);
        java.nio.ByteBuffer hs = f.containsKey("hdr-static-info") ? f.getByteBuffer("hdr-static-info") : null;
        if (hs == null) {
            b.append("  hdr-static-info: absent\n");
        } else {
            java.nio.ByteBuffer d = hs.duplicate();
            b.append("  hdr-static-info bytes=").append(d.remaining());
            int n = Math.min(32, d.remaining());
            for (int i = 0; i < n; i++) b.append(String.format(java.util.Locale.US, " %02x", d.get() & 0xFF));
            b.append('\n');
        }
        return b.toString();
    }

    /** Exception chain with MediaCodec.CodecException details (diagnostic info, error code, transient/recoverable). */
    public static String describeThrowable(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) {
            b.append(x.getClass().getName()).append(": ").append(String.valueOf(x.getMessage())).append('\n');
            if (x instanceof MediaCodec.CodecException) {
                MediaCodec.CodecException ce = (MediaCodec.CodecException) x;
                b.append("  diagnostic=").append(String.valueOf(ce.getDiagnosticInfo())).append(" errorCode=").append(ce.getErrorCode())
                        .append(" transient=").append(ce.isTransient()).append(" recoverable=").append(ce.isRecoverable()).append('\n');
            }
            if (x.getCause() == x) break;
        }
        return b.toString();
    }

    private static void line(StringBuilder b, MediaFormat f, String key, boolean isInt) {
        if (!f.containsKey(key)) { b.append("  ").append(key).append(": absent\n"); return; }
        b.append("  ").append(key).append('=');
        try {
            if (isInt) b.append(f.getInteger(key));
            else {
                if (key.equals("mime")) b.append(f.getString(key));
                else {
                    try { b.append(f.getFloat(key)); } catch (ClassCastException e) { b.append(f.getInteger(key)); }
                }
            }
        } catch (ClassCastException e) {
            b.append("unreadable ").append(e.getClass().getSimpleName());
        }
        b.append('\n');
    }

    private void copyIfMissing(MediaFormat of, String key) {
        if (!of.containsKey(key) && configured.containsKey(key)) of.setInteger(key, configured.getInteger(key));
    }

    public void setListener(Listener l) { listener = l; }

    /**
     * The encode configuration for this encoder: size aligned down to the encoder's alignment, the
     * bitrate clamped to its range, VBR when supported, the HDR10 profile when declared.
     */
    public static EncodeConfig plan(DeviceCaps.Enc enc, boolean hdr, int w, int h, FrameRate fps, long requestedBitrate, float iFrameSec) {
        int wa = Math.max(2, enc.wAlign), ha = Math.max(2, enc.hAlign);
        int aw = Math.max(wa, w / wa * wa), ah = Math.max(ha, h / ha * ha);
        int mode = enc.caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR : EncodeConfig.MODE_DEFAULT;
        int profile = hdr ? (enc.hdr10Profile ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 : MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                : enc.mime.equals(DeviceCaps.AVC) ? MediaCodecInfo.CodecProfileLevel.AVCProfileHigh : -1;
        return new EncodeConfig(hdr, enc.name, enc.mime, profile, aw, ah, fps, enc.clampBitrate(requestedBitrate), mode, iFrameSec,
                hdr ? MediaFormat.COLOR_STANDARD_BT2020 : MediaFormat.COLOR_STANDARD_BT709,
                hdr ? MediaFormat.COLOR_TRANSFER_ST2084 : MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED);
    }

    public static MediaFormat buildFormat(EncodeConfig c, HdrMetadata meta) {
        MediaFormat f = MediaFormat.createVideoFormat(c.mime, c.width, c.height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate);
        f.setFloat(MediaFormat.KEY_FRAME_RATE, (float) c.fps.fps());
        f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, c.iFrameSec);
        if (c.bitrateMode != EncodeConfig.MODE_DEFAULT) f.setInteger(MediaFormat.KEY_BITRATE_MODE, c.bitrateMode);
        if (c.profile >= 0) f.setInteger(MediaFormat.KEY_PROFILE, c.profile);
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, c.colorStandard);
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, c.colorTransfer);
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, c.colorRange);
        if (c.hdr && meta != null) f.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, meta.toStaticInfo());
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
