/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Taps beside the buttons under a post (#540). Facebook wraps the row under a post in a wrapper
 * whose own tap opens the comments, or who reacted when nobody has commented. The buttons and the
 * reaction faces sit inside it with taps of their own, so the wrapper's tap is the one a finger gets
 * on the empty stretch between them, which is easy to hit when aiming for a video's seek bar just
 * above. While the switch is on, the wrapper is drawn without its tap.
 */
public final class PostRowTaps {
    private PostRowTaps() {}

    /** The diagnostic counter for each row drawn without its tap. */
    static final String LEFT_OFF = "post row tap left off";

    /**
     * Injection point, where the wrapper decides whether to give the row its tap: true when it should
     * go without one. False while the switch is off, before the settings are ready, while the patch
     * is paused, or on any failure, which leaves Facebook's tap where it was.
     */
    public static boolean ignoreRowTaps() {
        try {
            HookStatus.invoked(FamilyNames.POST_ACTION_BUTTONS);
            HookStatus.bound(FamilyNames.POST_ACTION_BUTTONS, "post row tap");
            if (!Utils.settingsReady() || !Settings.IGNORE_POST_ROW_TAPS.get()) return false;
            HookStatus.counted(FamilyNames.POST_ACTION_BUTTONS, LEFT_OFF);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_ACTION_BUTTONS, "post row tap", failure);
            return false;
        }
    }
}
