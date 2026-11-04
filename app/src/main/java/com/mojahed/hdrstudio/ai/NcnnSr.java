package com.mojahed.hdrstudio.ai;

import android.content.res.AssetManager;

import com.mojahed.hdrstudio.stages.FramePipeline;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Experimental ncnn/Vulkan path for the same SRVGG weights as {@link SrEngine}.
 * Not the default. A missing Vulkan device does not fall back to GLSL.
 * Row 0 of every buffer is GL's bottom row, the same index order as the GLSL textures.
 * Tiling: {@link TilePlan}. Checked against the untiled run by {@link NcnnTileCheck}.
 */
public final class NcnnSr {
    public static final String NCNN_TAG = "20250503";
    public static final int P = SrEngine.P;

    public final Opts opts;
    public final boolean vulkan;
    /** True if any FP16 storage or arithmetic is in use (not the export mode). */
    public final boolean fp16;
    public final boolean loaded;
    public final boolean fullVulkan;
    public final String device;
    public final String reason;
    /** Requested and effective ncnn options as read back from the Net after load_param, plus device quirks. */
    public final String options;
    public final long loadMs;
    private final long handle;

    /**
     * Net options. Each precision mode is accepted on its own by the phone check; the native side
     * refuses a mode whose effective precision ncnn would change, so there is no silent fallback.
     * The experimental export mode is decided per build by AiEngineCompare.exportMode (Vulkan FP32 until then).
     */
    public static final class Opts {
        // must match the enum in ncnn_sr.cpp
        static final int F_VULKAN = 1, F_FP16_STORAGE = 2, F_FP16_ARITH = 4, F_FP16_PACKED = 8,
                F_FP16_UNIFORM = 16, F_WINOGRAD = 32, F_SGEMM = 64, F_PACKING = 128;
        public final boolean vulkan, fp16Storage, fp16Arith, winograd, sgemm, packing;
        public Opts(boolean vulkan, boolean fp16Storage, boolean fp16Arith, boolean winograd, boolean sgemm, boolean packing) {
            this.vulkan = vulkan; this.fp16Storage = fp16Storage; this.fp16Arith = fp16Arith;
            this.winograd = winograd; this.sgemm = sgemm; this.packing = packing;
        }
        public static Opts cpuFp32() { return new Opts(false, false, false, true, true, false); }
        public static Opts vulkanFp32() { return new Opts(true, false, false, true, true, false); }
        /** FP16 storage, FP32 arithmetic: the middle mode. */
        public static Opts vulkanFp16Storage() { return new Opts(true, true, false, true, true, false); }
        /** FP16 storage and arithmetic (failed the phone check in 2.3.3; not used by export). */
        public static Opts vulkanFp16() { return new Opts(true, true, true, true, true, false); }
        int flags() {
            int f = 0;
            if (vulkan) f |= F_VULKAN;
            if (fp16Storage) f |= F_FP16_STORAGE;
            if (fp16Arith) f |= F_FP16_ARITH;
            // fp16 uniform only with fp16 arithmetic (ncnn clears it otherwise); fp16 packed off: packing layout is off
            if (fp16Arith) f |= F_FP16_UNIFORM;
            if (winograd) f |= F_WINOGRAD;
            if (sgemm) f |= F_SGEMM;
            if (packing) f |= F_PACKING;
            return f;
        }
        public String label() {
            String p = !fp16Storage && !fp16Arith ? "FP32"
                    : fp16Storage && !fp16Arith ? "FP16 storage + FP32 arithmetic" : "FP16 storage + FP16 arithmetic";
            return (vulkan ? "Vulkan " : "CPU ") + p;
        }
    }

    public static final class Forward {
        public float[] rgb;
        public int w, h;
        /** packNs packs the CPU mat. extractNs is extract(), including its internal transfer and sync, not GPU compute alone. */
        public long packNs, extractNs, shuffleNs;
        /** tileCopyNs gathers each context window; mergeNs hands each cropped x4 core to its destination (GL upload in export). */
        public long tileCopyNs, mergeNs;
        /** Native window + core buffer bytes used (outside the Java heap, reused across frames). */
        public long nativeBufferBytes;
        public int tiles;
        public boolean fullVulkan;
    }

