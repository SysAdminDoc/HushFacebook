/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The "Suggested for you" groups card, its row of groups to join and "Discover more groups" under
 * it, for Hide suggested groups (#347).
 *
 * <p>On Home the card is a feed unit the edge filter takes out. The Groups tab's feed is built
 * another way and never reaches that filter, so the card showed up there a few posts down. Both
 * feeds draw it with the same component, whose layout already answers nothing when it has no groups
 * to show. The patch asks here first thing in that layout, and while the switch is on the layout
 * gives that same empty answer, so the card takes no room in either feed.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the card is Facebook's
 * own.
 */
public final class SuggestedGroupsCard {
    /** Counted under the patch's name each time the card lays out as nothing. */
    static final String HIDDEN = "Suggested groups card left out";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;

    private static final String HOOK = "suggested groups card";

    private SuggestedGroupsCard() {
    }

    /**
     * Injection point, first thing in the suggested groups card's layout. Never throws.
     *
     * @return true for no card, false to lay it out as Facebook would.
     */
    public static boolean hide() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_SUGGESTED_GROUPS.get()) {
                Logger.printDebug(() -> "Suggested groups card: kept");
                return false;
            }
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, HIDDEN);
            Logger.printDebug(() -> "Suggested groups card: left out");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            Logger.printException(() -> "Suggested groups card: could not read its switch", failure);
            return false;
        }
    }
}
