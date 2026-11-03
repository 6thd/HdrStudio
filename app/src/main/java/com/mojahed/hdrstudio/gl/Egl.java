package com.mojahed.hdrstudio.gl;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * EGL setup. One GLES 3 context per job.
 * HDR uses an exact recordable RGBA1010102 WINDOW config. PBUFFER is no longer required on the
 * same config: if it is missing, offscreen work uses a verified surfaceless or dummy-window path.
 * 8-bit is never a silent HDR fallback.
 */
public final class Egl {
    public static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    public static final int EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340;

    public final EGLDisplay display;
    public final EGLConfig config;
    public final EGLContext context;
    public final boolean tenBit;
    public final EglDiag.Offscreen offscreenKind;
    public final String diagReport;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture dummyTex;
    private Surface dummyWin;
    private EGLSurface dummyEgl = EGL14.EGL_NO_SURFACE;

    public Egl(boolean tenBit) {
        this(EglDiag.analyze(tenBit));
    }

    public Egl(EglDiag.Selection sel) {
        this.tenBit = sel.tenBit;
        diagReport = sel.report;
        display = sel.display;
        if (display == null || display == EGL14.EGL_NO_DISPLAY)
            throw new GlException((tenBit ? "eglGetDisplay failed\n" : "eglGetDisplay failed\n") + diagReport);
        if (sel.config == null || !sel.exactRgba || !sel.recordable || !sel.windowBit) {
            throw new GlException((tenBit
                    ? "no recordable RGBA1010102 EGL WINDOW config (10-bit RGB to encoder unsupported on this device)\n"
                    : "no recordable RGBA8888 EGL WINDOW config\n") + diagReport);
        }
        if (sel.offscreen == EglDiag.Offscreen.NONE) {
            throw new GlException("recordable " + (tenBit ? "RGBA1010102" : "RGBA8888")
                    + " WINDOW exists but no offscreen path (PBUFFER/SURFACELESS/DUMMY_WINDOW all failed)\n" + diagReport);
        }
        config = sel.config;
        offscreenKind = sel.offscreen;
        EglDiag.takeError();
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT)
            throw new GlException("eglCreateContext (GLES 3) failed " + EglDiag.takeErrorLine("") + "\n" + diagReport);
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
            EglDiag.takeError();
            EGLSurface s = EGL14.eglCreateWindowSurface(display, config, target,
                    new int[]{EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE}, 0);
            int e = EglDiag.takeError();
            if (s != null && s != EGL14.EGL_NO_SURFACE) return new WindowSurface(s, true);
            if (e != EGL14.EGL_SUCCESS && e != EGL14.EGL_BAD_MATCH && e != EGL14.EGL_BAD_ATTRIBUTE) {
                throw new GlException("eglCreateWindowSurface PQ failed " + EglDiag.errName(e) + " (0x" + Integer.toHexString(e) + ")");
            }
        }
        EglDiag.takeError();
        EGLSurface s = EGL14.eglCreateWindowSurface(display, config, target, new int[]{EGL14.EGL_NONE}, 0);
        if (s == null || s == EGL14.EGL_NO_SURFACE)
            throw new GlException("eglCreateWindowSurface failed " + EglDiag.takeErrorLine(""));
        return new WindowSurface(s, false);
    }

    public void makeCurrent(EGLSurface s) {
        if (!EGL14.eglMakeCurrent(display, s, s, context))
            throw new GlException("eglMakeCurrent failed " + EglDiag.takeErrorLine(""));
    }

    /** Offscreen current: PBUFFER if this config has it, else a path proven by {@link EglDiag}. */
    public void makeCurrentOffscreen() {
        switch (offscreenKind) {
            case PBUFFER:
                if (pbuffer == EGL14.EGL_NO_SURFACE) {
                    EglDiag.takeError();
                    pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                            new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
                    if (pbuffer == null || pbuffer == EGL14.EGL_NO_SURFACE)
                        throw new GlException("eglCreatePbufferSurface failed " + EglDiag.takeErrorLine("") + "\n" + diagReport);
                }
                makeCurrent(pbuffer);
                return;
            case SURFACELESS:
                EglDiag.takeError();
                if (!EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context))
                    throw new GlException("surfaceless eglMakeCurrent failed " + EglDiag.takeErrorLine("") + "\n" + diagReport);
                return;
            case DUMMY_WINDOW:
                if (dummyEgl == EGL14.EGL_NO_SURFACE) {
                    dummyTex = new SurfaceTexture(false);
                    dummyTex.setDefaultBufferSize(16, 16);
                    dummyWin = new Surface(dummyTex);
                    EglDiag.takeError();
                    dummyEgl = EGL14.eglCreateWindowSurface(display, config, dummyWin, new int[]{EGL14.EGL_NONE}, 0);
                    if (dummyEgl == null || dummyEgl == EGL14.EGL_NO_SURFACE)
                        throw new GlException("dummy window eglCreateWindowSurface failed " + EglDiag.takeErrorLine("") + "\n" + diagReport);
                }
                makeCurrent(dummyEgl);
                return;
            default:
                throw new GlException("no offscreen EGL path\n" + diagReport);
        }
    }

    public void swap(EGLSurface s, long ptsNs) {
        EGLExt.eglPresentationTimeANDROID(display, s, ptsNs);
        if (!EGL14.eglSwapBuffers(display, s)) throw new GlException("eglSwapBuffers failed " + EglDiag.takeErrorLine(""));
    }

    public void destroySurface(EGLSurface s) {
        if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s);
    }

    public void release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        destroySurface(pbuffer);
        destroySurface(dummyEgl);
        dummyEgl = EGL14.EGL_NO_SURFACE;
        if (dummyWin != null) { dummyWin.release(); dummyWin = null; }
        if (dummyTex != null) { dummyTex.release(); dummyTex = null; }
        EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        // no eglTerminate: the default display is shared with the UI renderer in this process
    }
}
