package com.mojahed.hdrstudio.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.VideoView;

import com.mojahed.hdrstudio.ai.SrEngine;
import com.mojahed.hdrstudio.core.DeviceCaps;
import com.mojahed.hdrstudio.core.FrameRate;
import com.mojahed.hdrstudio.core.SourceInfo;
import com.mojahed.hdrstudio.core.SourceProbe;
import com.mojahed.hdrstudio.encode.EncodeConfig;
import com.mojahed.hdrstudio.encode.Presets;
import com.mojahed.hdrstudio.gl.Egl;
import com.mojahed.hdrstudio.gl.GlKit;
import com.mojahed.hdrstudio.gl.ShaderLoader;
import com.mojahed.hdrstudio.job.EncodeSelfTest;
import com.mojahed.hdrstudio.job.UndeclaredConfigTrial;
import com.mojahed.hdrstudio.job.ExportJob;
import com.mojahed.hdrstudio.job.ExportRequest;
import com.mojahed.hdrstudio.job.ExportService;
import com.mojahed.hdrstudio.job.JobManager;
import com.mojahed.hdrstudio.stages.PipelineParams;

import java.util.Locale;

/** Arabic, right-to-left single screen. State that must survive rotation lives in {@link UiState}. */
public final class MainActivity extends Activity implements JobManager.Listener {
    private static final int PICK = 42;
    private final int bg = Color.parseColor("#0E0E10"), card = Color.parseColor("#1B1B1F"), accent = Color.parseColor("#FFD60A"), dim = Color.parseColor("#B8B8C0");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UiState st = UiState.get();
    private SharedPreferences prefs;

