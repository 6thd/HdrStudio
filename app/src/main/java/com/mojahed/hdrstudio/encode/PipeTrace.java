package com.mojahed.hdrstudio.encode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where frames go in one export: separate counters for every stage from the decoder to the file,
 * each with its first and last PTS and the wall time of its first and last frame, plus timestamped
 * events (encoder output format, muxer tracks and start, EOS sent and received, errors).
 * Thread-safe: the decoder loop, the encoder callback thread and the muxer write into it.
 * It only records; nothing here decides whether a file is accepted. Pure Java (JVM-tested).
 */
public final class PipeTrace {
    public static final String DECODED = "decoded", IN_RANGE = "in_range", PROCESSED = "processed", SENT = "sent_to_encoder",
            ENC_CONFIG = "encoder_config", ENC_OUT = "encoder_out", WRITTEN = "written", DROPPED = "dropped_muxer_not_ready",
            AUDIO = "audio_written";
    private static final String[][] LABELS = {
            {DECODED, "أُطر خرجت من المفكك"},
            {IN_RANGE, "منها داخل فترة القص"},
            {PROCESSED, "عولجت (FramePipeline)"},
            {SENT, "أُرسلت لسطح المرمّز (eglSwapBuffers)"},
            {ENC_CONFIG, "مخازن إعداد من المرمّز (SPS/PPS)"},
            {ENC_OUT, "إطارات خرجت من المرمّز"},
            {WRITTEN, "كُتبت في الملف (muxer)"},
            {DROPPED, "أُهملت لأن الـmuxer لم يبدأ أو توقف"},
            {AUDIO, "عينات صوت كُتبت"},
    };

    /** Wall clock in ms; replaceable in tests. */
    public interface Clock { long ms(); }

    public static final class Stage {
        public long count, firstPts = Long.MIN_VALUE, lastPts = Long.MIN_VALUE, minPts = Long.MAX_VALUE, maxPts = Long.MIN_VALUE;
        public long firstMs = -1, lastMs = -1;
        public int nonMonotonic;
    }

    private final Clock clock;
    private final long t0;
    private final Map<String, Stage> stages = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private static final int MAX_EVENTS = 60;
    private int droppedEvents;

    public PipeTrace() { this(new Clock() { public long ms() { return System.currentTimeMillis(); } }); }

    public PipeTrace(Clock c) {
        clock = c;
        t0 = c.ms();
        for (String[] l : LABELS) stages.put(l[0], new Stage());
    }

    public synchronized void frame(String stage, long ptsUs) {
        Stage s = stages.get(stage);
        if (s == null) { s = new Stage(); stages.put(stage, s); }
        long now = clock.ms() - t0;
        if (s.count == 0) { s.firstPts = ptsUs; s.firstMs = now; }
        else if (ptsUs <= s.lastPts) s.nonMonotonic++;
        s.count++;
        s.lastPts = ptsUs; s.lastMs = now;
        s.minPts = Math.min(s.minPts, ptsUs); s.maxPts = Math.max(s.maxPts, ptsUs);
    }

    public synchronized void event(String what) {
        if (events.size() >= MAX_EVENTS) { droppedEvents++; return; }
        events.add(String.format(Locale.US, "+%.3f ث %s", (clock.ms() - t0) / 1e3, what));
    }

    public synchronized long count(String stage) { Stage s = stages.get(stage); return s == null ? 0 : s.count; }

    /** ms since the trace started of the first frame of a stage, -1 if none. */
    public synchronized long firstMs(String stage) { Stage s = stages.get(stage); return s == null ? -1 : s.firstMs; }

    public synchronized long elapsedMs() { return clock.ms() - t0; }

    public synchronized Stage stage(String name) { return stages.get(name); }

    /**
     * No-progress rule for the encoder: frames have been sent but none came out.
     * @return a message when at least minFrames were sent, none came out, and the first was sent at least
     *         maxWaitMs ago; null otherwise
     */
    public synchronized String encoderStall(int minFrames, long maxWaitMs) {
        Stage sent = stages.get(SENT), out = stages.get(ENC_OUT);
        if (out.count > 0 || sent.count < minFrames || sent.firstMs < 0) return null;
        long waited = clock.ms() - t0 - sent.firstMs;
        if (waited < maxWaitMs) return null;
        return String.format(Locale.US, "المرمّز لم يُخرج أي إطار بعد %d إطار مُرسَل خلال %.0f ث (المهلة: %d إطار و%d ث)",
                sent.count, waited / 1e3, minFrames, maxWaitMs / 1000);
    }

    /** The first stage (in pipeline order) that has fewer frames than the one before it, or null. */
    public synchronized String firstLoss() {
        String[] chain = {IN_RANGE, PROCESSED, SENT, ENC_OUT, WRITTEN};
        for (int i = 1; i < chain.length; i++) {
            long a = stages.get(chain[i - 1]).count, b = stages.get(chain[i]).count;
            if (b < a) return String.format(Locale.US, "أول نقص: %s %d → %s %d", label(chain[i - 1]), a, label(chain[i]), b);
        }
        return null;
    }

    private static String label(String k) {
        for (String[] l : LABELS) if (l[0].equals(k)) return l[1];
        return k;
    }

    public synchronized String describe() {
        StringBuilder b = new StringBuilder("تتبع الإطارات (العدد · أول/آخر PTS بالثواني · زمن أول/آخر إطار منذ البدء):\n");
        for (Map.Entry<String, Stage> e : stages.entrySet()) {
            Stage s = e.getValue();
            b.append("  ").append(label(e.getKey())).append(": ").append(s.count);
            if (s.count > 0) {
                b.append(String.format(Locale.US, " · PTS %.3f→%.3f · عند %.1f→%.1f ث", s.firstPts / 1e6, s.lastPts / 1e6, s.firstMs / 1e3, s.lastMs / 1e3));
                if (s.nonMonotonic > 0) b.append(" · غير متزايد ").append(s.nonMonotonic).append(" مرة");
            }
            b.append('\n');
        }
        String loss = firstLoss();
        b.append("  ").append(loss == null ? "لا نقص بين المراحل" : loss).append('\n');
        b.append("الأحداث:\n");
        for (String ev : events) b.append("  ").append(ev).append('\n');
        if (droppedEvents > 0) b.append("  (").append(droppedEvents).append(" حدثاً إضافياً لم يُسجل)\n");
        return b.toString();
    }
}
