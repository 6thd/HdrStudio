package com.mojahed.hdrstudio.ai;

import android.content.res.AssetManager;

import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.Tex;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Real-ESRGAN realesr-general-x4v3 (SRVGGNetCompact) executed with OpenGL ES 3 fragment shaders.
 * No native runtime: weights are uploaded to an RGBA16F texture, features live in RGBA16F array
 * textures, maths is fp32. Mirrors SrEngine in tools/harness/engine.js, which is verified against
 * a NumPy reference that itself matches the ncnn runtime (see docs/AI_MODEL.md).
 *
 * Tiling: core tiles of T x T LR pixels plus P = 36 pixels of real neighbourhood on each side
 * (>= 34-layer receptive field + 2-pixel margin). Image borders get zeros exactly like the
 * full-frame network, so tiles are mathematically seamless (tested: max diff 0.0).
 */
public final class SrEngine {
    public static final int P = 36, MARGIN = 2;
    private static final int[][] SHAPES = new int[34][];
    static {
        SHAPES[0] = new int[]{64, 3};
        for (int i = 1; i <= 32; i++) SHAPES[i] = new int[]{64, 64};
        SHAPES[33] = new int[]{48, 64};
    }

    private final GlKit k;
    private final float[][] w = new float[34][], b = new float[34][], s = new float[34][];
    private Tex wtex, A, B, SR;
    private int tile;
    public String status = "غير محمّل";
    public boolean ready;
    public double selfTestMaxErr = -1;

    public SrEngine(GlKit k, AssetManager am) throws IOException {
        this.k = k;
        try (InputStream in = am.open("models/srvgg_general_x4v3.bin")) {
            DataInputStream din = new DataInputStream(in);
            byte[] all = new byte[4853184];
            din.readFully(all);
            if (din.read() != -1) throw new IOException("model blob larger than expected");
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            for (int L = 0; L < 34; L++) {
                int o = SHAPES[L][0], c = SHAPES[L][1];
                w[L] = new float[o * c * 9]; b[L] = new float[o];
                for (int i = 0; i < w[L].length; i++) w[L][i] = bb.getFloat();
                for (int i = 0; i < o; i++) b[L][i] = bb.getFloat();
                if (L < 33) { s[L] = new float[64]; for (int i = 0; i < 64; i++) s[L][i] = bb.getFloat(); }
            }
            if (bb.remaining() != 0) throw new IOException("model blob layout mismatch");
        }
        buildWeights();
    }

    private void buildWeights() {
        int W = 576, H = 34 * 16;
        float[] data = new float[W * H * 4];
        for (int L = 0; L < 34; L++) {
            int o = SHAPES[L][0], c = SHAPES[L][1], gin = c == 3 ? 1 : 16;
            for (int og = 0; og < 16; og++) {
                int[] outs = L < 33 ? new int[]{og * 4, og * 4 + 1, og * 4 + 2, og * 4 + 3} : new int[]{og, 16 + og, 32 + og, -1};
                for (int ig = 0; ig < gin; ig++)
                    for (int t = 0; t < 9; t++)
                        for (int j = 0; j < 4; j++) {
                            int in = ig * 4 + j, base = ((L * 16 + og) * W + (ig * 9 + t) * 4 + j) * 4;
                            for (int q = 0; q < 4; q++) {
                                int out = outs[q];
                                data[base + q] = (in < c && out >= 0 && out < o) ? w[L][((out * c + in) * 3 + t / 3) * 3 + t % 3] : 0f;
                            }
                        }
            }
        }
        wtex = k.tex(W, H, true, GlKit.floats(data));
    }

    /** Runs the bundled test vector through the GPU implementation; READY only if it matches. */
    public boolean selfTest(AssetManager am) {
        try (InputStream in = am.open("models/srvgg_selftest.bin")) {
            DataInputStream din = new DataInputStream(in);
            byte[] hdr = new byte[8];
            din.readFully(hdr);
            ByteBuffer hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            int tw = hb.getInt(), th = hb.getInt();
            byte[] rest = new byte[(tw * th * 3 + tw * th * 48) * 4];
            din.readFully(rest);
            ByteBuffer rb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
            float[] inp = new float[tw * th * 4];
            for (int i = 0; i < tw * th; i++) { inp[i * 4] = rb.getFloat(); inp[i * 4 + 1] = rb.getFloat(); inp[i * 4 + 2] = rb.getFloat(); inp[i * 4 + 3] = 1f; }
            Tex inT = k.tex(tw, th, true, GlKit.floats(inp));
            Tex out = k.tex(tw * 4, th * 4, true);
            run(inT, out, 64);
            float[] got = k.read(out);
            double maxErr = 0;
            for (int i = 0; i < tw * th * 16; i++)
                for (int ch = 0; ch < 3; ch++) maxErr = Math.max(maxErr, Math.abs(got[i * 4 + ch] - rb.getFloat()));
            k.delete(inT); k.delete(out);
            releaseTiles();
            selfTestMaxErr = maxErr;
            // desktop SwiftShader reference run: 1.1e-3; allow for mobile fp16 texture paths
            ready = maxErr < 0.01;
            status = ready ? String.format(java.util.Locale.US, "جاهز (فحص النموذج على GPU: أقصى خطأ %.4f)", maxErr)
                    : String.format(java.util.Locale.US, "غير جاهز: نتيجة GPU لا تطابق المرجع (خطأ %.3f)", maxErr);
        } catch (Exception e) {
            ready = false;
            status = "غير جاهز: " + e.getMessage();
        }
        return ready;
    }

