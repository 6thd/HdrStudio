package com.mojahed.hdrstudio.core;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Build;
import android.view.Display;

import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.EglDiag;
import com.mojahed.hdrstudio.gl.GlKit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Device capability report. Export capability (encoders, EGL/GL features) is kept separate from
 * display capability (whether this screen can show HDR) - they are unrelated.
 */
public final class DeviceCaps {
    public static final String HEVC = "video/hevc", AVC = "video/avc";

    public static final class Enc {
        public String name, mime;
        public boolean hw, swOnly, main10, hdr10Profile, hdr10PlusProfile, hdrEditing;
        public int wAlign = 2, hAlign = 2;
        public MediaCodecInfo.CodecCapabilities caps;

        public boolean supports(int w, int h, double fps) {
            try { return caps.getVideoCapabilities().areSizeAndRateSupported(w, h, fps); }
            catch (RuntimeException e) { return false; }
        }
        public boolean supportsSize(int w, int h) {
            try { return caps.getVideoCapabilities().isSizeSupported(w, h); } catch (RuntimeException e) { return false; }
        }
        public int clampBitrate(long b) {
            return caps.getVideoCapabilities().getBitrateRange().clamp((int) Math.min(Integer.MAX_VALUE, b));
        }
        public String describe() {
            return name + (hw ? " (عتاد)" : swOnly ? " (برمجي)" : "") + (main10 ? " · Main10" : "") +
                    (hdr10Profile ? " · HDR10" : "") + (hdr10PlusProfile ? " · HDR10+" : "") + (hdrEditing ? " · hdr-editing" : "");
        }
    }

    public int sdk = Build.VERSION.SDK_INT;
    public String model = Build.MANUFACTURER + " " + Build.MODEL;
    public final List<Enc> encoders = new ArrayList<>();
    // GL / EGL (filled by probeGl on a worker thread)
    public boolean glProbed, egl10bit, eglPq, yuvTarget, colorBufferFloat, externalEssl3;
    public int maxDrawBuffers, maxTexSize, maxArrayLayers, maxTexUnits;
    public String glRenderer = "", glError;
    public String eglReport = "";
    public String encoderFormatReport = "";
    public EglDiag.Offscreen eglOffscreen = EglDiag.Offscreen.NONE;
    // display (viewing only)
    public boolean displayHdr10, displayHlg, displayHdr10Plus, displayDv;
    public float displayMaxNits;
    public long memTotal, memAvail;

