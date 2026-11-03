package com.mojahed.hdrstudio.gl;

import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

import com.mojahed.hdrstudio.core.DeviceCaps;

import java.util.Locale;

/**
 * Phone diagnostic: enumerate every EGL config (not a cap of 8), query WINDOW / PBUFFER /
 * RECORDABLE separately, record eglGetError after each call, and try real offscreen paths.
 * Does not implement a YUV P010 encode path; it only reports whether the encoder declares it.
 */
public final class EglDiag {
    public static final int ES3 = 0x40;
    public static final int RECORDABLE = 0x3142;           // EGL_RECORDABLE_ANDROID
    public static final int COLOR_TYPE = 0x3339;           // EGL_COLOR_COMPONENT_TYPE_EXT
    public static final int COLOR_FIXED = 0x333A;
    public static final int COLOR_FLOAT = 0x333B;
    public static final String NOT_RUN_EGL = "لم تُنفّذ بسبب فشل تهيئة EGL";

    public enum Offscreen { NONE, PBUFFER, SURFACELESS, DUMMY_WINDOW }

    public static final class Selection {
        public EGLDisplay display;
        public EGLConfig config;
        public boolean tenBit;
        public boolean exactRgba;
        public boolean recordable;
        public boolean windowBit;
        public boolean pbufferBit;
        public Offscreen offscreen = Offscreen.NONE;
        public String report = "";
        public boolean usableForHdrEncode() {
            return tenBit && exactRgba && recordable && windowBit && offscreen != Offscreen.NONE;
        }
    }

    private EglDiag() {}

    public static String errName(int e) {
        switch (e) {
            case EGL14.EGL_SUCCESS: return "EGL_SUCCESS";
            case EGL14.EGL_NOT_INITIALIZED: return "EGL_NOT_INITIALIZED";
            case EGL14.EGL_BAD_ACCESS: return "EGL_BAD_ACCESS";
            case EGL14.EGL_BAD_ALLOC: return "EGL_BAD_ALLOC";
            case EGL14.EGL_BAD_ATTRIBUTE: return "EGL_BAD_ATTRIBUTE";
            case EGL14.EGL_BAD_CONFIG: return "EGL_BAD_CONFIG";
            case EGL14.EGL_BAD_CONTEXT: return "EGL_BAD_CONTEXT";
            case EGL14.EGL_BAD_CURRENT_SURFACE: return "EGL_BAD_CURRENT_SURFACE";
            case EGL14.EGL_BAD_DISPLAY: return "EGL_BAD_DISPLAY";
            case EGL14.EGL_BAD_MATCH: return "EGL_BAD_MATCH";
            case EGL14.EGL_BAD_NATIVE_PIXMAP: return "EGL_BAD_NATIVE_PIXMAP";
            case EGL14.EGL_BAD_NATIVE_WINDOW: return "EGL_BAD_NATIVE_WINDOW";
            case EGL14.EGL_BAD_PARAMETER: return "EGL_BAD_PARAMETER";
            case EGL14.EGL_BAD_SURFACE: return "EGL_BAD_SURFACE";
            case EGL14.EGL_CONTEXT_LOST: return "EGL_CONTEXT_LOST";
            default: return "EGL_0x" + Integer.toHexString(e);
        }
    }

    public static int takeError() { return EGL14.eglGetError(); }

    public static String takeErrorLine(String where) {
        int e = takeError();
        return where + " err=" + errName(e) + " (0x" + Integer.toHexString(e) + ")";
    }

    public static int get(EGLDisplay d, EGLConfig c, int attr) {
        int[] v = new int[1];
        if (!EGL14.eglGetConfigAttrib(d, c, attr, v, 0)) {
            takeError();
            return -1;
        }
        return v[0];
    }

