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

    // ------------------------------------------------------------------ access-unit inspection (prefix SEI)
    /** NAL type range of IRAP pictures (BLA, IDR, CRA, reserved IRAP). */
    public static boolean isIrapType(int t) { return t >= 16 && t <= 23; }

    /** One NAL unit inside an access-unit buffer. start = first byte of its start code / length field. */
    public static final class NalRef {
        public final int start, header, end, type;
        NalRef(int start, int header, int end, int type) { this.start = start; this.header = header; this.end = end; this.type = type; }
    }

    /**
     * Prefix part of an access unit: every NAL unit before the first VCL NAL unit, and the first
     * VCL NAL unit itself (last element), which is where H.265 places prefix SEI. Scanning stops at
     * the first VCL NAL, so the slice data is not walked. Framing: Annex-B (3- or 4-byte start
     * codes) or 4-byte length prefixes; returns null if there is no VCL NAL unit.
     */
    public static List<NalRef> prefixNals(byte[] au) { return prefixNals(au, isLengthPrefixed(au)); }

    public static List<NalRef> prefixNals(byte[] au, boolean lengthPrefixed) {
        List<NalRef> out = new ArrayList<>();
        if (lengthPrefixed) {
            int i = 0;
            while (i + 4 <= au.length) {
                int n = ((au[i] & 0xFF) << 24) | ((au[i + 1] & 0xFF) << 16) | ((au[i + 2] & 0xFF) << 8) | (au[i + 3] & 0xFF);
                if (n < 2 || i + 4 + n > au.length) return null;
                int t = (au[i + 4] >> 1) & 0x3F;
                out.add(new NalRef(i, i + 4, i + 4 + n, t));
                if (t < 32) return out;
                i += 4 + n;
            }
            return null;
        }
        int i = 0, hdr = -1, st = -1;
        while (i + 2 < au.length) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) {
                int s = (i > 0 && au[i - 1] == 0) ? i - 1 : i;
                if (hdr >= 0) {
                    int e = s; while (e > hdr && au[e - 1] == 0) e--;
                    out.add(new NalRef(st, hdr, e, (au[hdr] >> 1) & 0x3F));
                    if (out.get(out.size() - 1).type < 32) return out;
                }
                st = s; hdr = i + 3; i += 3;
            } else i++;
        }
        if (hdr >= 0 && hdr < au.length) {
            out.add(new NalRef(st, hdr, au.length, (au[hdr] >> 1) & 0x3F));
            if (out.get(out.size() - 1).type < 32) return out;
        }
        return null;
    }

    /** {insert offset, NAL type} of the first VCL NAL unit, or null. */
    private static int[] firstVcl(byte[] au, boolean lengthPrefixed) {
        List<NalRef> p = prefixNals(au, lengthPrefixed);
        if (p == null) return null;
        NalRef v = p.get(p.size() - 1);
        return new int[]{v.start, v.type};
    }

    /** One SEI message (payloadType + raw payload bytes, emulation prevention removed). */
    public static final class SeiMessage {
        public final int type; public final byte[] payload;
        public SeiMessage(int type, byte[] payload) { this.type = type; this.payload = payload; }
    }

    /** All SEI messages of an SEI NAL unit (nal = bytes from the 2-byte NAL header to the end). */
    public static List<SeiMessage> seiMessages(byte[] au, int header, int end) {
        byte[] rbsp = unescape(java.util.Arrays.copyOfRange(au, header, end), 2);
        List<SeiMessage> out = new ArrayList<>();
        int i = 0;
        while (i < rbsp.length && !(i == rbsp.length - 1 && (rbsp[i] & 0xFF) == 0x80)) {
            int type = 0, size = 0, v;
            do { v = rbsp[i++] & 0xFF; type += v; } while (v == 0xFF && i < rbsp.length);
            if (i >= rbsp.length) throw new IllegalStateException("SEI truncated after payloadType " + type);
            do { v = rbsp[i++] & 0xFF; size += v; } while (v == 0xFF && i < rbsp.length);
            if (i + size > rbsp.length) throw new IllegalStateException("SEI payload " + type + " size " + size + " exceeds NAL");
            out.add(new SeiMessage(type, java.util.Arrays.copyOfRange(rbsp, i, i + size)));
            i += size;
        }
        return out;
    }

    /** Prefix SEI NAL unit (2-byte header + escaped RBSP, no start code) carrying the given messages. */
    public static byte[] prefixSeiNal(List<SeiMessage> msgs) {
        ByteArrayOutputStream rb = new ByteArrayOutputStream();
        for (SeiMessage m : msgs) {
            int t = m.type; while (t >= 255) { rb.write(255); t -= 255; } rb.write(t);
            int s = m.payload.length; while (s >= 255) { rb.write(255); s -= 255; } rb.write(s);
            rb.write(m.payload, 0, m.payload.length);
        }
        rb.write(0x80);
        byte[] esc = escape(rb.toByteArray());
        byte[] nal = new byte[2 + esc.length];
        nal[0] = (byte) (39 << 1); nal[1] = 1;            // PREFIX_SEI, layer 0, temporal id 0
        System.arraycopy(esc, 0, nal, 2, esc.length);
        return nal;
    }

    /**
     * Inserts a NAL unit (header + payload, no framing) immediately before the first VCL NAL unit,
     * framed like the rest of the buffer (4-byte start code, or 4-byte length prefix).
     * Returns null if the access unit has no VCL NAL unit.
     */
    public static byte[] insertNalBeforeFirstVcl(byte[] au, byte[] nal, boolean lengthPrefixed) {
        int[] at = firstVcl(au, lengthPrefixed);
        if (at == null) return null;
        byte[] framed = new byte[4 + nal.length];
        if (lengthPrefixed) { framed[0] = (byte) (nal.length >>> 24); framed[1] = (byte) (nal.length >>> 16); framed[2] = (byte) (nal.length >>> 8); framed[3] = (byte) nal.length; }
        else framed[3] = 1;
        System.arraycopy(nal, 0, framed, 4, nal.length);
        byte[] out = new byte[au.length + framed.length];
        System.arraycopy(au, 0, out, 0, at[0]);
        System.arraycopy(framed, 0, out, at[0], framed.length);
        System.arraycopy(au, at[0], out, at[0] + framed.length, au.length - at[0]);
        return out;
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
