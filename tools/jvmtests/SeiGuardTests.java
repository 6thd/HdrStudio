import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.encode.HdrSeiGuard;
import com.mojahed.hdrstudio.encode.HevcBitstream;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Note C: SEI 137 (mastering display) and 144 (content light level) handled independently.
 * Streams come from x265 (run.sh); single messages are stripped or altered here.
 * Both framings (Annex-B as MediaCodec emits, 4-byte length prefixes as in MP4) are run.
 * Decoding and an independent ffprobe read of the outputs follow in run.sh.
 */
public class SeiGuardTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }

    static HdrMetadata meta() { HdrMetadata m = HdrMetadata.p3(1000); m.setMeasured(987, 321, "test"); return m; }

    static final class Run {
        final List<List<byte[]>> out = new ArrayList<>();
        HdrSeiGuard g; String error; int unchanged;
    }

    static Run run(List<List<byte[]>> aus, boolean lp, HdrMetadata m) {
        Run r = new Run();
        r.g = new HdrSeiGuard(m);
        try {
            for (List<byte[]> au : aus) {
                byte[] framed = lp ? StreamUtil.lengthPrefixed(au) : StreamUtil.annexB(au);
                byte[] o = r.g.onAccessUnit(framed);
                if (o == framed) r.unchanged++;
                r.out.add(StreamUtil.unframe(o));
            }
        } catch (HdrSeiGuard.Conflict c) { r.error = c.getMessage(); }
        return r;
    }

    static boolean sameAus(List<List<byte[]>> a, List<List<byte[]>> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).size() != b.get(i).size()) return false;
            for (int k = 0; k < a.get(i).size(); k++) if (!Arrays.equals(a.get(i).get(k), b.get(i).get(k))) return false;
        }
        return true;
    }

    static int iraps(List<List<byte[]>> aus) { int n = 0; for (List<byte[]> au : aus) if (StreamUtil.irap(au)) n++; return n; }

    /** Every IRAP carries exactly one 137 and one 144 equal to m; the inserted SEI sits right before the first VCL; non-IRAP AUs are untouched. */
    static String layoutProblem(List<List<byte[]>> in, List<List<byte[]>> out, HdrMetadata m) {
        byte[] wm = m.mdcvPayload(), wc = m.cllPayload();
        for (int i = 0; i < out.size(); i++) {
            List<byte[]> au = out.get(i);
            if (!StreamUtil.irap(au)) {
                if (!sameAus(Collections.singletonList(in.get(i)), Collections.singletonList(au))) return "non-IRAP AU " + i + " modified";
                continue;
            }
            if (StreamUtil.count(au, 137) != 1 || StreamUtil.count(au, 144) != 1)
                return "AU " + i + ": 137x" + StreamUtil.count(au, 137) + " 144x" + StreamUtil.count(au, 144);
            int v = StreamUtil.firstVclIndex(au);
            for (int k = 0; k < au.size(); k++) {
                if (StreamUtil.type(au.get(k)) != 39) continue;
                for (HevcBitstream.SeiMessage s : StreamUtil.sei(au.get(k))) {
                    if (s.type == 137 && !Arrays.equals(s.payload, wm)) return "AU " + i + ": 137 value";
                    if (s.type == 144 && !Arrays.equals(s.payload, wc)) return "AU " + i + ": 144 value";
                    if ((s.type == 137 || s.type == 144) && k > v) return "AU " + i + ": SEI after first VCL";
                }
            }
            if (au.size() != in.get(i).size() && StreamUtil.type(au.get(v - 1)) != 39) return "AU " + i + ": inserted SEI not directly before first VCL";
            if (in.get(i).size() > 0 && StreamUtil.type(in.get(i).get(0)) == 35 && StreamUtil.type(au.get(0)) != 35) return "AU " + i + ": AUD no longer first";
        }
        return null;
    }

    static List<byte[]> withExtraSei(List<byte[]> au, int type, byte[] payload) {
        List<byte[]> nu = new ArrayList<>(au);
        nu.add(StreamUtil.firstVclIndex(au), HevcBitstream.prefixSeiNal(Collections.singletonList(new HevcBitstream.SeiMessage(type, payload))));
        return nu;
    }

    static List<List<byte[]>> replaceFirstIrap(List<List<byte[]>> aus, List<byte[]> au) {
        List<List<byte[]>> o = new ArrayList<>(aus);
        for (int i = 0; i < o.size(); i++) if (StreamUtil.irap(o.get(i))) { o.set(i, au); break; }
        return o;
    }

    static List<byte[]> firstIrap(List<List<byte[]>> aus) { for (List<byte[]> au : aus) if (StreamUtil.irap(au)) return au; return null; }

    public static void main(String[] a) throws Exception {
        String d = a[0];
        HdrMetadata m = meta();
        List<List<byte[]>> noSei = StreamUtil.accessUnits(StreamUtil.nals(Files.readAllBytes(Paths.get(d + "/hdr_nosei_k.hevc"))));
        List<List<byte[]>> both = StreamUtil.accessUnits(StreamUtil.nals(Files.readAllBytes(Paths.get(d + "/hdr_both.hevc"))));
        List<List<byte[]>> conflict = StreamUtil.accessUnits(StreamUtil.nals(Files.readAllBytes(Paths.get(d + "/hdr_conflict.hevc"))));
        int ir = iraps(noSei);
        check("precondition_streams", ir >= 3 && iraps(both) == ir && noSei.size() == 12 && both.size() == 12
                        && StreamUtil.count(firstIrap(noSei), 137) == 0 && StreamUtil.count(firstIrap(both), 137) == 1 && StreamUtil.count(firstIrap(both), 144) == 1,
                "AUs " + noSei.size() + "/" + both.size() + " · IRAPs " + ir + "/" + iraps(both));
        int bothIrapsWithSei = 0;
        for (List<byte[]> au : both) if (StreamUtil.irap(au) && StreamUtil.count(au, 137) == 1) bothIrapsWithSei++;
        System.out.println("INFO x265 wrote SEI 137/144 on " + bothIrapsWithSei + " of " + ir + " IRAPs");

        List<List<byte[]>> mdcvOnly = StreamUtil.filterSei(both, s -> s.type != 144);
        List<List<byte[]>> cllOnly = StreamUtil.filterSei(both, s -> s.type != 137);
        List<List<byte[]>> annexOut1 = null, annexOut2 = null, annexOut3 = null;
        for (boolean lp : new boolean[]{false, true}) {
            String f = lp ? "_lengthprefixed" : "_annexb";
            Run r1 = run(noSei, lp, m);
            String p1 = r1.error != null ? r1.error : layoutProblem(noSei, r1.out, m);
            check("c1_both_missing_inserted" + f, p1 == null && r1.g.insertedMdcv == ir && r1.g.insertedCll == ir && r1.g.irapUnits == ir,
                    p1 != null ? p1 : r1.g.describe());
            Run r2 = run(mdcvOnly, lp, m);
            String p2 = r2.error != null ? r2.error : layoutProblem(mdcvOnly, r2.out, m);
            check("c2_mdcv_only_inserts_only_cll" + f, p2 == null && r2.g.insertedMdcv == ir - bothIrapsWithSei && r2.g.insertedCll == ir,
                    p2 != null ? p2 : r2.g.describe());
            Run r3 = run(cllOnly, lp, m);
            String p3 = r3.error != null ? r3.error : layoutProblem(cllOnly, r3.out, m);
            check("c3_cll_only_inserts_only_mdcv" + f, p3 == null && r3.g.insertedCll == ir - bothIrapsWithSei && r3.g.insertedMdcv == ir,
                    p3 != null ? p3 : r3.g.describe());
            Run r4 = run(both, lp, m);
            boolean allPresent = bothIrapsWithSei == ir;
            check("c4_both_present_correct_unchanged" + f, r4.error == null && layoutProblem(both, r4.out, m) == null
                            && (!allPresent || (r4.unchanged == both.size() && r4.g.insertedMdcv == 0 && r4.g.insertedCll == 0)),
                    r4.error != null ? r4.error : r4.g.describe() + " · unchanged " + r4.unchanged + "/" + both.size());
            if (!lp) { annexOut1 = r1.out; annexOut2 = r2.out; annexOut3 = r3.out; }
            else check("framing_independent_result", sameAus(annexOut1, r1.out) && sameAus(annexOut2, r2.out) && sameAus(annexOut3, r3.out),
                    "Annex-B and length-prefixed give identical NAL sequences");

            // ---- 5. conflicts and invalid messages
            Run r5a = run(conflict, lp, m);
            check("c5a_encoder_cll_conflicts_rejected" + f, r5a.error != null && r5a.error.contains("144"), String.valueOf(r5a.error));
            byte[] otherCll = {0x01, (byte) 0xF4, 0x00, 0x64};   // 500/100
            Run r5b = run(replaceFirstIrap(both, withExtraSei(firstIrap(both), 144, otherCll)), lp, m);
            check("c5b_duplicate_conflicting_rejected" + f, r5b.error != null && r5b.error.contains("144"), String.valueOf(r5b.error));
            Run r5c = run(replaceFirstIrap(both, withExtraSei(firstIrap(both), 144, m.cllPayload())), lp, m);
            check("c5c_duplicate_identical_accepted_not_added" + f, r5c.error == null && StreamUtil.count(r5c.out.get(0), 144) == 2 && StreamUtil.count(r5c.out.get(0), 137) == 1,
                    r5c.error != null ? r5c.error : "first AU 144x" + StreamUtil.count(r5c.out.get(0), 144));
            Run r5d = run(replaceFirstIrap(cllOnly, withExtraSei(firstIrap(cllOnly), 137, Arrays.copyOf(m.mdcvPayload(), 23))), lp, m);
            check("c5d_malformed_size_rejected" + f, r5d.error != null && r5d.error.contains("137"), String.valueOf(r5d.error));
            byte[] badLum = m.mdcvPayload().clone();
            System.arraycopy(new byte[]{0, 0, 0, 10}, 0, badLum, 16, 4);    // max 0.001 nit < min
            Run r5e = run(replaceFirstIrap(cllOnly, withExtraSei(firstIrap(cllOnly), 137, badLum)), lp, m);
            check("c5e_invalid_values_rejected" + f, r5e.error != null && r5e.error.contains("137"), String.valueOf(r5e.error));
            List<byte[]> nonIrap = null;
            for (List<byte[]> au : both) if (!StreamUtil.irap(au)) { nonIrap = au; break; }
            List<List<byte[]>> withNonIrapConflict = new ArrayList<>(both);
            withNonIrapConflict.set(both.indexOf(nonIrap), withExtraSei(nonIrap, 144, otherCll));
            Run r5f = run(withNonIrapConflict, lp, m);
            check("c5f_conflict_in_non_irap_rejected" + f, r5f.error != null, String.valueOf(r5f.error));
        }
        // codec-specific data
        List<byte[]> params = new ArrayList<>();
        for (byte[] n : firstIrap(noSei)) if (StreamUtil.type(n) >= 32 && StreamUtil.type(n) <= 34) params.add(n);
        HdrSeiGuard g = new HdrSeiGuard(m);
        List<byte[]> csdBad = new ArrayList<>(params);
        byte[] otherM = m.mdcvPayload().clone(); otherM[16] = 0x01;          // different max luminance
        csdBad.add(HevcBitstream.prefixSeiNal(Collections.singletonList(new HevcBitstream.SeiMessage(137, otherM))));
        String e1 = null;
        try { g.onCodecConfig(StreamUtil.annexB(csdBad)); } catch (HdrSeiGuard.Conflict c) { e1 = c.getMessage(); }
        check("c5g_codec_config_conflict_rejected", e1 != null && e1.contains("137"), String.valueOf(e1));
        List<byte[]> csdOk = new ArrayList<>(params);
        csdOk.add(HevcBitstream.prefixSeiNal(Arrays.asList(new HevcBitstream.SeiMessage(137, m.mdcvPayload()), new HevcBitstream.SeiMessage(144, m.cllPayload()))));
        HdrSeiGuard g2 = new HdrSeiGuard(m);
        String e2 = null;
        try { g2.onCodecConfig(StreamUtil.lengthPrefixed(csdOk)); } catch (HdrSeiGuard.Conflict c) { e2 = c.getMessage(); }
        byte[] firstOut = g2.onAccessUnit(StreamUtil.annexB(firstIrap(noSei)));
        check("csd_metadata_validated_but_still_inserted_in_irap", e2 == null && g2.csdMdcv && g2.csdCll
                && StreamUtil.count(StreamUtil.unframe(firstOut), 137) == 1 && StreamUtil.count(StreamUtil.unframe(firstOut), 144) == 1, g2.describe());
        String e3 = null;
        try { new HdrSeiGuard(m.mdcvPayload(), new byte[]{0, 100, 1, 0}); } catch (IllegalArgumentException x) { e3 = x.getMessage(); }
        check("c5h_invalid_configured_metadata_rejected", e3 != null, String.valueOf(e3));
        String e4 = null;
        try { new HdrSeiGuard(m).onAccessUnit(new byte[]{1, 2, 3, 4, 5, 6, 7}); } catch (HdrSeiGuard.Conflict x) { e4 = x.getMessage(); }
        check("c5i_unparsable_access_unit_rejected", e4 != null, String.valueOf(e4));
        // emulation prevention inside an inserted payload (min luminance 0.0001 nit -> 00 00 00 01)
        HdrMetadata em = meta(); em.masterMinNits = 0.0001f;
        Run re = run(noSei, false, em);
        byte[] seiNal = null;
        for (byte[] n : re.out.get(0)) if (StreamUtil.type(n) == 39) seiNal = n;
        boolean escaped = false;
        for (int i = 2; seiNal != null && i < seiNal.length; i++) if (seiNal[i - 2] == 0 && seiNal[i - 1] == 0 && seiNal[i] == 3) escaped = true;
        HevcBitstream.Info ei = HevcBitstream.inspect(Collections.singletonList(StreamUtil.annexB(re.out.get(0))));
        check("inserted_payload_emulation_prevention", re.error == null && escaped && ei.mdcvMinLum == 1 && ei.mdcvMaxLum == 10000000L && ei.maxCll == 987,
                "min=" + ei.mdcvMinLum + " escaped=" + escaped);

        // ---- outputs for the decode + ffprobe checks in run.sh
        String[] names = {"sei_case1_both_missing", "sei_case2_mdcv_only", "sei_case3_cll_only"};
        List<List<List<byte[]>>> outs = Arrays.asList(annexOut1, annexOut2, annexOut3);
        for (int i = 0; i < 3; i++) {
            List<byte[]> all = new ArrayList<>();
            for (List<byte[]> au : outs.get(i)) all.addAll(au);
            Files.write(Paths.get(d + "/" + names[i] + ".hevc"), StreamUtil.annexB(all));
        }
        System.out.println(fails == 0 ? "ALL SEI GUARD TESTS PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
