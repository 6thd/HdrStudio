package com.mojahed.hdrstudio.ai;

import android.content.res.AssetManager;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Direct vs tiled on the phone, for each engine (CPU FP32, Vulkan FP32, Vulkan FP16).
 * The tiled run is {@link NcnnSr#forward}, the same method export calls.
 * Same pixels, regions and limits as tools/ncnn/tile_check.py; the limits are not relaxed.
 *
 * Regions on the x4 output, LR coordinate = HR / 4:
 *   corner: within EDGE px of two image borders; edge: of one border (corners excluded);
 *   seam: within SEAM px of an interior tile boundary, not edge; centre: the rest.
 */
public final class NcnnTileCheck {
    public static final int EDGE = 2, SEAM = 2;
    public static final double MAX_ABS = AiEngineCompare.PROPOSED_MAX_ABS;
    public static final double PSNR_DB = AiEngineCompare.PROPOSED_PSNR_DB;

    public static final class Case { public int w, h; public float[] in, ref; }

    private NcnnTileCheck() {}

    public static List<Case> load(AssetManager am) throws Exception {
        List<Case> out = new ArrayList<>();
        try (InputStream is = am.open("models/srvgg_tilecheck.bin")) {
            DataInputStream din = new DataInputStream(is);
            int n = readInt(din);
            for (int k = 0; k < n; k++) {
                Case c = new Case();
                c.w = readInt(din); c.h = readInt(din);
                boolean ref = readInt(din) != 0;
                c.in = readFloats(din, c.w * c.h * 3);
                if (ref) c.ref = readFloats(din, c.w * c.h * 16 * 3);
                out.add(c);
            }
        }
        return out;
    }

    /** Tile cores to test for one case. CPU skips the smallest tiles of the large case (minutes on one big core). */
    static int[] tiles(Case c, boolean vulkan) {
        if (c.w * c.h <= 400) return new int[]{64, 8};
        return vulkan ? new int[]{64, 32, 16} : new int[]{64, 32};
    }

    /** Appends the report for one engine. Returns true only if every comparison is inside the limits. */
    public static boolean run(StringBuilder r, NcnnSr net, List<Case> cases, AiEngineCompare.Progress progress) {
        String tag = net.summary();
        if (!net.loaded) { r.append(tag).append('\n'); return false; }
        boolean all = true;
        for (Case c : cases) {
            if (progress != null) progress.onProgress(String.format(Locale.US, "%s %dx%d", net.vulkan ? (net.fp16 ? "Vulkan FP16" : "Vulkan FP32") : "CPU FP32", c.w, c.h));
            long t0 = System.nanoTime();
            NcnnSr.Forward d = net.direct(c.in, c.w, c.h);
            long dm = (System.nanoTime() - t0) / 1_000_000L;
            r.append(String.format(Locale.US, " %dx%d → %dx%d · المباشر %d ms%s\n", c.w, c.h, c.w * 4, c.h * 4, dm,
                    net.vulkan && !d.fullVulkan ? " · ليس Vulkan بالكامل" : ""));
            if (c.ref != null) all &= line(r, "المباشر مقابل مرجع NumPy", d.rgb, c.ref, c.w, c.h, 1 << 20);
            for (int t : tiles(c, net.vulkan)) {
                TilePlan plan = new TilePlan(c.w, c.h, t, NcnnSr.P);
                long s0 = System.nanoTime();
                NcnnSr.Forward f = net.forward(c.in, c.w, c.h, t, null);
                long sm = (System.nanoTime() - s0) / 1_000_000L;
                String label = String.format(Locale.US, "مقسّم T=%d (%d بلاطات، نافذة ≤%dx%d، %d ms) مقابل المباشر",
                        t, plan.count(), plan.maxWindowW(), plan.maxWindowH(), sm);
                all &= line(r, label, f.rgb, d.rgb, c.w, c.h, t);
                if (c.ref != null) all &= line(r, "  مقسّم T=" + t + " مقابل مرجع NumPy", f.rgb, c.ref, c.w, c.h, t);
            }
        }
        return all;
    }

    /** region: 0 corner, 1 edge, 2 seam, 3 centre. */
    static int region(int lx, int ly, int w, int h, int t) {
        boolean ex = lx < EDGE || lx >= w - EDGE;
        boolean ey = ly < EDGE || ly >= h - EDGE;
        if (ex && ey) return 0;
        if (ex || ey) return 1;
        for (int b = t; b < w; b += t) if (lx >= b - SEAM && lx < b + SEAM) return 2;
        for (int b = t; b < h; b += t) if (ly >= b - SEAM && ly < b + SEAM) return 2;
        return 3;
    }

    private static final String[] NAMES = {"زوايا", "حواف", "فواصل", "مركز"};

    private static boolean line(StringBuilder r, String label, float[] got, float[] ref, int w, int h, int t) {
        int W4 = w * 4, H4 = h * 4;
        double[] max = new double[5], se = new double[5];
        long[] n = new long[5];
        long bad = 0;
        for (int oy = 0; oy < H4; oy++) {
            for (int ox = 0; ox < W4; ox++) {
                int reg = region(ox / 4, oy / 4, w, h, t);
                int i = (oy * W4 + ox) * 3;
                for (int k = 0; k < 3; k++) {
                    float a = got[i + k], b = ref[i + k];
                    if (!Float.isFinite(a) || !Float.isFinite(b)) { bad++; continue; }
                    double d = Math.abs((double) a - b);
                    max[reg] = Math.max(max[reg], d); se[reg] += d * d;
                    max[4] = Math.max(max[4], d); se[4] += d * d;
                }
                n[reg]++; n[4]++;
            }
        }
        boolean ok = bad == 0;
        StringBuilder s = new StringBuilder();
        for (int reg = 4; reg >= 0; reg--) {
            if (n[reg] == 0) { if (reg < 4) s.append(" | ").append(NAMES[reg]).append(" —"); continue; }
            double mse = se[reg] / (n[reg] * 3.0);
            double psnr = mse <= 1e-12 ? 99 : 10 * Math.log10(1.0 / mse);
            boolean rok = max[reg] <= MAX_ABS && psnr >= PSNR_DB;
            ok &= rok;
            if (reg == 4) s.append(String.format(Locale.US, "الكل %.6f / %.1f dB", max[reg], psnr));
            else s.append(String.format(Locale.US, " | %s %.6f / %.1f", NAMES[reg], max[reg], psnr)).append(rok ? "" : " ✗");
        }
        if (bad > 0) s.append(" | NaN/Inf ").append(bad);
        r.append("  ").append(label).append(": ").append(s).append(ok ? "  ✓ ضمن الحد" : "  ✗ خارج الحد").append('\n');
        return ok;
    }

    private static int readInt(DataInputStream d) throws Exception {
        byte[] b = new byte[4];
        d.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static float[] readFloats(DataInputStream d, int n) throws Exception {
        byte[] b = new byte[n * 4];
        d.readFully(b);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] f = new float[n];
        bb.asFloatBuffer().get(f);
        return f;
    }
}
