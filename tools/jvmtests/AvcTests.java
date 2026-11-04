import com.mojahed.hdrstudio.core.FrameRate;
import com.mojahed.hdrstudio.encode.AvcProfileLevel;
import com.mojahed.hdrstudio.encode.EncodeConfig;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 2.3.9: AVC profile and level as one explicit pair.
 *  - the pair comes from the encoder's declared list (the phone's OMX.hisi.video.encoder.avc list gives
 *    High/Level 5.2 = 8/65536; another list gives another level, never a fixed number);
 *  - the level is part of EncodeConfig's canonical form and result key (a stored result for High
 *    without a level is not reused), while configurations without a level (HDR10) keep their key;
 *  - the SPS parser reads profile_idc/level_idc from real x264 streams (args: Annex B files).
 */
public class AvcTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // the phone's declared list (2.3.8 diagnosis): 1/32768, 2/32768, 8/65536
        int[][] hisi = {{1, 32768}, {2, 32768}, {8, 65536}};
        AvcProfileLevel.Pair p = AvcProfileLevel.choose(hisi);
        check("pair_phone_high_level52", p.profile == 8 && p.level == 65536, p.describe());
        int[][] other = {{1, 0x2000}, {2, 0x2000}, {8, 0x800}, {8, 0x1000}};
        AvcProfileLevel.Pair q = AvcProfileLevel.choose(other);
        check("pair_other_device_own_level", q.profile == 8 && q.level == 0x1000, q.describe());
        AvcProfileLevel.Pair m = AvcProfileLevel.choose(new int[][]{{1, 0x8000}, {2, 0x4000}});
        check("pair_no_high_uses_main", m.profile == 2 && m.level == 0x4000, m.describe());
        AvcProfileLevel.Pair n = AvcProfileLevel.choose(new int[][]{{1, 0x8000}});
        check("pair_neither_no_profile_no_level", n.profile < 0 && n.level < 0, n.describe());
        check("pair_empty_list", AvcProfileLevel.choose(new int[0][]).profile < 0, "");
        check("level_const_to_idc", AvcProfileLevel.levelIdc(65536) == 52 && AvcProfileLevel.levelIdc(0x8000) == 51 && AvcProfileLevel.levelIdc(0x800) == 40
                && AvcProfileLevel.levelIdc(32768) == 51, "65536→" + AvcProfileLevel.levelIdc(65536));
        check("profile_const_to_idc", AvcProfileLevel.profileIdc(8) == 100 && AvcProfileLevel.profileIdc(2) == 77 && AvcProfileLevel.profileIdc(1) == 66, "");

        // EncodeConfig: the level is in the canonical form and the key
        FrameRate f = FrameRate.fromAverage(29.885);
        EncodeConfig noLevel = new EncodeConfig(false, "OMX.hisi.video.encoder.avc", "video/avc", 8, 1072, 1920, f, 12_000_000, 1, 1f, 1, 3, 2);
        EncodeConfig withLevel = new EncodeConfig(false, "OMX.hisi.video.encoder.avc", "video/avc", 8, 65536, 1072, 1920, f, 12_000_000, 1, 1f, 1, 3, 2);
        EncodeConfig otherLevel = new EncodeConfig(false, "OMX.hisi.video.encoder.avc", "video/avc", 8, 32768, 1072, 1920, f, 12_000_000, 1, 1f, 1, 3, 2);
        String sys = "HUAWEI/x:10/ABC/1:user/release-keys";
        check("level_in_canonical", withLevel.canonical().contains("|profile=8|level=65536|"), withLevel.canonical());
        check("level_invalidates_old_result", !noLevel.resultKey(8, sys).equals(withLevel.resultKey(8, sys)) && !noLevel.equals(withLevel), "");
        check("level_change_invalidates", !withLevel.resultKey(8, sys).equals(otherLevel.resultKey(8, sys)), "");
        check("level_carried_by_with", withLevel.withBitrate(12_000_000).equals(withLevel) && withLevel.withSize(1072, 1920).level == 65536
                && withLevel.withFps(f).level == 65536 && withLevel.withBitrate(10_000_000).level == 65536, "");
        check("sdr_fields_unchanged", withLevel.fps.equals(f) && withLevel.bitrate == 12_000_000 && withLevel.bitrateMode == 1
                && withLevel.colorStandard == 1 && withLevel.colorTransfer == 3 && withLevel.colorRange == 2, withLevel.describe());
        boolean threw = false;
        try { new EncodeConfig(false, "x", "video/avc", -1, 65536, 16, 16, f, 1, 1, 1f, 1, 3, 2); } catch (IllegalArgumentException e) { threw = true; }
        check("level_without_profile_rejected", threw, "");
        // HDR10 (no level): canonical form identical to the 2.3.8 form, so its stored results stay valid
        EncodeConfig hdr = new EncodeConfig(true, "c2.test.hevc.encoder", "video/hevc", 4096, 1920, 1080, FrameRate.FPS_30, 20_000_000, 1, 1f, 6, 6, 2);
        String old = String.format(Locale.US, "cfg%d|%s|enc=%s|mime=%s|profile=%d|%dx%d|fps=%s|br=%d|mode=%d|gop=%.3f|colour=%d/%d/%d",
                EncodeConfig.SCHEMA, "hdr10", "c2.test.hevc.encoder", "video/hevc", 4096, 1920, 1080, FrameRate.FPS_30, 20_000_000, 1, 1f, 6, 6, 2);
        check("hdr_canonical_unchanged", hdr.canonical().equals(old) && hdr.level == -1, hdr.canonical());

        // SPS parser on hand-made buffers
        byte[] annexB = {0, 0, 0, 1, 0x67, 100, 0, 52, (byte) 0xac, 0, 0, 0, 1, 0x68, (byte) 0xee};
        AvcProfileLevel.Sps s = AvcProfileLevel.parseSps(annexB);
        check("sps_annexb", s != null && s.profileIdc == 100 && s.levelIdc == 52, s == null ? "null" : s.describe());
        byte[] raw = {0x67, 77, 0x40, 41, 0x10};
        s = AvcProfileLevel.parseSps(raw);
        check("sps_raw_nal", s != null && s.profileIdc == 77 && s.levelIdc == 41, s == null ? "null" : s.describe());
        byte[] pps = {0, 0, 1, 0x68, 1, 2};
        check("sps_absent_null", AvcProfileLevel.parseSps(pps) == null && AvcProfileLevel.parseSps(null) == null, "");
        // emulation prevention inside the first bytes (profile 0, constraint 0 → 00 00 03 xx)
        byte[] ep = {0, 0, 1, 0x67, 0, 0, 3, 30, 1};
        s = AvcProfileLevel.parseSps(ep);
        check("sps_emulation_prevention", s != null && s.profileIdc == 0 && s.constraintFlags == 0 && s.levelIdc == 30, s == null ? "null" : s.describe());
        String cmp = AvcProfileLevel.compare(8, 65536, AvcProfileLevel.parseSps(annexB));
        check("compare_match_text", cmp.contains("profile مطابق") && cmp.contains("level مطابق") && cmp.contains("level_idc=52"), cmp);
        String cmpLow = AvcProfileLevel.compare(8, 65536, AvcProfileLevel.parseSps(new byte[]{0x67, 100, 0, 40}));
        check("compare_lower_level_reported", cmpLow.contains("أدنى"), cmpLow);

        // real x264 streams: file=profileIdc:levelIdc
        for (String arg : a) {
            String[] kv = arg.split("=");
            String[] want = kv[1].split(":");
            AvcProfileLevel.Sps r = AvcProfileLevel.parseSps(Files.readAllBytes(Paths.get(kv[0])));
            check("sps_x264_" + Paths.get(kv[0]).getFileName(), r != null && r.profileIdc == Integer.parseInt(want[0]) && r.levelIdc == Integer.parseInt(want[1]),
                    r == null ? "null" : r.describe());
        }
        System.exit(fails > 0 ? 1 : 0);
    }
}
