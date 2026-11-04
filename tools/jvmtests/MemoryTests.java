import com.mojahed.hdrstudio.core.MemoryBudget;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Memory plan (phase 4). The GPU part of MemoryBudget must equal what the pipeline allocates:
 * the harness FramePipeline constructor (the desktop mirror of FramePipeline.java) is run by
 * tools/harness/alloc_probe.js with a byte-counting fake GL context for 42 stage combinations (7 sizes, temporal on/off,
 * classic / AI full / AI half work resolution).
 * usage: MemoryTests <alloc_probe output>
 */
public class MemoryTests {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }
    static long mb(long b) { return b >> 20; }
    static final long GB = 1L << 30;

    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(a[0]));
        int n = 0, bad = 0;
        String firstBad = "";
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            String[] f = l.trim().split(" ");
            int sw = Integer.parseInt(f[0]), sh = Integer.parseInt(f[1]), ow = Integer.parseInt(f[2]), oh = Integer.parseInt(f[3]);
            boolean temporal = f[4].equals("1"), ai = f[5].equals("1");
            float scale = Float.parseFloat(f[6]);
            long got = Long.parseLong(f[7]), want = MemoryBudget.pipeline(sw, sh, ow, oh, temporal, ai, scale);
            n++;
            if (got != want) { bad++; if (firstBad.isEmpty()) firstBad = l + " model=" + want; }
        }
        check("mem_model_equals_pipeline_allocation", n >= 42 && bad == 0, n + " combinations, mismatches=" + bad + (firstBad.isEmpty() ? "" : " first: " + firstBad));

        // disabled stages allocate nothing: classic same-size, no temporal = a, b, zero, luma pyramid, fin
        long lv = 0; int w = 1920, h = 1080;
        for (int i = 0; i < 4; i++) { w = (w + 1) / 2; h = (h + 1) / 2; lv += MemoryBudget.tex(w, h); }
        long minimal = 2 * MemoryBudget.tex(1920, 1080) + 8 + lv + MemoryBudget.tex(1920, 1080);
        check("mem_disabled_stages_allocate_nothing", MemoryBudget.pipeline(1920, 1080, 1920, 1080, false, false, 1f) == minimal
                        && MemoryBudget.history(1920, 1080, 1920, 1080, false, false) == 0,
                "classic 1080p without temporal = " + mb(minimal) + " MB (v2.1 allocated a third source buffer, three output buffers and the motion pyramid regardless)");
        check("mem_history_at_stage_resolution", MemoryBudget.history(1920, 1080, 3840, 2160, true, true) == 2 * MemoryBudget.tex(3840, 2160)
                        && MemoryBudget.history(1920, 1080, 3840, 2160, true, false) == 2 * MemoryBudget.tex(1920, 1080),
                "AI path: output size; classic path: source size");
        long t256 = 2L * 16 * (256 + 72) * (256 + 72) * 8 + (long) (260 * 4) * (260 * 4) * 8;
        check("mem_ai_tile_formula", MemoryBudget.aiTile(256) == t256, "T=256: " + mb(t256) + " MB (2x16 feature layers of 328^2 + 1040^2 result)");

        // tile choice: largest that fits, shrinks with memory, never touches the work scale
        int prev = Integer.MAX_VALUE; boolean mono = true; StringBuilder sb = new StringBuilder();
        for (long avail : new long[]{8 * GB, 4 * GB, 3 * GB, 2 * GB, 3 * GB / 2, GB}) {
            MemoryBudget.Plan p = MemoryBudget.plan(1920, 1080, true, 3840, 2160, true, true, true, 1f, avail, 12 * GB);
            if (p.tile > prev) mono = false;
            prev = p.tile;
            sb.append(String.format(Locale.US, "%dMB->%d%s ", mb(avail), p.tile, p.fits ? "" : "(refused)"));
            if (p.withinBudget && p.total() > p.budget) mono = false;
            if (p.fits != p.withinBudget) mono = false;
        }
        check("mem_tile_shrinks_with_memory", mono, "1080p->4K AI+temporal, 12 GB device, free: " + sb.toString().trim());
        MemoryBudget.Plan big = MemoryBudget.plan(1920, 1080, true, 3840, 2160, true, true, true, 1f, 8 * GB, 12 * GB);
        check("mem_large_device_gets_largest_tile", big.tile == 512 && big.withinBudget && big.fits, big.describe());
        MemoryBudget.Plan tight = MemoryBudget.plan(3840, 2160, true, 3840, 2160, true, true, true, 1f, 400L << 20, 4 * GB);
        check("mem_refuses_when_even_smallest_tile_cannot_fit", !tight.fits && !tight.withinBudget && tight.tile == 64, tight.describe());
        MemoryBudget.Plan uhd = MemoryBudget.plan(3840, 2160, true, 3840, 2160, true, true, true, 1f, 5 * GB, 12 * GB);
        check("mem_4k_same_size_ai_plan", uhd.fits && uhd.withinBudget, uhd.describe());
        // previously allowed by the 85% hardLimit; conservative budget is now the operating limit
        MemoryBudget.Plan overflow = MemoryBudget.plan(1920, 1080, true, 3840, 2160, true, true, true, 1f, 1536L << 20, 12 * GB);
        check("mem_smallest_tile_over_conservative_budget_is_refused",
                overflow.tile == 64 && overflow.total() > overflow.budget && overflow.total() <= overflow.hardLimit && !overflow.fits && overflow.fits == overflow.withinBudget,
                overflow.describe() + " · 85% of free would have been " + mb(overflow.hardLimit) + " MB");
        MemoryBudget.Plan preview = MemoryBudget.preview(3840, 2160, true, 1094, 674, 1094, 674, true, true, 1f, 960, 540, true, 5 * GB, 12 * GB);
        MemoryBudget.Plan export = MemoryBudget.plan(3840, 2160, true, 3840, 2160, true, true, true, 1f, 5 * GB, 12 * GB);
        check("mem_preview_plan_smaller_than_export_and_includes_bitmaps",
                preview.fits && preview.cpu > 0 && preview.encoder == 0 && preview.history == 0 && preview.total() < export.total() && preview.tile > 0,
                "preview " + preview.describe() + " vs export " + export.describe());

        // v2.1 chose the tile from free memory alone (>3 GB -> 512) without counting the frame buffers
        MemoryBudget.Plan v21 = MemoryBudget.plan(3840, 2160, true, 3840, 2160, true, true, true, 1f, 3200L << 20, 4 * GB);
        long v21Total = v21.total() - v21.aiTiles + MemoryBudget.aiTile(512);
        System.out.println(String.format(Locale.US, "INFO v2.1 tile rule at 3.2 GB free, 4K AI+temporal: tile 512 -> %d MB total (budget %d MB); new rule -> tile %d, %d MB",
                mb(v21Total), mb(v21.budget), v21.tile, mb(v21.total())));
        MemoryBudget.Plan glsl = MemoryBudget.plan(1920, 1080, false, 1920, 1080, false, false, true, 0.5f, 4 * GB, 8 * GB, false);
        // 2.3.5: the ncnn path keeps no x4 frame in Java; Java heap is its own limit; the tile shrinks with memory
        long x4Rgba = 16L * 3840 * 2160;
        check("mem_ncnn_java_holds_no_x4_frame", MemoryBudget.ncnnJava(960, 540, 512) < x4Rgba / 2,
                "Java " + mb(MemoryBudget.ncnnJava(960, 540, 512)) + " MB vs one x4 RGBA frame " + mb(x4Rgba) + " MB");
        // the phone of the 2.3.4 report: growth limit 512 MB, about 280 MB in use
        MemoryBudget.JavaHeap phone = new MemoryBudget.JavaHeap(536870912L, 285L << 20);
        MemoryBudget.Plan ph = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, 6 * GB, 12 * GB, true, phone);
        check("mem_ncnn_fits_2_3_4_phone_heap", ph.fits && ph.javaFits && ph.javaNeed <= phone.avail() / 2,
                ph.describe() + " | " + phone.describe());
        MemoryBudget.JavaHeap tiny = new MemoryBudget.JavaHeap(64L << 20, 40L << 20);
        MemoryBudget.Plan tj = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, 6 * GB, 12 * GB, true, tiny);
        check("mem_ncnn_java_limit_stops_before_alloc", !tj.fits && !tj.javaFits && tj.reason.contains("Java"),
                tj.reason);
        MemoryBudget.Plan nBig = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, 8 * GB, 12 * GB, true, phone);
        MemoryBudget.Plan nSmall = MemoryBudget.plan(1920, 1080, false, 3840, 2160, false, false, true, 0.5f, 1700L << 20, 12 * GB, true, phone);
        check("mem_ncnn_tile_shrinks_with_ram_not_resolution", nSmall.tile < nBig.tile && nSmall.ncnn && nBig.ncnn,
                "8 GB free -> T=" + nBig.tile + ", 1.7 GB free -> T=" + nSmall.tile + " (work scale stays 0.5)");
        check("mem_ncnn_counts_native_buffers", nBig.nativeNeed == MemoryBudget.ncnnNative(960, 540, nBig.tile)
                        && nBig.aiTiles == MemoryBudget.ncnnWorkspace(nBig.tile, 960, 540) + nBig.nativeNeed,
                "native " + mb(nBig.nativeNeed) + " MB, workspace " + mb(nBig.aiTiles - nBig.nativeNeed) + " MB");
        MemoryBudget.Plan ncnn = MemoryBudget.plan(1920, 1080, false, 1920, 1080, false, false, true, 0.5f, 4 * GB, 8 * GB, true);
        check("mem_ncnn_counts_vulkan_workspace_not_glsl_tiles",
                ncnn.ncnn && ncnn.aiTiles != glsl.aiTiles && ncnn.aiTiles >= MemoryBudget.ncnnWorkspace(ncnn.tile)
                        && ncnn.aiFixed == MemoryBudget.AI_WEIGHTS * 2 && ncnn.cpu > glsl.cpu && ncnn.fits == (ncnn.total() <= ncnn.budget),
                "glsl " + glsl.describe() + " | ncnn " + ncnn.describe());
        System.exit(fails == 0 ? 0 : 1);
    }
}
