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
    // display (viewing only)
    public boolean displayHdr10, displayHlg, displayHdr10Plus, displayDv;
    public float displayMaxNits;
    public long memTotal, memAvail;

    public static DeviceCaps collect(Context ctx) {
        DeviceCaps d = new DeviceCaps();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(HEVC) && !t.equalsIgnoreCase(AVC)) continue;
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
        return d;
    }

    /** Must run on a thread without a current EGL context. */
    public void probeGl() {
        Egl egl = null;
        try {
            try { egl = new Egl(true); egl10bit = true; }
            catch (RuntimeException e) { egl = new Egl(false); egl10bit = false; }
            eglPq = egl.hasExtension("EGL_EXT_gl_colorspace_bt2020_pq");
            egl.makeCurrentOffscreen();
            String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            ext = ext == null ? "" : " " + ext + " ";
            yuvTarget = ext.contains(" GL_EXT_YUV_target ");
            colorBufferFloat = ext.contains(" GL_EXT_color_buffer_float ") || ext.contains(" GL_EXT_color_buffer_half_float ");
            externalEssl3 = ext.contains(" GL_OES_EGL_image_external_essl3 ");
            glRenderer = GLES20.glGetString(GLES20.GL_RENDERER) + " / " + GLES20.glGetString(GLES20.GL_VERSION);
            maxDrawBuffers = GlKit.getInt(GLES30.GL_MAX_DRAW_BUFFERS);
            maxTexSize = GlKit.getInt(GLES20.GL_MAX_TEXTURE_SIZE);
            maxArrayLayers = GlKit.getInt(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS);
            maxTexUnits = GlKit.getInt(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS);
            glProbed = true;
        } catch (RuntimeException e) {
            glError = e.getMessage();
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

    private static int score(Enc e, int w, int h, double fps) {
        return (e.hw ? 100 : 0) + (e.swOnly ? -50 : 0) + (e.supports(w, h, fps) ? 20 : 0) + (e.hdr10Profile ? 5 : 0) + (e.hdrEditing ? 5 : 0);
    }

    public String exportReport() {
        StringBuilder b = new StringBuilder();
        b.append("الجهاز: ").append(model).append(" · Android API ").append(sdk).append('\n');
        Enc h = pickHdrEncoder(1920, 1080, 30);
        b.append("مرمّز HDR (HEVC 10-bit): ").append(h == null ? "غير متوفر" : h.describe()).append('\n');
        if (glProbed) {
            b.append("GPU: ").append(glRenderer).append('\n');
            b.append("سطح 10-bit: ").append(yes(egl10bit)).append(" · وسم BT.2020 PQ (EGL): ").append(yes(eglPq))
                    .append(" · EXT_YUV_target: ").append(yes(yuvTarget)).append('\n');
            b.append("حساب FP16: ").append(yes(colorBufferFloat)).append(" · MRT: ").append(maxDrawBuffers)
                    .append(" · أقصى نسيج: ").append(maxTexSize).append('\n');
        } else if (glError != null) b.append("فحص GPU فشل: ").append(glError).append('\n');
        b.append("الذاكرة: ").append(memTotal / (1 << 20)).append(" MB (متاح ").append(memAvail / (1 << 20)).append(" MB)");
        return b.toString();
    }

    public String displayReport() {
        return "عرض HDR على هذه الشاشة: HDR10 " + yes(displayHdr10) + " · HLG " + yes(displayHlg) + " · HDR10+ " + yes(displayHdr10Plus)
                + (displayMaxNits > 0 ? String.format(Locale.US, " · ذروة ~%.0f nit", displayMaxNits) : "")
                + "\n(قدرة الشاشة على العرض منفصلة عن قدرة الجهاز على تصدير ملف HDR)";
    }

    private static String yes(boolean b) { return b ? "نعم" : "لا"; }
}
