package com.mojahed.hdrstudio.encode;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * AVC profile and level as one explicit pair, and what the encoder actually wrote.
 *
 * Choice: the export asks for a profile only together with a level the encoder itself declares for
 * that profile (MediaCodecInfo.CodecProfileLevel list). Some encoders refuse configure() when
 * KEY_PROFILE comes without KEY_LEVEL (OMX.hisi.video.encoder.avc: High without a level is refused,
 * High with its declared level is accepted). The level is the highest one the encoder declares for the
 * chosen profile on this device, never a fixed number. High is preferred, then Main; if the encoder
 * declares neither, no profile and no level are set (encoder default), as before for other codecs.
 *
 * Verification: the output's SPS (csd-0) carries profile_idc and level_idc; they are reported next to
 * the requested pair. Pure Java (unit-tested on the JVM); the constants mirror
 * android.media.MediaCodecInfo.CodecProfileLevel.
 */
public final class AvcProfileLevel {
    private AvcProfileLevel() {}

    public static final int BASELINE = 0x01, MAIN = 0x02, EXTENDED = 0x04, HIGH = 0x08, HIGH10 = 0x10, HIGH422 = 0x20, HIGH444 = 0x40,
            CONSTRAINED_BASELINE = 0x10000, CONSTRAINED_HIGH = 0x80000;

    private static final int[] LEVEL_CONST = {0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000,
            0x4000, 0x8000, 0x10000, 0x20000, 0x40000, 0x80000};
    private static final int[] LEVEL_IDC = {10, 9, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52, 60, 61, 62};
    private static final String[] LEVEL_NAME = {"1", "1b", "1.1", "1.2", "1.3", "2", "2.1", "2.2", "3", "3.1", "3.2", "4", "4.1", "4.2",
            "5", "5.1", "5.2", "6", "6.1", "6.2"};

    /** Requested pair; profile/level are MediaCodec constants, -1 = not set. */
    public static final class Pair {
        public final int profile, level;
        public Pair(int profile, int level) { this.profile = profile; this.level = level; }
        public String describe() { return profile < 0 ? "افتراضي المرمّز (بلا profile ولا level)" : profileName(profile) + " (" + profile + ") / " + levelName(level) + " (" + level + ")"; }
    }

    /**
     * @param declared the encoder's declared {profile, level} pairs
     * @return High with its highest declared level, else Main with its highest declared level, else none
     */
    public static Pair choose(int[][] declared) {
        for (int p : new int[]{HIGH, MAIN}) {
            int best = maxLevel(declared, p);
            if (best > 0) return new Pair(p, best);
        }
        return new Pair(-1, -1);
    }

    public static int maxLevel(int[][] declared, int profile) {
        int best = 0;
        if (declared == null || profile < 0) return 0;
        for (int[] pl : declared) if (pl != null && pl.length >= 2 && pl[0] == profile) best = Math.max(best, pl[1]);
        return best;
    }

    /** profile_idc for a MediaCodec AVC profile constant, or -1. */
    public static int profileIdc(int profile) {
        switch (profile) {
            case BASELINE: case CONSTRAINED_BASELINE: return 66;
            case MAIN: return 77;
            case EXTENDED: return 88;
            case HIGH: case CONSTRAINED_HIGH: return 100;
            case HIGH10: return 110;
            case HIGH422: return 122;
            case HIGH444: return 244;
            default: return -1;
        }
    }

    /** level_idc for a MediaCodec AVC level constant, or -1 (level 1b is 9 here; in the SPS it may also be 11 with constraint_set3). */
    public static int levelIdc(int level) {
        for (int i = 0; i < LEVEL_CONST.length; i++) if (LEVEL_CONST[i] == level) return LEVEL_IDC[i];
        return -1;
    }