    public static String configLine(EGLDisplay d, EGLConfig c) {
        int id = get(d, c, EGL14.EGL_CONFIG_ID);
        int r = get(d, c, EGL14.EGL_RED_SIZE), g = get(d, c, EGL14.EGL_GREEN_SIZE);
        int b = get(d, c, EGL14.EGL_BLUE_SIZE), a = get(d, c, EGL14.EGL_ALPHA_SIZE);
        int st = get(d, c, EGL14.EGL_SURFACE_TYPE);
        int rt = get(d, c, EGL14.EGL_RENDERABLE_TYPE);
        int rec = get(d, c, RECORDABLE);
        int ctype = get(d, c, COLOR_TYPE);
        int samples = get(d, c, EGL14.EGL_SAMPLES);
        StringBuilder surf = new StringBuilder();
        if ((st & EGL14.EGL_WINDOW_BIT) != 0) surf.append("WINDOW");
        if ((st & EGL14.EGL_PBUFFER_BIT) != 0) surf.append(surf.length() == 0 ? "PBUFFER" : "|PBUFFER");
        if ((st & EGL14.EGL_PIXMAP_BIT) != 0) surf.append(surf.length() == 0 ? "PIXMAP" : "|PIXMAP");
        if (surf.length() == 0) surf.append("0x").append(Integer.toHexString(st));
        String es = ((rt & ES3) != 0 ? "ES3" : "") + ((rt & EGL14.EGL_OPENGL_ES2_BIT) != 0 ? "+ES2" : "");
        String ct = ctype == COLOR_FLOAT ? "FLOAT" : ctype == COLOR_FIXED ? "FIXED" : ctype < 0 ? "?" : "0x" + Integer.toHexString(ctype);
        return String.format(Locale.US, "#%d rgba=%d/%d/%d/%d surf=%s rec=%s es=%s ctype=%s samples=%d",
                id, r, g, b, a, surf, rec < 0 ? "?" : String.valueOf(rec), es.isEmpty() ? ("0x" + Integer.toHexString(rt)) : es, ct, samples);
    }

    public static boolean exactRgba(EGLDisplay d, EGLConfig c, boolean tenBit) {
        int want = tenBit ? 10 : 8, a = tenBit ? 2 : 8;
        return get(d, c, EGL14.EGL_RED_SIZE) == want
                && get(d, c, EGL14.EGL_GREEN_SIZE) == want
                && get(d, c, EGL14.EGL_BLUE_SIZE) == want
                && get(d, c, EGL14.EGL_ALPHA_SIZE) == a
                && get(d, c, COLOR_TYPE) != COLOR_FLOAT;
    }

    private static int[] filter(boolean tenBit, int surfaceMask, int recordable) {
        int bits = tenBit ? 10 : 8;
        if (recordable >= 0) {
            return new int[]{
                    EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits,
                    EGL14.EGL_ALPHA_SIZE, tenBit ? 2 : 8,
                    EGL14.EGL_RENDERABLE_TYPE, ES3,
                    EGL14.EGL_SURFACE_TYPE, surfaceMask,
                    RECORDABLE, recordable,
                    EGL14.EGL_NONE};
        }
        return new int[]{
                EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits,
                EGL14.EGL_ALPHA_SIZE, tenBit ? 2 : 8,
                EGL14.EGL_RENDERABLE_TYPE, ES3,
                EGL14.EGL_SURFACE_TYPE, surfaceMask,
                EGL14.EGL_NONE};
    }

    public static EGLConfig[] choose(EGLDisplay d, int[] attr, StringBuilder log, String label) {
        takeError();
        int[] num = new int[1];
        boolean ok = EGL14.eglChooseConfig(d, attr, 0, null, 0, 0, num, 0);
        int e1 = takeError();
        log.append('[').append(label).append("] choose(count) ok=").append(ok)
                .append(" n=").append(num[0]).append(" err=").append(errName(e1))
                .append(" (0x").append(Integer.toHexString(e1)).append(")\n");
        if (!ok || num[0] <= 0) return new EGLConfig[0];
        EGLConfig[] cfgs = new EGLConfig[num[0]];
        takeError();
        ok = EGL14.eglChooseConfig(d, attr, 0, cfgs, 0, cfgs.length, num, 0);
        int e2 = takeError();
        log.append("  fetch ok=").append(ok).append(" n=").append(num[0])
                .append(" err=").append(errName(e2)).append(" (0x").append(Integer.toHexString(e2)).append(")\n");
        int n = Math.max(0, Math.min(num[0], cfgs.length));
        for (int i = 0; i < n; i++) log.append("  ").append(configLine(d, cfgs[i])).append('\n');
        EGLConfig[] out = new EGLConfig[n];
        System.arraycopy(cfgs, 0, out, 0, n);
        return out;
    }

    public static EGLConfig firstExact(EGLDisplay d, EGLConfig[] cfgs, boolean tenBit) {
        for (EGLConfig c : cfgs) if (exactRgba(d, c, tenBit)) return c;
        return null;
    }

