/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.Setting;

/**
 * Hide the Subscribe link on posts split off the Follow switch (#434). Someone who had that switch
 * on keeps the Subscribe link hidden after the update, and from then on the two switches don't move
 * each other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class SubscribeLinkSettingTest {
    /** Read once a test has a context: Settings can't load before the rule gives it one. */
    private static String subscribeKey() {
        return Settings.HIDE_POST_SUBSCRIBE_LINK.key;
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void clear() {
        Settings.HIDE_POST_SUBSCRIBE_LINK.resetToDefault();
        Settings.HIDE_POST_FOLLOW_LINK.resetToDefault();
        store().edit().remove(SubscribeLinkSetting.FOLLOW_KEY).remove(subscribeKey()).commit();
        reload();
    }

    @After
    public void restore() {
        clear();
    }

    /** An update from a build where the one Follow and Subscribe switch was on, before the split. */
    private static void updateWithTheOldSwitchOn() {
        assertTrue(Settings.HIDE_POST_FOLLOW_LINK.save(true));
        store().edit().remove(subscribeKey()).commit();
        reload();
    }

    @Test
    public void theFollowSwitchKeepsItsKey() {
        assertEquals(SubscribeLinkSetting.FOLLOW_KEY, Settings.HIDE_POST_FOLLOW_LINK.key);
        assertFalse(Settings.HIDE_POST_FOLLOW_LINK.defaultValue);
    }

    @Test
    public void aFreshInstallStartsOffAndWritesNothing() {
        assertFalse(Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
        assertFalse("a fresh install stored the Subscribe switch", store().contains(subscribeKey()));
    }

    @Test
    public void theOldSwitchOnCarriesOverOnceAndStaysApart() {
        updateWithTheOldSwitchOn();
        assertTrue("the hidden Subscribe link came back in the split", Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
        assertTrue("the carried value wasn't stored as the Subscribe switch's own", store().getBoolean(subscribeKey(), false));
        assertTrue("the Follow switch lost its value", Settings.HIDE_POST_FOLLOW_LINK.savedValue());

        // Turning the Follow switch off afterwards leaves the Subscribe link hidden.
        assertTrue(Settings.HIDE_POST_FOLLOW_LINK.save(false));
        reload();
        assertTrue("the Follow switch still moves the Subscribe switch", Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
    }

    @Test
    public void turningSubscribeOffSticksWhileTheFollowSwitchStaysOn() {
        updateWithTheOldSwitchOn();
        assertTrue(Settings.HIDE_POST_SUBSCRIBE_LINK.save(false));
        assertTrue("off wasn't stored, so the next start would carry the Follow switch over again",
                store().contains(subscribeKey()));
        reload();
        assertFalse("the Subscribe switch came back on after a restart", Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
        assertTrue(Settings.HIDE_POST_FOLLOW_LINK.savedValue());
    }

    @Test
    public void theOldSwitchOffCarriesNothing() {
        store().edit().putBoolean(SubscribeLinkSetting.FOLLOW_KEY, false).commit();
        reload();
        assertFalse(Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
        assertFalse(store().contains(subscribeKey()));
    }

    @Test
    public void aSecondaryProcessReadsTheOldSwitchWithoutWriting() {
        updateWithTheOldSwitchOn();
        store().edit().remove(subscribeKey()).commit();
        var app = RuntimeEnvironment.getApplication().getApplicationInfo();
        String previous = app.processName;
        app.processName = RuntimeEnvironment.getApplication().getPackageName() + ":secondary";
        try {
            reload();
            assertTrue(Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
            assertFalse("a secondary process stored the Subscribe switch", store().contains(subscribeKey()));
        } finally {
            app.processName = previous;
        }
        reload();
        assertEquals(true, store().getBoolean(subscribeKey(), false));
    }

    /** A file saved before the split has the Follow key only, which hid both links then. */
    @Test
    public void aFileFromBeforeTheSplitHidesBothLinks() throws Exception {
        assertEquals(2, SettingsBackup.apply(SettingsBackup.parse(file(
                new JSONObject().put(SubscribeLinkSetting.FOLLOW_KEY, true)))));
        assertTrue(Settings.HIDE_POST_FOLLOW_LINK.savedValue());
        assertTrue("the old file's hidden Subscribe link came back", Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
    }

    /** A file written since carries both keys, and its own Subscribe key wins whatever the order. */
    @Test
    public void aFileFromAfterTheSplitKeepsItsOwnSubscribeKey() throws Exception {
        SettingsBackup.apply(SettingsBackup.parse(file(new JSONObject()
                .put(subscribeKey(), false).put(SubscribeLinkSetting.FOLLOW_KEY, true))));
        assertTrue(Settings.HIDE_POST_FOLLOW_LINK.savedValue());
        assertFalse("the Follow key moved the file's own Subscribe key", Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());

        SettingsBackup.apply(SettingsBackup.parse(file(new JSONObject()
                .put(SubscribeLinkSetting.FOLLOW_KEY, false).put(subscribeKey(), true))));
        assertFalse(Settings.HIDE_POST_FOLLOW_LINK.savedValue());
        assertTrue(Settings.HIDE_POST_SUBSCRIBE_LINK.savedValue());
    }

    private static String file(JSONObject values) throws Exception {
        return new JSONObject().put("format", SettingsBackup.FORMAT).put("schema", 1).put("settings", values).toString();
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    private static void reload() {
        ((SubscribeLinkSetting) Settings.HIDE_POST_SUBSCRIBE_LINK).load();
    }
}
