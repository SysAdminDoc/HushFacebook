/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keeps the progress bar of a video on screen. Two players hide theirs a few seconds in.
 *
 * <p>The Reels viewer's bar on 581 is the unified video scrubber (VDDScrubberPlugin). It has an
 * active look and a passive one, a thin line, and the patch asks {@link #keepsReelBar} first in the
 * passive one: a yes runs the active look instead, then {@link #hideTimeLabel} hides the elapsed
 * and total time the active look shows, so it appears only while you drag, as stock. Older builds' viewer used a bottom bar
 * (FbShortsViewerBottomSeekBarPlugin), which has two sizes. Facebook
 * makes it full size, with the thumb showing and the bar taking drags, when it shows a reel's
 * controls or while you scrub, and shrinks it to a line 2 dp high with the thumb hidden and drags
 * turned off when the controls go away or the reel plays on. The patch asks {@link #keepsReelBar}
 * first in the method that shrinks it, and a yes makes it full size instead, so the bar can be
 * read and dragged at any time. The time labels stay Facebook's: they show while you scrub.
 *
 * <p>A full-screen video's controls (the plugins built on the controls class
 * FeedFullscreenVideoControlsPlugin extends) set a timer to fade out each time they show or you
 * touch them. The patch asks {@link #keepsControls} first in the method that sets that timer, and
 * a yes sets none, so the controls and their progress bar stay until you tap the video, which
 * hides them as before. The newer Litho player hides its controls from its video controls
 * extension, 3 seconds after they show or are touched, and the player that Enter fullscreen
 * landscape mode opens runs on the Reels controls component, whose controller posts a runnable that
 * hides them about 3 seconds after a tap. The patch asks {@link #keepsControls} first in that
 * extension's timer and in that runnable, and a yes hides nothing. A tap still hides them.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, both answer no and
 * Facebook carries on with its own code.
 */
public final class ProgressBar {
    /** Counted under the patch's name each time the reel's bar is kept full size. */
    static final String REEL_BAR_KEPT = "Reel progress bar kept";
    /** Counted each time a full-screen video's fade timer isn't set. */
    static final String CONTROLS_KEPT = "Video controls kept";

    /** Counted each time the time label is hidden after the active look. */
    static final String TIME_LABEL_HIDDEN = "Reel time label hidden";

    /** Counted each time a reel's length check for a bar is answered yes, whatever the reel's length. */
    static final String SHORT_REEL_BAR = "Reel passed the bar's length check";

    /** Counted each time the time label is shown after the active look. */
    static final String TIME_LABEL_SHOWN = "Reel time label shown";

    /** Counted each time the kept bar's next move is set a frame away instead of Facebook's wait. */
    static final String BAR_SMOOTHED = "Reel bar moved every frame";

    /** Counted each time a reel's caption is laid out with room for the bar, whatever the reel's length. */
    static final String SHORT_REEL_ROOM = "Reel caption given room for the bar";

    /**
     * The least time between two refreshes of one time label. Facebook updates the bar many times a
     * second and each write lays the label out again, so the time is written about four times a
     * second, often enough for a seconds readout.
     */
    static final long TIME_REFRESH_MS = 250;

    private static final String FAMILY = FamilyNames.PROGRESS_BAR;

    /**
     * Each label's last refresh. Two scrubbers can update at once (the reel on screen and the next
     * one being readied), and each is held to {@link #TIME_REFRESH_MS} on its own.
     */
    private static final Map<View, Long> lastRefresh = new WeakHashMap<>();

    private ProgressBar() {
    }

    /**
     * The hook, first thing in the Reels viewer's method that shrinks its progress bar. True makes
     * the bar full size instead; false lets Facebook shrink it.
     */
    public static boolean keepsReelBar() {
        return keeps("reel progress bar", REEL_BAR_KEPT);
    }

    /**
     * The hook, first thing in the methods that set a full-screen video's fade timer, in the older
     * player and the newer one, and in the landscape player's hide runnable. True sets no timer or
     * runs no hide, so the controls stay; false lets Facebook's code run.
     */
    public static boolean keepsControls() {
        return keeps("video controls", CONTROLS_KEPT);
    }

    /**
     * The hook, right after the active look runs in place of the passive one. The active look shows
     * the scrubber's time label, the elapsed and total time. With the time switch on it stays, and
     * {@link #refreshesTime} keeps it current. Otherwise this hides it the way the passive look does
     * (invisible, not gone, which keeps the layout), as Facebook's update only writes it during a
     * drag, and a drag shows it again.
     */
    public static void hideTimeLabel(ViewGroup label) {
        try {
            if (label == null) return;
            if (showsTime()) {
                if (label.getVisibility() != View.VISIBLE) {
                    label.setVisibility(View.VISIBLE);
                    HookStatus.counted(FAMILY, TIME_LABEL_SHOWN);
                }
            } else if (label.getVisibility() != View.INVISIBLE) {
                label.setVisibility(View.INVISIBLE);
                HookStatus.counted(FAMILY, TIME_LABEL_HIDDEN);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel time label", failure);
        }
    }

    /**
     * The hook, first thing in the scrubber's check that a reel is long enough for a bar. Facebook
     * gives a reel under its minimum length no bar at all. True answers yes for every reel, so the
     * short ones get the bar too; false lets Facebook check.
     */
    public static boolean barsEveryReel() {
        return keeps("short reel bar", SHORT_REEL_BAR);
    }

    /**
     * The hook, first thing in the scrubber's progress update, with the scrubber's time label. True
     * has Facebook's own time writer put the current elapsed and total time into it before the
     * update runs: only while the time is shown, the label is on screen and {@link #TIME_REFRESH_MS}
     * has passed since that label's last refresh.
     */
    public static boolean refreshesTime(ViewGroup label) {
        try {
            if (label == null || !visible(label) || !showsTime()) return false;
            long now = SystemClock.uptimeMillis();
            Long last = lastRefresh.get(label);
            if (last != null && now - last < TIME_REFRESH_MS) return false;
            lastRefresh.put(label, now);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel time", failure);
            return false;
        }
    }

    /** Whether [view] and every view above it are visible. A view not in a window yet counts by its own. */
    private static boolean visible(View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            if (((View) at).getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /**
     * The hook, first thing in the wait before the scrubber's next progress update. Facebook waits
     * 100 ms, so the bar jumps along in steps. True has the patch wait about a frame instead, so the
     * kept bar moves smoothly; false keeps Facebook's wait.
     */
    public static boolean smoothsBar() {
        return keeps("smooth reel bar", BAR_SMOOTHED);
    }

    /**
     * The hook on a reel's length, right after the Reels footer's bar check reads it. Facebook lays
     * a reel's caption out with room for the bar only when the reel is past its minimum length for
     * one, so a short reel's caption would sit where {@link #barsEveryReel} now puts the bar. While
     * the bar is kept this answers a length past any minimum; otherwise the reel's own.
     */
    public static int reelLength(int length) {
        return keeps("short reel room", SHORT_REEL_ROOM) ? Integer.MAX_VALUE : length;
    }

    /** Whether the reel's time label stays up: the bar is kept and the time switch is on. */
    private static boolean showsTime() {
        return Utils.settingsReady() && Settings.KEEP_PROGRESS_BAR.get() && Settings.KEEP_PROGRESS_BAR_TIME.get();
    }

    private static boolean keeps(String hook, String counted) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return false;
            HookStatus.bound(FAMILY, hook);
            HookStatus.counted(FAMILY, counted);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, hook, failure);
            return false;
        }
    }
}
