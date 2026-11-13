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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *       uses. It stops counting when Facebook starts it again on its own after a pause.</li>
 *   <li>When that handler is asked ({@link #deciding}), it goes on only for the video whose pause
 *       Facebook just recorded, when that pause found it playing, came no more than
 *       {@link #LEAVE_WINDOW_MS} before Facebook asked, and didn't follow a tap within
 *       {@link #TAPPED_PAUSE_MS} (your own pause), and the screen that stopped wasn't closing or being
 *       rebuilt. Its checks of the server flag, the sound setting, eligible content, the trigger and
 *       the fullscreen player type answer yes then ({@link #skipCheck}), and a missing title gets a
 *       plain one ({@link #title}). Every other video gets a no at once.</li>
 *   <li>The notification's own server flag reads on while that one video continues
 *       ({@link #notificationAllowed}), until the handler's return to Facebook has hidden it.</li>
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

    // Hooks.

    /** The hook, first thing in FbGrootPlayer's play. */
    public static void started(Object player, Object trigger) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            throwIfAsked();
            if (!on()) return;
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, "player start");
            if (start(player, name(trigger), SystemClock.uptimeMillis())) ensureManager();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player start", failure);
        }
    }

    /** The hook, first thing in FbGrootPlayer's inner pause, before the player stops. */
    public static void pausing(Object player, Object trigger) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            throwIfAsked();
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
            String answer = handledRecently(videoId, now) ? TAKEN : judge(videoId, name(trigger), now);
            windowLeftAt = 0;
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, answer);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Background play: " + answer);
            if (!CONTINUED.equals(answer)) return true;
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
        if (returning != null && returning == handler && session) ending = true;
    }

    /** Before each of the return's own returns, once its player and notification are gone. */
    public static void returned() {
        if (!ending) return;
        ending = false;
        session = false;
        handledVideo = null;
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
        boolean yours = sinceTap >= 0 && sinceTap <= TAP_WINDOW_MS && !TapToPlay.visibilityDriven(trigger);
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
        handler = null;
        manager = null;
        MANAGER_ASKED.set(false);
        reader = PATCHED;
        failNext = null;
    }

    /** Takes [made] as the extension's handler, as if the manager had been made. For tests. */
    static void useHandler(Object made) {
        handler = made;
        manager = new Object();
        MANAGER_ASKED.set(true);
    }
}
