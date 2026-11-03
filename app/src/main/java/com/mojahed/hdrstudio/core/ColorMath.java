package com.mojahed.hdrstudio.core;

/** CPU colour maths shared by the self test, metadata and statistics (doubles, column-major mat3 for GL). */
public final class ColorMath {
    private ColorMath() {}

    public static double pqEncode(double nits) {
        double y = Math.pow(Math.max(0, Math.min(1, nits / 10000.0)), 0.1593017578125);
        return Math.pow((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y), 78.84375);
    }

    public static double pqDecode(double e) {
        double p = Math.pow(Math.max(0, Math.min(1, e)), 1 / 78.84375);
        return 10000 * Math.pow(Math.max(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p), 1 / 0.1593017578125);
    }

    /** kr, kb for matrix id: 0 BT.709, 1 BT.2020, 2 BT.601 */
    public static double[] krkb(int matrix) {
        return matrix == 1 ? new double[]{0.2627, 0.0593} : matrix == 2 ? new double[]{0.299, 0.114} : new double[]{0.2126, 0.0722};
    }

    /**
     * R'G'B' (0..1) -> Y'CbCr expressed as normalised code values (code / (2^bits - 1)).
     * Returns {A (row-major 3x3), offset(3)} such that yuv = A * rgb + offset.
     */
    public static double[][] rgbToYuvAffine(int matrix, boolean full, int bits) {
        double[] k = krkb(matrix);
        double kr = k[0], kb = k[1], kg = 1 - kr - kb;
        double max = (1 << bits) - 1, s = 1 << (bits - 8);
        double ys = full ? max : 219 * s, cs = full ? max : 224 * s;
        double yo = full ? 0 : 16 * s, co = 128 * s;
        double[] Y = {kr, kg, kb};
        double[] Cb = {-kr / (2 * (1 - kb)), -kg / (2 * (1 - kb)), 0.5};
        double[] Cr = {0.5, -kg / (2 * (1 - kr)), -kb / (2 * (1 - kr))};
        double[][] A = new double[3][3];
        for (int i = 0; i < 3; i++) { A[0][i] = Y[i] * ys / max; A[1][i] = Cb[i] * cs / max; A[2][i] = Cr[i] * cs / max; }
        return new double[][]{A[0], A[1], A[2], {yo / max, co / max, co / max}};
    }

    public static double[] apply(double[][] aff, double[] rgb) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = aff[i][0] * rgb[0] + aff[i][1] * rgb[1] + aff[i][2] * rgb[2] + aff[3][i];
        return r;
    }

    public static double[][] inv3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], i = m[2][2];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        return new double[][]{
                {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det},
                {(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det},
                {(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det}};
    }

    /**
     * Pre-distortion so that an encoder whose RGB->Y'CbCr is 'actual' produces the Y'CbCr of
     * 'target': rgb' = Aact^-1 (Atgt rgb + otgt - oact). Returns {mat3 column-major (9), offset (3)}.
     */
    public static float[][] compensation(double[][] target, double[][] actual) {
        double[][] Ai = inv3(new double[][]{actual[0], actual[1], actual[2]});
        float[] m = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                double v = 0;
                for (int k = 0; k < 3; k++) v += Ai[r][k] * target[k][c];
                m[c * 3 + r] = (float) v;            // column-major for glUniformMatrix3fv
            }
        float[] o = new float[3];
        for (int r = 0; r < 3; r++) {
            double v = 0;
            for (int k = 0; k < 3; k++) v += Ai[r][k] * (target[3][k] - actual[3][k]);
            o[r] = (float) v;
        }
        return new float[][]{m, o};
    }

    public static final float[] IDENTITY3 = {1, 0, 0, 0, 1, 0, 0, 0, 1};
}
