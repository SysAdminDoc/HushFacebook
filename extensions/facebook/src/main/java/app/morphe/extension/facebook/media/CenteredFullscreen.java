/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Open full screen videos centered (#242). Facebook's full screen player, the one a comment's video
 * or a video link opens, starts a video held upright with its details and comments panel open, and
 * that panel keeps a landscape video at the top of the screen. The player's quiet mode is the same
 * screen with the panel folded away, which is what its "Hide details and comments" chevron turns
 * on, and Facebook only starts in it when the phone is turned sideways.
 *
 * <p>Right after the player sets quiet mode up for a video, {@link #started} gets the quiet mode
 * manager and the setup's last argument, which keeps quiet mode off whichever way the phone is
 * held. With the switch on, quiet mode starts on, so the video opens centered and the chevron
 * still brings the panel back. A video Facebook keeps out of quiet mode, and every video while the
 * switch is off, opens as Facebook lays it out.
 */
public final class CenteredFullscreen {
    /** What the report counts each time a video opens centered. */
    static final String CENTERED = "Full screen video opened centered";

    private static final String FAMILY = FamilyNames.TAP_TO_PLAY;

    /** Reaches a manager's quiet mode flag. The tests hand in their own. */
    interface Flags {
        @Nullable AtomicBoolean of(Object manager);
    }

    static final Flags PATCHED = CenteredFullscreen::quietFlag;

    static Flags flags = PATCHED;

    private CenteredFullscreen() {
    }

    /** Filled in by the patch: the quiet mode manager's flag. */
    @Nullable
    public static AtomicBoolean quietFlag(Object manager) {
        return null;
    }

    /** Called by the patch right after the full screen player sets quiet mode up for a video. */
    public static void started(Object manager, boolean neverQuiet) {
        try {
            if (neverQuiet || !on()) return;
            AtomicBoolean quiet = flags.of(manager);
            if (quiet != null && quiet.compareAndSet(false, true)) HookStatus.counted(FAMILY, CENTERED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "full screen quiet mode", failure);
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.CENTER_FULL_SCREEN_VIDEOS.get();
    }
}
