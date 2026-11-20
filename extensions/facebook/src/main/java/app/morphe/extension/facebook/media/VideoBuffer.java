/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Video buffer (#526). Facebook's players decide two things over and over while a video plays:
 * whether enough of it has loaded to start (or to play on after a stall), and whether to load
 * more. Its HeroPlayer load control works both out from its own numbers and the server's, and the
 * plain ExoPlayer one some videos get keeps fixed ones. Each player's check asks here with the
 * number it settled on, in microseconds, and goes on with the answer.
 *
 * <p>{@link BufferSize#LOW} caps the numbers, the larger choices raise them, and Facebook's choice
 * hands every number back as it came. What Facebook does after the ask stays as it was: a short
 * clip still starts once half of it has loaded, and a player that has filled its memory share
 * stops loading or starts playing, whatever the time numbers say. Players live in Facebook's own
 * process, so a change shows from the next check, which comes within a moment. Nothing here may
 * throw into Facebook's player: paused, before the settings are ready or when anything here fails,
 * the player gets its own number back.
 */
public final class VideoBuffer {
    /** Counted each time a start check gets a number other than its own. */
    static final String START_CHANGED = "Start check given another wait";

    /** Counted each time a loading check gets other numbers than its own. */
    static final String AHEAD_CHANGED = "Loading check given another span";

    private static final String FAMILY = FamilyNames.VIDEO_BUFFER;

    /** The choices the last start and loading changes were logged for, so each is logged once. */
    private static volatile BufferSize loggedStart = BufferSize.FACEBOOK;
    private static volatile BufferSize loggedAhead = BufferSize.FACEBOOK;

    private VideoBuffer() {
    }

    /**
     * Injection point, each player's check of whether a video can start or play on, with the
     * time loaded it waits for [waitUs] and whether it's [afterStall]. Answers the wait it uses.
     */
    public static long startAt(long waitUs, boolean afterStall) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "start check");
            BufferSize size = chosen();
            if (size == BufferSize.FACEBOOK) return waitUs;
            long answer = toward(size, waitUs, micros(afterStall ? size.afterStallMs : size.startMs));
            if (answer != waitUs) {
                HookStatus.counted(FAMILY, START_CHANGED);
                if (loggedStart != size) {
                    loggedStart = size;
                    note(size, afterStall ? "the wait after a stall" : "the wait to start", waitUs, answer);
                }
            }
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "start check", failure);
            return waitUs;
        }
    }

    /**
     * Injection point, each player's check of whether to load more, on the time ahead [lowUs]
     * below which it always does. Answers the low mark it uses.
     */
    public static long aheadLow(long lowUs) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "loading check");
            BufferSize size = chosen();
            if (size == BufferSize.FACEBOOK) return lowUs;
            long answer = toward(size, lowUs, micros(size.aheadLowMs));
            if (answer != lowUs) HookStatus.counted(FAMILY, AHEAD_CHANGED);
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "loading check", failure);
            return lowUs;
        }
    }

    /**
     * Injection point, the same check, on the time ahead [highUs] past which the player stops
     * loading, once [lowUs] holds the low mark {@link #aheadLow} answered. Answers the high mark it
     * uses, never below the low one, so the player never stops loading under the mark where it
     * always loads.
     */
    public static long aheadHigh(long lowUs, long highUs) {
        try {
            BufferSize size = chosen();
            if (size == BufferSize.FACEBOOK) return highUs;
            long answer = Math.max(lowUs, toward(size, highUs, micros(size.aheadHighMs)));
            if (answer != highUs && loggedAhead != size) {
                loggedAhead = size;
                note(size, "the time kept loaded ahead", highUs, answer);
            }
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "loading check", failure);
            return highUs;
        }
    }

    /** The saved choice, or Facebook's while paused or before the settings are ready. */
    static BufferSize chosen() {
        if (!Utils.settingsReady()) return BufferSize.FACEBOOK;
        BufferSize size = Settings.VIDEO_BUFFER.get();
        return size == null ? BufferSize.FACEBOOK : size;
    }

    /** [facebookUs] moved to [wantedUs]: lowered to it under a cap, raised to it under a floor. */
    static long toward(BufferSize size, long facebookUs, long wantedUs) {
        return size.caps() ? Math.min(facebookUs, wantedUs) : Math.max(facebookUs, wantedUs);
    }

    private static long micros(int ms) {
        return ms * 1000L;
    }

    private static void note(BufferSize size, String what, long facebookUs, long answerUs) {
        Logger.printDebug(() -> "Video buffer " + size.fileValue + ": " + what + " went from "
                + facebookUs / 1000 + " ms to " + answerUs / 1000 + " ms");
    }
}
