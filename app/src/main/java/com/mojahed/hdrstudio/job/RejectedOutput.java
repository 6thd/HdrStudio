package com.mojahed.hdrstudio.job;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * A rejected export is not published, but it is kept for inspection inside the app's own storage
 * (Android/data/<package>/files/rejected/, not the gallery), with the run's report next to it and a
 * structural description of what the file actually contains (tracks, samples, PTS). Only the last
 * KEEP files are kept; a file larger than MAX_BYTES is described but not copied.
 */
final class RejectedOutput {
    private RejectedOutput() {}

    static final int KEEP = 3;
    static final long MAX_BYTES = 200L << 20;

    /** @return report lines (where it was kept, what the file contains); never throws */
    static String keep(Context ctx, Uri pending, File cacheFile, String name, String report) {
        StringBuilder r = new StringBuilder();
        try {
            File dir = ctx.getExternalFilesDir("rejected");
            if (dir == null) dir = new File(ctx.getFilesDir(), "rejected");
            if (!dir.isDirectory() && !dir.mkdirs()) return "تعذر إنشاء مجلد الملفات المرفوضة\n";
            if (name == null) name = "HDRStudio_rejected_" + System.currentTimeMillis() + ".mp4";
            long size = cacheFile != null ? cacheFile.length() : size(ctx, pending);
            File out = new File(dir, name);
            boolean copied = false;
            if (size >= 0 && size <= MAX_BYTES) {
                try (InputStream in = cacheFile != null ? new java.io.FileInputStream(cacheFile) : ctx.getContentResolver().openInputStream(pending);
                     OutputStream os = new FileOutputStream(out)) {
                    if (in == null) throw new java.io.IOException("openInputStream null");
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
                }
                copied = true;
            }
            String structure = copied ? describe(out.getAbsolutePath(), null, null) : describe(null, ctx, cacheFile != null ? Uri.fromFile(cacheFile) : pending);
            if (copied) r.append(String.format(Locale.US, "الملف المرفوض محفوظ للفحص داخل مساحة التطبيق (لا يظهر في المعرض): %s (%.2f MB)\n",
                    out.getAbsolutePath(), size / 1e6));
            else r.append(String.format(Locale.US, "الملف المرفوض أكبر من %d MB (%.1f MB) فلم يُنسخ؛ وصف بنيته فقط:\n", MAX_BYTES >> 20, size / 1e6));
            r.append(structure);
            try (OutputStream os = new FileOutputStream(new File(dir, name + ".txt"))) {
                os.write(((report == null ? "" : report) + "\n\n" + r).getBytes(StandardCharsets.UTF_8));
            }
            prune(dir);
        } catch (Throwable t) {
            r.append("تعذر حفظ الملف المرفوض: ").append(t).append('\n');
        }
        return r.toString();
    }

    private static long size(Context ctx, Uri u) {
        try (ParcelFileDescriptor p = ctx.getContentResolver().openFileDescriptor(u, "r")) {
            return p == null ? -1 : p.getStatSize();
        } catch (Exception e) { return -1; }
    }

    private static void prune(File dir) {
        File[] mp4 = dir.listFiles(new java.io.FileFilter() { public boolean accept(File f) { return f.getName().endsWith(".mp4"); } });
        if (mp4 == null || mp4.length <= KEEP) return;
        Arrays.sort(mp4, new Comparator<File>() { public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); } });
        for (int i = KEEP; i < mp4.length; i++) {
            mp4[i].delete();
            new File(dir, mp4[i].getName() + ".txt").delete();
        }
    }

    /** Tracks, sample counts, PTS range and bytes of a file, as the platform extractor sees it. */
    static String describe(String path, Context ctx, Uri uri) {
        StringBuilder b = new StringBuilder("بنية الملف المرفوض كما يراها MediaExtractor:\n");
        MediaExtractor ex = new MediaExtractor();
        try {
            if (path != null) ex.setDataSource(path); else ex.setDataSource(ctx, uri, null);
            int n = ex.getTrackCount();
            b.append("  عدد المسارات: ").append(n).append('\n');
            ByteBuffer buf = ByteBuffer.allocate(8 << 20);
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                b.append("  مسار ").append(i).append(": ").append(f.getString(MediaFormat.KEY_MIME));
                if (f.containsKey(MediaFormat.KEY_DURATION)) b.append(String.format(Locale.US, " · مدة معلنة %.3f ث", f.getLong(MediaFormat.KEY_DURATION) / 1e6));
                ex.selectTrack(i);
                ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long count = 0, sync = 0, bytes = 0, first = Long.MIN_VALUE, last = Long.MIN_VALUE;
                while (ex.getSampleTime() >= 0) {
                    long t = ex.getSampleTime();
                    if (first == Long.MIN_VALUE) first = t;
                    last = t;
                    long sz = ex.getSampleSize();
                    if (sz < 0) { buf.clear(); sz = ex.readSampleData(buf, 0); }
                    bytes += Math.max(0, sz);
                    if ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) sync++;
                    count++;
                    if (!ex.advance()) break;
                }
                ex.unselectTrack(i);
                b.append(String.format(Locale.US, " · %d عينة (%d مزامنة) · %.2f MB", count, sync, bytes / 1e6));
                if (count > 0) b.append(String.format(Locale.US, " · PTS %.3f→%.3f ث", first / 1e6, last / 1e6));
                b.append('\n');
            }
        } catch (Throwable t) {
            b.append("  تعذر فتح الملف: ").append(t).append('\n');
        } finally {
            ex.release();
        }
        return b.toString();
    }
}
