/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.facebook.reels.PostTextSelect;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Playback speed in the post menu: a feed or Watch video's 3-dot menu gets a Playback speed row,
 * which opens a list of speeds from 0.5x to 3x for that video, a live one too (#429).
 *
 * <p>Download any video's menu call adds the row ({@code VideoMenuItem}), and Keep the reel speed's
 * player hooks do the rest: {@link ReelSpeed#started} hands each started player here, which
 * remembers the last few by the video they play, and a pick goes on through the same FbGrootPlayer
 * setter the gear menu uses. The menu names its video by id, so the pick goes to the player
 * playing that video; when the menu names none, or the build's params report no id, it goes to the
 * player that started last. A pick for a video no player plays yet waits for its start. A pick
 * then counts as a gear menu pick, so Keep the reel speed and Keep the video speed keep it as they
 * would one from the gear menu. Off, paused, before the settings are ready, or anything here
 * failing, the menu is Facebook's and every video plays at Facebook's speed.
 */
public final class PostMenuSpeed {
    /** The speeds the row offers, slowest first. HeroManager plays from 0.25x to 4x. */
    static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f};

    /** Counted under the patch's name each time a post menu gets the row. */
    static final String ROW_ADDED = "post menu offered Playback speed";

    /** Counted under the patch's name each time a speed picked in the post menu goes on a video. */
    static final String APPLIED = "post menu speed set on a video";

    /** How many started videos the row can find a player for, the newest kept. */
    static final int REMEMBERED = 8;

    private static final String FAMILY = FamilyNames.KEEP_REEL_SPEED;

    private static final Object LOCK = new Object();

    /** The player that last started each video, by the video's id, oldest first. */
    private static final LinkedHashMap<String, WeakReference<Object>> PLAYERS = new LinkedHashMap<>();

    /** The player that last started any video, for a menu that names none. */
    @Nullable
    private static WeakReference<Object> lastPlayer;

    /** Whether a started video's params reported an id, so the row can tell players apart by it. */
    private static boolean idsRead;

    /** A video picked for before any player started it, and its speed. */
    @Nullable
    private static String waitingVideo;
    private static float waitingSpeed = ReelSpeed.NORMAL;

    private PostMenuSpeed() {
    }

    /** Whether the switch is on, not paused, and the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.POST_MENU_SPEED.get();
    }

    /** Whether a post menu gets the row: the switch on. Never throws. */
    public static boolean offersRow() {
        try {
            return on();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post menu speed row", failure);
            return false;
        }
    }

    /** The row's title. */
    public static String rowTitle() {
        HookStatus.counted(FAMILY, ROW_ADDED);
        return L10n.t("Playback speed");
    }

    /**
     * A tap on the row of a menu for [videoId], null when the menu names none: the list of speeds,
     * the one the video plays at checked, in Facebook's dark or light look. Never throws: it runs
     * inside Facebook's menu dispatch.
     */
    public static void open(@Nullable Context context, @Nullable String videoId) {
        try {
            if (!on()) return;
            HookStatus.invoked(FAMILY);
            Activity activity = activityOf(context);
            if (activity == null || activity.isFinishing()) {
                Logger.printDebug(() -> "Post menu speed: no screen to show the speeds on");
                return;
            }
            Object player = playerOf(videoId);
            float now = player == null ? waitingFor(videoId) : ReelSpeed.access.currentSpeed(player);
            String[] labels = labels();
            AlertDialog dialog = new AlertDialog.Builder(activity, PostTextSelect.dark(activity)
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle(L10n.t(activity, "Playback speed"))
                    .setSingleChoiceItems(labels, checked(now), (box, which) -> {
                        box.dismiss();
                        choose(activity, videoId, SPEEDS[which]);
                    })
                    .create();
            dialog.show();
            ShimmerTitle.wearTitleOf(dialog);
            HookStatus.bound(FAMILY, "post menu speed");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post menu speed", failure);
        }
    }

    /**
     * [speed] picked for [videoId]: set on the player playing it now, or kept until that video
     * starts when none does. A toast names the speed. Never throws.
     */
    static void choose(@Nullable Context context, @Nullable String videoId, float speed) {
        try {
            if (!on()) return;
            Object player = playerOf(videoId);
            if (player != null) {
                apply(player, speed);
                Logger.printDebug(() -> "Post menu speed: set " + speed + "x on the video playing");
            } else if (videoId != null && !videoId.isEmpty()) {
                synchronized (LOCK) {
                    waitingVideo = videoId;
                    waitingSpeed = speed;
                }
                Logger.printDebug(() -> "Post menu speed: " + speed + "x waits for the video to start");
            } else {
                Logger.printDebug(() -> "Post menu speed: " + speed + "x picked, but no video has started");
                return;
            }
            Utils.showToastShort(L10n.t(context, "Playback speed") + ": " + label(speed));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "post menu speed pick", failure);
        }
    }

    /**
     * Called by {@link ReelSpeed#started} for each start of [player] while the switch is on: it's
     * remembered by the video it plays, and a speed waiting for that video goes on. True when one did.
     */
    static boolean started(Object player) {
        Object params = ReelSpeed.access.params(player);
        String id = params == null ? null : ReelSpeed.access.videoId(params);
        Float speed = null;
        synchronized (LOCK) {
            lastPlayer = new WeakReference<>(player);
            if (id == null || id.isEmpty()) return false;
            idsRead = true;
            PLAYERS.remove(id);
            PLAYERS.put(id, new WeakReference<>(player));
            Iterator<Map.Entry<String, WeakReference<Object>>> oldest = PLAYERS.entrySet().iterator();
            while (PLAYERS.size() > REMEMBERED && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
            if (id.equals(waitingVideo)) {
                speed = waitingSpeed;
                waitingVideo = null;
            }
        }
        if (speed == null) return false;
        float waited = speed;
        apply(player, waited);
        Logger.printDebug(() -> "Post menu speed: the video picked for started, set to " + waited + "x");
        return true;
    }

    /** Sets [speed] on [player], then hands it over like a gear menu pick for the Keep switches. */
    private static void apply(Object player, float speed) {
        ReelSpeed.access.setSpeed(player, speed);
        HookStatus.counted(FAMILY, APPLIED);
        ReelSpeed.menuPicked(speed);
    }

    /**
     * The player playing [videoId] now, or the one that started last when the menu names no video
     * or no started video's params reported an id. Null when none does.
     */
    @Nullable
    static Object playerOf(@Nullable String videoId) {
        WeakReference<Object> found;
        synchronized (LOCK) {
            found = videoId == null || videoId.isEmpty() || !idsRead ? lastPlayer : PLAYERS.get(videoId);
        }
        Object player = found == null ? null : found.get();
        if (player == null || videoId == null || videoId.isEmpty() || !idsRead) return player;
        // The player may have moved on to another video since.
        Object params = ReelSpeed.access.params(player);
        return params != null && videoId.equals(ReelSpeed.access.videoId(params)) ? player : null;
    }

    /** The speed waiting for [videoId], or normal. */
    private static float waitingFor(@Nullable String videoId) {
        synchronized (LOCK) {
            return videoId != null && videoId.equals(waitingVideo) ? waitingSpeed : ReelSpeed.NORMAL;
        }
    }

    /** The row of {@link #SPEEDS} [speed] is, or -1 when it's none of them. */
    static int checked(float speed) {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < ReelSpeed.SAME) return i;
        }
        return -1;
    }

    /** {@link #SPEEDS} written as the list shows them: 0.5x, 1x, 2.5x. */
    static String[] labels() {
        String[] labels = new String[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) labels[i] = label(SPEEDS[i]);
        return labels;
    }

    private static String label(float speed) {
        return ReelSpeed.names(new float[] {speed}, 0, 1)[0] + "x";
    }

    /** The activity a view's context wraps, or null. The depth guards against a wrapper that wraps itself. */
    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 20; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Forgets every player and waiting speed, as a new Facebook process would. For tests. */
    static void forget() {
        synchronized (LOCK) {
            PLAYERS.clear();
            lastPlayer = null;
            idsRead = false;
            waitingVideo = null;
            waitingSpeed = ReelSpeed.NORMAL;
        }
    }
}
