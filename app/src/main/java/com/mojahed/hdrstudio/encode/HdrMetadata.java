package com.mojahed.hdrstudio.encode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * HDR10 static metadata (CTA-861.3 / SMPTE ST 2086), as MediaFormat.KEY_HDR_STATIC_INFO.
 *
 * Two different things, never mixed up:
 *  - Mastering display colour volume: describes the (virtual) grading display - a declared
 *    target, not a measurement. For SDR->HDR conversions we declare a P3-D65 display with the
 *    peak the user selected.
 *  - Content light levels MaxCLL/MaxFALL: measured from the final pixels in the optional
 *    analysis pass. When not measured they are written as 0 = "unknown" (allowed by CTA-861.3),
 *    never as invented numbers.
 */
public final class HdrMetadata {
    public float masterPeakNits = 1000, masterMinNits = 0.005f;
    public int maxCll, maxFall;                 // 0 = unknown
    public boolean cllMeasured;
    public String cllSource = "غير مقاس (0 = غير معروف)";
    // primaries (x, y) of the declared mastering display; default P3-D65
    public double[] r = {0.680, 0.320}, g = {0.265, 0.690}, b = {0.150, 0.060}, w = {0.3127, 0.3290};

    public static HdrMetadata p3(float peak) { HdrMetadata m = new HdrMetadata(); m.masterPeakNits = peak; return m; }

    public void setMeasured(float cll, float fall, String how) {
        maxCll = Math.max(1, Math.round(cll)); maxFall = Math.max(1, Math.round(fall));
        cllMeasured = true; cllSource = how;
    }

    public ByteBuffer toStaticInfo() {
        ByteBuffer bb = ByteBuffer.allocateDirect(25).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0);
        put(bb, r); put(bb, g); put(bb, b); put(bb, w);
        bb.putShort((short) Math.round(masterPeakNits));
        bb.putShort((short) Math.round(masterMinNits * 10000));
        bb.putShort((short) Math.min(65535, maxCll));
        bb.putShort((short) Math.min(65535, maxFall));
        bb.flip();
        return bb;
    }

    /** Same metadata as an HEVC prefix SEI NAL (Annex-B), for encoders that do not write it themselves. */
    public byte[] toSeiNal() {
        int[] gbr = {xy(g[0]), xy(g[1]), xy(b[0]), xy(b[1]), xy(r[0]), xy(r[1])};
        return HevcBitstream.buildHdr10Sei(gbr, xy(w[0]), xy(w[1]), Math.round(masterPeakNits * 10000.0),
                Math.round(masterMinNits * 10000.0), Math.min(65535, maxCll), Math.min(65535, maxFall));
    }

    private static int xy(double v) { return (int) Math.round(v * 50000); }

    private static void put(ByteBuffer bb, double[] xy) {
        bb.putShort((short) Math.round(xy[0] * 50000));
        bb.putShort((short) Math.round(xy[1] * 50000));
    }

    public String describe() {
        return "عرض المرجع (معلن): P3-D65 · " + (int) masterPeakNits + " nit\nMaxCLL/MaxFALL: " +
                (cllMeasured ? maxCll + " / " + maxFall + " (" + cllSource + ")" : "0 / 0 — " + cllSource);
    }
}
