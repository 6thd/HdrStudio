package com.mojahed.hdrstudio.encode;

import java.util.Locale;

/**
 * Hard checks of an exported HDR10 file's bitstream, kept apart from each other:
 *  - signalling: SPS bit depth 10/10, VUI BT.2020 / PQ / BT.2020 NCL / limited;
 *  - final metadata: the SEI in the file carries exactly the values this export configured
 *    (mastering peak; MaxCLL/MaxFALL as measured, or 0/0 = unknown when not measured), and every
 *    IRAP access unit carries both messages.
 * Pure Java (unit-tested on the JVM against x265 streams).
 */
public final class Hdr10FileCheck {
    private Hdr10FileCheck() {}

    /** Walks every access unit of the file: each IRAP must carry SEI 137 and 144 equal to the configured payloads. */
    public static final class IrapScan {
        private final byte[] mdcv, cll;
        public long accessUnits, iraps, missing, mismatched, unparsable;
        public String firstProblem;

        public IrapScan(HdrMetadata meta) { mdcv = meta.mdcvPayload(); cll = meta.cllPayload(); }

        public void add(byte[] au) {
            accessUnits++;
            java.util.List<HevcBitstream.NalRef> nals = HevcBitstream.prefixNals(au);
            if (nals == null) { unparsable++; note("وحدة وصول " + accessUnits + " بلا NAL صورة"); return; }
            boolean m = false, c = false, bad = false;
            for (HevcBitstream.NalRef n : nals) {
                if (n.type != 39) continue;
                try {
                    for (HevcBitstream.SeiMessage s : HevcBitstream.seiMessages(au, n.header, n.end)) {
                        if (s.type == HdrSeiGuard.MDCV) { m = true; bad |= !java.util.Arrays.equals(s.payload, mdcv); }
                        else if (s.type == HdrSeiGuard.CLL) { c = true; bad |= !java.util.Arrays.equals(s.payload, cll); }
                    }
                } catch (RuntimeException e) { unparsable++; note("SEI تالفة في وحدة الوصول " + accessUnits); return; }
            }
            if (bad) { mismatched++; note("رسالة SEI مختلفة عن القيم النهائية في وحدة الوصول " + accessUnits); }
            if (!HevcBitstream.isIrapType(nals.get(nals.size() - 1).type)) return;
            iraps++;
            if (!m || !c) { missing++; note("إطار مفتاحي في وحدة الوصول " + accessUnits + " بلا " + (!m ? "SEI 137" : "SEI 144")); }
        }

        private void note(String s) { if (firstProblem == null) firstProblem = s; }

        public boolean ok() { return iraps > 0 && missing == 0 && mismatched == 0 && unparsable == 0; }
    }

    public static Check signalling(HevcBitstream.Info bi) {
        String what = "البتستريم (SPS/VUI)";
        if (bi == null || !bi.spsFound) return Check.fail(what, "تعذر قراءة SPS" + (bi != null && bi.error != null ? " (" + bi.error + ")" : ""));
        if (bi.bitDepthLuma != 10 || bi.bitDepthChroma != 10) return Check.fail(what, "ليس 10-bit (" + bi.bitDepthLuma + "/" + bi.bitDepthChroma + ")");
        if (!bi.colourDescription || bi.colourPrimaries != 9 || bi.transfer != 16 || bi.matrix != 9 || bi.fullRange)
            return Check.fail(what, String.format(Locale.US, "وصف الألوان في VUI ليس BT.2020/PQ/Limited (%d/%d/%d %s)", bi.colourPrimaries, bi.transfer, bi.matrix, bi.fullRange ? "full" : "limited"));
        return Check.pass(what, "10-bit · BT.2020 · PQ · Limited (حقيقة عن الإشارة، لا عن جودة الصورة)");
    }

    /**
     * @param bi   parse of csd + first access units
     * @param meta metadata configured for this export
     * @param scan whole-file walk (null = not scanned)
     */
    public static Check finalMetadata(HevcBitstream.Info bi, HdrMetadata meta, IrapScan scan) {
        String what = "بيانات HDR الثابتة النهائية في الملف";
        if (bi == null || !bi.mdcvFound) return Check.fail(what, "رسالة mastering (SEI 137) غير موجودة");
        if (meta == null) return Check.fail(what, "لا توجد بيانات مضبوطة للمقارنة");
        long wantMax = Math.round(meta.masterPeakNits * 10000.0), wantMin = Math.round(meta.masterMinNits * 10000.0);
        if (bi.mdcvMaxLum != wantMax || bi.mdcvMinLum != wantMin)
            return Check.fail(what, String.format(Locale.US, "سطوع mastering في الملف %d/%d بدلاً من %d/%d (0.0001 nit)", bi.mdcvMaxLum, bi.mdcvMinLum, wantMax, wantMin));
        byte[] p = meta.mdcvPayload();
        for (int k = 0; k < 6; k++)
            if (bi.mdcvPrimaries[k] != HdrSeiGuard.u16(p, 2 * k)) return Check.fail(what, "أوليات mastering في الملف لا تطابق المضبوط");
        if (bi.mdcvWhiteX != HdrSeiGuard.u16(p, 12) || bi.mdcvWhiteY != HdrSeiGuard.u16(p, 14)) return Check.fail(what, "نقطة البياض لا تطابق المضبوط");
        String cll;
        if (meta.cllMeasured) {
            if (!bi.cllFound) return Check.fail(what, "قيم MaxCLL/MaxFALL المقاسة لم تُكتب");
            if (bi.maxCll != meta.maxCll || bi.maxFall != meta.maxFall)
                return Check.fail(what, "MaxCLL/MaxFALL في الملف " + bi.maxCll + "/" + bi.maxFall + " بدلاً من المقاس " + meta.maxCll + "/" + meta.maxFall);
            cll = "MaxCLL/MaxFALL " + bi.maxCll + "/" + bi.maxFall + " مقاسة من سلسلة الإخراج النهائية";
        } else {
            if (bi.cllFound && (bi.maxCll != 0 || bi.maxFall != 0))
                return Check.fail(what, "الملف يحمل MaxCLL/MaxFALL غير مقاسة (" + bi.maxCll + "/" + bi.maxFall + ")");
            cll = "MaxCLL/MaxFALL = 0/0 (غير معروف، لم يُقس)";
        }
        if (scan != null && !scan.ok())
            return Check.fail(what, String.format(Locale.US, "فحص الملف كاملاً: %d إطاراً مفتاحياً، %d بلا الرسالتين، %d رسالة مختلفة، %d غير مقروءة%s",
                    scan.iraps, scan.missing, scan.mismatched, scan.unparsable, scan.firstProblem != null ? " (" + scan.firstProblem + ")" : ""));
        return Check.pass(what, String.format(Locale.US, "mastering %.0f nit · %s%s", bi.mdcvMaxLum / 10000.0, cll,
                scan != null ? " · موجودة ومتطابقة في كل الإطارات المفتاحية (" + scan.iraps + ")" : ""));
    }
}
