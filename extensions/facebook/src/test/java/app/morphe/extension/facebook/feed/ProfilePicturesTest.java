/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

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
 * Hide profile pictures (#400): each switch takes the picture off only its own place, and off,
 * paused or before the settings are ready, every post header and comment keeps its picture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProfilePicturesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stand-ins for the header's person model and the comment's built picture. */
    private final Object actor = new Object();
    private final Object picture = new Object();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_FEED_PROFILE_PICTURES.resetToDefault();
        Settings.HIDE_COMMENT_PROFILE_PICTURES.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PROFILE_PICTURES + ":")) return line;
        }
        return null;
    }

    @Test
    public void bothSwitchesStartOffAndBelongToTheirPatch() {
        assertFalse(Settings.HIDE_FEED_PROFILE_PICTURES.defaultValue);
        assertFalse(Settings.HIDE_COMMENT_PROFILE_PICTURES.defaultValue);
        assertTrue(PatchFamily.PROFILE_PICTURES.switches.contains(Settings.HIDE_FEED_PROFILE_PICTURES));
        assertTrue(PatchFamily.PROFILE_PICTURES.switches.contains(Settings.HIDE_COMMENT_PROFILE_PICTURES));
    }

    @Test
    public void eachSwitchTakesThePictureOffOnlyItsOwnPlace() {
        Settings.HIDE_FEED_PROFILE_PICTURES.save(true);
        assertNull("a post header kept its picture", ProfilePictures.feedPicture(actor));
        assertSame("the posts switch hid a comment's picture", picture, ProfilePictures.commentPicture(picture));

        Settings.HIDE_FEED_PROFILE_PICTURES.save(false);
        Settings.HIDE_COMMENT_PROFILE_PICTURES.save(true);
        assertSame("the comments switch hid a post header's picture", actor, ProfilePictures.feedPicture(actor));
        assertNull("a comment kept its picture", ProfilePictures.commentPicture(picture));

        String line = statusLine();
        assertTrue(line, line.endsWith("Counted: " + ProfilePictures.FEED_PICTURE_HIDDEN + " 1, "
                + ProfilePictures.COMMENT_PICTURE_HIDDEN + " 1"));
    }

    @Test
    public void aHeaderOrCommentWithNoPictureStaysThatWayAndIsntCounted() {
        Settings.HIDE_FEED_PROFILE_PICTURES.save(true);
        Settings.HIDE_COMMENT_PROFILE_PICTURES.save(true);
        assertNull(ProfilePictures.feedPicture(null));
        assertNull(ProfilePictures.commentPicture(null));
        String line = statusLine();
        assertFalse("a missing picture was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void offPausedOrColdEveryPictureStays() {
        assertSame("off, a post header's picture went", actor, ProfilePictures.feedPicture(actor));
        assertSame("off, a comment's picture went", picture, ProfilePictures.commentPicture(picture));

        Settings.HIDE_FEED_PROFILE_PICTURES.save(true);
        Settings.HIDE_COMMENT_PROFILE_PICTURES.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertSame("paused by " + reason + ", a post header's picture went", actor, ProfilePictures.feedPicture(actor));
            assertSame("paused by " + reason + ", a comment's picture went", picture, ProfilePictures.commentPicture(picture));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertSame("a post header's picture went before the settings were ready", actor,
                    ProfilePictures.feedPicture(actor));
            assertSame("a comment's picture went before the settings were ready", picture,
                    ProfilePictures.commentPicture(picture));
        });

        String line = statusLine();
        assertFalse("a picture left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertNull("on again after the pause, the post header kept its picture", ProfilePictures.feedPicture(actor));
    }
}
