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

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
        Settings.POST_WORDS_ON_TIMELINES.resetToDefault();
        Settings.POST_WORDS_IN_GROUPS.resetToDefault();
        Settings.LINK_POSTS_ON_TIMELINES.resetToDefault();
        Settings.LINK_POSTS_IN_GROUPS.resetToDefault();
        Settings.POST_WORDS_IN_SEARCH.resetToDefault();
        Settings.LINK_POSTS_IN_SEARCH.resetToDefault();
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

    /**
     * With the search switches as they ship, search, an opened post, the News feed, a profile and an
     * admin's queue are drawn as they come.
     */
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

    /**
     * Profiles and Pages, and groups, each have their own switch under each rule (#458). They start
     * on, so the rules reach where they did. One turned off keeps that place's posts and reads
     * nothing for its rule there, and the other places still lose theirs.
     */
    @Test
    public void eachPlacesSwitchKeepsItsOwnPosts() {
        assertTrue(Settings.POST_WORDS_ON_TIMELINES.defaultValue);
        assertTrue(Settings.POST_WORDS_IN_GROUPS.defaultValue);
        assertTrue(Settings.LINK_POSTS_ON_TIMELINES.defaultValue);
        assertTrue(Settings.LINK_POSTS_IN_GROUPS.defaultValue);
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        Settings.POST_WORDS_ON_TIMELINES.save(false);
        Settings.LINK_POSTS_IN_GROUPS.save(false);

        assertFalse("words off on profiles keep the profile post", hides(post(SPOILER)));
        assertTrue("links still go on profiles", hides(post(DRAMA)));
        assertTrue("words still go in groups", hidesIn("GROUPS", post(SPOILER)));
        assertFalse("links off in groups keep the group post", hidesIn("GROUPS", post(DRAMA)));
        assertNull("no profile row was read for words", line(TimelineWords.WORDS.route));
        assertNull("no group row was read for links", line(TimelineWords.GROUP_LINKS.route));

        // Both rules off in groups, the story component's rows aren't read, and no list is asked for.
        Settings.POST_WORDS_IN_GROUPS.save(false);
        FeedFilterCounters.clear();
        TimelineWords.ListReader broken = environment -> {
            throw new IllegalStateException("asked with both group switches off");
        };
        assertFalse(TimelineWords.hideInList(post(SPOILER), environment("GROUPS"), broken, words, links));
        assertNull(line(TimelineWords.LISTS));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("group feed row"));
    }

    /**
     * Search results have their own switch under each rule (#458), and it starts off, so what you
     * search for still shows. On, a search result's post goes by its rule while the group switches
     * keep their own posts, and no other list is reached.
     */
    @Test
    public void searchResultsHaveTheirOwnSwitchThatStartsOff() {
        assertFalse(Settings.POST_WORDS_IN_SEARCH.defaultValue);
        assertFalse(Settings.LINK_POSTS_IN_SEARCH.defaultValue);
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        assertFalse("off to begin with", hidesIn("SEARCH_RESULTS", post(SPOILER)));
        assertFalse(hidesIn("SEARCH_RESULTS", post(DRAMA)));
        assertNull("no search row was read", line(TimelineWords.SEARCH_WORDS.route));

        Settings.POST_WORDS_IN_SEARCH.save(true);
        Settings.LINK_POSTS_IN_SEARCH.save(true);
        Settings.POST_WORDS_IN_GROUPS.save(false);
        Settings.LINK_POSTS_IN_GROUPS.save(false);
        assertTrue("words go in search", hidesIn("SEARCH_RESULTS", post(SPOILER)));
        assertFalse("a keep phrase wins", hidesIn("SEARCH_RESULTS", post(KEPT_SPOILER)));
        assertTrue("links go in search", hidesIn("SEARCH_RESULTS", post(DRAMA)));
        assertFalse(hidesIn("SEARCH_RESULTS", post(PLAIN)));
        assertFalse("the group switches are off, so groups keep theirs", hidesIn("GROUPS", post(SPOILER)));
        assertFalse("search reaches no other list", hidesIn("PERMALINK", post(SPOILER)));

        String wordsRow = line(TimelineWords.SEARCH_WORDS.route);
        assertNotNull(FeedFilterCounters.report().toString(), wordsRow);
        assertTrue(wordsRow, wordsRow.contains("Removed: " + PostWordsForTests.HIDE_WORD + " 1"));
        String linksRow = line(TimelineWords.SEARCH_LINKS.route);
        assertNotNull(FeedFilterCounters.report().toString(), linksRow);
        assertTrue(linksRow, linksRow.contains("Removed: " + PostWordsForTests.CAPTION_LINK + " 1"));
        assertNull("no group row was read", line(TimelineWords.GROUP_WORDS.route));
        assertEquals("the settings row counts it with the feed's", 1, PostWordsForTests.hidden());

        Settings.HIDE_POSTS_WITH_WORDS.save(false);
        assertFalse("the main switch off, its search switch does nothing", hidesIn("SEARCH_RESULTS", post(SPOILER)));
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

    /** Stands in for a module of a search results page: a post's module carries its story, any other none. */
    private static final class SearchModule {
        final GraphQLStory story;

        SearchModule(GraphQLStory story) {
            this.story = story;
        }
    }

    private final TimelineWords.ModuleReader stories =
            module -> module instanceof SearchModule ? ((SearchModule) module).story : null;

    private List<Object> keptPage(List<?> modules) {
        return TimelineWords.keptSearchResults(modules, stories, words, links);
    }

    /**
     * Search's Posts tab and the posts mixed into All come as page modules, each carrying its story,
     * and the story component never draws them (#458, seen on the phone). With a search switch on, a
     * module whose post goes by that rule comes off the page, the rest keep their order, and a module
     * that isn't a post (a person, a Page, a suggestion) stays.
     */
    @Test
    public void searchPagesLosePostsByTheirSearchSwitches() {
        SearchModule person = new SearchModule(null);
        SearchModule spoiler = new SearchModule(post(SPOILER));
        SearchModule kept = new SearchModule(post(KEPT_SPOILER));
        SearchModule drama = new SearchModule(post(DRAMA));
        Object other = new Object();
        SearchModule plain = new SearchModule(post(PLAIN));
        List<Object> page = Arrays.asList(person, spoiler, kept, drama, other, plain);

        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        assertNull("the search switches start off", keptPage(page));
        assertNull("nothing was read", line(TimelineWords.SEARCH_MODULES));

        Settings.POST_WORDS_IN_SEARCH.save(true);
        assertEquals(Arrays.asList(person, kept, drama, other, plain), keptPage(page));
        Settings.LINK_POSTS_IN_SEARCH.save(true);
        assertEquals(Arrays.asList(person, kept, other, plain), keptPage(page));
        assertNull("nothing to take off keeps Facebook's list", keptPage(Arrays.asList(person, plain, kept)));

        String modules = line(TimelineWords.SEARCH_MODULES);
        assertNotNull(FeedFilterCounters.report().toString(), modules);
        assertTrue(modules, modules.startsWith(TimelineWords.SEARCH_MODULES + ": 3 lists, 15 items"));
        assertTrue(modules, modules.contains(TimelineWords.SEARCH_NOT_A_POST + " 5"));
        assertTrue(modules, modules.contains(TimelineWords.SEARCH_POST + " 10"));
        String wordsRow = line(TimelineWords.SEARCH_WORDS.route);
        assertTrue(wordsRow, wordsRow.contains("Removed: " + PostWordsForTests.HIDE_WORD + " 2"));
        String linksRow = line(TimelineWords.SEARCH_LINKS.route);
        assertTrue(linksRow, linksRow.contains("Removed: " + PostWordsForTests.CAPTION_LINK + " 1"));
        assertNull("no group or profile row was read", line(TimelineWords.GROUP_WORDS.route));
        assertNull(line(TimelineWords.WORDS.route));

        Settings.HIDE_POSTS_WITH_WORDS.save(false);
        Settings.HIDE_LINK_POSTS.save(false);
        assertNull("the main switches off, the search switches do nothing", keptPage(page));
    }

    /** Paused, before the settings are ready, with no modules or with a reader that throws, the page is kept whole. */
    @Test
    public void searchPagesPausedNotReadyOrFailingKeepEveryModule() {
        SearchModule plain = new SearchModule(post(PLAIN));
        List<Object> page = Arrays.asList(new SearchModule(post(SPOILER)), plain);
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.POST_WORDS_IN_SEARCH.save(true);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(keptPage(page));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertNull(keptPage(page)));
        assertNull(keptPage(null));
        assertNull(keptPage(Collections.emptyList()));

        TimelineWords.ModuleReader broken = module -> {
            throw new IllegalStateException("module released");
        };
        assertNull(TimelineWords.keptSearchResults(page, broken, words, links));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("search results page"));
        assertEquals("the next page still goes by your words", Arrays.asList(plain), keptPage(page));
    }

    /**
     * On 588 a post on search's Posts tab comes with no story: Facebook's card reads its words off
     * the module's own tree, so the patch hands those over as text (#458, seen on the phone, where
     * every post read as no post). The words go by your real lists and the real link rule, a kept
     * phrase still winning, and a post with no words is read and stays.
     */
    @Test
    public void searchPostWordsWithNoStoryGoByYourLists() {
        Object person = new Object();
        Object spoiler = new Object();
        Object kept = new Object();
        Object drama = new Object();
        Object photo = new Object();
        Object plain = new Object();
        Map<Object, String> shown = new IdentityHashMap<>();
        shown.put(spoiler, SPOILER);
        shown.put(kept, KEPT_SPOILER);
        shown.put(drama, DRAMA);
        shown.put(photo, "");
        shown.put(plain, PLAIN);
        TimelineWords.ModuleReader cards = shown::get;
        List<Object> page = Arrays.asList(person, spoiler, kept, drama, photo, plain);
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        Settings.POST_WORDS_IN_SEARCH.save(true);

        assertEquals(Arrays.asList(person, kept, drama, photo, plain),
                TimelineWords.keptSearchResults(page, cards, TimelineWords.WORDS_READER, TimelineWords.LINKS_READER));
        Settings.LINK_POSTS_IN_SEARCH.save(true);
        assertEquals(Arrays.asList(person, kept, photo, plain),
                TimelineWords.keptSearchResults(page, cards, TimelineWords.WORDS_READER, TimelineWords.LINKS_READER));

        String modules = line(TimelineWords.SEARCH_MODULES);
        assertTrue(modules, modules.contains(TimelineWords.SEARCH_POST + " 10"));
        assertTrue(modules, modules.contains(TimelineWords.SEARCH_NOT_A_POST + " 2"));
        String wordsRow = line(TimelineWords.SEARCH_WORDS.route);
        assertTrue(wordsRow, wordsRow.contains("Removed: " + PostWordsForTests.HIDE_WORD + " 2"));
        assertTrue(wordsRow, wordsRow.contains(PostWordsForTests.NO_TEXT));
        String linksRow = line(TimelineWords.SEARCH_LINKS.route);
        assertTrue(linksRow, linksRow.contains("Removed: " + PostWordsForTests.CAPTION_LINK + " 1"));
    }

    /** Before the patch fills in the stubs, no module reads as a post, so the page is kept whole. */
    @Test
    public void unpatchedStubsReadNoSearchPost() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.POST_WORDS_IN_SEARCH.save(true);

        assertNull(TimelineWords.searchPostOf(new SearchModule(post(SPOILER))));
        assertNull(TimelineWords.searchPostOf(null));
        assertNull(TimelineWords.searchPostText(new SearchModule(post(SPOILER))));
        assertNull(TimelineWords.keptSearchResults(Arrays.asList(new SearchModule(post(SPOILER))), "search"));
        String modules = line(TimelineWords.SEARCH_MODULES);
        assertTrue(modules, modules.contains(TimelineWords.SEARCH_NOT_A_POST + " 1"));
    }
}
