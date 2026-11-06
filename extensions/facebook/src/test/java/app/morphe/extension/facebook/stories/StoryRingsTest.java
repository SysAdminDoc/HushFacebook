/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

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

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide story rings (#397): each switch takes the ring off only its own place, and off, paused or
 * before the settings are ready, every picture keeps the ring Facebook gave it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryRingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A stand-in for Facebook's ring kinds, which keep their names in the app. */
    private enum Ring { LIVE, STORY_SEEN, STORY_UNSEEN }

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_FEED_STORY_RINGS.resetToDefault();
        Settings.HIDE_COMMENT_STORY_RINGS.resetToDefault();
        Settings.HIDE_PROFILE_STORY_RINGS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_RINGS + ":")) return line;
        }
        return null;
    }

    @Test
    public void theThreeSwitchesStartOffAndBelongToTheirPatch() {
        assertFalse(Settings.HIDE_FEED_STORY_RINGS.defaultValue);
        assertFalse(Settings.HIDE_COMMENT_STORY_RINGS.defaultValue);
        assertFalse(Settings.HIDE_PROFILE_STORY_RINGS.defaultValue);
        assertTrue(PatchFamily.STORY_RINGS.switches.contains(Settings.HIDE_FEED_STORY_RINGS));
        assertTrue(PatchFamily.STORY_RINGS.switches.contains(Settings.HIDE_COMMENT_STORY_RINGS));
        assertTrue(PatchFamily.STORY_RINGS.switches.contains(Settings.HIDE_PROFILE_STORY_RINGS));
    }

    @Test
    public void eachSwitchTakesTheRingOffOnlyItsOwnPlace() {
        Settings.HIDE_FEED_STORY_RINGS.save(true);
        assertFalse("the feed kept its ring", StoryRings.feedRing(1));
        assertFalse("the feed switch hid a comment ring", StoryRings.hideCommentRing());
        assertSame("the feed switch hid a profile ring", Ring.STORY_UNSEEN, StoryRings.profileRing(Ring.STORY_UNSEEN));

        Settings.HIDE_FEED_STORY_RINGS.save(false);
        Settings.HIDE_COMMENT_STORY_RINGS.save(true);
        assertTrue("the comments switch hid a feed ring", StoryRings.feedRing(1));
        assertTrue("a comment kept its ring", StoryRings.hideCommentRing());
        assertSame("the comments switch hid a profile ring", Ring.LIVE, StoryRings.profileRing(Ring.LIVE));

        Settings.HIDE_COMMENT_STORY_RINGS.save(false);
        Settings.HIDE_PROFILE_STORY_RINGS.save(true);
        assertTrue("the profiles switch hid a feed ring", StoryRings.feedRing(1));
        assertFalse("the profiles switch hid a comment ring", StoryRings.hideCommentRing());
        assertNull("a profile kept its ring", StoryRings.profileRing(Ring.LIVE));

        String line = statusLine();
        assertTrue(line, line.endsWith("Counted: " + StoryRings.FEED_RING_HIDDEN + " 1, "
                + StoryRings.COMMENT_RING_HIDDEN + " 1, " + StoryRings.PROFILE_RING_HIDDEN + " 1"));
    }

    @Test
    public void aPictureWithNoRingStaysWithoutOneAndIsntCounted() {
        Settings.HIDE_FEED_STORY_RINGS.save(true);
        Settings.HIDE_PROFILE_STORY_RINGS.save(true);
        assertFalse(StoryRings.feedRing(0));
        assertNull(StoryRings.profileRing(null));
        String line = statusLine();
        assertFalse("a picture with no ring was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void offPausedOrColdEveryPictureKeepsItsRing() {
        assertTrue("off, a feed ring went", StoryRings.feedRing(1));
        assertFalse("off, a comment ring went", StoryRings.hideCommentRing());
        assertSame("off, a profile ring went", Ring.STORY_SEEN, StoryRings.profileRing(Ring.STORY_SEEN));

        Settings.HIDE_FEED_STORY_RINGS.save(true);
        Settings.HIDE_COMMENT_STORY_RINGS.save(true);
        Settings.HIDE_PROFILE_STORY_RINGS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertTrue("paused by " + reason + ", a feed ring went", StoryRings.feedRing(1));
            assertFalse("paused by " + reason + ", a comment ring went", StoryRings.hideCommentRing());
            assertSame("paused by " + reason + ", a profile ring went", Ring.LIVE, StoryRings.profileRing(Ring.LIVE));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertTrue("a feed ring went before the settings were ready", StoryRings.feedRing(1));
            assertFalse("a comment ring went before the settings were ready", StoryRings.hideCommentRing());
            assertSame("a profile ring went before the settings were ready", Ring.LIVE, StoryRings.profileRing(Ring.LIVE));
        });

        String line = statusLine();
        assertFalse("a ring left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertFalse("on again after the pause, the feed ring stayed", StoryRings.feedRing(1));
    }
}
