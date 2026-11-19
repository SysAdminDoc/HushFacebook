/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.media.TapToPlayForTests.Trigger;

/** What tests outside this package need of Keep playing in the background: a player and a whole leave. */
public final class BackgroundPlayForTests {
    /** Stands in for Facebook's video state manager: its current player, which the window's buttons act on. */
    public static final class StateManager {
        public WeakReference<Object> current = new WeakReference<>(null);
    }

    /** The manager every player made since the last {@link #forget()} holds, as Facebook's share one. */
    private static StateManager states = new StateManager();

    /** Stands in for FbGrootPlayer: its kept isPlaying and a video id accessor. */
    public static final class Player {
        private static final AtomicInteger NEXT = new AtomicInteger(1000);

        /** Its video state manager, in a field as FbGrootPlayer keeps it. */
        private final StateManager stateManager = states;

        public boolean playing = true;
        /** A player Facebook let go: pressing play counts, but it doesn't start. */
        public boolean stuck;
        /** How many times something other than Facebook started this player. */
        public int plays;
        /** Where the video is, in milliseconds. */
        public int position;
        /** How long the video is, or 0 when that isn't known. Play at the end doesn't start it. */
        public int length;
        /** Where it was as something other than Facebook last started it, or -1. */
        public int positionAtPlay = -1;
        /** A video id no other player here has. */
        public String video = String.valueOf(NEXT.incrementAndGet());

        public boolean isPlaying() {
            return playing;
        }

        public String videoId() {
            return video;
        }

        /** FbGrootPlayer's pause: the patched inner pause tells the extension first, then it stops. */
        public void pause(Trigger trigger) {
            BackgroundPlay.pausing(this, trigger);
            playing = false;
        }

        /** Facebook's activation of this player, which makes it the manager's current one. */
        public void activate() {
            stateManager.current = new WeakReference<>(this);
        }
    }

    /** The player the window's buttons act on now, or null. */
    public static Object currentPlayer() {
        return states.current.get();
    }

    /** The patched reader itself, handed the stand-ins' field names in place of the patch's. */
    private static final BackgroundPlay.Reflected FIELDS = new BackgroundPlay.Reflected();

    /** Reads a {@link Player} as the patched reader reads FbGrootPlayer. */
    static final BackgroundPlay.PlayerReader READER = new BackgroundPlay.PlayerReader() {
        @Override
        public boolean playing(Object player) {
            return ((Player) player).isPlaying();
        }

        @Override
        public String videoId(Object player) {
            return ((Player) player).videoId();
        }

        @Override
        public void pause(Object player, String trigger) {
            ((Player) player).pause(Trigger.valueOf(trigger));
        }

        @Override
        public void play(Object player) {
            Player started = (Player) player;
            started.plays++;
            started.positionAtPlay = started.position;
            boolean ended = started.length > 0 && started.position >= started.length;
            started.playing = !started.stuck && !ended;
        }

        @Override
        public int position(Object player) {
            return ((Player) player).position;
        }

        @Override
        public void seek(Object player, int positionMs) {
            ((Player) player).position = positionMs;
        }

        @Override
        public int length(Object player) {
            return ((Player) player).length;
        }

        @Override
        public boolean makeCurrent(Object player) throws Exception {
            return FIELDS.makeCurrent(player, "stateManager", "current");
        }

        @Override
        public boolean isCurrent(Object player) throws Exception {
            return FIELDS.isCurrent(player, "stateManager", "current");
        }
    };

    private BackgroundPlayForTests() { }

    /** Forgets every followed player, tap and decision, and reads players as {@link Player}. */
    public static void forget() {
        TapToPlayForTests.forget();
        BackgroundPlay.forget();
        BackgroundPlayButton.forget();
        BackgroundPlay.reader = READER;
        states = new StateManager();
    }

    /**
     * A video started with a tap plays for two seconds, Facebook pauses it as you leave, and its
     * manager asks the extension's handler about that pause. Answers whether the handler took the
     * video on and skipped Facebook's own checks for it. Leaves nothing behind.
     */
    public static boolean continuesAVideoYouStarted() {
        forget();
        try {
            Object handler = new Object();
            BackgroundPlay.useHandler(handler);
            Player player = new Player();
            TapToPlayForTests.tapEnded(80);
            BackgroundPlay.started(player, Trigger.BY_USER);
            SystemClock.sleep(2000);
            BackgroundPlay.pausing(player, Trigger.BY_PLAYER);
            boolean stopped = BackgroundPlay.deciding(handler, player.video, Trigger.BY_PLAYER);
            boolean skipped = BackgroundPlay.skipCheck();
            BackgroundPlay.answered(false);
            return !stopped && skipped;
        } finally {
            forget();
        }
    }
}