    private NcnnSr(Opts o, long handle, boolean fullVulkan, String device, String reason, String options, long loadMs) {
        this.opts = o;
        this.vulkan = o.vulkan;
        this.fp16 = o.fp16Storage || o.fp16Arith;
        this.handle = handle;
        this.loaded = handle != 0;
        this.fullVulkan = fullVulkan;
        this.device = device == null ? "" : device;
        this.reason = reason == null ? "" : reason;
        this.options = options == null ? "" : options;
        this.loadMs = loadMs;
    }

    /** The mode export and preview use: this build's decision (AiEngineCompare.exportMode), Vulkan FP32 until decided. */
    public static NcnnSr openForExport(android.content.Context ctx) { return open(ctx.getAssets(), AiEngineCompare.exportMode(ctx)); }

    public static NcnnSr open(AssetManager am, Opts o) {
        long t0 = System.nanoTime();
        try {
            System.loadLibrary("hdrstudio_ncnn");
        } catch (UnsatisfiedLinkError e) {
            return new NcnnSr(o, 0, false, "", "مكتبة ncnn غير مبنية في هذا الـ APK: " + e.getMessage(), "", 0);
        }
        String device = o.vulkan ? nativeDevice() : "CPU";
        byte[] model;
        try (InputStream in = am.open("models/srvgg_general_x4v3.bin")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            model = bos.toByteArray();
        } catch (Exception e) {
            return new NcnnSr(o, 0, false, device, "تعذر قراءة الأوزان: " + e.getMessage(), "", 0);
        }
        long h = nativeCreate(model, o.flags());
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (h == 0) return new NcnnSr(o, 0, false, device, nativeLastError(), "", ms);
        return new NcnnSr(o, h, nativeFullVulkan(h), device, nativeLastError(), nativeOptions(h), ms);
    }

    public void close() {
        freeBuffers();
        if (handle != 0) nativeDestroy(handle);
    }

    // Window (RGB) and core (RGBA x4) buffers for the tiled path. Native memory, not Java heap;
    // allocated for the largest tile of the current plan and reused for every tile and frame.
    private java.nio.ByteBuffer winBuf, coreBuf;
    private java.nio.FloatBuffer winF, coreF;

    /** Bytes of the reusable native buffers a tiled run of this size and tile needs. */
    public static long bufferBytes(int w, int h, int tileCore) {
        TilePlan p = new TilePlan(w, h, tileCore, P);
        return 4L * (p.windowFloats() + p.coreFloats());
    }

    private void ensureBuffers(TilePlan plan) {
        long wb = 4L * plan.windowFloats(), cb = 4L * plan.coreFloats();
        if (winBuf == null || winBuf.capacity() < wb) {
            if (winBuf != null) { nativeFreeDirect(winBuf); winBuf = null; winF = null; }
            winBuf = nativeAllocDirect(wb);
            if (winBuf == null) throw new IllegalStateException("ncnn: native window buffer of " + wb + " bytes not allocated: " + nativeLastError());
            winF = winBuf.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        }
        if (coreBuf == null || coreBuf.capacity() < cb) {
            if (coreBuf != null) { nativeFreeDirect(coreBuf); coreBuf = null; coreF = null; }
            coreBuf = nativeAllocDirect(cb);
            if (coreBuf == null) throw new IllegalStateException("ncnn: native core buffer of " + cb + " bytes not allocated: " + nativeLastError());
            coreF = coreBuf.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        }
    }

    /** Frees the reusable buffers (also done by close()). */
    public void freeBuffers() {
        if (winBuf != null) { nativeFreeDirect(winBuf); winBuf = null; winF = null; }
        if (coreBuf != null) { nativeFreeDirect(coreBuf); coreBuf = null; coreF = null; }
    }

    /** One untiled forward of the whole image (the reference the tiled path must match). */
    public Forward direct(float[] rgb, int w, int h) {
        Forward f = new Forward();
        f.w = w * 4; f.h = h * 4;
        f.rgb = new float[f.w * f.h * 3];
        f.fullVulkan = fullVulkan;
        f.tiles = 1;
        if (!loaded) return f;
        long[] phase = new long[3];
        int rc = nativeForward(handle, rgb, w, h, f.rgb, phase);
        if (rc < 0) throw new IllegalStateException("ncnn forward: " + nativeLastError());
        if (rc == 2) f.fullVulkan = false;
        f.packNs = phase[0]; f.extractNs = phase[1]; f.shuffleNs = phase[2];
        return f;
    }

