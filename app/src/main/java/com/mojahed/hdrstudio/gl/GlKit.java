package com.mojahed.hdrstudio.gl;

import android.opengl.GLES20;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Android twin of tools/harness/glkit.js: RGBA16F textures, texture arrays, MRT into array
 * layers, programs with uniform reflection, float read-back. Must be used on the GL thread.
 */
public final class GlKit {
    public static final class Uniform { int loc, type, size; }
    public static final class Program {
        public final String name; final int id; final Map<String, Uniform> uniforms = new HashMap<>();
        Program(String name, int id) { this.name = name; this.id = id; }
    }

    private final ShaderLoader loader;
    private final Map<String, Program> programs = new HashMap<>();
    private final int fbo, vao;
    private long texBytes;

    public GlKit(ShaderLoader loader) {
        this.loader = loader;
        int[] a = new int[1];
        GLES20.glGenFramebuffers(1, a, 0); fbo = a[0];
        GLES30.glGenVertexArrays(1, a, 0); vao = a[0];
    }

    public ShaderLoader loader() { return loader; }
    public long allocatedBytes() { return texBytes; }

    // ------------------------------------------------------------------ programs
    public Program program(String fragName) {
        Program p = programs.get(fragName);
        if (p != null) return p;
        int vs = compile(GLES20.GL_VERTEX_SHADER, loader.assemble("fullscreen.vert"), "fullscreen.vert");
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, loader.assemble(fragName), fragName);
        int id = GLES20.glCreateProgram();
        GLES20.glAttachShader(id, vs);
        GLES20.glAttachShader(id, fs);
        GLES20.glLinkProgram(id);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) throw new GlException(fragName + " link: " + GLES20.glGetProgramInfoLog(id));
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        p = new Program(fragName, id);
        int[] n = new int[1];
        GLES20.glGetProgramiv(id, GLES20.GL_ACTIVE_UNIFORMS, n, 0);
        int[] size = new int[1], type = new int[1];
        for (int i = 0; i < n[0]; i++) {
            String name = GLES20.glGetActiveUniform(id, i, size, 0, type, 0);
            Uniform u = new Uniform();
            u.loc = GLES20.glGetUniformLocation(id, name);
            u.type = type[0]; u.size = size[0];
            if (name.endsWith("[0]")) name = name.substring(0, name.length() - 3);
            p.uniforms.put(name, u);
        }
        programs.put(fragName, p);
        return p;
    }

    private static int compile(int type, String src, String label) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new GlException(label + ": " + log);
        }
        return s;
    }

    // ------------------------------------------------------------------ textures
    public Tex tex(int w, int h, boolean nearest) { return tex(w, h, nearest, null); }

    public Tex tex(int w, int h, boolean nearest, FloatBuffer data) {
        int[] a = new int[1];
        GLES20.glGenTextures(1, a, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, w, h);
        if (data != null) GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_FLOAT, data);
        int f = nearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GlException.check("tex " + w + "x" + h);
        texBytes += 8L * w * h;
        return new Tex(a[0], w, h, 1, false);
    }

    /** RGBA8 texture (preview read-back). */
    public Tex tex8(int w, int h) {
        int[] a = new int[1];
        GLES20.glGenTextures(1, a, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        texBytes += 4L * w * h;
        return new Tex(a[0], w, h, 1, false);
    }

    public Tex texArray(int w, int h, int layers) {
        int[] a = new int[1];
        GLES20.glGenTextures(1, a, 0);
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, a[0]);
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RGBA16F, w, h, layers);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GlException.check("texArray " + w + "x" + h + "x" + layers);
        texBytes += 8L * w * h * layers;
        return new Tex(a[0], w, h, layers, true);
    }

    /** Submits queued GPU work (keeps single submissions short between AI tiles). */
    public void flush() { GLES20.glFlush(); }

    public void delete(Tex t) {
        if (t == null) return;
        GLES20.glDeleteTextures(1, new int[]{t.id}, 0);
        texBytes -= (long) t.w * t.h * t.layers * 8;
    }

    // ------------------------------------------------------------------ drawing
    public void run(String prog, Tex target, Map<String, Object> u) { run(prog, new Tex.Target[]{target.layer(0)}, u, null, null); }

    public void run(String prog, Tex target, Map<String, Object> u, int[] viewport, int[] scissor) {
        run(prog, new Tex.Target[]{target.layer(0)}, u, viewport, scissor);
    }

    /** Draw into FBO targets (MRT supported). viewport/scissor: {x, y, w, h} or null. */
    public void run(String prog, Tex.Target[] targets, Map<String, Object> u, int[] viewport, int[] scissor) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        int[] bufs = new int[targets.length];
        for (int i = 0; i < 4; i++) {
            int att = GLES20.GL_COLOR_ATTACHMENT0 + i;
            if (i >= targets.length) { GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, att, GLES20.GL_TEXTURE_2D, 0, 0); continue; }
            Tex.Target t = targets[i];
            if (t.tex.array) GLES30.glFramebufferTextureLayer(GLES20.GL_FRAMEBUFFER, att, t.tex.id, 0, t.layer);
            else GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, att, GLES20.GL_TEXTURE_2D, t.tex.id, 0);
            bufs[i] = att;
        }
        GLES30.glDrawBuffers(bufs.length, bufs, 0);
        int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new GlException("FBO incomplete 0x" + Integer.toHexString(st) + " for " + prog);
        Tex t0 = targets[0].tex;
        drawProgram(prog, u, viewport != null ? viewport : new int[]{0, 0, t0.w, t0.h}, scissor);
    }

    /** Draw into the currently bound EGL window surface (encoder input). */
    public void runToSurface(String prog, Map<String, Object> u, int w, int h) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        drawProgram(prog, u, new int[]{0, 0, w, h}, null);
    }

    private void drawProgram(String prog, Map<String, Object> u, int[] vp, int[] sc) {
        Program p = program(prog);
        GLES20.glUseProgram(p.id);
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3]);
        if (sc != null) { GLES20.glEnable(GLES20.GL_SCISSOR_TEST); GLES20.glScissor(sc[0], sc[1], sc[2], sc[3]); }
        else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        int unit = 0;
        for (Map.Entry<String, Object> e : u.entrySet()) {
            Uniform un = p.uniforms.get(e.getKey());
            if (un == null) continue;                 // optimised away by the compiler
            Object v = e.getValue();
            switch (un.type) {
                case GLES20.GL_SAMPLER_2D:
                case GLES30.GL_SAMPLER_2D_ARRAY: {
                    Tex t = (Tex) v;
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
                    GLES20.glBindTexture(t.array ? GLES30.GL_TEXTURE_2D_ARRAY : GLES20.GL_TEXTURE_2D, t.id);
                    GLES20.glUniform1i(un.loc, unit++);
                    break;
                }
                case 0x8D66: /* GL_SAMPLER_EXTERNAL_OES */
                case 0x8BE7: /* GL_SAMPLER_EXTERNAL_2D_Y2Y_EXT */ {
                    int texId = (Integer) v;
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
                    GLES20.glBindTexture(0x8D65 /* GL_TEXTURE_EXTERNAL_OES */, texId);
                    GLES20.glUniform1i(un.loc, unit++);
                    break;
                }
                case GLES20.GL_FLOAT:
                    if (v instanceof float[]) GLES20.glUniform1fv(un.loc, ((float[]) v).length, (float[]) v, 0);
                    else GLES20.glUniform1f(un.loc, ((Number) v).floatValue());
                    break;
                case GLES20.GL_FLOAT_VEC2: GLES20.glUniform2fv(un.loc, ((float[]) v).length / 2, (float[]) v, 0); break;
                case GLES20.GL_FLOAT_VEC3: GLES20.glUniform3fv(un.loc, ((float[]) v).length / 3, (float[]) v, 0); break;
                case GLES20.GL_FLOAT_VEC4: GLES20.glUniform4fv(un.loc, ((float[]) v).length / 4, (float[]) v, 0); break;
                case GLES20.GL_INT:
                case GLES20.GL_BOOL:
                    if (v instanceof Boolean) GLES20.glUniform1i(un.loc, ((Boolean) v) ? 1 : 0);
                    else GLES20.glUniform1i(un.loc, ((Number) v).intValue());
                    break;
                case GLES20.GL_INT_VEC2: GLES20.glUniform2iv(un.loc, 1, (int[]) v, 0); break;
                case GLES20.GL_INT_VEC4: GLES20.glUniform4iv(un.loc, 1, (int[]) v, 0); break;
                case GLES20.GL_FLOAT_MAT3: GLES20.glUniformMatrix3fv(un.loc, 1, false, (float[]) v, 0); break;
                case GLES20.GL_FLOAT_MAT4: GLES20.glUniformMatrix4fv(un.loc, 1, false, (float[]) v, 0); break;
                default: throw new GlException("unsupported uniform type 0x" + Integer.toHexString(un.type) + " " + e.getKey());
            }
        }
        GLES30.glBindVertexArray(vao);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GlException.check(prog);
    }

    // ------------------------------------------------------------------ read-back
    public float[] read(Tex t) { return read(t, 0, 0, t.w, t.h); }

    private boolean readHalf;   // driver only allows HALF_FLOAT read-back (EXT_color_buffer_half_float without _float)

    public float[] read(Tex t, int x, int y, int w, int h) {
        bindForRead(t);
        float[] out = new float[w * h * 4];
        if (!readHalf) {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* clear */ }
            FloatBuffer fb = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
            GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_FLOAT, fb);
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) { fb.position(0); fb.get(out); return out; }
            readHalf = true;
        }
        ByteBuffer hb = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, hb);
        GlException.check("readPixels half");
        for (int i = 0; i < out.length; i++) out[i] = halfToFloat(hb.getShort(i * 2));
        return out;
    }

    static float halfToFloat(short h) {
        int b = h & 0xFFFF, e = (b >> 10) & 0x1F, m = b & 0x3FF, sgn = (b >> 15) != 0 ? -1 : 1;
        if (e == 0) return sgn * m * (1f / (1 << 24));
        if (e == 31) return m == 0 ? sgn * Float.POSITIVE_INFINITY : Float.NaN;
        return sgn * (1 + m / 1024f) * (float) Math.pow(2, e - 15);
    }

    /** RGBA8 read-back (from an RGBA8 texture). Returns ARGB ints, top-down rows (for Bitmap). */
    public int[] readArgbTopDown(Tex t) {
        bindForRead(t);
        ByteBuffer bb = ByteBuffer.allocateDirect(t.w * t.h * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, t.w, t.h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        GlException.check("readPixels rgba8");
        int[] px = new int[t.w * t.h];
        for (int y = 0; y < t.h; y++) {
            int dst = (t.h - 1 - y) * t.w;
            for (int x = 0; x < t.w; x++) {
                int i = (y * t.w + x) * 4;
                int r = bb.get(i) & 255, g = bb.get(i + 1) & 255, b = bb.get(i + 2) & 255;
                px[dst + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }

    private void bindForRead(Tex t) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t.id, 0);
        for (int i = 1; i < 4; i++)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0 + i, GLES20.GL_TEXTURE_2D, 0, 0);
        GLES30.glReadBuffer(GLES20.GL_COLOR_ATTACHMENT0);
    }

    public static FloatBuffer floats(float[] a) {
        FloatBuffer fb = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(a).position(0);
        return fb;
    }

    public static int getInt(int pname) {
        IntBuffer b = IntBuffer.allocate(1);
        GLES20.glGetIntegerv(pname, b);
        return b.get(0);
    }

    public void release() {
        for (Program p : programs.values()) GLES20.glDeleteProgram(p.id);
        programs.clear();
        GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
    }
}
