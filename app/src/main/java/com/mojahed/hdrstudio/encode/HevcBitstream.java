package com.mojahed.hdrstudio.encode;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Independent HEVC bitstream inspection (ITU-T H.265), so verification does not rely on what
 * MediaFormat/decoders *report*: reads the SPS (bit depth, VUI colour description) and prefix
 * SEI messages (137 mastering display colour volume, 144 content light level) directly from
 * the NAL units. Accepts Annex-B (start codes) or 4-byte length-prefixed buffers.
 * Pure Java (no Android dependency) - unit-tested on the JVM against x265 streams.
 */
public final class HevcBitstream {
    public static final class Info {
        public boolean spsFound;
        public int bitDepthLuma = -1, bitDepthChroma = -1, chromaFormat = -1, width, height;
        public boolean vuiPresent, colourDescription;
        public int colourPrimaries = -1, transfer = -1, matrix = -1;
        public boolean fullRange;
        public boolean mdcvFound, cllFound;
        public int[] mdcvPrimaries;        // G x,y  B x,y  R x,y  (units 0.00002)
        public int mdcvWhiteX, mdcvWhiteY;
        public long mdcvMaxLum, mdcvMinLum; // units 0.0001 cd/m2
        public int maxCll, maxFall;
        public String error;

        /** HDR10 = 10-bit 4:2:0, BT.2020 primaries (9), PQ (16), BT.2020 NCL matrix (9), limited range. */
        public boolean isHdr10Signal() {
            return spsFound && bitDepthLuma == 10 && bitDepthChroma == 10 && colourDescription
                    && colourPrimaries == 9 && transfer == 16 && matrix == 9 && !fullRange;
        }

        public String describe() {
            if (!spsFound) return "SPS غير موجود" + (error != null ? " (" + error + ")" : "");
            StringBuilder b = new StringBuilder(String.format(Locale.US, "SPS: %dx%d · luma %d-bit · chroma %d-bit · chroma_format %d",
                    width, height, bitDepthLuma, bitDepthChroma, chromaFormat));
            b.append(colourDescription ? String.format(Locale.US, " · VUI primaries=%d transfer=%d matrix=%d %s",
                    colourPrimaries, transfer, matrix, fullRange ? "full" : "limited") : " · VUI بلا وصف ألوان");
            b.append(" · SEI mastering: ").append(mdcvFound ? String.format(Locale.US, "%.0f nit", mdcvMaxLum / 10000.0) : "غير موجود");
            b.append(" · SEI CLL: ").append(cllFound ? maxCll + "/" + maxFall : "غير موجود");
            return b.toString();
        }
    }

    private HevcBitstream() {}

    /** Parses all NAL units found in the given buffers (csd-0 and the first access units). */
    public static Info inspect(List<byte[]> buffers) {
        Info info = new Info();
        for (byte[] buf : buffers) {
            for (byte[] nal : splitNals(buf)) {
                if (nal.length < 3) continue;
                int type = (nal[0] >> 1) & 0x3F;
                try {
                    if (type == 33 && !info.spsFound) parseSps(unescape(nal, 2), info);
                    else if (type == 39) parseSei(unescape(nal, 2), info);
                } catch (RuntimeException e) {
                    if (info.error == null) info.error = "تعذر تحليل NAL نوع " + type + ": " + e.getMessage();
                }
            }
        }
        return info;
    }