    public static String profileName(int profile) {
        switch (profile) {
            case BASELINE: return "Baseline";
            case CONSTRAINED_BASELINE: return "Constrained Baseline";
            case MAIN: return "Main";
            case EXTENDED: return "Extended";
            case HIGH: return "High";
            case CONSTRAINED_HIGH: return "Constrained High";
            case HIGH10: return "High 10";
            case HIGH422: return "High 4:2:2";
            case HIGH444: return "High 4:4:4";
            default: return "profile " + profile;
        }
    }

    public static String levelName(int level) {
        for (int i = 0; i < LEVEL_CONST.length; i++) if (LEVEL_CONST[i] == level) return "Level " + LEVEL_NAME[i];
        return "level " + level;
    }

    public static String profileIdcName(int idc) {
        switch (idc) {
            case 66: return "Baseline";
            case 77: return "Main";
            case 88: return "Extended";
            case 100: return "High";
            case 110: return "High 10";
            case 122: return "High 4:2:2";
            case 244: return "High 4:4:4";
            default: return "profile_idc " + idc;
        }
    }

    public static String levelIdcName(int idc) {
        if (idc == 9) return "Level 1b";
        if (idc <= 0) return "level_idc " + idc;
        return String.format(Locale.US, "Level %d%s", idc / 10, idc % 10 == 0 ? "" : "." + (idc % 10));
    }

    /** First SPS of an AVC codec-config buffer (Annex B start codes or a single raw NAL). */
    public static final class Sps {
        public final int profileIdc, constraintFlags, levelIdc;
        Sps(int p, int c, int l) { profileIdc = p; constraintFlags = c; levelIdc = l; }
        public String describe() {
            return String.format(Locale.US, "profile_idc=%d (%s) · constraint=0x%02x · level_idc=%d (%s)", profileIdc, profileIdcName(profileIdc),
                    constraintFlags, levelIdc, levelIdcName(levelIdc));
        }
    }

    public static Sps parseSps(byte[] buf) {
        if (buf == null) return null;
        int n = buf.length;
        // Annex B: scan NAL units after start codes
        for (int i = 0; i + 3 < n; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
                int s = i + 3;
                Sps r = spsAt(buf, s);
                if (r != null) return r;
                i = s - 1;
            }
        }
        return spsAt(buf, 0); // no start code: a raw NAL
    }

    private static Sps spsAt(byte[] buf, int s) {
        if (s >= buf.length || (buf[s] & 0x80) != 0 || (buf[s] & 0x1f) != 7) return null;
        byte[] rbsp = unescape(buf, s + 1, Math.min(buf.length, s + 1 + 16));
        if (rbsp.length < 3) return null;
        return new Sps(rbsp[0] & 0xff, rbsp[1] & 0xff, rbsp[2] & 0xff);
    }

    /** Removes emulation-prevention bytes (00 00 03 → 00 00). */
    static byte[] unescape(byte[] b, int from, int to) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int zeros = 0;
        for (int i = from; i < to; i++) {
            int v = b[i] & 0xff;
            if (zeros >= 2 && v == 3) { zeros = 0; continue; }
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    /** One line comparing the requested pair with the SPS the encoder wrote. */
    public static String compare(int profile, int level, Sps sps) {
        StringBuilder b = new StringBuilder("AVC profile/level: المطلوب ");
        b.append(new Pair(profile, level).describe()).append(" · الفعلي في SPS ");
        if (sps == null) return b.append("تعذر قراءة SPS").toString();
        b.append(sps.describe());
        if (profile >= 0) {
            int wp = profileIdc(profile), wl = levelIdc(level);
            boolean pOk = wp == sps.profileIdc;
            boolean lOk = wl < 0 || sps.levelIdc <= wl;
            b.append(pOk ? " · profile مطابق" : " · profile مختلف عن المطلوب");
            if (wl > 0) b.append(sps.levelIdc == wl ? " · level مطابق" : lOk ? " · level أدنى من الحد المطلوب (ضمن الحد)" : " · level أعلى من المطلوب");
        }
        return b.toString();
    }
}
