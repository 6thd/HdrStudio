import com.mojahed.hdrstudio.encode.HevcBitstream;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

/** Checks the independent HEVC parser against x265 streams with known properties (made by run.sh). */
public class BitstreamTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }
    static HevcBitstream.Info load(String p) throws Exception {
        return HevcBitstream.inspect(Collections.singletonList(Files.readAllBytes(Paths.get(p))));
    }
    public static void main(String[] a) throws Exception {
        String d = a[0];
        HevcBitstream.Info h = load(d + "/hdr10.hevc");
        check("hdr10_stream_detected", h.isHdr10Signal() && h.mdcvFound && h.cllFound && h.maxCll == Integer.parseInt(a[1]) && h.mdcvMaxLum == 10000000L,
                h.describe());
        HevcBitstream.Info f = load(d + "/fhd_lists.hevc");
        check("hdr10_fhd_scaling_lists_bframes", f.isHdr10Signal() && f.maxCll == 1000 && f.maxFall == 400 && f.width == 1920
                && Arrays.equals(f.mdcvPrimaries, new int[]{13250, 34500, 7500, 3000, 34000, 16000}), f.describe());
        HevcBitstream.Info s = load(d + "/sdr8.hevc");
        check("sdr_8bit_not_hdr", !s.isHdr10Signal() && s.bitDepthLuma == 8 && !s.mdcvFound, s.describe());
        HevcBitstream.Info t = load(d + "/ten_nosei.hevc");
        check("10bit_bt709_without_sei_not_hdr10", !t.isHdr10Signal() && t.bitDepthLuma == 10 && t.transfer == 1 && !t.cllFound, t.describe());
        // the same HDR10 stream converted to 4-byte length-prefixed framing (as in MP4 samples)
        byte[] annexB = Files.readAllBytes(Paths.get(d + "/hdr10.hevc"));
        java.io.ByteArrayOutputStream lp = new java.io.ByteArrayOutputStream();
        int i = 0, start = -1;
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        while (i + 2 < annexB.length) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) { if (start >= 0) spans.add(new int[]{start, i}); i += 3; start = i; } else i++;
        }
        spans.add(new int[]{start, annexB.length});
        for (int[] sp : spans) {
            int e = sp[1]; while (e > sp[0] && annexB[e - 1] == 0) e--;
            int n = e - sp[0];
            lp.write(n >>> 24); lp.write(n >>> 16); lp.write(n >>> 8); lp.write(n);
            lp.write(annexB, sp[0], n);
        }
        HevcBitstream.Info l = HevcBitstream.inspect(Collections.singletonList(lp.toByteArray()));
        check("length_prefixed_framing", l.isHdr10Signal() && l.cllFound, l.describe());

        // ---- SEI injection: 10-bit BT.2020/PQ stream WITHOUT SEI -> app inserts SEI -> parse back
        byte[] noSei = Files.readAllBytes(Paths.get(d + "/hdr_nosei.hevc"));
        HevcBitstream.Info before = HevcBitstream.inspect(Collections.singletonList(noSei));
        com.mojahed.hdrstudio.encode.HdrMetadata meta = com.mojahed.hdrstudio.encode.HdrMetadata.p3(1000);
        meta.setMeasured(987, 321, "test");
        com.mojahed.hdrstudio.encode.HdrSeiGuard guard = new com.mojahed.hdrstudio.encode.HdrSeiGuard(meta);
        java.util.List<byte[]> outNals = new java.util.ArrayList<>();
        int perIrapOk = 0, irapCount = 0;
        for (java.util.List<byte[]> au : StreamUtil.accessUnits(StreamUtil.nals(noSei))) {
            java.util.List<byte[]> o = StreamUtil.unframe(guard.onAccessUnit(StreamUtil.annexB(au)));
            if (StreamUtil.irap(o)) { irapCount++; if (StreamUtil.count(o, 137) == 1 && StreamUtil.count(o, 144) == 1) perIrapOk++; }
            outNals.addAll(o);
        }
        byte[] withSei = StreamUtil.annexB(outNals);
        HevcBitstream.Info after = HevcBitstream.inspect(Collections.singletonList(withSei));
        check("sei_injection_parsed_back", !before.mdcvFound && after.isHdr10Signal() && after.mdcvFound && after.cllFound
                && after.maxCll == 987 && after.maxFall == 321 && after.mdcvMaxLum == 10000000L && after.mdcvMinLum == 50
                && Arrays.equals(after.mdcvPrimaries, new int[]{13250, 34500, 7500, 3000, 34000, 16000}) && after.mdcvWhiteX == 15635 && after.mdcvWhiteY == 16450,
                after.describe());
        check("sei_injection_once_per_irap", irapCount > 0 && perIrapOk == irapCount, perIrapOk + "/" + irapCount + " IRAPs carry exactly one 137 and one 144");
        Files.write(Paths.get(d + "/hdr_injected.hevc"), withSei);
        // emulation prevention round trip on adversarial payloads
        java.util.Random rnd = new java.util.Random(3);
        boolean rt = true;
        for (int k = 0; k < 2000 && rt; k++) {
            byte[] x = new byte[1 + rnd.nextInt(40)];
            for (int q = 0; q < x.length; q++) x[q] = (byte) (rnd.nextInt(4) == 0 ? rnd.nextInt(4) : (rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(256)));
            byte[] e = HevcBitstream.escape(x);
            for (int q = 2; q < e.length; q++) if (e[q - 2] == 0 && e[q - 1] == 0 && (e[q] & 0xFF) < 3) rt = false;
            byte[] withHdr = new byte[e.length + 2]; System.arraycopy(e, 0, withHdr, 2, e.length);
            if (!Arrays.equals(HevcBitstream.unescape(withHdr, 2), x)) rt = false;
        }
        check("emulation_prevention_roundtrip", rt, "2000 random payloads");
        // a length-prefixed buffer whose first length is 0x00000100 (looks like a start code)
        byte[] fake = new byte[4 + 256];
        fake[2] = 1; fake[4] = 0x02; fake[5] = 0x01;           // NAL len 256, TRAIL_R header
        check("length_prefix_not_mistaken_for_start_code", HevcBitstream.isLengthPrefixed(fake), "");

        // ---- 10-bit precision measure on x265-decoded self-test ramps (made by make_ramp_streams.py)
        for (String nm : new String[]{"10bit_31M", "10bit_7M", "8bit_31M", "8bit_7M"}) {
            byte[] raw = Files.readAllBytes(Paths.get(d + "/ramp/ramp_" + nm + ".i16"));
            int n = raw.length / 2 / 3, worst = Integer.MAX_VALUE, distinct = 0;
            for (int fr = 0; fr < 3; fr++) {
                int[] codes = new int[n];
                java.util.HashSet<Integer> ds = new java.util.HashSet<>();
                for (int q = 0; q < n; q++) { int o = (fr * n + q) * 2; codes[q] = (raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8); ds.add(codes[q]); }
                worst = Math.min(worst, com.mojahed.hdrstudio.encode.PrecisionMetric.coverage90(codes));
                distinct = Math.max(distinct, ds.size());
            }
            boolean ten = nm.startsWith("10");
            boolean verdict = com.mojahed.hdrstudio.encode.PrecisionMetric.proves10bit(worst);
            check("ramp_precision_" + nm, verdict == ten, "coverage90=" + worst + " distinct=" + distinct + " -> " + (verdict ? "10-bit" : "not 10-bit"));
        }
        System.out.println(fails == 0 ? "ALL BITSTREAM TESTS PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
