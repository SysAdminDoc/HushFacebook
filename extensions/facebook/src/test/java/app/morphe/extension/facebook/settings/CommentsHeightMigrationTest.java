/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.comments.CommentsHeight;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.FailingStore;
import app.morphe.extension.shared.settings.Setting;

/**
 * The upgrade from the Open comments at full height switch to the Open comments at list (#179): a
 * phone with the switch on opens comments at full height and one with it off stays at Facebook's
 * height, in one write that retires the switch's key, and a failed write keeps the old choice
 * readable until a later start or save finishes it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class CommentsHeightMigrationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void clearChoice() {
        Settings.COMMENTS_HEIGHT.get();
        store().edit().remove(CommentsHeightSetting.LEGACY_KEY).remove(CommentsHeightSetting.KEY).commit();
        reload();
    }

    @After public void resetChoice() {
        store().edit().remove(CommentsHeightSetting.LEGACY_KEY).commit();
        Settings.COMMENTS_HEIGHT.resetToDefault();
    }

    @Test public void aFreshInstallNeedsNoMigrationWrite() {
        try (FailingStore counted = FailingStore.install()) {
            CommentsHeightSetting.finishMigration();
            assertEquals(0, counted.editors.get());
            assertEquals(CommentsHeight.FACEBOOK, Settings.COMMENTS_HEIGHT.defaultValue);
            assertEquals(CommentsHeight.FACEBOOK, Settings.COMMENTS_HEIGHT.savedValue());
        }
    }

    @Test public void theOldSwitchOnBecomesFullHeightAndOffFacebooksInOneCommit() {
        for (boolean on : new boolean[]{true, false}) {
            store().edit().remove(CommentsHeightSetting.KEY).putBoolean(CommentsHeightSetting.LEGACY_KEY, on).commit();
            reload();
            CommentsHeight expected = on ? CommentsHeight.FULL : CommentsHeight.FACEBOOK;
            assertEquals("before the write, the old switch reads", expected, Settings.COMMENTS_HEIGHT.savedValue());
            try (FailingStore counted = FailingStore.install()) {
                CommentsHeightSetting.finishMigration();
                assertEquals(1, counted.editors.get());
                assertFalse(store().contains(CommentsHeightSetting.LEGACY_KEY));
                assertEquals(on, store().contains(CommentsHeightSetting.KEY));
                reload();
                assertEquals(expected, Settings.COMMENTS_HEIGHT.savedValue());
                CommentsHeightSetting.finishMigration();
                assertEquals("migration ran twice", 1, counted.editors.get());
            }
        }
    }

    @Test public void aListValueAlreadySavedWinsOverTheOldSwitch() {
        store().edit().putString(CommentsHeightSetting.KEY, CommentsHeight.HALF.name())
                .putBoolean(CommentsHeightSetting.LEGACY_KEY, true).commit();
        reload();
        assertEquals(CommentsHeight.HALF, Settings.COMMENTS_HEIGHT.savedValue());
        CommentsHeightSetting.finishMigration();
        assertFalse(store().contains(CommentsHeightSetting.LEGACY_KEY));
        reload();
        assertEquals(CommentsHeight.HALF, Settings.COMMENTS_HEIGHT.savedValue());
    }

    @Test public void choosingFacebooksHeightAfterTheUpgradeNeverBringsFullHeightBack() {
        store().edit().putBoolean(CommentsHeightSetting.LEGACY_KEY, true).commit();
        CommentsHeightSetting.finishMigration();
        reload();
        assertTrue(Settings.COMMENTS_HEIGHT.save(CommentsHeight.FACEBOOK));
        assertFalse(store().contains(CommentsHeightSetting.KEY));
        CommentsHeightSetting.finishMigration();
        reload();
        assertEquals(CommentsHeight.FACEBOOK, Settings.COMMENTS_HEIGHT.savedValue());
    }

    @Test public void aFailedStartupWriteKeepsTheOldChoiceAndCanBeRetried() {
        for (FailingStore.Fault fault : new FailingStore.Fault[]{FailingStore.Fault.EDIT_THROWS,
                FailingStore.Fault.STAGE_THROWS, FailingStore.Fault.LOST, FailingStore.Fault.COMMIT_THROWS,
                FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING}) {
            clearChoice();
            store().edit().putBoolean(CommentsHeightSetting.LEGACY_KEY, true).commit();
            try (FailingStore failed = FailingStore.install(fault)) {
                CommentsHeightSetting.migrate();
                reload();
                assertEquals(fault.name(), CommentsHeight.FULL, Settings.COMMENTS_HEIGHT.savedValue());
            }
            CommentsHeightSetting.finishMigration();
            assertFalse(fault.name(), store().contains(CommentsHeightSetting.LEGACY_KEY));
            reload();
            assertEquals(fault.name(), CommentsHeight.FULL, Settings.COMMENTS_HEIGHT.savedValue());
        }
    }

    @Test public void aLaterSaveFinishesAFailedMigration() {
        store().edit().putBoolean(CommentsHeightSetting.LEGACY_KEY, true).commit();
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.LOST)) {
            CommentsHeightSetting.migrate();
        }
        reload();
        assertTrue(store().contains(CommentsHeightSetting.LEGACY_KEY));
        assertTrue(Settings.COMMENTS_HEIGHT.save(CommentsHeight.HALF));
        assertFalse(store().contains(CommentsHeightSetting.LEGACY_KEY));
        reload();
        assertEquals(CommentsHeight.HALF, Settings.COMMENTS_HEIGHT.savedValue());
    }

    @Test public void aSecondaryProcessReadsTheOldSwitchWithoutWritingIt() {
        store().edit().putBoolean(CommentsHeightSetting.LEGACY_KEY, true).commit();
        var app = RuntimeEnvironment.getApplication().getApplicationInfo();
        String previous = app.processName;
        app.processName = RuntimeEnvironment.getApplication().getPackageName() + ":secondary";
        try (FailingStore counted = FailingStore.install()) {
            CommentsHeightSetting.finishMigration();
            reload();
            assertEquals(0, counted.editors.get());
            assertTrue(store().contains(CommentsHeightSetting.LEGACY_KEY));
            assertEquals(CommentsHeight.FULL, Settings.COMMENTS_HEIGHT.savedValue());
        } finally {
            app.processName = previous;
        }
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    private static void reload() {
        ((CommentsHeightSetting) Settings.COMMENTS_HEIGHT).load();
    }
}
