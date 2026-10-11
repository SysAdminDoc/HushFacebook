/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Picture-in-picture for reels, through the picture-in-picture Facebook already ships for its
 * Reels viewer (ReelsPipUtil) behind server flags. The viewer first asks a gate of its own whether
 * its surface has picture-in-picture, then ReelsPipUtil whether the activity may use it: Android 12
 * or later, its flags or a preference of its own, the phone's picture-in-picture feature and a
 * memory check. With the switch on, the gate says yes on Android 12 or later, and the check says
 * yes there whenever the phone has the feature. Facebook's own code does the rest: it sets the
 * window's aspect ratio and turns on Android's auto-enter, so a playing reel shrinks into a window
 * when you leave Facebook.
 *
 * <p>Facebook arms the window once as the Reels viewer opens, for that one player: it keeps that
 * player going in the window and turns auto-enter off and on as it pauses and plays. A swipe starts
 * the next reel's player without arming again, so the window would open on a frozen reel, or not at
 * all once the first one's pause turned auto-enter off. So when another reel starts, the extension
 * arms the window again with Facebook's own arguments for that reel's player. And while the window
 * is armed, it sets Facebook's change of auto-enter aside and decides from the reel on screen:
 * while it's paused, auto-enter is off, set the way Facebook's own disarm sets it, and when a reel
 * plays, it's back on.
 *
 * <p>In the window, Facebook's viewer opens in a container with a new view id, which a React
 * Native view on the main screen can already hold. The extension swaps such an id for a free one,
 * or the viewer opens hidden and the window stays black.
 *
 * <p>Facebook's full-screen Watch viewer arms the same window for the video on screen, behind a
 * server flag of its own. With the switch on, the extension answers that flag yes, and Facebook's
 * own code arms the window; the rest here treats that arming like any other.
 *
 * <p>The Video tab arms the same window as you scroll its videos, behind two answers of its own:
 * the gate the Reels viewer asks, which this copy reads inline, and a server flag for its deep-dive
 * viewer. With the switch on, both say yes, and Facebook's own code arms the window for the video on
 * screen. Its checks for ads, live videos and the video's shape stay Facebook's, so those still open
 * no window.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class PictureInPicture {
    /** Counted under the patch's name each time the check says yes where Facebook would decide. */
    static final String ALLOWED = "picture-in-picture allowed";

    /** Counted each time the Reels viewer's gate says yes where Facebook would decide. */
    static final String SURFACE = "reels viewer allowed";

    /** Counted each time a paused reel turns auto-enter off. */
    static final String HELD = "paused reel held";

    /** Counted each time a start Tap to play holds turns auto-enter off. */
    static final String START_HELD = "held start kept out of the window";

    /** Counted each time Facebook's own change of auto-enter is set aside. */
    static final String SET_ASIDE = "facebook change set aside";

    /** Counted each time the window is armed again for the reel a swipe started. */
    static final String REARMED = "armed again for the next reel";

    /** Counted each time the window's viewer gets a view id of its own instead of one a view already holds. */
    static final String VIEWER_ID = "viewer id replaced";

    /** Counted each time the full-screen Watch viewer's flag says yes where Facebook's answer was no. */
    static final String IMMERSIVE = "watch viewer flag opened";

    /** Counted each time the Video tab's copy of the Reels viewer's gate says yes where Facebook's answer was no. */
    static final String HOME_GATE = "video tab gate opened";

    /** Counted each time the Video tab's flag for its deep-dive viewer says yes where Facebook's answer was no. */
    static final String HOME_FLAG = "video tab flag opened";

    /** How many new view ids the viewer may go through for one nothing on screen holds. */
    private static final int VIEWER_ID_TRIES = 64;

    private static final String FAMILY = FamilyNames.PICTURE_IN_PICTURE;

    /** The Android version the hooks go by. Tests stand in a later one. */
    static volatile int apiLevel = Build.VERSION.SDK_INT;

    /**
     * The screen ReelsPipUtil last armed the window on, the player that last started while it's
     * armed, and whether a pause holds auto-enter.
     */
    private static WeakReference<Activity> armedScreen = new WeakReference<>(null);
    private static WeakReference<Object> current = new WeakReference<>(null);
    private static boolean held;

    /**
     * The arguments ReelsPipUtil last armed the window with, kept until it disarms, as Facebook
     * keeps its own: a swipe arms again with them for the next reel's player.
     */
    private static Object[] arming;

    private PictureInPicture() {
    }

    /**
     * The hook, first thing in ReelsPipUtil's check of whether [activity] may use
     * picture-in-picture. True answers yes; false leaves the answer to Facebook's own check.
     */
    public static boolean allowed(Activity activity) {
        try {
            if (activity == null || !on()) return false;
            // Entering throws on a phone without the feature, which Facebook's check reads too.
            if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                return false;
            }
            HookStatus.bound(FAMILY, "picture-in-picture check");
            HookStatus.counted(FAMILY, ALLOWED);
            Logger.printDebug(() -> "Picture-in-picture: allowed for " + activity.getClass().getSimpleName());
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "picture-in-picture check", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in the Reels viewer's gate of whether its surface has
     * picture-in-picture. True answers yes; false leaves the answer to Facebook's own gate.
     */
    public static boolean surfaceAllowed() {
        try {
            if (!on()) return false;
            HookStatus.bound(FAMILY, "reels viewer gate");
            HookStatus.counted(FAMILY, SURFACE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reels viewer gate", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in ReelsPipUtil's arming of the window for [player] on [activity], with
     * all of the arming's [arguments].
     */
    public static void armed(Activity activity, Object player, Object[] arguments) {
        try {
            arm(activity, player, arguments);
            Logger.printDebug(() -> "Picture-in-picture: armed for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming", failure);
        }
    }

    /** The hook, first thing in each of ReelsPipUtil's disarms. Nothing is armed after it. */
    public static void disarmed() {
        try {
            arm(null, null, null);
            Logger.printDebug(() -> "Picture-in-picture: disarmed");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "disarm", failure);
        }
    }

    /** Facebook just set auto-enter itself, on or off, so nothing is held any more. */
    private static synchronized void arm(Activity activity, Object player, Object[] arguments) {
        armedScreen = new WeakReference<>(activity);
        current = new WeakReference<>(player);
        arming = arguments;
        held = false;
    }

    /**
     * The hook, first thing in FbGrootPlayer's pause. The reel on screen pausing turns auto-enter
     * off, unless the window is already open.
     */
    public static void playerPaused(Object player) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || player == null) return;
            Activity screen = holding(player);
            if (screen == null) return;
            autoEnter(screen, false);
            HookStatus.counted(FAMILY, HELD);
            Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " paused, held");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player pause", failure);
        }
    }

    /**
     * Tap to play, as it holds the start of [player], a video nobody chose to play. Facebook arms
     * the window for each reel as it starts, auto-enter on, a moment before that start is held, and
     * nothing pauses a start that never went ahead. So leaving Facebook opened the window and played
     * the held reel there, and back in Facebook it showed time gone that nobody watched (#90). While
     * the window is armed and closed, auto-enter goes off as it does for a paused reel, and the tap
     * that plays a reel turns it back on ({@link #playerPlaying}).
     */
    public static void startHeld(Object player) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || player == null) return;
            Activity screen = holdingStart();
            if (screen == null) return;
            autoEnter(screen, false);
            HookStatus.counted(FAMILY, START_HELD);
            Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " held by Tap to play, held");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "held start", failure);
        }
    }

    /**
     * The armed screen when a held start should hold auto-enter, marked held: whichever player the
     * window was armed for, since the reel on screen is the held one and nothing plays.
     */
    private static synchronized Activity holdingStart() {
        Activity screen = armedScreen.get();
        if (screen == null || screen.isInPictureInPictureMode() || !on()) return null;
        held = true;
        return screen;
    }

    /**
     * The hook, in FbGrootPlayer's play once it goes ahead. While the window is armed, [player] is
     * the reel on screen from now on: a held auto-enter goes back on, switch or no switch, since it
     * was the extension that turned it off, and a reel other than the armed one gets the window
     * armed again for it.
     */
    public static void playerPlaying(Object player) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || player == null) return;
            Object[] next = switching(player);
            Activity screen = releasing();
            if (screen != null) {
                autoEnter(screen, true);
                Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " playing, released");
            }
            if (next != null) Utils.runOnMainThread(() -> armAgain(next, player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player play", failure);
        }
    }

    /**
     * The arguments to arm the window again with, when [player] isn't the reel on screen and the
     * switch is on. Either way, while the window is armed, [player] is the reel on screen now.
     */
    private static synchronized Object[] switching(Object player) {
        if (armedScreen.get() == null || player == current.get()) return null;
        current = new WeakReference<>(player);
        return arming != null && on() ? arming : null;
    }

    /** The armed screen when a pause holds auto-enter, released. */
    private static synchronized Activity releasing() {
        if (!held) return null;
        held = false;
        return armedScreen.get();
    }

    /**
     * Arms the window again with [arguments] for [player], on the main thread where Facebook arms
     * it, unless it was disarmed or another reel started since.
     */
    private static void armAgain(Object[] arguments, Object player) {
        try {
            synchronized (PictureInPicture.class) {
                if (arguments != arming || player != current.get()) return;
            }
            Activity screen = armedScreen.get();
            if (screen == null || screen.isFinishing() || screen.isDestroyed()) return;
            if (!rearm(arguments, player)) return;
            HookStatus.bound(FAMILY, "arming again");
            HookStatus.counted(FAMILY, REARMED);
            Logger.printDebug(() -> "Picture-in-picture: armed again for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming again", failure);
        }
    }

    /**
     * Filled in by the patch: arms the window again through ReelsPipUtil with the [arguments] of its
     * last arming, for [player] and that player's own video. False until the patch fills it in.
     */
    public static boolean rearm(Object[] arguments, Object player) {
        return false;
    }

    /** The armed screen when [player] is the reel on screen and its pause should hold auto-enter, marked held. */
    private static synchronized Activity holding(Object player) {
        if (held || player != current.get()) return null;
        Activity screen = armedScreen.get();
        if (screen == null || screen.isInPictureInPictureMode() || !on()) return null;
        held = true;
        return screen;
    }

    /**
     * The hook, first thing in the runnable ReelsPipUtil posts when its one player's state turns
     * auto-enter off or on. True sets that change aside: while the window is armed, the reel on
     * screen decides. False lets Facebook set it.
     */
    public static boolean setAside() {
        try {
            if (!tracking() || !on()) return false;
            HookStatus.bound(FAMILY, "auto-enter change");
            HookStatus.counted(FAMILY, SET_ASIDE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "auto-enter change", failure);
            return false;
        }
    }

    /**
     * The hook, in the opening of the window's viewer, right after it asks for a new view id for
     * the container it opens in. The viewer's fragment finds that container by its id from the top
     * of [activity]'s screen, and a new id can be one a React Native view already set itself, as
     * Marketplace's does. That view wins, so the viewer opens inside the hidden main screen at no
     * size, and the window stays black while the reel plays. So an [id] a view holds is swapped for
     * one none does; a free one, or one past the tries, stays Facebook's own.
     */
    public static int viewerId(Activity activity, int id) {
        try {
            if (activity == null || !on() || activity.findViewById(id) == null) return id;
            int free = id;
            for (int tries = 0; tries < VIEWER_ID_TRIES && activity.findViewById(free) != null; tries++) {
                free = View.generateViewId();
            }
            if (activity.findViewById(free) != null) return id;
            int chosen = free;
            HookStatus.bound(FAMILY, "viewer id");
            HookStatus.counted(FAMILY, VIEWER_ID);
            Logger.printDebug(() -> "Picture-in-picture: viewer id " + id + " was taken, " + chosen + " instead");
            return chosen;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "viewer id", failure);
            return id;
        }
    }

    /**
     * The hook, right after the full-screen Watch viewer reads the server flag that lets it arm its
     * picture-in-picture window, with Facebook's [answer]. A no turns into a yes while the switch is
     * on; a yes stays one, and everything else is Facebook's own.
     */
    public static boolean immersiveAllowed(boolean answer) {
        try {
            boolean enabled = on();
            if (!enabled) return answer;
            HookStatus.bound(FAMILY, "watch viewer flag");
            if (answer) return true;
            HookStatus.counted(FAMILY, IMMERSIVE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "watch viewer flag", failure);
            return answer;
        }
    }

    /**
     * The hook, right after the Video tab's arming reads its own copy of the Reels viewer's gate,
     * with Facebook's [answer]. A no turns into a yes while the switch is on.
     */
    public static boolean homeGateAllowed(boolean answer) {
        return opened(answer, "video tab gate", HOME_GATE);
    }

    /**
     * The hook, right after the Video tab's arming reads the server flag that lets its deep-dive
     * viewer keep the window, with Facebook's [answer]; Facebook's no turns the window off. A no
     * turns into a yes while the switch is on.
     */
    public static boolean homeFlagAllowed(boolean answer) {
        return opened(answer, "video tab flag", HOME_FLAG);
    }

    /**
     * Facebook's [answer] to a question that only decides whether to arm the window: yes while the
     * switch is on, counting [counted] when it was a no, and Facebook's own otherwise.
     */
    private static boolean opened(boolean answer, String hook, String counted) {
        try {
            if (!on()) return answer;
            HookStatus.bound(FAMILY, hook);
            if (answer) return true;
            HookStatus.counted(FAMILY, counted);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, hook, failure);
            return answer;
        }
    }

    /** Whether a window is armed with a reel on screen. */
    private static synchronized boolean tracking() {
        return armedScreen.get() != null && current.get() != null;
    }

    /** Params with only auto-enter set change only auto-enter, as Facebook's own disarm relies on. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static void autoEnter(Activity screen, boolean enabled) {
        screen.setPictureInPictureParams(new PictureInPictureParams.Builder().setAutoEnterEnabled(enabled).build());
    }

    /** Whether the switch is on, on Android 12 or later: Facebook's own code needs its auto-enter. */
    private static boolean on() {
        HookStatus.invoked(FAMILY);
        return Utils.settingsReady() && Settings.PICTURE_IN_PICTURE.get() && apiLevel >= Build.VERSION_CODES.S;
    }

    /** Forgets what was armed. Tests only. */
    static void forget() {
        arm(null, null, null);
    }
}
