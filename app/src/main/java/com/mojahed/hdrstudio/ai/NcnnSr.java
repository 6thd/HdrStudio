package com.mojahed.hdrstudio.ai;

import android.content.res.AssetManager;

import com.mojahed.hdrstudio.stages.FramePipeline;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Experimental ncnn/Vulkan path for the same SRVGG weights as {@link SrEngine}.
 * Not the default. A missing Vulkan device does not fall back to GLSL.
 * Row 0 of every buffer is GL's bottom row, the same index order as the GLSL textures
 * and tools/srvgg_reference.py.
 */
public final class NcnnSr {
    public static final String NCNN_TAG = "20250503";
    public static final int P = SrEngine.P;

    public final boolean fp16;
    public final boolean loaded;
    public final boolean fullVulkan;
    public final String device;
    public final String reason;
    public final long loadMs;
    private final long handle;

    public static final class Forward {
        public float[] rgb;
        public int w, h;
        public long packNs, networkNs, shuffleNs, tileCopyNs;
        public int tiles;
        public boolean fullVulkan;
    }

    private NcnnSr(boolean fp16, long handle, boolean fullVulkan, String device, String reason, long loadMs) {
        this.fp16 = fp16;
        this.handle = handle;
        this.loaded = handle != 0;
        this.fullVulkan = fullVulkan;
        this.device = device == null ? "" : device;
        this.reason = reason == null ? "" : reason;
        this.loadMs = loadMs;
    }

    public static NcnnSr open(AssetManager am, boolean fp16) {
        long t0 = System.nanoTime();
        try {
            System.loadLibrary("hdrstudio_ncnn");
        } catch (UnsatisfiedLinkError e) {
            return new NcnnSr(fp16, 0, false, "", "مكتبة ncnn غير مبنية في هذا الـ APK: " + e.getMessage(), 0);
        }
        String device = nativeDevice();
        byte[] model;
        try (InputStream in = am.open("models/srvgg_general_x4v3.bin")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            model = bos.toByteArray();
        } catch (Exception e) {
            return new NcnnSr(fp16, 0, false, device, "تعذر قراءة الأوزان: " + e.getMessage(), 0);
        }
        long h = nativeCreate(model, fp16);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (h == 0) return new NcnnSr(fp16, 0, false, device, nativeLastError(), ms);
        return new NcnnSr(fp16, h, nativeFullVulkan(h), device, nativeLastError(), ms);
    }

    public void close() { if (handle != 0) nativeDestroy(handle); }

    /** One image, exact ×4. tileCore is the same T the GLSL engine uses. */
    public Forward forward(float[] rgb, int w, int h, int tileCore, FramePipeline.TileGate gate) {
        Forward f = new Forward();
        f.w = w * 4; f.h = h * 4;
        f.rgb = new float[f.w * f.h * 3];
        f.fullVulkan = fullVulkan;
        if (!loaded) return f;
        int T = Math.max(8, tileCore);
        int F = T + 2 * P;
        float[] window = new float[F * F * 3];
        float[] hr = new float[F * 4 * F * 4 * 3];
        long[] phase = new long[3];
        int tilesX = (w + T - 1) / T, tilesY = (h + T - 1) / T;
        f.tiles = tilesX * tilesY;
        int done = 0;
        for (int y0 = 0; y0 < h; y0 += T) {
            for (int x0 = 0; x0 < w; x0 += T) {
                int cw = Math.min(T, w - x0), ch = Math.min(T, h - y0);
                java.util.Arrays.fill(window, 0f);
                long c0 = System.nanoTime();
                for (int wy = 0; wy < F; wy++) {
                    int iy = y0 - P + wy;
                    if (iy < 0 || iy >= h) continue;
                    for (int wx = 0; wx < F; wx++) {
                        int ix = x0 - P + wx;
                        if (ix < 0 || ix >= w) continue;
                        int s = (iy * w + ix) * 3, d = (wy * F + wx) * 3;
                        window[d] = rgb[s]; window[d + 1] = rgb[s + 1]; window[d + 2] = rgb[s + 2];
                    }
                }
                f.tileCopyNs += System.nanoTime() - c0;
                int rc = nativeForward(handle, window, F, F, hr, phase);
                if (rc < 0) throw new IllegalStateException("ncnn forward: " + nativeLastError());
                if (rc == 2) f.fullVulkan = false;
                f.packNs += phase[0]; f.networkNs += phase[1]; f.shuffleNs += phase[2];
                int x1 = P, y1 = P;
                for (int y = 0; y < ch * 4; y++) {
                    int sy = (y1 * 4 + y) * (F * 4) + x1 * 4;
                    int dy = ((y0 * 4 + y) * f.w) + x0 * 4;
                    System.arraycopy(hr, sy * 3, f.rgb, dy * 3, cw * 4 * 3);
                }
                done++;
                if (gate != null && done < f.tiles) gate.between(done, f.tiles);
            }
        }
        return f;
    }

    public String summary() {
        if (!loaded) return "ncnn/Vulkan غير جاهز: " + reason;
        return String.format(Locale.US, "ncnn %s · %s · %s · تحميل %d ms · %s",
                NCNN_TAG, fp16 ? "FP16" : "FP32", fullVulkan ? "Vulkan للطبقات المحمّلة" : "ليس Vulkan بالكامل",
                loadMs, device);
    }

    private static native long nativeCreate(byte[] model, boolean fp16);
    private static native void nativeDestroy(long handle);
    private static native int nativeForward(long handle, float[] in, int w, int h, float[] out, long[] phaseNs);
    private static native String nativeLastError();
    private static native String nativeDevice();
    private static native boolean nativeFullVulkan(long handle);
}
