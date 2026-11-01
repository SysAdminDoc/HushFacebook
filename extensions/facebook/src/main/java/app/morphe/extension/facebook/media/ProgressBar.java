/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.reels.CaptionRoom;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keeps the progress bar of a video on screen. Two players hide theirs a few seconds in.
 *
 * <p>The Reels viewer's bar on 581 is the unified video scrubber (VDDScrubberPlugin). It has an
 * active look and a passive one, a thin line, and the patch asks {@link #keepsReelBar} first in the
 * passive one: a yes runs the active look instead, then {@link #hideTimeLabel} hides the elapsed
 * and total time the active look shows, so it appears only while you drag, as stock. The patch hides
 * the "most replayed" graph over the bar there too, as the passive look does, and {@link
 * #clearsLastReel} has it clear the graph and the chapter marks as the scrubber takes the next reel,
 * so one reel's doesn't stay over the next. When Facebook's Reels registry returns from a hand-over
 * without giving the scrubber the reel on screen, {@link #handsOver} and {@link #leftOnLastReel} hide
 * the bar and its time instead of leaving the last reel's stopped there, until a reel takes the
 * scrubber ({@link #mounts}). When the switch changes with Reels open, {@link #looksAgain} has the
 * scrubber's next progress update give the bar the look the switch now asks for, and turned on, it
 * lifts a playing reel's caption above the bar ({@link #liftFooters}). Older builds' viewer used a bottom bar
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
 * <p>Tap the bar to seek, in the same family, is {@link TapToSeek}.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, both answer no and
 * Facebook carries on with its own code.
 */
public final class ProgressBar {
    /** Counted under the patch's name each time the reel's bar is kept full size. */
    static final String REEL_BAR_KEPT = "Reel progress bar kept";
    /** Counted each time the kept bar drops the last reel's graph and chapter marks for the next reel. */
    static final String NEXT_REEL_CLEARED = "Reel bar cleared for the next reel";
    /** Counted each time the bar goes back to the start as the scrubber takes the next reel. */
    static final String NEXT_REEL_STARTED_OVER = "Reel bar started over for the next reel";
    /** Counted each time the next reel's time label shows 0:00 and its length as the scrubber takes it. */
    static final String NEXT_REEL_TIME_SHOWN = "Reel time shown from the start of the next reel";
    /** Counted each time a full-screen video's fade timer isn't set. */
    static final String CONTROLS_KEPT = "Video controls kept";

    /** Counted each time the time label is hidden after the active look. */
    static final String TIME_LABEL_HIDDEN = "Reel time label hidden";

    /** Counted each time a reel's length check for a bar is answered yes, whatever the reel's length. */
    static final String SHORT_REEL_BAR = "Reel passed the bar's length check";

    /** Counted each time a reel Facebook gave no bar at all gets one. */
    static final String NO_BAR_REEL = "Reel without a bar given one";

    /** Counted each time the time label is shown after the active look. */
    static final String TIME_LABEL_SHOWN = "Reel time label shown";

    /** Counted each time a time label Facebook left hidden is put back while the bar shows. */
    static final String TIME_LABEL_BACK = "Reel time label put back";

    /** Counted each time the kept bar's next move is set a frame away instead of Facebook's wait. */
    static final String BAR_SMOOTHED = "Reel bar moved every frame";

    /** Counted each time a reel's footer is kept in its paused layout, with the caption above the bar. */
    static final String FOOTER_LIFTED = "Reel caption kept above the bar";

    /** Counted each time a footer laid out for a playing reel is lifted above the bar after the switch went on. */
    static final String FOOTER_LIFTED_AGAIN = "Reel caption lifted above the bar after the switch went on";

    /** Counted each time a short reel's check for the faded bar look is answered no. */
    static final String FADED_LOOK_SKIPPED = "Short reel kept from the faded bar";

    /** Counted each time the bar, while kept, is still set to the look that fades it out. */
    static final String FADED_LOOK = "Reel bar set to the faded look";

    /** Counted each time the bar, while kept, is still set to the look that hides it. */
    static final String INVISIBLE_LOOK = "Reel bar set to the invisible look";

    /** Counted each time the bar, while kept, takes back the place of a look Facebook parked to hide it. */
    static final String PARKED_HIDE_REPLACED = "Reel bar kept over a parked hide";

    /** Counted each time a reel hand-over ends with the scrubber still on the last reel, so the kept bar is hidden. */
    static final String LAST_REEL_HIDDEN = "Reel bar hidden while it holds the last reel";

    /** Counted each time a look that would show the bar is dropped while the bar holds the last reel. */
    static final String LAST_REEL_LOOK = "Reel bar kept from showing the last reel";

    /** Counted each time Facebook's Reels scrubber registry, its hide flag set, hides every bar instead of handing one over. */
    static final String REGISTRY_HID_EVERY_BAR = "Reel bar hidden on every reel by Facebook's registry";

    /** Counted each time the registry has no scrubber for a reel, so the kept bar left on the last reel is hidden. */
    static final String NO_SCRUBBER_HIDDEN = "Reel bar hidden for a reel the registry has no scrubber for";

    /** Counted each time an older scrubber unloading leaves the registry's default holder with the scrubber that loaded after it. */
    static final String DEFAULT_HOLDER_KEPT = "Reel bar's scrubber kept in the registry past an older one's unload";

    /** Counted each time a press of the bar leaves a reel's name and buttons up instead of Facebook's seek look. */
    static final String OVERLAY_KEPT = "Reel overlay kept on a press of the bar";

    /** Counted each time a bar still in Facebook's thin look is made full size, after the switch went on. */
    static final String KEPT_LOOK_AGAIN = "Reel bar made full size after the switch went on";

    /** Counted each time a bar still in the kept look goes back to Facebook's look, after the switch went off. */
    static final String FACEBOOK_LOOK_AGAIN = "Reel bar given back to Facebook after the switch went off";

    /** Counted each time a bar hidden for holding the last reel comes back once a reel takes it. */
    static final String LAST_REEL_BACK = "Reel bar back once a reel takes it";

    /** Counted each time a reel's bar update is run again because nothing had drawn the reel since its light mount. */
    static final String UPDATE_AGAIN = "Reel bar update run again after a hand-over";

    /** How long after a light mount, and then between checks, a kept bar is checked for having drawn its reel. */
    static final long UPDATE_CHECK_MS = 500;

    /** The most checks after one light mount: about 30 seconds of a reel that doesn't start. */
    static final int UPDATE_CHECKS = 60;

    /** The scrubber's looks that leave no bar on screen: 2 fades it out, 4 hides it. */
    static final int FADED = 2;
    static final int INVISIBLE = 4;

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

    /**
     * The time labels of the scrubbers that have the kept look: {@link #hideTimeLabel} ran for them,
     * from the passive look's hook. Facebook's own passive look running anywhere ({@link
     * #keepsReelBar} answering no) empties it. Facebook asks for a look only on a touch or a change
     * of look, so a switch flipped with Reels open left each scrubber in the look it had, and
     * {@link #looksAgain} compares the two.
     */
    private static final Set<View> keptLooks = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The Reels footers' seek bar status holders Facebook made, each reported by {@link #footerMade}
     * and held no longer than Facebook holds it. Facebook sets a footer's status only on a player
     * event, so the reel on screen when the switch went on kept its playing layout, with the caption
     * where the kept bar and its time go (#73), and {@link #looksAgain} lifts these.
     */
    private static final Set<Object> footers = Collections.newSetFromMap(new WeakHashMap<>());

    /** Forgets every footer {@link #footerMade} reported, for tests, which share one process. */
    static void forgetFootersForTests() {
        synchronized (footers) {
            footers.clear();
        }
    }

    /** Ends every watch {@link #mounts} started, for tests, which share one process. */
    static void forgetWatchesForTests() {
        watches.clear();
    }

    /** Facebook's footer lift in its place, for tests, where the stub has nothing behind it. */
    @Nullable
    static volatile FooterLift footerLiftForTests;

    interface FooterLift {
        boolean lift(Object footer);
    }

    /**
     * A reel hand-over Facebook may leave unfinished: the reel the scrubber holds, the one on screen,
     * and what the extension did about it.
     */
    private static final class HandOver {
        final WeakReference<View> bar;
        @Nullable final String held;
        @Nullable final String shown;
        /** Facebook ran the scrubber's light mount, which takes the reel on, during the hand-over. */
        boolean mounted;
        /** The extension hid the bar and its time, and the bar's visibility before that. */
        boolean hidden;
        int barWas = View.VISIBLE;
        /** Facebook hid the bar itself afterwards, so its looks own the bar again. */
        boolean facebookHid;
        boolean dropLogged;

        HandOver(@Nullable View bar, @Nullable String held, @Nullable String shown) {
            this.bar = new WeakReference<>(bar);
            this.held = held;
            this.shown = shown;
        }
    }

    /**
     * Each scrubber's time label while that scrubber holds a reel other than the one on screen: from
     * a hand-over to another reel until Facebook's light mount takes the reel on, or the reel the
     * scrubber holds is back on screen.
     */
    private static final Map<View, HandOver> handOvers = new WeakHashMap<>();

    /**
     * A scrubber Facebook's light mount just handed a reel, watched from the mount until its progress
     * update first draws that reel ({@link #refreshesTime} is reached), the next mount, or {@link
     * #UPDATE_CHECKS} checks.
     */
    private static final class Watch {
        final WeakReference<Object> scrubber;
        @Nullable final String reel;
        int checks;
        int runs;

        Watch(Object scrubber, @Nullable String reel) {
            this.scrubber = new WeakReference<>(scrubber);
            this.reel = reel;
        }
    }

    /** Each time label's watch, from {@link #mounts} until its scrubber's update draws the reel. */
    private static final Map<View, Watch> watches = new WeakHashMap<>();

    /** Facebook's progress update in its place, for tests, where the stub has nothing behind it. */
    @Nullable
    static volatile UpdateAgain updateAgainForTests;

    interface UpdateAgain {
        void run(Object scrubber);
    }

    /** The label of the hand-over running now, between {@link #handsOver} and {@link #leftOnLastReel}. */
    @Nullable
    private static WeakReference<View> handingOver;

    /**
     * The scrubber the registry last picked for a reel on screen, as {@link #handsOver} saw it: its
     * time label, its bar and the reel it was handed. {@link #noScrubber} hides that one.
     */
    @Nullable
    private static WeakReference<View> lastLabel;
    @Nullable
    private static WeakReference<View> lastBar;
    @Nullable
    private static String lastShown;

    /**
     * The reel length the bar info builder last logged, so a bar info built again for the same reel
     * doesn't log again. Only the debug log reads it.
     */
    private static volatile int lastLoggedLengthMs = -1;

    /**
     * The bar info Facebook's builder made last: the reel's length, when, and on which thread. As the
     * scrubber takes another reel, the builder runs for that reel about a millisecond before {@link
     * #nextReel} (seen on the S22 on every swipe, #120), so nextReel can show the reel's length right
     * away instead of hiding the time until the reel plays.
     */
    private static final class BarInfo {
        final int lengthMs;
        final long at;
        final Thread thread;

        BarInfo(int lengthMs, long at, Thread thread) {
            this.lengthMs = lengthMs;
            this.at = at;
            this.thread = thread;
        }
    }

    @Nullable
    private static volatile BarInfo lastBarInfo;

    /** How old the last bar info can be for {@link #nextReel} to take it as the next reel's. */
    static final long BAR_INFO_FRESH_MS = 50;

    /** Forgets every hand-over and the scrubber last picked, for tests, which share one process. */
    static void forgetHandOversForTests() {
        handOvers.clear();
        handingOver = null;
        lastLabel = null;
        lastBar = null;
        lastShown = null;
        lastBarInfo = null;
    }

    private ProgressBar() {
    }

    /**
     * The hook, first thing in the Reels viewer's method that shrinks its progress bar. True makes
     * the bar full size instead; false lets Facebook shrink it.
     */
    public static boolean keepsReelBar() {
        boolean kept = keeps("reel progress bar", REEL_BAR_KEPT);
        // Facebook's own look goes on: no scrubber can be counted on to keep the kept look past it.
        if (!kept) keptLooks.clear();
        return kept;
    }

    /**
     * The hook in the scrubber's progress update, ahead of the time refresh, with the scrubber's time
     * label. True has the patch run the scrubber's passive look, which the hook in it turns into the
     * active one while the bar is kept and leaves as Facebook's otherwise. That happens once the look
     * the scrubber has stops matching the switch: turned on with Reels open, the bar stayed Facebook's
     * thin line, and turned off, it stayed full size with its time stopped there and carried onto the
     * next reel, until a touch or a restart. While a hand-over hides the bar for holding the last reel,
     * nothing changes. Turned on, the footers laid out for a playing reel are lifted too ({@link
     * #liftFooters}), so the bar and its time don't sit on that reel's caption.
     */
    public static boolean looksAgain(@Nullable ViewGroup label) {
        try {
            if (label == null) return false;
            boolean kept = Utils.settingsReady() && Settings.KEEP_PROGRESS_BAR.get();
            if (kept == keptLooks.contains(label)) return false;
            HandOver over = handOvers.get(label);
            if (over != null && over.hidden) return false;
            HookStatus.invoked(FAMILY);
            if (kept) {
                HookStatus.bound(FAMILY, "reel bar look again");
                // The passive look's hook adds it too. Added here, a hook that didn't run isn't asked
                // for again on every update.
                keptLooks.add(label);
                HookStatus.counted(FAMILY, KEPT_LOOK_AGAIN);
                Logger.printDebug(() -> "Reel bar: the switch is on, so the bar takes the kept look");
                liftFooters();
            } else {
                keptLooks.remove(label);
                HookStatus.counted(FAMILY, FACEBOOK_LOOK_AGAIN);
                Logger.printDebug(() -> "Reel bar: the switch is off, so the bar goes back to Facebook's look");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel bar look again", failure);
            return false;
        }
    }

    /**
     * The hook, as the scrubber takes another video. Facebook keeps one scrubber for the Reels
     * viewer and hands it reel after reel without asking for a look again, and the passive
     * look, which the kept bar never takes, is what took the last reel's "most replayed" graph and
     * chapter marks away. True has the patch hide the graph and run Facebook's own reset of the
     * chapter marks, which also brings back a bar the marks drew over; a reel with its own marks gets
     * them again from Facebook. False loads the reel as Facebook does.
     */
    public static boolean clearsLastReel() {
        return keeps("reel bar reset", NEXT_REEL_CLEARED);
    }

    /**
     * The hook, as the scrubber takes another video, with its time label and its bar. Facebook
     * leaves both where the last reel stopped, and only a playing reel's progress update moves them.
     * A reel that waits, held by Tap to play or for a player that's slow to start, showed the last
     * reel's position and time until it played, with Facebook's thin line as much as with the kept
     * bar (#90, #120). While the bar is kept or Tap to play is on, this puts the bar back at the
     * start. A time label that stays up shows 0:00 and the reel's own length right away when the
     * reel's bar info was just built ({@link #startsTime}), and otherwise goes invisible until {@link
     * #refreshesTime} puts it back with the reel's own time, which waits for the reel to play (0.7 s
     * on the S22, a second or more on a slow connection, #120). Otherwise Facebook's code runs as it
     * would.
     */
    public static void nextReel(@Nullable ViewGroup label, @Nullable View bar) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !(Settings.KEEP_PROGRESS_BAR.get() || Settings.TAP_TO_PLAY.get())) return;
            HookStatus.bound(FAMILY, "reel bar start over");
            if (bar instanceof android.widget.ProgressBar && ((android.widget.ProgressBar) bar).getProgress() != 0) {
                ((android.widget.ProgressBar) bar).setProgress(0);
                HookStatus.counted(FAMILY, NEXT_REEL_STARTED_OVER);
            }
            if (label != null && showsTime()) {
                lastRefresh.remove(label);
                String length = startsTime(label, bar);
                if (length != null) {
                    HookStatus.counted(FAMILY, NEXT_REEL_TIME_SHOWN);
                    Logger.printDebug(() -> "Reel bar: the scrubber takes another reel, its time starts at 0:00 of " + length);
                } else {
                    label.setVisibility(View.INVISIBLE);
                    Logger.printDebug(() -> "Reel bar: the scrubber takes another reel, its time is hidden until the reel plays");
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel bar start over", failure);
        }
    }

    /**
     * Writes 0:00 and the next reel's length into [label]'s elapsed and total time, its first and
     * last children, the way Facebook's time writer does, and keeps the label up. Only when the bar
     * info Facebook built last is the next reel's: built on this thread at most {@link
     * #BAR_INFO_FRESH_MS} ago, and not taken already. A label hidden for holding the last reel stays
     * hidden, and one Facebook made invisible comes back only while the bar shows and nobody drags
     * it, as in {@link #refreshesTime}. Answers the length when the label shows it, or null when it
     * stays hidden.
     */
    @Nullable
    private static String startsTime(ViewGroup label, @Nullable View bar) {
        BarInfo info = lastBarInfo;
        if (info == null || info.thread != Thread.currentThread()) return null;
        long age = SystemClock.uptimeMillis() - info.at;
        if (age < 0 || age > BAR_INFO_FRESH_MS) return null;
        HandOver over = handOvers.get(label);
        if (over != null && over.hidden) return null;
        int count = label.getChildCount();
        if (count < 2) return null;
        View elapsed = label.getChildAt(0);
        View total = label.getChildAt(count - 1);
        if (!(elapsed instanceof TextView) || !(total instanceof TextView)) return null;
        lastBarInfo = null;
        String length = time(info.lengthMs);
        ((TextView) elapsed).setText(time(0));
        ((TextView) total).setText(length);
        if (label.getVisibility() == View.INVISIBLE && bar != null && !bar.isPressed() && visible(bar)) {
            label.setVisibility(View.VISIBLE);
        }
        return label.getVisibility() == View.VISIBLE ? length : null;
    }

    /**
     * A time as Facebook's time writer puts it: whole seconds, m:ss, or h:mm:ss from an hour on,
     * with the app's locale's digits.
     */
    static String time(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        return hours > 0
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(Locale.getDefault(), "%d:%02d", minutes, seconds % 60);
    }

    /**
     * A reel as the debug lines name it: "reel" and the first six characters of its id's SHA-256,
     * so the lines of one hand-over still pair up without the reel's Facebook id in logcat.
     */
    static String reelName(@Nullable String reel) {
        if (reel == null || reel.isEmpty()) return "no reel";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(reel.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT, "reel %02x%02x%02x", digest[0], digest[1], digest[2]);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The hook first thing in Facebook's Reels scrubber registry as a new reel shows, with whether its
     * hide flag is set and the reel's key. With the flag set, which the Watch tab's controller does,
     * the registry asks every scrubber it holds for the look that hides the bar and returns. With no
     * scrubber for the reel it returns too ({@link #noScrubber}). Both come before {@link #handsOver},
     * so a reel that arrived with the last reel's spot and no hand-over logged (#120) couldn't tell
     * which way it went, or whether the registry ran at all. While the bar is kept this logs each
     * ask and counts the hide. Facebook's code goes on as it would.
     */
    public static void registryAsks(boolean hidesAll, @Nullable Integer reel) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return;
            HookStatus.bound(FAMILY, "reel registry");
            if (hidesAll) HookStatus.counted(FAMILY, REGISTRY_HID_EVERY_BAR);
            Logger.printDebug(() -> "Reel bar: Facebook's registry shows reel " + reel
                    + (hidesAll ? " and hides every bar, its hide flag is set" : ""));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel registry", failure);
        }
    }

    /**
     * The hook in the registry's hand-over where it has found no scrubber for the reel on screen,
     * neither the reel's own nor the one it falls back on, just before it returns without handing
     * one over. The scrubber on screen then still holds the last reel, its bar and time stopped
     * where that reel was (#120). While the bar is kept, this hides the bar and time of the scrubber
     * the last hand-over picked, as {@link #leftOnLastReel} does, until a reel takes it.
     */
    public static void noScrubber() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return;
            HookStatus.bound(FAMILY, "reel registry");
            WeakReference<View> labels = lastLabel;
            View label = labels == null ? null : labels.get();
            if (label == null) {
                Logger.printDebug(() -> "Reel bar: Facebook's registry has no scrubber for the reel on screen, and none was picked before");
                return;
            }
            HandOver over = handOvers.get(label);
            if (over == null) {
                WeakReference<View> bars = lastBar;
                over = new HandOver(bars == null ? null : bars.get(), lastShown, null);
                handOvers.put(label, over);
            }
            hide(label, over);
            HookStatus.counted(FAMILY, NO_SCRUBBER_HIDDEN);
            String held = over.held;
            Logger.printDebug(() -> "Reel bar: Facebook's registry has no scrubber for the reel on screen, so the bar of "
                    + reelName(held) + " is hidden until a reel takes it");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel registry", failure);
        }
    }

    /**
     * The hook in Facebook's Reels scrubber registry as a new reel shows, once it has picked the
     * scrubber, with that scrubber's time label and bar, the id of the reel the scrubber holds (its
     * bar info's) and the id of the reel on screen. Facebook goes on to hand the scrubber the reel,
     * through the light mount that {@link #mounts} reports, but returns before that when the reel's
     * player isn't ready or the reel has no id, and the scrubber stays on the last reel: its bar and
     * time stopped where that reel was, and a touch showed 0:00 over the last reel's length (#90,
     * #120). While the bar is kept, this notes a hand-over to another reel for
     * {@link #leftOnLastReel}. The reel the scrubber holds coming back on screen ends any such hold
     * and shows the bar again.
     */
    public static void handsOver(@Nullable ViewGroup label, @Nullable View bar, @Nullable String held, @Nullable String shown) {
        try {
            handingOver = null;
            if (label == null) return;
            lastLabel = new WeakReference<>(label);
            lastBar = new WeakReference<>(bar);
            lastShown = shown;
            if (shown != null && shown.equals(held)) {
                HandOver over = handOvers.remove(label);
                if (over != null && showBarAgain(over)) {
                    Logger.printDebug(() -> "Reel bar: " + reelName(shown) + " is back on screen, the bar shows again");
                }
                return;
            }
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) {
                HandOver over = handOvers.remove(label);
                if (over != null) showBarAgain(over);
                return;
            }
            HookStatus.bound(FAMILY, "reel hand-over");
            HandOver before = handOvers.get(label);
            HandOver over = new HandOver(bar, held, shown);
            if (before != null && before.hidden) {
                // Still hidden from an earlier hand-over: what to put back stays the same.
                over.hidden = true;
                over.barWas = before.barWas;
                over.facebookHid = before.facebookHid;
            }
            handOvers.put(label, over);
            handingOver = new WeakReference<>(label);
            Logger.printDebug(() -> "Reel bar: handing the bar from " + reelName(held) + " to " + reelName(shown));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel hand-over", failure);
        }
    }

    /**
     * The hook, first thing in the scrubber base's light mount, with the scrubber's time label and
     * the scrubber. Facebook runs the mount as it hands the scrubber a reel, so the scrubber holds the
     * reel it's given from here on: a hand-over noted by {@link #handsOver} is done, and a bar hidden
     * for holding the last reel shows again, unless Facebook hid it meanwhile.
     *
     * <p>While the bar is kept, the scrubber is also watched until its progress update draws the
     * reel. The mount starts that update, but the update stops for good when it can't draw yet: the
     * pager still scrolling, no player ready, or no position or length yet, the last leaving the
     * update marked as running with nothing scheduled. Only the scroll settling and a change in the
     * player's state start it again, and after a fast double swipe both can come too early. The reel
     * swiped past starts playing and loses the scrubber before it pauses, so the scrubber still has
     * its PLAYING when the reel on screen plays, and a play event that changes nothing, or one sent
     * before the scrubber listens to that reel, starts nothing. The reel played with its bar at the
     * start and no time (#120). So every {@link #UPDATE_CHECK_MS} until a first draw, {@link
     * #updateAgain} runs the update once more, which goes on by itself once it draws.
     *
     * <p>Whatever the switches, the label also hands {@link CaptionRoom} the reel's page, so the
     * footer's caption draws its last line whole (#96).
     */
    public static void mounts(@Nullable ViewGroup label, @Nullable Object scrubber) {
        try {
            if (label == null) return;
            CaptionRoom.mounted(label);
            HandOver over = handOvers.remove(label);
            watch(label, scrubber, over == null ? null : over.shown);
            if (over == null) return;
            over.mounted = true;
            boolean back = showBarAgain(over);
            Logger.printDebug(() -> "Reel bar: Facebook handed the bar to " + reelName(over.shown) + (back ? ", which shows again" : ""));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel hand-over", failure);
        }
    }

    /**
     * The hook in the scrubber base's load, once it has built the bar info of the reel it takes, with
     * the scrubber's time label, the scrubber and that reel's id. A scrubber can take a reel this way
     * with no hand-over and no light mount, as after a feed refresh (#120), so a hold on its bar ends
     * here as it does at a mount, and the scrubber is watched until its update draws the reel.
     */
    public static void loads(@Nullable ViewGroup label, @Nullable Object scrubber, @Nullable String reel) {
        try {
            if (label == null) return;
            HandOver over = handOvers.remove(label);
            watch(label, scrubber, reel);
            boolean back = over != null && showBarAgain(over);
            Logger.printDebug(() -> "Reel bar: scrubber " + Integer.toHexString(System.identityHashCode(scrubber))
                    + " loaded " + reelName(reel) + (back ? ", so its bar shows again" : ""));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "scrubber load", failure);
        }
    }

    /**
     * The hook in the scrubber base's unload, where it clears the registry's default holder, with
     * what the registry holds and the unloading scrubber's own holder. Facebook clears it whichever
     * scrubber it belongs to, so an older scrubber unloading late takes the holder of the one that
     * loaded after it, and the registry has no scrubber for the reel on screen until another load
     * (#120). While the bar is kept, true keeps a holder that isn't the unloading scrubber's.
     */
    /** Asked as a press of the bar is about to clear the reel's name, caption and buttons for Facebook's seek look. */
    public static boolean keepsOverlayWhileSeeking() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_REEL_OVERLAY.get()) return false;
            HookStatus.bound(FAMILY, "bar press");
            HookStatus.counted(FAMILY, OVERLAY_KEPT);
            Logger.printDebug(() -> "Reel bar: a press leaves the name and buttons up");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "bar press", failure);
            return false;
        }
    }

    public static boolean keepsDefaultHolder(@Nullable Object current, @Nullable Object own) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return false;
            HookStatus.bound(FAMILY, "scrubber unload");
            boolean keep = current != null && current != own;
            if (keep) HookStatus.counted(FAMILY, DEFAULT_HOLDER_KEPT);
            Logger.printDebug(() -> "Reel bar: holder " + Integer.toHexString(System.identityHashCode(own))
                    + " unloaded" + (keep ? ", another scrubber's stays in the registry" : ""));
            return keep;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "scrubber unload", failure);
            return false;
        }
    }

    /** Starts watching [label]'s scrubber after a light mount while the bar is kept, in place of any earlier watch. */
    private static void watch(View label, @Nullable Object scrubber, @Nullable String reel) {
        if (scrubber == null || !Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) {
            watches.remove(label);
            return;
        }
        Watch watch = new Watch(scrubber, reel);
        watches.put(label, watch);
        WeakReference<View> held = new WeakReference<>(label);
        Utils.runOnMainThreadDelayed(() -> check(held, watch), UPDATE_CHECK_MS);
    }

    /**
     * One check of a watch {@link #watch} started: unless its scrubber's update drew the reel, another
     * mount took the label or the switch went off, runs the update again and checks again later.
     * While the bar is hidden for holding the last reel there's nothing of the reel's own to draw, so
     * only the next check comes.
     */
    private static void check(WeakReference<View> held, Watch watch) {
        try {
            View label = held.get();
            if (label == null || watches.get(label) != watch) return;
            Object scrubber = watch.scrubber.get();
            if (scrubber == null || ++watch.checks > UPDATE_CHECKS || !Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) {
                watches.remove(label);
                return;
            }
            HandOver over = handOvers.get(label);
            if (over == null || !over.hidden) {
                HookStatus.invoked(FAMILY);
                HookStatus.bound(FAMILY, "reel bar update again");
                watch.runs++;
                HookStatus.counted(FAMILY, UPDATE_AGAIN);
                if (watch.runs == 1) {
                    Logger.printDebug(() -> "Reel bar: nothing drew " + reelName(watch.reel) + " since Facebook handed over the bar, so its update runs again");
                }
                UpdateAgain forced = updateAgainForTests;
                if (forced != null) {
                    forced.run(scrubber);
                } else {
                    updateAgain(scrubber);
                }
                // A run that drew ended the watch in refreshesTime.
                if (watches.get(label) != watch) return;
            }
            Utils.runOnMainThreadDelayed(() -> check(held, watch), UPDATE_CHECK_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel bar update again", failure);
        }
    }

    /**
     * Filled in by the patch: runs [scrubber]'s own progress update once, as Facebook's frame
     * callback does. The update moves the bar and schedules the next one when it finds the reel's
     * position and length, and stops again otherwise. Does nothing until filled.
     */
    public static void updateAgain(Object scrubber) {
    }

    /**
     * The hook at the end of the registry's hand-over, on every way out past the one {@link #handsOver}
     * saw begin. When that hand-over was to another reel and Facebook ran no light mount, the scrubber
     * still holds the last reel, so while the bar is kept this hides the bar and its time label
     * (invisible, which keeps the layout) instead of leaving them stopped on the last reel, and
     * {@link #lookFor} keeps Facebook's looks from showing them until a reel takes the scrubber.
     */
    public static void leftOnLastReel() {
        try {
            WeakReference<View> current = handingOver;
            handingOver = null;
            View label = current == null ? null : current.get();
            if (label == null) return;
            HandOver over = handOvers.get(label);
            if (over == null || over.mounted) return;
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return;
            hide(label, over);
            HookStatus.counted(FAMILY, LAST_REEL_HIDDEN);
            Logger.printDebug(() -> "Reel bar: Facebook left the bar on " + reelName(over.held) + " with " + reelName(over.shown)
                    + " on screen, so it's hidden until a reel takes it");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel hand-over", failure);
        }
    }

    /**
     * The hook, first thing in the scrubber's look selector, with the scrubber's time label and the
     * look asked for, answering the look the selector goes on with. While {@link #leftOnLastReel}
     * or {@link #noScrubber} keeps the bar hidden, a look that would show it (a player event of the last reel, or a parked
     * one) answers null, which the selector ignores, so the last reel's bar and time don't come back
     * over the reel on screen. A look that fades or hides the bar goes through, and Facebook's looks
     * then own the bar again. Every other look goes through as asked.
     */
    @Nullable
    public static Integer lookFor(@Nullable ViewGroup label, @Nullable Integer look) {
        try {
            if (label == null || look == null) return look;
            HandOver over = handOvers.get(label);
            if (over == null || !over.hidden) return look;
            if (look == FADED || look == INVISIBLE) {
                over.facebookHid = true;
                return look;
            }
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) {
                handOvers.remove(label);
                showBarAgain(over);
                return look;
            }
            HookStatus.counted(FAMILY, LAST_REEL_LOOK);
            if (!over.dropLogged) {
                over.dropLogged = true;
                Logger.printDebug(() -> "Reel bar: kept look " + look + " from showing the bar of " + reelName(over.held));
            }
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel hand-over", failure);
            return look;
        }
    }

    /**
     * Hides [label] and the bar of [over] (invisible, which keeps the layout), noting the bar's
     * visibility the first time, so {@link #lookFor} keeps Facebook's looks from showing them until
     * a reel takes the scrubber.
     */
    private static void hide(View label, HandOver over) {
        View bar = over.bar.get();
        if (!over.hidden) {
            over.hidden = true;
            if (bar != null) over.barWas = bar.getVisibility();
        }
        if (bar != null && bar.getVisibility() == View.VISIBLE) bar.setVisibility(View.INVISIBLE);
        if (label.getVisibility() == View.VISIBLE) label.setVisibility(View.INVISIBLE);
        lastRefresh.remove(label);
    }

    /**
     * Puts back the bar {@link #leftOnLastReel} hid, as it was before, unless Facebook hid it since.
     * The time label stays hidden for {@link #refreshesTime} to bring back with the reel's own time.
     * Whether the bar came back.
     */
    private static boolean showBarAgain(HandOver over) {
        if (!over.hidden || over.facebookHid || over.barWas != View.VISIBLE) return false;
        View bar = over.bar.get();
        if (bar == null || bar.getVisibility() != View.INVISIBLE) return false;
        bar.setVisibility(over.barWas);
        HookStatus.counted(FAMILY, LAST_REEL_BACK);
        return true;
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
     * drag, and a drag shows it again. The first time a bar takes the kept look this way, the playing
     * footers are lifted too ({@link #liftFooters}), as {@link #looksAgain} does when it gets there
     * first.
     */
    public static void hideTimeLabel(ViewGroup label) {
        try {
            if (label == null) return;
            boolean newlyKept = keptLooks.add(label);
            if (showsTime()) {
                if (label.getVisibility() != View.VISIBLE) {
                    label.setVisibility(View.VISIBLE);
                    HookStatus.counted(FAMILY, TIME_LABEL_SHOWN);
                }
            } else if (label.getVisibility() != View.INVISIBLE) {
                label.setVisibility(View.INVISIBLE);
                HookStatus.counted(FAMILY, TIME_LABEL_HIDDEN);
            }
            // Facebook's own look can be the first to give a bar the kept look after the switch went
            // on: the reel on screen asks for its look again as it plays on or loops once the
            // settings close, before its next progress update, so looksAgain then finds the look
            // already kept and lifts nothing. That reel's footer, and the next reel's already laid
            // out, stayed HIDDEN, and a play event changes nothing (HIDDEN again), so the bar's time
            // sat on their captions (#73).
            if (newlyKept && liftFooters() > 0) {
                Logger.printDebug(() -> "Reel bar: Facebook's look gave the bar the kept look, so the playing footers lift too");
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
     * The hook in the scrubber's bar info builder, with Facebook's answer to whether the reel has a
     * bar at all and the reel's length in ms. Facebook answers no for an ad, for what the viewer's
     * config rules out and for some reels it flags on its servers, and the scrubber then hides the bar
     * (#73). While the bar is kept, a reel with a known length gets one; otherwise Facebook's answer
     * stands. A known length is noted for {@link #nextReel}, which comes right after for a reel the
     * scrubber takes.
     */
    public static boolean hasBar(boolean facebook, int lengthMs) {
        if (lengthMs > 0) {
            try {
                lastBarInfo = new BarInfo(lengthMs, SystemClock.uptimeMillis(), Thread.currentThread());
                if (lengthMs != lastLoggedLengthMs && Utils.settingsReady()) {
                    lastLoggedLengthMs = lengthMs;
                    Logger.printDebug(() -> "Reel bar: bar info built for a reel of " + lengthMs + " ms");
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "reel bar info", failure);
            }
        }
        if (facebook || lengthMs <= 0) return facebook;
        return keeps("reel without a bar", NO_BAR_REEL);
    }

    /**
     * The hook in the scrubber's progress update, with the scrubber's time label and its bar, once
     * Facebook has moved the bar and just before it checks for a drag. True has Facebook's own time
     * writer put the current elapsed and total time into it: only while the time is shown, the label
     * is on screen and {@link #TIME_REFRESH_MS} has passed since that label's last refresh. The writer
     * moves the bar as well, so it's asked only where Facebook found the position and length usable
     * (#120).
     *
     * <p>Facebook hides the label (invisible) when a touch starts, at a reel's end and while a drag
     * shows preview frames, and only its looks show it again. A look that doesn't change leaves it
     * hidden, so a reel could play on with no time until the next drag (#73). While the time is shown,
     * the bar is on screen and nobody is dragging it, this puts such a label back first.
     *
     * <p>While the bar is hidden for holding the last reel ({@link #leftOnLastReel}), the label stays
     * hidden and nothing is written, as the scrubber's position and length are the last reel's.
     *
     * <p>Being asked at all means the update drew the reel, which ends the label's watch from {@link
     * #mounts}, whatever the switches. It also hands {@link CaptionRoom} the label, since the first
     * reel after a start gets no light mount (#96).
     */
    public static boolean refreshesTime(ViewGroup label, @Nullable View bar) {
        try {
            if (label == null) return false;
            CaptionRoom.updated(label);
            // Facebook found the reel's position and length and moved the bar: the update draws.
            Watch watch = watches.remove(label);
            if (watch != null && watch.runs > 0) {
                Logger.printDebug(() -> "Reel bar: " + reelName(watch.reel) + " drew after its update ran again " + watch.runs + " time(s)");
            }
            if (!showsTime()) return false;
            HandOver over = handOvers.get(label);
            if (over != null && over.hidden) return false;
            if (label.getVisibility() == View.INVISIBLE && bar != null && !bar.isPressed() && visible(bar)) {
                label.setVisibility(View.VISIBLE);
                HookStatus.counted(FAMILY, TIME_LABEL_BACK);
            }
            if (!visible(label)) return false;
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
     * The hook in the Reels footer's seek bar status, when it starts a reel and each time it would
     * go to HIDDEN, which a playing reel gets. Only VISIBLE, a paused reel's status, lifts the
     * caption above where the bar and its time go, so while playing the kept bar and its time sat on
     * the caption (#73). True keeps the status VISIBLE; false lets Facebook hide it.
     */
    public static boolean liftsFooter() {
        return keeps("reel caption lift", FOOTER_LIFTED);
    }

    /**
     * The hook in the Reels footer's seek bar status holder, as Facebook makes one, with the holder.
     * Changes nothing: it's remembered, whatever the switch, so {@link #liftFooters} can reach it.
     */
    public static void footerMade(@Nullable Object footer) {
        if (footer == null) return;
        try {
            synchronized (footers) {
                footers.add(footer);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel caption lift", failure);
        }
    }

    /**
     * Lifts every footer Facebook still holds whose status is the playing one, HIDDEN, through
     * {@link #liftFooter}, as the switch goes on with Reels open. Facebook sets the status only on a
     * player event, so the reel on screen kept the caption where the kept bar and its time now go
     * (#73) until it was paused or played again. A footer that's paused, dragged or already lifted
     * stays as it is. Answers how many were lifted.
     */
    static int liftFooters() {
        Object[] held;
        synchronized (footers) {
            held = footers.toArray();
        }
        int lifted = 0;
        for (Object footer : held) {
            if (footer == null) continue;
            try {
                FooterLift forced = footerLiftForTests;
                if (forced != null ? forced.lift(footer) : liftFooter(footer)) lifted++;
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "reel caption lift", failure);
            }
        }
        for (int i = 0; i < lifted; i++) HookStatus.counted(FAMILY, FOOTER_LIFTED_AGAIN);
        if (lifted > 0) {
            int count = lifted;
            Logger.printDebug(() -> "Reel bar: the switch is on, so " + count + " playing reel footer(s) lift the caption above the bar");
        }
        return lifted;
    }

    /**
     * Filled in by the patch: runs [footer]'s own status update with no player event, which the
     * patch turns into Facebook's change from HIDDEN to VISIBLE while the bar is kept, and answers
     * whether the status changed. False until filled.
     */
    public static boolean liftFooter(Object footer) {
        return false;
    }

    /**
     * The hook, first thing in the scrubber's check that a reel is short enough for the look that
     * fades the bar out, which Facebook asks before the length check {@link #barsEveryReel} answers.
     * True answers no, so a short reel keeps its bar; false lets Facebook check.
     */
    public static boolean noFadedBar() {
        return keeps("short reel faded bar", FADED_LOOK_SKIPPED);
    }

    /**
     * The hook, first thing in the scrubber's look selector, with the look. Changes nothing: while
     * the bar is kept it counts the looks that still leave no bar, so a report shows which way a
     * reel without one went.
     */
    public static void look(@Nullable Integer look) {
        try {
            if (look == null || (look != FADED && look != INVISIBLE)) return;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return;
            HookStatus.bound(FAMILY, "reel bar look");
            HookStatus.counted(FAMILY, look == FADED ? FADED_LOOK : INVISIBLE_LOOK);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel bar look", failure);
        }
    }

    /**
     * The hook in the scrubber's look selector, when the look asked for is the one the bar already
     * has, with the look Facebook parked to apply once a reel's player change ends. Facebook skips a
     * look the bar has, so a hide parked as one reel left the screen still landed on the next reel to
     * use the same scrubber, after that reel's own look had been skipped, and it played with no bar
     * (#73). True, while the bar is kept, for a look that shows the bar over a parked hide: the
     * selector goes on as for a new look and parks it in the hide's place. False keeps the skip.
     */
    public static boolean replacesParkedLook(@Nullable Integer look, @Nullable Integer parked) {
        if (look == null || parked == null) return false;
        if (look == FADED || look == INVISIBLE || (parked != FADED && parked != INVISIBLE)) return false;
        return keeps("reel bar parked hide", PARKED_HIDE_REPLACED);
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
