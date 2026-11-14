/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.SharedPreferences;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.FailingStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Hold a post to react split into Hold a feed photo to react and Hold a feed video to react (#454):
 * whoever had the old switch on finds both on, once, and whoever had it off finds both off. A settings
 * file from before the split does the same.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class HoldReactSettingMigrationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void clearChoices() {
        Settings.HOLD_PHOTO_TO_REACT.get();
        store().edit().remove(HoldReactSetting.LEGACY_KEY).remove(HoldReactSetting.PHOTO_KEY)
                .remove(HoldReactSetting.VIDEO_KEY).commit();
        reload();
    }

    @After public void resetChoices() {
        store().edit().remove(HoldReactSetting.LEGACY_KEY).commit();
        Settings.HOLD_PHOTO_TO_REACT.resetToDefault();
        Settings.HOLD_VIDEO_TO_REACT.resetToDefault();
    }

    @Test public void bothStartOffAndFreshDefaultsNeedNoMigrationWrite() {
        try (FailingStore counted = FailingStore.install()) {
            HoldReactSetting.finishMigration();
            assertEquals(0, counted.editors.get());
            assertFalse(Settings.HOLD_PHOTO_TO_REACT.defaultValue);
            assertFalse(Settings.HOLD_VIDEO_TO_REACT.defaultValue);
            assertFalse(Settings.HOLD_PHOTO_TO_REACT.savedValue());
            assertFalse(Settings.HOLD_VIDEO_TO_REACT.savedValue());
        }
    }

    @Test public void theOldSwitchOnTurnsBothOnInOneCommitAndOnlyOnce() {
        store().edit().putBoolean(HoldReactSetting.LEGACY_KEY, true).commit();
        try (FailingStore counted = FailingStore.install()) {
            HoldReactSetting.finishMigration();
            assertEquals(1, counted.editors.get());
            assertFalse(store().contains(HoldReactSetting.LEGACY_KEY));
            assertTrue(store().getBoolean(HoldReactSetting.PHOTO_KEY, false));
            assertTrue(store().getBoolean(HoldReactSetting.VIDEO_KEY, false));
            reload();
            assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
            assertTrue(Settings.HOLD_VIDEO_TO_REACT.savedValue());
            HoldReactSetting.finishMigration();
            assertEquals("migration ran twice", 1, counted.editors.get());
        }
    }

    @Test public void theOldSwitchOffLeavesBothOffWithNoKeyOfTheirOwn() {
        store().edit().putBoolean(HoldReactSetting.LEGACY_KEY, false).commit();
        HoldReactSetting.finishMigration();
        assertFalse(store().contains(HoldReactSetting.LEGACY_KEY));
        assertFalse(store().contains(HoldReactSetting.PHOTO_KEY));
        assertFalse(store().contains(HoldReactSetting.VIDEO_KEY));
        reload();
        assertFalse(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertFalse(Settings.HOLD_VIDEO_TO_REACT.savedValue());
    }

    @Test public void aNewChoiceAlreadyStoredWinsOverTheOldSwitch() {
        for (String existing : new String[]{HoldReactSetting.PHOTO_KEY, HoldReactSetting.VIDEO_KEY}) {
            String missing = existing.equals(HoldReactSetting.PHOTO_KEY) ? HoldReactSetting.VIDEO_KEY : HoldReactSetting.PHOTO_KEY;
            store().edit().putBoolean(HoldReactSetting.LEGACY_KEY, true).putBoolean(existing, false)
                    .remove(missing).commit();
            HoldReactSetting.finishMigration();
            assertFalse(store().getBoolean(existing, true));
            assertTrue(store().getBoolean(missing, false));
            assertFalse(store().contains(HoldReactSetting.LEGACY_KEY));
        }
    }

    @Test public void turningOneOffAfterwardsNeverBringsTheOldOnBack() {
        store().edit().putBoolean(HoldReactSetting.LEGACY_KEY, true).commit();
        HoldReactSetting.finishMigration();
        reload();
        assertTrue(Settings.HOLD_VIDEO_TO_REACT.save(false));
        assertFalse(store().contains(HoldReactSetting.VIDEO_KEY));
        HoldReactSetting.finishMigration();
        reload();
        assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertFalse(Settings.HOLD_VIDEO_TO_REACT.savedValue());
    }

    @Test public void failedStartupWritesKeepTheOldChoiceAndCanBeRetried() {
        for (FailingStore.Fault fault : new FailingStore.Fault[]{FailingStore.Fault.EDIT_THROWS,
                FailingStore.Fault.STAGE_THROWS, FailingStore.Fault.LOST, FailingStore.Fault.COMMIT_THROWS,
                FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING}) {
            clearChoices();
            store().edit().putBoolean(HoldReactSetting.LEGACY_KEY, true).commit();
            try (FailingStore failed = FailingStore.install(fault)) {
                HoldReactSetting.migrate();
                reload();
                assertTrue(fault.name(), Settings.HOLD_PHOTO_TO_REACT.savedValue());
                assertTrue(fault.name(), Settings.HOLD_VIDEO_TO_REACT.savedValue());
            }
            HoldReactSetting.finishMigration();
            assertFalse(fault.name(), store().contains(HoldReactSetting.LEGACY_KEY));
            reload();
            assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
            assertTrue(Settings.HOLD_VIDEO_TO_REACT.savedValue());
        }
    }

    @Test public void aLaterSaveOfOneFinishesAFailedMigrationWithoutLosingTheOther() {
        leaveFailedMigration();
        assertTrue(Settings.HOLD_PHOTO_TO_REACT.save(false));
        assertFalse(store().contains(HoldReactSetting.LEGACY_KEY));
        reload();
        assertFalse(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertTrue(Settings.HOLD_VIDEO_TO_REACT.savedValue());
    }

    @Test public void theSwitchRowsOwnDefaultCleanupAlsoFinishesAFailedMigration() {
        leaveFailedMigration();
        // Android's Preference stores the selected value before the framework updates Setting.
        store().edit().putBoolean(HoldReactSetting.VIDEO_KEY, false).commit();
        BooleanSetting.privateSetValue(Settings.HOLD_VIDEO_TO_REACT, false);
        assertFalse(store().contains(HoldReactSetting.LEGACY_KEY));
        assertFalse(store().contains(HoldReactSetting.VIDEO_KEY));
        reload();
        assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertFalse(Settings.HOLD_VIDEO_TO_REACT.savedValue());
    }

    @Test public void aFailedSaveOfOneKeepsBothAsTheOldSwitchHadThem() {
        for (FailingStore.Fault fault : new FailingStore.Fault[]{FailingStore.Fault.EDIT_THROWS,
                FailingStore.Fault.STAGE_THROWS, FailingStore.Fault.LOST, FailingStore.Fault.COMMIT_THROWS,
                FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING}) {
            clearChoices();
            leaveFailedMigration();
            try (FailingStore failed = FailingStore.install(fault)) {
                assertFalse(fault.name(), Settings.HOLD_PHOTO_TO_REACT.save(false));
            }
            reload();
            assertTrue(fault.name(), Settings.HOLD_PHOTO_TO_REACT.savedValue());
            assertTrue(fault.name(), Settings.HOLD_VIDEO_TO_REACT.savedValue());
        }
    }

    @Test public void aSecondaryProcessReadsTheOldSwitchWithoutWritingIt() {
        store().edit().putBoolean(HoldReactSetting.LEGACY_KEY, true).commit();
        var app = RuntimeEnvironment.getApplication().getApplicationInfo();
        String previous = app.processName;
        app.processName = RuntimeEnvironment.getApplication().getPackageName() + ":secondary";
        try (FailingStore counted = FailingStore.install()) {
            HoldReactSetting.finishMigration();
            reload();
            assertEquals(0, counted.editors.get());
            assertTrue(store().contains(HoldReactSetting.LEGACY_KEY));
            assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
            assertTrue(Settings.HOLD_VIDEO_TO_REACT.savedValue());
            assertFalse(Settings.HOLD_PHOTO_TO_REACT.save(false));
        } finally {
            app.processName = previous;
        }
    }

    @Test public void aSparseImportFinishesAFailedMigrationWithoutLosingTheOther() throws Exception {
        leaveFailedMigration();
        JSONObject values = new JSONObject().put(HoldReactSetting.VIDEO_KEY, false);
        assertEquals(1, SettingsBackup.apply(SettingsBackup.parse(file(values))));
        assertFalse(store().contains(HoldReactSetting.LEGACY_KEY));
        reload();
        assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertFalse(Settings.HOLD_VIDEO_TO_REACT.savedValue());
    }

    /** A file saved before the split carries the old key, which sets both, and a new key in it wins whatever the order. */
    @Test public void aFileFromBeforeTheSplitSetsBoth() throws Exception {
        assertEquals(2, SettingsBackup.apply(SettingsBackup.parse(file(
                new JSONObject().put(HoldReactSetting.LEGACY_KEY, true)))));
        assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertTrue(Settings.HOLD_VIDEO_TO_REACT.savedValue());

        Settings.HOLD_PHOTO_TO_REACT.save(false);
        Settings.HOLD_VIDEO_TO_REACT.save(false);
        SettingsBackup.apply(SettingsBackup.parse(file(new JSONObject().put(HoldReactSetting.VIDEO_KEY, false)
                .put(HoldReactSetting.LEGACY_KEY, true))));
        assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertFalse("the file's own video key wins", Settings.HOLD_VIDEO_TO_REACT.savedValue());
        assertFalse("the old key is never stored again", store().contains(HoldReactSetting.LEGACY_KEY));

        JSONObject refused = new JSONObject().put(HoldReactSetting.LEGACY_KEY, "yes");
        try {
            SettingsBackup.parse(file(refused));
            fail("an old key that isn't true or false came in");
        } catch (SettingsBackup.Rejected expected) {
            assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        }
    }

    private static String file(JSONObject values) throws Exception {
        return new JSONObject().put("format", SettingsBackup.FORMAT).put("schema", 1).put("settings", values).toString();
    }

    /** The old switch started off, so a stored on is the one that differs from the defaults. */
    private static void leaveFailedMigration() {
        store().edit().putBoolean(HoldReactSetting.LEGACY_KEY, true).commit();
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.LOST)) {
            HoldReactSetting.migrate();
        }
        reload();
        assertTrue(store().contains(HoldReactSetting.LEGACY_KEY));
        assertTrue(Settings.HOLD_PHOTO_TO_REACT.savedValue());
        assertTrue(Settings.HOLD_VIDEO_TO_REACT.savedValue());
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    private static void reload() {
        ((HoldReactSetting) Settings.HOLD_PHOTO_TO_REACT).load();
        ((HoldReactSetting) Settings.HOLD_VIDEO_TO_REACT).load();
    }
}
