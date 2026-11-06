/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide suggested groups on the Groups tab (#347): with the switch on, as it starts, the suggested
 * groups card lays out as nothing; off, paused or before the settings are ready it's Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SuggestedGroupsCardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_GROUPS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_POSTS + ":")) return line;
        }
        return "";
    }

    @Test
    public void theSwitchStartsOnAndTakesTheCardOut() {
        assertTrue(Settings.HIDE_SUGGESTED_GROUPS.defaultValue);
        assertTrue(SuggestedGroupsCard.hide());
        String line = statusLine();
        assertTrue(line, line.contains(SuggestedGroupsCard.HIDDEN + " 1"));
    }

    @Test
    public void offKeepsTheCard() {
        Settings.HIDE_SUGGESTED_GROUPS.save(false);
        assertFalse(SuggestedGroupsCard.hide());
        assertFalse(statusLine().contains(SuggestedGroupsCard.HIDDEN));
    }

    @Test
    public void pausedKeepsTheCard() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(SuggestedGroupsCard.hide());
        PauseForTests.resume();
        assertTrue(SuggestedGroupsCard.hide());
    }

    @Test
    public void beforeTheSettingsAreReadyKeepsTheCard() {
        SettingsContextRule.withoutContext(() -> assertFalse(SuggestedGroupsCard.hide()));
    }

    @Test
    public void theGroupsTabsOwnRowGoesWithTheSwitchAndCountsApart() {
        assertTrue(SuggestedGroupsCard.hideDiscoverRow());
        String line = statusLine();
        assertTrue(line, line.contains(SuggestedGroupsCard.DISCOVER_ROW_HIDDEN + " 1"));
        assertFalse(line, line.contains(SuggestedGroupsCard.HIDDEN));
    }

    @Test
    public void theGroupsTabsOwnRowStaysWhenOffOrPaused() {
        Settings.HIDE_SUGGESTED_GROUPS.save(false);
        assertFalse(SuggestedGroupsCard.hideDiscoverRow());
        Settings.HIDE_SUGGESTED_GROUPS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(SuggestedGroupsCard.hideDiscoverRow());
        PauseForTests.resume();
        assertFalse(statusLine().contains(SuggestedGroupsCard.DISCOVER_ROW_HIDDEN));
        SettingsContextRule.withoutContext(() -> assertFalse(SuggestedGroupsCard.hideDiscoverRow()));
    }
}