    private void ensureTiles(int T) {
        if (A != null && tile == T) return;
        releaseTiles();
        int F = T + 2 * P;
        A = k.texArray(F, F, 16);
        B = k.texArray(F, F, 16);
        SR = k.tex((T + 2 * MARGIN) * 4, (T + 2 * MARGIN) * 4, true);
        tile = T;
    }

    public void releaseTiles() { k.delete(A); k.delete(B); k.delete(SR); A = B = SR = null; tile = 0; }

    public void release() { releaseTiles(); k.delete(wtex); wtex = null; }

    public interface TileCallback { void onTile(int done, int total); }

    /** lr: model input (E_SDR, LR size). out: output texture; every pixel of it is written. */
    public int run(Tex lr, Tex out, int tileCore) { return run(lr, out, tileCore, null); }

    public int run(Tex lr, Tex out, int tileCore, TileCallback cb) {
        ensureTiles(tileCore);
        int T = tileCore, F = T + 2 * P, LRw = lr.w, LRh = lr.h;
        double fx = out.w / (LRw * 4.0), fy = out.h / (LRh * 4.0);
        int total = ((LRw + T - 1) / T) * ((LRh + T - 1) / T), done = 0;
        Map<String, Object> u = new HashMap<>();
        for (int y0 = 0; y0 < LRh; y0 += T) {
            for (int x0 = 0; x0 < LRw; x0 += T) {
                int cw = Math.min(T, LRw - x0), ch = Math.min(T, LRh - y0);
                int ox = x0 - P, oy = y0 - P;
                int[] valid = {Math.max(0, -ox), Math.max(0, -oy), Math.min(F, LRw - ox), Math.min(F, LRh - oy)};
                u.clear();
                u.put("uLR", lr); u.put("uOrigin", new int[]{ox, oy});
                k.run("sr_load.frag", new Tex.Target[]{A.layer(0)}, u, null, null);
                Tex src = A, dst = B;
                for (int L = 0; L < 33; L++) {
                    for (int ogb = 0; ogb < 16; ogb += 4) {
                        float[] bias = new float[16], slope = new float[16];
                        for (int q = 0; q < 16; q++) { bias[q] = b[L][ogb * 4 + q]; slope[q] = s[L][ogb * 4 + q]; }
                        u.clear();
                        u.put("uIn", src); u.put("uW", wtex); u.put("uGin", L == 0 ? 1 : 16); u.put("uRow0", L * 16 + ogb);
                        u.put("uBias", bias); u.put("uSlope", slope); u.put("uValid", valid);
                        k.run("sr_conv.frag", new Tex.Target[]{dst.layer(ogb), dst.layer(ogb + 1), dst.layer(ogb + 2), dst.layer(ogb + 3)}, u, null, null);
                    }
                    Tex t = src; src = dst; dst = t;
                }
                float[] bias = new float[64];
                for (int kk = 0; kk < 16; kk++) { bias[kk * 4] = b[33][kk]; bias[kk * 4 + 1] = b[33][16 + kk]; bias[kk * 4 + 2] = b[33][32 + kk]; }
                int rx0 = Math.max(0, x0 - MARGIN), ry0 = Math.max(0, y0 - MARGIN);
                int rx1 = Math.min(LRw, x0 + cw + MARGIN), ry1 = Math.min(LRh, y0 + ch + MARGIN);
                u.clear();
                u.put("uIn", src); u.put("uW", wtex); u.put("uRow0", 33 * 16); u.put("uBias", bias); u.put("uLR", lr);
                u.put("uOrigin", new int[]{ox, oy}); u.put("uFeatStart", new int[]{rx0 - ox, ry0 - oy}); u.put("uHrOrigin", new int[]{0, 0});
                k.run("sr_final.frag", SR, u, new int[]{0, 0, (rx1 - rx0) * 4, (ry1 - ry0) * 4}, null);
                int tx0 = (int) Math.round(x0 * 4 * fx), tx1 = (int) Math.round((x0 + cw) * 4 * fx);
                int ty0 = (int) Math.round(y0 * 4 * fy), ty1 = (int) Math.round((y0 + ch) * 4 * fy);
                u.clear();
                u.put("uSR", SR); u.put("uSrOrigin", new float[]{rx0 * 4, ry0 * 4}); u.put("uScale", new float[]{(float) fx, (float) fy});
                u.put("uSrValid", new int[]{(rx1 - rx0) * 4, (ry1 - ry0) * 4});
                if (tx1 > tx0 && ty1 > ty0)
                    k.run("sr_resample.frag", out, u, new int[]{0, 0, out.w, out.h}, new int[]{tx0, ty0, tx1 - tx0, ty1 - ty0});
                done++;
                if (cb != null) cb.onTile(done, total);
            }
        }
        return total;
    }

    /** Tile size from free memory: features use 2 arrays x 16 layers x (T+72)^2 x 8 bytes. */
    public static int chooseTile(long availBytes) {
        if (availBytes > 3L << 30) return 512;
        if (availBytes > 1536L << 20) return 256;
        return 128;
    }
}
