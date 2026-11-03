package com.mojahed.hdrstudio.job;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide job state. The export runs in ExportService, independent of any Activity, so a
 * screen rotation or leaving the app does not stop it; the UI re-attaches and reads the state.
 */
public final class JobManager {
    public enum State { IDLE, RUNNING, DONE, FAILED, CANCELLED }

    public static final class Snapshot {
        public State state = State.IDLE;
        public boolean preview;
        public String stage = "", detail = "", result = "", error = "";
        public float fraction;
        public double fps, etaSec = -1, msPerFrame;
        public String outputUri, outputPath;
        public String thermal = "";
    }

    public interface Listener { void onUpdate(Snapshot s); }

    private static final JobManager I = new JobManager();
    public static JobManager get() { return I; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private Snapshot snap = new Snapshot();
    private ExportJob job;
    private ExportRequest pending;

    public synchronized Snapshot snapshot() { return snap; }
    public synchronized boolean busy() { return snap.state == State.RUNNING; }

    public synchronized void setPending(ExportRequest r) { pending = r; }
    public synchronized ExportRequest takePending() { ExportRequest r = pending; pending = null; return r; }
    public synchronized void attach(ExportJob j) { job = j; }

    public void cancel() {
        ExportJob j;
        synchronized (this) { j = job; }
        if (j != null) j.cancel();
    }

    public void addListener(Listener l) {
        synchronized (this) { listeners.add(l); }
        final Snapshot s = snapshot();
        main.post(new Runnable() { @Override public void run() { l.onUpdate(s); } });
    }

    public synchronized void removeListener(Listener l) { listeners.remove(l); }

    void publish(Snapshot s) {
        final List<Listener> ls;
        synchronized (this) { snap = s; ls = new ArrayList<>(listeners); }
        main.post(new Runnable() {
            @Override public void run() { for (Listener l : ls) l.onUpdate(s); }
        });
    }
}
