import com.mojahed.hdrstudio.encode.PipeTrace;

/**
 * 2.3.10: the export frame trace. Counters per stage with first/last PTS, the first stage that loses
 * frames, and the encoder no-progress rule (frames sent, none out, after a minimum count and time).
 */
public class TraceTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }

    static long now = 1000;

    public static void main(String[] a) {
        PipeTrace.Clock c = new PipeTrace.Clock() { public long ms() { return now; } };
        // the 2.3.9 phone case: 30 frames decoded, processed and sent; the encoder outputs config only; audio written
        PipeTrace t = new PipeTrace(c);
        for (int i = 0; i < 30; i++) {
            long pts = 4_021_000 + i * 33_461L;
            t.frame(PipeTrace.DECODED, pts); t.frame(PipeTrace.IN_RANGE, pts); t.frame(PipeTrace.PROCESSED, pts);
            now += 9_700;
            t.frame(PipeTrace.SENT, pts - 4_021_000);
        }
        t.frame(PipeTrace.ENC_CONFIG, 0);
        for (int i = 0; i < 47; i++) t.frame(PipeTrace.AUDIO, i * 21_333L);
        String loss = t.firstLoss();
        check("loss_located_at_encoder", loss != null && loss.contains("أُرسلت لسطح المرمّز") && loss.contains(" 30 ") && loss.endsWith("0"), loss);
        check("counts", t.count(PipeTrace.SENT) == 30 && t.count(PipeTrace.ENC_OUT) == 0 && t.count(PipeTrace.AUDIO) == 47, "");
        PipeTrace.Stage s = t.stage(PipeTrace.SENT);
        check("first_last_pts", s.firstPts == 0 && s.lastPts == 29 * 33_461L && s.nonMonotonic == 0, s.firstPts + "→" + s.lastPts);
        String stall = t.encoderStall(16, 60_000);
        check("stall_detected", stall != null && stall.contains("30 إطار"), String.valueOf(stall));

        // the no-progress rule must not fire before both limits, nor once a frame came out
        now = 0;
        PipeTrace u = new PipeTrace(c);
        for (int i = 0; i < 15; i++) { u.frame(PipeTrace.SENT, i); now += 10_000; }
        check("stall_needs_min_frames", u.encoderStall(16, 60_000) == null, "15 frames, 150 s");
        now = 0;
        PipeTrace v = new PipeTrace(c);
        for (int i = 0; i < 20; i++) { v.frame(PipeTrace.SENT, i); now += 1_000; }
        check("stall_needs_min_time", v.encoderStall(16, 60_000) == null, "20 frames, 20 s");
        now += 60_000;
        check("stall_after_time", v.encoderStall(16, 60_000) != null, "20 frames, 80 s");
        v.frame(PipeTrace.ENC_OUT, 0);
        check("no_stall_once_output", v.encoderStall(16, 60_000) == null, "");

        // a complete run: no loss reported
        PipeTrace w = new PipeTrace(c);
        for (int i = 0; i < 5; i++)
            for (String st : new String[]{PipeTrace.DECODED, PipeTrace.IN_RANGE, PipeTrace.PROCESSED, PipeTrace.SENT, PipeTrace.ENC_OUT, PipeTrace.WRITTEN}) w.frame(st, i);
        check("no_loss_complete_run", w.firstLoss() == null && w.describe().contains("لا نقص بين المراحل"), "");
        // non-monotonic PTS is counted
        w.frame(PipeTrace.WRITTEN, 2);
        check("non_monotonic_counted", w.stage(PipeTrace.WRITTEN).nonMonotonic == 1, "");
        // events are capped and the overflow is reported
        for (int i = 0; i < 70; i++) w.event("e" + i);
        String d = w.describe();
        check("events_capped", d.contains("e59") && !d.contains("e60") && d.contains("10 حدثاً إضافياً"), "");
        check("describe_has_all_stages", d.contains("أُطر خرجت من المفكك") && d.contains("كُتبت في الملف") && d.contains("أُهملت لأن الـmuxer"), "");
        System.exit(fails > 0 ? 1 : 0);
    }
}
