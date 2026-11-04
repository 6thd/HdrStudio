package com.mojahed.hdrstudio.job;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.mojahed.hdrstudio.ui.MainActivity;

/**
 * Foreground service that owns the export thread: survives rotation, leaving the app and the
 * screen turning off (partial wake lock). Foreground service type: dataSync.
 */
public final class ExportService extends Service {
    public static final String ACTION_CANCEL = "com.mojahed.hdrstudio.CANCEL";
    private static final String CH = "export";
    private static final int ID = 7;
    private Thread worker;
    private PowerManager.WakeLock wl;
    private JobManager.Listener listener;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) { JobManager.get().cancel(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "التصدير", NotificationManager.IMPORTANCE_LOW));
        // targetSdk 34: dataSync is valid on Android 10-15 and not subject to the Android 15
        // dataSync time limit (that limit applies to apps targeting API 35).
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        startForeground(ID, build("بدء التصدير", "", 0), type);
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        final AbExport ab = JobManager.get().takePendingAb();
        final ExportRequest req = ab == null ? JobManager.get().takePending() : null;
        if (req == null && ab == null) { stopSelf(); return START_NOT_STICKY; }
        PowerManager pm = getSystemService(PowerManager.class);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hdrstudio:export");
        wl.acquire(6 * 60 * 60 * 1000L);
        final ExportJob job = req == null ? null : new ExportJob(this, req);
        if (job != null) JobManager.get().attach(job);
        listener = new JobManager.Listener() {
            @Override public void onUpdate(JobManager.Snapshot s) {
                if (s.state == JobManager.State.RUNNING) {
                    String eta = s.etaSec >= 0 ? String.format(java.util.Locale.US, " · متبقٍ ~%d:%02d", (int) (s.etaSec / 60), (int) (s.etaSec % 60)) : "";
                    nm.notify(ID, build(s.stage, s.detail + eta, (int) (s.fraction * 1000)));
                }
            }
        };
        JobManager.get().addListener(listener);
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try { if (job != null) job.run(); else ab.run(ExportService.this); } finally { finish(); }
            }
        }, "export-gl");
        worker.setPriority(Thread.NORM_PRIORITY);
        worker.start();
        return START_NOT_STICKY;
    }

    private void finish() {
        JobManager.Snapshot s = JobManager.get().snapshot();
        JobManager.get().removeListener(listener);
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = s.state == JobManager.State.DONE ? "اكتمل التصدير" : s.state == JobManager.State.CANCELLED ? "أُلغي التصدير" : "فشل التصدير";
        stopForeground(STOP_FOREGROUND_DETACH);
        if (!s.preview) nm.notify(ID + 1, new Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(s.state == JobManager.State.FAILED ? s.error : s.stage)
                .setContentIntent(open()).setAutoCancel(true).build());
        nm.cancel(ID);
        if (wl != null && wl.isHeld()) wl.release();
        stopSelf();
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build(String title, String text, int progress) {
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, ExportService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title).setContentText(text)
                .setProgress(1000, progress, progress == 0)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open())
                .addAction(new Notification.Action.Builder(null, "إلغاء", cancel).build())
                .build();
    }
}
