/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;

/**
 * Keeps the Java crash that ends the app for the diagnostic report's [LATEST JAVA CRASH] section.
 *
 * <p>Android keeps no stack trace for a Java crash: its exit record says CRASH and nothing more, so
 * a report sent after "Facebook keeps stopping" (#94) couldn't say what stopped it. The pause
 * module's uncaught-exception handler hands each crash here before passing it on. The trace and
 * the last diagnostic events go through the redactor and stay in Facebook's own files, so every
 * report exported after it carries them, until the next crash replaces them or the diagnostic log
 * is cleared. While the app's crash file switch is on, the same text also goes to a file in
 * Download/Hushfacebook ({@link CrashFile}), for a Facebook that crashes before settings open.
 *
 * <p>The handler sits in front of Facebook's own, so it also sees an exception one of Facebook's
 * swallows. When the handlers after it return, the process lives on, and an exception off the main
 * thread puts the report back as it was ({@link #survived}) and takes its file back, so it can't
 * push out a crash from an earlier start. One swallowed on the main thread stays: that thread is gone, and Facebook freezes.
 *
 * <p>Each save answers its own {@link Saved}, so a swallowed exception takes back only what it
 * wrote. While another thread's crash saved after it is still waiting on its handlers, the report
 * is that crash's, and it stays. Clear diagnostic data takes the waiting saves out with the report
 * ({@link #clear}), so one that lives on after it can't write a cleared report back.
 */
public final class JavaCrashReport {
    /** How much of the diagnostic event log goes in after the trace. */
    static final int RECENT_EVENTS_MAX_CHARS = 12_000;

    /** How much of the trace Android kept for a hang goes in its crash file. */
    static final int ANR_TRACE_MAX_CHARS = 16_000;

    /** How far into a hang's trace the main thread is looked for. */
    static final int ANR_TRACE_SCAN_MAX_CHARS = 2_000_000;

    /** The exception kept last, weakly: a handler Facebook put on top of ours can hand it back. */
    private static volatile WeakReference<Throwable> lastSaved = new WeakReference<>(null);

    /**
     * The saves whose handlers haven't let the process live on, oldest first, each in the report
     * after the one before it. A main thread's stays, as its report does. Guarded by
     * {@link LogBufferManager#CLEAR_UNDO_LOCK}, which Clear diagnostic data and its Undo hold too,
     * so a clear can't land between a save reading the report and writing over it.
     */
    private static final List<Saved> PENDING = new ArrayList<>();

    /**
     * Takes the crash files in Download/Hushfacebook one at a time, each a prune and an insert or a
     * delete. Never held with {@link LogBufferManager#CLEAR_UNDO_LOCK}: every diagnostic event takes
     * that one, and a slow MediaStore would hold them all up. The deaths a start files take it too.
     */
    static final Object FILE_LOCK = new Object();

    /** One save, for {@link #survived}: what the report held before it, and the file it wrote. */
    public static final class Saved {
        /** "" for nothing. A save before this one that lives on hands this one its own. */
        String before;

        /** Set by the thread that saved, before it hands the save to {@link #survived}. */
        @Nullable
        Uri file;

        /** What a clear took this save out with, or null while it waits in {@link #PENDING}. */
        @Nullable
        Cleared cleared;

        Saved(String before) {
            this.before = before;
        }
    }

    /**
     * What Clear diagnostic data took out of the report: the report, and the saves still waiting in
     * it, oldest first. One of them living on after the clear takes back into this, and the cleared
     * report stays cleared. Undo puts the report back with them waiting in it again ({@link #putBack}).
     */
    static final class Cleared {
        String report;
        final List<Saved> waiting;

        /**
         * Undo has put this back. A later step of that Undo can fail and leave it to be tapped
         * again, and a second put back would take the report the first one restored for what a
         * waiting save wrote over.
         */
        boolean restored;

        Cleared(String report, List<Saved> waiting) {
            this.report = report;
            this.waiting = waiting;
        }
    }

    private JavaCrashReport() {
    }

    /**
     * Keeps [throwable] as the latest Java crash, unless it's the one kept last. Answers the save,
     * for {@link #survived}, or null when nothing was written. Never throws.
     */
    @Nullable
    public static Saved save(@Nullable Thread thread, @Nullable Throwable throwable) {
        try {
            Context context = Utils.getContext();
            if (context == null || throwable == null || lastSaved.get() == throwable) return null;
            lastSaved = new WeakReference<>(throwable);
            String report = build(context, thread, throwable);
            Saved saved;
            synchronized (LogBufferManager.CLEAR_UNDO_LOCK) {
                saved = new Saved(LogBufferManager.readCrashReport(context));
                LogBufferManager.persistCrashReport(context, report);
                PENDING.add(saved);
            }
            // The save waits in PENDING already, so it's answered even when the file can't be had:
            // left without an answer, it would stay there for good.
            try {
                if (CrashFile.wanted()) {
                    synchronized (FILE_LOCK) {
                        saved.file = CrashFile.write(context, report, System.currentTimeMillis());
                    }
                }
            } catch (Throwable noFile) {
                // The report holds the crash; only the copy in Download/Hushfacebook is missing.
            }
            return saved;
        } catch (Throwable ignored) {
            // Crash-time code must never get in the way of the handlers after this one.
            return null;
        }
    }

