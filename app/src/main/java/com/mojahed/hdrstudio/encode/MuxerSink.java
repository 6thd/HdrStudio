package com.mojahed.hdrstudio.encode;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Thread-safe MP4 muxer wrapper. Starts only when the video format is known and the audio
 * format (if any) has been registered. Counts what was written so the job can verify it.
 */
public final class MuxerSink {
    private final MediaMuxer muxer;
    private int videoTrack = -1, audioTrack = -1;
    private MediaFormat pendingAudio;
    private boolean started, stopped;
    public long videoSamples, audioSamples, lastVideoPtsUs = -1, lastAudioPtsUs = -1, bytes;
    /** Optional frame trace (export diagnostics). */
    public volatile PipeTrace trace;

    public MuxerSink(FileDescriptor fd, int orientationDegrees) throws IOException {
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.setOrientationHint(orientationDegrees);
    }

    public MuxerSink(String path, int orientationDegrees) throws IOException {
        muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.setOrientationHint(orientationDegrees);
    }

    /** Must be called before the video format arrives. Throws if the container rejects it. */
    public synchronized void setAudioFormat(MediaFormat f) { pendingAudio = f; }

    public synchronized void onVideoFormat(MediaFormat f) {
        PipeTrace t = trace;
        if (started) {
            if (t != null) t.event("muxer: تغيّرت صيغة الفيديو بعد البدء (مرفوض)");
            throw new IllegalStateException("video format changed after muxer start");
        }
        videoTrack = muxer.addTrack(f);
        if (t != null) t.event("muxer: أُضيف مسار الفيديو #" + videoTrack);
        if (pendingAudio != null) {
            audioTrack = muxer.addTrack(pendingAudio);
            if (t != null) t.event("muxer: أُضيف مسار الصوت #" + audioTrack + " (" + pendingAudio.getString(MediaFormat.KEY_MIME) + ")");
        }
        muxer.start();
        started = true;
        if (t != null) t.event("muxer: بدأ");
        notifyAll();
    }

    public synchronized boolean isStarted() { return started; }
    public synchronized boolean hasAudio() { return audioTrack >= 0; }

    /** @return true if written; false if the muxer has not started or was stopped (the sample is dropped). */
    public synchronized boolean writeVideo(ByteBuffer data, MediaCodec.BufferInfo info) {
        PipeTrace t = trace;
        if (!started || stopped) {
            if (t != null) t.frame(PipeTrace.DROPPED, info.presentationTimeUs);
            return false;
        }
        muxer.writeSampleData(videoTrack, data, info);
        videoSamples++; bytes += info.size;
        lastVideoPtsUs = Math.max(lastVideoPtsUs, info.presentationTimeUs);
        if (t != null) t.frame(PipeTrace.WRITTEN, info.presentationTimeUs);
        return true;
    }

    public synchronized void writeAudio(ByteBuffer data, MediaCodec.BufferInfo info) {
        if (!started || stopped || audioTrack < 0) return;
        muxer.writeSampleData(audioTrack, data, info);
        audioSamples++; bytes += info.size;
        lastAudioPtsUs = Math.max(lastAudioPtsUs, info.presentationTimeUs);
        PipeTrace t = trace;
        if (t != null) t.frame(PipeTrace.AUDIO, info.presentationTimeUs);
    }

    /** Stops the muxer (finalises the moov box). Returns false if it never started. */
    public synchronized boolean stop() {
        if (stopped) return started;
        stopped = true;
        if (started) muxer.stop();
        PipeTrace t = trace;
        if (t != null) t.event(started ? "muxer: أُغلق (" + videoSamples + " فيديو · " + audioSamples + " صوت)" : "muxer: أُغلق دون أن يبدأ");
        return started;
    }

    public synchronized void release() {
        try { if (started && !stopped) { stopped = true; muxer.stop(); } } catch (RuntimeException ignored) { }
        try { muxer.release(); } catch (RuntimeException ignored) { }
    }

    /** Dry run: can an MP4 muxer accept this audio format without re-encoding? */
    public static boolean canPassthrough(MediaFormat audio, String tmpPath) {
        MediaMuxer m = null;
        try {
            m = new MediaMuxer(tmpPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            m.addTrack(audio);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (m != null) try { m.release(); } catch (RuntimeException ignored) { }
            new java.io.File(tmpPath).delete();
        }
    }
}