    // ------------------------------------------------------------------ NAL framing
    /** True when b is a valid sequence of 4-byte length-prefixed NAL units covering the whole buffer. */
    public static boolean isLengthPrefixed(byte[] b) {
        int i = 0, count = 0;
        while (i + 4 <= b.length) {
            long n = ((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            i += 4;
            if (n < 2 || i + n > b.length) return false;
            if ((b[i] & 0x80) != 0 || (b[i + 1] & 0x07) == 0) return false;   // forbidden_zero_bit / nuh_temporal_id_plus1
            i += (int) n; count++;
        }
        return count > 0 && i == b.length;
    }

    static List<byte[]> splitNals(byte[] b) {
        List<byte[]> out = new ArrayList<>();
        // A length prefix such as 00 00 01 xx looks like a start code, so validate the
        // length-prefixed interpretation first (it must tile the buffer exactly).
        boolean annexB = !isLengthPrefixed(b);
        if (annexB) {
            int i = 0, start = -1;
            while (i + 2 < b.length) {
                if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                    if (start >= 0) out.add(trimZeros(b, start, i));
                    i += 3; start = i;
                } else i++;
            }
            if (start >= 0 && start < b.length) out.add(java.util.Arrays.copyOfRange(b, start, b.length));
        } else {
            int i = 0;
            while (i + 4 <= b.length) {
                int n = ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                i += 4;
                if (n <= 0 || i + n > b.length) break;
                out.add(java.util.Arrays.copyOfRange(b, i, i + n));
                i += n;
            }
        }
        return out;
    }

    private static byte[] trimZeros(byte[] b, int s, int e) {
        while (e > s && b[e - 1] == 0) e--;            // trailing zero bytes belong to the next start code
        return java.util.Arrays.copyOfRange(b, s, e);
    }

    /** Removes emulation-prevention bytes (00 00 03 -> 00 00) after skipping the NAL header. */
    public static byte[] unescape(byte[] nal, int skip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = skip; i < nal.length; i++) {
            int v = nal[i] & 0xFF;
            if (zeros >= 2 && v == 3) { zeros = 0; continue; }
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ bit reader
    static final class Bits {
        final byte[] d; int pos;
        Bits(byte[] d) { this.d = d; }
        int u(int n) {
            int v = 0;
            for (int k = 0; k < n; k++) {
                if (pos >= d.length * 8) throw new IllegalStateException("end of data");
                v = (v << 1) | ((d[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }
        long u32() { return ((long) u(16) << 16) | u(16); }
        int ue() {
            int z = 0;
            while (u(1) == 0) { if (++z > 31) throw new IllegalStateException("bad ue"); }
            return z == 0 ? 0 : (1 << z) - 1 + u(z);
        }
        int se() { int k = ue(); return (k & 1) != 0 ? (k + 1) / 2 : -(k / 2); }
        void skip(int n) { pos += n; }
    }

    // ------------------------------------------------------------------ SPS
    private static void profileTierLevel(Bits r, int maxSubLayersMinus1) {
        r.skip(88);                 // general profile_space..reserved/inbld flags
        r.skip(8);                  // general_level_idc
        boolean[] prof = new boolean[8], lev = new boolean[8];
        for (int i = 0; i < maxSubLayersMinus1; i++) { prof[i] = r.u(1) == 1; lev[i] = r.u(1) == 1; }
        if (maxSubLayersMinus1 > 0) for (int i = maxSubLayersMinus1; i < 8; i++) r.skip(2);
        for (int i = 0; i < maxSubLayersMinus1; i++) { if (prof[i]) r.skip(88); if (lev[i]) r.skip(8); }
    }

    private static void scalingListData(Bits r) {
        for (int sizeId = 0; sizeId < 4; sizeId++) {
            for (int matrixId = 0; matrixId < 6; matrixId += (sizeId == 3) ? 3 : 1) {
                if (r.u(1) == 0) r.ue();
                else {
                    int coefNum = Math.min(64, 1 << (4 + (sizeId << 1)));
                    if (sizeId > 1) r.se();
                    for (int i = 0; i < coefNum; i++) r.se();
                }
            }
        }
    }

    static void parseSps(byte[] rbsp, Info info) {
        Bits r = new Bits(rbsp);
        r.u(4);
        int maxSub = r.u(3);
        r.u(1);
        profileTierLevel(r, maxSub);
        r.ue();
        info.chromaFormat = r.ue();
        if (info.chromaFormat == 3) r.u(1);
        info.width = r.ue(); info.height = r.ue();
        if (r.u(1) == 1) { r.ue(); r.ue(); r.ue(); r.ue(); }
        info.bitDepthLuma = r.ue() + 8;
        info.bitDepthChroma = r.ue() + 8;
        info.spsFound = true;                       // bit depth is known from here on
        int log2MaxPocLsb = r.ue() + 4;
        boolean subOrder = r.u(1) == 1;
        for (int i = subOrder ? 0 : maxSub; i <= maxSub; i++) { r.ue(); r.ue(); r.ue(); }
        r.ue(); r.ue(); r.ue(); r.ue(); r.ue(); r.ue();
        if (r.u(1) == 1) { if (r.u(1) == 1) scalingListData(r); }
        r.u(1); r.u(1);
        if (r.u(1) == 1) { r.u(4); r.u(4); r.ue(); r.ue(); r.u(1); }
        int numSets = r.ue();
        int[] numDelta = new int[numSets + 1];
        for (int idx = 0; idx < numSets; idx++) {
            boolean inter = idx != 0 && r.u(1) == 1;
            if (inter) {
                r.u(1); r.ue();                     // delta_rps_sign, abs_delta_rps_minus1 (delta_idx = 1 in SPS)
                int ref = idx - 1, n = 0;
                for (int j = 0; j <= numDelta[ref]; j++) {
                    boolean used = r.u(1) == 1;
                    boolean useDelta = used || r.u(1) == 1;
                    if (useDelta) n++;
                }
                numDelta[idx] = n;
            } else {
                int neg = r.ue(), pos = r.ue();
                for (int i = 0; i < neg + pos; i++) { r.ue(); r.u(1); }
                numDelta[idx] = neg + pos;
            }
        }
        if (r.u(1) == 1) {
            int n = r.ue();
            for (int i = 0; i < n; i++) { r.u(log2MaxPocLsb); r.u(1); }
        }
        r.u(1); r.u(1);
        info.vuiPresent = r.u(1) == 1;
        if (!info.vuiPresent) return;
        if (r.u(1) == 1) { int idc = r.u(8); if (idc == 255) { r.u(16); r.u(16); } }
        if (r.u(1) == 1) r.u(1);
        if (r.u(1) == 1) {
            r.u(3);
            info.fullRange = r.u(1) == 1;
            info.colourDescription = r.u(1) == 1;
            if (info.colourDescription) { info.colourPrimaries = r.u(8); info.transfer = r.u(8); info.matrix = r.u(8); }
        }
    }

    // ------------------------------------------------------------------ SEI writing
    /** Adds emulation-prevention bytes (00 00 0x with x <= 3 -> 00 00 03 0x). */
    public static byte[] escape(byte[] rbsp) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(rbsp.length + 8);
        int zeros = 0;
        for (byte x : rbsp) {
            int v = x & 0xFF;
            if (zeros >= 2 && v <= 3) { o.write(3); zeros = 0; }
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    private static void put16(ByteArrayOutputStream o, int v) { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
    private static void put32(ByteArrayOutputStream o, long v) { put16(o, (int) (v >>> 16)); put16(o, (int) (v & 0xFFFF)); }

    /**
     * Prefix SEI NAL (Annex-B, with 4-byte start code) carrying mastering display colour volume
     * (payload 137, primaries in G, B, R order, units 0.00002 / 0.0001 cd/m2) and content light
     * level (payload 144; 0 = unknown).
     */
    public static byte[] buildHdr10Sei(int[] gbrXY, int whiteX, int whiteY, long maxLum, long minLum, int maxCll, int maxFall) {
        ByteArrayOutputStream rb = new ByteArrayOutputStream();
        rb.write(137); rb.write(24);
        for (int k = 0; k < 6; k++) put16(rb, gbrXY[k]);
        put16(rb, whiteX); put16(rb, whiteY);
        put32(rb, maxLum); put32(rb, minLum);
        rb.write(144); rb.write(4);
        put16(rb, maxCll); put16(rb, maxFall);
        rb.write(0x80);                                   // rbsp_trailing_bits
        byte[] esc = escape(rb.toByteArray());
        byte[] nal = new byte[4 + 2 + esc.length];
        nal[3] = 1;
        nal[4] = (byte) (39 << 1); nal[5] = 1;            // PREFIX_SEI, layer 0, temporal id 0
        System.arraycopy(esc, 0, nal, 6, esc.length);
        return nal;
    }

    /** Does this access unit already carry a mastering-display SEI? */
    public static boolean hasMdcv(byte[] au) {
        Info i = new Info();
        for (byte[] nal : splitNals(au)) {
            if (nal.length >= 3 && ((nal[0] >> 1) & 0x3F) == 39) {
                try { parseSei(unescape(nal, 2), i); } catch (RuntimeException ignored) { }
            }
        }
        return i.mdcvFound;
    }

    /**
     * Inserts an Annex-B SEI NAL (with start code) right before the first VCL NAL unit of an
     * Annex-B access unit (after any AUD/VPS/SPS/PPS), as H.265 7.4.2.4.4 requires for prefix SEI.
     * Returns null if the buffer is not Annex-B or has no VCL NAL.
     */
    public static byte[] insertBeforeFirstVcl(byte[] au, byte[] seiWithStartCode) {
        if (isLengthPrefixed(au)) return null;
        int i = 0;
        while (i + 3 < au.length) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) {
                int type = (au[i + 3] >> 1) & 0x3F;
                if (type < 32) {
                    int at = (i > 0 && au[i - 1] == 0) ? i - 1 : i;      // include a 4-byte start code's leading zero
                    byte[] out = new byte[au.length + seiWithStartCode.length];
                    System.arraycopy(au, 0, out, 0, at);
                    System.arraycopy(seiWithStartCode, 0, out, at, seiWithStartCode.length);
                    System.arraycopy(au, at, out, at + seiWithStartCode.length, au.length - at);
                    return out;
                }
                i += 3;
            } else i++;
        }
        return null;
    }

    // ------------------------------------------------------------------ SEI
    static void parseSei(byte[] rbsp, Info info) {
        int i = 0;
        while (i < rbsp.length && !(i == rbsp.length - 1 && (rbsp[i] & 0xFF) == 0x80)) {
            int type = 0, size = 0, v;
            do { v = rbsp[i++] & 0xFF; type += v; } while (v == 0xFF && i < rbsp.length);
            do { v = rbsp[i++] & 0xFF; size += v; } while (v == 0xFF && i < rbsp.length);
            if (i + size > rbsp.length) break;
            Bits r = new Bits(java.util.Arrays.copyOfRange(rbsp, i, i + size));
            if (type == 137 && size >= 24) {
                info.mdcvFound = true;
                info.mdcvPrimaries = new int[6];
                for (int k = 0; k < 6; k++) info.mdcvPrimaries[k] = r.u(16);
                info.mdcvWhiteX = r.u(16); info.mdcvWhiteY = r.u(16);
                info.mdcvMaxLum = r.u32(); info.mdcvMinLum = r.u32();
            } else if (type == 144 && size >= 4) {
                info.cllFound = true;
                info.maxCll = r.u(16); info.maxFall = r.u(16);
            }
            i += size;
        }
    }
}
