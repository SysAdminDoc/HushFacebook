/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.app.Application;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.navigation.FacebookTabs;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

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
 * arms the window again with Facebook's own arguments for that reel's player, in that reel's own
 * shape, worked out the way Facebook works it out when it arms. A reel of a shape Facebook arms no
 * window for, a landscape one, isn't armed again: it opens no window, as with Facebook's own arming,
 * where before it opened in the last reel's tall shape around the wide video (#126). And while the
 * window is armed, it sets Facebook's change of auto-enter aside and decides from the reel on screen:
 * while it's paused, or of a shape the window doesn't take, auto-enter is off, set the way
 * Facebook's own disarm sets it, and when a reel plays, it's back on.
 *
 * <p>In the window, Facebook's viewer opens in a container with a new view id, which a React
 * Native view on the main screen can already hold. The extension swaps such an id for a free one,
 * or the viewer opens hidden and the window stays black.
 *
 * <p>That viewer hides every view of the screen as it opens, and on the way back Facebook shows
 * them all again, whatever they were before. The spots for views Facebook builds only once they're
 * shown are among them, so the reel viewer got Facebook Flex's switcher bar over the reel (#126).
 * The extension keeps how each view showed and puts that back.
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
 * <p>When the Reels tab's window floats, it holds Facebook's main screen and the task every Facebook
 * activity joins, so a link, a notification or a settings shortcut opened meanwhile drew its page in
 * the window and left it there. A page opening in that task while the window floats brings Facebook
 * back full screen ({@link #arrived}).
 *
 * <p>When a reel floats from Facebook's full-screen viewer instead, the window's task has the viewer
 * at its root, and a link opened from another app starts that same viewer for its page, so Android
 * only brought the floating reel forward and the page never opened. A link that also builds
 * Facebook's main screen under its page cleared the window's task and drew that screen in the
 * window. Facebook's launch step gives such a start a task of its own, so the page opens full screen
 * and the reel keeps floating ({@link #ownTask}).
 *
 * <p>While the window is armed, a reel's More sheet opened on the armed screen gets a
 * Picture-in-picture row ({@link PictureInPictureRow}), whose tap opens the window right away with
 * the shape Facebook armed it with (#44). With Only from a reel's More menu on as well, leaving
 * Facebook opens no window: auto-enter goes off once Facebook has armed, Facebook's own changes of
 * it are set aside, and nothing here turns it back on, so the row is the only way in.
 *
 * <p>Off, ReelsPipUtil's check says no, so the window Facebook turns on for some accounts by itself
 * stays off as well (#252, #256). Paused, before the settings are ready, or when anything here
 * fails, the answer is Facebook's own.
 */
public final class PictureInPicture {
    /** Counted under the patch's name each time the check says yes where Facebook would decide. */
    static final String ALLOWED = "picture-in-picture allowed";

    /** Counted each time the check says no with the switch off, where Facebook would decide. */
    static final String REFUSED = "picture-in-picture refused";

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

    /** Counted each time a reel of a shape Facebook arms no window for turns auto-enter off. */
    static final String KEPT_OUT = "wide reel kept out of the window";

    /** Counted each time a player whose video is off screen plays and the window stays with the reel on screen. */
    static final String OFF_SCREEN = "off-screen player left out of the window";

    /** Counted each time a player on another screen plays and the window stays with the armed screen's reel. */
    static final String OTHER_SCREEN = "player on another screen left out of the window";

    /** Counted each time the window is armed again because Facebook didn't after it was opened full screen. */
    static final String REARMED_AFTER_WINDOW = "armed again after the window closed";

    /** How long after its screen pauses the page watch looks whether it went into the window. */
    static final long FLOAT_CHECK_MS = 800;

    /** How long Facebook gets to arm the window itself once its screen comes back full screen. */
    static final long REARM_AFTER_WINDOW_MS = 1000;

    /** Counted each time a reel keeps its place where Facebook put it back at its start as its screen came back. */
    static final String PLACE_KEPT = "reel kept its place as its screen came back";

    /** How long after its screen resumes a seek to a reel's start still belongs to the screen coming back. */
    static final long RETURN_MS = 1500;

    /** Counted each time a reel that just ended is left to go back to its start and loop. */
    static final String LOOP_LEFT = "reel left to loop as it ended";

    /** How long after a reel ends a seek to its start is its loop. Facebook seeks from inside its end-of-video step, so at once. */
    static final long LOOP_MS = 1000;

    /** The triggers of the seeks that put a reel back at its start: the viewer's for a reel out of sight, the bind's, and the autoplay's as it picks the reel up again. */
    static final Set<String> REWINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "BY_SHORT_FORM_VIDEO_INVISIBLE", "BY_GROOT_TRANSITION", "BY_AUTOPLAY")));

    /**
     * The narrowest and widest window Facebook arms for a reel, width to height, as its arming
     * checks it: 1 by 2.39, Android's own narrowest, up to square.
     */
    static final float NARROWEST = 1f / 2.39f;
    static final float WIDEST = 1f;

    /** Counted each time the window's viewer gets a view id of its own instead of one a view already holds. */
    static final String VIEWER_ID = "viewer id replaced";

    /** Counted each time the full-screen Watch viewer's flag says yes where Facebook's answer was no. */
    static final String IMMERSIVE = "watch viewer flag opened";

    /** Counted each time the Video tab's copy of the Reels viewer's gate says yes where Facebook's answer was no. */
    static final String HOME_GATE = "video tab gate opened";

    /** Counted each time the Video tab's flag for its deep-dive viewer says yes where Facebook's answer was no. */
    static final String HOME_FLAG = "video tab flag opened";

    /** Counted each time a page opened in the floating window's task brought Facebook back full screen. */
    static final String BROUGHT_BACK = "page opened in the window brought back full screen";

    /** Counted each time a page started while a reel floats got a task of its own, so it opened full screen. */
    static final String OWN_TASK = "page started in a task of its own";

    /** The extra Facebook puts on its main screen's start when it builds that screen under a page. */
    static final String UNDER_A_PAGE = "launched_in_background_of_activity_stack";

    /** Counted each time auto-enter goes off after an arming because the window opens only from the More menu. */
    static final String MENU_ONLY = "auto-enter kept off for the More menu";

    /** Counted each time auto-enter goes off because the Hushfacebook settings show over the armed screen. */
    static final String UNDER_SETTINGS = "auto-enter kept off under the settings";

    /** Counted each time the More sheet's Picture-in-picture row opened the window. */
    static final String FROM_MENU = "opened from the More menu";

    /** Counted each time the floating button opened the window. */
    static final String FROM_BUTTON = "opened from the button";

    /** Counted each time a view back from the window shows as it did before, not as Facebook asked. */
    static final String AS_BEFORE = "view kept as before the window";

    /** How many new view ids the viewer may go through for one nothing on screen holds. */
    private static final int VIEWER_ID_TRIES = 64;

    /**
     * How long after bringing Facebook back another page in the window waits before it may do so
     * again, so a start that didn't bring the window back can't start Facebook over and over.
     */
    static final long BRING_BACK_GAP_MS = 3000;

    /**
     * How long after a start got a task of its own while a reel floats a screen opening in the window
     * is left alone. The page is already full screen in its own task, so starting Facebook again would
     * only stack its launcher screen over it: on the S22 a SimpleLoginActivity opened and closed in the
     * window right after a link's page left it, and the launcher start covered the page.
     */
    static final long SENT_AWAY_GAP_MS = 5000;

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

    /** Whether the reel on screen has a shape the window doesn't take, so auto-enter stays off for it. */
    private static boolean unfit;

    /**
     * The screen the Hushfacebook settings show over while the switch is on, until they close. Its
     * auto-enter stays off meanwhile, so leaving Facebook with the settings up doesn't float them.
     */
    private static WeakReference<Activity> underSettings = new WeakReference<>(null);

    /**
     * The arguments ReelsPipUtil last armed the window with, kept until it disarms, as Facebook
     * keeps its own: a swipe arms again with them for the next reel's player.
     */
    private static Object[] arming;

    /** When a page in the window last brought Facebook back, in uptime milliseconds. */
    private static long broughtBackAt = -BRING_BACK_GAP_MS;

    /** When a start while a reel floats last got a task of its own, in uptime milliseconds. */
    private static long sentAwayAt = -SENT_AWAY_GAP_MS;

    /** The application the page watch went on, so arming again adds no second one. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    /**
     * Set on a thread once the first start of a page's task stack got a task of its own, until that
     * thread's next message: the starts after it follow it into its task.
     */
    private static final ThreadLocal<Boolean> stackGoingBy = new ThreadLocal<>();

    /** The armed screen while the page watch saw it in the window, until it comes back full screen. */
    private static WeakReference<Activity> floating = new WeakReference<>(null);

    /** The armed screen while it's paused, floating in the window or behind something else. */
    private static WeakReference<Activity> away = new WeakReference<>(null);

    /** When a screen last came back from being {@link #away}. */
    private static long backAt = -RETURN_MS;

    /** The player whose video last reached its end, and when. */
    private static WeakReference<Object> ended = new WeakReference<>(null);
    private static long endedAt = -LOOP_MS;

    /** Goes up with each arming and disarm, so a later look can tell whether Facebook did either. */
    private static int armings;

    /** How each view the window's viewer hid showed before, until it's shown again. */
    private static final Map<View, Integer> beforeWindow = Collections.synchronizedMap(new WeakHashMap<>());

    /** Reads the view a player's video is attached to. Tests stand in their own. */
    interface Views {
        @Nullable
        View of(Object player);
    }

    static volatile Views views = PictureInPicture::playerView;

    private PictureInPicture() {
    }

    /** Filled in by the patch: the view FbGrootPlayer's video is attached to, or null. Only a player may be passed. */
    @Nullable
    public static ViewGroup playerView(Object player) {
        return null;
    }

    /**
     * Whether [player]'s video is attached to a view that isn't on screen: not shown, or less than
     * half of it visible. Facebook starts the reel next to the one on screen for a moment and pauses
     * it again as it gets it ready, and that reel's view sits beside the screen. A player with no
     * view known, or a view that can't be read, counts as on screen, as before (#126).
     */
    static boolean offScreen(Object player) {
        try {
            View view = views.of(player);
            if (view == null) return false;
            int width = view.getWidth();
            int height = view.getHeight();
            if (!view.isAttachedToWindow() || !view.isShown() || width <= 0 || height <= 0) return true;
            Rect visible = new Rect();
            if (!view.getGlobalVisibleRect(visible)) return true;
            return (long) visible.width() * visible.height() * 2 < (long) width * height;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Whether [player]'s video is attached to a view of a screen other than the armed one. Once the
     * reel viewer floats, Facebook opened from the launcher shows Home full screen beside it, and
     * Home's inline videos then armed the floating window for themselves. A player with no view
     * known, or a view whose screen can't be read, counts as the armed screen's, as before.
     */
    static boolean onAnotherScreen(Object player) {
        try {
            View view = views.of(player);
            if (view == null) return false;
            Activity screen = activityOf(view.getContext());
            Activity armed = armedScreen();
            return screen != null && armed != null && screen != armed;
        } catch (Throwable failure) {
            return false;
        }
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
     * The hook, in ReelsPipUtil's check after {@link #allowed} left it to Facebook. True answers no.
     * Facebook turns its own window on for some accounts, from a server flag or its Media settings,
     * and every Facebook arming asks this check first, so the switch off keeps that window off too
     * (#252, #256). Paused, Facebook decides.
     */
    public static boolean refused() {
        try {
            if (!Utils.settingsReady() || Setting.isPaused() || Settings.PICTURE_IN_PICTURE.get()) return false;
            HookStatus.bound(FAMILY, "picture-in-picture check");
            HookStatus.counted(FAMILY, REFUSED);
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
            if (activity != null) watch(activity.getApplication());
            // Facebook turns auto-enter on right after this hook, so it goes off again once the arming is done.
            if (activity != null && (menuOnly() || underSettings(activity))) Utils.runOnMainThread(() -> keepForMenu(activity));
            PictureInPictureButton.sync();
            Logger.printDebug(() -> "Picture-in-picture: armed for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming", failure);
        }
    }

    /**
     * Turns auto-enter off on [screen] after Facebook armed it, while the window opens only from the
     * More menu or the settings show over it, unless it was disarmed or armed on another screen
     * since, or the window is open.
     */
    static void keepForMenu(Activity screen) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            synchronized (PictureInPicture.class) {
                if (armedScreen.get() != screen) return;
            }
            if (screen.isInPictureInPictureMode()) return;
            if (menuOnly()) {
                autoEnter(screen, false);
                HookStatus.bound(FAMILY, "More menu only");
                HookStatus.counted(FAMILY, MENU_ONLY);
            } else if (underSettings(screen)) {
                autoEnter(screen, false);
                HookStatus.counted(FAMILY, UNDER_SETTINGS);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "More menu only", failure);
        }
    }

    /**
     * The Hushfacebook settings showing over [screen]. A reel playing under them kept the window
     * armed, so pressing Home with the settings up floated the settings page in the window. With
     * the switch on, auto-enter goes off on the screen until they close, and an arming meanwhile
     * is kept off too ({@link #keepForMenu}).
     */
    public static void settingsShown(@Nullable Activity screen) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || screen == null || !on()) return;
            synchronized (PictureInPicture.class) {
                underSettings = new WeakReference<>(screen);
                if (armedScreen.get() != screen) return;
            }
            if (screen.isInPictureInPictureMode()) return;
            autoEnter(screen, false);
            HookStatus.counted(FAMILY, UNDER_SETTINGS);
            Logger.printDebug(() -> "Picture-in-picture: settings shown over the armed screen, auto-enter off");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "settings shown", failure);
        }
    }

    /**
     * The settings closed. Auto-enter comes back on the screen they showed over as the reel on
     * screen has it: on, unless a pause holds it, the reel doesn't fit the window, or the window was
     * disarmed or armed on another screen since.
     */
    public static void settingsClosed() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            Activity screen;
            synchronized (PictureInPicture.class) {
                screen = underSettings.get();
                underSettings = new WeakReference<>(null);
                if (screen == null || armedScreen.get() != screen || held || unfit || current.get() == null) return;
            }
            if (screen.isFinishing() || screen.isDestroyed() || screen.isInPictureInPictureMode()) return;
            autoEnter(screen, true);
            Logger.printDebug(() -> "Picture-in-picture: settings closed, auto-enter back");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "settings closed", failure);
        }
    }

    /** Whether the settings show over [screen]. */
    private static synchronized boolean underSettings(Activity screen) {
        return screen != null && underSettings.get() == screen;
    }

    /**
     * The screen a reel's More sheet built in [context] may open the window on: the armed screen,
     * when the sheet is on it, with the switch on, the phone's feature, and no window open yet.
     * Null otherwise, so a sheet on another screen, such as a reel opened over the armed one, gets
     * no row that would float the wrong screen, and a reel the window doesn't take gets none either.
     */
    static Activity menuScreen(@Nullable Context context) {
        Activity screen;
        synchronized (PictureInPicture.class) {
            screen = unfit ? null : armedScreen.get();
        }
        if (screen == null || !on() || activityOf(context) != screen) return null;
        if (screen.isFinishing() || screen.isDestroyed() || screen.isInPictureInPictureMode()) return null;
        if (!screen.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return null;
        return screen;
    }

    /**
     * The screen the floating button belongs on: the armed screen, under the same conditions as the
     * More sheet's row, with the button's own switch on. Null otherwise.
     */
    @Nullable
    static Activity buttonScreen() {
        try {
            Activity armed;
            synchronized (PictureInPicture.class) {
                armed = armedScreen.get();
            }
            Activity screen = armed == null ? null : menuScreen(armed);
            return screen != null && Settings.PICTURE_IN_PICTURE_BUTTON.get() ? screen : null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "button", failure);
            return null;
        }
    }

    /** The activity [context] is, or wraps, or null. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 16; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * The More sheet row's tap: opens the window on [screen] now, if it's still the armed screen and
     * may. Params with nothing set keep the shape Facebook armed it with. True when it opened.
     */
    static boolean enterFromMenu(@Nullable Activity screen) {
        try {
            if (screen == null || menuScreen(screen) == null) return false;
            boolean entered = screen.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            HookStatus.bound(FAMILY, "More menu row");
            if (entered) HookStatus.counted(FAMILY, FROM_MENU);
            Logger.printDebug(() -> "Picture-in-picture: the More menu row " + (entered ? "opened" : "didn't open") + " the window");
            return entered;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "More menu row", failure);
            return false;
        }
    }

    /**
     * The floating button's tap: opens the window on [screen] now, with the same plain params as the
     * More sheet's row, if the button still belongs there. True when it opened.
     */
    static boolean enterFromButton(@Nullable Activity screen) {
        try {
            if (screen == null || buttonScreen() != screen) return false;
            boolean entered = screen.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            HookStatus.bound(FAMILY, "button");
            if (entered) HookStatus.counted(FAMILY, FROM_BUTTON);
            Logger.printDebug(() -> "Picture-in-picture: the button " + (entered ? "opened" : "didn't open") + " the window");
            return entered;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "button", failure);
            return false;
        }
    }

    /**
     * Puts the page watch on [application] once. The first arming comes long after Facebook's
     * application set itself up, which its override of the registration needs.
     */
    private static synchronized void watch(Application application) {
        if (application == null || watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new Arrivals());
        watching = new WeakReference<>(application);
    }

    /** Hands each Facebook activity as it's created to {@link #arrived}. */
    static final class Arrivals implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
            arrived(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            screenBack(activity, SystemClock.uptimeMillis());
            cameBack(activity);
            PictureInPictureButton.sync();
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (activity == null || activity != armedScreen()) return;
            screenAway(activity);
            Utils.runOnMainThreadDelayed(() -> floats(activity), FLOAT_CHECK_MS);
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /**
     * [activity] was just created. While the window floats with the armed screen in it, that screen's
     * task is the one Facebook's activities join, so a page created in it opened inside the window.
     * Android brings a floating task back full screen only for a start it counts as starting the app
     * again, which a new page in the task isn't and the launcher's own start is (the icon tap that
     * already brought it back on the S22). So Facebook is started that way a moment later, once the
     * page is up, and the window comes back full screen with the page on top. Facebook's own answer,
     * closing the window's screen when another activity resumes, is for its separate Reels viewer:
     * nothing in a floating task resumes, and here the screen is Facebook's main one.
     */
    static void arrived(Activity activity) {
        try {
            if (activity == null || !bringingBack(activity, SystemClock.uptimeMillis())) return;
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            String page = activity.getClass().getSimpleName();
            Utils.runOnMainThread(() -> bringBack(activity, launch, page));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "page in the window", failure);
        }
    }

    /**
     * Whether [activity] opened in the armed screen's task while that screen floats in the window,
     * with the switch on, Facebook not brought back in the last {@link #BRING_BACK_GAP_MS} and no start
     * sent to a task of its own in the last {@link #SENT_AWAY_GAP_MS}, as of [now]. Answering yes
     * starts that gap.
     */
    static synchronized boolean bringingBack(Activity activity, long now) {
        Activity screen = armedScreen.get();
        if (screen == null || screen == activity) return false;
        if (!screen.isInPictureInPictureMode() || screen.getTaskId() != activity.getTaskId() || !on()) return false;
        if (now - sentAwayAt < SENT_AWAY_GAP_MS || now - broughtBackAt < BRING_BACK_GAP_MS) return false;
        broughtBackAt = now;
        return true;
    }

    private static synchronized Activity armedScreen() {
        return armedScreen.get();
    }

    /** A start while a reel floats got a task of its own at [now]. */
    static synchronized void sentAway(long now) {
        sentAwayAt = now;
    }

    /** [activity] paused a moment ago. If it's the armed screen and it floats in the window now, that's noted. */
    static synchronized void floats(Activity activity) {
        try {
            if (activity == armedScreen.get() && activity.isInPictureInPictureMode()) floating = new WeakReference<>(activity);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "window watch", failure);
        }
    }

    /**
     * [activity] resumed. If it's the screen that floated in the window, it was just opened full
     * screen again, and Facebook gets {@link #REARM_AFTER_WINDOW_MS} to arm the window for it.
     */
    static void cameBack(Activity activity) {
        int seen;
        synchronized (PictureInPicture.class) {
            if (activity == null || activity != floating.get()) return;
            floating = new WeakReference<>(null);
            seen = armings;
        }
        Utils.runOnMainThreadDelayed(() -> armAfterWindow(activity, seen), REARM_AFTER_WINDOW_MS);
    }

    /**
     * Facebook mostly arms the window again once it's opened full screen, but not every time, and
     * then its last arming stays on, for a reel that may not be the one on screen anymore (#126).
     * Unless Facebook armed or disarmed since [seen],
     * the window is armed again for the reel on screen, as a swipe arms it, or kept closed for a
     * reel it doesn't take. A pause holding auto-enter still holds it.
     */
    static void armAfterWindow(Activity activity, int seen) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            Object[] arguments;
            Object player;
            boolean wasHeld;
            synchronized (PictureInPicture.class) {
                if (armings != seen || activity != armedScreen.get() || !on()) return;
                arguments = arming;
                player = current.get();
                wasHeld = held;
            }
            if (arguments == null || player == null || activity.isFinishing() || activity.isDestroyed()
                    || activity.isInPictureInPictureMode()) return;
            if (!rearm(arguments, player)) {
                keepOut(activity, player);
                return;
            }
            if (wasHeld && holding(player) != null) autoEnter(activity, false);
            HookStatus.bound(FAMILY, "arming after the window");
            HookStatus.counted(FAMILY, REARMED_AFTER_WINDOW);
            Logger.printDebug(() -> "Picture-in-picture: armed again after the window closed, for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming after the window", failure);
        }
    }

    /** Starts Facebook from [activity] with its own launcher [launch], counting it under [page]. */
    private static void bringBack(Activity activity, Intent launch, String page) {
        try {
            activity.startActivity(launch);
            HookStatus.bound(FAMILY, "page in the window");
            HookStatus.counted(FAMILY, BROUGHT_BACK);
            Logger.printDebug(() -> "Picture-in-picture: " + page + " opened in the window, so Facebook was brought back full screen");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "page in the window", failure);
        }
    }

    /**
     * The hook, first thing in Facebook's launch step (583 `LX/0Oe;->A01`), which every start of a
     * Facebook screen passes with the [context] it starts from and its [intent], before Facebook's
     * interceptors copy it. While a reel floats from a screen other than Facebook's main one, two
     * kinds of start would end in the window's task, so each gets FLAG_ACTIVITY_NEW_TASK and
     * FLAG_ACTIVITY_MULTIPLE_TASK and opens full screen in a task of its own, with the reel still
     * floating (#126):
     * - a start of the floating screen's own class that looks for a task. The window's task has that
     *   screen at its root, and Android counts an intent that differs only in its extras as the same
     *   one, so it brought the floating reel forward and dropped the page.
     * - the first start of a task stack Facebook builds with its main screen under a page, which
     *   clears the task it finds and drew the main screen in the window. The page's own start after
     *   it is left alone and follows it into its new task.
     * A floating main screen, the Reels tab's window, is left to {@link #arrived}.
     */
    public static void ownTask(@Nullable Context context, @Nullable Intent intent) {
        try {
            if (intent == null || followsItsStack(intent)) return;
            Activity screen = armedScreen();
            if (screen == null || !screen.isInPictureInPictureMode() || screen.isFinishing() || !on()) return;
            String floats = screen.getComponentName().getClassName();
            ComponentName target = intent.getComponent();
            if (FacebookTabs.MAIN_TAB_ACTIVITY.equals(floats) || target == null
                    || !screen.getPackageName().equals(target.getPackageName())) return;
            boolean stackFirst = (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0
                    && intent.getBooleanExtra(UNDER_A_PAGE, false);
            if (!stackFirst && !(floats.equals(target.getClassName()) && looksForTask(context, intent, screen))) return;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            sentAway(SystemClock.uptimeMillis());
            if (stackFirst) stackGoesBy();
            HookStatus.bound(FAMILY, "start while floating");
            HookStatus.counted(FAMILY, OWN_TASK);
            Logger.printDebug(() -> "Picture-in-picture: " + target.getShortClassName() + " started in a task of its own while a reel floats");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "start while floating", failure);
        }
    }

    /**
     * Whether Android looks for a task to start [intent] from [context] in: it asks for a new one, or
     * comes from no activity (Facebook adds NEW_TASK after its launch step then), from a
     * single-instance one like Facebook's link handler, or from inside the floating [screen]'s task.
     */
    private static boolean looksForTask(@Nullable Context context, Intent intent, Activity screen) throws PackageManager.NameNotFoundException {
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) return true;
        Activity source = activityOf(context);
        if (source == null || source.getTaskId() == screen.getTaskId()) return true;
        return source.getPackageManager().getActivityInfo(source.getComponentName(), 0).launchMode
                == ActivityInfo.LAUNCH_SINGLE_INSTANCE;
    }

    /**
     * Whether [intent] is a later start of a task stack whose first start got a task of its own just
     * now on this thread: it asks for no new task, so it goes on top of that one.
     */
    private static boolean followsItsStack(Intent intent) {
        if (stackGoingBy.get() == null) return false;
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) return true;
        stackGoingBy.remove();
        return false;
    }

    /** Marks a task stack going by on this thread until its next message, when there is a looper to end it. */
    private static void stackGoesBy() {
        Looper looper = Looper.myLooper();
        if (looper == null) return;
        stackGoingBy.set(Boolean.TRUE);
        new Handler(looper).post(stackGoingBy::remove);
    }

    /** The hook, first thing in each of ReelsPipUtil's disarms. Nothing is armed after it. */
    public static void disarmed() {
        try {
            arm(null, null, null);
            PictureInPictureButton.sync();
            Logger.printDebug(() -> "Picture-in-picture: disarmed");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "disarm", failure);
        }
    }

    private static synchronized void screenAway(Activity activity) {
        away = new WeakReference<>(activity);
    }

    private static synchronized void screenBack(Activity activity, long now) {
        if (activity == null || activity != away.get()) return;
        away = new WeakReference<>(null);
        backAt = now;
    }

    /**
     * The hook, first in the seek every seek of FbGrootPlayer goes through. Facebook puts a reel back
     * at its start as its screen comes back, from the window or from another app, with Hushfacebook
     * paused too, so it started over every time (#44). Answering yes drops that seek and the armed
     * reel goes on from where it was. Only a seek to 0 by one of {@link #REWINDS} counts, while the
     * screen is away or within {@link #RETURN_MS} of its return, so a swipe still rewinds the reel it
     * leaves and a drag to the start is left alone. A reel that just ended ({@link #playerEnded})
     * goes back to its start by the same seek to loop, and dropping that one froze it on its last
     * frame, so it stays.
     */
    public static boolean keepPlace(Object trigger, Object player, int positionMs) {
        try {
            long now = SystemClock.uptimeMillis();
            if (positionMs != 0 || player == null || !comingBack(player, now)) return false;
            String name = trigger instanceof Enum ? ((Enum<?>) trigger).name() : null;
            if (!REWINDS.contains(name)) {
                Logger.printDebug(() -> "Picture-in-picture: a seek to the start by " + name + " left alone as the screen came back");
                return false;
            }
            if (justEnded(player, now)) {
                HookStatus.counted(FAMILY, LOOP_LEFT);
                Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " ended, left to loop, " + name);
                return false;
            }
            HookStatus.counted(FAMILY, PLACE_KEPT);
            Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " kept its place, " + name);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "seek as the screen came back", failure);
            return false;
        }
    }

    /** Whether [player] is the armed reel, with the switch on, and its screen is away or came back just before [now]. */
    private static synchronized boolean comingBack(Object player, long now) {
        Activity screen = armedScreen.get();
        if (screen == null || player != current.get() || !on()) return false;
        return screen == away.get() || now - backAt < RETURN_MS;
    }

    /** The hook, right after Facebook's end-of-video step reads its player. */
    public static void playerEnded(Object player) {
        try {
            if (player != null) noteEnded(player, SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "video end", failure);
        }
    }

    private static synchronized void noteEnded(Object player, long now) {
        ended = new WeakReference<>(player);
        endedAt = now;
    }

    /** Whether [player]'s video reached its end just before [now]. */
    private static synchronized boolean justEnded(Object player, long now) {
        return player == ended.get() && now - endedAt < LOOP_MS;
    }

    /** Facebook just set auto-enter itself, on or off, so nothing is held any more. */
    private static synchronized void arm(Activity activity, Object player, Object[] arguments) {
        armedScreen = new WeakReference<>(activity);
        current = new WeakReference<>(player);
        arming = arguments;
        held = false;
        unfit = false;
        armings++;
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
     * armed again for it. Unless that other player's video is off screen: Facebook getting the next
     * reel ready plays it for a moment, and the window then opened on that reel (#126). Or unless it
     * plays on another screen, such as Home beside a floating viewer ({@link #onAnotherScreen}).
     */
    public static void playerPlaying(Object player) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || player == null) return;
            if (anotherReel(player) && onAnotherScreen(player)) {
                HookStatus.counted(FAMILY, OTHER_SCREEN);
                Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " plays on another screen, left out");
                return;
            }
            if (anotherReel(player) && offScreen(player)) {
                HookStatus.counted(FAMILY, OFF_SCREEN);
                Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " plays off screen, left out");
                return;
            }
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

    /** Whether the window is armed and [player] isn't the reel on screen. */
    private static synchronized boolean anotherReel(Object player) {
        return armedScreen.get() != null && player != current.get();
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

    /** The armed screen when a pause holds auto-enter, released, unless the reel on screen is one the window doesn't take. */
    private static synchronized Activity releasing() {
        if (!held) return null;
        held = false;
        return unfit ? null : armedScreen.get();
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
            if (!rearm(arguments, player)) {
                keepOut(screen, player);
                return;
            }
            HookStatus.bound(FAMILY, "arming again");
            HookStatus.counted(FAMILY, REARMED);
            Logger.printDebug(() -> "Picture-in-picture: armed again for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming again", failure);
        }
    }

    /**
     * Filled in by the patch: arms the window again through ReelsPipUtil with the [arguments] of its
     * last arming, for [player], that player's own video and the shape {@link #shape} gives for it.
     * False when that reel has a shape the window doesn't take, and until the patch fills it in.
     */
    public static boolean rearm(Object[] arguments, Object player) {
        return false;
    }

    /**
     * The window's shape for a reel whose video is [aspect] wide to one high, worked out as Facebook
     * works it out when it arms the window for a reel: in thousandths, from {@link #NARROWEST} up to
     * {@link #WIDEST}, and 9 by 16 for a video of no known shape. Null for a shape Facebook arms no
     * window for, such as a landscape reel's.
     */
    @Nullable
    public static Rational shape(double aspect) {
        try {
            if (!(aspect > 0)) return new Rational(9, 16);
            int thousandths = (int) ((float) aspect * 1000f);
            float ratio = thousandths / 1000f;
            Logger.printDebug(() -> "Picture-in-picture: the next reel is " + aspect + " wide to one high");
            if (thousandths <= 0 || ratio < NARROWEST || ratio > WIDEST) return null;
            return new Rational(thousandths, 1000);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel shape", failure);
            return null;
        }
    }

    /**
     * [player], the reel on screen, has a shape the window doesn't take, so it isn't armed again:
     * auto-enter goes off, leaving it out of the window as Facebook's own arming does, until a reel
     * the window takes plays and is armed. Nothing changes once another reel started, or while the
     * window is open.
     */
    private static void keepOut(Activity screen, Object player) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        synchronized (PictureInPicture.class) {
            if (player != current.get()) return;
            unfit = true;
            held = false;
        }
        PictureInPictureButton.sync();
        if (screen.isInPictureInPictureMode()) return;
        autoEnter(screen, false);
        HookStatus.bound(FAMILY, "arming again");
        HookStatus.counted(FAMILY, KEPT_OUT);
        Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " kept out of the window");
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
            // Only from the More menu, Facebook's change is set aside armed or not: it may only turn auto-enter on.
            if (!on() || !(tracking() || menuOnly())) return false;
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

    /**
     * The hook, in the opening of the window's viewer, as it hides each view of the screen but its
     * own container: keeps how [view] showed, for {@link #shownAgain} on the way back. The viewer
     * can open twice on the way into the window, so a hidden view keeps what was kept for it.
     */
    public static void hiding(View view) {
        try {
            if (view == null || !on()) return;
            HookStatus.bound(FAMILY, "screen hidden");
            int now = view.getVisibility();
            if (now != View.GONE || !beforeWindow.containsKey(view)) beforeWindow.put(view, now);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "screen hidden", failure);
        }
    }

    /**
     * The hook, in place of Facebook's call that shows each view of the screen again on the way
     * back from the window, with the [visibility] Facebook asks for: shown, whatever the view was
     * before. A view the viewer hid goes back to how it showed before instead. Among them are the
     * spots for views Facebook builds only once they're shown (ViewStubs), and showing one builds
     * it: the reel viewer got Facebook Flex's switcher bar, a purple button over the top right of
     * the reel, and four more views it never shows (#126). A spot hidden before stays hidden, and so
     * does a spot the viewer didn't hide.
     */
    public static void shownAgain(View view, int visibility) {
        int shown = visibility;
        try {
            if (view != null && on()) {
                HookStatus.bound(FAMILY, "screen shown again");
                Integer before = beforeWindow.remove(view);
                int kept = before != null ? before : view instanceof ViewStub ? view.getVisibility() : visibility;
                if (kept != visibility) {
                    HookStatus.counted(FAMILY, AS_BEFORE);
                    shown = kept;
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "screen shown again", failure);
        }
        view.setVisibility(shown);
    }

    /** Whether a window is armed with a reel on screen. */
    private static synchronized boolean tracking() {
        return armedScreen.get() != null && current.get() != null;
    }

    /**
     * Params with only auto-enter set change only auto-enter, as Facebook's own disarm relies on.
     * While the window opens only from the More menu, or the settings show over the screen, it's
     * always off.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static void autoEnter(Activity screen, boolean enabled) {
        boolean answer = enabled && !menuOnly() && !underSettings(screen);
        screen.setPictureInPictureParams(new PictureInPictureParams.Builder().setAutoEnterEnabled(answer).build());
    }

    /** Whether the switch is on, on Android 12 or later: Facebook's own code needs its auto-enter. */
    private static boolean on() {
        HookStatus.invoked(FAMILY);
        return Utils.settingsReady() && Settings.PICTURE_IN_PICTURE.get() && apiLevel >= Build.VERSION_CODES.S;
    }

    /** Whether the switch is on and the window opens only from the More menu. */
    static boolean menuOnly() {
        return on() && Settings.PICTURE_IN_PICTURE_FROM_MENU.get();
    }

    /**
     * Forgets what was armed, when a page last brought Facebook back or a start last got a task of its
     * own, and a task stack going by. Tests only.
     */
    static void forget() {
        arm(null, null, null);
        stackGoingBy.remove();
        synchronized (PictureInPicture.class) {
            broughtBackAt = -BRING_BACK_GAP_MS;
            sentAwayAt = -SENT_AWAY_GAP_MS;
            floating = new WeakReference<>(null);
            underSettings = new WeakReference<>(null);
            away = new WeakReference<>(null);
            backAt = -RETURN_MS;
            ended = new WeakReference<>(null);
            endedAt = -LOOP_MS;
        }
    }
}