    /** Full dump + which offscreen path actually works. Initializes the default display; does not terminate it. */
    public static Selection analyze(boolean tenBit) {
        Selection s = new Selection();
        s.tenBit = tenBit;
        StringBuilder log = new StringBuilder();
        log.append("=== EGL diagnostic ").append(tenBit ? "10-bit HDR" : "8-bit SDR").append(" ===\n");
        takeError();
        s.display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        log.append(takeErrorLine("eglGetDisplay")).append('\n');
        if (s.display == null || s.display == EGL14.EGL_NO_DISPLAY) {
            s.report = log.toString();
            return s;
        }
        int[] ver = new int[2];
        takeError();
        boolean init = EGL14.eglInitialize(s.display, ver, 0, ver, 1);
        log.append("eglInitialize ok=").append(init).append(" version=").append(ver[0]).append('.').append(ver[1])
                .append(" ").append(takeErrorLine("")).append('\n');
        String exts = EGL14.eglQueryString(s.display, EGL14.EGL_EXTENSIONS);
        log.append("EGL_EXTENSIONS: ").append(exts == null ? "(null)" : exts).append('\n');
        log.append("EGL_ANDROID_recordable: ").append(hasExt(exts, "EGL_ANDROID_recordable")).append('\n');
        log.append("EGL_KHR_surfaceless_context: ").append(hasExt(exts, "EGL_KHR_surfaceless_context")).append('\n');
        log.append("EGL_EXT_gl_colorspace_bt2020_pq: ").append(hasExt(exts, "EGL_EXT_gl_colorspace_bt2020_pq")).append('\n');

        takeError();
        int[] nAll = new int[1];
        EGL14.eglGetConfigs(s.display, null, 0, 0, nAll, 0);
        log.append(takeErrorLine("eglGetConfigs(count)")).append(" n=").append(nAll[0]).append('\n');
        EGLConfig[] all = new EGLConfig[Math.max(nAll[0], 1)];
        takeError();
        boolean got = EGL14.eglGetConfigs(s.display, all, 0, all.length, nAll, 0);
        log.append("eglGetConfigs fetch ok=").append(got).append(" n=").append(nAll[0]).append(" ")
                .append(takeErrorLine("")).append('\n');
        int n = Math.max(0, Math.min(nAll[0], all.length));
        log.append("ALL CONFIGS (").append(n).append("):\n");
        int exactWinRec = 0, exactPbuf = 0, exactWin = 0, exactRec = 0;
        for (int i = 0; i < n; i++) {
            log.append("  ").append(configLine(s.display, all[i])).append('\n');
            EGLConfig c = all[i];
            if (!exactRgba(s.display, c, tenBit)) continue;
            int st = get(s.display, c, EGL14.EGL_SURFACE_TYPE);
            int rec = get(s.display, c, RECORDABLE);
            boolean es3 = (get(s.display, c, EGL14.EGL_RENDERABLE_TYPE) & ES3) != 0;
            if (!es3) continue;
            if ((st & EGL14.EGL_WINDOW_BIT) != 0) exactWin++;
            if ((st & EGL14.EGL_PBUFFER_BIT) != 0) exactPbuf++;
            if (rec == 1) exactRec++;
            if ((st & EGL14.EGL_WINDOW_BIT) != 0 && rec == 1) exactWinRec++;
        }
        log.append("exact RGBA ").append(tenBit ? "10/10/10/2" : "8/8/8/8").append(" + ES3 counts: WINDOW=")
                .append(exactWin).append(" PBUFFER=").append(exactPbuf).append(" RECORDABLE=")
                .append(exactRec).append(" WINDOW+RECORDABLE=").append(exactWinRec).append('\n');

        log.append("-- separate filters (eglChooseConfig, no 8-config cap) --\n");
        int wantSurf = tenBit ? 10 : 8;
        choose(s.display, filter(tenBit, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, 1), log,
                "WINDOW|PBUFFER + RECORDABLE " + wantSurf);
        EGLConfig[] winRec = choose(s.display, filter(tenBit, EGL14.EGL_WINDOW_BIT, 1), log,
                "WINDOW + RECORDABLE " + wantSurf);
        EGLConfig[] pbuf = choose(s.display, filter(tenBit, EGL14.EGL_PBUFFER_BIT, -1), log,
                "PBUFFER (no RECORDABLE req) " + wantSurf);
        choose(s.display, filter(tenBit, EGL14.EGL_WINDOW_BIT, -1), log, "WINDOW (no RECORDABLE req) " + wantSurf);
        choose(s.display, filter(tenBit, EGL14.EGL_PBUFFER_BIT, 1), log, "PBUFFER + RECORDABLE " + wantSurf);
        choose(s.display, filter(tenBit, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, -1), log,
                "WINDOW|PBUFFER (no RECORDABLE req) " + wantSurf);

        EGLConfig chosen = firstExact(s.display, winRec, tenBit);
        if (chosen == null) {
            // still pick a WINDOW+RECORDABLE even if RGB sizes are a superset, but only if exact exists nowhere
            for (EGLConfig c : winRec) {
                if (get(s.display, c, EGL14.EGL_RED_SIZE) >= wantSurf
                        && get(s.display, c, EGL14.EGL_GREEN_SIZE) >= wantSurf
                        && get(s.display, c, EGL14.EGL_BLUE_SIZE) >= wantSurf) {
                    log.append("no exact RGBA match in WINDOW+RECORDABLE; first superset is NOT used (HDR refuses silent widening)\n");
                    break;
                }
            }
        }
        s.config = chosen;
        if (chosen != null) {
            s.exactRgba = exactRgba(s.display, chosen, tenBit);
            s.windowBit = (get(s.display, chosen, EGL14.EGL_SURFACE_TYPE) & EGL14.EGL_WINDOW_BIT) != 0;
            s.pbufferBit = (get(s.display, chosen, EGL14.EGL_SURFACE_TYPE) & EGL14.EGL_PBUFFER_BIT) != 0;
            s.recordable = get(s.display, chosen, RECORDABLE) == 1;
            log.append("chosen for encode: ").append(configLine(s.display, chosen)).append('\n');
        } else {
            log.append("chosen for encode: NONE — no exact ").append(tenBit ? "RGBA1010102" : "RGBA8888")
                    .append(" WINDOW+RECORDABLE ES3 config\n");
        }

        if (chosen != null) {
            s.offscreen = tryOffscreen(s.display, chosen, hasExt(exts, "EGL_KHR_surfaceless_context"), log);
        } else if (pbuf.length > 0) {
            EGLConfig pb = firstExact(s.display, pbuf, tenBit);
            if (pb != null) {
                log.append("PBUFFER exact exists on a different config (").append(configLine(s.display, pb))
                        .append("); cannot mix with a missing RECORDABLE WINDOW context. Noted, not used for HDR encode.\n");
            }
        }

        log.append("offscreen path: ").append(s.offscreen).append('\n');
        if (tenBit) {
            log.append("high-precision RGB to encoder (recordable WINDOW 10/10/10/2 + offscreen): ")
                    .append(s.usableForHdrEncode() ? "YES" : "NO").append('\n');
            if (!s.usableForHdrEncode())
                log.append("8-bit surface: NOT used as HDR fallback. Gradation check is not skipped; dependents stay NOT_RUN.\n");
        }
        s.report = log.toString();
        return s;
    }

