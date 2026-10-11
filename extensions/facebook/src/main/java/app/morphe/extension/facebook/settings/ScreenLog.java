/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The last screens Facebook brought to the front, for the diagnostic report, while Debug logging is
 * on. Tracing #76 and #18 took screen-by-screen guesses, and this list says which screen a tap
 * opened and what link it carried.
 *
 * <p>Each line keeps the screen's class, the intent's action, and a Meta link's host and path; any
 * other link is written as an outside link and nothing more. A link's query, fragment and user part
 * are never kept, and the report's redaction runs over every line as it does over every section.
 * With Debug logging off nothing is recorded.
 */
final class ScreenLog {
    /** The screens kept, oldest dropped first. */
    static final int LIMIT = 50;

    private static final class Start {
        final long at;
        final String line;

        Start(long at, String line) {
            this.at = at;
            this.line = line;
        }
    }

    private static final ArrayDeque<Start> STARTS = new ArrayDeque<>();

    /** The screen and intent written last, so coming back to the same one isn't written again. */
    private static WeakReference<Activity> lastScreen;
    private static WeakReference<Intent> lastIntent;

    static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() { return "SCREENS OPENED"; }
        @Override public List<String> lines() { return report(SystemClock.elapsedRealtime()); }
        @Override public boolean isAppState() { return true; }
    };

    private ScreenLog() {}

    /** Called as each Facebook screen resumes, on the main thread. Never throws. */
    static void resumed(Activity activity) {
        try {
            if (!Utils.settingsReady() || !BaseSettings.DEBUG.get()) return;
            Intent intent = activity.getIntent();
            if (lastScreen != null && lastScreen.get() == activity && lastIntent != null && lastIntent.get() == intent) return;
            lastScreen = new WeakReference<>(activity);
            lastIntent = new WeakReference<>(intent);
            add(SystemClock.elapsedRealtime(), describe(activity.getClass().getName(),
                    intent == null ? null : intent.getAction(), intent == null ? null : intent.getData()));
        } catch (Throwable ignored) {
            // A screen that can't be described is left out rather than holding Facebook's resume up.
        }
    }

    static void add(long at, String line) {
        synchronized (STARTS) {
            while (STARTS.size() >= LIMIT) STARTS.removeFirst();
            STARTS.addLast(new Start(at, line));
        }
    }

    /**
     * The screen's class, then the intent's action and the link's host and path when it has them.
     * A link that isn't Facebook's own or to a Meta site is written as "(outside link)" and nothing
     * more: Facebook opens outside links in its own browser, and a file or a page someone opened
     * there is theirs, not a report's.
     */
    static String describe(String screen, @Nullable String action, @Nullable Uri link) {
        StringBuilder line = new StringBuilder(screen);
        if (action != null && !action.isEmpty()) line.append(' ').append(action);
        if (link != null) {
            if (!metaLink(link)) {
                line.append(" (outside link)");
            } else {
                String host = link.getHost();
                String path = link.getPath();
                String where = (host == null ? "" : host) + (path == null ? "" : path);
                if (!where.isEmpty()) line.append(' ').append(where);
            }
        }
        return line.toString();
    }

    /** The web hosts Meta runs. */
    private static final String[] META_SITES = {"facebook.com", "fb.com", "fb.me", "fb.watch", "fb.gg", "fbcdn.net",
            "fbsbx.com", "messenger.com", "m.me", "instagram.com", "threads.net", "threads.com", "whatsapp.com",
            "meta.com"};

    /** Whether [link] is one of Facebook's own ({@code fb://} and its {@code fb-} siblings) or a web link to a Meta site. */
    static boolean metaLink(Uri link) {
        String scheme = link.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (scheme.startsWith("fb")) return true;
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        String host = link.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String site : META_SITES) {
            if (host.equals(site) || host.endsWith("." + site)) return true;
        }
        return false;
    }

    /** Oldest first, each with how long before the report it came to the front. */
    static List<String> report(long now) {
        List<String> out = new ArrayList<>();
        synchronized (STARTS) {
            for (Start start : STARTS) {
                out.add(String.format(Locale.ROOT, "%d s before this report: %s",
                        Math.max(0, (now - start.at) / 1000), start.line));
            }
        }
        return out;
    }

    static void clearForTests() {
        synchronized (STARTS) {
            STARTS.clear();
        }
        lastScreen = null;
        lastIntent = null;
    }
}
