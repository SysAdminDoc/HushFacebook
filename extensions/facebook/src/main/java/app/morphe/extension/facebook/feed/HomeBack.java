/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Back on a scrolled-down Home (#247). NewsFeedFragment's onBackPressed scrolls a feed that isn't
 * at the top back to it and starts a refresh there, so the post you came back to after writing a
 * comment is gone. With Back leaves Home where it is on, the first Back only says how to get to the
 * top, and a second Back within {@link #SECOND_BACK_MS} gets Facebook's own scroll and refresh.
 * A Back on a feed already at the top, and every Back with the switch off, is Facebook's.
 *
 * <p>On some accounts the Back that closes a post's comments over the feed reaches that scroll too,
 * so the feed jumps to the top and reloads as the comments go (#378). With Keep feed position on
 * return on, a Back that arrives while another window over the feed has the focus, or within
 * {@link #CLOSED_OVER_FEED_MS} of the feed's screen getting it back, keeps the feed where it is
 * without a word, and leaves the second-Back count alone.
 */
public final class HomeBack {
    /** How long after the toast a Back still counts as the second one. */
    static final long SECOND_BACK_MS = 2_000L;
    /** How long after a screen over the feed closed a Back still belongs to closing it. */
    static final long CLOSED_OVER_FEED_MS = 1_000L;
    private static final String KEPT = "kept Home where it was on Back";
    private static final String LET_FACEBOOK = "let Facebook go to the top on a second Back";
    private static final String KEPT_AFTER_CLOSE = "kept the feed on a Back that closed a screen over it";
    /** When the last Back that kept Home showed its toast, or -1 once a second Back used it. */
    private static long keptAt = -1;
    /** When the screen in front last got the focus back or resumed, or the feed came back to it, or -1. */
    private static long cameBackAt = -1;
    private static WeakReference<Activity> front = new WeakReference<>(null);
    private static boolean watching;
    /** The decor views already told about focus, so a resume never adds a second listener. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ViewTreeObserver.OnWindowFocusChangeListener FOCUS = focused -> {
        if (focused) cameBack();
    };

    private HomeBack() { }

    /**
     * Follows the screen in front, and when it gets the focus back from a window over it, so a Back
     * that closed comments over the feed can be told from one on the feed itself (#378). Called once
     * the Application is created, only with the patch in the build.
     */
    public static synchronized void watch(Context context) {
        if (watching || !(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new Screens());
        watching = true;
    }

    /** The feed or the screen holding it came back from something over it, now. */
    public static void cameBack() {
        cameBackAt(SystemClock.elapsedRealtime());
    }

    static synchronized void cameBackAt(long now) {
        cameBackAt = now;
    }

    /**
     * Whether a Back at [now] is the one that closed a screen over the feed: the screen in front
     * hasn't got the focus ([frontFocused] false), or got it back within {@link #CLOSED_OVER_FEED_MS}.
     */
    static synchronized boolean closedOverFeedAt(long now, boolean frontFocused) {
        if (!frontFocused) return true;
        return cameBackAt >= 0 && now >= cameBackAt && now - cameBackAt <= CLOSED_OVER_FEED_MS;
    }

    /** Whether the screen in front has the focus; true when no screen is known, so nothing is held on a guess. */
    private static boolean frontFocused() {
        Activity activity = front.get();
        if (activity == null) return true;
        Window window = activity.getWindow();
        return window == null || window.getDecorView().hasWindowFocus();
    }

    /**
     * Called in NewsFeedFragment's onBackPressed once it knows the feed isn't at the top, before it
     * scrolls there. True ends the press as handled with the feed where it is, and a toast says a
     * second Back goes to the top. A failure gives Facebook the press, as it would unpatched.
     */
    public static boolean holdScrollToTop() {
        try {
            HookStatus.invoked(FamilyNames.RETURN_REFRESH);
            if (!Utils.settingsReady()) return false;
            if (Settings.BLOCK_RETURN_REFRESH.get()
                    && closedOverFeedAt(SystemClock.elapsedRealtime(), frontFocused())) {
                Logger.printDebug(() -> "Home back: Back as a screen over the feed closed: kept the feed where it is");
                HookStatus.counted(FamilyNames.RETURN_REFRESH, KEPT_AFTER_CLOSE);
                return true;
            }
            if (!Settings.BACK_KEEPS_HOME.get()) return false;
            boolean hold = decideAt(SystemClock.elapsedRealtime());
            Logger.printDebug(() -> "Home back: Back on a scrolled Home"
                    + (hold ? ": kept the feed where it is" : ": second Back, Facebook goes to the top"));
            HookStatus.counted(FamilyNames.RETURN_REFRESH, hold ? KEPT : LET_FACEBOOK);
            if (hold && !Settings.HIDE_BACK_REMINDER.get()) {
                Utils.showToastShort(L10n.t("Press Back again to go to the top"));
            }
            return hold;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, "Home back", failure);
            return false;
        }
    }

    /**
     * Whether a Back at [now] keeps Home where it is: yes, unless it comes within
     * {@link #SECOND_BACK_MS} of the last one that did, which it uses up so a third Back keeps again.
     */
    static synchronized boolean decideAt(long now) {
        if (keptAt >= 0 && now >= keptAt && now - keptAt <= SECOND_BACK_MS) {
            keptAt = -1;
            return false;
        }
        keptAt = now;
        return true;
    }

    /** Forgets the last kept Back and the last screen that closed, so the next Back starts fresh. */
    static synchronized void forget() {
        keptAt = -1;
        cameBackAt = -1;
        front = new WeakReference<>(null);
        watching = false;
    }

    /** The screen in front as each one resumes, which is also a screen over it closing. */
    private static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            try {
                synchronized (HomeBack.class) {
                    front = new WeakReference<>(activity);
                }
                cameBack();
                Window window = activity.getWindow();
                if (window == null) return;
                View decor = window.getDecorView();
                if (WATCHED.put(decor, Boolean.TRUE) == null) {
                    decor.getViewTreeObserver().addOnWindowFocusChangeListener(FOCUS);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.RETURN_REFRESH, "Home back screens", failure);
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle saved) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }
}
