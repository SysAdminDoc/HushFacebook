/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hold back analytics uploads patch asks before Facebook's camera roll cloud processing
 * runs, for its camera roll switch.
 *
 * <p>The processing behind camera roll sharing suggestions looks through the photos and videos on
 * the phone, runs models on them and uploads photos, video details and the model output. On 582 no
 * local preference gates it: the opt-in on Facebook's Camera roll sharing suggestions page is applied
 * on Facebook's server. One config check decides whether it runs, and a no makes Facebook cancel the
 * work it scheduled, so the patch puts {@link #holdProcessing} first in that check. A run started
 * straight from opting in on that page skips the check (582 {@code $didJustUserOptedIn}). A
 * separate job reports how many photos and videos the phone holds. Its first config read is a
 * kill switch, and {@link #stopMediaCount} answers it as set while the switch holds, which cancels
 * that job the way Facebook's own kill switch does.
 *
 * <p>It fails open: the switch off, a pause, settings that aren't ready yet, or a failure in here,
 * and Facebook goes on as it would have.
 */
public final class CameraRollProcessing {
    /** The diagnostic counter route: each check Facebook made, and the ones held back. */
    static final String ROUTE = "Camera roll processing";

    /** What a processing check answered no is counted under. */
    static final String PROCESSING = "Cloud processing checks";

    /** What a media count job cancelled is counted under. */
    static final String MEDIA_COUNT = "Media count reports";

    private CameraRollProcessing() {
    }

    /**
     * Injection point, first in the camera roll processing's config check. True answers the check
     * no at once. Never throws.
     */
    public static boolean holdProcessing() {
        return hold(PROCESSING, "camera roll processing check");
    }

    /**
     * Injection point, on the media count job's kill switch. Answers [facebook], or true while the
     * switch holds the job, which sends it down the cancel branch. Never throws.
     */
    public static boolean stopMediaCount(boolean facebook) {
        if (facebook) {
            HookStatus.invoked(FamilyNames.ANALYTICS_UPLOADS);
            return true;
        }
        return hold(MEDIA_COUNT, "media count job");
    }

    /** True when the switch holds back what's starting, counted under [what]. Never throws. */
    private static boolean hold(String what, String hook) {
        try {
            HookStatus.invoked(FamilyNames.ANALYTICS_UPLOADS);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HOLD_CAMERA_ROLL_PROCESSING.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, what);
            Logger.printDebug(() -> "Camera roll processing: held back a " + hook);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ANALYTICS_UPLOADS, hook, failure);
            return false;
        }
    }
}
