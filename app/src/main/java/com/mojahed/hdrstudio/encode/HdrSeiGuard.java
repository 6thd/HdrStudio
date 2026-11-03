package com.mojahed.hdrstudio.encode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * HDR10 static metadata in the HEVC bitstream: mastering display colour volume (SEI payload 137)
 * and content light level (payload 144), each handled on its own.
 *
 * Policy (H.265 D.3.28 / D.3.35: the messages describe the whole CLVS, must be present in its first
 * access unit - an IRAP - and every instance in a CLVS must have the same content):
 *  - every 137/144 message the encoder wrote, in the codec-specific data or in any access unit,
 *    is parsed and must be well-formed and equal to the configured values; otherwise the export
 *    fails with {@link Conflict} - a wrong or contradicting message is never hidden or overwritten;
 *  - in every IRAP access unit, a message that is missing is added (only the missing one), as a
 *    new prefix SEI NAL unit immediately before the first VCL NAL unit; a message present in the
 *    codec-specific data (hvcC) does not count, because players do not reliably apply it;
 *  - non-IRAP access units are only checked, never modified.
 * Framing: Annex-B (what MediaCodec emits) and 4-byte length prefixes; other NAL length sizes are
 * not supported and are rejected rather than guessed.
 * Adding metadata does not make wrong pixels right: the colour path is proven separately.
 * Pure Java (unit-tested on the JVM against x265 streams and ffprobe).
 */
public final class HdrSeiGuard {
    public static final int MDCV = 137, CLL = 144;

    public static final class Conflict extends IllegalStateException {
        public Conflict(String m) { super(m); }
    }

    private final byte[] mdcv, cll;
    public long accessUnits, irapUnits, insertedMdcv, insertedCll, encoderMdcv, encoderCll;
    public boolean csdMdcv, csdCll;

    public HdrSeiGuard(HdrMetadata m) { this(m.mdcvPayload(), m.cllPayload()); }

    public HdrSeiGuard(byte[] mdcvPayload, byte[] cllPayload) {
        if (mdcvPayload.length != 24 || cllPayload.length != 4) throw new IllegalArgumentException("payload sizes");
        String e = validate(MDCV, mdcvPayload); if (e == null) e = validate(CLL, cllPayload);
        if (e != null) throw new IllegalArgumentException("configured metadata invalid: " + e);
        this.mdcv = mdcvPayload.clone(); this.cll = cllPayload.clone();
    }

    /** Codec-specific data (VPS/SPS/PPS and possibly SEI, no VCL). */
    public void onCodecConfig(byte[] csd) {
        for (byte[] nal : HevcBitstream.splitNals(csd)) {
            if (nal.length < 3 || ((nal[0] >> 1) & 0x3F) != 39) continue;
            for (HevcBitstream.SeiMessage s : messages(nal, 0, nal.length, "codec config")) {
                if (s.type == MDCV) { check(s, mdcv, "codec config"); csdMdcv = true; }
                else if (s.type == CLL) { check(s, cll, "codec config"); csdCll = true; }
            }
        }
    }

