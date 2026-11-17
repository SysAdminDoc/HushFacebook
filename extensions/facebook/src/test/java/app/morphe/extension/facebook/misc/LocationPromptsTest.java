/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Issue #498: Facebook's location page, a promotion shown after sign-in, fails its check while Skip
 * location prompts is on, which it is from the start, and passes as Facebook meant when it's off or
 * paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocationPromptsTest {
    /** The trigger the reported page came with, as Facebook's InterstitialTrigger names itself. */
    private static final String LOGIN = "FACEBOOK_FOR_ANDROID_LOGIN_INTERSTITIAL_QP";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SKIP_LOCATION_PROMPTS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** Stands in for Facebook's trigger, whose toString is its action name. */
    private static Object trigger(String name) {
        return new Object() {
            @Override
            public String toString() {
                return name;
            }
        };
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(LocationPrompts.ROUTE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOnAndThePageDoesntShow() {
        assertTrue("the switch doesn't start on", Settings.SKIP_LOCATION_PROMPTS.defaultValue);
        assertTrue(LocationPrompts.skips(trigger(LOGIN)));
        assertTrue(LocationPrompts.skips(null));
        assertEquals(LocationPrompts.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: skipped. Removed: skipped 2. "
                + "Kinds: " + LOGIN + " 1, unknown 1", counterLine());
    }

    @Test
    public void offThePageShows() {
        Settings.SKIP_LOCATION_PROMPTS.save(false);
        assertFalse(LocationPrompts.skips(trigger(LOGIN)));
        assertEquals(LocationPrompts.ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: " + LOGIN + " 1", counterLine());
    }

    @Test
    public void pausedThePageShows() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(why + " kept the page from showing", LocationPrompts.skips(trigger(LOGIN)));
        }
        PauseForTests.resume();
        assertTrue("the switch didn't come back after the pause", LocationPrompts.skips(trigger(LOGIN)));
    }

    @Test
    public void aTriggerThatThrowsLeavesFacebooksCheckAlone() {
        Object broken = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("no name");
            }
        };
        assertFalse(LocationPrompts.skips(broken));
    }

    @Test
    public void theTriggerNameIsKeptShort() {
        assertEquals(LOGIN, LocationPrompts.triggerName(trigger(LOGIN)));
        assertEquals("NEWSFEED:extra", LocationPrompts.triggerName(trigger(" NEWSFEED:extra ")));
        assertEquals("unknown", LocationPrompts.triggerName(trigger("  ")));
        assertEquals("unknown", LocationPrompts.triggerName(null));
        StringBuilder longName = new StringBuilder();
        while (longName.length() < 120) longName.append('X');
        assertEquals(80, LocationPrompts.triggerName(trigger(longName.toString())).length());
    }
}
