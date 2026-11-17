/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Issue #498: Facebook's device location screen closes as it opens while Skip location prompts is
 * on, which it is from the start, and opens as Facebook meant when it's off or paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocationPromptsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SKIP_LOCATION_PROMPTS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** The screen as Facebook's link fb://device_location_optin opens it, with its extras. */
    private static Activity screen(String source, String entryPoint) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (source != null) intent.putExtra("source", source);
        if (entryPoint != null) intent.putExtra("entry_point", entryPoint);
        return Robolectric.buildActivity(Activity.class, intent).get();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(LocationPrompts.ROUTE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOnAndClosesTheScreen() {
        assertTrue("the switch doesn't start on", Settings.SKIP_LOCATION_PROMPTS.defaultValue);
        assertTrue(LocationPrompts.skips(screen("feed_upsell", "newsfeed")));
        assertTrue(LocationPrompts.skips(screen(null, null)));
        assertEquals(LocationPrompts.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: skipped. Removed: skipped 2. "
                + "Kinds: feed_upsell/newsfeed 1, unknown 1", counterLine());
    }

    @Test
    public void offTheScreenOpens() {
        Settings.SKIP_LOCATION_PROMPTS.save(false);
        assertFalse(LocationPrompts.skips(screen("marketplace", "location_picker")));
        assertEquals(LocationPrompts.ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: marketplace/location_picker 1",
                counterLine());
    }

    @Test
    public void pausedTheScreenOpens() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(why + " closed the screen", LocationPrompts.skips(screen("feed_upsell", "newsfeed")));
        }
        PauseForTests.resume();
        assertTrue("the switch didn't come back after the pause", LocationPrompts.skips(screen("feed_upsell", "newsfeed")));
    }

    @Test
    public void theSourceComesFromTheLinkWhenTheExtrasDontCarryIt() {
        Intent link = new Intent(Intent.ACTION_VIEW,
                Uri.parse("fb://device_location_optin?source=hush_probe&entry_point=deep_link"));
        assertEquals("hush_probe/deep_link", LocationPrompts.openedFrom(Robolectric.buildActivity(Activity.class, link).get()));
        assertEquals("feed_upsell/unknown", LocationPrompts.openedFrom(screen("feed_upsell", null)));
        assertEquals("unknown", LocationPrompts.openedFrom(null));
    }
}
