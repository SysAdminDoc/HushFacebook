/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The side panel's Menu (#184): while any Menu switch acts, a panel the server wants as the Bloks
 * Menu gets Facebook's own Menu, the one every Menu hook edits. With every switch off, paused, or
 * before the settings are ready, the panel is what the server picked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuPanelTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_MENU_UPGRADES.resetToDefault();
        Settings.HIDE_MENU_ALSO_FROM_META.resetToDefault();
        Settings.HIDE_MENU_HELP.resetToDefault();
        Settings.HIDE_MENU_SHORTCUTS.resetToDefault();
        Settings.KEEP_MENU_SETTINGS_EXPANDED.resetToDefault();
        Settings.HIDE_MENU_MUSE.resetToDefault();
        Settings.HIDE_MENU_META_AI.resetToDefault();
        Settings.HIDDEN_MENU_TILES.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String line(List<String> report, String prefix) {
        for (String line : report) {
            if (line.startsWith(prefix + ":")) return line;
        }
        return null;
    }

    private static void allSwitchesOff() {
        Settings.HIDE_MENU_UPGRADES.save(false);
        Settings.HIDE_MENU_ALSO_FROM_META.save(false);
        Settings.HIDE_MENU_HELP.save(false);
        Settings.HIDE_MENU_SHORTCUTS.save(false);
        Settings.KEEP_MENU_SETTINGS_EXPANDED.save(false);
        Settings.HIDE_MENU_MUSE.save(false);
        Settings.HIDE_MENU_META_AI.save(false);
        Settings.HIDDEN_MENU_TILES.save("");
    }

    @Test
    public void theDefaultsPutFacebooksOwnMenuInThePanel() {
        assertFalse(MenuPanel.bloksPanel(true));
        assertEquals(MenuPanel.ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: " + MenuPanel.SWAPPED
                        + ". Removed: " + MenuPanel.SWAPPED + " 1",
                line(FeedFilterCounters.report(), MenuPanel.ROUTE));
        assertEquals(FamilyNames.MENU_PROMOTIONS + ": invoked 1, 1 found, 0 missing",
                line(HookStatus.report(), FamilyNames.MENU_PROMOTIONS));
    }

    /** A panel the server already gave Facebook's own Menu stays that way, switches or not. */
    @Test
    public void facebooksOwnMenuIsNeverSwapped() {
        assertFalse(MenuPanel.bloksPanel(false));
        allSwitchesOff();
        assertFalse(MenuPanel.bloksPanel(false));
        assertNull("a native panel was counted", line(FeedFilterCounters.report(), MenuPanel.ROUTE));
    }

    /** Each switch alone is enough: every one of them edits Facebook's own Menu only. */
    @Test
    public void eachSwitchAloneSwapsThePanel() {
        allSwitchesOff();
        assertTrue("with every switch off the panel is the server's", MenuPanel.bloksPanel(true));

        Settings.HIDE_MENU_UPGRADES.save(true);
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.HIDE_MENU_UPGRADES.save(false);

        Settings.HIDE_MENU_ALSO_FROM_META.save(true);
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.HIDE_MENU_ALSO_FROM_META.save(false);

        Settings.HIDE_MENU_HELP.save(true);
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.HIDE_MENU_HELP.save(false);

        Settings.HIDE_MENU_SHORTCUTS.save(true);
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.HIDE_MENU_SHORTCUTS.save(false);

        Settings.KEEP_MENU_SETTINGS_EXPANDED.save(true);
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.KEEP_MENU_SETTINGS_EXPANDED.save(false);

        Settings.HIDE_MENU_MUSE.save(true);
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.HIDE_MENU_MUSE.save(false);

        Settings.HIDE_MENU_META_AI.save(true);
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.HIDE_MENU_META_AI.save(false);

        Settings.HIDDEN_MENU_TILES.save("gaming");
        assertFalse(MenuPanel.bloksPanel(true));
        Settings.HIDDEN_MENU_TILES.save("");
        assertTrue(MenuPanel.bloksPanel(true));
    }

    @Test
    public void pausedThePanelIsTheServers() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(MenuPanel.bloksPanel(true));
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertTrue(MenuPanel.bloksPanel(true));
        PauseForTests.resume();
        assertFalse(MenuPanel.bloksPanel(true));
    }

    @Test
    public void untilTheSettingsAreReadyThePanelIsTheServers() {
        boolean[] bloks = {false, false};
        SettingsContextRule.withoutContext(() -> bloks[0] = MenuPanel.bloksPanel(true));
        SettingsContextRule.beforeThePauseIsDecided(() -> bloks[1] = MenuPanel.bloksPanel(true));
        assertTrue(bloks[0]);
        assertTrue(bloks[1]);
        assertFalse(MenuPanel.bloksPanel(true));
    }

    /**
     * #184's 0.16.0 report: the server gives the account the Bloks Menu, so the swapped panel opens
     * the Menu page and the page's factory asks the same flag. Both answers turn into Facebook's own
     * Menu, each counted under its own reason.
     */
    @Test
    public void aBloksAccountsPanelAndPageBothGetFacebooksOwnMenu() {
        assertFalse(MenuPanel.bloksPanel(true));
        assertFalse(MenuPanel.bloksMenu(true));
        assertEquals(MenuPanel.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + MenuPanel.SWAPPED_PAGE
                        + ". Removed: " + MenuPanel.SWAPPED + " 1, " + MenuPanel.SWAPPED_PAGE + " 1",
                line(FeedFilterCounters.report(), MenuPanel.ROUTE));
        assertEquals(FamilyNames.MENU_PROMOTIONS + ": invoked 2, 1 found, 0 missing",
                line(HookStatus.report(), FamilyNames.MENU_PROMOTIONS));
    }

    @Test
    public void theMenuPageIsTheServersWithEverySwitchOffOrPaused() {
        assertFalse("a native page stays native", MenuPanel.bloksMenu(false));
        allSwitchesOff();
        assertTrue(MenuPanel.bloksMenu(true));
        assertFalse(MenuPanel.bloksMenu(false));
        assertNull("a page left alone was counted", line(FeedFilterCounters.report(), MenuPanel.ROUTE));

        Settings.HIDDEN_MENU_TILES.save("gaming");
        assertFalse(MenuPanel.bloksMenu(true));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(MenuPanel.bloksMenu(true));
        PauseForTests.resume();
        boolean[] bloks = {false};
        SettingsContextRule.withoutContext(() -> bloks[0] = MenuPanel.bloksMenu(true));
        assertTrue("before the settings are ready the page is the server's", bloks[0]);
    }
}