    /**
     * The handlers after ours returned for [thread]'s exception, so the process lives on. Off the
     * main thread, its file goes, and the report goes back to what it held before [saved], unless a
     * newer save is in it. That one then goes back there when it lives on too. After a clear, all
     * of that happens to what the clear took out, not the report. Never throws.
     */
    public static void survived(@Nullable Thread thread, @Nullable Saved saved) {
        try {
            Context context = Utils.getContext();
            if (context == null || saved == null || thread == null || thread == Looper.getMainLooper().getThread()) return;
            synchronized (LogBufferManager.CLEAR_UNDO_LOCK) {
                List<Saved> chain = saved.cleared == null ? PENDING : saved.cleared.waiting;
                int at = chain.indexOf(saved);
                if (at < 0) return;
                chain.remove(at);
                if (at < chain.size()) {
                    chain.get(at).before = saved.before;
                } else if (saved.cleared != null) {
                    saved.cleared.report = saved.before;
                } else {
                    LogBufferManager.persistCrashReport(context, saved.before);
                }
            }
            if (saved.file != null) {
                synchronized (FILE_LOCK) {
                    CrashFile.takeBack(context, saved.file);
                }
            }
        } catch (Throwable ignored) {
            // The exception's report stays; nothing else is lost.
        }
    }

    static String build(Context context, @Nullable Thread thread, Throwable throwable) {
        StringBuilder report = facts(context, System.currentTimeMillis(), Application.getProcessName(), true);
        report.append("thread: ").append(thread == null ? "unknown" : thread.getName()).append('\n')
                .append("exception: ").append(throwable.getClass().getName()).append('\n')
                .append("\n[STACK TRACE]\n").append(stackTrace(throwable));
        String recent = LogBufferManager.snapshotForCrash(RECENT_EVENTS_MAX_CHARS);
        if (!recent.isEmpty()) report.append("\n[RECENT EVENTS]\n").append(recent).append('\n');
        return redacted(report, true);
    }

    /**
     * The crash file for a death Android recorded and the next start found: a native crash, a hang
     * or a memory-limiter kill, which never reach the Java handler. The same header as a Java
     * crash's, dated when the process died, then [what] it was and Android's own account of it, and
     * for a hang the main thread's part of the trace Android kept. A death from before the last
     * install or update, at [installedAt], says when that was and leaves the versions unknown:
     * this build's could be wrong for the one that died. So does any death when [installedAt] is
     * negative, Android not saying when that was.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    public static String buildForExit(Context context, ApplicationExitInfo exit, String what, long installedAt) {
        boolean versionsKnown = installedAt >= 0 && exit.getTimestamp() > installedAt;
        StringBuilder report = facts(context, exit.getTimestamp(), exit.getProcessName(), versionsKnown);
        if (!versionsKnown) {
            report.append("installed_utc: ")
                    .append(installedAt >= 0 ? Instant.ofEpochMilli(installedAt).toString() : "unknown").append('\n');
        }
        String description = exit.getDescription();
        report.append("death: ").append(what).append('\n')
                .append("exit_reason: ").append(exit.getReason()).append('\n')
                .append("exit_description: ").append(description == null ? "none" : description).append('\n')
                .append("exit_status: ").append(exit.getStatus()).append('\n')
                .append("exit_importance: ").append(exit.getImportance()).append('\n')
                .append("exit_pss_kb: ").append(exit.getPss()).append('\n')
                .append("exit_rss_kb: ").append(exit.getRss()).append('\n')
                .append("found_at_utc: ").append(Instant.ofEpochMilli(System.currentTimeMillis())).append('\n');
        if (exit.getReason() == ApplicationExitInfo.REASON_ANR) {
            String trace = traceFromMain(exit);
            if (!trace.isEmpty()) report.append("\n[ANR TRACE]\n").append(trace).append('\n');
        }
        return redacted(report, versionsKnown);
    }

    /**
     * The lines both kinds of crash file start with, for a process named [process] at [when], with
     * this build's versions unless [versionsKnown] says they could be another build's.
     */
    private static StringBuilder facts(Context context, long when, @Nullable String process, boolean versionsKnown) {
        return new StringBuilder()
                .append("timestamp_utc: ").append(Instant.ofEpochMilli(when)).append('\n')
                .append("package: ").append(context.getPackageName()).append('\n')
                .append("app_version: ").append(versionsKnown ? Utils.getAppVersionName() : "unknown").append('\n')
                .append("morphe_version: ").append(versionsKnown ? Utils.getPatchesReleaseVersion() : "unknown").append('\n')
                .append("android_api: ").append(Build.VERSION.SDK_INT).append('\n')
                .append("abi: ").append(LogBufferManager.abiLine()).append('\n')
                .append("process: ").append(process).append('\n');
    }

