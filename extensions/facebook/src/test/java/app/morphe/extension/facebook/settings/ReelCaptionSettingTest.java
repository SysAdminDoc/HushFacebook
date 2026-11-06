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

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.Setting;

/**
 * Show full reel captions split off Show full post text (#398). Someone who had that switch on keeps
 * whole reel captions after the update, and from then on the two switches don't move each other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class ReelCaptionSettingTest {
    private static final String REEL_KEY = Settings.SHOW_FULL_REEL_CAPTIONS.key;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void clear() {
        Settings.SHOW_FULL_REEL_CAPTIONS.resetToDefault();
        Settings.SHOW_FULL_POST_TEXT.resetToDefault();
        store().edit().remove(ReelCaptionSetting.FEED_KEY).remove(REEL_KEY).commit();
        reload();
    }

    @After
    public void restore() {
        clear();
    }

    /** An update from a build where Show full post text was on, before the reel switch existed. */
    private static void updateWithTheOldSwitchOn() {
        assertTrue(Settings.SHOW_FULL_POST_TEXT.save(true));
        store().edit().remove(REEL_KEY).commit();
        reload();
    }

    @Test
    public void aFreshInstallStartsOffAndWritesNothing() {
        assertFalse(Settings.SHOW_FULL_REEL_CAPTIONS.savedValue());
        assertFalse("a fresh install stored the reel switch", store().contains(REEL_KEY));
    }

    @Test
    public void theOldSwitchOnCarriesOverOnceAndStaysApart() {
        updateWithTheOldSwitchOn();
        assertTrue("whole reel captions were lost in the split", Settings.SHOW_FULL_REEL_CAPTIONS.savedValue());
        assertTrue("the carried value wasn't stored as the reel switch's own", store().getBoolean(REEL_KEY, false));

        // Turning the feed switch off afterwards leaves reel captions as they are.
        assertTrue(Settings.SHOW_FULL_POST_TEXT.save(false));
        reload();
        assertTrue("the feed switch still moves the reel switch", Settings.SHOW_FULL_REEL_CAPTIONS.savedValue());
    }

    @Test
    public void turningReelCaptionsOffSticksWhileTheFeedSwitchStaysOn() {
        updateWithTheOldSwitchOn();
        assertTrue(Settings.SHOW_FULL_REEL_CAPTIONS.save(false));
        assertTrue("off wasn't stored, so the next start would carry the feed switch over again",
                store().contains(REEL_KEY));
        reload();
        assertFalse("reel captions came back on after a restart", Settings.SHOW_FULL_REEL_CAPTIONS.savedValue());
        assertTrue(Settings.SHOW_FULL_POST_TEXT.savedValue());
    }

    @Test
    public void theOldSwitchOffCarriesNothing() {
        store().edit().putBoolean(ReelCaptionSetting.FEED_KEY, false).commit();
        reload();
        assertFalse(Settings.SHOW_FULL_REEL_CAPTIONS.savedValue());
        assertFalse(store().contains(REEL_KEY));
    }

    @Test
    public void aSecondaryProcessReadsTheOldSwitchWithoutWriting() {
        updateWithTheOldSwitchOn();
        store().edit().remove(REEL_KEY).commit();
        var app = RuntimeEnvironment.getApplication().getApplicationInfo();
        String previous = app.processName;
        app.processName = RuntimeEnvironment.getApplication().getPackageName() + ":secondary";
        try {
            reload();
            assertTrue(Settings.SHOW_FULL_REEL_CAPTIONS.savedValue());
            assertFalse("a secondary process stored the reel switch", store().contains(REEL_KEY));
        } finally {
            app.processName = previous;
        }
        reload();
        assertEquals(true, store().getBoolean(REEL_KEY, false));
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    private static void reload() {
        ((ReelCaptionSetting) Settings.SHOW_FULL_REEL_CAPTIONS).load();
    }
}