    public static DeviceCaps collect(Context ctx) {
        DeviceCaps d = new DeviceCaps();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (int kind : new int[]{MediaCodecList.REGULAR_CODECS, MediaCodecList.ALL_CODECS}) {
            for (MediaCodecInfo info : new MediaCodecList(kind).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(HEVC) && !t.equalsIgnoreCase(AVC)) continue;
                    String key = info.getName() + "|" + t.toLowerCase(Locale.US);
                    if (!seen.add(key)) continue;
                    Enc e = new Enc();
                    e.name = info.getName(); e.mime = t.toLowerCase(Locale.US);
                    e.hw = info.isHardwareAccelerated(); e.swOnly = info.isSoftwareOnly();
                    try { e.caps = info.getCapabilitiesForType(t); } catch (RuntimeException ex) { continue; }
                    for (MediaCodecInfo.CodecProfileLevel pl : e.caps.profileLevels) {
                        if (e.mime.equals(HEVC)) {
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) e.main10 = true;
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) { e.main10 = true; e.hdr10Profile = true; }
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) { e.main10 = true; e.hdr10PlusProfile = true; }
                        }
                    }
                    e.hdrEditing = e.caps.isFeatureSupported("hdr-editing");
                    e.wAlign = e.caps.getVideoCapabilities().getWidthAlignment();
                    e.hAlign = e.caps.getVideoCapabilities().getHeightAlignment();
                    d.encoders.add(e);
                }
            }
        }
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display disp = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (disp != null && disp.getHdrCapabilities() != null) {
            for (int t : disp.getHdrCapabilities().getSupportedHdrTypes()) {
                if (t == Display.HdrCapabilities.HDR_TYPE_HDR10) d.displayHdr10 = true;
                if (t == Display.HdrCapabilities.HDR_TYPE_HLG) d.displayHlg = true;
                if (t == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) d.displayHdr10Plus = true;
                if (t == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) d.displayDv = true;
            }
            d.displayMaxNits = disp.getHdrCapabilities().getDesiredMaxLuminance();
        }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        d.memTotal = mi.totalMem; d.memAvail = mi.availMem;
        d.encoderFormatReport = d.allHevcInputReport();
        return d;
    }

    /** Must run on a thread without a current EGL context. Never silently uses 8-bit for HDR. */
    public void probeGl() {
        Egl egl = null;
        try {
            EglDiag.Selection hdr = EglDiag.analyze(true);
            eglReport = hdr.report;
            eglOffscreen = hdr.offscreen;
            egl10bit = hdr.usableForHdrEncode();
            if (egl10bit) {
                egl = new Egl(hdr);
            } else {
                glError = "لا يوجد مسار RGB 10-bit قابل للتسجيل إلى المرمّز (انظر تعداد EGL أدناه). لم يُستخدم سطح 8-bit بديلاً.";
                // labeled 8-bit context only to read GL_RENDERER — not an HDR path
                try {
                    EglDiag.Selection sdr = EglDiag.analyze(false);
                    eglReport = eglReport + "\n" + sdr.report;
                    if (sdr.config != null && sdr.offscreen != EglDiag.Offscreen.NONE) {
                        egl = new Egl(sdr);
                        glRenderer = "(سياق 8-bit للقراءة فقط، ليس مسار HDR) ";
                    }
                } catch (RuntimeException ignored) { }
            }
            if (egl == null) return;
            eglPq = egl.hasExtension("EGL_EXT_gl_colorspace_bt2020_pq");
            egl.makeCurrentOffscreen();
            String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            ext = ext == null ? "" : " " + ext + " ";
            yuvTarget = ext.contains(" GL_EXT_YUV_target ");
            colorBufferFloat = ext.contains(" GL_EXT_color_buffer_float ") || ext.contains(" GL_EXT_color_buffer_half_float ");
            externalEssl3 = ext.contains(" GL_OES_EGL_image_external_essl3 ");
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER) + " / " + GLES20.glGetString(GLES20.GL_VERSION);
            glRenderer = (glRenderer == null ? "" : glRenderer) + renderer;
            maxDrawBuffers = GlKit.getInt(GLES30.GL_MAX_DRAW_BUFFERS);
            maxTexSize = GlKit.getInt(GLES20.GL_MAX_TEXTURE_SIZE);
            maxArrayLayers = GlKit.getInt(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS);
            maxTexUnits = GlKit.getInt(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS);
            glProbed = true;
        } catch (RuntimeException e) {
            glError = e.getMessage();
            if (eglReport == null || eglReport.isEmpty()) eglReport = e.getMessage();
        } finally {
            if (egl != null) egl.release();
        }
    }

    /** Best HEVC Main10 encoder for w x h @ fps (hardware first). */
    public Enc pickHdrEncoder(int w, int h, double fps) {
        Enc best = null;
        for (Enc e : encoders) {
            if (!e.mime.equals(HEVC) || !e.main10 || !e.supportsSize(w, h)) continue;
            if (best == null || score(e, w, h, fps) > score(best, w, h, fps)) best = e;
        }
        return best;
    }

    public Enc pickSdrEncoder(String mime, int w, int h, double fps) {
        Enc best = null;
        for (Enc e : encoders) {
            if (!e.mime.equals(mime) || !e.supportsSize(w, h)) continue;
            if (best == null || score(e, w, h, fps) > score(best, w, h, fps)) best = e;
        }
        return best;
    }

    /** Every HEVC encoder and its declared input color formats — not only the one pickHdrEncoder chose. */
    public String allHevcInputReport() {
        StringBuilder b = new StringBuilder();
        b.append("ALL HEVC encoders (REGULAR+ALL lists, declared capabilities, not proven):\n");
        int n = 0;
        Enc chosen = pickHdrEncoder(1920, 1080, 30);
        for (Enc e : encoders) {
            if (!HEVC.equals(e.mime)) continue;
            n++;
            boolean pick = chosen != null && e.name.equals(chosen.name);
            b.append("-- ").append(e.describe()).append(pick ? "  [chosen for 1080p30 HDR]" : "").append('\n');
            try {
                MediaCodecInfo.VideoCapabilities vc = e.caps.getVideoCapabilities();
                b.append("  size align ").append(e.wAlign).append("x").append(e.hAlign)
                        .append(" max ").append(vc.getSupportedWidths().getUpper()).append("x")
                        .append(vc.getSupportedHeights().getUpper())
                        .append(" bitrate ").append(vc.getBitrateRange().getLower()).append("–")
                        .append(vc.getBitrateRange().getUpper()).append('\n');
            } catch (RuntimeException ignored) { }
            if (e.caps.profileLevels != null) {
                b.append("  profiles:");
                for (MediaCodecInfo.CodecProfileLevel pl : e.caps.profileLevels) {
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) b.append(" Main");
                    else if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) b.append(" Main10");
                    else if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) b.append(" Main10HDR10");
                    else if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) b.append(" Main10HDR10Plus");
                    else b.append(" p").append(pl.profile);
                }
                b.append('\n');
            }
            b.append(EglDiag.encoderColorFormats(e));
        }
        if (n == 0) b.append("  (none)\n");
        return b.toString();
    }

    private static int score(Enc e, int w, int h, double fps) {
        return (e.hw ? 100 : 0) + (e.swOnly ? -50 : 0) + (e.supports(w, h, fps) ? 20 : 0) + (e.hdr10Profile ? 5 : 0) + (e.hdrEditing ? 5 : 0);
    }

    public String exportReport() {
        StringBuilder b = new StringBuilder();
        b.append("الجهاز: ").append(model).append(" · Android API ").append(sdk).append('\n');
        Enc h = pickHdrEncoder(1920, 1080, 30);
        b.append("مرمّز HDR المختار (HEVC 10-bit): ").append(h == null ? "غير متوفر" : h.describe()).append('\n');
        b.append("مسار HDR المعتمد: ").append(egl10bit ? "مفتوح (RECORDABLE 10/10/10/2)" : "مغلق (لا إعداد عالي الدقة معلن RECORDABLE)").append('\n');
        b.append("اختبار #24/#25 تجريبي ولا يفتح التصدير. تصدير SDR مستقل.\n");
        if (glError != null) b.append("HDR EGL: ").append(glError).append('\n');
        if (glProbed) {
            b.append("GPU: ").append(glRenderer).append('\n');
            b.append("سطح 10-bit قابل للتسجيل: ").append(yes(egl10bit)).append(" · offscreen: ").append(eglOffscreen)
                    .append(" · وسم BT.2020 PQ (EGL): ").append(yes(eglPq))
                    .append(" · EXT_YUV_target: ").append(yes(yuvTarget)).append('\n');
            b.append("حساب FP16: ").append(yes(colorBufferFloat)).append(" · MRT: ").append(maxDrawBuffers)
                    .append(" · أقصى نسيج: ").append(maxTexSize).append('\n');
        }
        b.append("الذاكرة: ").append(memTotal / (1 << 20)).append(" MB (متاح ").append(memAvail / (1 << 20)).append(" MB)\n");
        if (encoderFormatReport != null && !encoderFormatReport.isEmpty()) b.append(encoderFormatReport);
        if (eglReport != null && !eglReport.isEmpty()) b.append(eglReport);
        return b.toString().trim();
    }

    public String displayReport() {
        return "عرض HDR على هذه الشاشة: HDR10 " + yes(displayHdr10) + " · HLG " + yes(displayHlg) + " · HDR10+ " + yes(displayHdr10Plus)
                + (displayMaxNits > 0 ? String.format(Locale.US, " · ذروة ~%.0f nit", displayMaxNits) : "")
                + "\n(قدرة الشاشة على العرض منفصلة عن قدرة الجهاز على تصدير ملف HDR)";
    }

    private static String yes(boolean b) { return b ? "نعم" : "لا"; }
}
