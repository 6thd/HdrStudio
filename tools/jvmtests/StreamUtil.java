import com.mojahed.hdrstudio.encode.HevcBitstream;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test helpers: split an Annex-B HEVC elementary stream into NAL units / access units and re-frame them. */
final class StreamUtil {
    private StreamUtil() {}

    static int type(byte[] nal) { return (nal[0] >> 1) & 0x3F; }
    static boolean vcl(byte[] nal) { return type(nal) < 32; }

    /** NAL units (header + payload) of an Annex-B buffer. */
    static List<byte[]> nals(byte[] b) {
        List<byte[]> out = new ArrayList<>();
        int i = 0, start = -1;
        while (i + 2 < b.length) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                if (start >= 0) { int e = i; while (e > start && b[e - 1] == 0) e--; out.add(Arrays.copyOfRange(b, start, e)); }
                i += 3; start = i;
            } else i++;
        }
        if (start >= 0) out.add(Arrays.copyOfRange(b, start, b.length));
        return out;
    }

    /** Groups NAL units into access units (H.265 7.4.2.4.4: a new AU starts at AUD/VPS/SPS/PPS/prefix SEI or a first slice after a VCL). */
    static List<List<byte[]>> accessUnits(List<byte[]> nals) {
        List<List<byte[]>> aus = new ArrayList<>();
        List<byte[]> cur = new ArrayList<>();
        boolean seenVcl = false;
        for (byte[] n : nals) {
            int t = type(n);
            boolean firstSlice = t < 32 && (n[2] & 0x80) != 0;
            boolean startsAu = (t >= 32 && t <= 35) || t == 39 || (t >= 41 && t <= 44) || (t >= 48 && t <= 55) || firstSlice;
            if (startsAu && seenVcl) { aus.add(cur); cur = new ArrayList<>(); seenVcl = false; }
            cur.add(n);
            if (t < 32) seenVcl = true;
        }
        if (!cur.isEmpty()) aus.add(cur);
        return aus;
    }

    static byte[] annexB(List<byte[]> nals) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] n : nals) { o.write(0); o.write(0); o.write(0); o.write(1); o.write(n, 0, n.length); }
        return o.toByteArray();
    }

    static byte[] lengthPrefixed(List<byte[]> nals) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] n : nals) { int l = n.length; o.write(l >>> 24); o.write(l >>> 16); o.write(l >>> 8); o.write(l); o.write(n, 0, l); }
        return o.toByteArray();
    }

    /** NAL units of one framed access unit (either framing). */
    static List<byte[]> unframe(byte[] au) {
        if (!HevcBitstream.isLengthPrefixed(au)) return nals(au);
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i + 4 <= au.length) {
            int n = ((au[i] & 0xFF) << 24) | ((au[i + 1] & 0xFF) << 16) | ((au[i + 2] & 0xFF) << 8) | (au[i + 3] & 0xFF);
            out.add(Arrays.copyOfRange(au, i + 4, i + 4 + n));
            i += 4 + n;
        }
        return out;
    }

    static List<HevcBitstream.SeiMessage> sei(byte[] nal) { return HevcBitstream.seiMessages(nal, 0, nal.length); }

    /** Rewrites prefix SEI NAL units of every AU: messages for which keep() is false are removed; empty SEI NALs are dropped. */
    interface Keep { boolean keep(HevcBitstream.SeiMessage m); }

    static List<List<byte[]>> filterSei(List<List<byte[]>> aus, Keep k) {
        List<List<byte[]>> out = new ArrayList<>();
        for (List<byte[]> au : aus) {
            List<byte[]> nu = new ArrayList<>();
            for (byte[] n : au) {
                if (type(n) != 39) { nu.add(n); continue; }
                List<HevcBitstream.SeiMessage> keep = new ArrayList<>();
                for (HevcBitstream.SeiMessage m : sei(n)) if (k.keep(m)) keep.add(m);
                if (!keep.isEmpty()) nu.add(HevcBitstream.prefixSeiNal(keep));
            }
            out.add(nu);
        }
        return out;
    }

    static int count(List<byte[]> au, int seiType) {
        int c = 0;
        for (byte[] n : au) if (type(n) == 39) for (HevcBitstream.SeiMessage m : sei(n)) if (m.type == seiType) c++;
        return c;
    }

    static int firstVclIndex(List<byte[]> au) { for (int i = 0; i < au.size(); i++) if (vcl(au.get(i))) return i; return -1; }
    static boolean irap(List<byte[]> au) { int i = firstVclIndex(au); return i >= 0 && HevcBitstream.isIrapType(type(au.get(i))); }
}
