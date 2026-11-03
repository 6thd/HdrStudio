import com.mojahed.hdrstudio.core.FrameRate;
import com.mojahed.hdrstudio.encode.Check;
import com.mojahed.hdrstudio.encode.EncodeConfig;
import com.mojahed.hdrstudio.encode.Hdr10FileCheck;
import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.HevcBitstream;
import com.mojahed.hdrstudio.encode.PathTestVerdict;
import com.mojahed.hdrstudio.encode.PixelDiagnostics;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.stream.Stream;

/**
 * Notes A, B, D of the v2.1 review, each with a regression proof: the v2.1 logic (compiled from
 * the baseline commit into its own class loader by run.sh) is run on the same inputs and shown to
 * give the wrong answer that the new code fixes.
 * usage: NotesTests <streams dir> <legacy classes dir> <android.jar> <repo root>
 */
public class NotesTests {
    static int fails = 0, missing = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }
    static void missing(String n) { System.out.println("MISSING " + n + "  v2.1 classes not available (baseline commit not in this checkout)"); missing++; }

    static ClassLoader legacy;

    static Object legacyNew(String cls) throws Exception { return Class.forName(cls, true, legacy).getDeclaredConstructor().newInstance(); }
    static void set(Object o, String f, Object v) throws Exception { Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v); }
    static Object call(Object o, String m, Class<?>[] t, Object... a) throws Exception { Method x = o.getClass().getDeclaredMethod(m, t); x.setAccessible(true); return x.invoke(o, a); }

    /** 10-bit luma codes of raw yuv420p10le frames, sampled as the app does in whole-frame mode (every 3rd pixel of every 3rd row). */
    static long[] histogram(Path raw, int w, int h, int frames) throws Exception {
        byte[] b = Files.readAllBytes(raw);
        long[] hist = new long[1024];
        int fs = w * h * 3;
        for (int f = 0; f < Math.min(frames, b.length / fs); f++)
            for (int y = 0; y < h; y += 3)
                for (int x = 0; x < w; x += 3) {
                    int o = f * fs + (y * w + x) * 2;
                    hist[Math.min(1023, (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8))]++;
                }
        return hist;
    }

    static double fracNonMul4(long[] h) { long t = 0, n = 0; for (int c = 0; c < h.length; c++) { t += h[c]; if ((c & 3) != 0) n += h[c]; } return t == 0 ? 0 : (double) n / t; }

    /** v2.1: OutputVerifier failed the file when !pd.noEightBitYcbcrStage(0.25). */
    static boolean legacyAccepts(long[] hist) throws Exception {
        Object pd = legacyNew("com.mojahed.hdrstudio.encode.StreamProbe$PixelDepth");
        set(pd, "available", true);
        set(pd, "fracNonMultipleOf4", fracNonMul4(hist));
        return (Boolean) call(pd, "noEightBitYcbcrStage", new Class<?>[]{double.class}, 0.25);
    }

    static EncodeConfig cfg(int w, int h, FrameRate f, int br) {
        return new EncodeConfig(true, "c2.test.hevc.encoder", "video/hevc", 4096, w, h, f, br, 1, 1f, 6, 6, 2);
    }

    public static void main(String[] a) throws Exception {
        String d = a[0];
        if (!a[1].equals("-"))
            legacy = new URLClassLoader(new URL[]{new File(a[1]).toURI().toURL(), new File(a[2]).toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        Path root = Paths.get(a[3]);

        // ================= A: pixel statistics are diagnostic only
        String[][] cases = {
                {"black_opener", "320", "240"}, {"flat_colours", "320", "240"}, {"colour_bars", "320", "240"}, {"fade_from_black", "320", "240"},
                {"natural", "320", "240"}, {"natural_8bit_stage", "320", "240"}};
        // null = valid content that must simply not be flagged (CONSISTENT or INCONCLUSIVE)
        PixelDiagnostics.Outcome[] want = {PixelDiagnostics.Outcome.INCONCLUSIVE, PixelDiagnostics.Outcome.INCONCLUSIVE, null, PixelDiagnostics.Outcome.INCONCLUSIVE,
                PixelDiagnostics.Outcome.CONSISTENT, PixelDiagnostics.Outcome.SUSPECT_8BIT};
        // v2.1 expectation: null = not asserted (reported only)
        Boolean[] legacyShould = {false, null, null, null, true, false};
        for (int i = 0; i < cases.length; i++) {
            long[] h = histogram(Paths.get(d, "px_" + cases[i][0] + ".yuv10"), Integer.parseInt(cases[i][1]), Integer.parseInt(cases[i][2]), 3);
            PixelDiagnostics.Result r = PixelDiagnostics.classify(h);
            boolean okNew = want[i] == null ? r.outcome != PixelDiagnostics.Outcome.SUSPECT_8BIT : r.outcome == want[i];
            check("A_" + cases[i][0] + "_new_" + (want[i] == null ? "not_flagged_" + r.outcome : want[i]), okNew,
                    String.format("distinct=%d range=%d-%d non-mult-4=%.3f", r.distinct, r.min, r.max, r.fracNonMultipleOf4));
            if (legacy == null) { missing("A_" + cases[i][0] + "_v2.1"); continue; }
            boolean old = legacyAccepts(h);
            String det = "v2.1 noEightBitYcbcrStage(0.25)=" + old + (i < 4 && !old ? " -> valid 10-bit file REJECTED by v2.1" : "");
            if (legacyShould[i] == null) System.out.println("INFO A_" + cases[i][0] + "_v2.1  " + det);
            else check("A_" + cases[i][0] + "_v2.1_" + (legacyShould[i] ? "accepted" : "rejected"), old == legacyShould[i], det);
        }
        long[] empty = new long[1024];
        check("A_no_samples_inconclusive", PixelDiagnostics.classify(empty).outcome == PixelDiagnostics.Outcome.INCONCLUSIVE, "");

        // ---- hard file checks are kept (signalling)
        HevcBitstream.Info hdr = HevcBitstream.inspect(Collections.singletonList(Files.readAllBytes(Paths.get(d, "hdr_both.hevc"))));
        HevcBitstream.Info sdr8 = HevcBitstream.inspect(Collections.singletonList(Files.readAllBytes(Paths.get(d, "sdr8.hevc"))));
        HevcBitstream.Info ten709 = HevcBitstream.inspect(Collections.singletonList(Files.readAllBytes(Paths.get(d, "ten_nosei.hevc"))));
        check("A_hard_signalling_hdr10_pass", Hdr10FileCheck.signalling(hdr).passed(), Hdr10FileCheck.signalling(hdr).detail);
        check("A_hard_signalling_8bit_fail", Hdr10FileCheck.signalling(sdr8).failed(), Hdr10FileCheck.signalling(sdr8).detail);
        check("A_hard_signalling_bt709_fail", Hdr10FileCheck.signalling(ten709).failed(), Hdr10FileCheck.signalling(ten709).detail);
        check("A_hard_signalling_missing_sps_fail", Hdr10FileCheck.signalling(new HevcBitstream.Info()).failed(), "");

        // ================= B: one encode configuration, exact identity
        EncodeConfig base = cfg(1920, 1080, FrameRate.FPS_30, 20_000_000);
        String sys = "brand/device:14/ABC/1:user/release-keys";
        String k = base.resultKey(6, sys);
        check("B_same_config_same_key", k.equals(cfg(1920, 1080, FrameRate.FPS_30, 20_000_000).resultKey(6, sys)) && base.equals(cfg(1920, 1080, FrameRate.FPS_30, 20_000_000)), k);
        check("B_bitrate_change_invalidates", !k.equals(base.withBitrate(80_000_000).resultKey(6, sys)) && !base.equals(base.withBitrate(80_000_000)), "");
        check("B_resolution_change_invalidates", !k.equals(base.withSize(1920, 1088).resultKey(6, sys)), "");
        check("B_fps_30_vs_29.97_invalidates", !k.equals(base.withFps(FrameRate.FPS_29_97).resultKey(6, sys)), "");
        check("B_fps_60_vs_59.94_distinct", !cfg(1920, 1080, FrameRate.FPS_60, 1).equals(cfg(1920, 1080, FrameRate.FPS_59_94, 1)), "");
        check("B_test_version_invalidates", !k.equals(base.resultKey(7, sys)), "");
        check("B_system_build_invalidates", !k.equals(base.resultKey(6, sys + "x")) && !k.equals(base.resultKey(6, null)), "");
        EncodeConfig cbr = new EncodeConfig(true, "c2.test.hevc.encoder", "video/hevc", 4096, 1920, 1080, FrameRate.FPS_30, 20_000_000, 2, 1f, 6, 6, 2);
        EncodeConfig gop = new EncodeConfig(true, "c2.test.hevc.encoder", "video/hevc", 4096, 1920, 1080, FrameRate.FPS_30, 20_000_000, 1, 2f, 6, 6, 2);
        EncodeConfig prof = new EncodeConfig(true, "c2.test.hevc.encoder", "video/hevc", 4096 / 2, 1920, 1080, FrameRate.FPS_30, 20_000_000, 1, 1f, 6, 6, 2);
        check("B_mode_gop_profile_invalidate", !base.equals(cbr) && !base.equals(gop) && !base.equals(prof), "");
        check("B_canonical_has_no_metadata_values", !base.canonical().contains("987") && !base.canonical().contains("1000nit"), base.canonical());
        // v2.1 matched on (hdr, encoder, w, h) with 1% fps tolerance and no bitrate
        if (legacy != null) {
            Object old = legacyNew("com.mojahed.hdrstudio.job.EncodeSelfTest$Result");
            set(old, "hdr", true); set(old, "encoder", "c2.test.hevc.encoder"); set(old, "width", 1920); set(old, "height", 1080);
            set(old, "fps", 30f); set(old, "bitrate", 20_000_000L);
            Class<?>[] mt = {boolean.class, String.class, int.class, int.class, double.class};
            boolean oldFps = (Boolean) call(old, "matches", mt, true, "c2.test.hevc.encoder", 1920, 1080, 29.97);
            boolean oldBr = (Boolean) call(old, "matches", mt, true, "c2.test.hevc.encoder", 1920, 1080, 30.0);
            check("B_v2.1_accepted_29.97_result_for_30fps", oldFps, "v2.1 matches(29.97 vs 30)=" + oldFps + " -> wrong result reused");
            check("B_v2.1_ignored_bitrate", oldBr, "v2.1 has no bitrate in matches(): a 20 Mbps test was reused for any bitrate");
        } else { missing("B_v2.1_fps_tolerance"); missing("B_v2.1_bitrate"); }
        // HDR metadata: the path test uses SAMPLE values; the final file is checked against FINAL values
        HdrMetadata sample = HdrMetadata.p3(1000); sample.setMeasured(987, 321, "sample");
        HdrMetadata finalM = HdrMetadata.p3(1000); finalM.setMeasured(1180, 402, "measured");
        HdrMetadata finalUnmeasured = HdrMetadata.p3(1000);
        HdrMetadata finalPeak = HdrMetadata.p3(4000); finalPeak.setMeasured(987, 321, "measured");
        check("B_sample_metadata_matches_itself", Hdr10FileCheck.finalMetadata(hdr, sample, null).passed(), Hdr10FileCheck.finalMetadata(hdr, sample, null).detail);
        check("B_sample_values_not_accepted_as_final_measured", Hdr10FileCheck.finalMetadata(hdr, finalM, null).failed(), Hdr10FileCheck.finalMetadata(hdr, finalM, null).detail);
        check("B_sample_values_not_accepted_as_final_unknown", Hdr10FileCheck.finalMetadata(hdr, finalUnmeasured, null).failed(), Hdr10FileCheck.finalMetadata(hdr, finalUnmeasured, null).detail);
        check("B_sample_peak_not_accepted_as_final_peak", Hdr10FileCheck.finalMetadata(hdr, finalPeak, null).failed(), Hdr10FileCheck.finalMetadata(hdr, finalPeak, null).detail);
        Hdr10FileCheck.IrapScan scan = new Hdr10FileCheck.IrapScan(sample);
        for (java.util.List<byte[]> au : StreamUtil.accessUnits(StreamUtil.nals(Files.readAllBytes(Paths.get(d, "sei_case1_both_missing.hevc"))))) scan.add(StreamUtil.lengthPrefixed(au));
        check("B_whole_file_irap_scan_pass", scan.ok() && Hdr10FileCheck.finalMetadata(hdr, sample, scan).passed(), scan.iraps + " IRAPs");
        Hdr10FileCheck.IrapScan scan2 = new Hdr10FileCheck.IrapScan(sample);
        for (java.util.List<byte[]> au : StreamUtil.accessUnits(StreamUtil.nals(Files.readAllBytes(Paths.get(d, "hdr_nosei_k.hevc"))))) scan2.add(StreamUtil.lengthPrefixed(au));
        check("B_whole_file_irap_scan_detects_missing", !scan2.ok() && Hdr10FileCheck.finalMetadata(hdr, sample, scan2).failed(), Hdr10FileCheck.finalMetadata(hdr, sample, scan2).detail);
        // ---- exact frame rates
        check("B_fps_snap_29.97", FrameRate.fromAverage(30000.0 / 1001).equals(FrameRate.FPS_29_97) && FrameRate.fromAverage(29.9700).equals(FrameRate.FPS_29_97), FrameRate.fromAverage(29.97).toString());
        check("B_fps_snap_30", FrameRate.fromAverage(30.0).equals(FrameRate.FPS_30) && FrameRate.fromAverage(29.9995).equals(FrameRate.FPS_30), "");
        check("B_fps_23.976_24_25", FrameRate.fromAverage(23.976).equals(FrameRate.FPS_23_976) && FrameRate.fromAverage(24).equals(FrameRate.FPS_24) && FrameRate.fromAverage(25).equals(FrameRate.FPS_25), "");
        check("B_fps_container_2997_125_is_ntsc_film", FrameRate.fromAverage(2997.0 / 125).equals(FrameRate.FPS_23_976), FrameRate.fromAverage(2997.0 / 125).toString());
        check("B_fps_nonstandard_kept", FrameRate.fromAverage(17.5).equals(new FrameRate(35, 2)) && !FrameRate.fromAverage(17.5).isStandard(), FrameRate.fromAverage(17.5).toString());
        long maxErr = 0;
        double acc = 0;
        FrameRate r = FrameRate.FPS_29_97;
        for (long i = 0; i < 1_000_000; i++) {
            long exact = Math.floorDiv(i * 1001L * 1_000_000L * 2 + 30000, 60000L);
            maxErr = Math.max(maxErr, Math.abs(r.ptsUs(i) - exact));
            acc += 1e6 / r.fps();
        }
        long drift = Math.abs(Math.round(acc) - r.ptsUs(1_000_000));
        check("B_pts_no_accumulated_error", maxErr == 0 && r.ptsUs(30000) == 1_001_000_000L, "frame 30000 at " + r.ptsUs(30000) + " us; float accumulation would drift " + drift + " us");

        // ================= D: verdicts per property, errors are not partial successes
        PathTestVerdict v = allPass();
        v.gradation = PathTestVerdict.gradation(PathTestVerdict.PixelProbe.MEASURED, 34, "");
        check("D_all_pass_measured", v.exportAllowed() && v.gradation.passed() && v.summary().contains("اجتاز اختبار دقة التدرج بهذه الإعدادات"), "");
        PathTestVerdict u = allPass();
        u.gradation = PathTestVerdict.gradation(PathTestVerdict.PixelProbe.UNSUPPORTED, -1, "(c2.dec)");
        check("D_no_p010_inconclusive_allowed_with_limit", u.gradation.verdict == Check.Verdict.INCONCLUSIVE && u.exportAllowed()
                && u.summary().contains("لم تُقس على هذا الجهاز"), u.gradation.line());
        for (PathTestVerdict.PixelProbe p : new PathTestVerdict.PixelProbe[]{PathTestVerdict.PixelProbe.ERROR, PathTestVerdict.PixelProbe.TIMEOUT}) {
            PathTestVerdict e = allPass();
            e.gradation = PathTestVerdict.gradation(p, -1, "");
            check("D_decoder_" + p + "_is_fail_not_partial", e.gradation.failed() && !e.exportAllowed(), e.gradation.line());
        }
        PathTestVerdict lo = allPass();
        lo.gradation = PathTestVerdict.gradation(PathTestVerdict.PixelProbe.MEASURED, 15, "");
        check("D_8bit_ramp_fail", lo.gradation.failed() && !lo.exportAllowed(), lo.gradation.line());
        PathTestVerdict bs = allPass(); bs.bitstream = Check.fail("البتستريم", "8-bit");
        PathTestVerdict mt2 = allPass(); mt2.metadataTransport = Check.fail("نقل", "x");
        PathTestVerdict dt = allPass(); dt.decoderTags = Check.fail("وسوم المفكك", "bt709");
        check("D_any_hard_fail_blocks", !bs.exportAllowed() && !mt2.exportAllowed() && !dt.exportAllowed(), "");
        PathTestVerdict sd = new PathTestVerdict(); sd.hdr = false;
        sd.colour = Check.pass("c", ""); sd.containerTags = Check.pass("t", ""); sd.decoderTags = Check.inconclusive("d", "");
        check("D_sdr_needs_decoder_evidence", !sd.exportAllowed(), "SDR/H.264 has no independent parser");
        check("D_json_has_three_way_verdicts", u.toJson().contains("\"INCONCLUSIVE\"") && u.toJson().contains("\"PASS\""), u.toJson().substring(0, Math.min(120, u.toJson().length())));
        // v2.1: a decoder error during the pixel probe left pixelMeasured=false - the same as "no P010" - and usable() said yes (PARTIAL)
        if (legacy != null) {
            Object oldR = legacyNew("com.mojahed.hdrstudio.job.EncodeSelfTest$Result");
            Class<?> ov = Class.forName("com.mojahed.hdrstudio.job.EncodeSelfTest$Verdict", true, legacy);
            set(oldR, "hdr", true); set(oldR, "colour", ov.getEnumConstants()[0]); set(oldR, "containerTagsOk", true); set(oldR, "decoderTagsOk", true);
            set(oldR, "bitstreamOk", true); set(oldR, "pixelMeasured", false); set(oldR, "pixelOk", false);
            boolean oldUsable = (Boolean) call(oldR, "usable", new Class<?>[0]);
            check("D_v2.1_decoder_error_counted_as_partial_success", oldUsable, "v2.1 usable() with an unmeasured (possibly failed) pixel probe = " + oldUsable);
        } else missing("D_v2.1_partial_success");
        // wording
        long hits;
        try (Stream<Path> s = Files.walk(root.resolve("app/src/main"))) {
            hits = s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".xml")).filter(p -> {
                try { return new String(Files.readAllBytes(p), "UTF-8").contains("مثبت بالكامل"); } catch (Exception e) { return true; }
            }).count();
        }
        check("D_no_fully_proven_wording", hits == 0, hits + " files contain the old wording");

        System.out.println(fails == 0 ? "ALL NOTES TESTS PASS" + (missing > 0 ? " (" + missing + " MISSING)" : "") : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static PathTestVerdict allPass() {
        PathTestVerdict v = new PathTestVerdict();
        v.hdr = true;
        v.colour = Check.pass("دقة الألوان", ""); v.containerTags = Check.pass("وسوم الحاوية", ""); v.decoderTags = Check.pass("وسوم المفكك", "");
        v.bitstream = Check.pass("البتستريم", ""); v.metadataTransport = Check.pass("نقل البيانات", "");
        return v;
    }
}
