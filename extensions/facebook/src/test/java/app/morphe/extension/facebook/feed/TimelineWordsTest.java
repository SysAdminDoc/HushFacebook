/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.IdentityHashMap;
import java.util.Map;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide posts with words you choose and Hide link posts on profile and Page timelines and in groups
 * (#458): on, a post whose words hold a hidden phrase, or whose words name a website off Facebook,
 * isn't drawn. A kept phrase wins, a facebook.com link stays, and a row that isn't a story or has no
 * words stays, each counted under why. A row in any list but a group's is drawn as it comes. Off,
 * paused or before the settings are ready, every row is drawn.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TimelineWordsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String DRAMA = "She married the billionaire's son 😱 Watch More: https://page.dramabuzzs.co";
    private static final String FACEBOOK_LINK = "New photos at https://www.facebook.com/somepage/photos";
    private static final String PLAIN = "Morning walk by the lake with the dog";
    private static final String SPOILER = "Big spoiler for the finale tonight";
    private static final String KEPT_SPOILER = "Big spoiler for the finale tonight, recipe inside";

    /** Each story's message, none for a story missing here. */
    private final Map<Object, String> messages = new IdentityHashMap<>();

    private final TimelineWords.Reader words = story -> PostWordsForTests.storyKind(story, messages::get);
    private final TimelineWords.Reader links = story -> PostWordsForTests.captionLinkKind(story, messages::get);

    @Before
    public void lists() {
        PostWordsForTests.forget();
        Settings.HIDDEN_WORDS.save("spoiler");
        Settings.KEPT_WORDS.save("recipe");
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_POSTS_WITH_WORDS.resetToDefault();
        Settings.HIDE_LINK_POSTS.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        PostWordsForTests.forget();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private GraphQLStory post(String message) {
        GraphQLStory story = new GraphQLStory();
        if (message != null) messages.put(story, message);
        return story;
    }

    private boolean hides(Object unit) {
        return TimelineWords.hide(unit, words, links);
    }

    private static String line(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ": ")) return line;
        }
        return null;
    }

    /** Off, which is how both switches ship, every row is drawn and none is read. */
    @Test
    public void offEveryPostIsDrawn() {
        assertFalse(Settings.HIDE_POSTS_WITH_WORDS.defaultValue);
        assertFalse(Settings.HIDE_LINK_POSTS.defaultValue);
        assertFalse(hides(post(SPOILER)));
        assertFalse(hides(post(DRAMA)));
        assertNull(line(TimelineWords.WORDS.route));
        assertNull(line(TimelineWords.LINKS.route));
    }

    /** Words on, the post with a hidden phrase goes, the kept phrase saves its post, and each read is counted. */
    @Test
    public void wordsOnAPostWithAHiddenPhraseIsNotDrawn() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);

        assertTrue(hides(post(SPOILER)));
        assertFalse("a keep phrase wins", hides(post(KEPT_SPOILER)));
        assertFalse(hides(post(PLAIN)));
        assertFalse("only the words rule is on, so the link post stays", hides(post(DRAMA)));
        assertFalse("a post with no words stays", hides(post(null)));
        assertFalse("a row that isn't a story stays", hides(new Object()));

        String row = line(TimelineWords.WORDS.route);
        assertNotNull(FeedFilterCounters.report().toString(), row);
        assertTrue(row, row.contains("Removed: " + PostWordsForTests.HIDE_WORD + " 1"));
        assertTrue(row, row.contains(PostWordsForTests.KEEP_WORD + " 1"));
        assertTrue(row, row.contains(PostWordsForTests.NO_MATCH + " 2"));
        assertTrue(row, row.contains(PostWordsForTests.NO_TEXT + " 1"));
        assertTrue(row, row.contains(PostText.Outcome.NOT_A_STORY.reason + " 1"));
        assertEquals("the settings row counts it with the feed's", 1, PostWordsForTests.hidden());
        assertNull(line(TimelineWords.LINKS.route));
    }

    /** Links on, a post whose words name a website goes, and a Facebook link or plain words stay. */
    @Test
    public void linksOnAPostNamingASiteIsNotDrawn() {
        Settings.HIDE_LINK_POSTS.save(true);

        assertTrue(hides(post(DRAMA)));
        assertFalse("a link to Facebook itself stays", hides(post(FACEBOOK_LINK)));
        assertFalse(hides(post(SPOILER)));

        String row = line(TimelineWords.LINKS.route);
        assertNotNull(FeedFilterCounters.report().toString(), row);
        assertTrue(row, row.contains("Removed: " + PostWordsForTests.CAPTION_LINK + " 1"));
        assertTrue(row, row.contains(PostWordsForTests.NO_SITE_NAMED + " 2"));
        assertEquals("a link isn't counted as words", 0, PostWordsForTests.hidden());
    }

    /** Both on, each rule takes its own post, and the words rule reads first. */
    @Test
    public void bothOnEachRuleTakesItsPost() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);

        assertTrue(hides(post(SPOILER + " https://spoilers.example")));
        assertTrue(hides(post(DRAMA)));
        assertFalse(hides(post(PLAIN)));

        assertTrue(line(TimelineWords.WORDS.route).contains("Removed: " + PostWordsForTests.HIDE_WORD + " 1"));
        String linkRow = line(TimelineWords.LINKS.route);
        assertTrue("the post the words took isn't read for links: " + linkRow,
                linkRow.startsWith(TimelineWords.LINKS.route + ": 2 lists, 2 items"));
        assertTrue(linkRow, linkRow.contains("Removed: " + PostWordsForTests.CAPTION_LINK + " 1"));
    }

    /** Paused, or asked before the settings are ready, every row is drawn. */
    @Test
    public void pausedOrNotReadyEveryPostIsDrawn() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse("paused by " + reason, hides(post(SPOILER)));
            assertFalse("paused by " + reason, hides(post(DRAMA)));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(hides(post(SPOILER))));
        assertNull(line(TimelineWords.WORDS.route));
        assertNull(line(TimelineWords.LINKS.route));
    }

    /** A reader that throws keeps the row and says so in the hook status. */
    @Test
    public void aFailingReadDrawsTheRow() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        TimelineWords.Reader broken = story -> {
            throw new IllegalStateException("tree released");
        };

        assertFalse(TimelineWords.hide(post(SPOILER), broken, links));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("timeline row"));
    }

    /** Each environment's list name, none for an environment missing here. */
    private final Map<Object, String> listNames = new IdentityHashMap<>();

    private final TimelineWords.ListReader lists = listNames::get;

    private Object environment(String list) {
        Object environment = new Object();
        if (list != null) listNames.put(environment, list);
        return environment;
    }

    private boolean hidesIn(String list, Object story) {
        return TimelineWords.hideInList(story, environment(list), lists, words, links);
    }

    /** In a group's feed, the Groups tab's feed and a set of groups, both rules take their posts. */
    @Test
    public void groupListsDropTheirPosts() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);

        for (String list : new String[] {"GROUPS", "CROSS_GROUP_FEED", "GROUP_SET_FEED"}) {
            assertTrue(list, hidesIn(list, post(SPOILER)));
            assertFalse(list + ": a keep phrase wins", hidesIn(list, post(KEPT_SPOILER)));
            assertFalse(list, hidesIn(list, post(PLAIN)));
        }
        assertTrue(hidesIn("GROUPS_PINNED", post(DRAMA)));
        assertFalse("a link to Facebook itself stays", hidesIn("GROUPS", post(FACEBOOK_LINK)));

        String wordsRow = line(TimelineWords.GROUP_WORDS.route);
        assertNotNull(FeedFilterCounters.report().toString(), wordsRow);
        assertTrue(wordsRow, wordsRow.contains("Removed: " + PostWordsForTests.HIDE_WORD + " 3"));
        assertEquals("the settings row counts them with the feed's", 3, PostWordsForTests.hidden());
        String linksRow = line(TimelineWords.GROUP_LINKS.route);
        assertNotNull(FeedFilterCounters.report().toString(), linksRow);
        assertTrue(linksRow, linksRow.contains("Removed: " + PostWordsForTests.CAPTION_LINK + " 1"));
        assertTrue(line(TimelineWords.LISTS), line(TimelineWords.LISTS).contains("GROUPS 4"));
        assertNull("profile routes stay apart", line(TimelineWords.WORDS.route));
    }

    /** Search, an opened post, the News feed, a profile and an admin's queue are drawn as they come. */
    @Test
    public void otherListsAreDrawnAsTheyCome() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);

        for (String list : new String[] {"FEED", "PERMALINK", "SEARCH_RESULTS", "PROFILE_PAGE", "GROUPS_PENDING", null}) {
            assertFalse(String.valueOf(list), hidesIn(list, post(SPOILER + " https://spoilers.example")));
        }

        assertNull("no group row was read", line(TimelineWords.GROUP_WORDS.route));
        assertNull(line(TimelineWords.GROUP_LINKS.route));
        String seen = line(TimelineWords.LISTS);
        assertNotNull(FeedFilterCounters.report().toString(), seen);
        assertTrue(seen, seen.startsWith(TimelineWords.LISTS + ": 6 lists, 6 items, 0 removed"));
        assertTrue(seen, seen.endsWith(". Kinds: FEED 1, GROUPS_PENDING 1, PERMALINK 1, PROFILE_PAGE 1, SEARCH_RESULTS 1, "
                + TimelineWords.UNNAMED_LIST + " 1"));
    }

    /** Both switches off, the story component's rows aren't read, and no list is asked for. */
    @Test
    public void offTheListIsNeverAskedFor() {
        TimelineWords.ListReader broken = environment -> {
            throw new IllegalStateException("asked with both switches off");
        };

        assertFalse(TimelineWords.hideInList(post(SPOILER), environment("GROUPS"), broken, words, links));
        assertNull(line(TimelineWords.LISTS));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("group feed row"));
    }

    /** Before the patch fills in the stubs, no list has a name, so every row is drawn. */
    @Test
    public void unpatchedStubsNameNoList() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);

        assertNull(TimelineWords.listNameOf(new Object()));
        assertNull(TimelineWords.listNameOf(null));
        assertFalse(TimelineWords.hideInList(post(SPOILER), new Object()));
        assertTrue(line(TimelineWords.LISTS), line(TimelineWords.LISTS).contains(TimelineWords.UNNAMED_LIST + " 1"));
    }

    /** Paused, before the settings are ready, or with a list reader that throws, every row is drawn. */
    @Test
    public void pausedNotReadyOrFailingGroupRowsAreDrawn() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(hidesIn("GROUPS", post(SPOILER)));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(hidesIn("GROUPS", post(SPOILER))));
        assertNull(line(TimelineWords.GROUP_WORDS.route));

        TimelineWords.ListReader broken = environment -> {
            throw new IllegalStateException("environment released");
        };
        assertFalse(TimelineWords.hideInList(post(SPOILER), environment("GROUPS"), broken, words, links));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("group feed row"));
    }
}
