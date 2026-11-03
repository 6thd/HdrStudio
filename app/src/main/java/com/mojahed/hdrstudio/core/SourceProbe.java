package com.mojahed.hdrstudio.core;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the container/bitstream metadata and walks all video sample timestamps (no decoding)
 * to get the exact frame count, the real average frame rate (29.97 stays 29.97) and VFR.
 * Missing colour information is replaced by declared, documented defaults.
 */
public final class SourceProbe {
    private SourceProbe() {}

    public static SourceInfo probe(Context ctx, Uri uri) throws IOException {
        SourceInfo s = new SourceInfo();
        s.displayName = "video";
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) s.displayName = c.getString(0);
                if (!c.isNull(1)) s.fileBytes = c.getLong(1);
            }
        } catch (RuntimeException ignored) { }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (s.videoTrack < 0 && mime.startsWith("video/")) { s.videoTrack = i; readVideo(s, f, mime); }
                else if (s.audioTrack < 0 && mime.startsWith("audio/")) {
                    s.audioTrack = i; s.audioMime = mime;
                    s.audioChannels = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                    s.audioSampleRate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                    s.audioDurationUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
                }
            }
            if (s.videoTrack < 0) throw new IOException("لا يوجد مسار فيديو في الملف");
            walkTimestamps(ex, s);
        } finally {
            ex.release();
        }
        return s;
    }

    private static void readVideo(SourceInfo s, MediaFormat f, String mime) {
        s.mime = mime;
        s.dolbyVision = mime.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION);
        s.width = f.getInteger(MediaFormat.KEY_WIDTH);
        s.height = f.getInteger(MediaFormat.KEY_HEIGHT);
        s.rotation = f.containsKey(MediaFormat.KEY_ROTATION) ? f.getInteger(MediaFormat.KEY_ROTATION) : 0;
        if (f.containsKey("sar-width") && f.containsKey("sar-height")) {
            s.parW = Math.max(1, f.getInteger("sar-width")); s.parH = Math.max(1, f.getInteger("sar-height"));
        }
        s.durationUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try { s.nominalFps = f.getInteger(MediaFormat.KEY_FRAME_RATE); }
            catch (ClassCastException e) { s.nominalFps = f.getFloat(MediaFormat.KEY_FRAME_RATE); }
        }
        s.profile = f.containsKey(MediaFormat.KEY_PROFILE) ? f.getInteger(MediaFormat.KEY_PROFILE) : -1;
        s.bitDepth = guessBitDepth(mime, s.profile);

        boolean big = s.width * s.height >= 1280 * 720 - 1;
        if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) s.colorStandard = f.getInteger(MediaFormat.KEY_COLOR_STANDARD);
        else { s.colorStandard = big ? MediaFormat.COLOR_STANDARD_BT709 : MediaFormat.COLOR_STANDARD_BT601_NTSC; s.standardAssumed = true; }
        if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) s.colorTransfer = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
        else { s.colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO; s.transferAssumed = true; }
        if (f.containsKey(MediaFormat.KEY_COLOR_RANGE)) s.colorRange = f.getInteger(MediaFormat.KEY_COLOR_RANGE);
        else { s.colorRange = MediaFormat.COLOR_RANGE_LIMITED; s.rangeAssumed = true; }
        if (s.isHdr() && s.bitDepth < 10) s.bitDepth = 10;
        if (f.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
            ByteBuffer b = f.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO);
            if (b != null && b.remaining() >= 25) {
                s.hdrStaticInfo = b.duplicate();
                ByteBuffer l = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                l.position(l.position() + 17);
                s.srcMasteringPeak = l.getShort() & 0xFFFF;
                l.getShort();
                s.srcMaxCll = l.getShort() & 0xFFFF;
                s.srcMaxFall = l.getShort() & 0xFFFF;
            }
        }
    }

    static int guessBitDepth(String mime, int profile) {
        if (profile < 0) return 8;
        switch (mime) {
            case MediaFormat.MIMETYPE_VIDEO_HEVC:
                return (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                        || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) ? 10 : 8;
            case MediaFormat.MIMETYPE_VIDEO_AV1:
                return (profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                        || profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                        || profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus) ? 10 : 8;
            case MediaFormat.MIMETYPE_VIDEO_VP9:
                return profile >= MediaCodecInfo.CodecProfileLevel.VP9Profile2 ? 10 : 8;
            case MediaFormat.MIMETYPE_VIDEO_AVC:
                return profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 ? 10 : 8;
            default:
                return 8;
        }
    }

    private static void walkTimestamps(MediaExtractor ex, SourceInfo s) {
        ex.selectTrack(s.videoTrack);
        int n = 0;
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        // collect pts (decode order) -> sort to presentation order for interval stats
        long[] pts = new long[1024];
        while (true) {
            long t = ex.getSampleTime();
            if (t < 0) break;
            if (n == pts.length) pts = java.util.Arrays.copyOf(pts, n * 2);
            pts[n++] = t;
            first = Math.min(first, t); last = Math.max(last, t);
            if (!ex.advance()) break;
        }
        s.frameCount = n;
        s.firstPtsUs = n > 0 ? first : 0;
        s.lastPtsUs = n > 0 ? last : 0;
        if (n >= 2) {
            long[] p = java.util.Arrays.copyOf(pts, n);
            java.util.Arrays.sort(p);
            double mn = Double.MAX_VALUE, mx = 0, sum = 0, sum2 = 0;
            for (int i = 1; i < n; i++) {
                double d = (p[i] - p[i - 1]) / 1000.0;
                mn = Math.min(mn, d); mx = Math.max(mx, d); sum += d; sum2 += d * d;
            }
            double mean = sum / (n - 1);
            double sd = Math.sqrt(Math.max(0, sum2 / (n - 1) - mean * mean));
            s.avgFps = 1000.0 / mean;           // exact average (e.g. 29.970)
            s.minFrameMs = mn; s.maxFrameMs = mx;
            s.vfr = sd / mean > 0.05;
            if (s.durationUs <= 0) s.durationUs = (long) ((last - first) + mean * 1000);
        } else {
            s.avgFps = s.nominalFps > 0 ? s.nominalFps : 30;
        }
        ex.unselectTrack(s.videoTrack);
    }
}
