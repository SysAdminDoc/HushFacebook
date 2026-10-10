/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.content.Intent;

import app.morphe.extension.shared.L10n;

/**
 * An optional launcher entry for Facebook's public Saved route, and the Saved row the Menu gets
 * beside the Hushfacebook settings row. Never evicts another shortcut.
 */
public final class SavedShortcut {
    static final String ID = "hushfacebook_saved";
    enum Result { OFF, PUBLISHED, NO_ROOM, UNAVAILABLE }

    private static final RouteShortcut SHORTCUT = new RouteShortcut(ID, "Saved", "fb://saved", Settings.SAVED_SHORTCUT,
            context -> L10n.t(context, "Saved"), () -> L10n.t("Saved isn't available in this build."),
            android.R.drawable.ic_menu_save);

    private SavedShortcut() { }

    static Intent intent(Context context) {
        return SHORTCUT.intent(context);
    }

    /** Whether the Saved row belongs in the Menu: the switch is on and this build opens the route itself. */
    public static boolean wanted(Context context) {
        return SHORTCUT.wanted(context);
    }

    /**
     * Opens Facebook's Saved screen from [context], inside the current task when it's an activity.
     * False when this build has no Saved route of its own or Android refused to start it.
     */
    public static boolean open(Context context) {
        return SHORTCUT.open(context);
    }

    static void refresh(Context context) {
        SHORTCUT.refresh(context);
    }

    /** Settings changes give immediate feedback; application starts quietly retry when needed. */
    static void changed(Context context) {
        SHORTCUT.changed(context);
    }

    static Result refreshNow(Context context) {
        return SHORTCUT.refreshNow(context);
    }
}
