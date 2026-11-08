/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A video opened from Saved (#404).
 *
 * <p>Tapping a video in Saved opens it in the Reels viewer, and swiping up from there brings a
 * suggested reel, not the next video you saved. Before Facebook decides where the video opens, the
 * patch hands {@link #staysInPlayer} the channel it named, and a yes for Saved's channel plays the
 * video in Facebook's video player instead. Every other channel, the feed's included, isn't touched.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook opens it where
 * it would have.
 */
public final class SavedVideos {
    /** The channel Facebook gives a video opened from Saved. */
    static final String SAVED_CHANNEL = "vod_watchfeed_saved";

    /** Counted each time a saved video stays in the video player instead of opening Reels. */
    static final String KEPT = "Saved video kept in the player";

    /** The hook's name in the diagnostics report. */
    static final String HOOK = "Saved videos";

    private SavedVideos() {
    }

    /**
     * Injection point, right after Facebook names the channel a video tap opens in: whether to play
     * it in the video player instead of asking on. Never throws.
     */
    public static boolean staysInPlayer(String channel) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.bound(FamilyNames.REEL_DECLUTTER, HOOK);
            if (!SAVED_CHANNEL.equals(channel) || !Utils.settingsReady() || !Settings.SAVED_VIDEOS_IN_PLAYER.get()) {
                return false;
            }
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, HOOK, failure);
            return false;
        }
    }
}
