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
 * What the Skip location prompts patch asks each time Facebook checks whether its location page may
 * show (#498).
 *
 * <p>The page that starts "To use Location Services, allow Facebook to access your location", with
 * Skip and Continue, is a promotion Facebook's server sends, mostly right after you sign in. It shows
 * only while a check on the phone passes: Facebook hasn't been given location and hasn't shown it too
 * many times yet. The patch asks here first thing in that check. A yes, and the check fails, so the
 * page never shows and Facebook goes on to Home. Nothing is allowed and nothing changes. Location you
 * turn on yourself, in Android's settings or when Facebook asks inside a feature, still works.
 *
 * <p>Off, paused, settings that aren't ready yet, or a failure in here, and Facebook's own check
 * answers as it always did.
 */
public final class LocationPrompts {
    /** The diagnostic counter route: each check, by the trigger it ran for, and the ones skipped. */
    static final String ROUTE = "Location prompts";

    /** Longest trigger name kept in the counters. */
    private static final int NAME_LIMIT = 80;

    private LocationPrompts() {
    }

    /**
     * Injection point, first thing in the location promotion's filter: true and the filter fails,
     * so the page doesn't show. The trigger is Facebook's InterstitialTrigger; its name only feeds
     * the counters.
     */
    public static boolean skips(Object trigger) {
        try {
            HookStatus.invoked(FamilyNames.LOCATION_PROMPTS);
            String when = triggerName(trigger);
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, when);
            if (!Utils.settingsReady() || !Settings.SKIP_LOCATION_PROMPTS.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, "skipped");
            Logger.printDebug(() -> "Skip location prompts: kept Facebook's location page from showing at " + when);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOCATION_PROMPTS, "location promotion", failure);
            return false;
        }
    }

    /** The trigger's name as Facebook writes it, cut to 80 characters, or "unknown". */
    static String triggerName(Object trigger) {
        if (trigger == null) return "unknown";
        String name = String.valueOf(trigger).trim();
        if (name.isEmpty()) return "unknown";
        return name.length() > NAME_LIMIT ? name.substring(0, NAME_LIMIT) : name;
    }
}
