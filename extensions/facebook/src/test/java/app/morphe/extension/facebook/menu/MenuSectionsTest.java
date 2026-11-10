/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.facebook.menu.MenuSectionsForTests.Group;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hooks in the two sections of every Menu group: the Upgrades and Also from Meta groups build
 * nothing while their switches are on, each by its own switch, and every other group, and every
 * group while paused or before the settings are ready, builds as Facebook built it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuSectionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        MenuSectionsForTests.newProcess();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_MENU_UPGRADES.resetToDefault();
        Settings.HIDE_MENU_ALSO_FROM_META.resetToDefault();
        Settings.HIDE_MENU_HELP.resetToDefault();
        Settings.KEEP_MENU_SETTINGS_EXPANDED.resetToDefault();
        MenuSectionsForTests.newProcess();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String line(List<String> report, String prefix) {
        for (String line : report) {
            if (line.startsWith(prefix + ":")) return line;
        }
        return null;
    }

    @Test
    public void bothSwitchesStartOnAndHideTheirGroupsInBothSections() {
        assertTrue("the Upgrades switch starts off", Settings.HIDE_MENU_UPGRADES.get());
        assertTrue("the Also from Meta switch starts off", Settings.HIDE_MENU_ALSO_FROM_META.get());
        assertTrue(MenuSectionsForTests.hidesUpgrades());
        assertTrue(MenuSectionsForTests.hidesAlsoFromMeta());
        assertTrue(MenuSectionsForTests.hidesServerUpgrades());
        assertTrue(MenuSectionsForTests.hidesServerAlsoFromMeta());
    }

    /** Settings, Log out, the shortcuts and the rest are never the patch's to hide. */
    @Test
    public void everyOtherGroupBuildsAsFacebookBuiltIt() {
        for (Group group : Group.values()) {
            if (group == Group.UPSELL || group == Group.PRODUCTS_FROM_FACEBOOK || group == Group.HELP) continue;
            assertFalse(group + " was hidden", MenuSections.hideSection(group));
            assertFalse(group + "'s server part was hidden", MenuSections.hideServerSection(group));
            assertNull(group + " has a switch", MenuSections.switchFor(group.name()));
        }
        assertSame(Settings.HIDE_MENU_UPGRADES, MenuSections.switchFor("UPSELL"));
        assertSame(Settings.HIDE_MENU_ALSO_FROM_META, MenuSections.switchFor("PRODUCTS_FROM_FACEBOOK"));
        assertSame(Settings.HIDE_MENU_HELP, MenuSections.switchFor("HELP"));
    }

    /**
     * Help and support stays until its own switch is on (issue #224), then both of its sections
     * build nothing, it's counted under its Menu title, and it waits for a restart like the others.
     */
    @Test
    public void helpAndSupportGoesOnlyByItsOwnSwitch() {
        assertFalse("the Help and support switch starts off", Settings.HIDE_MENU_HELP.get());
        assertFalse(MenuSections.hideSection(Group.HELP));
        assertFalse(MenuSections.hideServerSection(Group.HELP));

        Settings.HIDE_MENU_HELP.save(true);
        assertTrue(MenuSections.hideSection(Group.HELP));
        assertTrue(MenuSections.hideServerSection(Group.HELP));
        assertFalse("Settings and privacy went with it", MenuSections.hideSection(Group.SETTINGS));
        assertTrue("an open Menu keeps Help and support until Facebook restarts", Settings.HIDE_MENU_HELP.rebootApp);
        // The native section was asked about Help and support twice, once with the switch off.
        assertEquals(MenuSections.ROUTE + ": 3 lists, 3 items, 1 removed. Last reason: " + MenuSections.HELP_HIDDEN
                        + ". Removed: " + MenuSections.HELP_HIDDEN + " 1. Kinds: HELP 2, SETTINGS 1",
                line(FeedFilterCounters.report(), MenuSections.ROUTE));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, Help and support comes back", MenuSections.hideSection(Group.HELP));
    }

    /**
     * Settings and privacy folds up as Facebook has it until Keep Settings and privacy expanded is
     * on, then it reads as open whatever its state says (issue #224). No other group, nothing but
     * an enum, a pause or settings that aren't ready ever changes a group's own state.
     */
    @Test
    public void onlySettingsAndPrivacyIsKeptOpenAndOnlyByItsSwitch() {
        assertFalse("the switch starts off", Settings.KEEP_MENU_SETTINGS_EXPANDED.get());
        assertTrue("off, a folded Settings and privacy stays folded", MenuSections.collapsed(true, Group.SETTINGS));
        assertFalse(MenuSections.collapsed(false, Group.SETTINGS));

        Settings.KEEP_MENU_SETTINGS_EXPANDED.save(true);
        assertFalse("on, Settings and privacy is open", MenuSections.collapsed(true, Group.SETTINGS));
        assertFalse(MenuSections.collapsed(false, Group.SETTINGS));
        for (Group group : Group.values()) {
            if (group == Group.SETTINGS) continue;
            assertTrue(group + " was opened", MenuSections.collapsed(true, group));
            assertFalse(group + " was folded", MenuSections.collapsed(false, group));
        }
        assertTrue("a title isn't a group", MenuSections.collapsed(true, "SETTINGS"));
        assertTrue(MenuSections.collapsed(true, null));
        assertTrue("an open Menu keeps its state until Facebook restarts", Settings.KEEP_MENU_SETTINGS_EXPANDED.rebootApp);

        boolean[] collapsed = {false};
        SettingsContextRule.withoutContext(() -> collapsed[0] = MenuSections.collapsed(true, Group.SETTINGS));
        assertTrue("until the settings are ready, the state is Facebook's", collapsed[0]);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("paused, the state is Facebook's", MenuSections.collapsed(true, Group.SETTINGS));
    }

    /**
     * Only the Settings and privacy header is marked to stay open, and only while Keep Settings and
     * privacy expanded is on (#224 follow-up). Anything but that enum, a pause or settings that
     * aren't ready leave every header to its own state.
     */
    @Test
    public void onlyTheSettingsAndPrivacyHeaderIsKeptOpenAndOnlyByItsSwitch() {
        assertFalse("off, the header goes by its state", MenuSections.keepsHeaderOpen(Group.SETTINGS));

        Settings.KEEP_MENU_SETTINGS_EXPANDED.save(true);
        assertTrue("on, the Settings and privacy header stays open", MenuSections.keepsHeaderOpen(Group.SETTINGS));
        for (Group group : Group.values()) {
            if (group == Group.SETTINGS) continue;
            assertFalse(group + "'s header was kept open", MenuSections.keepsHeaderOpen(group));
        }
        assertFalse("a title isn't a group", MenuSections.keepsHeaderOpen("SETTINGS"));
        assertFalse(MenuSections.keepsHeaderOpen(null));

        boolean[] open = {true};
        SettingsContextRule.withoutContext(() -> open[0] = MenuSections.keepsHeaderOpen(Group.SETTINGS));
        assertFalse("until the settings are ready, the header goes by its state", open[0]);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, the header goes by its state", MenuSections.keepsHeaderOpen(Group.SETTINGS));
    }

    /**
     * A Menu built before a switch changed keeps its groups on screen even though both sections
     * ask again, so each switch brings up the restart row (#184).
     */
    @Test
    public void bothSwitchesWaitForARestart() {
        assertTrue("an open Menu keeps Upgrades until Facebook restarts", Settings.HIDE_MENU_UPGRADES.rebootApp);
        assertTrue("an open Menu keeps Also from Meta until Facebook restarts",
                Settings.HIDE_MENU_ALSO_FROM_META.rebootApp);
    }

    /** Each switch reaches its own group and leaves the other one hidden. */
    @Test
    public void eachGroupGoesByItsOwnSwitch() {
        Settings.HIDE_MENU_UPGRADES.save(false);
        assertFalse(MenuSectionsForTests.hidesUpgrades());
        assertFalse(MenuSectionsForTests.hidesServerUpgrades());
        assertTrue(MenuSectionsForTests.hidesAlsoFromMeta());
        assertTrue(MenuSectionsForTests.hidesServerAlsoFromMeta());

        Settings.HIDE_MENU_UPGRADES.save(true);
        Settings.HIDE_MENU_ALSO_FROM_META.save(false);
        assertTrue(MenuSectionsForTests.hidesUpgrades());
        assertTrue(MenuSectionsForTests.hidesServerUpgrades());
        assertFalse(MenuSectionsForTests.hidesAlsoFromMeta());
        assertFalse(MenuSectionsForTests.hidesServerAlsoFromMeta());

        Settings.HIDE_MENU_UPGRADES.save(false);
        assertFalse(MenuSectionsForTests.hidesUpgrades());
        assertFalse(MenuSectionsForTests.hidesAlsoFromMeta());
    }

    @Test
    public void pausedBothGroupsComeBack() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(MenuSectionsForTests.hidesUpgrades());
        assertFalse(MenuSectionsForTests.hidesServerAlsoFromMeta());
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(MenuSectionsForTests.hidesAlsoFromMeta());
        assertFalse(MenuSectionsForTests.hidesServerUpgrades());
        PauseForTests.resume();
        assertTrue(MenuSectionsForTests.hidesUpgrades());
        assertTrue(MenuSectionsForTests.hidesServerAlsoFromMeta());
    }

    /** Until the settings are ready, the Menu is Facebook's. */
    @Test
    public void untilTheSettingsAreReadyTheMenuIsFacebooks() {
        boolean[] hid = {true, true};
        SettingsContextRule.withoutContext(() -> {
            hid[0] = MenuSectionsForTests.hidesUpgrades();
            hid[1] = MenuSectionsForTests.hidesServerAlsoFromMeta();
        });
        assertFalse(hid[0]);
        assertFalse(hid[1]);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            hid[0] = MenuSectionsForTests.hidesAlsoFromMeta();
            hid[1] = MenuSectionsForTests.hidesServerUpgrades();
        });
        assertFalse(hid[0]);
        assertFalse(hid[1]);
        assertTrue(MenuSectionsForTests.hidesUpgrades());
    }

    /**
     * A group is known by its enum's name only. A string that reads UPSELL is a title, not a
     * group, so it's left alone, and so is nothing at all; the report names what came instead.
     */
    @Test
    public void anythingButAnEnumIsLeftAndReported() {
        assertFalse(MenuSections.hideSection("UPSELL"));
        assertFalse(MenuSections.hideServerSection(null));
        List<String> missing = HookStatus.missing(FamilyNames.MENU_PROMOTIONS);
        assertEquals(missing.toString(), 2, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("Menu group enum " + MenuSections.NATIVE_HOOK
                + "#java.lang.String"));
        assertTrue(missing.toString(), missing.get(1).contains("Menu group enum " + MenuSections.SERVER_HOOK + "#null"));
        assertNull("a non-group was counted", line(FeedFilterCounters.report(), MenuSections.ROUTE));
    }

    /**
     * Every group each section asks about is counted by its enum name, with the hidden ones under
     * their Menu titles, and each hook that read a group counts as found.
     */
    @Test
    public void theReportSaysWhatEachSectionBuiltAndWhatWent() {
        MenuSections.hideSection(Group.HELP);
        MenuSections.hideSection(Group.SETTINGS);
        MenuSectionsForTests.hidesUpgrades();
        MenuSectionsForTests.hidesAlsoFromMeta();
        MenuSectionsForTests.hidesUpgrades();
        MenuSections.hideServerSection(Group.PROFILE);
        Settings.HIDE_MENU_UPGRADES.save(false);
        MenuSectionsForTests.hidesServerUpgrades();

        List<String> counters = FeedFilterCounters.report();
        assertEquals(MenuSections.ROUTE + ": 5 lists, 5 items, 3 removed. Last reason: Upgrades. "
                + "Removed: Upgrades 2, Also from Meta 1. Kinds: UPSELL 2, HELP 1, PRODUCTS_FROM_FACEBOOK 1, SETTINGS 1",
                line(counters, MenuSections.ROUTE));
        assertEquals(MenuSections.SERVER_ROUTE + ": 2 lists, 2 items, 0 removed. Kinds: PROFILE 1, UPSELL 1",
                line(counters, MenuSections.SERVER_ROUTE));
        assertEquals(FamilyNames.MENU_PROMOTIONS + ": invoked 7, 2 found, 0 missing",
                line(HookStatus.report(), FamilyNames.MENU_PROMOTIONS));
        assertEquals("Hide Menu promotions", FamilyNames.MENU_PROMOTIONS);
    }
}