    private static String redacted(StringBuilder report, boolean versionsKnown) {
        long versionCode = versionsKnown ? Utils.getAppVersionCode() : -1L;
        String patchBuild = versionsKnown ? Utils.getPatchesBuildIdentity() : null;
        // Messages and log lines can carry addresses and ids: a download names what it couldn't reach.
        // These two values are a numeric package fact and a literal verified and embedded by the
        // patcher. Prose redaction would mistake digit runs in valid hashes for content ids.
        return "schema: 1\ncomplete: true\n"
                + "app_version_code: " + (versionCode < 0 ? "unknown" : Long.toString(versionCode)) + '\n'
                + "patch_build: " + (patchBuild == null || patchBuild.trim().isEmpty() ? "unknown" : patchBuild) + '\n'
                + DiagnosticRedactor.redact(report.toString());
    }

    /**
     * Up to {@link #ANR_TRACE_MAX_CHARS} of the trace Android kept for a hang, from the main
     * thread's stack on, after the line that says why ({@code Subject:}). ART's dump opens with
     * the class loaders and GC timings, which in Facebook can run past the limit before the first
     * thread, so the head alone would hold no stack. A trace without a main thread found in the
     * first {@link #ANR_TRACE_SCAN_MAX_CHARS} gives its head instead. "" when Android kept none.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    static String traceFromMain(ApplicationExitInfo exit) {
        try (InputStream trace = exit.getTraceInputStream()) {
            if (trace == null) return "";
            BufferedReader lines = new BufferedReader(new InputStreamReader(trace, StandardCharsets.UTF_8));
            StringBuilder head = new StringBuilder();
            StringBuilder main = null;
            String subject = null;
            long scanned = 0;
            String line;
            while ((line = lines.readLine()) != null) {
                if (main == null) {
                    if (line.startsWith("\"main\"")) {
                        main = new StringBuilder();
                        if (subject != null) main.append(subject).append("\n\n");
                    } else {
                        if (subject == null && line.startsWith("Subject:")) subject = line;
                        if (head.length() < ANR_TRACE_MAX_CHARS) head.append(line).append('\n');
                        scanned += line.length() + 1;
                        if (scanned > ANR_TRACE_SCAN_MAX_CHARS) break;
                        continue;
                    }
                }
                main.append(line).append('\n');
                if (main.length() >= ANR_TRACE_MAX_CHARS) break;
            }
            StringBuilder kept = main != null ? main : head;
            if (kept.length() > ANR_TRACE_MAX_CHARS) kept.setLength(ANR_TRACE_MAX_CHARS);
            return kept.toString();
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /** The whole trace, causes and suppressed ones included, as Java prints it. */
    private static String stackTrace(Throwable throwable) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            throwable.printStackTrace(writer);
        }
        return text.toString();
    }

    /**
     * For Clear diagnostic data, before it deletes the report: the report as it stands and the saves
     * waiting in it, taken out together, so none of them can write it back.
     */
    static Cleared clear(@Nullable Context context) {
        synchronized (LogBufferManager.CLEAR_UNDO_LOCK) {
            // No context, no report to delete either: the saves keep writing to it.
            if (context == null) return new Cleared("", new ArrayList<>());
            Cleared cleared = new Cleared(LogBufferManager.readCrashReport(context), new ArrayList<>(PENDING));
            for (Saved saved : cleared.waiting) saved.cleared = cleared;
            PENDING.clear();
            return cleared;
        }
    }

    /**
     * Undo of a clear: the report goes back with its saves waiting in it again, as if it was never
     * cleared. While its place is empty it goes straight back. A crash saved since keeps the place,
     * and while that one waits on its handlers the cleared report goes in under it: the oldest save
     * since takes it as what it wrote over, so it comes back if they all live on. It used to stay
     * with the clear, and the report ended empty. Once only for each clear. Throws when the report
     * can't be written.
     */
    static void putBack(@Nullable Context context, Cleared cleared) throws Exception {
        synchronized (LogBufferManager.CLEAR_UNDO_LOCK) {
            if (cleared.restored) return;
            if (!LogBufferManager.restoreJavaCrashIfMissing(context, cleared.report)) {
                // The clear took every save waiting before it, and this is its first put back, so
                // these all came after.
                if (PENDING.isEmpty()) return;
                PENDING.get(0).before = cleared.report;
            }
            cleared.restored = true;
            for (Saved saved : cleared.waiting) saved.cleared = null;
            PENDING.addAll(0, cleared.waiting);
            cleared.waiting.clear();
        }
    }

    /** Forgets the exception kept last and the saves waiting, as in a new process. Called by the pause module's reset. */
    public static void resetForTests() {
        lastSaved = new WeakReference<>(null);
        synchronized (LogBufferManager.CLEAR_UNDO_LOCK) {
            PENDING.clear();
        }
    }
}
