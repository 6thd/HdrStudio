package com.mojahed.hdrstudio.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Which build is running, from the phone itself: version name and code, the commit and Actions run that
 * CI writes into assets/build_info.txt, the SHA-256 of the installed APK file and of the signing
 * certificate. A version number alone does not identify a build (2.3.6's CI log was lost).
 */
public final class BuildIdentity {
    private static String cached;

    private BuildIdentity() {}

    public static synchronized String describe(Context ctx) {
        if (cached != null) return cached;
        StringBuilder b = new StringBuilder();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            b.append("النسخة ").append(pi.versionName).append(" (").append(pi.getLongVersionCode()).append(") · ").append(ctx.getPackageName());
            String info = asset(ctx);
            b.append(" · ").append(info == null ? "commit غير مضمّن (ليس بناء GitHub Actions)" : info);
            b.append("\nAPK sha256 ").append(sha256File(pi.applicationInfo.sourceDir));
            if (pi.applicationInfo.splitSourceDirs != null && pi.applicationInfo.splitSourceDirs.length > 0)
                b.append(" + ").append(pi.applicationInfo.splitSourceDirs.length).append(" split");
            Signature[] sigs = pi.signingInfo == null ? null
                    : pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            if (sigs != null && sigs.length > 0) b.append(" · شهادة التوقيع sha256 ").append(hex(MessageDigest.getInstance("SHA-256").digest(sigs[sigs.length - 1].toByteArray())));
        } catch (Throwable t) {
            b.append("تعذر تحديد هوية النسخة: ").append(t);
        }
        cached = b.toString();
        return cached;
    }

    /** "commit <sha> · Actions run #<n> (<id>) · <ref>" from the asset CI writes, or null. */
    private static String asset(Context ctx) {
        try (InputStream in = ctx.getAssets().open("build_info.txt")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String commit = "", run = "", runId = "", ref = "";
            for (String l; (l = r.readLine()) != null; ) {
                int i = l.indexOf('=');
                if (i < 0) continue;
                String k = l.substring(0, i).trim(), v = l.substring(i + 1).trim();
                if (k.equals("commit")) commit = v;
                else if (k.equals("run_number")) run = v;
                else if (k.equals("run_id")) runId = v;
                else if (k.equals("ref")) ref = v;
            }
            return String.format(Locale.US, "commit %s · Actions run #%s (%s) · %s", commit, run, runId, ref);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256File(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format(Locale.US, "%02x", x & 0xFF));
        return s.toString();
    }
}
