import com.mojahed.hdrstudio.core.ColorMath;
import com.mojahed.hdrstudio.encode.HdrMetadata;
import com.mojahed.hdrstudio.stages.SceneAnalyzer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** Plain-JVM tests of the app's Android-independent logic (run by tools/jvmtests/run.sh). */
public class JvmTests {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // PQ
        double e203 = ColorMath.pqEncode(203);
        check("pq_203_nits", Math.abs(e203 - 0.58069) < 1e-4, "PQ(203)=" + e203);
        double worst = 0;
        for (double n = 0.01; n <= 10000; n *= 1.07) worst = Math.max(worst, Math.abs(ColorMath.pqDecode(ColorMath.pqEncode(n)) - n) / n);
        check("pq_roundtrip", worst < 1e-9, "max rel err " + worst);
        // Y'CbCr code points
        double[][] t = ColorMath.rgbToYuvAffine(1, false, 10);
        double[] w = ColorMath.apply(t, new double[]{1, 1, 1}), k = ColorMath.apply(t, new double[]{0, 0, 0});
        check("bt2020_limited_10bit_white_black", Math.abs(w[0] * 1023 - 940) < 1e-6 && Math.abs(k[0] * 1023 - 64) < 1e-6
                && Math.abs(w[1] * 1023 - 512) < 1e-6 && Math.abs(w[2] * 1023 - 512) < 1e-6, String.format("Y white %.3f black %.3f", w[0] * 1023, k[0] * 1023));
        // compensation: encoder that uses BT.709 full range while the file says BT.2020 limited
        double[][] act = ColorMath.rgbToYuvAffine(0, true, 10);
        float[][] c = ColorMath.compensation(t, act);
        Random r = new Random(1);
        double err = 0;
        for (int i = 0; i < 1000; i++) {
            double[] rgb = {r.nextDouble(), r.nextDouble(), r.nextDouble()};
            double[] pre = new double[3];
            for (int row = 0; row < 3; row++) {
                pre[row] = c[1][row];
                for (int col = 0; col < 3; col++) pre[row] += c[0][col * 3 + row] * rgb[col];   // column-major
            }
            double[] got = ColorMath.apply(act, pre), want = ColorMath.apply(t, rgb);
            for (int q = 0; q < 3; q++) err = Math.max(err, Math.abs(got[q] - want[q]) * 1023);
        }
        check("encoder_matrix_compensation", err < 1e-3, "max error " + err + " codes (float precision)");
        // HDR static info layout (CTA-861.3, little endian, units 0.00002 / 1 nit / 0.0001 nit)
        HdrMetadata m = HdrMetadata.p3(1000);
        m.setMeasured(840.4f, 60.9f, "test");
        ByteBuffer b = m.toStaticInfo().order(ByteOrder.LITTLE_ENDIAN);
        int id = b.get(0), rx = b.getShort(1) & 0xFFFF, maxL = b.getShort(17) & 0xFFFF, minL = b.getShort(19) & 0xFFFF, cll = b.getShort(21) & 0xFFFF, fall = b.getShort(23) & 0xFFFF;
        check("hdr_static_info_bytes", b.remaining() == 25 && id == 0 && rx == 34000 && maxL == 1000 && minL == 50 && cll == 840 && fall == 61,
                "rx=" + rx + " max=" + maxL + " min=" + minL + " cll=" + cll + " fall=" + fall);
        HdrMetadata u = HdrMetadata.p3(1000);
        ByteBuffer ub = u.toStaticInfo().order(ByteOrder.LITTLE_ENDIAN);
        check("unmeasured_cll_is_zero_unknown", (ub.getShort(21) | ub.getShort(23)) == 0, "MaxCLL/MaxFALL written as 0 when not measured");
        // scene analyser
        SceneAnalyzer sa = new SceneAnalyzer();
        float[] f1 = new float[24 * 14], f2 = new float[f1.length], f3 = new float[f1.length];
        for (int i = 0; i < f1.length; i++) {
            f1[i] = 0.2f + 0.6f * (float) Math.abs(Math.sin(i * 0.37));
            f2[i] = (float) Math.min(1, Math.pow(Math.pow(f1[i], 2.4) * 1.6, 1 / 2.4)) + 0.002f * (float) r.nextGaussian();
            f3[i] = (float) r.nextDouble();
        }
        boolean first = sa.isCut(f1), same = sa.isCut(f1.clone()), flash = sa.isCut(f2), back = sa.isCut(f1.clone()), cut = sa.isCut(f3);
        check("scene_cut_logic", first && !same && !flash && !back && cut,
                "first=" + first + " same=" + same + " flash=" + flash + " back=" + back + " newShot=" + cut);
        System.out.println(fails == 0 ? "ALL JVM TESTS PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
