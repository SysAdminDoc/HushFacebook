/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.facebook.reels.FeedVideoControls;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keep playing in the background: a video you started keeps its sound after you leave Facebook.
 *
 * <p>Facebook already has the whole feature, behind server flags this account doesn't get. Its
 * BackgroundPlaybackManager listens for activity stops, and once no Facebook window is left it
 * reads the last play or pause any FbGrootPlayer recorded and asks its handlers whether to carry
 * that video on. Its fullscreen handler builds a separate "background_playback" player from the
 * paused one, seeks it to where you were, plays it with BY_BACKGROUND_PLAY and starts
 * PlaybackNotificationService, whose notification and media session play, pause and skip, and whose
 * dismissal or a swipe of Facebook out of recents releases that player. Coming back, the handler
 * pauses and releases it and hides the notification. Audio focus loss and calls pause it the way
 * Facebook set its player up.
 *
 * <p>The patch keeps all of that and changes who qualifies:
 *
 * <ul>
 *   <li>Once a video you started plays, the extension makes a manager of its own and gives it one
 *       fullscreen handler ({@link #ensureManager}), since Facebook only makes them for a fullscreen
 *       player when its flags are on.</li>
 *   <li>A video counts as yours when its start came within {@link #TAP_WINDOW_MS} of a tap and wasn't
 *       one Facebook sends because something came into view ({@link #started}), the rule Tap to play
 *       uses. It stops counting when Facebook starts it again on its own after a pause. In Facebook's
 *       Reels viewer every start counts ({@link #inReels}): a swipe there is how you pick the reel,
 *       and only the one on screen plays (D#524).</li>
 *   <li>When that handler is asked ({@link #deciding}), it goes on only for the video whose pause
 *       Facebook just recorded, when that pause found it playing, came no more than
 *       {@link #LEAVE_WINDOW_MS} before Facebook asked, and didn't follow a tap within
 *       {@link #TAPPED_PAUSE_MS} (your own pause), and the screen that stopped wasn't closing or being
 *       rebuilt. Its checks of the server flag, the sound setting, eligible content, the trigger and
 *       the fullscreen player type answer yes then ({@link #skipCheck}), and a missing title gets a
 *       plain one ({@link #title}). Every other video gets a no at once.</li>
 *   <li>The notification's own server flag reads on while that one video continues
 *       ({@link #notificationAllowed}), until the handler's return to Facebook has hidden it.</li>
 *   <li>A video playing in the picture-in-picture window counts as yours when the phone locks
 *       ({@link #windowLocked}), since you left it playing there.</li>
 * </ul>
 *
 * <p>Off, paused, before the settings are ready, for any handler of Facebook's own, or when anything
 * here throws, Facebook decides as it would unpatched, and without its flags that's no background
 * sound. A hook that can't read the player gives a no.
 */
public final class BackgroundPlay {
    /** How long after a tap ends a start still counts as yours. Tap to play's window. */
    static final long TAP_WINDOW_MS = TapToPlay.TAP_WINDOW_MS;

    /** A pause this soon after a tap ended is taken as that tap's, your own pause. */
    static final long TAPPED_PAUSE_MS = 500;

    /**
     * How long before Facebook asks its handler the pause that stopped the video may have come.
     * Facebook's own check allows a second; this is a little looser so its own decides.
     */
    static final long LEAVE_WINDOW_MS = 1500;

    /** How long a video one handler took on stops another from starting a second player for it. */
    static final long HANDLED_MS = 5000;

    /** How long after the window's headphones button its leave still carries the video on. */
    static final long WINDOW_BUTTON_MS = 5000;

    /** How long after Facebook's background player hands a locked window's video back it plays on. */
    static final long WINDOW_RESUME_MS = 400;
    /** How many times, that far apart, it looks for an open window on an unlocked phone. */
    static final int WINDOW_RESUME_TRIES = 5;
    /** How often the window's video is looked at once it's back from a lock. */
    static final long WINDOW_KEEP_MS = 500;
    /** How many presses of play in a row may fail to start the window's video before it's left alone. */
    static final int WINDOW_KEEP_MISSES = 4;
    /** How long before its end the window's video, back from a lock, starts over. */
    static final long WINDOW_START_OVER_LEAD_MS = 250;
    /** How close to its end a hand-back starts the window's video over instead of going on. */
    static final int WINDOW_NEAR_END_MS = 2000;
    private static final String WINDOW_RESUME = "window resume";
    private static final String WINDOW_BUTTONS = "window buttons";
    private static final int NO_POSITION = -1;

    /** The most players followed at once; the oldest goes first. */
    static final int FOLLOWED = 8;

    /** The trigger Facebook's background player starts, and its notification resumes, with. */
    static final String BACKGROUND_TRIGGER = "BY_BACKGROUND_PLAY";

    /** The trigger the window's headphones button pauses the window's video with, Android's own. */
    static final String WINDOW_PAUSE_TRIGGER = "BY_ANDROID";

    /** Facebook's manager, a kept name. */
    static final String MANAGER_CLASS = "com.facebook.video.bgplayback.manager.BackgroundPlaybackManager";

    /** The diagnostic counter route: each time the handler was asked, by what it answered. */
    static final String ROUTE = "Background play";
    static final String CONTINUED = "continued";
    static final String SWITCH_OFF = "switch off";
    static final String CLOSING = "screen closing";
    static final String NOT_YOURS = "not started by you";
    static final String NOT_PLAYING = "not playing";
    static final String TOO_EARLY = "paused before you left";
    static final String TAPPED = "paused with a tap";
    static final String TAKEN = "Facebook's handler took it";
    /** The reel after the one playing in the background, handed on by Next reel in the background. */
    static final String NEXT_REEL = "the next reel";

    private static final String SOURCE = "BackgroundPlay";

    /**
     * Reads what the rule needs of a player, and pauses one for the window's button. The patched one
     * reflects; a test stands in.
     */
    interface PlayerReader {
        boolean playing(Object player) throws Exception;

        @Nullable
        String videoId(Object player) throws Exception;

        /** Pauses [player] through its own pause with the trigger named [trigger]. */
        void pause(Object player, String trigger) throws Exception;

        /** Starts [player] again, as Tap to play lets a start it armed through. */
        void play(Object player) throws Exception;

        /** Where [player] is, in milliseconds. */
        int position(Object player) throws Exception;

        /** Moves [player] to [positionMs]. */
        void seek(Object player, int positionMs) throws Exception;

        /** How long [player]'s video is, in milliseconds, or 0 when that isn't known. */
        default int length(Object player) throws Exception {
            return 0;
        }

        /**
         * Makes [player] the current player of Facebook's video state manager, the one the window's own
         * buttons act on. Answers whether another player was current until now.
         */
        default boolean makeCurrent(Object player) throws Exception {
            return false;
        }

        /** Whether [player] is the current player of Facebook's video state manager. No when that can't be read. */
        default boolean isCurrent(Object player) throws Exception {
            return false;
        }

        /** Pauses and lets go of [player], as the handler's return does with its own background player. */
        default void release(Object player, String trigger) throws Exception {
            pause(player, trigger);
        }
    }

    static final PlayerReader PATCHED = new Reflected();

    /** The reader the hooks use. A test swaps it. */
    static volatile PlayerReader reader = PATCHED;

    private static final List<Followed> FOLLOWING = new ArrayList<>();

    /** The handler the extension gave its own manager, and that manager. */
    @Nullable
    static volatile Object handler;
    @Nullable
    private static volatile Object manager;
    private static final AtomicBoolean MANAGER_ASKED = new AtomicBoolean();

    /** True while the extension's handler takes on a video, from its check to its answer. */
    static volatile boolean carrying;
    /** True from a video carried on until the handler's return to Facebook has run. */
    static volatile boolean session;
    private static volatile boolean ending;
    @Nullable
    private static volatile String asked;
    @Nullable
    private static volatile String handledVideo;
    private static volatile long handledAt;
    /** Whether the Facebook screen that stopped last was closing or being rebuilt. */
    static volatile boolean closing;
    /** When the window's headphones button was last tapped, or 0. */
    private static volatile long windowLeftAt;
    /** The window's video a lock handed to Facebook's background player (#495), until that ends. */
    private static volatile WeakReference<Object> lockedPlayer = new WeakReference<>(null);
    /** The player Facebook's background player started last. */
    private static volatile WeakReference<Object> backgroundPlayer = new WeakReference<>(null);
    /** Whether that player was still playing as the handler's return began. */
    private static volatile boolean keptPlaying;
    /** Where it had got to in the locked window's video by then, or {@link #NO_POSITION}. */
    private static volatile int keptPosition = NO_POSITION;
    /** The window's video back from a lock, until it's paused or the window closes. */
    private static volatile WeakReference<Object> keptGoing = new WeakReference<>(null);
    /** Which round of looks at that video is the live one, so an old round stops by itself. */
    private static volatile int keptLook;
    /** How many looks in a row found that video stopped after it was pressed to play. */
    private static int keptMisses;

    /** Makes the next hook throw, once. For tests. */
    @Nullable
    static volatile RuntimeException failNext;

    private BackgroundPlay() { }

    // Filled in by the patch.

    /** The name of the player's no-argument video id accessor. The patch replaces this body. */
    @Nullable
    public static String videoIdMethod() {
        return null;
    }

    /** The name of the player's pause that takes only a trigger. The patch replaces this body. */
    @Nullable
    public static String pauseMethod() {
        return null;
    }

    /** The binary name of Facebook's fullscreen background handler. The patch replaces this body. */
    @Nullable
    public static String handlerClass() {
        return null;
    }

    /** The name of the player's field holding Facebook's video state manager. The patch replaces this body. */
    @Nullable
    public static String stateManagerField() {
        return null;
    }

    /** The name of that manager's field holding its current player. The patch replaces this body. */
    @Nullable
    public static String currentPlayerField() {
        return null;
    }

    // Hooks.

    /** The hook, first thing in FbGrootPlayer's play. */
    public static void started(Object player, Object trigger) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            throwIfAsked();
            if (!on()) return;
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "player start");
            String started = name(trigger);
            if (BACKGROUND_TRIGGER.equals(started)) backgroundPlayer = new WeakReference<>(player);
            if (start(player, started, SystemClock.uptimeMillis())) ensureManager();
            NextReel.started(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player start", failure);
        }
    }

    /** The hook, first thing in FbGrootPlayer's inner pause, before the player stops. */
    public static void pausing(Object player, Object trigger) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            throwIfAsked();
            if (player == keptGoing.get()) keptGoing = new WeakReference<>(null);
            if (!on()) return;
            Followed followed = followed(player);
            if (followed == null) return;
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "player pause");
            boolean playing;
            String video;
            try {
                playing = reader.playing(player);
                video = reader.videoId(player);
            } catch (Throwable unreadable) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player read", unreadable);
                return;
            }
            pause(followed, name(trigger), playing, video, SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player pause", failure);
        }
    }

    /**
     * The hook, first thing in the fullscreen background handler's check of the last recorded pause,
     * with the video and trigger of that record. True makes the check answer no at once.
     */
    public static boolean deciding(Object asking, @Nullable String videoId, Object trigger) {
        carrying = false;
        asked = videoId;
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            throwIfAsked();
            long now = SystemClock.uptimeMillis();
            Object ours = handler;
            if (ours == null || asking != ours) {
                // One of Facebook's own, in its own manager: it decides, unless ours just took this video.
                return handledRecently(videoId, now);
            }
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "background handler");
            String answer = NextReel.advancing ? NEXT_REEL
                    : handledRecently(videoId, now) ? TAKEN : judge(videoId, name(trigger), now);
            windowLeftAt = 0;
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, answer);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: " + answer);
            if (!CONTINUED.equals(answer) && !NEXT_REEL.equals(answer)) return true;
            carrying = true;
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "background handler", failure);
            carrying = false;
            return asking != null && asking == handler;
        }
    }

    /** Whether a handler took [videoId] on so recently that another would start a second player for it. */
    private static boolean handledRecently(@Nullable String videoId, long now) {
        String handled = handledVideo;
        return videoId != null && videoId.equals(handled) && now - handledAt <= HANDLED_MS;
    }

    /** Before each of Facebook's own checks the handler makes: true skips it for a video carried on. */
    public static boolean skipCheck() {
        if (!carrying) return false;
        HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "Facebook's checks");
        return true;
    }

    /** The notification's title, where Facebook would give up on a video without one. */
    @Nullable
    public static String title(@Nullable String title) {
        if (!carrying || (title != null && !title.isEmpty())) return title;
        try {
            return L10n.t("Facebook video");
        } catch (Throwable failure) {
            return "Facebook";
        }
    }

    /** The notification's second line, where Facebook would give up on a video without an owner. */
    @Nullable
    public static String subtitle(@Nullable String subtitle) {
        return carrying && subtitle == null ? "" : subtitle;
    }

    /** Before each answer of the handler's check. */
    public static void answered(boolean accepted) {
        try {
            if (accepted) {
                handledVideo = asked;
                handledAt = SystemClock.uptimeMillis();
            }
            if (!carrying) return;
            carrying = false;
            session = accepted;
            ending = false;
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> accepted
                    ? "Background play: Facebook's background player took over"
                    : "Background play: Facebook's handler didn't start its player");
            if (accepted) NextReel.carried();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "handler answer", failure);
        }
    }

    /** First thing in the notification's server flag check. True shows the notification. */
    public static boolean notificationAllowed() {
        if (!carrying && !session) return false;
        HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "notification");
        return true;
    }

    /** First thing in the handler's return to Facebook. */
    public static void returning(Object returning) {
        if (returning == null || returning != handler) return;
        NextReel.stop();
        if (session) {
            ending = true;
            keptPlaying = backgroundPlaying();
            keptPosition = keptPlaying ? backgroundPosition() : NO_POSITION;
        }
    }

    /** Before each of the return's own returns, once its player and notification are gone. */
    public static void returned() {
        if (!ending) return;
        ending = false;
        session = false;
        handledVideo = null;
        Object player = lockedPlayer.get();
        boolean goOn = keptPlaying;
        int position = keptPosition;
        lockedPlayer = new WeakReference<>(null);
        backgroundPlayer = new WeakReference<>(null);
        keptPlaying = false;
        keptPosition = NO_POSITION;
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: handed back, "
                + (player == null ? "no locked window" : goOn ? "playing at " + position : "paused"));
        if (player == null) return;
        resumeLater(player, goOn, position, WINDOW_RESUME_TRIES);
    }

    private static void resumeLater(Object player, boolean goOn, int position, int tries) {
        try {
            Utils.runOnMainThreadDelayed(() -> resumeWindow(player, goOn, position, tries), WINDOW_RESUME_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_RESUME, failure);
        }
    }

    /** Whether Facebook's background player is playing, so a video paused from the lock screen isn't. */
    private static boolean backgroundPlaying() {
        try {
            Object player = backgroundPlayer.get();
            return player != null && reader.playing(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_RESUME, failure);
            return false;
        }
    }

    /**
     * How far Facebook's background player got in the locked window's video. It's a player of its
     * own, so the window's stood still all the while. Nothing when it's playing something else.
     */
    private static int backgroundPosition() {
        try {
            Object background = backgroundPlayer.get();
            Object window = lockedPlayer.get();
            if (background == null || window == null || background == window) return NO_POSITION;
            String video = reader.videoId(window);
            if (video == null || !video.equals(reader.videoId(background))) return NO_POSITION;
            return reader.position(background);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_RESUME, failure);
            return NO_POSITION;
        }
    }

    /**
     * Unlocking gives the video back to the window, which Android resumes and pauses again, so it sat
     * paused though it had been playing all along (#495). Plays it on from where the background player
     * got to, if the window is open and nothing has started it since. The phone can still read as
     * locked just after the hand back, so a closed or locked window gets a few more looks. Playing or
     * paused, the window's own buttons go back to its player first: Facebook still had the background
     * player as its current one, gone with its session, so Pause and Play did nothing (#495 on 588).
     * A hand-back in the last two seconds starts it over: at 7551 of 8033 the seek landed on the
     * frame a second back, it buffered, and Facebook let the video go when its loop came (#495 on 588).
     */
    private static void resumeWindow(Object player, boolean goOn, int position, int tries) {
        try {
            if (!Utils.settingsReady() || !Settings.KEEP_PLAYING_IN_BACKGROUND.get()) return;
            if (!PictureInPicture.inOpenWindow(player)) {
                if (tries > 1) {
                    resumeLater(player, goOn, position, tries - 1);
                } else {
                    Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                            () -> "Background play: no open window on an unlocked phone to go back to");
                }
                return;
            }
            pointButtonsAt(player);
            if (!goOn) return;
            if (reader.playing(player)) {
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Background play: the window's video is playing already");
                keepGoing(player);
                return;
            }
            int length = reader.length(player);
            boolean nearItsEnd = position > 0 && length > 0 && length - position < WINDOW_NEAR_END_MS;
            if (nearItsEnd) {
                reader.seek(player, 0);
            } else if (position > 0) {
                reader.seek(player, position);
            }
            reader.play(player);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> nearItsEnd
                    ? "Background play: the window's video was back at " + position + " of " + length
                            + ", too near its end, so it starts over"
                    : "Background play: the window's video goes on from " + position + " of " + length);
            keepGoing(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_RESUME, failure);
        }
    }

    private static void pointButtonsAt(Object player) {
        try {
            if (reader.makeCurrent(player)) {
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Background play: the window's buttons pointed at another player, now at the window's");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_BUTTONS, failure);
        }
    }

    private static void keepGoing(Object player) throws Exception {
        keptGoing = new WeakReference<>(player);
        keptMisses = 0;
        int look = ++keptLook;
        if (!startOverNearItsEnd(player, look)) lookLater(player, look);
    }

    /**
     * Back from a lock, a window reel that plays to its end is lost: Facebook's end of video puts it
     * back at the start, stopped, then lets the video go, so nothing starts it again (#495 on 588).
     * A reel less than two looks from its end starts over just before it instead, so that never runs.
     * That end is what Facebook does to a player that isn't its current one. While the window's
     * player is current ({@link #pointButtonsAt}), Facebook loops it itself, and a second seek to the
     * start right after its own lost the video (#495 on 588: its loop at 8293 of 8520, this one 30 ms
     * later, then 0 of 0), so then it's left to Facebook.
     */
    private static boolean startOverNearItsEnd(Object player, int look) throws Exception {
        if (reader.isCurrent(player)) return false;
        int length = reader.length(player);
        int at = reader.position(player);
        if (length <= 0 || at < 0) return false;
        long left = length - at;
        if (left > 2 * WINDOW_KEEP_MS) return false;
        Utils.runOnMainThreadDelayed(() -> startOver(player, look), Math.max(0, left - WINDOW_START_OVER_LEAD_MS));
        return true;
    }

    private static void startOver(Object player, int look) {
        try {
            if (look != keptLook || keptGoing.get() != player) return;
            if (!Utils.settingsReady() || !Settings.KEEP_PLAYING_IN_BACKGROUND.get()
                    || !PictureInPicture.inOpenWindow(player)) {
                keptGoing = new WeakReference<>(null);
                return;
            }
            if (reader.playing(player) && !reader.isCurrent(player)) {
                int length = reader.length(player);
                int at = reader.position(player);
                if (length > 0 && at >= 0 && length - at <= WINDOW_KEEP_MS) {
                    reader.seek(player, 0);
                    Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                            () -> "Background play: the window's video came to its end at " + at + " of " + length
                                    + ", started over");
                } else if (startOverNearItsEnd(player, look)) {
                    // Not there yet, maybe still loading: it looks again just before the end.
                    return;
                }
            }
            lookLater(player, look);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_RESUME, failure);
        }
    }

    private static void lookLater(Object player, int look) {
        try {
            Utils.runOnMainThreadDelayed(() -> lookAtWindow(player, look), WINDOW_KEEP_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_RESUME, failure);
        }
    }

    /**
     * Back from a lock, Facebook's restart at the end of a reel starts the window's video and it stops
     * again moments later without any pause, so the sound ended at the first loop (#495). What stops
     * it sits below the player's pause and isn't known, but pressing play again holds. Anything that
     * does pause the video ends this, so a Pause in the window stays a pause. A player Facebook has
     * let go doesn't start whatever is pressed, so after {@link #WINDOW_KEEP_MISSES} presses in a row
     * that didn't take, it's left alone. A video playing close to its end starts over just before it
     * ({@link #startOverNearItsEnd}), since Facebook lets the video go once it reaches the end.
     */
    private static void lookAtWindow(Object player, int look) {
        try {
            if (look != keptLook || keptGoing.get() != player) return;
            if (!Utils.settingsReady() || !Settings.KEEP_PLAYING_IN_BACKGROUND.get()
                    || !PictureInPicture.inOpenWindow(player)) {
                keptGoing = new WeakReference<>(null);
                return;
            }
            if (reader.playing(player)) {
                keptMisses = 0;
                if (!startOverNearItsEnd(player, look)) lookLater(player, look);
                return;
            }
            int at = reader.position(player);
            int length = reader.length(player);
            String where = " at " + at + " of " + length;
            if (++keptMisses > WINDOW_KEEP_MISSES) {
                keptGoing = new WeakReference<>(null);
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Background play: the window's video won't start again" + where + ", left as it is");
                return;
            }
            if (atItsEnd(at, length)) {
                reader.seek(player, 0);
                reader.play(player);
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Background play: the window's video stopped at its end" + where + ", started over");
            } else {
                reader.play(player);
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Background play: the window's video stopped by itself" + where + ", started again");
            }
            lookLater(player, look);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, WINDOW_RESUME, failure);
        }
    }

    /**
     * Whether [player] stopped less than a look from its video's end. After a lock, Facebook's loop
     * back to the start sometimes never comes, and play on a video that has ended doesn't start it,
     * so the window's video sat on its last frame (#495). Such a video starts over, as the loop would.
     */
    private static boolean atItsEnd(int at, int length) {
        return length > 0 && at >= length - WINDOW_KEEP_MS;
    }

    /** Whether [player] is the one Facebook's background player is running a carried video on now. */
    /** The player Facebook's background player started last, or null. */
    @Nullable
    static Object currentBackgroundPlayer() {
        return backgroundPlayer.get();
    }

    static boolean isBackgroundPlayer(@Nullable Object player) {
        return player != null && playingOn() && player == backgroundPlayer.get();
    }

    /**
     * Whether a start Tap to play would hold is Facebook's background player carrying on a video
     * this switch let through, or its notification resuming it.
     */
    static boolean allowsStart(@Nullable String trigger) {
        return BACKGROUND_TRIGGER.equals(trigger) && playingOn();
    }

    /** Whether a video is being carried on, or Facebook's background player has one now. */
    static boolean playingOn() {
        return carrying || session;
    }

    // The rule.

    private static boolean on() {
        return Utils.settingsReady() && (Settings.KEEP_PLAYING_IN_BACKGROUND.get()
                || (Settings.BACKGROUND_PLAY_BUTTON.get() && leavingWindow(SystemClock.uptimeMillis())));
    }

    /** Whether the window's headphones button was tapped no more than {@link #WINDOW_BUTTON_MS} before [now]. */
    static boolean leavingWindow(long now) {
        long tapped = windowLeftAt;
        return tapped > 0 && now >= tapped && now - tapped <= WINDOW_BUTTON_MS;
    }

    /**
     * The window's headphones button: takes [player], the window's video, as one you started, so the
     * leave that follows carries it on, whether or not Keep playing in the background is on. Makes
     * the manager now, on the main thread, ahead of the stop it has to see, then pauses the video.
     *
     * <p>That pause is what the manager asks about. Leaving the window, Android stops the screen
     * without pausing it first, as it does when you leave a screen that's open, and the manager asks
     * as the screen stops, before Facebook's own pause of the video. Without one here it asked about
     * whatever Facebook recorded last, a play or another video's pause, and the window's video didn't
     * carry on.
     */
    static void leaveWindow(@Nullable Object player, Context context) throws Exception {
        long now = SystemClock.uptimeMillis();
        windowLeftAt = now;
        lockedPlayer = new WeakReference<>(null);
        if (player != null) {
            synchronized (FOLLOWING) {
                Followed followed = find(player);
                if (followed == null) followed = follow(player);
                followed.yours = true;
                followed.startedAt = now;
            }
        }
        if (manager == null && MANAGER_ASKED.compareAndSet(false, true)) makeManager(context);
        if (player != null) pauseForLeave(player);
    }

    /**
     * The phone locked with [player] playing in the window (#495). Android stops the window's screen
     * without a pause, the same gap the headphones button closes, so with Keep playing in the
     * background on this takes the same way out. A video you paused there stays paused.
     */
    static void windowLocked(@Nullable Object player, Context context) throws Exception {
        if (player == null || !Utils.settingsReady() || !Settings.KEEP_PLAYING_IN_BACKGROUND.get()) return;
        if (!reader.playing(player)) return;
        leaveWindow(player, context);
        lockedPlayer = new WeakReference<>(player);
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: window locked");
    }

    /** Pauses the window's video while it plays, so Facebook records the pause its manager reads. */
    private static void pauseForLeave(Object player) {
        try {
            if (!reader.playing(player)) return;
            reader.pause(player, WINDOW_PAUSE_TRIGGER);
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "window pause");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "window pause", failure);
        }
    }

    /** Records a start. Answers whether it was yours. */
    static boolean start(Object player, @Nullable String trigger, long now) {
        long sinceTap = TapClock.msSinceTap(now);
        boolean yours = sinceTap >= 0 && sinceTap <= TAP_WINDOW_MS && !TapToPlay.visibilityDriven(trigger)
                || inReels(player);
        synchronized (FOLLOWING) {
            Followed followed = find(player);
            if (yours) {
                if (followed == null) followed = follow(player);
                followed.yours = true;
            } else if (followed != null && followed.pausedAt >= followed.startedAt && automatic(trigger)) {
                // Facebook started it again on its own after a pause: it's no longer the video you chose.
                followed.yours = false;
            }
            if (followed != null) followed.startedAt = now;
        }
        return yours;
    }

    /**
     * Whether [player] plays in Facebook's Reels viewer, by its origin ("fb_shorts_tab::reels_tab",
     * "newsfeed::fb_shorts_viewer", "profile::fb_shorts_profile_viewer"): there the reel playing is the
     * one on screen, the one you swiped to. A reel in a feed ("fb_shorts_native_in_feed_unit") or a
     * story isn't. False when the origin can't be read.
     */
    static boolean inReels(Object player) {
        Object origin;
        try {
            origin = TapToPlay.origins.of(player);
        } catch (Throwable unreadable) {
            return false;
        }
        return reelsOrigin(origin);
    }

    /** Whether [origin], a PlayerOrigin, is the Reels viewer's (see {@link #inReels}). */
    static boolean reelsOrigin(@Nullable Object origin) {
        if (origin == null) return false;
        String name = origin.toString().toLowerCase(Locale.ROOT);
        return name.contains("fb_shorts") && !name.contains("in_feed") && !name.contains("story");
    }

    /** Whether Facebook sends [trigger] on its own: autoplay, or something coming into view or back. */
    static boolean automatic(@Nullable String trigger) {
        return trigger != null && (trigger.contains("AUTOPLAY") || TapToPlay.visibilityDriven(trigger));
    }

    static void pause(Followed followed, @Nullable String trigger, boolean playing, @Nullable String video, long now) {
        long sinceTap = TapClock.msSinceTap(now);
        boolean tapped = sinceTap >= 0 && sinceTap <= TAPPED_PAUSE_MS;
        synchronized (FOLLOWING) {
            followed.pausedAt = now;
            followed.pauseTrigger = trigger;
            followed.pauseVideo = video;
            if (playing) {
                followed.playingPausedAt = now;
                followed.playingPauseTapped = tapped;
                followed.playingPauseVideo = video;
            }
        }
    }

    /** What the handler answers for the recorded pause of [videoId] with [trigger]. */
    static String judge(@Nullable String videoId, @Nullable String trigger, long now) {
        if (!on()) return SWITCH_OFF;
        if (closing) return CLOSING;
        Followed match = null;
        synchronized (FOLLOWING) {
            purge();
            if (videoId != null && trigger != null) {
                for (Followed followed : FOLLOWING) {
                    if (!followed.yours || !videoId.equals(followed.pauseVideo) || !trigger.equals(followed.pauseTrigger)) {
                        continue;
                    }
                    if (match == null || followed.pausedAt > match.pausedAt) match = followed;
                }
            }
            if (match == null) return NOT_YOURS;
            if (match.playingPausedAt < match.startedAt || !videoId.equals(match.playingPauseVideo)) return NOT_PLAYING;
            if (now - match.playingPausedAt > LEAVE_WINDOW_MS) return TOO_EARLY;
            if (match.playingPauseTapped) return TAPPED;
        }
        return CONTINUED;
    }

    @Nullable
    private static Followed followed(Object player) {
        synchronized (FOLLOWING) {
            Followed followed = find(player);
            return followed != null && followed.yours ? followed : null;
        }
    }

    @Nullable
    private static Followed find(Object player) {
        purge();
        if (player == null) return null;
        for (Followed followed : FOLLOWING) {
            if (followed.player.get() == player) return followed;
        }
        return null;
    }

    private static Followed follow(Object player) {
        if (FOLLOWING.size() >= FOLLOWED) {
            Followed oldest = FOLLOWING.get(0);
            for (Followed followed : FOLLOWING) {
                if (followed.startedAt < oldest.startedAt) oldest = followed;
            }
            FOLLOWING.remove(oldest);
        }
        Followed followed = new Followed(player);
        FOLLOWING.add(followed);
        return followed;
    }

    private static void purge() {
        Iterator<Followed> each = FOLLOWING.iterator();
        while (each.hasNext()) {
            if (each.next().player.get() == null) each.remove();
        }
    }

    @Nullable
    private static String name(Object trigger) {
        return trigger instanceof Enum ? ((Enum<?>) trigger).name() : null;
    }

    private static void throwIfAsked() {
        RuntimeException failure = failNext;
        if (failure != null) {
            failNext = null;
            throw failure;
        }
    }

    // Facebook's manager and handler.

    /**
     * Makes Facebook's manager and one fullscreen handler for it, once, on the main thread. The
     * extension's own activity watcher goes in first, so it sees each stop before the manager does.
     */
    static void ensureManager() {
        if (manager != null || !MANAGER_ASKED.compareAndSet(false, true)) return;
        Utils.runOnMainThread(() -> {
            try {
                makeManager(Utils.getContext());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "background manager", failure);
            }
        });
    }

    static void makeManager(Context context) throws Exception {
        String handlerName = handlerClass();
        if (handlerName == null) {
            HookStatus.missingMember(FamilyNames.BACKGROUND_PLAY, "class", "background playback", "fullscreen handler");
            return;
        }
        Application application = (Application) context.getApplicationContext();
        application.registerActivityLifecycleCallbacks(new StopWatcher());
        ClassLoader loader = BackgroundPlay.class.getClassLoader();
        Object made = Class.forName(handlerName, true, loader).getDeclaredConstructor().newInstance();
        // Its constructor registers it for activity callbacks, as Facebook's own does.
        Object madeManager = Class.forName(MANAGER_CLASS, true, loader).getDeclaredConstructor().newInstance();
        handlersOf(madeManager).add(made);
        handler = made;
        manager = madeManager;
        HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "background manager");
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: handler ready");
    }

    /** The manager's one list, its handlers. */
    @SuppressWarnings("unchecked")
    static List<Object> handlersOf(Object manager) throws Exception {
        Field found = null;
        for (Field field : manager.getClass().getDeclaredFields()) {
            if (field.getType() != List.class) continue;
            if (found != null) throw new IllegalStateException("the manager has more than one list");
            found = field;
        }
        if (found == null) throw new IllegalStateException("the manager has no list of handlers");
        found.setAccessible(true);
        return (List<Object>) found.get(manager);
    }

    /** Notes whether each stopping Facebook screen is closing or being rebuilt, before the manager asks. */
    static final class StopWatcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            try {
                closing = activity.isFinishing() || activity.isChangingConfigurations();
            } catch (Throwable failure) {
                closing = true;
            }
            NextReel.stopped(activity);
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            closing = false;
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }

        @Override
        public void onActivityResumed(@NonNull Activity activity) { }

        @Override
        public void onActivityPaused(@NonNull Activity activity) { }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    /** What the rule keeps of one player you started, weakly: a player Facebook drops goes with it. */
    static final class Followed {
        final WeakReference<Object> player;
        boolean yours;
        long startedAt = Long.MIN_VALUE;
        long pausedAt = Long.MIN_VALUE;
        @Nullable
        String pauseTrigger;
        @Nullable
        String pauseVideo;
        long playingPausedAt = Long.MIN_VALUE;
        boolean playingPauseTapped;
        @Nullable
        String playingPauseVideo;

        Followed(Object player) {
            this.player = new WeakReference<>(player);
        }
    }

    /** Reads a player through its kept isPlaying and the video id accessor the patch named. */
    static final class Reflected implements PlayerReader {
        private final Map<Class<?>, Method> playing = new ConcurrentHashMap<>();
        private final Map<Class<?>, Method> videoIds = new ConcurrentHashMap<>();
        private final Map<Class<?>, Method> pauses = new ConcurrentHashMap<>();
        private final Map<Class<?>, Method> releases = new ConcurrentHashMap<>();
        private final Map<Class<?>, Field> managers = new ConcurrentHashMap<>();
        private final Map<Class<?>, Field> currents = new ConcurrentHashMap<>();

        @Override
        public boolean playing(Object player) throws Exception {
            Method method = playing.get(player.getClass());
            if (method == null) {
                method = player.getClass().getMethod("isPlaying");
                playing.put(player.getClass(), method);
            }
            return Boolean.TRUE.equals(method.invoke(player));
        }

        @Nullable
        @Override
        public String videoId(Object player) throws Exception {
            Method method = videoIds.get(player.getClass());
            if (method == null) {
                String name = videoIdMethod();
                if (name == null) {
                    HookStatus.missingMember(FamilyNames.BACKGROUND_PLAY, "method", "FbGrootPlayer", "video id");
                    return null;
                }
                method = player.getClass().getMethod(name);
                videoIds.put(player.getClass(), method);
            }
            Object id = method.invoke(player);
            return id instanceof String ? (String) id : null;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void pause(Object player, String trigger) throws Exception {
            Method method = pauses.get(player.getClass());
            if (method == null) {
                String name = pauseMethod();
                if (name == null) {
                    HookStatus.missingMember(FamilyNames.BACKGROUND_PLAY, "method", "FbGrootPlayer", "pause");
                    return;
                }
                for (Method each : player.getClass().getMethods()) {
                    Class<?>[] types = each.getParameterTypes();
                    if (each.getName().equals(name) && types.length == 1 && types[0].isEnum()) {
                        method = each;
                        break;
                    }
                }
                if (method == null) throw new NoSuchMethodException(name + "(trigger)");
                pauses.put(player.getClass(), method);
            }
            Class type = method.getParameterTypes()[0];
            method.invoke(player, Enum.valueOf(type, trigger));
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void release(Object player, String trigger) throws Exception {
            pause(player, trigger);
            Method method = releases.get(player.getClass());
            if (method == null) {
                String name = NextReel.releaseMethod();
                if (name == null) {
                    HookStatus.missingMember(FamilyNames.BACKGROUND_PLAY, "method", "FbGrootPlayer", "release");
                    return;
                }
                for (Method each : player.getClass().getMethods()) {
                    Class<?>[] types = each.getParameterTypes();
                    if (each.getName().equals(name) && types.length == 1 && types[0].isEnum()) {
                        method = each;
                        break;
                    }
                }
                if (method == null) throw new NoSuchMethodException(name + "(trigger)");
                releases.put(player.getClass(), method);
            }
            Class type = method.getParameterTypes()[0];
            method.invoke(player, Enum.valueOf(type, trigger));
        }

        @Override
        public void play(Object player) {
            TapToPlay.armForResume(player);
            FeedVideoControls.commandPlayer(player, true);
        }

        @Override
        public int position(Object player) {
            return FeedVideoControls.playerPosition(player);
        }

        @Override
        public void seek(Object player, int positionMs) {
            FeedVideoControls.seekPlayer(player, null, positionMs);
        }

        @Override
        public int length(Object player) {
            return FeedVideoControls.playerLength(player);
        }

        @Override
        public boolean makeCurrent(Object player) throws Exception {
            return makeCurrent(player, stateManagerField(), currentPlayerField());
        }

        @Override
        public boolean isCurrent(Object player) throws Exception {
            return isCurrent(player, stateManagerField(), currentPlayerField());
        }

        /**
         * Puts a new WeakReference to [player] in the [currentName] field of the manager its
         * [managerName] field holds, unless that already refers to [player].
         */
        boolean makeCurrent(Object player, @Nullable String managerName, @Nullable String currentName)
                throws Exception {
            Object manager = manager(player, managerName, currentName);
            if (manager == null) return false;
            Field current = field(currents, manager.getClass(), currentName);
            Object held = current.get(manager);
            if (held instanceof WeakReference && ((WeakReference<?>) held).get() == player) return false;
            current.set(manager, new WeakReference<>(player));
            return true;
        }

        /** Whether the [currentName] field of the manager [player]'s [managerName] field holds refers to [player]. */
        boolean isCurrent(Object player, @Nullable String managerName, @Nullable String currentName)
                throws Exception {
            Object manager = manager(player, managerName, currentName);
            if (manager == null) return false;
            Object held = field(currents, manager.getClass(), currentName).get(manager);
            return held instanceof WeakReference && ((WeakReference<?>) held).get() == player;
        }

        @Nullable
        private Object manager(Object player, @Nullable String managerName, @Nullable String currentName)
                throws Exception {
            if (managerName == null || currentName == null) {
                HookStatus.missingMember(FamilyNames.BACKGROUND_PLAY, "field", "FbGrootPlayer", "video state manager");
                return null;
            }
            return field(managers, player.getClass(), managerName).get(player);
        }

        private static Field field(Map<Class<?>, Field> found, Class<?> owner, String name) throws NoSuchFieldException {
            Field field = found.get(owner);
            if (field != null) return field;
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                for (Field each : type.getDeclaredFields()) {
                    if (!each.getName().equals(name)) continue;
                    each.setAccessible(true);
                    found.put(owner, each);
                    return each;
                }
            }
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
    }

    /** Forgets every followed player, the decision and the handler. For tests. */
    static void forget() {
        synchronized (FOLLOWING) {
            FOLLOWING.clear();
        }
        carrying = false;
        session = false;
        ending = false;
        asked = null;
        handledVideo = null;
        handledAt = 0;
        closing = false;
        windowLeftAt = 0;
        lockedPlayer = new WeakReference<>(null);
        backgroundPlayer = new WeakReference<>(null);
        keptPlaying = false;
        keptPosition = NO_POSITION;
        keptGoing = new WeakReference<>(null);
        keptLook = 0;
        keptMisses = 0;
        handler = null;
        manager = null;
        MANAGER_ASKED.set(false);
        reader = PATCHED;
        failNext = null;
        NextReel.forget();
    }

    /** Takes [made] as the extension's handler, as if the manager had been made. For tests. */
    static void useHandler(Object made) {
        handler = made;
        manager = new Object();
        MANAGER_ASKED.set(true);
    }
}
