/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.JavaCrashReport;

/**
 * Pause Hushfacebook, and the safe mode that turns it on without being asked.
 *
 * <p>Whether a process runs paused is decided once, at the first context, before any hook reads
 * a setting. Paused, every setting that changes Facebook answers its unpatched value
 * ({@link Setting#get()}); saved values stay as they are and come back on the next start.
 *
 * <p>Three things pause a process: the Pause switch, safe mode, and a file named
 * {@value #MARKER_FILE_NAME} in Facebook's own folder under {@code Android/data} or
 * {@code Android/media}, for a phone that can't reach the settings at all. Safe mode turns on after
 * three starts in a row that crashed within a minute. A start is recorded when the process begins
 * and cleared once it has run for a
 * minute, so a record that is still there at the next start belonged to a process that died
 * young. Android 11 and newer say why it died, and only a crash, a native crash or a hang counts;
 * being swiped away or force-stopped does not. Older Android counts the crashes that reached the
 * Java uncaught-exception handler.
 */
public final class HushfacebookPause {
    /** Why this process runs paused. */
    public enum Reason { NONE, SWITCH, CRASH_LOOP, MARKER_FILE }

    public static final String MARKER_FILE_NAME = "hushfacebook-safe-mode";
    static final String START_RECORD_NAME = "hushfacebook-start";
    static final String CRASH_STREAK_NAME = "hushfacebook-start-crashes";
    static final long START_WINDOW_MS = 60_000L;
    static final int CRASHES_TO_SAFE_MODE = 3;
    private static final String CRASHED = "crashed";

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean CRASH_MARKED = new AtomicBoolean();
    private static volatile Reason reason = Reason.NONE;
    private static volatile long startElapsed;
    @Nullable private static volatile File filesDir;
    @Nullable private static volatile Context appContext;
    /** Whether this process installed the crash handler, so {@link #keepCrashMarkOnTop} keeps it there. */
    private static volatile boolean crashMarkWanted;
    /** How many times a process puts the crash handler back in front: Facebook installs five of its own. */
    static final int CRASH_MARK_MOVES_MAX = 8;
    private static final AtomicInteger CRASH_MARK_MOVES = new AtomicInteger();

    /** Whether Android still holds this process in its crash state. Tests put an answer here. */
    interface CrashState {
        boolean stillCrashing();
    }

    static CrashState crashState = HushfacebookPause::inErrorState;

    /** Runs while the pause is being decided, so a test can see what a hook sees then. */
    @Nullable static volatile Runnable whileDecidingForTests;

    private HushfacebookPause() {
    }

    /** Runs at the first context of every process. Later calls do nothing. */
    public static void onProcessStart(Context context) {
        if (context == null || !STARTED.compareAndSet(false, true)) return;
        startElapsed = SystemClock.elapsedRealtime();
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
        try {
            filesDir = context.getFilesDir();
            if (Utils.isMainProcess()) countLastStart(context);
        } catch (RuntimeException failure) {
            // Never let the safety net be what stops Facebook from starting.
            Logger.printException(() -> "Hushfacebook pause: could not read the last start", failure);
        }
        Runnable probe = whileDecidingForTests;
        if (probe != null) probe.run();
        reason = decide(context);
        Setting.setPausedForProcess(reason != Reason.NONE);
        if (reason != Reason.NONE) {
            Logger.printInfo(() -> "Hushfacebook is paused for this start: " + reason);
        }
    }

    public static Reason reason() {
        return reason;
    }

    /** The same answer every setting gives: this process runs paused. */
    public static boolean isPaused() {
        return Setting.isPaused();
    }

    /**
     * Turns the marker files, safe mode and the Pause switch off, and starts the crash count again.
     * The change applies from the next start. The two switches go in one commit and only once both
     * markers are gone, so a step that fails leaves the pause the person had rather than part of it.
     * The crash count is reset last: one that can't be only lets safe mode count on from where it was.
     *
     * @return why the next start still runs paused, read back from what was kept; NONE when it won't.
     */
    public static Reason turnBackOn(Context context) {
        if (removeMarkers(context)) {
            Map<Setting<?>, Object> on = new HashMap<>();
            on.put(BaseSettings.SAFE_MODE, false);
            on.put(BaseSettings.PAUSED, false);
            try {
                Setting.saveAll(on);
                File dir = filesDir != null ? filesDir : context.getFilesDir();
                write(new File(dir, CRASH_STREAK_NAME), "0");
            } catch (IOException | RuntimeException failure) {
                Logger.printException(() -> "Hushfacebook pause: could not turn back on", failure);
            }
        }
        return decide(context);
    }

