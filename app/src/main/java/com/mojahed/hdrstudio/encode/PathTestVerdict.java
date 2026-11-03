package com.mojahed.hdrstudio.encode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Verdicts of the export path test, one per property, kept apart:
 *   colour accuracy, container tags, decoder-reported tags, bitstream signalling (SPS/VUI),
 *   metadata transport (SEI 137/144 with SAMPLE values), gradation precision (self-test ramp).
 * An HDR10 export is allowed only when colour, container tags, bitstream and metadata transport
 * PASS, decoder tags did not FAIL, and gradation either PASSED or was INCONCLUSIVE because the
 * decoder offers no P010 output. A decoder error or timeout during the gradation measurement is a
 * FAIL (the measurement did not happen), never a partial success.
 * Pure Java (unit-tested on the JVM).
 */
public final class PathTestVerdict {
    public enum PixelProbe { MEASURED, UNSUPPORTED, ERROR, TIMEOUT, NOT_RUN }

    public boolean hdr;
    public PixelProbe gradationProbe = PixelProbe.NOT_RUN;
    public Check colour = Check.fail("دقة الألوان", "لم يُقس"),
            containerTags = Check.fail("وسوم الحاوية", "لم تُقرأ"),
            decoderTags = Check.inconclusive("وسوم المفكك (من البتستريم فقط)", "لم تُقرأ"),
            bitstream = Check.fail("البتستريم (عمق البت + VUI)", "لم يُحلَّل"),
            metadataTransport = Check.fail("نقل بيانات HDR الثابتة (قيم نموذجية)", "لم يُفحص"),
            gradation = Check.inconclusive("دقة التدرج 10-bit (تدرج الفحص)", "لم يُقس");

    /** Gradation verdict from the ramp measurement. */
    public static Check gradation(PixelProbe status, int coverage90, String note) {
        String what = "دقة التدرج 10-bit (تدرج الفحص)";
        switch (status) {
            case MEASURED:
                boolean ok = PrecisionMetric.proves10bit(coverage90);
                return Check.of(ok, what, String.format(Locale.US, "تغطية 90%% بـ %d رمزاً، الحد ≥ %d (معاير على x265؛ اختبار عملي لا برهان عام)",
                        coverage90, PrecisionMetric.MIN_COVERAGE90_FOR_10BIT));
            case UNSUPPORTED:
                return Check.inconclusive(what, "المفكك لا يخرج P010 فلا يمكن القياس على هذا الجهاز " + note);
            case TIMEOUT:
                return Check.fail(what, "انتهت مهلة فك الترميز أثناء القياس — لم يحدث القياس، أعد الفحص " + note);
            case ERROR:
                return Check.fail(what, "خطأ في المفكك أثناء القياس — لم يحدث القياس، أعد الفحص " + note);
            default:
                return Check.inconclusive(what, "لم يُشغَّل " + note);
        }
    }

    /** Records the probe status and the check together so exportAllowed can tell P010-unsupported from NOT_RUN. */
    public void setGradation(PixelProbe status, int coverage90, String note) {
        gradationProbe = status;
        gradation = gradation(status, coverage90, note);
    }

    /** Only "no P010 output" is an allowed INCONCLUSIVE; NOT_RUN is not. */
    public boolean gradationUnmeasurable() { return gradationProbe == PixelProbe.UNSUPPORTED; }

    /**
     * EGL never became current, so colour/tags/bitstream/gradation were not measured.
     * INCONCLUSIVE + this wording — not FAIL, which would look like a failed measurement.
     */
    public void markNotRunBecauseEgl(String eglDetail) {
        String why = "لم تُنفّذ بسبب فشل تهيئة EGL" + (eglDetail == null || eglDetail.isEmpty() ? "" : " — " + firstLine(eglDetail));
        colour = Check.inconclusive("دقة الألوان", why);
        containerTags = Check.inconclusive("وسوم الحاوية", why);
        decoderTags = Check.inconclusive("وسوم المفكك (من البتستريم فقط)", why);
        if (hdr) {
            bitstream = Check.inconclusive("البتستريم (عمق البت + VUI)", why);
            metadataTransport = Check.inconclusive("نقل بيانات HDR الثابتة (قيم نموذجية)", why);
            setGradation(PixelProbe.NOT_RUN, -1, why);
        } else {
            setGradation(PixelProbe.NOT_RUN, -1, why);
        }
    }

    private static String firstLine(String s) {
        int n = s.indexOf('\n');
        String line = n < 0 ? s : s.substring(0, n);
        return line.length() > 240 ? line.substring(0, 240) : line;
    }

    public boolean exportAllowed() {
        if (!colour.passed() || !containerTags.passed() || decoderTags.failed()) return false;
        if (!hdr) return decoderTags.passed();          // SDR (also H.264): no independent parser -> decoder evidence needed
        return bitstream.passed() && metadataTransport.passed() && (gradation.passed() || gradationProbe == PixelProbe.UNSUPPORTED);
    }

    public List<Check> all() {
        List<Check> l = new ArrayList<>();
        l.add(colour); l.add(containerTags); l.add(decoderTags);
        if (hdr) { l.add(bitstream); l.add(metadataTransport); l.add(gradation); }
        return l;
    }

    /** What the user must know about the limits of this result. */
    public List<String> limitations() {
        List<String> l = new ArrayList<>();
        if (colour.detail.contains("لم تُنفّذ بسبب فشل تهيئة EGL")) {
            l.add("قياسات الألوان والوسوم والبتستريم والتدرج لم تُشغَّل: فشلت تهيئة EGL قبل الترميز. ليست نتائج فحص فاشلة.");
            return l;
        }
        if (hdr) {
            l.add("قيم MaxCLL/MaxFALL وقمة العرض في هذا الفحص نموذجية؛ القيم النهائية تُتحقق في الملف المصدَّر نفسه.");
            if (gradation.passed()) l.add("اجتاز اختبار دقة التدرج بهذه الإعدادات (ليس إثباتاً عاماً لكل محتوى أو مرمّز).");
            else if (gradationUnmeasurable()) l.add("دقة التدرج 10-bit لم تُقس على هذا الجهاز؛ التحقق يقتصر على البتستريم والألوان والوسوم والبيانات.");
            l.add("عمق 10-bit في SPS حقيقة عن الملف، ولا يقول شيئاً عن جودة التفاصيل أو عن مدى ديناميكي لم يكن في المصدر.");
        }
        return l;
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        for (Check c : all()) b.append(c.line()).append('\n');
        b.append(exportAllowed() ? (hdr ? "النتيجة: تصدير HDR10 مسموح بهذا الإعداد." : "النتيجة: تصدير SDR مسموح بهذا الإعداد.")
                : (hdr ? "النتيجة: تصدير HDR10 ممنوع بهذا الإعداد." : "النتيجة: التصدير ممنوع بهذا الإعداد.")).append('\n');
        for (String s : limitations()) b.append("ملاحظة: ").append(s).append('\n');
        return b.toString().trim();
    }

    public String toJson() {
        StringBuilder b = new StringBuilder("{\"hdr\":").append(hdr).append(",\"exportAllowed\":").append(exportAllowed()).append(",\"checks\":[");
        List<Check> a = all();
        for (int i = 0; i < a.size(); i++) b.append(i > 0 ? "," : "").append(a.get(i).toJson());
        return b.append("]}").toString();
    }
}
