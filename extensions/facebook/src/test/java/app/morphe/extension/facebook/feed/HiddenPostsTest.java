/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A post the person hid stays out of the feed when a pull to refresh brings it back (#444): with
 * Hushfacebook on, "Hide all from" on a group post and a pull put the same post back. Facebook
 * publishes the hide, and Undo's VISIBLE, by the post's id, and the feed guard keeps out every new
 * edge of a post hidden since the start and not brought back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HiddenPostsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Category { ORGANIC }

    private static final int VISIBLE = 0;

    @After
    public void restore() {
        HiddenPosts.clearForTests();
        PauseForTests.resume();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static GraphQLStory post(String id) {
        return (GraphQLStory) new GraphQLStory().with("id", id);
    }

    /** The guard as the funnel asks it, with no filter patched in. */
    private static boolean guard(Object unit) {
        return FeedFilter.hideEdge(Category.ORGANIC, unit, false, false);
    }

    @Test
    public void aPostYouHidStaysOutWhenARefreshBringsItBack() {
        assertFalse("nothing hidden yet", guard(post("S1")));

        HiddenPosts.published("S1", HiddenPosts.HIDDEN);
        assertTrue("the hidden post came back on a refresh", guard(post("S1")));
        assertFalse("another post", guard(post("S2")));
        assertFalse("a unit with no id", guard(new GraphQLStory()));
        assertFalse("not a post", guard(new Object()));
    }

    @Test
    public void undoBringsThePostBack() {
        HiddenPosts.published("S1", HiddenPosts.CONTRACTING);
        assertTrue("folding away already counts", guard(post("S1")));
        HiddenPosts.published("S1", VISIBLE);
        assertFalse("Undo brought it back", guard(post("S1")));

        HiddenPosts.published("S3", HiddenPosts.GONE);
        assertTrue("gone", guard(post("S3")));
        HiddenPosts.published("S3", null);
        HiddenPosts.published(null, VISIBLE);
        assertTrue("a publish with no visibility or no id changes nothing", guard(post("S3")));
    }

    @Test
    public void pausedLeavesTheFeedAsFacebookHasIt() {
        HiddenPosts.published("S1", HiddenPosts.HIDDEN);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(guard(post("S1")));
        PauseForTests.resume();
        assertTrue(guard(post("S1")));
    }

    @Test
    public void itKeepsTheNewestHiddenPosts() {
        for (int index = 0; index <= HiddenPosts.MAX; index++) HiddenPosts.published("P" + index, HiddenPosts.HIDDEN);
        assertEquals(HiddenPosts.MAX, HiddenPosts.heldForTests());
        assertFalse("the oldest went first", guard(post("P0")));
        assertTrue(guard(post("P" + HiddenPosts.MAX)));

        HiddenPosts.published("P1", HiddenPosts.HIDDEN);
        HiddenPosts.published("Q", HiddenPosts.HIDDEN);
        assertTrue("hiding it again made it the newest", guard(post("P1")));
        assertFalse(guard(post("P2")));
    }
}
