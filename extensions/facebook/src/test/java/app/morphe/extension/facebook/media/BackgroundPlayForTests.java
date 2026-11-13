/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;


import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.media.TapToPlayForTests.Trigger;

/** What tests outside this package need of Keep playing in the background: a player and a whole leave. */
public final class BackgroundPlayForTests {
    /** Stands in for FbGrootPlayer: its kept isPlaying and a video id accessor. */
    public static final class Player {
        private static final AtomicInteger NEXT = new AtomicInteger(1000);

        public boolean playing = true;
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
    }

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
    };

    private BackgroundPlayForTests() { }

    /** Forgets every followed player, tap and decision, and reads players as {@link Player}. */
    public static void forget() {
        TapToPlayForTests.forget();
        BackgroundPlay.forget();
        BackgroundPlayButton.forget();
        BackgroundPlay.reader = READER;
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
