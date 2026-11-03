package com.mojahed.hdrstudio.encode;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Audio handling, never silent:
 *  1. passthrough if the MP4 muxer accepts the source format (AAC, AMR, ...);
 *  2. otherwise decode + re-encode to AAC-LC into a temporary .m4a, then passthrough that;
 *  3. if both fail the export fails with a message (the user may explicitly choose "no audio").
 * Samples are written interleaved with video: pumpUntil(videoPts + lead).
 */
public final class AudioTrackSource {
    public final String mode;            // "copy", "aac-transcode", "none"
    public final MediaFormat format;
    private final MediaExtractor ex;
    private final ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final long startUs, endUs, offsetUs;
    private boolean done;
    public long written;

    private AudioTrackSource(String mode, MediaExtractor ex, MediaFormat f, long startUs, long endUs, long offsetUs) {
        this.mode = mode; this.ex = ex; this.format = f; this.startUs = startUs; this.endUs = endUs; this.offsetUs = offsetUs;
    }

    /** startUs/endUs limit the range (preview clips); offsetUs is subtracted from timestamps. */
    public static AudioTrackSource open(Context ctx, Uri src, int track, File cacheDir, long startUs, long endUs, long offsetUs,
                                        AtomicBoolean cancel) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, src, null);
        MediaFormat f = ex.getTrackFormat(track);
        File probe = new File(cacheDir, "mux_probe.mp4");
        if (MuxerSink.canPassthrough(f, probe.getAbsolutePath())) {
            ex.selectTrack(track);
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            return new AudioTrackSource("copy", ex, f, startUs, endUs, offsetUs);
        }
        ex.release();
        File aac = new File(cacheDir, "audio_transcoded.m4a");
        transcodeToAac(ctx, src, track, aac, startUs, endUs, cancel);
        MediaExtractor ex2 = new MediaExtractor();
        ex2.setDataSource(aac.getAbsolutePath());
        ex2.selectTrack(0);
        return new AudioTrackSource("aac-transcode", ex2, ex2.getTrackFormat(0), 0, Long.MAX_VALUE, 0);
    }

    /** Write audio samples with pts <= limitUs. */
    public void pumpUntil(MuxerSink mux, long limitUs) {
        while (!done) {
            long t = ex.getSampleTime();
            if (t < 0 || t > endUs) { done = true; break; }
            long outPts = t - offsetUs;
            if (outPts > limitUs) break;
            buf.clear();
            int n = ex.readSampleData(buf, 0);
            if (n < 0) { done = true; break; }
            if (t >= startUs && outPts >= 0) {
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, n, outPts, flags);
                mux.writeAudio(buf, info);
                written++;
            }
            ex.advance();
        }
    }

    public void release() { try { ex.release(); } catch (RuntimeException ignored) { } }

    // ------------------------------------------------------------------ transcoding
    private static void transcodeToAac(Context ctx, Uri src, int track, File out, long startUs, long endUs, AtomicBoolean cancel) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(ctx, src, null);
            MediaFormat in = ex.getTrackFormat(track);
            ex.selectTrack(track);
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            in.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
            dec = MediaCodec.createDecoderByType(in.getString(MediaFormat.KEY_MIME));
            dec.configure(in, null, null, 0);
            dec.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo(), eo = new MediaCodec.BufferInfo();
            boolean extractorDone = false, decoderDone = false, encInputDone = false, encDone = false;
            int sr = 0, ch = 0, muxTrack = -1;
            long framesQueued = 0, firstPts = Long.MIN_VALUE;
            byte[] pending = null;
            int pendingOff = 0;
            while (!encDone) {
                if (cancel.get()) throw new IOException("cancelled");
                if (!extractorDone) {
                    int i = dec.dequeueInputBuffer(2000);
                    if (i >= 0) {
                        long t = ex.getSampleTime();
                        int n = (t < 0 || t > endUs) ? -1 : ex.readSampleData(dec.getInputBuffer(i), 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); extractorDone = true; }
                        else { dec.queueInputBuffer(i, 0, n, t, 0); ex.advance(); }
                    }
                }
                if (pending == null && !decoderDone) {
                    int o = dec.dequeueOutputBuffer(bi, 2000);
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && enc == null) {
                        MediaFormat pf = dec.getOutputFormat();
                        sr = pf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        ch = pf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (pf.containsKey(MediaFormat.KEY_PCM_ENCODING) && pf.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT)
                            throw new IOException("unsupported PCM encoding " + pf.getInteger(MediaFormat.KEY_PCM_ENCODING));
                        MediaFormat ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch);
                        ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                        ef.setInteger(MediaFormat.KEY_BIT_RATE, Math.min(320000, 96000 * Math.max(1, ch)));
                        ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
                        enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        enc.start();
                    } else if (o >= 0) {
                        if (bi.size > 0 && bi.presentationTimeUs >= startUs) {
                            ByteBuffer pb = dec.getOutputBuffer(o);
                            pending = new byte[bi.size];
                            pb.position(bi.offset);
                            pb.get(pending, 0, bi.size);
                            pendingOff = 0;
                            if (firstPts == Long.MIN_VALUE) firstPts = bi.presentationTimeUs;
                        }
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderDone = true;
                        dec.releaseOutputBuffer(o, false);
                    }
                }
                if (enc == null) { if (decoderDone) throw new IOException("audio decoder produced no PCM"); continue; }
                if (!encInputDone && (pending != null || decoderDone)) {
                    int i = enc.dequeueInputBuffer(2000);
                    if (i >= 0) {
                        if (pending != null) {
                            ByteBuffer ib = enc.getInputBuffer(i);
                            int n = Math.min(ib.remaining(), pending.length - pendingOff);
                            n -= n % (2 * ch);
                            ib.put(pending, pendingOff, n);
                            long pts = Math.max(0, firstPts - startUs) + framesQueued * 1000000L / sr;
                            enc.queueInputBuffer(i, 0, n, pts, 0);
                            framesQueued += n / (2 * ch);
                            pendingOff += n;
                            if (pendingOff >= pending.length) pending = null;
                        } else {
                            enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encInputDone = true;
                        }
                    }
                }
                int o = enc.dequeueOutputBuffer(eo, encInputDone ? 10000 : 0);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (o >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(o);
                    if ((eo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && eo.size > 0 && muxStarted) {
                        ob.position(eo.offset);
                        ob.limit(eo.offset + eo.size);
                        mux.writeSampleData(muxTrack, ob, eo);
                    }
                    enc.releaseOutputBuffer(o, false);
                    if ((eo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
                }
            }
            if (!muxStarted) throw new IOException("audio encoder produced no output");
        } catch (RuntimeException e) {
            throw new IOException("تعذر تحويل الصوت إلى AAC: " + e.getMessage(), e);
        } finally {
            if (dec != null) try { dec.stop(); dec.release(); } catch (RuntimeException ignored) { }
            if (enc != null) try { enc.stop(); enc.release(); } catch (RuntimeException ignored) { }
            if (mux != null) try { if (muxStarted) mux.stop(); mux.release(); } catch (RuntimeException ignored) { }
            ex.release();
        }
    }
}
