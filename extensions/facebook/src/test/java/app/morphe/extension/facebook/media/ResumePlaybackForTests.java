/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Stands in for FbGrootPlayer, its VideoPlayerParams and its trigger enum, the way the patch's
 * filled-in stubs would reach them, and runs the resume Facebook's looper would run later.
 */
public final class ResumePlaybackForTests {
    /** Stands in for Facebook's EventTriggerType: only the constant names matter. */
    public enum Trigger {
        BY_USER, BY_AUTOPLAY, BY_PLAYER, BY_SEEKBAR_CONTROLLER, BY_SEEK, BY_USER_GESTURE, BY_MEDIA_SESSION_CONTROLS,
        BY_ABSOLUTE_SEEK_BY_TRANSITION, BY_INITIAL_FRAME_PERFECT_SEEK
    }

    /** A trigger enum without the constant the resume seeks with. */
    public enum TriggerWithoutPlayer { BY_USER, BY_AUTOPLAY }

    /** Stands in for VideoPlayerParams: the fields the rule reads, under names of its own. */
    public static final class Params {
        public String id;
        public int length;
        public int start;
        public boolean live;
        public boolean reel;
        public boolean sponsored;
        public boolean loop;
        public boolean gif;
        public boolean audio;

        public Params(String id, int length) {
            this.id = id;
            this.length = length;
        }
    }

    /** What the patch's field-name stub would answer for {@link Params}. */
    public static final String FIELDS = "videoId=id;videoDurationMs=length;startPositionMs=start;isLiveNow=live;"
            + "isFbShorts=reel;isSponsored=sponsored;shouldLoopVideo=loop;isAnimatedGifVideo=gif;isAudioOnly=audio";

    /** Stands in for FbGrootPlayer. */
    public static final class Player {
        public Params params;
        public int position;
        /** What the player's own length reader answers; 0 when it doesn't know yet. */
        public int length;
        /** Every seek made on it, as "TRIGGER@ms". */
        public final List<String> seeks = new ArrayList<>();
        /** Runs inside a seek, where Facebook's seek tells the hooks about itself. */
        public Runnable duringSeek;

        public Player(Params params) {
            this.params = params;
        }
    }

    /** Resumes the rule posted, not yet run. */
    static final Deque<Runnable> LATER = new ArrayDeque<>();

    /** What the patched stubs do, on {@link Player}s. */
    static final ResumePlayback.Player ACCESS = new ResumePlayback.Player() {
        @Override
        public int position(Object player) {
            return ((Player) player).position;
        }

        @Override
        public int duration(Object player) {
            return ((Player) player).length;
        }

        @Override
        public Object params(Object player) {
            return ((Player) player).params;
        }

        @Override
        public boolean seek(Object player, Object trigger, int positionMs) {
            Player seeking = (Player) player;
            // Facebook's seek tells the seek hook first, then pauses and restarts a playing video.
            ResumePlayback.seeking(trigger, player, positionMs);
            if (seeking.duringSeek != null) seeking.duringSeek.run();
            seeking.seeks.add(((Enum<?>) trigger).name() + "@" + positionMs);
            seeking.position = positionMs;
            return true;
        }

        @Override
        public String fields() {
            return FIELDS;
        }
    };

    private ResumePlaybackForTests() {
    }

    /** Stands in for the patched stubs and Facebook's looper, and forgets every player and point. */
    public static void install() {
        ResumePlayback.forget();
        LATER.clear();
        ResumePlayback.access = ACCESS;
        ResumePlayback.later = task -> LATER.add(task);
        // Recently watched shares the start hook. Its checks would land on the real looper, after
        // the test, so they're dropped unless RecentlyWatchedForTests installs its own.
        RecentlyWatched.forget();
        RecentlyWatched.timer = (delayMs, task) -> false;
    }

    /** Runs every resume posted so far. */
    public static void runLater() {
        Runnable task;
        while ((task = LATER.poll()) != null) task.run();
    }

    /** Whether a seek was marked on [player] before its first start. Reading it takes the mark. */
    public static boolean placedBeforeStart(Object player) {
        return ResumePlayback.PLAYERS.wasPositioned(ResumePlayback.PLAYERS.firstStart(player, "read", 0));
    }

    /** Puts the app's own player, looper and store back. */
    public static void forget() {
        LATER.clear();
        ResumePlayback.forget();
        RecentlyWatched.forget();
    }

    /**
     * For the pause probe: a long video left at 5:00 in one player, then its start in another.
     * True when that start seeks, which a paused Hushfacebook must never do.
     */
    public static boolean resumesALongVideo() {
        install();
        Params params = new Params("1234567890", 20 * 60_000);
        Player first = new Player(params);
        first.position = 5 * 60_000;
        ResumePlayback.stopped(first, Trigger.BY_USER);
        Player second = new Player(params);
        ResumePlayback.started(second, Trigger.BY_USER);
        runLater();
        boolean sought = !second.seeks.isEmpty();
        forget();
        return sought;
    }
}
