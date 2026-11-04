package com.mojahed.hdrstudio.encode;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Range;
import android.view.Surface;

import com.mojahed.hdrstudio.core.DeviceCaps;

import java.util.Locale;

/**
 * Diagnostic only: which MediaFormat key makes the chosen encoder refuse configure().
 * Starts from a minimal Surface-input format the encoder should accept, then adds the export's keys
 * one at a time (float frame rate, bitrate mode, profile, level, colour tags), each attempt with a new
 * codec instance that is released before the next. Then isolated variants: 30 fps instead of the
 * source rate, the export format with a level, and the same format in landscape. Nothing here changes
 * the export or the self test; it reports.
 */
public final class EncoderProbe {
    private EncoderProbe() {}

    private interface Edit { void apply(MediaFormat f); }

    public static String run(DeviceCaps.Enc enc, EncodeConfig c) {
        StringBuilder r = new StringBuilder();
        r.append(String.format(Locale.US, "تشخيص تهيئة المرمّز %s · %s %dx%d · %s fps · %.2f Mbps · وضع %d · profile %d\n",
                enc.name, c.mime, c.width, c.height, c.fps.label(), c.bitrate / 1e6, c.bitrateMode, c.profile));
        r.append(capabilities(enc, c));
        final double fps = c.fps.fps();
        final int level = maxLevel(enc, c.profile);
        Edit fpsFloat = new Edit() { public void apply(MediaFormat f) { f.setFloat(MediaFormat.KEY_FRAME_RATE, (float) fps); } };
        Edit mode = new Edit() { public void apply(MediaFormat f) { if (c.bitrateMode != EncodeConfig.MODE_DEFAULT) f.setInteger(MediaFormat.KEY_BITRATE_MODE, c.bitrateMode); } };
        Edit profile = new Edit() { public void apply(MediaFormat f) { if (c.profile >= 0) f.setInteger(MediaFormat.KEY_PROFILE, c.profile); } };
        Edit lvl = new Edit() { public void apply(MediaFormat f) { if (level > 0) f.setInteger(MediaFormat.KEY_LEVEL, level); } };
        Edit tags = new Edit() { public void apply(MediaFormat f) {
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, c.colorStandard);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, c.colorTransfer);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, c.colorRange);
        } };
        Edit iFloat = new Edit() { public void apply(MediaFormat f) { f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, c.iFrameSec); } };

        r.append("السلّم (كل خطوة تضيف مفتاحاً إلى السابقة):\n");
        String[] names = {
                "1 أساسي: Surface · معدل البت · 30 fps صحيح · فاصل I صحيح 1",
                "2 + معدل الإطارات الفعلي " + c.fps.label() + " كعدد عشري، وفاصل I عشري",
                "3 + وضع معدل البت " + c.bitrateMode,
                "4 + profile " + c.profile + " (بلا level)",
                "5 + وسوم الألوان standard " + c.colorStandard + " transfer " + c.colorTransfer + " range " + c.colorRange + " (= صيغة التصدير)",
        };
        Edit[][] steps = {
                {},
                {fpsFloat, iFloat},
                {fpsFloat, iFloat, mode},
                {fpsFloat, iFloat, mode, profile},
                {fpsFloat, iFloat, mode, profile, tags},
        };
        int firstFail = -1;
        for (int i = 0; i < steps.length; i++) {
            String res = attempt(enc.name, c.mime, c.width, c.height, c.bitrate, steps[i]);
            if (res != null && firstFail < 0) firstFail = i;
            r.append("  ").append(names[i]).append(": ").append(res == null ? "قُبل" : "رُفض — " + res).append('\n');
        }
        r.append(firstFail < 0 ? "  كل خطوات السلّم قُبلت.\n" : "  أول خطوة رُفضت: " + names[firstFail] + "\n");

        r.append("عزل (كل سطر مستقل):\n");
        iso(r, "صيغة التصدير لكن 30 fps", enc, c, c.width, c.height,
                new Edit[]{new Edit() { public void apply(MediaFormat f) { f.setFloat(MediaFormat.KEY_FRAME_RATE, 30f); } }, iFloat, mode, profile, tags});
        iso(r, "صيغة التصدير + level " + level + (level > 0 ? "" : " (لا level معلن لهذا profile)"), enc, c, c.width, c.height,
                new Edit[]{fpsFloat, iFloat, mode, profile, lvl, tags});
        iso(r, "صيغة التصدير بلا profile", enc, c, c.width, c.height, new Edit[]{fpsFloat, iFloat, mode, tags});
        iso(r, "صيغة التصدير بلا وضع معدل البت", enc, c, c.width, c.height, new Edit[]{fpsFloat, iFloat, profile, tags});
        iso(r, "صيغة التصدير أفقياً " + c.height + "x" + c.width, enc, c, c.height, c.width, new Edit[]{fpsFloat, iFloat, mode, profile, tags});
        r.append("لم يتغير إعداد التصدير ولا توقيت الفيديو. هذا تشخيص فقط.\n");
        return r.toString();
    }

    private static void iso(StringBuilder r, String name, DeviceCaps.Enc enc, EncodeConfig c, int w, int h, Edit[] edits) {
        String res = attempt(enc.name, c.mime, w, h, c.bitrate, edits);
        r.append("  ").append(name).append(": ").append(res == null ? "قُبل" : "رُفض — " + res).append('\n');
    }

    /** configure + createInputSurface + start on a fresh instance, then release. null = accepted. */
    private static String attempt(String codecName, String mime, int w, int h, int bitrate, Edit[] edits) {
        MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        for (Edit e : edits) e.apply(f);
        MediaCodec codec = null;
        Surface s = null;
        String stage = "createByCodecName";
        try {
            codec = MediaCodec.createByCodecName(codecName);
            stage = "configure";
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            stage = "createInputSurface";
            s = codec.createInputSurface();
            stage = "start";
            codec.start();
            codec.stop();
            return null;
        } catch (Throwable t) {
            String d = t.getClass().getSimpleName();
            if (t instanceof MediaCodec.CodecException) {
                MediaCodec.CodecException ce = (MediaCodec.CodecException) t;
                d += String.format(Locale.US, " errorCode=%d (0x%08x)", ce.getErrorCode(), ce.getErrorCode());
            }
            return stage + ": " + d;
        } finally {
            if (s != null) s.release();
            if (codec != null) try { codec.release(); } catch (Throwable ignored) { }
        }
    }

    private static String capabilities(DeviceCaps.Enc enc, EncodeConfig c) {
        StringBuilder b = new StringBuilder("قدرات المرمّز:\n");
        try {
            MediaCodecInfo.VideoCapabilities v = enc.caps.getVideoCapabilities();
            b.append(String.format(Locale.US, "  العرض %s · الارتفاع %s · محاذاة %dx%d · معدل البت %s\n",
                    v.getSupportedWidths(), v.getSupportedHeights(), v.getWidthAlignment(), v.getHeightAlignment(), v.getBitrateRange()));
            b.append(String.format(Locale.US, "  الحجم %dx%d مدعوم: %s · المقلوب %dx%d: %s\n", c.width, c.height, v.isSizeSupported(c.width, c.height),
                    c.height, c.width, v.isSizeSupported(c.height, c.width)));
            if (v.isSizeSupported(c.width, c.height)) {
                Range<Double> fr = v.getSupportedFrameRatesFor(c.width, c.height);
                b.append(String.format(Locale.US, "  معدلات الإطارات لهذا الحجم %s · %s: %s · 30: %s\n", fr, c.fps.label(),
                        v.areSizeAndRateSupported(c.width, c.height, c.fps.fps()), v.areSizeAndRateSupported(c.width, c.height, 30)));
            }
            MediaCodecInfo.EncoderCapabilities e = enc.caps.getEncoderCapabilities();
            b.append(String.format(Locale.US, "  أوضاع معدل البت: CQ %s · VBR %s · CBR %s\n",
                    e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ),
                    e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
                    e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)));
            StringBuilder pl = new StringBuilder();
            for (MediaCodecInfo.CodecProfileLevel p : enc.caps.profileLevels) pl.append(' ').append(p.profile).append('/').append(p.level);
            b.append("  profile/level المعلنة:").append(pl.length() == 0 ? " لا شيء" : pl.toString()).append('\n');
            boolean surface = false;
            for (int cf : enc.caps.colorFormats) surface |= cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
            b.append("  مدخل Surface معلن: ").append(surface).append('\n');
        } catch (Throwable t) {
            b.append("  تعذر قراءة القدرات: ").append(t).append('\n');
        }
        return b.toString();
    }

    /** The highest level the encoder lists for this profile, or 0. */
    static int maxLevel(DeviceCaps.Enc enc, int profile) {
        int best = 0;
        if (profile < 0 || enc.caps == null) return 0;
        for (MediaCodecInfo.CodecProfileLevel p : enc.caps.profileLevels) if (p.profile == profile) best = Math.max(best, p.level);
        return best;
    }
}
