/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.content.Intent;

import app.morphe.extension.shared.L10n;

/**
 * An optional launcher entry for the videos you've watched, reels included, and the Watch history
 * row the Menu gets beside the Hushfacebook settings row (issue #114). It opens Facebook's own
 * Activity log route with the Videos you've watched category, the page that otherwise takes five
 * or six taps through Settings. Never evicts another shortcut.
 */
public final class WatchHistoryShortcut {
    static final String ID = "hushfacebook_watch_history";

    /** Facebook's Activity log route, filtered to its Videos you've watched category. */
    static final String ROUTE = "fb://activitylog?category_key=VIDEOWATCH";

    private static final RouteShortcut SHORTCUT = new RouteShortcut(ID, "Watch history", ROUTE,
            Settings.WATCH_HISTORY_SHORTCUT, context -> L10n.t(context, "Watch history"),
            () -> L10n.t("Watch history isn't available in this build."), android.R.drawable.ic_menu_recent_history);

    private WatchHistoryShortcut() { }

    static Intent intent(Context context) {
        return SHORTCUT.intent(context);
    }

    /** Whether the Watch history row belongs in the Menu: the switch is on and this build opens the route. */
    public static boolean wanted(Context context) {
        return SHORTCUT.wanted(context);
    }

    /**
     * Opens the videos you've watched from [context], inside the current task when it's an
     * activity. False when this build has no such route of its own or Android refused to start it.
     */
    public static boolean open(Context context) {
        return SHORTCUT.open(context);
    }

    /** The toast for a row this build can't open. */
    public static String unavailable() {
        return SHORTCUT.unavailable();
    }

    static void refresh(Context context) {
        SHORTCUT.refresh(context);
    }

    /** Settings changes give immediate feedback; application starts quietly retry when needed. */
    static void changed(Context context) {
        SHORTCUT.changed(context);
    }

    static SavedShortcut.Result refreshNow(Context context) {
        return SHORTCUT.refreshNow(context);
    }
}