    /**
     * One image, exact x4, tiled by {@link TilePlan}: every context window is real image only,
     * so the network pads at true image borders exactly as {@link #direct} does, and the crop
     * offset comes from the window actually used. This is the path export uses.
     */
    public Forward forward(float[] rgb, int w, int h, int tileCore, final FramePipeline.TileGate gate) {
        float[] out = new float[w * 4 * h * 4 * 3];
        Forward f = forwardTo(rgb, 3, w, h, tileCore, TilePlan.rgbArraySink(out, w), gate);
        f.rgb = out;
        return f;
    }

    /**
     * The tiled forward export uses: src is RGB (3) or RGBA straight from a GL read (4), each tile's
     * x4 core goes to sink as soon as it is computed. No x4 frame is held here; f.rgb stays null.
     */
    public Forward forwardTo(float[] src, int srcChannels, int w, int h, int tileCore, TilePlan.Sink sink, final FramePipeline.TileGate gate) {
        final Forward f = new Forward();
        f.w = w * 4; f.h = h * 4;
        f.fullVulkan = fullVulkan;
        if (!loaded) return f;
        TilePlan plan = new TilePlan(w, h, tileCore, P);
        f.tiles = plan.count();
        ensureBuffers(plan);
        f.nativeBufferBytes = winBuf.capacity() + coreBuf.capacity();
        final long[] phase = new long[3];
        TilePlan.Stats st = new TilePlan.Stats();
        plan.run(src, srcChannels, winF, coreF, new TilePlan.CoreNet() {
            @Override public void forwardCore(java.nio.FloatBuffer window, int fw, int fh, int offX, int offY, int cw, int ch, java.nio.FloatBuffer core) {
                int rc = nativeForwardCore(handle, winBuf, fw, fh, offX, offY, cw, ch, coreBuf, phase);
                if (rc < 0) throw new IllegalStateException("ncnn forward: " + nativeLastError());
                if (rc == 2) f.fullVulkan = false;
                f.packNs += phase[0]; f.extractNs += phase[1]; f.shuffleNs += phase[2];
            }
        }, sink, st, gate == null ? null : new TilePlan.Between() {
            @Override public void between(int done, int total) { gate.between(done, total); }
        });
        f.tileCopyNs = st.gatherNs;
        f.mergeNs = st.sinkNs;
        return f;
    }

    /** One intermediate blob ("c0".."c33", "p0".."p32") as top-down CHW floats, or null. Diagnostic only. */
    public float[] layer(float[] rgb, int w, int h, String blob) {
        if (!loaded) return null;
        float[] out = new float[w * h * 64];
        int n = nativeLayer(handle, rgb, w, h, blob, out);
        if (n <= 0) return null;
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    public String summary() {
        if (!loaded) return "ncnn/Vulkan غير جاهز: " + reason;
        return String.format(Locale.US, "ncnn %s · %s · %s · تحميل %d ms · %s",
                NCNN_TAG, opts.label(),
                !vulkan ? "CPU" : fullVulkan ? "Vulkan للطبقات المحمّلة" : "ليس Vulkan بالكامل",
                loadMs, device);
    }

    private static native long nativeCreate(byte[] model, int flags);
    private static native String nativeOptions(long handle);
    private static native int nativeLayer(long handle, float[] in, int w, int h, String blob, float[] out);
    private static native int nativeForwardCore(long handle, java.nio.ByteBuffer window, int fw, int fh, int offX, int offY, int cw, int ch, java.nio.ByteBuffer core, long[] phaseNs);
    private static native java.nio.ByteBuffer nativeAllocDirect(long bytes);
    private static native void nativeFreeDirect(java.nio.ByteBuffer buf);
    private static native void nativeDestroy(long handle);
    private static native int nativeForward(long handle, float[] in, int w, int h, float[] out, long[] phaseNs);
    private static native String nativeLastError();
    private static native String nativeDevice();
    private static native boolean nativeFullVulkan(long handle);
}
