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

    public final boolean fp16;
    public final boolean vulkan;
    public final boolean winograd, sgemm, packing;
    public final boolean loaded;
    public final boolean fullVulkan;
    public final String device;
    public final String reason;
    public final long loadMs;
    private final long handle;

    /** Net options. production() is what export uses; cpuFp32() is the comparison baseline only. */
    public static final class Opts {
        public final boolean vulkan, fp16, winograd, sgemm, packing;
        public Opts(boolean vulkan, boolean fp16, boolean winograd, boolean sgemm, boolean packing) {
            this.vulkan = vulkan; this.fp16 = fp16; this.winograd = winograd; this.sgemm = sgemm; this.packing = packing;
        }
        public static Opts production(boolean fp16) { return new Opts(true, fp16, true, true, false); }
        public static Opts cpuFp32() { return new Opts(false, false, true, true, false); }
        public String label() {
            return String.format(Locale.US, "%s %s", vulkan ? "Vulkan" : "CPU", fp16 ? "FP16" : "FP32");
        }
    }

    public static final class Forward {
        public float[] rgb;
        public int w, h;
        /** packNs packs the CPU mat. extractNs is extract(), including its internal transfer and sync, not GPU compute alone. */
        public long packNs, extractNs, shuffleNs;
        /** tileCopyNs gathers each context window; mergeNs crops each x4 core into the output. */
        public long tileCopyNs, mergeNs;
        public int tiles;
        public boolean fullVulkan;
    }

    private NcnnSr(Opts o, long handle, boolean fullVulkan, String device, String reason, long loadMs) {
        this.fp16 = o.fp16;
        this.vulkan = o.vulkan;
        this.winograd = o.winograd;
        this.sgemm = o.sgemm;
        this.packing = o.packing;
        this.handle = handle;
        this.loaded = handle != 0;
        this.fullVulkan = fullVulkan;
        this.device = device == null ? "" : device;
        this.reason = reason == null ? "" : reason;
        this.loadMs = loadMs;
    }

    public static NcnnSr open(AssetManager am, boolean fp16) { return open(am, Opts.production(fp16)); }

    public static NcnnSr open(AssetManager am, Opts o) {
        long t0 = System.nanoTime();
        try {
            System.loadLibrary("hdrstudio_ncnn");
        } catch (UnsatisfiedLinkError e) {
            return new NcnnSr(o, 0, false, "", "مكتبة ncnn غير مبنية في هذا الـ APK: " + e.getMessage(), 0);
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
            return new NcnnSr(o, 0, false, device, "تعذر قراءة الأوزان: " + e.getMessage(), 0);
        }
        long h = nativeCreate(model, o.vulkan, o.fp16, o.winograd, o.sgemm, o.packing);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (h == 0) return new NcnnSr(o, 0, false, device, nativeLastError(), ms);
        return new NcnnSr(o, h, nativeFullVulkan(h), device, nativeLastError(), ms);
    }

    public void close() { if (handle != 0) nativeDestroy(handle); }

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
        final Forward f = new Forward();
        f.w = w * 4; f.h = h * 4;
        f.rgb = new float[f.w * f.h * 3];
        f.fullVulkan = fullVulkan;
        if (!loaded) return f;
        TilePlan plan = new TilePlan(w, h, tileCore, P);
        f.tiles = plan.count();
        final long[] phase = new long[3];
        TilePlan.Stats st = new TilePlan.Stats();
        plan.run(rgb, new TilePlan.Net() {
            @Override public void forward(float[] window, int fw, int fh, float[] hr) {
                int rc = nativeForward(handle, window, fw, fh, hr, phase);
                if (rc < 0) throw new IllegalStateException("ncnn forward: " + nativeLastError());
                if (rc == 2) f.fullVulkan = false;
                f.packNs += phase[0]; f.extractNs += phase[1]; f.shuffleNs += phase[2];
            }
        }, f.rgb, st, gate == null ? null : new TilePlan.Between() {
            @Override public void between(int done, int total) { gate.between(done, total); }
        });
        f.tileCopyNs = st.gatherNs;
        f.mergeNs = st.mergeNs;
        return f;
    }

    public String summary() {
        if (!loaded) return "ncnn/Vulkan غير جاهز: " + reason;
        return String.format(Locale.US, "ncnn %s · %s · %s · تحميل %d ms · %s",
                NCNN_TAG, new Opts(vulkan, fp16, winograd, sgemm, packing).label(),
                !vulkan ? "CPU" : fullVulkan ? "Vulkan للطبقات المحمّلة" : "ليس Vulkan بالكامل",
                loadMs, device);
    }

    private static native long nativeCreate(byte[] model, boolean vulkan, boolean fp16, boolean winograd, boolean sgemm, boolean packing);
    private static native void nativeDestroy(long handle);
    private static native int nativeForward(long handle, float[] in, int w, int h, float[] out, long[] phaseNs);
    private static native String nativeLastError();
    private static native String nativeDevice();
    private static native boolean nativeFullVulkan(long handle);
}
