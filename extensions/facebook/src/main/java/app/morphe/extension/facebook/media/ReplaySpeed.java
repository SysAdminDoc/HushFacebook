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
 * Speed on finished live videos (#238). Facebook's gear menu always offers Playback speed in the
 * inline and Watch players, but in its full-screen player, the one a live video and its replay open
 * in, a video that isn't live gets the row only behind a server flag or the unified player's
 * engagement state, and the live player passes neither. The patch asks {@link #offerSpeed} where
 * the menu is about to leave the row out, and a yes adds it, with Facebook's own speed sheet behind
 * it. Videos that are still live never get here, and an ad keeps Facebook's menu.
 */
public final class ReplaySpeed {
    /** Counted under Keep the reel speed each time the gear menu gets the row it would have left out. */
    static final String OFFERED = "Playback speed offered on a full-screen video that isn't live";

    private static final String FAMILY = FamilyNames.KEEP_REEL_SPEED;

    private ReplaySpeed() {
    }

    /**
     * Called in the gear menu's builder on a full-screen video that isn't live, where Facebook has
     * just decided to leave Playback speed out. [sponsored] is the video's isSponsored, nonzero for
     * an ad. True puts the row in. Off, paused, not ready, on an ad or failing, Facebook's no stands.
     */
    public static boolean offerSpeed(int sponsored) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.SPEED_ON_REPLAYS.get()) return false;
            if (sponsored != 0) {
                Logger.printDebug(() -> "Replay speed: an ad's gear menu keeps Facebook's rows");
                return false;
            }
            HookStatus.counted(FAMILY, OFFERED);
            Logger.printDebug(() -> "Replay speed: the gear menu of a full-screen video that isn't live gets Playback speed");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "replay speed", failure);
            return false;
        }
    }
}
