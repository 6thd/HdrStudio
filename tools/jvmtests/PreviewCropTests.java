import com.mojahed.hdrstudio.core.PreviewCrop;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Crop-preview geometry (phase 4). The pixel equivalence of crop and full frame is tested by the
 * harness (preview_crop) with the geometry of engine.js; here the app's PreviewCrop must produce the
 * identical geometry for every case of tools/harness/crop_probe.js, and the geometry invariants
 * (margin inside the frame, whole-pixel edges at every resolution, exact scale) must hold.
 * usage: PreviewCropTests <crop_probe output>
 */
public class PreviewCropTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }

    static String fmt(PreviewCrop g) {
        if (g == null) return "null";
        return g.x0 + " " + g.y0 + " " + g.w + " " + g.h + " " + g.outW + " " + g.outH + " " + g.visX + " " + g.visY + " " + g.visW + " " + g.visH
                + " " + g.srcX + " " + g.srcY + " " + g.srcW + " " + g.srcH + " " + g.margin + " " + (g.fullFrame ? 1 : 0);
    }

    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(a[0]));
        int n = 0, bad = 0, crops = 0, nulls = 0, inv = 0;
        String firstBad = "", firstInv = "";
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            String[] f = l.trim().split(" ");
            int sw = Integer.parseInt(f[0]), sh = Integer.parseInt(f[1]), ow = Integer.parseInt(f[2]), oh = Integer.parseInt(f[3]);
            boolean ai = f[4].equals("1");
            float ws = Float.parseFloat(f[5]);
            double fx = Double.parseDouble(f[6]), fy = Double.parseDouble(f[7]);
            int vw = Integer.parseInt(f[8]), vh = Integer.parseInt(f[9]);
            StringBuilder js = new StringBuilder();
            for (int i = 10; i < f.length; i++) js.append(i > 10 ? " " : "").append(f[i]);
            PreviewCrop g = PreviewCrop.plan(sw, sh, ow, oh, ai, ws, 12f, fx, fy, vw, vh);
            n++;
            if (!fmt(g).equals(js.toString())) { bad++; if (firstBad.isEmpty()) firstBad = l + " | java: " + fmt(g); }
            if (g == null) { nulls++; continue; }
            if (g.fullFrame) continue;
            crops++;
            // invariants: exact scale of the processed rectangle, visible window inside it with the margin
            // (unless at the frame edge), visible window covers the requested size or the frame
            int mx = PreviewCrop.margin(sw, ow, ai, ws, 12f), my = PreviewCrop.margin(sh, oh, ai, ws, 12f);
            boolean ok = (long) g.outW * sw == (long) g.w * ow && (long) g.outH * sh == (long) g.h * oh
                    && (g.x0 == 0 || g.srcX - g.x0 >= mx) && (g.x0 + g.w == sw || g.x0 + g.w - g.srcX - g.srcW >= mx)
                    && (g.y0 == 0 || g.srcY - g.y0 >= my) && (g.y0 + g.h == sh || g.y0 + g.h - g.srcY - g.srcH >= my)
                    && g.visX + g.visW <= g.outW && g.visY + g.visH <= g.outH
                    && (g.visW >= Math.min(vw, ow) || g.srcW == sw) && (g.visH >= Math.min(vh, oh) || g.srcH == sh);
            if (!ok) { inv++; if (firstInv.isEmpty()) firstInv = l; }
        }
        check("preview_crop_geometry_java_equals_harness", n > 300 && bad == 0, n + " cases, mismatches=" + bad + (firstBad.isEmpty() ? "" : " first: " + firstBad));
        check("preview_crop_geometry_invariants", crops > 50 && inv == 0, crops + " crops (" + nulls + " without an exact common grid -> full-frame preview), violations=" + inv
                + (firstInv.isEmpty() ? "" : " first: " + firstInv));
        PreviewCrop u = PreviewCrop.plan(3840, 2160, 3840, 2160, true, 1f, 12f, 0.5, 0.5, 960, 540);
        check("preview_crop_4k_same_size_ai", u != null && !u.fullFrame && u.srcW == 960 && u.srcH == 540,
                u + " (processed " + (u == null ? 0 : Math.round(100.0 * u.w * u.h / (3840.0 * 2160))) + "% of the frame)");
        PreviewCrop odd = PreviewCrop.plan(1919, 1077, 2560, 1440, false, 1f, 12f, 0.5, 0.5, 960, 540);
        check("preview_crop_no_exact_grid_falls_back", odd == null, "1919x1077 -> 2560x1440 has no common pixel grid: full-frame preview");
        System.exit(fails == 0 ? 0 : 1);
    }
}