    /**
     * @return the access unit to write: the same array when nothing was added.
     * @throws Conflict on malformed or contradicting messages, or on unsupported framing.
     */
    public byte[] onAccessUnit(byte[] au) {
        boolean lp = HevcBitstream.isLengthPrefixed(au);
        List<HevcBitstream.NalRef> nals = HevcBitstream.prefixNals(au, lp);
        if (nals == null) throw new Conflict("وحدة وصول HEVC بلا NAL صورة (VCL) أو بتأطير غير مدعوم");
        accessUnits++;
        HevcBitstream.NalRef vcl = nals.get(nals.size() - 1);
        boolean hasM = false, hasC = false;
        for (HevcBitstream.NalRef n : nals) {
            if (n.type != 39) continue;
            for (HevcBitstream.SeiMessage s : messages(au, n.header, n.end, "access unit")) {
                if (s.type == MDCV) { check(s, mdcv, "access unit"); hasM = true; }
                else if (s.type == CLL) { check(s, cll, "access unit"); hasC = true; }
            }
        }
        if (!HevcBitstream.isIrapType(vcl.type)) return au;
        irapUnits++;
        if (hasM) encoderMdcv++;
        if (hasC) encoderCll++;
        if (hasM && hasC) return au;
        List<HevcBitstream.SeiMessage> add = new ArrayList<>();
        if (!hasM) add.add(new HevcBitstream.SeiMessage(MDCV, mdcv));
        if (!hasC) add.add(new HevcBitstream.SeiMessage(CLL, cll));
        byte[] out = HevcBitstream.insertNalBeforeFirstVcl(au, HevcBitstream.prefixSeiNal(add), lp);
        if (out == null) throw new Conflict("تعذر إدراج SEI قبل أول NAL صورة");
        if (!hasM) insertedMdcv++;
        if (!hasC) insertedCll++;
        return out;
    }

    public String describe() {
        return String.format(Locale.US, "IRAP %d · Mastering: المرمّز %d / أدرجه التطبيق %d · CLL: المرمّز %d / أدرجه التطبيق %d%s",
                irapUnits, encoderMdcv, insertedMdcv, encoderCll, insertedCll,
                csdMdcv || csdCll ? " · موجود أيضاً في بيانات الترميز (hvcC)" : "");
    }

    private static List<HevcBitstream.SeiMessage> messages(byte[] buf, int header, int end, String where) {
        try { return HevcBitstream.seiMessages(buf, header, end); }
        catch (RuntimeException e) { throw new Conflict("رسالة SEI تالفة في " + where + ": " + e.getMessage()); }
    }

    private static void check(HevcBitstream.SeiMessage s, byte[] want, String where) {
        String bad = validate(s.type, s.payload);
        if (bad != null) throw new Conflict("رسالة SEI " + s.type + " مخالفة في " + where + ": " + bad);
        if (!Arrays.equals(s.payload, want))
            throw new Conflict("رسالة SEI " + s.type + " في " + where + " تختلف عن البيانات المضبوطة: " + describePayload(s.type, s.payload)
                    + " بدلاً من " + describePayload(s.type, want));
    }

    /** null when the payload is well-formed, else the reason. */
    public static String validate(int type, byte[] p) {
        if (type == MDCV) {
            if (p.length != 24) return "حجم mastering " + p.length + " بدلاً من 24";
            for (int k = 0; k < 8; k++) { int v = u16(p, 2 * k); if (v > 50000) return "إحداثي لوني خارج المدى " + v; }
            long max = u32(p, 16), min = u32(p, 20);
            if (max <= min) return "أقصى سطوع ≤ أدنى سطوع";
            if (max < 50000) return "أقصى سطوع أقل من 5 nit";
            return null;
        }
        if (type == CLL) {
            if (p.length != 4) return "حجم CLL " + p.length + " بدلاً من 4";
            int cll = u16(p, 0), fall = u16(p, 2);
            if (fall > cll) return "MaxFALL (" + fall + ") أكبر من MaxCLL (" + cll + ")";
            return null;
        }
        return null;
    }

    public static String describePayload(int type, byte[] p) {
        if (type == MDCV && p.length == 24)
            return String.format(Locale.US, "G(%d,%d) B(%d,%d) R(%d,%d) WP(%d,%d) L(%d,%d)", u16(p, 0), u16(p, 2), u16(p, 4), u16(p, 6),
                    u16(p, 8), u16(p, 10), u16(p, 12), u16(p, 14), u32(p, 16), u32(p, 20));
        if (type == CLL && p.length == 4) return u16(p, 0) + "/" + u16(p, 2);
        return p.length + " bytes";
    }

    static int u16(byte[] p, int o) { return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF); }
    static long u32(byte[] p, int o) { return ((long) u16(p, o) << 16) | u16(p, o + 2); }
}
