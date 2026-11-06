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
 * another way and never reaches that filter, so the card showed up there a few posts down. Home's
 * card and the Groups tab's in-feed copy are drawn by one component, whose layout already answers
 * nothing when it has no groups to show. The patch asks {@link #hide()} first thing in that layout,
 * and while the switch is on the layout gives that same empty answer.
 *
 * <p>The Groups tab also closes its feed with a block of its own, and that block draws its
 * "Suggested for you" row with another component, which never reaches the first one. The patch asks
 * {@link #hideDiscoverRow()} first thing there. Each answer counts under its own name, so a
 * diagnostic shows which of the two a card came through.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the card is Facebook's
 * own.
 */
public final class SuggestedGroupsCard {
    /** Counted under the patch's name each time the card lays out as nothing. */
    static final String HIDDEN = "Suggested groups card left out";

    /** Counted each time the Groups tab's own "Suggested for you" row lays out as nothing. */
    static final String DISCOVER_ROW_HIDDEN = "Groups tab suggested groups left out";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;

    private static final String HOOK = "suggested groups card";

    private static final String DISCOVER_ROW_HOOK = "Groups tab suggested groups row";

    private SuggestedGroupsCard() {
    }

    /**
     * Injection point, first thing in the suggested groups card's layout. Never throws.
     *
     * @return true for no card, false to lay it out as Facebook would.
     */
    public static boolean hide() {
        return decide(HOOK, HIDDEN, "Suggested groups card");
    }

    /**
     * Injection point, first thing in the layout of the "Suggested for you" row the Groups tab
     * closes its feed with. Never throws.
     *
     * @return true for no row, false to lay it out as Facebook would.
     */
    public static boolean hideDiscoverRow() {
        return decide(DISCOVER_ROW_HOOK, DISCOVER_ROW_HIDDEN, "Groups tab suggested groups");
    }

    private static boolean decide(String hook, String counted, String what) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_SUGGESTED_GROUPS.get()) {
                Logger.printDebug(() -> what + ": kept");
                return false;
            }
            HookStatus.bound(FAMILY, hook);
            HookStatus.counted(FAMILY, counted);
            Logger.printDebug(() -> what + ": left out");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, hook, failure);
            Logger.printException(() -> what + ": could not read its switch", failure);
            return false;
        }
    }
}