    private static boolean hasExt(String list, String ext) {
        return list != null && (" " + list + " ").contains(" " + ext + " ");
    }

    private static Offscreen tryOffscreen(EGLDisplay d, EGLConfig cfg, boolean surfacelessExt, StringBuilder log) {
        takeError();
        EGLContext ctx = EGL14.eglCreateContext(d, cfg, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        log.append("eglCreateContext GLES3 ").append(takeErrorLine("")).append(" ctx=")
                .append(ctx != null && ctx != EGL14.EGL_NO_CONTEXT ? "ok" : "NO_CONTEXT").append('\n');
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return Offscreen.NONE;
        Offscreen kind = Offscreen.NONE;
        try {
            if ((get(d, cfg, EGL14.EGL_SURFACE_TYPE) & EGL14.EGL_PBUFFER_BIT) != 0) {
                takeError();
                EGLSurface pb = EGL14.eglCreatePbufferSurface(d, cfg,
                        new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
                int e = takeError();
                boolean ok = pb != null && pb != EGL14.EGL_NO_SURFACE;
                log.append("PBUFFER 1x1 create: ").append(ok ? "ok" : "FAIL").append(" ").append(errName(e))
                        .append(" (0x").append(Integer.toHexString(e)).append(")\n");
                if (ok) {
                    takeError();
                    boolean cur = EGL14.eglMakeCurrent(d, pb, pb, ctx);
                    log.append("  makeCurrent PBUFFER: ").append(cur ? "ok" : "FAIL").append(" ")
                            .append(takeErrorLine("")).append('\n');
                    if (cur) kind = Offscreen.PBUFFER;
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(d, pb);
                }
            } else {
                log.append("PBUFFER: config has no EGL_PBUFFER_BIT — this is the likely WINDOW|PBUFFER chooseConfig miss\n");
            }
            if (kind == Offscreen.NONE && surfacelessExt) {
                takeError();
                boolean cur = EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx);
                log.append("SURFACELESS makeCurrent: ").append(cur ? "ok" : "FAIL").append(" ")
                        .append(takeErrorLine("")).append('\n');
                if (cur) kind = Offscreen.SURFACELESS;
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } else if (kind == Offscreen.NONE) {
                log.append("SURFACELESS: extension EGL_KHR_surfaceless_context absent, not tried\n");
            }
            if (kind == Offscreen.NONE) {
                SurfaceTexture st = null;
                Surface win = null;
                EGLSurface es = EGL14.EGL_NO_SURFACE;
                try {
                    st = new SurfaceTexture(false);
                    st.setDefaultBufferSize(16, 16);
                    win = new Surface(st);
                    takeError();
                    es = EGL14.eglCreateWindowSurface(d, cfg, win, new int[]{EGL14.EGL_NONE}, 0);
                    int e = takeError();
                    boolean ok = es != null && es != EGL14.EGL_NO_SURFACE;
                    log.append("DUMMY_WINDOW SurfaceTexture: ").append(ok ? "ok" : "FAIL").append(" ")
                            .append(errName(e)).append(" (0x").append(Integer.toHexString(e)).append(")\n");
                    if (ok) {
                        takeError();
                        boolean cur = EGL14.eglMakeCurrent(d, es, es, ctx);
                        log.append("  makeCurrent DUMMY_WINDOW: ").append(cur ? "ok" : "FAIL").append(" ")
                                .append(takeErrorLine("")).append('\n');
                        if (cur) kind = Offscreen.DUMMY_WINDOW;
                        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    }
                } catch (Throwable t) {
                    log.append("DUMMY_WINDOW exception: ").append(t.getClass().getSimpleName()).append(": ")
                            .append(t.getMessage()).append('\n');
                } finally {
                    if (es != null && es != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, es);
                    if (win != null) win.release();
                    if (st != null) st.release();
                }
            }
        } finally {
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(d, ctx);
        }
        return kind;
    }

    /** Encoder color-format dump. Does not try to configure a P010 input; declaration only. */
    public static String encoderColorFormats(DeviceCaps.Enc enc) {
        if (enc == null || enc.caps == null) return "encoder formats: (none)\n";
        StringBuilder b = new StringBuilder();
        b.append("encoder ").append(enc.name).append(" colorFormats (declared, not proven):\n");
        boolean surface = false, p010 = false, abgr1010102 = false, flex = false;
        for (int cf : enc.caps.colorFormats) {
            b.append(String.format(Locale.US, "  0x%08X %s\n", cf, colorName(cf)));
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
            if (cf == 54) p010 = true;
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flex = true;
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010) abgr1010102 = true;
        }
        b.append("  COLOR_FormatSurface: ").append(yn(surface)).append('\n');
        b.append("  COLOR_Format32bitABGR2101010 (10-bit RGB buffer): ").append(yn(abgr1010102))
                .append(" — not used; listed only\n");
        b.append("  COLOR_FormatYUVP010 (54): ").append(yn(p010))
                .append(" — capability check only, path NOT implemented\n");
        b.append("  COLOR_FormatYUV420Flexible: ").append(yn(flex)).append('\n');
        if (!p010) b.append("  YUV P010 encoder input is NOT declared; do not assume it.\n");
        return b.toString();
    }

    private static String yn(boolean v) { return v ? "yes" : "no"; }

    static String colorName(int cf) {
        switch (cf) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface: return "COLOR_FormatSurface";
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible: return "COLOR_FormatYUV420Flexible";
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar: return "COLOR_FormatYUV420Planar";
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar: return "COLOR_FormatYUV420SemiPlanar";
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar: return "COLOR_FormatYUV420PackedSemiPlanar";
            case 54: return "COLOR_FormatYUVP010";
            case MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010: return "COLOR_Format32bitABGR2101010";
            default: return "";
        }
    }
}
