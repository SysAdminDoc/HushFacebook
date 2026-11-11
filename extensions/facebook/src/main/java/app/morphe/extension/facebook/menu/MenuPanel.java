/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Which Menu Facebook's side panel shows (#184).
 *
 * <p>Some accounts open the Menu as a panel sliding in from the side. When Facebook sets that panel
 * up it asks the server whether to fill it with the Bloks Menu, a page the server draws, or with the
 * Menu Facebook draws itself, the one the Menu tab shows. Every Menu hook (Upgrades, Also from Meta,
 * the Muse card, Meta AI and the picked tiles) edits the second, so the patch hands the server's
 * answer to {@link #bloksPanel} and Facebook's own Menu goes in the panel while any of them acts.
 *
 * <p>Facebook's own Menu in the panel is the Menu tab's page (fragment type 20), and the factory
 * building that page asks the same server flag again, so an account the server gives the Bloks Menu
 * got the Bloks page back inside the swapped panel (#184's 0.16.0 report: the panel swapped, no Menu
 * hook ran). That answer goes to {@link #bloksMenu}, which also gives such an account's Menu tab
 * Facebook's own Menu.
 *
 * <p>The panel is set up once per screen, so a switch changed later shows after a restart. With every
 * switch off, a pause, settings that aren't ready, or any failure in here, the panel and the page are
 * what the server picked.
 */
public final class MenuPanel {
    /** The Hook status name of the hook. */
    static final String HOOK = "Menu panel";

    /** The counter route: every Bloks panel or page asked for, and the ones turned into Facebook's own Menu. */
    static final String ROUTE = "Menu panel";

    /** What a Bloks panel turned into Facebook's own Menu is counted under. */
    static final String SWAPPED = "Bloks Menu";

    /** What a Bloks Menu page turned into Facebook's own Menu is counted under. */
    static final String SWAPPED_PAGE = "Bloks Menu page";

    private MenuPanel() {
    }

    /**
     * Injection point, where Facebook sets up the side panel, right after the server answered
     * [facebooks] to whether it's the Bloks Menu. Answers false, Facebook's own Menu, while a Menu
     * switch acts, and [facebooks] otherwise. Never throws.
     */
    public static boolean bloksPanel(boolean facebooks) {
        return ownMenu(facebooks, SWAPPED, "the side panel");
    }

    /**
     * Injection point, where Facebook's Menu page factory (the Menu tab's page, and the swapped side
     * panel's) has the server's answer [facebooks] to whether the page is the Bloks Menu. Answers as
     * {@link #bloksPanel} does. Never throws.
     */
    public static boolean bloksMenu(boolean facebooks) {
        return ownMenu(facebooks, SWAPPED_PAGE, "the Menu page");
    }

    private static boolean ownMenu(boolean facebooks, String reason, String where) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            if (!facebooks) return false;
            // Ready first: Settings loads every switch, and it can't before the context is set.
            if (!Utils.settingsReady() || !anySwitchActs()) return true;
            HookStatus.bound(FamilyNames.MENU_PROMOTIONS, HOOK);
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.removed(ROUTE, 1, reason);
            Logger.printDebug(() -> "Hide Menu promotions: " + where + " gets Facebook's own Menu, not the Bloks one");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, HOOK, failure);
            return facebooks;
        }
    }

    /** Whether any switch of Hide Menu promotions has something to change in the Menu. */
    static boolean anySwitchActs() {
        return Settings.HIDE_MENU_UPGRADES.get() || Settings.HIDE_MENU_ALSO_FROM_META.get()
                || Settings.HIDE_MENU_HELP.get() || Settings.HIDE_MENU_SHORTCUTS.get()
                || Settings.HIDE_MENU_CREATE_PAGE.get() || Settings.KEEP_MENU_SETTINGS_EXPANDED.get()
                || Settings.HIDE_MENU_MUSE.get() || Settings.HIDE_MENU_META_AI.get()
                || !MenuTiles.hiddenNow().isEmpty();
    }
}
