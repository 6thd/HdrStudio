package com.mojahed.hdrstudio.encode;

/** One verified property with a three-way verdict and the evidence behind it. Pure Java. */
public final class Check {
    public enum Verdict { PASS, FAIL, INCONCLUSIVE }

    public final Verdict verdict;
    public final String what, detail;

    private Check(Verdict v, String what, String detail) { this.verdict = v; this.what = what; this.detail = detail == null ? "" : detail; }

    public static Check pass(String what, String detail) { return new Check(Verdict.PASS, what, detail); }
    public static Check fail(String what, String detail) { return new Check(Verdict.FAIL, what, detail); }
    public static Check inconclusive(String what, String detail) { return new Check(Verdict.INCONCLUSIVE, what, detail); }
    public static Check of(boolean ok, String what, String detail) { return ok ? pass(what, detail) : fail(what, detail); }
    public static Check restore(Verdict v, String what, String detail) { return new Check(v, what, detail); }

    public boolean passed() { return verdict == Verdict.PASS; }
    public boolean failed() { return verdict == Verdict.FAIL; }

    public static String label(Verdict v) { return v == Verdict.PASS ? "PASS" : v == Verdict.FAIL ? "FAIL" : "INCONCLUSIVE"; }

    public String line() {
        String ar = verdict == Verdict.PASS ? "ناجح" : verdict == Verdict.FAIL ? "فشل" : "غير حاسم";
        return "• " + what + ": " + ar + " [" + label(verdict) + "]" + (detail.isEmpty() ? "" : " — " + detail);
    }

    public String toJson() {
        return "{\"what\":" + q(what) + ",\"verdict\":\"" + verdict.name() + "\",\"detail\":" + q(detail) + "}";
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    @Override public String toString() { return line(); }
}
