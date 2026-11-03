package com.mojahed.hdrstudio.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * EGL setup. One GLES 3 context per job. The config is 10-bit (RGBA1010102) for HDR10 output
 * and 8-bit for SDR output, always EGL_RECORDABLE_ANDROID so it can feed a MediaCodec surface.
 *
 * HDR window surfaces request EGL_GL_COLORSPACE_BT2020_PQ_EXT. That attribute only tags the
 * buffers' dataspace (the shader already writes PQ code values). If it is unavailable we do
 * NOT silently fall back: {@link #createWindowSurface} reports it, and HDR export is only
 * allowed when the on-device self test proves the resulting file is correct.
 */
public final class Egl {
    public static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    public static final int EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x40;

    public final EGLDisplay display;
    public final EGLConfig config;
    public final EGLContext context;
    public final boolean tenBit;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;

    public Egl(boolean tenBit) {
        this.tenBit = tenBit;
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new GlException("eglInitialize failed");
        int bits = tenBit ? 10 : 8;
        int[] attr = {
                EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits,
                EGL14.EGL_ALPHA_SIZE, tenBit ? 2 : 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[8];
        int[] num = new int[1];
        EGL14.eglChooseConfig(display, attr, 0, cfgs, 0, cfgs.length, num, 0);
        EGLConfig chosen = null;
        // eglChooseConfig returns configs with >= the requested sizes; pick an exact match.
        for (int i = 0; i < num[0]; i++) {
            int[] r = new int[1], a = new int[1];
            EGL14.eglGetConfigAttrib(display, cfgs[i], EGL14.EGL_RED_SIZE, r, 0);
            EGL14.eglGetConfigAttrib(display, cfgs[i], EGL14.EGL_ALPHA_SIZE, a, 0);
            if (r[0] == bits && a[0] == (tenBit ? 2 : 8)) { chosen = cfgs[i]; break; }
        }
        if (chosen == null) throw new GlException(tenBit ? "no recordable RGBA1010102 EGL config (10-bit output unsupported)" : "no recordable RGBA8888 EGL config");
        config = chosen;
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new GlException("eglCreateContext (GLES 3) failed");
    }

    public boolean hasExtension(String ext) {
        String s = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
        return s != null && (" " + s + " ").contains(" " + ext + " ");
    }

    /** Result of creating the encoder input surface. */
    public static final class WindowSurface {
        public final EGLSurface surface; public final boolean pqTagged;
        WindowSurface(EGLSurface s, boolean pq) { surface = s; pqTagged = pq; }
    }

    /** @param wantPq request BT.2020 PQ dataspace; pqTagged tells whether the driver accepted it. */
    public WindowSurface createWindowSurface(Surface target, boolean wantPq) {
        if (wantPq && hasExtension("EGL_EXT_gl_colorspace_bt2020_pq")) {
            EGLSurface s = EGL14.eglCreateWindowSurface(display, config, target,
                    new int[]{EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE}, 0);
            if (s != null && s != EGL14.EGL_NO_SURFACE) return new WindowSurface(s, true);
        }
        EGLSurface s = EGL14.eglCreateWindowSurface(display, config, target, new int[]{EGL14.EGL_NONE}, 0);
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw new GlException("eglCreateWindowSurface failed 0x" + Integer.toHexString(EGL14.eglGetError()));
        return new WindowSurface(s, false);
    }

    public void makeCurrent(EGLSurface s) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) throw new GlException("eglMakeCurrent failed 0x" + Integer.toHexString(EGL14.eglGetError()));
    }

    /** Make current on a 1x1 pbuffer (offscreen work: previews, tests, analysis passes). */
    public void makeCurrentOffscreen() {
        if (pbuffer == EGL14.EGL_NO_SURFACE)
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        makeCurrent(pbuffer);
    }

    public void swap(EGLSurface s, long ptsNs) {
        EGLExt.eglPresentationTimeANDROID(display, s, ptsNs);
        if (!EGL14.eglSwapBuffers(display, s)) throw new GlException("eglSwapBuffers failed 0x" + Integer.toHexString(EGL14.eglGetError()));
    }

    public void destroySurface(EGLSurface s) {
        if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s);
    }

    public void release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        destroySurface(pbuffer);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        // no eglTerminate: the default display is shared with the UI renderer in this process
    }
}
