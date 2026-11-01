/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.misc.BreakTimer;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Resume long videos: a video longer than {@link #MIN_DURATION_MS} that was left partway picks up
 * where it was left, once, the next time a player starts it.
 *
 * <p>Everything here goes through FbGrootPlayer, the player nearly every Facebook surface plays
 * video with, on 577 and 580. The patch tells this class about five moments of it:
 *
 * <ul>
 *   <li>{@link #started}, first thing in its kept-name maybeTrackVideoStart, which a start of the
 *       video runs once the video is playing. The first start of a video in a player looks up the
 *       point saved for it and, when there is one, seeks there right after Facebook's start is
 *       done.</li>
 *   <li>{@link #stopped}, first thing in its kept-name maybeTrackVideoStop, which a pause and the
 *       end of the video run. That saves where the video is, or forgets its point at the end.</li>
 *   <li>{@link #released} where the player is released and {@link #rebound} where it's given a
 *       video, which save the same way and start the player over.</li>
 *   <li>{@link #seeking}, first thing in its seek, so a seek someone else makes before the resume
 *       (your drag of the seek bar, or Facebook carrying a position over from the feed) wins.</li>
 * </ul>
 *
 * <p>A point is saved only for a video of at least {@link #MIN_DURATION_MS} that was stopped at
 * least {@link #MIN_SAVED_MS} in and more than {@link #END_MARGIN_MS} before its end. A stop
 * closer to the end forgets the point. Reels under two minutes, live videos, ads, looping and
 * audio-only players and GIFs are left alone, and so is a start Facebook gives a start point of its
 * own, which is what a link to a moment in a video does. The points are kept in {@link ResumePoints}.
 *
 * <p>Since 581 Facebook opens every video it plays full screen in the reel viewer, a half-hour
 * documentary included, and that viewer loops what it plays. So a reel of two minutes or more,
 * looping or not, is a long video here like any other.
 *
 * <p>Off, paused, before the settings are ready, or when anything here throws, the player plays as
 * Facebook starts it: nothing is saved, nothing is looked up and nothing seeks. The report counts
 * what happened by kind, and Debug logging writes positions and durations, never which video.
 */
public final class ResumePlayback {
    /** A video shorter than this is never saved or resumed: two minutes. */
    static final int MIN_DURATION_MS = 120_000;

    /** A stop earlier than this says nothing about where the video was left. */
    static final int MIN_SAVED_MS = 15_000;

    /** A stop this close to the end, or past it, means the video was finished, and forgets it. */
    static final int END_MARGIN_MS = 10_000;

    /** A start already past this was positioned by Facebook, so it isn't moved. */
    static final int START_WINDOW_MS = 3_000;

    /**
     * How long a player's first start keeps its place through a new bind. FbGrootPlayer can replay
     * a bind it put off right after the play it belongs to, and that isn't a new video.
     */
    static final long BIND_GRACE_MS = 2_000;

    /** The trigger the resume's seek carries, the one Facebook's own player-made seeks use. */
    static final String SEEK_TRIGGER = "BY_PLAYER";

    /**
     * Seeks only something you did sends, which stop a resume even back to the start. Any other seek
     * past {@link #START_WINDOW_MS} stops it too, so a tap on a comment's timestamp (a trigger of its
     * own on 580 only) needs no name here.
     */
    static final Set<String> USER_SEEKS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "BY_USER", "BY_USER_GESTURE", "BY_SEEKBAR_CONTROLLER", "BY_SEEK", "BY_MEDIA_SESSION_CONTROLS")));

    /** The report's line. */
    static final String ROUTE = "Resume long videos";

    // What the report counts, by kind. None of them says which video.
    static final String RESUMED = "resumed";
    static final String SAVED = "point saved";
    static final String CLEARED = "point cleared at the end";
    static final String NOTHING_SAVED = "no point yet";
    static final String SHORT = "under two minutes";
    static final String REEL = "reel";
    static final String LIVE = "live";
    static final String AD = "ad";
    static final String LOOPS = "loops";
    static final String AUDIO = "audio only";
    static final String GIF = "GIF";
    static final String SEEKED_FIRST = "seek before the resume";
    static final String PAST_START = "started past the start";
    static final String OWN_START = "Facebook's own start point";
    static final String MOVED_ON = "moved on before the resume";

    /** What {@link #playerParams} answers until the patch fills it in. */
    static final Object NOT_PATCHED = new Object();

    /** What {@link #position} and {@link #duration} answer until the patch fills them in. */
    static final int NOT_PATCHED_TIME = Integer.MIN_VALUE;

    // The names {@link #paramFields} reports VideoPlayerParams' fields under, as its debug dump does.
    static final String VIDEO_ID = "videoId";
    static final String DURATION = "videoDurationMs";
    static final String START_POSITION = "startPositionMs";
    static final String LIVE_NOW = "isLiveNow";
    static final String FB_SHORTS = "isFbShorts";
    static final String SPONSORED = "isSponsored";
    static final String LOOPING = "shouldLoopVideo";
    static final String ANIMATED_GIF = "isAnimatedGifVideo";
    static final String AUDIO_ONLY = "isAudioOnly";

    private static final String SOURCE = "ResumePlayback";
    private static final String FAMILY = FamilyNames.RESUME_LONG_VIDEOS;

    /** What this class reads from a player and does to it. {@link #PATCHED} is the patch's; tests stand in. */
    interface Player {
        /** The player's position in milliseconds, or {@link #NOT_PATCHED_TIME}. */
        int position(Object player);

        /** The length of the player's video in milliseconds, 0 when unknown, or {@link #NOT_PATCHED_TIME}. */
        int duration(Object player);

        /** The player's VideoPlayerParams, null when it has none, or {@link #NOT_PATCHED}. */
        @Nullable
        Object params(Object player);

        /** Seeks the player with [trigger] to [positionMs]. False when the patch didn't fill it in. */
        boolean seek(Object player, Object trigger, int positionMs);

        /** The params' field names as "reported=field;...", or null when the patch didn't fill it in. */
        @Nullable
        String fields();
    }

    static final Player PATCHED = new Player() {
        @Override
        public int position(Object player) {
            return ResumePlayback.position(player);
        }

        @Override
        public int duration(Object player) {
            return ResumePlayback.duration(player);
        }

        @Override
        public Object params(Object player) {
            return playerParams(player);
        }

        @Override
        public boolean seek(Object player, Object trigger, int positionMs) {
            return seekPlayer(player, trigger, positionMs);
        }

        @Override
        public String fields() {
            return paramFields();
        }
    };

    /** Runs the resume once Facebook's start is done. {@link #ON_THIS_LOOPER} is the app's; tests stand in. */
    interface Later {
        /** False when it can't run [task]. */
        boolean post(Runnable task);
    }

    /** On the looper of the thread that started the player, which is the player's own. */
    static final Later ON_THIS_LOOPER = task -> {
        Looper looper = Looper.myLooper();
        return looper != null && new Handler(looper).post(task);
    };

    static volatile Player access = PATCHED;
    static volatile Later later = ON_THIS_LOOPER;
    @Nullable
    static volatile ResumePoints pointsForTests;

    static final Players PLAYERS = new Players();
    private static final Object POINTS_LOCK = new Object();
    @Nullable
    private static ResumePoints points;
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new HashMap<>();
    private static final Map<Class<?>, Object> SEEK_TRIGGERS = new HashMap<>();

    private ResumePlayback() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: FbGrootPlayer's position reader. Only a player may be passed. */
    public static int position(Object player) {
        return NOT_PATCHED_TIME;
    }

    /** Filled in by the patch: FbGrootPlayer's reader of its video's length. Only a player may be passed. */
    public static int duration(Object player) {
        return NOT_PATCHED_TIME;
    }

    /** Filled in by the patch: FbGrootPlayer's VideoPlayerParams getter. Only a player may be passed. */
    @Nullable
    public static Object playerParams(Object player) {
        return NOT_PATCHED;
    }

    /**
     * Filled in by the patch: FbGrootPlayer's seek, with the trigger (an EventTriggerType constant)
     * and the position. True once it's called it.
     */
    public static boolean seekPlayer(Object player, Object trigger, int positionMs) {
        return false;
    }

    /** Filled in by the patch: VideoPlayerParams' field names, as "reported=field;...". */
    @Nullable
    public static String paramFields() {
        return null;
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The hook, first thing in FbGrootPlayer's maybeTrackVideoStart. Recently watched, Skip silent
     * parts' menu row and Take breaks' reel count share it.
     */
    public static void started(Object player, Object trigger) {
        try {
            HookStatus.invoked(FAMILY);
            RecentlyWatched.started(player);
            SkipSilence.started(player, trigger);
            if (player != null && BreakTimer.switchedOn()) {
                Facts facts = facts(player);
                if (facts != null && facts.reel) BreakTimer.reelStarted();
            }
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player start");
            start(player, trigger, SystemClock.uptimeMillis(), System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player start", failure);
        }
    }

    /** The hook, first thing in FbGrootPlayer's maybeTrackVideoStop, which its pause and the video's end run. */
    public static void stopped(Object player, Object trigger) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player stop");
            // The resume's own seek pauses the player on its way, where it still is at the start.
            if (PLAYERS.seekingNow(player)) return;
            remember(player, System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player stop", failure);
        }
    }

    /** The hook, first thing in FbGrootPlayer's release. The player starts over after it. */
    public static void released(Object player, Object trigger) {
        try {
            HookStatus.invoked(FAMILY);
            if (player == null) return;
            try {
                if (on()) {
                    HookStatus.bound(FAMILY, "player release");
                    remember(player, System.currentTimeMillis());
                }
            } finally {
                PLAYERS.forget(player);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player release", failure);
        }
    }

    /**
     * The hook, first thing where FbGrootPlayer binds a video. The video it had is saved, and the
     * next start counts as a first one, unless the player's first start came just now.
     */
    public static void rebound(Object player) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player bind");
            remember(player, System.currentTimeMillis());
            PLAYERS.rebound(player, SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player bind", failure);
        }
    }

    /**
     * The hook, first thing in FbGrootPlayer's seek. A seek before the resume that goes past the
     * start, or that only something you did sends, means the player was put where someone wanted
     * it, and the resume is dropped.
     */
    public static void seeking(Object trigger, Object player, int positionMs) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player seek");
            String name = trigger instanceof Enum ? ((Enum<?>) trigger).name() : null;
            if (positionMs > START_WINDOW_MS || (name != null && USER_SEEKS.contains(name))) {
                PLAYERS.positioned(player);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player seek", failure);
        }
    }

    /**
     * The seek bar on feed videos, just before it seeks. It asks for the exact spot with
     * BY_INITIAL_FRAME_PERFECT_SEEK, which Facebook also sends by itself to start a video over, so
     * {@link #seeking} can't tell that this one is you placing the video.
     */
    public static void placedByYou(Object player) {
        try {
            if (!on() || player == null) return;
            PLAYERS.positioned(player);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player seek", failure);
        }
    }

    // ------------------------------------------------------------------ the rule

    private static boolean on() {
        return Utils.settingsReady() && Settings.RESUME_LONG_VIDEOS.get();
    }

    /** A start of [player]: its first start of a video looks the video up and resumes it. */
    static void start(Object player, @Nullable Object trigger, long uptime, long now) {
        Facts facts = facts(player);
        if (facts == null) return;
        State state = PLAYERS.firstStart(player, facts.videoId, uptime);
        if (state == null) return;
        FeedFilterCounters.sawList(ROUTE, 1);

        int length = length(player, facts);
        String skip = skipReason(facts, length);
        if (skip != null) {
            count(skip);
            if (REEL.equals(skip)) {
                // Where each reel began, for reels whose seekbar shows time gone before they play.
                log(() -> "Resume long videos: left a start alone, reel, " + startedAt(player, facts, length)
                        + ", " + (trigger instanceof Enum ? ((Enum<?>) trigger).name() : "no trigger"));
            } else if (!SHORT.equals(skip)) {
                log(() -> "Resume long videos: left a start alone, " + skip);
            }
            return;
        }
        ResumePoints store = points();
        if (store == null) return;
        ResumePoints.Point point = store.get(facts.videoId, now);
        if (point == null) {
            count(NOTHING_SAVED);
            return;
        }
        if (PLAYERS.wasPositioned(state)) {
            skipped(SEEKED_FIRST);
            return;
        }
        if (facts.startPositionMs > 0) {
            skipped(OWN_START);
            return;
        }
        int at = readPosition(player);
        if (at == NOT_PATCHED_TIME) return;
        if (at > START_WINDOW_MS) {
            skipped(PAST_START);
            return;
        }
        int target = point.positionMs;
        if (target >= length - END_MARGIN_MS) {
            // Saved before the video was known to be this short. It'd end the video at once.
            if (store.remove(facts.videoId, now)) count(CLEARED);
            return;
        }
        Object seekTrigger = seekTrigger(trigger);
        if (seekTrigger == null) return;
        if (!later.post(() -> resume(player, state, facts.videoId, seekTrigger, target, length))) {
            log(() -> "Resume long videos: no looper to resume on");
        }
    }

    /** The resume itself, once Facebook's start is done: the seek, unless something moved first. */
    static void resume(Object player, State state, String videoId, Object trigger, int target, int length) {
        try {
            if (!on()) return;
            Facts facts = facts(player);
            if (facts == null || !videoId.equals(facts.videoId) || !PLAYERS.isCurrent(player, state)) {
                skipped(MOVED_ON);
                return;
            }
            if (!PLAYERS.beginSeek(state)) {
                skipped(SEEKED_FIRST);
                return;
            }
            boolean sought;
            try {
                sought = access.seek(player, trigger, target);
            } finally {
                PLAYERS.endSeek(state);
            }
            if (!sought) {
                HookStatus.missingMember(FAMILY, "method", "FbGrootPlayer", "the seek");
                return;
            }
            HookStatus.bound(FAMILY, "resume seek");
            count(RESUMED);
            log(() -> "Resume long videos: resumed at " + clock(target) + " of " + clock(length));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "resume", failure);
        }
    }

    /** Saves where [player]'s video is, or forgets its point when it's at the end. */
    static void remember(Object player, long now) {
        Facts facts = facts(player);
        if (facts == null) return;
        int length = length(player, facts);
        if (skipReason(facts, length) != null) return;
        int at = readPosition(player);
        if (at == NOT_PATCHED_TIME || at < 0) return;
        ResumePoints store = points();
        if (store == null) return;
        if (at >= length - END_MARGIN_MS) {
            if (store.remove(facts.videoId, now)) {
                count(CLEARED);
                log(() -> "Resume long videos: forgot a point at the end of " + clock(length));
            }
        } else if (at >= MIN_SAVED_MS) {
            store.put(facts.videoId, at, now);
            count(SAVED);
            log(() -> "Resume long videos: saved " + clock(at) + " of " + clock(length));
        }
    }

    /**
     * Why a video [length] long is left alone, or null when it's one this resumes: reels under
     * {@link #MIN_DURATION_MS}, live videos, ads, looping, audio-only and GIF players, and anything
     * shorter than {@link #MIN_DURATION_MS}. A long video in the reel viewer, which loops everything,
     * resumes.
     */
    @Nullable
    static String skipReason(Facts facts, int length) {
        boolean longReel = facts.reel && length >= MIN_DURATION_MS;
        if (facts.reel && !longReel) return REEL;
        if (facts.live) return LIVE;
        if (facts.ad) return AD;
        if (facts.loops && !longReel) return LOOPS;
        if (facts.gif) return GIF;
        if (facts.audioOnly) return AUDIO;
        if (length < MIN_DURATION_MS) return SHORT;
        return null;
    }

    /** The video's length: the player's own when it knows it, as Facebook's reads it, else the params'. */
    private static int length(Object player, Facts facts) {
        int length = readDuration(player);
        return length > 0 ? length : facts.durationMs;
    }

    private static int readPosition(Object player) {
        int at = access.position(player);
        if (at == NOT_PATCHED_TIME) {
            HookStatus.missingMember(FAMILY, "method", "FbGrootPlayer", "the position reader");
        } else {
            HookStatus.bound(FAMILY, "player position");
        }
        return at;
    }

    /** Where [player] is as its video starts, of how long, and the start Facebook's params asked for. */
    static String startedAt(Object player, Facts facts, int length) {
        int at = access.position(player);
        return "at " + (at == NOT_PATCHED_TIME ? "an unknown position" : at + " ms") + " of " + length
                + " ms, Facebook's start " + facts.startPositionMs + " ms";
    }

    private static int readDuration(Object player) {
        int length = access.duration(player);
        if (length == NOT_PATCHED_TIME) {
            HookStatus.missingMember(FAMILY, "method", "FbGrootPlayer", "the length reader");
            return 0;
        }
        return length;
    }

    /**
     * The constant of the start's own trigger enum the resume seeks with, or null when there's none.
     * Skip silent parts' row seeks with it too.
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object seekTrigger(@Nullable Object trigger) {
        if (!(trigger instanceof Enum)) return null;
        Class<?> type = ((Enum<?>) trigger).getDeclaringClass();
        synchronized (SEEK_TRIGGERS) {
            if (SEEK_TRIGGERS.containsKey(type)) return SEEK_TRIGGERS.get(type);
            Object constant;
            try {
                constant = Enum.valueOf((Class) type, SEEK_TRIGGER);
            } catch (IllegalArgumentException missing) {
                constant = null;
                HookStatus.missingMember(FAMILY, "enum constant", type.getName(), SEEK_TRIGGER);
            }
            SEEK_TRIGGERS.put(type, constant);
            return constant;
        }
    }

    // ------------------------------------------------------------------ the params

    /** What the rule reads from a player's VideoPlayerParams. */
    static final class Facts {
        final String videoId;
        final int durationMs;
        final int startPositionMs;
        final boolean live;
        final boolean reel;
        final boolean ad;
        final boolean loops;
        final boolean gif;
        final boolean audioOnly;

        Facts(String videoId, int durationMs, int startPositionMs, boolean live, boolean reel, boolean ad,
              boolean loops, boolean gif, boolean audioOnly) {
            this.videoId = videoId;
            this.durationMs = durationMs;
            this.startPositionMs = startPositionMs;
            this.live = live;
            this.reel = reel;
            this.ad = ad;
            this.loops = loops;
            this.gif = gif;
            this.audioOnly = audioOnly;
        }
    }

    /** [player]'s facts, or null when it has no params, no video id, or a field can't be read. */
    @Nullable
    static Facts facts(Object player) {
        Object params = access.params(player);
        if (params == NOT_PATCHED) {
            HookStatus.missingMember(FAMILY, "method", "FbGrootPlayer", "the params getter");
            return null;
        }
        if (params == null) return null;
        Map<String, Field> fields = fieldsOf(params.getClass());
        if (fields == null) return null;
        try {
            Object id = fields.get(VIDEO_ID).get(params);
            if (!(id instanceof String) || ((String) id).isEmpty()) return null;
            HookStatus.bound(FAMILY, "player params");
            return new Facts((String) id,
                    fields.get(DURATION).getInt(params),
                    fields.get(START_POSITION).getInt(params),
                    fields.get(LIVE_NOW).getBoolean(params),
                    fields.get(FB_SHORTS).getBoolean(params),
                    fields.get(SPONSORED).getBoolean(params),
                    fields.get(LOOPING).getBoolean(params),
                    fields.get(ANIMATED_GIF).getBoolean(params),
                    fields.get(AUDIO_ONLY).getBoolean(params));
        } catch (IllegalAccessException | IllegalArgumentException unreadable) {
            HookStatus.threw(FAMILY, "player params", unreadable);
            return null;
        }
    }

    /**
     * The fields of [type] the rule reads, by their reported names, looked up once per class. Null,
     * and a line in Hook status, when the patch passed no names or the class lacks one.
     */
    @Nullable
    private static Map<String, Field> fieldsOf(Class<?> type) {
        synchronized (FIELDS) {
            if (FIELDS.containsKey(type)) return FIELDS.get(type);
            Map<String, Field> found = readFields(type);
            FIELDS.put(type, found);
            return found;
        }
    }

    @Nullable
    private static Map<String, Field> readFields(Class<?> type) {
        String names = access.fields();
        if (names == null) {
            HookStatus.missingMember(FAMILY, "method", "ResumePlayback", "the params' field names");
            return null;
        }
        Map<String, String> reported = new HashMap<>();
        for (String pair : names.split(";")) {
            int equals = pair.indexOf('=');
            if (equals > 0) reported.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        Map<String, Field> fields = new HashMap<>();
        for (String name : Arrays.asList(VIDEO_ID, DURATION, START_POSITION, LIVE_NOW, FB_SHORTS, SPONSORED,
                LOOPING, ANIMATED_GIF, AUDIO_ONLY)) {
            String fieldName = reported.get(name);
            Field field = fieldName == null ? null : declaredField(type, fieldName);
            if (field == null) {
                HookStatus.missingMember(FAMILY, "field", type.getName(), name);
                return null;
            }
            field.setAccessible(true);
            fields.put(name, field);
        }
        HookStatus.bound(FAMILY, "params fields");
        return fields;
    }

    @Nullable
    private static Field declaredField(Class<?> type, String name) {
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            try {
                return at.getDeclaredField(name);
            } catch (NoSuchFieldException notHere) {
                // Its parent, then.
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the saved points

    /**
     * Called once Facebook's main process has started: drops the saved points past their age on a
     * worker, whether or not the switch is on. A point keeps a video's ID, and with the switch off
     * nothing reads the points, so they used to stay for good.
     */
    public static void onFacebookStart() {
        Utils.runOnBackgroundThread(() -> {
            try {
                ResumePoints store = points();
                if (store != null) store.dropExpired(System.currentTimeMillis());
            } catch (Throwable failure) {
                Logger.printException(() -> "Resume long videos: could not age the saved points", failure);
            }
        });
    }

    /** The saved points, read the first time they're needed. Null outside the main process. */
    @Nullable
    private static ResumePoints points() {
        ResumePoints forTests = pointsForTests;
        if (forTests != null) return forTests;
        synchronized (POINTS_LOCK) {
            if (points == null) {
                Context context = Utils.getContext();
                if (context == null || !Utils.isMainProcess()) return null;
                points = new ResumePoints(context.getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE));
            }
            return points;
        }
    }

    // ------------------------------------------------------------------ the report

    private static void count(String kind) {
        FeedFilterCounters.sawKind(ROUTE, kind);
    }

    private static void skipped(String why) {
        count(why);
        log(() -> "Resume long videos: didn't resume, " + why);
    }

    private static void log(Logger.LogMessage message) {
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, message);
    }

    /** A position as minutes and seconds, the way a player shows it. */
    static String clock(int ms) {
        int seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------------ the players

    /** One player's first start of a video, and what happened to it since. */
    static final class State {
        final String videoId;
        final long startedAt;
        /** Someone else put the player where they wanted it before the resume. */
        boolean positioned;
        /** The resume's own seek is running. */
        boolean seeking;

        State(String videoId, long startedAt) {
            this.videoId = videoId;
            this.startedAt = startedAt;
        }
    }

    /**
     * Each player's {@link State}, by identity and weakly: a player Facebook drops is forgotten
     * with it. A player with no state has had no first start since it was bound or released, and a
     * seek on it before then is kept as a positioned marker.
     */
    static final class Players {
        private final Map<Key, State> states = new HashMap<>();
        private final Set<Key> soughtBeforeStart = new HashSet<>();
        private final ReferenceQueue<Object> gone = new ReferenceQueue<>();

        /**
         * The state of [player]'s first start of [videoId], or null when this isn't one: the player
         * already started this video since it was bound.
         */
        @Nullable
        synchronized State firstStart(Object player, String videoId, long uptime) {
            purge();
            Key key = new Key(player, null);
            State current = states.get(key);
            if (current != null && current.videoId.equals(videoId)) return null;
            State state = new State(videoId, uptime);
            state.positioned = soughtBeforeStart.remove(key);
            states.remove(key);
            states.put(new Key(player, gone), state);
            return state;
        }

        synchronized boolean wasPositioned(State state) {
            return state.positioned;
        }

        synchronized boolean isCurrent(Object player, State state) {
            purge();
            return states.get(new Key(player, null)) == state;
        }

        /** Marks the resume's seek as running. False when someone else sought first. */
        synchronized boolean beginSeek(State state) {
            if (state.positioned) return false;
            state.seeking = true;
            return true;
        }

        synchronized void endSeek(State state) {
            state.seeking = false;
        }

        synchronized boolean seekingNow(Object player) {
            purge();
            State state = states.get(new Key(player, null));
            return state != null && state.seeking;
        }

        /** Someone other than the resume sought [player]. */
        synchronized void positioned(Object player) {
            purge();
            Key key = new Key(player, null);
            State state = states.get(key);
            if (state == null) {
                soughtBeforeStart.add(new Key(player, gone));
            } else if (!state.seeking) {
                state.positioned = true;
            }
        }

        /** [player] was given a video: its next start is a first one, unless its first start came just now. */
        synchronized void rebound(Object player, long uptime) {
            purge();
            Key key = new Key(player, null);
            State state = states.get(key);
            if (state != null && uptime - state.startedAt < BIND_GRACE_MS) return;
            states.remove(key);
            soughtBeforeStart.remove(key);
        }

        synchronized void forget(Object player) {
            purge();
            Key key = new Key(player, null);
            states.remove(key);
            soughtBeforeStart.remove(key);
        }

        synchronized int size() {
            purge();
            return states.size();
        }

        synchronized void clear() {
            states.clear();
            soughtBeforeStart.clear();
            purge();
        }

        private void purge() {
            Reference<?> cleared;
            while ((cleared = gone.poll()) != null) {
                states.remove(cleared);
                soughtBeforeStart.remove(cleared);
            }
        }

        private static final class Key extends WeakReference<Object> {
            private final int hash;

            Key(Object player, @Nullable ReferenceQueue<Object> queue) {
                super(player, queue);
                hash = System.identityHashCode(player);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                if (other == this) return true;
                if (!(other instanceof Key)) return false;
                Object mine = get();
                return mine != null && mine == ((Key) other).get();
            }
        }
    }

    // ------------------------------------------------------------------ for tests

    /** Forgets every player, cached field and trigger, and puts the app's player and looper back. */
    static void forget() {
        PLAYERS.clear();
        synchronized (FIELDS) {
            FIELDS.clear();
        }
        synchronized (SEEK_TRIGGERS) {
            SEEK_TRIGGERS.clear();
        }
        synchronized (POINTS_LOCK) {
            points = null;
        }
        access = PATCHED;
        later = ON_THIS_LOOPER;
        pointsForTests = null;
    }

    static int playersKnown() {
        return PLAYERS.size();
    }
}
