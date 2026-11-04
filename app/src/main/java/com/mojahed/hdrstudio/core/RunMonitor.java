package com.mojahed.hdrstudio.core;

import android.content.Context;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Process-wide record of what can disturb a timed run: the app leaving or returning to the
 * foreground and the screen turning off or on (MainActivity feeds both). A timed interval is
 * "interrupted" if such an event falls inside it, or the app was not in the foreground or the
 * screen not interactive at its start or end. Nothing is dropped: callers report the reason.
 */
public final class RunMonitor {
    public static final class Event { public final long ms; public final String what; Event(long ms, String what) { this.ms = ms; this.what = what; } }

    private static final List<Event> events = new ArrayList<>();
    private static volatile boolean foreground;

    private RunMonitor() {}

    public static synchronized void mark(String what) {
        events.add(new Event(System.currentTimeMillis(), what));
        if (events.size() > 500) events.remove(0);
    }

    public static void setForeground(boolean fg) {
        foreground = fg;
        mark(fg ? "التطبيق في الواجهة" : "التطبيق انتقل إلى الخلفية");
    }

    public static boolean foreground() { return foreground; }

    public static boolean interactive(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    public static synchronized List<Event> between(long t0, long t1) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) if (e.ms >= t0 && e.ms <= t1) out.add(e);
        return out;
    }

    /** State at one instant, for the start and the end of an interval. */
    public static final class State {
        public final long ms; public final boolean fg, screen;
        public State(long ms, boolean fg, boolean screen) { this.ms = ms; this.fg = fg; this.screen = screen; }
    }

    public static State now(Context ctx) { return new State(System.currentTimeMillis(), foreground, interactive(ctx)); }

    /** Why [a, b] is interrupted, or null if it is clean. */
    public static String interruption(State a, State b) {
        StringBuilder r = new StringBuilder();
        if (!a.fg || !b.fg) r.append("التطبيق ليس في الواجهة عند ").append(!a.fg ? "البداية" : "النهاية").append("؛ ");
        if (!a.screen || !b.screen) r.append("الشاشة غير نشطة عند ").append(!a.screen ? "البداية" : "النهاية").append("؛ ");
        for (Event e : between(a.ms, b.ms)) r.append(e.what).append(" عند ").append(clock(e.ms)).append("؛ ");
        return r.length() == 0 ? null : r.substring(0, r.length() - 2);
    }

    public static String clock(long ms) { return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(ms)); }
}