    /** The marker that pauses Hushfacebook from outside the app, or null when there is no storage. */
    @Nullable
    public static File markerFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, MARKER_FILE_NAME);
    }

    /**
     * The same marker in Facebook's own folder under {@code Android/media}, or null when there is no
     * storage. Android 11 refuses adb's shell in {@code Android/data} but lets it make a file here.
     * Asking Android for the folder makes it, so the file goes in without a {@code mkdir}; when
     * Android gives none, the folder's usual place on the phone's own storage stands in.
     */
    @Nullable
    @SuppressWarnings("deprecation")
    public static File mediaMarkerFile(Context context) {
        try {
            File[] dirs = context.getExternalMediaDirs();
            File dir = dirs != null && dirs.length > 0 ? dirs[0] : null;
            if (dir == null) {
                File storage = android.os.Environment.getExternalStorageDirectory();
                if (storage == null) return null;
                dir = new File(storage, "Android/media/" + context.getPackageName());
            }
            return new File(dir, MARKER_FILE_NAME);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** The marker that is there now, the files folder's first, or null when neither is. */
    @Nullable
    public static File presentMarker(Context context) {
        File marker = markerFile(context);
        if (marker != null && marker.exists()) return marker;
        File media = mediaMarkerFile(context);
        return media != null && media.exists() ? media : null;
    }

    /** Deletes both markers; false when one is still there. */
    private static boolean removeMarkers(Context context) {
        boolean gone = true;
        for (File marker : new File[]{markerFile(context), mediaMarkerFile(context)}) {
            if (marker != null && marker.exists() && !marker.delete()) gone = false;
        }
        return gone;
    }

    /** Whether the next start runs paused, as things stand now. */
    public static boolean pausesNextStart(Context context) {
        return decide(context) != Reason.NONE;
    }

    static Reason decide(Context context) {
        if (presentMarker(context) != null) return Reason.MARKER_FILE;
        if (BaseSettings.SAFE_MODE.savedValue()) return Reason.CRASH_LOOP;
        if (BaseSettings.PAUSED.savedValue()) return Reason.SWITCH;
        return Reason.NONE;
    }

    /**
     * Reads the record the last start left, if it died before clearing it, adds it to the streak
     * of young crashes, and leaves a record for this start.
     */
    private static void countLastStart(Context context) {
        File dir = filesDir;
        if (dir == null) return;
        File record = new File(dir, START_RECORD_NAME);
        File streakFile = new File(dir, CRASH_STREAK_NAME);
        String last = read(record);
        if (last != null) {
            int streak = diedYoungFromACrash(context, last) ? parseCount(read(streakFile)) + 1 : 0;
            if (streak >= CRASHES_TO_SAFE_MODE) {
                BaseSettings.SAFE_MODE.save(true);
                streak = 0;
                Logger.printInfo(() -> "Hushfacebook safe mode: three starts in a row crashed within a minute");
            }
            write(streakFile, Integer.toString(streak));
        }
        write(record, Process.myPid() + " " + System.currentTimeMillis());
        installCrashMark();
        new Handler(Looper.getMainLooper()).postDelayed(HushfacebookPause::survivedTheStart, START_WINDOW_MS);
    }

    /**
     * A minute in, this start did not crash: the record goes and the streak ends.
     *
     * <p>A crash the handler marked can still be ending the process at this point. Android holds
     * a process that crashed again open behind its "keeps stopping" dialog, and a crash off the
     * main thread leaves this looper running meanwhile, so clearing the record here would forget
     * the crash. While Android still has the process in its crash state the record stays for the
     * next start to judge. An exception a later handler swallowed leaves no crash state, and
     * that start did survive.
     */
    static void survivedTheStart() {
        File dir = filesDir;
        if (dir == null) return;
        if (CRASH_MARKED.get() && crashState.stillCrashing()) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir, START_RECORD_NAME).delete();
        write(new File(dir, CRASH_STREAK_NAME), "0");
    }

    private static boolean inErrorState() {
        Context context = appContext;
        if (context == null) return false;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.ProcessErrorStateInfo> errors =
                    manager == null ? null : manager.getProcessesInErrorState();
            if (errors == null) return false;
            int pid = Process.myPid();
            for (ActivityManager.ProcessErrorStateInfo error : errors) {
                if (error.pid == pid) return true;
            }
        } catch (RuntimeException unanswered) {
            Logger.printException(() -> "Hushfacebook pause: could not ask for the crash state", unanswered);
        }
        return false;
    }

    static boolean diedYoungFromACrash(Context context, String record) {
        String[] parts = record.trim().split(" ");
        int pid;
        long started;
        try {
            pid = Integer.parseInt(parts[0]);
            started = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
        } catch (RuntimeException unreadable) {
            return false;
        }
        boolean markedByHandler = parts.length > 2 && CRASHED.equals(parts[2]);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Boolean young = exitSaysDiedYoung(context, pid, started);
            if (young != null) return young;
        }
        return markedByHandler;
    }

    /** What Android's exit record since the start says, or null when it kept none. */
    @RequiresApi(Build.VERSION_CODES.R)
    @Nullable
    private static Boolean exitSaysDiedYoung(Context context, int pid, long started) {
        ApplicationExitInfo exit = firstExitSince(context, pid, started);
        if (exit == null) return null;
        int reason = exit.getReason();
        boolean crash = reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR;
        // Android writes a crash down when it is reported, before any "keeps stopping"
        // dialog holds the process open, so the first record since the start says when
        // the crash happened. A process that lived past the minute by the clock on the
        // wall, the device asleep or not, did not die young.
        return crash && exit.getTimestamp() - started <= START_WINDOW_MS;
    }

    /**
     * The earliest exit Android recorded for the pid since the start, or null when there is
     * none. A record older than the start belonged to an earlier process that had the same pid.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Nullable
    private static ApplicationExitInfo firstExitSince(Context context, int pid, long started) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return null;
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, pid, 16);
        ApplicationExitInfo first = null;
        if (exits == null) return null;
        for (ApplicationExitInfo exit : exits) {
            if (exit.getPid() != pid || exit.getTimestamp() < started) continue;
            if (first == null || exit.getTimestamp() < first.getTimestamp()) first = exit;
        }
        return first;
    }

    /**
     * Marks the start record when an uncaught exception ends the process inside the first
     * minute, for Android before 11, which keeps no exit reasons, and keeps the crash's trace for
     * the diagnostic report ({@link JavaCrashReport}), since Android keeps none. The handler only
     * writes two files and hands the crash on.
     */
    private static void installCrashMark() {
        crashMarkWanted = true;
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashMark) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashMark(current));
    }

    /**
     * Puts the crash handler back in front when something replaced the default handler since the
     * start. Facebook installs five handlers of its own during its start-up, each wrapping the one
     * before it (581 {@code LX/0lE;->A00}, {@code LX/05L;->start}, {@code LX/0uA;->start}, {@code
     * LX/0hs;->A0Y}, and the browser's {@code LX/f4g}), and one that ends the process itself would
     * never reach ours. Run as each screen is created; a process that never installed the handler
     * (anything but the main one) is left alone. It moves to the front at most
     * {@link #CRASH_MARK_MOVES_MAX} times a process, so a handler that puts itself back in front
     * whenever it isn't can't grow the chain without end. Never throws.
     */
    public static void keepCrashMarkOnTop() {
        if (!crashMarkWanted || Thread.getDefaultUncaughtExceptionHandler() instanceof CrashMark) return;
        try {
            if (CRASH_MARK_MOVES.incrementAndGet() > CRASH_MARK_MOVES_MAX) return;
            installCrashMark();
        } catch (Throwable failure) {
            Logger.printException(() -> "Hushfacebook pause: could not put the crash handler back in front", failure);
        }
    }

    static void markCrash() {
        File dir = filesDir;
        if (dir == null || SystemClock.elapsedRealtime() - startElapsed > START_WINDOW_MS) return;
        if (!CRASH_MARKED.compareAndSet(false, true)) return;
        File record = new File(dir, START_RECORD_NAME);
        String current = read(record);
        if (current != null && !current.trim().endsWith(CRASHED)) write(record, current.trim() + " " + CRASHED);
    }

    private static final class CrashMark implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler delegate;

        CrashMark(Thread.UncaughtExceptionHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            try {
                markCrash();
            } catch (Throwable ignored) {
                // The crash belongs to the handler after this one.
            }
            JavaCrashReport.Saved saved = JavaCrashReport.save(thread, throwable);
            if (delegate != null) delegate.uncaughtException(thread, throwable);
            // Still here: the handlers after this one let the process live on.
            JavaCrashReport.survived(thread, saved);
        }
    }

    static int parseCount(@Nullable String text) {
        if (text == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    @Nullable
    static String read(File file) {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int length = in.read(buffer);
            return length <= 0 ? "" : new String(buffer, 0, length, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static void write(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "Hushfacebook pause: could not write " + file.getName(), failure);
        }
    }

    static void pauseForTests(Reason why) {
        reason = why;
        Setting.setPausedForProcess(why != Reason.NONE);
    }

    static void resetForTests() {
        STARTED.set(false);
        CRASH_MARKED.set(false);
        crashMarkWanted = false;
        CRASH_MARK_MOVES.set(0);
        JavaCrashReport.resetForTests();
        reason = Reason.NONE;
        filesDir = null;
        appContext = null;
        crashState = HushfacebookPause::inErrorState;
        Setting.setPausedForProcess(false);
    }
}
