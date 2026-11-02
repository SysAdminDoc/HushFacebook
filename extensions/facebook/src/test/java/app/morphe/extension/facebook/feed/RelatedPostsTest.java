/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.comments.MetaAiSummaries;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The Related posts list under a post (#231): with the switch on, the socket under a post's buttons
 * gets a no for the Related posts plugin, counted, while the Meta AI summary and every other plugin
 * it asks about is left to Facebook. The list a post opened on its own shows after its comments goes
 * the same way, and that post's request stops asking for it. Off, paused, or before the settings
 * are ready, nothing is held.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RelatedPostsTest {
    /** The Meta AI summary, another plugin of the same socket, which this switch never holds. */
    private static final String POST_SUMMARY = MetaAiSummaries.POST_SUMMARY;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_RELATED_POSTS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_POSTS + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheListIsHeldAndCountedAndOtherPluginsStay() {
        Settings.HIDE_RELATED_POSTS.save(true);
        assertFalse("the Meta AI summary was held", RelatedPosts.holds(POST_SUMMARY));
        assertFalse("a plugin with no name was held", RelatedPosts.holds(null));
        assertTrue("the Related posts list stayed", RelatedPosts.holds(RelatedPosts.PLUGIN));
        assertTrue("the next post's list stayed", RelatedPosts.holds(RelatedPosts.PLUGIN));
        String line = statusLine();
        assertNotNull("the report doesn't name the family", line);
        assertTrue("other plugins were counted as calls: " + line, line.contains("invoked 2,"));
        assertTrue("the list kept out wasn't counted: " + line,
                line.contains("Counted: " + RelatedPosts.HIDDEN + " 2"));
    }

    /**
     * The reporter's second case: a post opened on its own lists Related posts after its comments,
     * through the comment screen's bottom socket, and its request asks the server for that list.
     */
    @Test
    public void onTheListAfterCommentsIsHeldAndItsRequestLeavesItOut() {
        Settings.HIDE_RELATED_POSTS.save(true);
        assertFalse("another plugin of the bottom socket was held", RelatedPosts.holds(
                "com.facebook.feedback.comments.plugins.bottomcontent.impl.relatedgroups.RelatedGroupsPlugin"));
        assertTrue("the list after the comments stayed", RelatedPosts.holds(RelatedPosts.COMMENT_PLUGIN));
        assertTrue("the request still asked for the list", RelatedPosts.keepsOutOfRequest());
        String line = statusLine();
        assertNotNull("the report doesn't name the family", line);
        assertTrue("the list after comments wasn't counted apart: " + line,
                line.contains("Counted: " + RelatedPosts.HIDDEN_AFTER_COMMENTS + " 1"));
        assertTrue("the request wasn't counted: " + line, line.contains(", " + RelatedPosts.LEFT_OUT + " 1"));
        assertFalse("the list under a post was counted: " + line, line.contains(RelatedPosts.HIDDEN + " "));
    }

    @Test
    public void offOrPausedTheRequestAsksAsFacebookWrote() {
        assertFalse("off, the request left the list out", RelatedPosts.keepsOutOfRequest());
        assertFalse("off, the list after comments was held", RelatedPosts.holds(RelatedPosts.COMMENT_PLUGIN));

        Settings.HIDE_RELATED_POSTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, the request left the list out", RelatedPosts.keepsOutOfRequest());
        assertFalse("paused, the list after comments was held", RelatedPosts.holds(RelatedPosts.COMMENT_PLUGIN));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("the request left the list out before the settings were ready",
                RelatedPosts.keepsOutOfRequest()));

        String line = statusLine();
        assertFalse("a request left to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void offOrPausedTheListStays() {
        assertFalse("the switch doesn't start off", Settings.HIDE_RELATED_POSTS.get());
        assertFalse("off, the list was held", RelatedPosts.holds(RelatedPosts.PLUGIN));

        Settings.HIDE_RELATED_POSTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held the list",
                    RelatedPosts.holds(RelatedPosts.PLUGIN));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse("the list was held before the settings were ready",
                RelatedPosts.holds(RelatedPosts.PLUGIN)));

        String line = statusLine();
        assertFalse("a list left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the list stayed", RelatedPosts.holds(RelatedPosts.PLUGIN));
    }

    @Test
    public void theSwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse("each post asks again, so no restart is needed", Settings.HIDE_RELATED_POSTS.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.HIDE_RELATED_POSTS.userDialogMessage);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.SUGGESTED_POSTS.switches.contains(Settings.HIDE_RELATED_POSTS));
    }
}