    private TextView srcText, devText, selfTestText, trialText, aiText, compareText, statusText, resultText, etaText, previewNote, storageText;
    private RadioGroup presetGroup, resGroup, aiScaleGroup, gammaGroup;
    private Switch aiSwitch, ncnnSwitch, temporalSwitch, debandSwitch, measureSwitch, audioSwitch, cropSwitch;
    private SeekBar sDenoise, sChroma, sTemporal, sAi, sSharpen, sExpand, sPeak, sVibrance, sBitrate, sPos;
    private TextView bitrateLabel, posLabel;
    private Button exportBtn, selfTestBtn, trialBtn, aiTestBtn, compareBtn, perfBtn, previewBtn, clipBtn, abBtn, diagBtn, openBtn;
    private ProgressBar progress;
    private CompareView compare;
    private VideoView video;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        buildUi();
        android.content.IntentFilter sf = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, sf, android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenRx, sf);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        if (st.caps == null) {
            devText.setText("جارٍ فحص قدرات الجهاز…");
            bg(new Runnable() {
                @Override public void run() {
                    ExportJob.cleanupStalePending(getApplicationContext());
                    DeviceCaps c = DeviceCaps.collect(getApplicationContext());
                    c.probeGl();
                    st.caps = c;
                    ui(new Runnable() { @Override public void run() { refreshAll(); } });
                }
            });
        }
        refreshAll();
    }

    @Override protected void onStart() { super.onStart(); JobManager.get().addListener(this); }
    @Override protected void onStop() { JobManager.get().removeListener(this); super.onStop(); }
    @Override protected void onResume() { super.onResume(); com.mojahed.hdrstudio.core.RunMonitor.setForeground(true); }
    @Override protected void onPause() { com.mojahed.hdrstudio.core.RunMonitor.setForeground(false); super.onPause(); }

    // screen on/off for RunMonitor (timed runs report it as an interruption)
    private final android.content.BroadcastReceiver screenRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            com.mojahed.hdrstudio.core.RunMonitor.mark(Intent.ACTION_SCREEN_OFF.equals(i.getAction()) ? "انطفأت الشاشة" : "أضيئت الشاشة");
        }
    };

    @Override protected void onDestroy() {
        try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** Keeps the screen on while a measurement or the A/B export runs (cleared when it ends). */
    private void keepScreen(boolean on) {
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        com.mojahed.hdrstudio.core.RunMonitor.mark(on ? "إبقاء الشاشة مضاءة: تفعيل" : "إبقاء الشاشة مضاءة: إيقاف");
    }

    private boolean abRunning;

    private void bg(Runnable r) { new Thread(r, "ui-worker").start(); }
    private void ui(Runnable r) { main.post(r); }

    // ------------------------------------------------------------------ UI construction
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        root.setBackgroundColor(bg);

        TextView title = text("HDR Studio", 26, accent);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("تحسين الفيديو وإخراج HDR10 على الهاتف", 14, dim));

        Button pick = button("اختيار فيديو", true);
        pick.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
            startActivityForResult(i, PICK);
        }});
        root.addView(pick, lp(10));

        LinearLayout c1 = card(root, "المصدر");
        srcText = text("لم يُختر فيديو", 13, Color.WHITE);
        c1.addView(srcText);

        LinearLayout c2 = card(root, "فحص الجهاز");
        devText = text("", 13, Color.WHITE);
        c2.addView(devText);
        selfTestText = text("", 13, dim);
        c2.addView(selfTestText, lp(6));
        selfTestBtn = button("فحص مسار التصدير (ترميز ← فك ترميز)", false);
        selfTestBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runSelfTest(); } });
        c2.addView(selfTestBtn, lp(6));
        trialBtn = button("اختبار EGL #24/#25 (تجريبي، لا يفتح التصدير)", false);
        trialBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runUndeclaredTrial(); } });
        c2.addView(trialBtn, lp(6));
        trialText = text("اختبار الإعدادات غير المعلنة RECORDABLE لم يُشغَّل بعد.", 12, dim);
        c2.addView(trialText, lp(6));
        Button copyDiag = button("نسخ تقرير التشخيص", false);
        copyDiag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String body = (devText.getText() == null ? "" : devText.getText().toString()) + "\n\n"
                        + (selfTestText.getText() == null ? "" : selfTestText.getText().toString()) + "\n\n"
                        + (trialText.getText() == null ? "" : trialText.getText().toString()) + "\n\n"
                        + (compareText == null || compareText.getText() == null ? "" : compareText.getText().toString());
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("HDR Studio diagnostic", body));
                if (statusText != null) statusText.setText("نُسخ تقرير التشخيص إلى الحافظة.");
            }
        });
        c2.addView(copyDiag, lp(6));

        LinearLayout c3 = card(root, "الإخراج");
        presetGroup = radios(c3, new String[]{Presets.HDR10_MASTER.label, Presets.SDR.label, Presets.TIKTOK_HDR.label, Presets.TIKTOK_SDR.label}, prefs.getInt("preset", 0));
        c3.addView(text("الدقة", 14, Color.WHITE), lp(8));
        resGroup = radios(c3, new String[]{"تحسين بنفس الدقة", "720p", "1080p", "1440p", "2160p (4K)"}, prefs.getInt("res", 0));
        bitrateLabel = text("", 13, Color.WHITE);
        c3.addView(bitrateLabel, lp(8));
        sBitrate = seek(c3, null, 0, 100, prefs.getInt("bitrate", 50));
        storageText = text("", 12, dim);
        c3.addView(storageText);
        audioSwitch = sw(c3, "الإبقاء على الصوت", prefs.getBoolean("audio", true));

        LinearLayout c4 = card(root, "تحسين الصورة (كل عنصر مستقل)");
        aiSwitch = sw(c4, "استعادة التفاصيل بالذكاء الاصطناعي (Real-ESRGAN)", prefs.getBoolean("ai", false));
        aiText = text("حالة النموذج: لم يُفحص", 12, dim);
        c4.addView(aiText);
        aiTestBtn = button("فحص نموذج AI على GPU", false);
        aiTestBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runAiTest(); } });
        c4.addView(aiTestBtn, lp(4));
        ncnnSwitch = sw(c4, "محرك AI التجريبي: ncnn/Vulkan FP32 (ليس الافتراضي)", prefs.getBoolean("ncnn", false));
        compareBtn = button("فحص بلاطات ncnn ثم المقارنة", false);
        compareBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runEngineCompare(); } });
        c4.addView(compareBtn, lp(4));
        perfBtn = button("قياس الأداء فقط (بعد فحص هذه النسخة)", false);
        perfBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runPerfOnly(); } });
        c4.addView(perfBtn, lp(4));
        compareText = text("مقارنة المحركين لم تُشغَّل. لا تحتاج فيديو ولا نجاح تصدير HDR.", 12, dim);
        c4.addView(compareText, lp(4));
        sAi = seek(c4, "مزج نتيجة AI مع الأصل", 0, 100, prefs.getInt("aiAmt", 70));
        aiScaleGroup = radios(c4, new String[]{"AI أعلى جودة (بدقة المصدر)", "AI أسرع (بنصف الدقة – يفقد قواماً دقيقاً)"}, prefs.getInt("aiQuality", 0));
        sDenoise = seek(c4, "إزالة الضوضاء (الإضاءة)", 0, 100, prefs.getInt("dn", 30));
        sChroma = seek(c4, "إزالة ضوضاء الألوان", 0, 100, prefs.getInt("dnc", 50));
        temporalSwitch = sw(c4, "تنظيف زمني موجّه بالحركة", prefs.getBoolean("tmp", true));
        sTemporal = seek(c4, "قوة التنظيف الزمني", 0, 90, prefs.getInt("tmps", 70));
        debandSwitch = sw(c4, "تنعيم التدرجات (منع التشريط)", prefs.getBoolean("deb", true));
        sSharpen = seek(c4, "حدة نهائية متكيفة", 0, 100, prefs.getInt("sh", 35));

        LinearLayout c5 = card(root, "HDR");
        sExpand = seek(c5, "قوة توسيع الإضاءات", 0, 100, prefs.getInt("exp", 80));
        sPeak = seek(c5, "قمة السطوع (nit)", 0, 3, prefs.getInt("peak", 1));
        sVibrance = seek(c5, "ثراء الألوان", 0, 100, prefs.getInt("vib", 20));
        c5.addView(text("افتراض منحنى مصدر SDR", 13, Color.WHITE), lp(6));
        gammaGroup = radios(c5, new String[]{"BT.1886 (2.4) – معيار التحويل ITU", "2.2 – مشاهدة الهاتف"}, prefs.getInt("gamma", 0));
        measureSwitch = sw(c5, "قياس دقيق لـ MaxCLL/MaxFALL (مرور إضافي)", prefs.getBoolean("measure", true));

        LinearLayout c6 = card(root, "المعاينة");
        posLabel = text("الموضع: 0.0 ث", 13, Color.WHITE);
        c6.addView(posLabel);
        sPos = seek(c6, null, 0, 1000, 300);
        cropSwitch = sw(c6, "قص 1:1 من وسط الإطار بدقة المصدر (أسرع، يطابق نفس المنطقة في التصدير)", prefs.getBoolean("crop", true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        previewBtn = button("معاينة إطار قبل/بعد", false);
        clipBtn = button("معاينة مقطع 3 ثوانٍ", false);
        row.addView(previewBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(clipBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c6.addView(row, lp(6));
        previewBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runPreview(); } });
        clipBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startExport(true); } });
        abBtn = button("اختبار تصدير A/B: ثانية واحدة SDR بـ GLSL ثم ncnn", false);
        abBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startAbExport(); } });
        c6.addView(abBtn, lp(4));
        diagBtn = button("تشخيص التصدير القصير: أول 5 إطارات بلا AI ثم GLSL ثم GLSL+glFinish", false);
        diagBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startExportDiag(); } });
        c6.addView(diagBtn, lp(4));
        previewNote = text("", 12, dim);
        c6.addView(previewNote, lp(4));
        compare = new CompareView(this);
        c6.addView(compare, lp(6));
        video = new VideoView(this);
        video.setVisibility(View.GONE);
        c6.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        LinearLayout c7 = card(root, "التصدير");
        exportBtn = button("بدء التصدير", true);
        exportBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (JobManager.get().busy()) JobManager.get().cancel(); else startExport(false);
        }});
        c7.addView(exportBtn);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        c7.addView(progress, lp(8));
        statusText = text("", 14, Color.WHITE);
        c7.addView(statusText);
        etaText = text("", 13, dim);
        c7.addView(etaText);
        resultText = text("", 12, Color.WHITE);
        resultText.setTextIsSelectable(true);
        c7.addView(resultText, lp(6));
        openBtn = button("فتح الملف الناتج", false);
        openBtn.setVisibility(View.GONE);
        c7.addView(openBtn, lp(6));

        CompoundButton.OnCheckedChangeListener occ = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) { saveAndRefresh(); }
        };
        for (Switch s : new Switch[]{aiSwitch, ncnnSwitch, temporalSwitch, debandSwitch, measureSwitch, audioSwitch}) s.setOnCheckedChangeListener(occ);
        RadioGroup.OnCheckedChangeListener rc = new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) { saveAndRefresh(); }
        };
        for (RadioGroup g : new RadioGroup[]{presetGroup, resGroup, aiScaleGroup, gammaGroup}) g.setOnCheckedChangeListener(rc);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        sv.addView(root);
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(0, in.getSystemWindowInsetTop(), 0, in.getSystemWindowInsetBottom());
                return in;
            }
        });
        setContentView(sv);
    }

    // ------------------------------------------------------------------ settings
    private int checked(RadioGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) if (((RadioButton) g.getChildAt(i)).isChecked()) return i;
        return 0;
    }

    private Presets preset() { return Presets.values()[checked(presetGroup)]; }

    private int targetShort() { return new int[]{0, 720, 1080, 1440, 2160}[checked(resGroup)]; }

    private float peakNits() { return new float[]{600, 1000, 1500, 2000}[sPeak.getProgress()]; }

    private PipelineParams params() {
        PipelineParams p = new PipelineParams();
        p.ai = aiSwitch.isChecked();
        p.aiNcnn = p.ai && ncnnSwitch.isChecked();
        p.aiAmount = sAi.getProgress() / 100f;
        p.aiWorkScale = checked(aiScaleGroup) == 0 ? 1f : 0.5f;     // never chosen automatically
        if (st.src != null && p.aiWorkScale == 0.5f && Math.min(st.src.width, st.src.height) <= 720) p.aiWorkScale = 1f;
        p.denoiseLuma = sDenoise.getProgress() / 100f;
        p.denoiseChroma = sChroma.getProgress() / 100f;
        p.temporal = temporalSwitch.isChecked();
        p.temporalStrength = sTemporal.getProgress() / 100f;
        p.deband = debandSwitch.isChecked();
        p.sharpen = sSharpen.getProgress() / 100f * 0.8f;
        p.expand = sExpand.getProgress() / 100f;
        p.peak = peakNits();
        p.vibrance = sVibrance.getProgress() / 100f * 0.6f;
        p.sdrGamma = checked(gammaGroup);
        return p;
    }

    private long bitrate() {
        if (st.src == null) return 0;
        int[] d = ExportJob.planDims(st.src, preset(), targetShort());
        long base = preset().startBitrate(d[0], d[1], st.src.avgFps);
        double f = Math.pow(4, (sBitrate.getProgress() - 50) / 50.0);       // x0.25 .. x4 around the starting point
        return Math.round(base * f);
    }

    private void saveAndRefresh() {
        prefs.edit().putInt("preset", checked(presetGroup)).putInt("res", checked(resGroup)).putInt("aiQuality", checked(aiScaleGroup))
                .putInt("gamma", checked(gammaGroup)).putBoolean("ai", aiSwitch.isChecked()).putBoolean("ncnn", ncnnSwitch.isChecked()).putBoolean("tmp", temporalSwitch.isChecked())
                .putBoolean("deb", debandSwitch.isChecked()).putBoolean("measure", measureSwitch.isChecked()).putBoolean("audio", audioSwitch.isChecked()).putBoolean("crop", cropSwitch.isChecked())
                .putInt("bitrate", sBitrate.getProgress()).putInt("aiAmt", sAi.getProgress()).putInt("dn", sDenoise.getProgress()).putInt("dnc", sChroma.getProgress())
                .putInt("tmps", sTemporal.getProgress()).putInt("sh", sSharpen.getProgress()).putInt("exp", sExpand.getProgress()).putInt("peak", sPeak.getProgress())
                .putInt("vib", sVibrance.getProgress()).apply();
        refreshAll();
    }

    // ------------------------------------------------------------------ refresh
    /** Exactly the plan ExportJob will use (encoder, aligned size, fps); the self test must match it. */
    private ExportJob.Plan plan() {
        if (st.caps == null) return null;
        if (st.src == null) return ExportJob.planFor(st.caps, preset(), 1920, 1080, FrameRate.FPS_30, 0);
        return ExportJob.planOutput(st.caps, st.src, preset(), targetShort(), bitrate());
    }

    private DeviceCaps.Enc plannedEncoder() { ExportJob.Plan p = plan(); return p == null || p.error != null ? null : p.enc; }

    /**
     * Why a self test that did run does not allow this export, in one or two lines. A configure refusal is not
     * "run the test first": the test ran and could not start.
     */
    private static String selfTestBlocked(EncodeSelfTest.Result r) {
        String col = r.verdict.colour == null ? "" : r.verdict.colour.detail;
        String d = r.detail == null ? "" : r.detail;
        if (col.contains("VideoEncoder.configure")) {
            StringBuilder b = new StringBuilder("تعذّر بدء الفحص: رفض المرمّز الإعدادات (VideoEncoder.configure)");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("errorCode=(-?\\d+)").matcher(d);
            if (m.find()) b.append(" — رمز الخطأ ").append(m.group(1)).append(String.format(java.util.Locale.US, " (0x%08x)", Long.parseLong(m.group(1)) & 0xFFFFFFFFL));
            int i = d.indexOf("أول خطوة رُفضت: ");
            if (i >= 0) { int e = d.indexOf('\n', i); b.append("\n").append(d, i, e < 0 ? d.length() : e); }
            else if (d.contains("كل خطوات السلّم قُبلت")) b.append("\nكل خطوات تشخيص التهيئة قُبلت منفصلة؛ التفاصيل في نتيجة الفحص.");
            return b.toString();
        }
        if (col.contains("EGL")) return "تعذّر بدء الفحص: فشلت تهيئة EGL. التفاصيل في نتيجة الفحص.";
        return "فحص مسار التصدير لم يسمح بهذا الإعداد. التفاصيل في نتيجة الفحص.";
    }

    private EncodeSelfTest.Result currentSelfTest() {
        ExportJob.Plan p = plan();
        if (p == null || p.config == null || p.error != null) return null;
        String key = p.config.canonical();
        EncodeSelfTest.Result r = st.selfTests.get(key);
        if (r == null) {
            r = EncodeSelfTest.load(this, p.config);
            if (r != null) st.selfTests.put(key, r);
        }
        return r;
    }

    private void refreshAll() {
        srcText.setText(st.src == null ? "لم يُختر فيديو" : st.src.summaryArabic());
        if (st.caps != null) devText.setText(st.caps.exportReport() + "\n" + st.caps.displayReport());
        EncodeSelfTest.Result r = currentSelfTest();
        DeviceCaps.Enc e = plannedEncoder();
        if (e == null) selfTestText.setText(st.caps == null ? "" : "لا يوجد مرمّز مناسب لهذا الإخراج");
        else if (r == null) {
            ExportJob.Plan pl = plan();
            selfTestText.setText("لم يُفحص مسار " + (preset().hdr ? "HDR10" : "SDR") + " بهذا الإعداد بالضبط (" + pl.config.describe()
                    + "). التصدير يتطلب الفحص بنفس الإعداد؛ تغيير معدل البت أو الدقة أو معدل الإطارات يتطلب فحصاً جديداً.");
        }
        else selfTestText.setText(r.summary());
        if (st.trialReport != null) trialText.setText(st.trialReport);
        boolean hdrSrc = st.src != null && st.src.isHdr();
        aiSwitch.setEnabled(!hdrSrc);
        if (hdrSrc && aiSwitch.isChecked()) aiSwitch.setChecked(false);
        ncnnSwitch.setEnabled(!hdrSrc && aiSwitch.isChecked());
        if (hdrSrc && ncnnSwitch.isChecked()) ncnnSwitch.setChecked(false);
        if (st.aiCompareReport != null) compareText.setText(st.aiCompareReport);
        String ai = (st.aiStatus == null ? "حالة النموذج: لم يُفحص (يُفحص تلقائياً عند التصدير)" : "حالة النموذج: " + st.aiStatus)
                + "\nأعلى جودة: الأفضل في الاختبارات (يحافظ على قوام الشعر والبشرة). الأسرع: نحو 4 أضعاف السرعة مع فقدان قوام دقيق."
                + (st.src != null && Math.min(st.src.width, st.src.height) > 1080 ? "\nمصدر 4K بأعلى جودة مكلف جداً: شغّل «معاينة مقطع» أولاً لقياس الزمن الفعلي، أو استخدم التحسين التقليدي (أطفئ AI)." : "");
        if (hdrSrc) ai = "AI غير متاح لمصدر HDR: النموذج مدرّب على صور SDR، وتمرير PQ إليه يفسد الإضاءات. يُستخدم التحسين التقليدي.";
        aiText.setText(ai);
        long br = bitrate();
        bitrateLabel.setText(st.src == null ? "معدل البت: يُحدد بعد اختيار الفيديو" :
                String.format(Locale.US, "معدل البت: %.1f Mbps (نقطة بداية %s، عدّلها وقارن على عينات)", br / 1e6, preset().label));
        if (st.src != null) {
            long need = (long) ((br + 320_000) / 8.0 * st.src.durationUs / 1e6);
            long avail = new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
            storageText.setText(String.format(Locale.US, "الحجم المتوقع ~%d MB · المتاح %d MB%s", need >> 20, avail >> 20, avail < need * 1.1 ? " ⚠ المساحة غير كافية" : ""));
            storageText.setTextColor(avail < need * 1.1 ? Color.parseColor("#FF6B6B") : dim);
            posLabel.setText(String.format(Locale.US, "الموضع: %.1f ث", posUs() / 1e6));
            if (st.measuredMsPerFrame > 0) {
                PipelineParams p = params();
                int passes = preset().hdr && measureSwitch.isChecked() ? 2 : 1;
                double sec = st.src.frameCount * st.measuredMsPerFrame / 1000.0 * passes;
                etaText.setText(String.format(Locale.US, "تقدير مبني على قياس المعاينة (%.0f ms/إطار): ~%d دقيقة%s", st.measuredMsPerFrame, (int) Math.ceil(sec / 60),
                        passes == 2 ? " (يفترض أن مرور القياس يستغرق زمن المعالجة نفسه)" : ""));
            }
        }
        onUpdate(JobManager.get().snapshot());
        if (st.before != null) compare.set(st.before, st.after);
    }

    private long posUs() { return st.src == null ? 0 : (long) (st.src.firstPtsUs + Math.max(0L, st.src.durationUs - 3_000_000L) * sPos.getProgress() / 1000.0); }

    // ------------------------------------------------------------------ actions
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
        srcText.setText("جارٍ تحليل الملف…");
        bg(new Runnable() { @Override public void run() {
            try {
                SourceInfo s = SourceProbe.probe(getApplicationContext(), uri);
                st.uri = uri; st.src = s; st.before = st.after = null; st.measuredMsPerFrame = 0;
            } catch (Exception e) {
                final String m = e.getMessage();
                ui(new Runnable() { @Override public void run() { srcText.setText("تعذر قراءة الملف: " + m); } });
                return;
            }
            ui(new Runnable() { @Override public void run() { refreshAll(); } });
        }});
    }

    private void runSelfTest() {
        final ExportJob.Plan pl = plan();
        if (pl == null || pl.enc == null) { toastText("لا يوجد مرمّز"); return; }
        if (pl.error != null) { selfTestText.setText(pl.error); return; }
        final EncodeConfig cfg = pl.config;
        selfTestText.setText("جارٍ الفحص بإعداد التصدير (" + cfg.describe() + "): أنماط معروفة ← ترميز ← تحليل البتستريم ← فك ترميز ← قياس…");
        selfTestBtn.setEnabled(false);
        bg(new Runnable() { @Override public void run() {
            final EncodeSelfTest.Result r = EncodeSelfTest.run(getApplicationContext(), pl.enc, cfg);
            st.selfTests.put(cfg.canonical(), r);
            ui(new Runnable() { @Override public void run() { selfTestBtn.setEnabled(true); refreshAll(); } });
        }});
    }

    private void runUndeclaredTrial() {
        if (st.caps == null) { toastText("انتظر فحص الجهاز"); return; }
        trialText.setText("جارٍ اختبار EGL #24/#25/#23 على Surface المرمّز بدون RECORDABLE…");
        trialBtn.setEnabled(false);
        bg(new Runnable() {
            @Override public void run() {
                try {
                    st.trialReport = UndeclaredConfigTrial.run(getApplicationContext(), st.caps);
                } catch (Throwable t) {
                    st.trialReport = "فشل الاختبار التجريبي: " + t.getMessage();
                }
                ui(new Runnable() { @Override public void run() { trialBtn.setEnabled(true); refreshAll(); } });
            }
        });
    }

    private void runEngineCompare() {
        compareText.setText("جارٍ فحص المسار المقسّم لـ ncnn لكل وضع (CPU FP32 وVulkan FP32 وVulkan تخزين FP16 مع حساب FP32) ثم قياس أداء الأوضاع المقبولة. لا يُستخدم فيديو ولا يُفتح التصدير.");
        compareBtn.setEnabled(false);
        perfBtn.setEnabled(false);
        keepScreen(true);
        bg(new Runnable() { @Override public void run() {
            final String report = com.mojahed.hdrstudio.ai.AiEngineCompare.run(getApplicationContext(), new com.mojahed.hdrstudio.ai.AiEngineCompare.Progress() {
                @Override public void onProgress(final String line) {
                    ui(new Runnable() { @Override public void run() { compareText.setText("جارٍ: " + line); } });
                }
            });
            st.aiCompareReport = report;
            ui(new Runnable() { @Override public void run() { keepScreen(false); compareBtn.setEnabled(true); perfBtn.setEnabled(true); compareText.setText(report); } });
        }});
    }

    private void runPerfOnly() {
        compareText.setText("جارٍ قياس الأداء للأوضاع المقبولة في فحص هذه النسخة، بعد انتظار تبريد الهاتف إن لزم. لا يُستخدم فيديو ولا يُفتح التصدير.");
        compareBtn.setEnabled(false);
        perfBtn.setEnabled(false);
        keepScreen(true);
        bg(new Runnable() { @Override public void run() {
            final String report = com.mojahed.hdrstudio.ai.AiEngineCompare.runPerfOnly(getApplicationContext(), new com.mojahed.hdrstudio.ai.AiEngineCompare.Progress() {
                @Override public void onProgress(final String line) {
                    ui(new Runnable() { @Override public void run() { compareText.setText("جارٍ: " + line); } });
                }
            });
            st.aiCompareReport = report;
            ui(new Runnable() { @Override public void run() { keepScreen(false); compareBtn.setEnabled(true); perfBtn.setEnabled(true); compareText.setText(report); } });
        }});
    }

    private void runAiTest() {
        aiText.setText("جارٍ تحميل النموذج وتشغيل متجه الاختبار على GPU…");
        aiTestBtn.setEnabled(false);
        bg(new Runnable() { @Override public void run() {
            String status;
            Egl egl = null;
            GlKit k = null;
            SrEngine sr = null;
            long t0 = System.nanoTime();
            try {
                egl = new Egl(false);
                egl.makeCurrentOffscreen();
                k = new GlKit(new ShaderLoader(getAssets()));
                sr = new SrEngine(k, getAssets());
                sr.selfTest(getAssets());
                status = sr.status + String.format(Locale.US, " · %d ms", (System.nanoTime() - t0) / 1000000);
                st.aiReady = sr.ready;
            } catch (Throwable t) {
                status = "غير جاهز: " + t.getMessage();
                st.aiReady = false;
            } finally {
                if (sr != null) sr.release();
                if (k != null) k.release();
                if (egl != null) egl.release();
            }
            st.aiStatus = status;
            ui(new Runnable() { @Override public void run() { aiTestBtn.setEnabled(true); refreshAll(); } });
        }});
    }

    private void runPreview() {
        if (st.src == null) { toastText("اختر فيديو أولاً"); return; }
        final ExportJob.Plan pl = plan();
        if (pl == null || pl.config == null || pl.error != null) {
            previewNote.setText(pl != null && pl.error != null ? pl.error : "لا توجد خطة تصدير لهذه الإعدادات.");
            return;
        }
        previewBtn.setEnabled(false);
        previewNote.setText("جارٍ معالجة الإطار…");
        final PipelineParams p = params();
        final boolean hdr = preset().hdr;
        final int outW = pl.config.width, outH = pl.config.height;
        final long memAvail = st.caps == null ? 0 : st.caps.memAvail, memTotal = st.caps == null ? 0 : st.caps.memTotal;
        final long t = posUs();
        final boolean crop = cropSwitch.isChecked();
        bg(new Runnable() { @Override public void run() {
            final PreviewRenderer.Result r = PreviewRenderer.render(getApplicationContext(), st.uri, st.src, p, t, hdr, outW, outH, crop, memAvail, memTotal);
            ui(new Runnable() { @Override public void run() {
                previewBtn.setEnabled(true);
                if (r.error != null) { previewNote.setText("فشلت المعاينة: " + r.error); return; }
                st.before = r.before; st.after = r.after;
                compare.set(r.before, r.after);
                String time = r.crop != null
                        ? String.format(Locale.US, "زمن معالجة القص: %d ms (+ تحليل الإطار كاملاً %d ms؛ لا يمثل زمن إطار كامل)", r.ms, r.analysisMs)
                        : String.format(Locale.US, "زمن معالجة الإطار: %d ms", r.ms);
                previewNote.setText(time + " (دون ترميز؛ التنظيف الزمني لا يظهر في إطار واحد؛ مقطع الـ 3 ثوانٍ يُظهر الحركة). " + r.note);
            }});
        }});
    }

    private void startExport(boolean preview) {
        if (st.src == null) { toastText("اختر فيديو أولاً"); return; }
        if (JobManager.get().busy()) { toastText("هناك مهمة قيد التشغيل"); return; }
        Presets ps = preset();
        EncodeSelfTest.Result r = currentSelfTest();
        if (r == null || !r.usable()) {
            statusText.setText(r != null ? selfTestBlocked(r)
                    : ps.hdr ? "لا يمكن تصدير HDR10 قبل نجاح «فحص مسار التصدير» على هذا الجهاز. اختر SDR أو شغّل الفحص."
                    : "شغّل «فحص مسار التصدير» أولاً للتأكد من صحة الألوان.");
            return;
        }
        ExportRequest q = new ExportRequest();
        q.uri = st.uri; q.src = st.src; q.params = params(); q.preset = ps; q.targetShortSide = targetShort();
        q.bitrateOverride = bitrate(); q.measureLight = measureSwitch.isChecked() && !preview; q.keepAudio = audioSwitch.isChecked();
        q.selfTest = r; q.preview = preview;
        if (preview) { q.startUs = posUs(); q.endUs = q.startUs + 3_000_000L; }
        JobManager.get().setPending(q);
        video.setVisibility(View.GONE);
        startForegroundService(new Intent(this, ExportService.class));
    }

    /** One second of the current position, exported with GLSL and with ncnn, same settings (job/AbExport). */
    private void startAbExport() {
        if (st.src == null) { toastText("اختر فيديو أولاً"); return; }
        if (JobManager.get().busy()) { toastText("هناك مهمة قيد التشغيل"); return; }
        Presets ps = preset();
        if (ps.hdr || st.src.isHdr()) { statusText.setText("اختبار A/B لمقطع SDR فقط: اختر مصدراً SDR وإعداد تصدير SDR."); return; }
        PipelineParams pp = params();
        if (!pp.ai) { statusText.setText("فعّل AI أولاً: الاختبار يقارن محركي AI بالإعدادات نفسها."); return; }
        EncodeSelfTest.Result r = currentSelfTest();
        if (r == null || !r.usable()) { statusText.setText(r != null ? selfTestBlocked(r) : "شغّل «فحص مسار التصدير» أولاً للتأكد من صحة الألوان."); return; }
        ExportRequest q = new ExportRequest();
        q.uri = st.uri; q.src = st.src; q.params = pp; q.preset = ps; q.targetShortSide = targetShort();
        q.bitrateOverride = bitrate(); q.measureLight = false; q.keepAudio = audioSwitch.isChecked(); q.selfTest = r;
        q.startUs = posUs(); q.endUs = q.startUs + com.mojahed.hdrstudio.job.AbExport.LENGTH_US;
        JobManager.get().setPendingAb(new com.mojahed.hdrstudio.job.AbExport(q));
        abRunning = true;
        keepScreen(true);
        video.setVisibility(View.GONE);
        startForegroundService(new Intent(this, ExportService.class));
    }

    /** The first frames of the SDR range: without AI, GLSL, GLSL with glFinish before the final draw (job/ExportDiag). */
    private void startExportDiag() {
        if (st.src == null) { toastText("اختر فيديو أولاً"); return; }
        if (JobManager.get().busy()) { toastText("هناك مهمة قيد التشغيل"); return; }
        Presets ps = preset();
        if (ps.hdr || st.src.isHdr()) { statusText.setText("تشخيص التصدير لمقطع SDR فقط: اختر مصدراً SDR وإعداد تصدير SDR."); return; }
        EncodeSelfTest.Result r = currentSelfTest();
        if (r == null || !r.usable()) { statusText.setText(r != null ? selfTestBlocked(r) : "شغّل «فحص مسار التصدير» أولاً للتأكد من صحة الألوان."); return; }
        ExportRequest q = new ExportRequest();
        q.uri = st.uri; q.src = st.src; q.params = params(); q.preset = ps; q.targetShortSide = targetShort();
        q.bitrateOverride = bitrate(); q.measureLight = false; q.keepAudio = audioSwitch.isChecked(); q.selfTest = r;
        q.startUs = posUs(); q.endUs = com.mojahed.hdrstudio.job.ExportDiag.endFor(q.startUs, st.src.avgFps);
        JobManager.get().setPendingAb(new com.mojahed.hdrstudio.job.ExportDiag(q));
        abRunning = true;
        keepScreen(true);
        video.setVisibility(View.GONE);
        startForegroundService(new Intent(this, ExportService.class));
    }

    @Override
    public void onUpdate(JobManager.Snapshot s) {
        if (abRunning && s.abFinal) { abRunning = false; keepScreen(false); }
        if (statusText == null) return;
        boolean running = s.state == JobManager.State.RUNNING;
        exportBtn.setText(running ? "إلغاء" : "بدء التصدير");
        clipBtn.setEnabled(!running);
        if (abBtn != null) abBtn.setEnabled(!running);
        if (diagBtn != null) diagBtn.setEnabled(!running);
        progress.setProgress((int) (s.fraction * 1000));
        String head = (s.preview ? "[معاينة] " : "") + s.stage + (s.thermal.isEmpty() ? "" : " · " + s.thermal);
        statusText.setText(s.state == JobManager.State.FAILED ? head + "\n" + s.error : head);
        if (running) etaText.setText(s.detail + (s.etaSec >= 0 ? String.format(Locale.US, " · متبقٍ ~%d:%02d (مقاس)", (int) (s.etaSec / 60), (int) (s.etaSec % 60)) : ""));
        if (!running && s.state != JobManager.State.IDLE) resultText.setText(s.result);
        if (s.state == JobManager.State.DONE && s.preview && s.outputPath != null && s.msPerFrame > 0) {
            if (st.lastPreviewPath == null || !st.lastPreviewPath.equals(s.outputPath + s.msPerFrame)) {
                st.lastPreviewPath = s.outputPath + s.msPerFrame;
                st.measuredMsPerFrame = s.msPerFrame;
                video.setVisibility(View.VISIBLE);
                video.setVideoURI(Uri.fromFile(new java.io.File(s.outputPath)));
                video.start();
                boolean hdrScreen = st.caps != null && st.caps.displayHdr10;
                previewNote.setText(preset().hdr ? (hdrScreen ? "المقطع HDR10 يُعرض بإضاءة HDR على هذه الشاشة." : "الشاشة لا تعلن دعم HDR10: النظام سيعرض المقطع بتحويل SDR.") : "");
                refreshAll();
            }
        }
        final String out = s.outputUri;
        openBtn.setVisibility(s.state == JobManager.State.DONE && out != null && !s.preview ? View.VISIBLE : View.GONE);
        openBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(out), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }});
    }

    // ------------------------------------------------------------------ widgets
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(top);
        return l;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        t.setTextIsSelectable(true);
        return t;
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(card); g.setCornerRadius(dp(12));
        c.setBackground(g);
        TextView t = text(title, 16, accent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        root.addView(c, lp(12));
        return c;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.BLACK : Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? accent : Color.parseColor("#2C2C33")); g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private Switch sw(LinearLayout parent, String label, boolean on) {
        Switch s = new Switch(this);
        s.setText(label); s.setTextColor(Color.WHITE); s.setChecked(on);
        parent.addView(s, lp(8));
        return s;
    }

    private SeekBar seek(LinearLayout parent, final String label, int min, int max, int value) {
        final TextView t = label == null ? null : text(label + ": " + value, 13, Color.WHITE);
        if (t != null) parent.addView(t, lp(8));
        SeekBar s = new SeekBar(this);
        s.setMin(min); s.setMax(max); s.setProgress(value);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (t != null) t.setText(label + ": " + (b == sPeak ? (int) peakNits() : v));
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { saveAndRefresh(); }
        });
        parent.addView(s);
        return s;
    }

    private RadioGroup radios(LinearLayout parent, String[] labels, int sel) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(View.generateViewId());
            r.setText(labels[i]); r.setTextColor(Color.WHITE);
            g.addView(r);
            if (i == sel) r.setChecked(true);
        }
        parent.addView(g);
        return g;
    }

    private void toastText(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
}
