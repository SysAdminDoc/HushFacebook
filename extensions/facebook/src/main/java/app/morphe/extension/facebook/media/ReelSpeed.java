/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keep the reel speed: the playback speed picked in a reel's menu stays for the next reels.
 *
 * <p>The Reels menu offers its speeds in two pickers, and a pick in either sets the speed on the
 * reel's own FbGrootPlayer and shows Facebook's speed toast a moment later; nothing else shows that
 * toast. Some accounts get the gear menu's speed sheet in a reel's More menu instead, which sets
 * the speed the same way and shows no toast; Watch videos have that sheet in their gear menu. Facebook
 * remembers a speed per video, so the next reel's player starts at normal speed. The patch hands this
 * class every speed set on a player ({@link #speedSet}), the toast's speed ({@link #picked}), the
 * gear sheet's ({@link #gearPicked}), which is kept only when it's picked on a reel, and each start
 * of playback ({@link #started}). When the gear sheet can't find its player, it asks
 * {@link #gearSheetSpeed} which speed to check.
 *
 * <p>A picked speed is kept for the viewer the reel was playing in, its PlayerOrigin's origin:
 * fb_shorts_viewer for the Reels viewer, video_home where an account's Reels live in the Video
 * tab, fb_shorts_native_in_feed_unit for reels in the feed, and so on. A pick in one viewer never
 * changes another's. When a player of that viewer starts a video it hasn't started since the pick,
 * which it tells by the VideoPlayerParams object each video brings, the speed goes on if the video
 * is a reel (isFbShorts) that isn't an ad (isSponsored) or live (isLiveNow): Facebook's menu may
 * not offer a speed on an ad, a live video sped up runs into its live edge, and other videos keep
 * Facebook's speed. A reel that started before the pick, such as the next one Facebook readies in
 * advance, gets it when it starts again. A reel paused and started again after that, a reel held at
 * 2x and anything else set on the reel you're watching stay as they are until the next reel.
 * Picking normal speed goes back to Facebook's reset for that viewer. The speeds are saved in a
 * preferences file of their own ({@link #FILE}), so they last through a restart of Facebook or the
 * phone (#199) and never reach the settings export or the diagnostic report. Nothing is kept or
 * applied while the switch is off, Hushfacebook is paused, the settings aren't ready, or anything
 * here fails.
 *
 * <p>Keep the video speed, the patch's second switch, does the same for videos that aren't reels: a
 * speed picked in a feed or Watch video's gear menu is kept once for every viewer, and each next
 * video that isn't a reel, an ad or live starts at it, while reels keep the speed kept for them. The
 * speed goes on through the same FbGrootPlayer setter Facebook's own gear menu uses. Stories, chats
 * and the composer's previews keep Facebook's speed, so a pick there isn't kept either. With that
 * switch off, a gear pick on a video that isn't a reel stays with that video, as Facebook has it.
 *
 * <p>Slower speeds, the third switch, adds 0.1x and 0.25x to the speeds the Reels menu's two pickers
 * offer ({@link #speedChoices}) and to the gear menu's speed sheet ({@link #gearValues},
 * {@link #gearSpeeds} and {@link #gearLabels}). Faster speeds adds 2.5x and 3x after the fastest
 * speed the same pickers and sheet offer (#430). Playback speed in the post menu sets a speed from a
 * feed or Watch video's 3-dot menu through the same setter ({@link PostMenuSpeed}, #429).
 */
public final class ReelSpeed {
    static final float NORMAL = 1f;

    /** Two speeds this close are the same one, as Facebook's own speed toast and cache compare them. */
    static final float SAME = 0.01f;

    /** How long after a player's speed changed the toast still names that change. Facebook waits 150 ms. */
    static final long PICK_WINDOW_MS = 2000;

    /** Counted under the patch's name for each reel started at the kept speed. */
    static final String APPLIED = "reel started at the kept speed";

    /** Counted under the patch's name for each feed or Watch video started at the kept video speed. */
    static final String VIDEO_APPLIED = "video started at the kept speed";

    /** The speeds Slower speeds offers, slowest first. ExoPlayer plays down to 0.1x. */
    static final float[] SLOWER = {0.1f, 0.25f};

    /** Counted under the patch's name each time a Reels speed picker offers the slower speeds. */
    static final String SLOWER_OFFERED = "Reels speed menu offered slower speeds";

    /** Counted under the patch's name each time the gear menu's speed sheet offers the slower speeds. */
    static final String GEAR_SLOWER_OFFERED = "gear speed sheet offered slower speeds";

    /** The speeds Faster speeds offers, slowest first. HeroManager plays up to 4x. */
    static final float[] FASTER = {2.5f, 3f};

    /** Counted under the patch's name each time a Reels speed picker offers the faster speeds. */
    static final String FASTER_OFFERED = "Reels speed menu offered faster speeds";

    /** Counted under the patch's name each time the gear menu's speed sheet offers the faster speeds. */
    static final String GEAR_FASTER_OFFERED = "gear speed sheet offered faster speeds";

    /** The labels of the speeds {@link #gearSpeeds} just put ahead of the sheet's own, for {@link #gearLabels}. */
    private static final ThreadLocal<String[]> GEAR_ADDED = new ThreadLocal<>();

    /** The labels of the speeds {@link #gearSpeeds} just put after the sheet's own, set with {@link #GEAR_ADDED}. */
    private static final ThreadLocal<String[]> GEAR_APPENDED = new ThreadLocal<>();

    /** A speed label with a decimal in it: what comes before the number, the decimal mark, and what comes after. */
    private static final Pattern DECIMAL_LABEL = Pattern.compile("([^0-9]*)[0-9]+([.,])[0-9]+([^0-9]*)");

    /**
     * What a player's origin holds when it plays somewhere a kept video speed doesn't belong: chats,
     * the composer and camera's previews, and the TV cast screen. Stories are told by how their
     * origin starts, so feed_story still counts as the feed.
     */
    private static final String[] NOT_VIDEO_VIEWERS = {
            "thread", "messenger", "message", "composer", "picker", "editor", "camera", "inspiration",
            "living_room",
    };

    private static final String FAMILY = FamilyNames.KEEP_REEL_SPEED;

    /** The preferences file the kept speeds are saved in, in Facebook's own preferences folder. Only this class writes it. */
    static final String FILE = "hushfacebook_kept_speeds";

    /** The key the video speed is saved under. A viewer's reel speed is saved under {@link #REEL_KEY} and its origin. */
    private static final String VIDEO_KEY = "video";
    private static final String REEL_KEY = "reel:";

    /** How many viewers' reel speeds are saved. Facebook has a short list of viewers; a pick past it is kept until a restart. */
    static final int MAX_SAVED = 32;

    /** The fastest speed read back from the file. Facebook's menus top out at 2x. */
    private static final float FASTEST = 4f;

    /** What this class reads from a player and does to it. {@link #PATCHED} is the patch's; tests stand in. */
    interface Player {
        /** Sets the player's speed through Facebook's own setter. */
        void setSpeed(Object player, float speed);

        /** The player's PlayerOrigin, or null when there's none or the patch didn't fill it in. */
        @Nullable
        Object origin(Object player);

        /** The player's VideoPlayerParams, one object per video it binds, or null before the first. */
        @Nullable
        Object params(Object player);

        /** Whether [params]' video is a reel, by its isFbShorts. */
        boolean reel(Object params);

        /** Whether [params]' video is an ad, by its isSponsored. */
        boolean ad(Object params);

        /** Whether [params]' video is live now, by its isLiveNow. */
        boolean live(Object params);

        /** [params]' video id, or null when the patch found none to fill in. */
        @Nullable
        String videoId(Object params);

        /** The speed the player plays at, by the getter the gear menu's speed sheet reads. */
        float currentSpeed(Object player);
    }

    static final Player PATCHED = new Player() {
        @Override
        public void setSpeed(Object player, float speed) {
            setPlayerSpeed(player, speed);
        }

        @Override
        public Object origin(Object player) {
            return playerOrigin(player);
        }

        @Override
        public Object params(Object player) {
            return playerParams(player);
        }

        @Override
        public boolean reel(Object params) {
            return fbShorts(params);
        }

        @Override
        public boolean ad(Object params) {
            return sponsored(params);
        }

        @Override
        public boolean live(Object params) {
            return liveNow(params);
        }

        @Override
        public String videoId(Object params) {
            return ReelSpeed.videoId(params);
        }

        @Override
        public float currentSpeed(Object player) {
            return playerSpeed(player);
        }
    };

    static volatile Player access = PATCHED;

    private static final Object LOCK = new Object();

    /** The speed kept for each viewer, by its origin. */
    private static final Map<String, Float> KEPT = new HashMap<>();

    /** The params each player last started with a speed kept for its viewer, weakly. Players don't override equals. */
    private static final Map<Object, WeakReference<Object>> HANDLED = new WeakHashMap<>();

    /** The speed kept for feed and Watch videos, the same in every viewer, or null for Facebook's. */
    @Nullable
    private static Float videoKept;

    @Nullable
    private static WeakReference<Object> lastSetPlayer;
    private static float lastSetSpeed = NORMAL;
    private static long lastSetAt = Long.MIN_VALUE;

    /** The player that last started playing, weakly: the reel on screen when its menu opens. */
    @Nullable
    private static WeakReference<Object> lastStartedPlayer;

    /** Whether the saved speeds have been read into {@link #KEPT} and {@link #videoKept}. */
    private static boolean loaded;

    /** The file the speeds are saved in, or null outside Facebook's main process or before a context. */
    @Nullable
    private static SharedPreferences saved;

    private ReelSpeed() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: FbGrootPlayer's speed setter. Only a player may be passed. */
    public static void setPlayerSpeed(Object player, float speed) {
    }

    /** Filled in by the patch: FbGrootPlayer's PlayerOrigin getter. Only a player may be passed. */
    @Nullable
    public static Object playerOrigin(Object player) {
        return null;
    }

    /** Filled in by the patch: FbGrootPlayer's VideoPlayerParams getter. Only a player may be passed. */
    @Nullable
    public static Object playerParams(Object player) {
        return null;
    }

    /** Filled in by the patch: the field VideoPlayerParams' debug dump reports as isFbShorts. Only params may be passed. */
    public static boolean fbShorts(Object params) {
        return false;
    }

    /** Filled in by the patch: the field the params' debug dump reports as isSponsored. Only params may be passed. */
    public static boolean sponsored(Object params) {
        return false;
    }

    /** Filled in by the patch: the field the params' debug dump reports as isLiveNow. Only params may be passed. */
    public static boolean liveNow(Object params) {
        return false;
    }

    /**
     * Filled in by the patch when the params' debug dump reports a videoId: that field. Null
     * otherwise. Only params may be passed.
     */
    @Nullable
    public static String videoId(Object params) {
        return null;
    }

    /** Filled in by the patch: the speed getter the gear menu's speed sheet reads. Only a player may be passed. */
    public static float playerSpeed(Object player) {
        return NORMAL;
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The hook, first thing in FbGrootPlayer's speed setter, whoever calls it. With debug logging on,
     * it logs the speed the player really gets, whatever its menu shows.
     */
    public static void speedSet(Object player, float speed) {
        try {
            HookStatus.invoked(FAMILY);
            if (player == null || !on()) return;
            synchronized (LOCK) {
                lastSetPlayer = new WeakReference<>(player);
                lastSetSpeed = speed;
                lastSetAt = SystemClock.uptimeMillis();
            }
            Logger.printDebug(() -> "Reel speed: a player in " + viewerForLog(player) + " set to " + speed + "x");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "speed set", failure);
        }
    }

    /**
     * The hook, first thing in the Reels menu's speed toast, which follows a pick. Keeps [speed] for
     * the viewer of the player the pick just set it on; normal speed forgets that viewer's speed.
     */
    public static void picked(float speed) {
        pick(speed, false, "speed picked");
    }

    /**
     * The hook straight after each speed the gear menu's sheet sets, which shows no toast. Kept like
     * a pick in the Reels menu when the player's video is a reel. A speed picked on any other video,
     * in a Watch video's gear menu for one, is kept for the next feed and Watch videos with Keep the
     * video speed on, and stays with that video with it off.
     */
    public static void gearPicked(float speed) {
        pick(speed, true, "gear speed picked");
    }

    /** A speed picked in a post's 3-dot menu ({@link PostMenuSpeed}), just set on its player: kept like a gear menu pick. */
    static void menuPicked(float speed) {
        pick(speed, true, "post menu speed picked");
    }

    /**
     * The hook where the gear menu's speed sheet couldn't find its player and takes [speed], normal
     * speed, as the one to check. It can't find a reel's player on some accounts, so a kept speed
     * showed as Normal, and the checked row can't be picked, which left no way to pick normal speed
     * and forget the kept one (#199). The speed of the player on screen comes back instead, so the
     * row it plays at is checked and Normal can be picked. Off, paused, no player started yet, or
     * anything here failing, [speed] comes back.
     */
    public static float gearSheetSpeed(float speed) {
        try {
            HookStatus.invoked(FAMILY);
            if (!same(speed, NORMAL) || !on()) return speed;
            Object onScreen;
            synchronized (LOCK) {
                onScreen = lastStartedPlayer == null ? null : lastStartedPlayer.get();
            }
            if (onScreen == null) return speed;
            float playing = access.currentSpeed(onScreen);
            if (Float.isNaN(playing) || playing <= 0 || same(playing, speed)) return speed;
            HookStatus.bound(FAMILY, "gear speed sheet");
            Logger.printDebug(() -> "Reel speed: the gear menu found no player, so it checks the "
                    + playing + "x the one on screen plays at");
            return playing;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gear speed sheet", failure);
            return speed;
        }
    }

    /**
     * The hook where each of the Reels menu's two speed pickers, the attribute selector and the
     * dropdown, has just made its list of speeds, which it builds its items from (#95). With
     * {@link Settings#SLOWER_REEL_SPEEDS} on, the list that comes back starts with the
     * {@link #SLOWER} speeds slower than any it offers, so 0.1x and 0.25x come before Facebook's
     * 0.5x. With {@link Settings#FASTER_REEL_SPEEDS} on, it ends with the {@link #FASTER} speeds
     * faster than any it offers (#430), so a list that stops at 2x gets 2.5x and 3x. A pick of one
     * goes through the toast and the speed setter like any other, so Keep the reel speed keeps it.
     * Off, paused, or anything here failing, Facebook's own list comes back.
     */
    public static List<?> speedChoices(List<?> speeds) {
        try {
            HookStatus.invoked(FAMILY);
            if (speeds == null || speeds.isEmpty() || !Utils.settingsReady()) return speeds;
            boolean slower = Settings.SLOWER_REEL_SPEEDS.get();
            boolean faster = Settings.FASTER_REEL_SPEEDS.get();
            if (!slower && !faster) return speeds;
            HookStatus.bound(FAMILY, "speed menu");
            float slowest = Float.MAX_VALUE;
            float fastest = 0;
            for (Object speed : speeds) {
                slowest = Math.min(slowest, ((Float) speed));
                fastest = Math.max(fastest, ((Float) speed));
            }
            List<Object> choices = new ArrayList<>(speeds.size() + SLOWER.length + FASTER.length);
            if (slower) {
                for (float speed : SLOWER) {
                    if (speed < slowest - SAME) choices.add(speed);
                }
            }
            int added = choices.size();
            choices.addAll(speeds);
            if (faster) {
                for (float speed : FASTER) {
                    if (speed > fastest + SAME) choices.add(speed);
                }
            }
            if (choices.size() == speeds.size()) return speeds;
            if (added > 0) HookStatus.counted(FAMILY, SLOWER_OFFERED);
            if (choices.size() > added + speeds.size()) HookStatus.counted(FAMILY, FASTER_OFFERED);
            return choices;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "speed menu", failure);
            return speeds;
        }
    }

    /**
     * The hook first in the gear menu's speed sheet builder, on its flag for reading each speed from
     * a float rather than parsing it back out of its label with the locale's NumberFormat (#95). With
     * {@link Settings#SLOWER_REEL_SPEEDS} or {@link Settings#FASTER_REEL_SPEEDS} on it answers true,
     * so {@link #gearSpeeds} gets the sheet's real speeds and a pick plays the speed it shows
     * whatever the locale. Facebook's own builders pass true on some paths, and the pick reads the
     * float whenever the flag is set. Off, paused or failing, Facebook's flag stands.
     */
    public static boolean gearValues(boolean values) {
        try {
            if (values || !Utils.settingsReady()) return values;
            return Settings.SLOWER_REEL_SPEEDS.get() || Settings.FASTER_REEL_SPEEDS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gear speed sheet", failure);
            return values;
        }
    }

    /**
     * The hook where the gear menu's speed sheet has its speeds and their labels and is about to
     * make one item from each pair: the speeds come here first. With the switch on, the array that
     * comes back starts with the {@link #SLOWER} speeds slower than any the sheet offers, and
     * {@link #gearLabels}, called straight after, puts a label in front of the labels for each.
     * With Faster speeds on, it ends with the {@link #FASTER} speeds faster than any the sheet
     * offers, labelled after the sheet's own. Speeds of zero mean the sheet still reads its labels,
     * so nothing is added. Off, paused or failing, Facebook's array comes back and so do its labels.
     */
    public static float[] gearSpeeds(float[] speeds) {
        GEAR_ADDED.remove();
        GEAR_APPENDED.remove();
        try {
            HookStatus.invoked(FAMILY);
            if (speeds == null || speeds.length == 0 || !Utils.settingsReady()) return speeds;
            boolean slower = Settings.SLOWER_REEL_SPEEDS.get();
            boolean faster = Settings.FASTER_REEL_SPEEDS.get();
            if (!slower && !faster) return speeds;
            float slowest = Float.MAX_VALUE;
            float fastest = 0;
            for (float speed : speeds) {
                slowest = Math.min(slowest, speed);
                fastest = Math.max(fastest, speed);
            }
            if (slowest <= 0) return speeds;
            HookStatus.bound(FAMILY, "gear speed sheet");
            int added = 0;
            // SLOWER runs slowest first, so the speeds below the sheet's slowest are its first ones.
            if (slower) {
                for (float speed : SLOWER) {
                    if (speed < slowest - SAME) added++;
                }
            }
            // FASTER runs slowest first too, so the speeds above the sheet's fastest are its last ones.
            int appended = 0;
            if (faster) {
                for (float speed : FASTER) {
                    if (speed > fastest + SAME) appended++;
                }
            }
            if (added == 0 && appended == 0) return speeds;
            float[] choices = new float[added + speeds.length + appended];
            System.arraycopy(SLOWER, 0, choices, 0, added);
            System.arraycopy(speeds, 0, choices, added, speeds.length);
            System.arraycopy(FASTER, FASTER.length - appended, choices, added + speeds.length, appended);
            // Labelled here, so all that's left for gearLabels is to join the arrays.
            GEAR_ADDED.set(names(SLOWER, 0, added));
            GEAR_APPENDED.set(names(FASTER, FASTER.length - appended, appended));
            if (added > 0) HookStatus.counted(FAMILY, GEAR_SLOWER_OFFERED);
            if (appended > 0) HookStatus.counted(FAMILY, GEAR_FASTER_OFFERED);
            return choices;
        } catch (Throwable failure) {
            GEAR_ADDED.remove();
            GEAR_APPENDED.remove();
            HookStatus.threw(FAMILY, "gear speed sheet", failure);
            return speeds;
        }
    }

    /** [count] of [speeds] from [from] written plainly, "0.25" or "3" with no ".0" on a whole speed. */
    static String[] names(float[] speeds, int from, int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            float speed = speeds[from + i];
            names[i] = speed == (int) speed ? String.valueOf((int) speed) : String.valueOf(speed);
        }
        return names;
    }

    /**
     * The labels for the speeds {@link #gearSpeeds} just added on this thread, ahead of the sheet's
     * own labels for the slower ones and after them for the faster ones, written like the first of
     * the sheet's with a decimal in it: German's "0,5x" makes "0,25x" and "3x", English's "0.5"
     * makes "0.25" and "3" ({@link #styled}). Facebook's labels come back unchanged when nothing was
     * added.
     */
    public static String[] gearLabels(String[] labels) {
        String[] added = GEAR_ADDED.get();
        String[] appended = GEAR_APPENDED.get();
        GEAR_ADDED.remove();
        GEAR_APPENDED.remove();
        if (added == null || appended == null || labels == null) return labels;
        try {
            String model = null;
            for (String label : labels) {
                if (label != null && DECIMAL_LABEL.matcher(label).matches()) {
                    model = label;
                    break;
                }
            }
            String[] choices = new String[added.length + labels.length + appended.length];
            for (int i = 0; i < added.length; i++) choices[i] = styled(added[i], model);
            System.arraycopy(labels, 0, choices, added.length, labels.length);
            for (int i = 0; i < appended.length; i++) {
                choices[added.length + labels.length + i] = styled(appended[i], model);
            }
            return choices;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gear speed sheet", failure);
            return labels;
        }
    }

    /**
     * [plain], a speed like "0.25", with the decimal mark and whatever goes around the number in
     * [model], a label of Facebook's like "0,5x". [plain] as it is with no model.
     */
    static String styled(String plain, @Nullable String model) {
        if (model == null) return plain;
        Matcher label = DECIMAL_LABEL.matcher(model);
        if (!label.matches()) return plain;
        return label.group(1) + plain.replace('.', label.group(2).charAt(0)) + label.group(3);
    }

    private static void pick(float speed, boolean gear, String where) {
        try {
            HookStatus.invoked(FAMILY);
            boolean reels = reelsOn();
            // Only the gear sheet is offered on videos that aren't reels; the toast is the Reels menu's.
            boolean videos = gear && videosOn();
            if (!reels && !videos) return;
            HookStatus.bound(FAMILY, where);
            Object player = pickedPlayer(speed, SystemClock.uptimeMillis());
            if (gear) {
                if (player == null) {
                    Logger.printDebug(() -> "Reel speed: " + speed + "x picked in the gear menu, but no player found that was just set to it, so it isn't kept");
                    return;
                }
                Object video = access.params(player);
                if (video == null) {
                    Logger.printDebug(() -> "Reel speed: " + speed + "x picked in the gear menu, but its player's video couldn't be read, so it isn't kept");
                    return;
                }
                if (!access.reel(video)) {
                    if (videos) {
                        keepForVideos(player, video, speed);
                    } else {
                        Logger.printDebug(() -> "Reel speed: " + speed + "x picked in the gear menu on a video that isn't a reel, it stays with that video");
                    }
                    return;
                }
                if (!reels) {
                    Logger.printDebug(() -> "Reel speed: " + speed + "x picked in the gear menu on a reel, Keep the reel speed is off, so it stays with that reel");
                    return;
                }
            }
            String origin = player == null ? null : originName(player);
            if (origin == null) {
                if (same(speed, NORMAL)) {
                    // Normal picked on a reel already at normal sets nothing, so no player names the
                    // viewer. The reel on screen is the one that started last: only its viewer forgets.
                    Object onScreen;
                    synchronized (LOCK) {
                        onScreen = lastStartedPlayer == null ? null : lastStartedPlayer.get();
                    }
                    String viewer = onScreen == null ? null : originName(onScreen);
                    if (viewer != null) {
                        synchronized (LOCK) {
                            loadSaved();
                            KEPT.remove(viewer);
                            save(REEL_KEY + viewer, null);
                        }
                    }
                    Logger.printDebug(() -> viewer != null
                            ? "Reel speed: normal speed picked, reels in " + viewer + " start as Facebook starts them"
                            : "Reel speed: normal speed picked, but no reel is known to be on screen; kept speeds stay");
                } else {
                    Logger.printDebug(() -> "Reel speed: " + speed + "x picked, but no reel player took it");
                }
                return;
            }
            Object params = access.params(player);
            synchronized (LOCK) {
                loadSaved();
                if (same(speed, NORMAL)) {
                    KEPT.remove(origin);
                    save(REEL_KEY + origin, null);
                } else {
                    KEPT.put(origin, speed);
                    save(REEL_KEY + origin, KEPT.size() <= MAX_SAVED ? speed : null);
                }
                // The reel picked on already plays at the speed.
                if (params != null) HANDLED.put(player, new WeakReference<>(params));
            }
            Logger.printDebug(() -> same(speed, NORMAL)
                    ? "Reel speed: normal speed picked, reels in " + origin + " start as Facebook starts them"
                    : "Reel speed: keeping " + speed + "x for " + origin);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, where, failure);
        }
    }

    /**
     * Keeps [speed], picked in the gear menu on [player]'s [params], a video that isn't a reel, for
     * the next feed and Watch videos. Normal speed forgets it. A pick on an ad, a live video or in a
     * viewer the speed doesn't go to stays with that video.
     */
    private static void keepForVideos(Object player, Object params, float speed) {
        String origin = originName(player);
        String skip = access.ad(params) ? "an ad" : access.live(params) ? "live"
                : !videoViewer(origin) ? "not a feed or Watch video" : null;
        if (skip != null) {
            Logger.printDebug(() -> "Video speed: " + speed + "x picked in the gear menu on a video in " + origin
                    + ", it's " + skip + ", so it stays with that video");
            return;
        }
        synchronized (LOCK) {
            loadSaved();
            videoKept = same(speed, NORMAL) ? null : speed;
            save(VIDEO_KEY, videoKept);
            // The video picked on already plays at the speed.
            HANDLED.put(player, new WeakReference<>(params));
        }
        Logger.printDebug(() -> same(speed, NORMAL)
                ? "Video speed: normal speed picked, feed and Watch videos start as Facebook starts them"
                : "Video speed: keeping " + speed + "x for feed and Watch videos, picked in " + origin);
    }

    /**
     * The hook, first thing in FbGrootPlayer's maybeTrackVideoStart, which runs once the player has
     * started playing. The first start of each video after a pick gets the kept speed: a reel gets
     * its viewer's, and a feed or Watch video gets the video speed. Neither goes on an ad or a live video.
     * With Playback speed in the post menu on, {@link PostMenuSpeed} hears of the start first, and a
     * speed it set for the video stands.
     */
    public static void started(Object player) {
        try {
            HookStatus.invoked(FAMILY);
            if (player == null) return;
            boolean reels = reelsOn();
            boolean videos = videosOn();
            boolean menu = PostMenuSpeed.on();
            if (!reels && !videos && !menu) return;
            String origin = originName(player);
            Float reelKept;
            Float videoSpeed;
            synchronized (LOCK) {
                loadSaved();
                lastStartedPlayer = new WeakReference<>(player);
                reelKept = !reels || origin == null ? null : KEPT.get(origin);
                videoSpeed = videos ? videoKept : null;
            }
            if (menu && PostMenuSpeed.started(player)) return;
            // Nothing kept yet: a later start of the same video can still get a pick.
            if (reelKept == null && videoSpeed == null) return;
            Object params = access.params(player);
            if (params == null) return;
            boolean reel = access.reel(params);
            Float kept = reel ? reelKept : videoSpeed;
            if (kept == null) {
                if (!reel) Logger.printDebug(() -> "Reel speed: a video in " + origin + " started at Facebook's speed, it's not a reel");
                return;
            }
            synchronized (LOCK) {
                WeakReference<Object> handled = HANDLED.get(player);
                if (handled != null && handled.get() == params) return;
                HANDLED.put(player, new WeakReference<>(params));
            }
            float speed = kept;
            String skip = access.ad(params) ? "an ad" : access.live(params) ? "live"
                    : !reel && !videoViewer(origin) ? "not a feed or Watch video" : null;
            if (skip != null) {
                Logger.printDebug(() -> (reel ? "Reel speed: a video in " : "Video speed: a video in ") + origin
                        + " started at Facebook's speed, it's " + skip);
                return;
            }
            if (reel) {
                HookStatus.bound(FAMILY, "player start");
                access.setSpeed(player, speed);
                HookStatus.counted(FAMILY, APPLIED);
                Logger.printDebug(() -> "Reel speed: a reel in " + origin + " started, set to the kept " + speed + "x");
            } else {
                HookStatus.bound(FAMILY, "video start");
                access.setSpeed(player, speed);
                HookStatus.counted(FAMILY, VIDEO_APPLIED);
                Logger.printDebug(() -> "Video speed: a video in " + origin + " started, set to the kept " + speed + "x");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player start", failure);
        }
    }

    // ------------------------------------------------------------------ the rule

    private static boolean on() {
        return reelsOn() || videosOn();
    }

    private static boolean reelsOn() {
        return Utils.settingsReady() && Settings.KEEP_REEL_SPEED.get();
    }

    private static boolean videosOn() {
        return Utils.settingsReady() && Settings.KEEP_VIDEO_SPEED.get();
    }

    /**
     * Whether a player from [origin] plays feed or Watch videos, where a kept video speed goes:
     * not a story, a chat, the composer's or camera's previews or the TV cast screen.
     */
    static boolean videoViewer(@Nullable String origin) {
        if (origin == null) return false;
        String name = origin.toLowerCase(Locale.ROOT);
        if (name.startsWith("fb_stories") || name.startsWith("story_") || name.startsWith("stories_")) return false;
        for (String part : NOT_VIDEO_VIEWERS) {
            if (name.contains(part)) return false;
        }
        return true;
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < SAME;
    }

    // ------------------------------------------------------------------ the saved speeds

    /**
     * Reads the saved speeds into memory the first time a hook needs them, so after a restart of
     * Facebook or the phone the next reel or video starts where the last pick left it. A value
     * that can't be a pick is skipped. Holds {@link #LOCK}.
     */
    private static void loadSaved() {
        if (loaded) return;
        loaded = true;
        if (saved == null) {
            Context context = Utils.getContext();
            if (context == null || !Utils.isMainProcess()) return;
            saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
        for (Map.Entry<String, ?> entry : saved.getAll().entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Float) || !usable((Float) value)) continue;
            String key = entry.getKey();
            if (VIDEO_KEY.equals(key)) {
                videoKept = (Float) value;
            } else if (key.startsWith(REEL_KEY) && key.length() > REEL_KEY.length()) {
                KEPT.put(key.substring(REEL_KEY.length()), (Float) value);
            }
        }
        String read = KEPT + (videoKept == null ? "" : ", video " + videoKept);
        Logger.printDebug(() -> "Reel speed: read the saved speeds " + read);
    }

    /** Whether [speed], read back from the file, is one a menu could have picked and normal isn't. */
    private static boolean usable(float speed) {
        return !Float.isNaN(speed) && speed >= SLOWER[0] - SAME && speed <= FASTEST && !same(speed, NORMAL);
    }

    /** Saves [speed] under [key], or removes the key when [speed] is null. Holds {@link #LOCK}. */
    private static void save(String key, @Nullable Float speed) {
        if (saved == null) return;
        SharedPreferences.Editor edit = saved.edit();
        if (speed == null) {
            edit.remove(key);
        } else {
            edit.putFloat(key, speed);
        }
        edit.apply();
    }

    /** The player a pick of [speed] at [now] set its speed on, or null when none did just before. */
    @Nullable
    static Object pickedPlayer(float speed, long now) {
        synchronized (LOCK) {
            Object player = lastSetPlayer == null ? null : lastSetPlayer.get();
            if (player == null || !same(lastSetSpeed, speed) || now < lastSetAt || now - lastSetAt > PICK_WINDOW_MS) {
                return null;
            }
            return player;
        }
    }

    /**
     * The viewer a player plays in: its PlayerOrigin's origin, what its toString() writes before
     * "::" (the part after names where in the viewer it started, which can differ from reel to reel).
     */
    @Nullable
    static String originName(Object player) {
        Object origin = access.origin(player);
        if (origin == null) return null;
        String name = origin.toString();
        int cut = name.indexOf("::");
        name = cut < 0 ? name : name.substring(0, cut);
        return name.isEmpty() ? null : name;
    }

    /** [player]'s viewer for a log line. Facebook's getter throws before the player's first bind. */
    private static String viewerForLog(Object player) {
        try {
            String origin = originName(player);
            return origin == null ? "no viewer" : origin;
        } catch (Throwable failure) {
            return "no viewer yet";
        }
    }

    /** The speed kept for [origin], or {@link #NORMAL} when none is. For tests. */
    static float kept(String origin) {
        synchronized (LOCK) {
            loadSaved();
            Float kept = KEPT.get(origin);
            return kept == null ? NORMAL : kept;
        }
    }

    /** The speed kept for feed and Watch videos, or {@link #NORMAL} when none is. For tests. */
    static float videoKept() {
        synchronized (LOCK) {
            loadSaved();
            return videoKept == null ? NORMAL : videoKept;
        }
    }

    /** Forgets what's in memory, as a restart of Facebook does, and leaves the saved speeds. For tests. */
    static void restart() {
        synchronized (LOCK) {
            videoKept = null;
            lastSetPlayer = null;
            lastSetSpeed = NORMAL;
            lastSetAt = Long.MIN_VALUE;
            lastStartedPlayer = null;
            KEPT.clear();
            HANDLED.clear();
            loaded = false;
        }
        PostMenuSpeed.forget();
    }

    /** Forgets every kept speed, the saved ones too, the last speed set and every started video. For tests. */
    static void forget() {
        restart();
        synchronized (LOCK) {
            if (saved != null) saved.edit().clear().commit();
            saved = null;
        }
        access = PATCHED;
    }
}
