/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** Double tap to skip (#225): when Facebook's own double-tap skip test is answered on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DoubleTapSkipTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        HookStatus.clear();
        PauseForTests.resume();
        Settings.DOUBLE_TAP_SKIP.resetToDefault();
        Settings.HIDE_SKIP_SECONDS.resetToDefault();
    }

    @Test
    public void theSwitchStartsOffAndLeavesFacebooksAnswer() {
        assertFalse(DoubleTapSkip.flag(false));
        assertTrue("an account Facebook already gave the skip keeps it", DoubleTapSkip.flag(true));
    }

    @Test
    public void onTurnsTheSkipOn() {
        Settings.DOUBLE_TAP_SKIP.save(true);
        assertTrue(DoubleTapSkip.flag(false));
        assertTrue(DoubleTapSkip.flag(true));
    }

    @Test
    public void pausedLeavesFacebooksAnswer() {
        Settings.DOUBLE_TAP_SKIP.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(DoubleTapSkip.flag(false));
        assertTrue(DoubleTapSkip.flag(true));
    }

    /** The "+5s" label as Facebook makes it: hidden until a skip shows it. */
    private static TextView label() {
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        label.setVisibility(View.GONE);
        return label;
    }

    @Test
    public void theSecondsShowAsFacebookAsksWithTheSwitchOff() {
        TextView label = label();
        DoubleTapSkip.label(label, View.VISIBLE);
        assertEquals(View.VISIBLE, label.getVisibility());
        DoubleTapSkip.label(label, View.GONE);
        assertEquals(View.GONE, label.getVisibility());
    }

    @Test
    public void onKeepsTheSecondsHiddenWhetherOrNotTheSkipSwitchIsOn() {
        Settings.HIDE_SKIP_SECONDS.save(true);
        TextView label = label();
        DoubleTapSkip.label(label, View.VISIBLE);
        assertEquals("Facebook's own skip showed its seconds", View.GONE, label.getVisibility());

        Settings.DOUBLE_TAP_SKIP.save(true);
        DoubleTapSkip.label(label, View.VISIBLE);
        assertEquals("the switched-on skip showed its seconds", View.GONE, label.getVisibility());
        assertTrue("the skip itself stopped", DoubleTapSkip.flag(false));
    }

    @Test
    public void onStillHidesWhatFacebookHides() {
        Settings.HIDE_SKIP_SECONDS.save(true);
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        DoubleTapSkip.label(label, View.INVISIBLE);
        assertEquals(View.INVISIBLE, label.getVisibility());
    }

    @Test
    public void pausedShowsTheSeconds() {
        Settings.HIDE_SKIP_SECONDS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        TextView label = label();
        DoubleTapSkip.label(label, View.VISIBLE);
        assertEquals(View.VISIBLE, label.getVisibility());
    }
}
