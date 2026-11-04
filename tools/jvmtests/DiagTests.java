import com.mojahed.hdrstudio.encode.Check;
import com.mojahed.hdrstudio.encode.PathTestVerdict;

/**
 * HDR path-test diagnostics ported from 2.3.2-test: a stop at VideoEncoder.configure is reported
 * as INCONCLUSIVE with that stage, never as a failed measurement, and never allows export.
 */
public class DiagTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }

    public static void main(String[] a) {
        for (boolean hdr : new boolean[]{true, false}) {
            PathTestVerdict v = new PathTestVerdict();
            v.hdr = hdr;
            v.markNotMeasured("VideoEncoder.configure", "مرحلة الفشل: VideoEncoder.configure\nMediaFormat: {...}");
            int failed = 0, inconclusive = 0;
            for (Check c : v.all()) { if (c.failed()) failed++; if (c.verdict == Check.Verdict.INCONCLUSIVE) inconclusive++; }
            String tag = hdr ? "hdr" : "sdr";
            check("configure_stop_is_inconclusive_" + tag, failed == 0 && inconclusive > 0, "failed=" + failed + " inconclusive=" + inconclusive);
            check("configure_stop_blocks_export_" + tag, !v.exportAllowed(), "exportAllowed=" + v.exportAllowed());
            check("configure_stop_names_stage_" + tag, v.colour.detail.contains("VideoEncoder.configure") && !v.colour.detail.contains("\n"), v.colour.detail);
            boolean note = false;
            for (String s : v.limitations()) note |= s.contains("VideoEncoder.configure") && s.contains("eglCreateWindowSurface");
            check("configure_stop_note_not_egl_" + tag, note, String.valueOf(v.limitations()));
        }
        PathTestVerdict e = new PathTestVerdict();
        e.hdr = true;
        e.markNotRunBecauseEgl("eglGetDisplay failed");
        boolean eglNote = false;
        for (String s : e.limitations()) eglNote |= s.contains("EGL");
        check("egl_stop_keeps_its_own_note", eglNote && !e.exportAllowed(), String.valueOf(e.limitations()));
        System.out.println("DIAG SUMMARY fails=" + fails);
        if (fails > 0) System.exit(1);
    }
}
