/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.content.Intent;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Skip location prompts patch asks each time Facebook opens its device location screen
 * (#498).
 *
 * <p>Facebook asks for your location through one screen, its device location opt-in. It opens at
 * times Facebook picks, each naming a source and an entry point, and shows the page that starts "To
 * use Location Services, allow Facebook to access your location" with Continue and Skip, which goes
 * on to Android's permission question or Google's location dialog. The patch asks here first thing
 * as that screen opens. A yes, and the screen closes the way Facebook closes it when it has nothing
 * to ask, before anything shows, so nothing is allowed and nothing changes. A location you give
 * Facebook in Android's settings still works.
 *
 * <p>Off, paused, settings that aren't ready yet, or a failure in here, and the screen opens as
 * Facebook meant it to.
 */
public final class LocationPrompts {
    /** The diagnostic counter route: each time the screen opened, by its source, and the ones skipped. */
    static final String ROUTE = "Location prompts";

    private LocationPrompts() {
    }

    /**
     * Injection point, right after the device location screen's onActivityCreate has called its
     * parent's: true and the screen closes there.
     */
    public static boolean skips(Activity screen) {
        try {
            HookStatus.invoked(FamilyNames.LOCATION_PROMPTS);
            String from = openedFrom(screen);
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, from);
            if (!Utils.settingsReady() || !Settings.SKIP_LOCATION_PROMPTS.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, "skipped");
            Logger.printDebug(() -> "Skip location prompts: closed the location screen opened from " + from);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOCATION_PROMPTS, "location screen", failure);
            return false;
        }
    }

    /** The source and entry point Facebook opened the screen with, "source/entry point", or "unknown". */
    static String openedFrom(Activity screen) {
        Intent intent = screen == null ? null : screen.getIntent();
        if (intent == null) return "unknown";
        String source = intent.getStringExtra("source");
        String entry = intent.getStringExtra("entry_point");
        if (source == null && intent.getData() != null && intent.getData().isHierarchical()) {
            source = intent.getData().getQueryParameter("source");
            entry = intent.getData().getQueryParameter("entry_point");
        }
        if (source == null && entry == null) return "unknown";
        return (source == null ? "unknown" : source) + "/" + (entry == null ? "unknown" : entry);
    }
}
