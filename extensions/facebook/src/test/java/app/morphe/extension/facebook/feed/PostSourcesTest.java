/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.modelutil.BaseModelWithTree;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** Hide posts from people, Pages and sites: the list, the read of a post, and the rule in the feed guard. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostSourcesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Map<Object, List<?>> authors = new HashMap<>();
    private final Map<Object, List<?>> links = new HashMap<>();
    private final StoryFlag.Accessor actors = authors::get;
    private final StoryFlag.Accessor attachments = links::get;

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_POSTS_FROM_SOURCES.resetToDefault();
        Settings.HIDDEN_SOURCES.resetToDefault();
        Settings.HIDE_PAGE_POSTS.resetToDefault();
        Settings.ONLY_FRIENDS_POSTS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static BaseModelWithTree author(String id, String name) {
        return new BaseModelWithTree(0).with("id", id).with("name", name);
    }

    private static BaseModelWithTree link(String url) {
        return new BaseModelWithTree(0).with("url", url);
    }

    private GraphQLStory story(GraphQLStory shared, List<?> by, List<?> linking) {
        GraphQLStory story = new GraphQLStory(shared);
        authors.put(story, by);
        links.put(story, linking);
        return story;
    }

    private PostSources.Found read(Object unit) {
        return PostSources.read(unit, actors, attachments, story -> ((GraphQLStory) story).A04());
    }

    @Test
    public void eachLineIsAnIdASiteOrAName() {
        assertEquals(PostSources.Kind.ID, PostSources.rule("100044218155390", 1).kind);
        PostSources.Rule site = PostSources.rule("https://www.Example.com/news?x=1", 2);
        assertEquals(PostSources.Kind.SITE, site.kind);
        assertEquals("example.com", site.value);
        PostSources.Rule name = PostSources.rule("  Daily   Bugle ", 3);
        assertEquals(PostSources.Kind.NAME, name.kind);
        assertEquals("daily bugle", name.value);
        assertEquals("a name with a dot stays a name", PostSources.Kind.NAME, PostSources.rule("Dr. Ana Ruiz", 4).kind);
        assertEquals("two digits are a name", PostSources.Kind.NAME, PostSources.rule("42", 5).kind);
        assertNull(PostSources.rule("   ", 6));
        assertNull(PostSources.rule(String.join("", Collections.nCopies(PostSources.MAX_LENGTH + 1, "a")), 7));
    }

    @Test
    public void cleanKeepsOneRulePerLineInOrderAndSaysHowManyItLeftOut() {
        String typed = "example.com\n\n  Daily   Bugle \nEXAMPLE.com\nwww.example.com\n123456\n" + "x".repeat(101);
        assertEquals("example.com\nDaily Bugle\n123456", PostSources.clean(typed));
        assertEquals(3, PostSources.leftOut(typed));
        assertTrue(PostSources.isClean("example.com\nDaily Bugle"));
        assertFalse(PostSources.isClean("example.com\n\nDaily Bugle"));
        assertEquals(3, PostSources.count("example.com\nDaily Bugle\n123456"));
    }

    @Test
    public void theListStopsAtItsRuleCountAndItsRoom() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < PostSources.MAX_RULES + 5; i++) many.append("site").append(i).append(".com\n");
        assertEquals(PostSources.MAX_RULES, PostSources.count(PostSources.clean(many.toString())));
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < PostSources.MAX_RULES; i++) wide.append("名".repeat(PostSources.MAX_LENGTH - 4)).append(i).append('\n');
        String clean = PostSources.clean(wide.toString());
        assertTrue(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PostSources.MAX_LIST_BYTES);
        assertTrue(PostSources.count(clean) < PostSources.MAX_RULES);
    }

    @Test
    public void aLinkCountsAsItsSiteEvenThroughFacebooksRedirect() {
        assertEquals("example.com", PostSources.host("https://www.example.com/a/b"));
        assertEquals("news.example.com", PostSources.host("http://news.example.com"));
        assertEquals("example.org", PostSources.host(
                "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.example.org%2Fstory&h=AT0"));
        assertEquals("l.facebook.com", PostSources.host("https://l.facebook.com/other"));
        assertNull(PostSources.host("not a link"));
        assertNull(PostSources.host(null));
    }

    @Test
    public void aRuleMatchesAnAuthorsIdOrNameOrALinksSiteAndItsSubdomains() {
        GraphQLStory post = story(null, Arrays.asList(author("100044218155390", "Daily Bugle")),
                Arrays.asList(link("https://news.example.com/x")));
        PostSources.Found found = read(post);
        assertEquals(PostSources.Outcome.READ, found.outcome);
        assertEquals(1, PostSources.match(PostSources.rules("100044218155390"), found));
        assertEquals(2, PostSources.match(PostSources.rules("other.com\ndaily BUGLE"), found));
        assertEquals(1, PostSources.match(PostSources.rules("example.com"), found));
        assertEquals("a site's name inside another doesn't match",
                0, PostSources.match(PostSources.rules("ample.com\nDaily"), found));
        assertEquals(0, PostSources.match(PostSources.rules("10004421815539"), found));
    }

    @Test
    public void aShareMatchesTheSharedPostsAuthorAndLinks() {
        GraphQLStory original = story(null, Arrays.asList(author("555", "Daily Bugle")),
                Arrays.asList(link("https://example.org/a")));
        GraphQLStory share = story(original, Arrays.asList(author("777", "A Friend")), Collections.emptyList());
        PostSources.Found found = read(share);
        assertEquals(1, PostSources.match(PostSources.rules("Daily Bugle"), found));
        assertEquals(1, PostSources.match(PostSources.rules("example.org"), found));
        assertEquals(1, PostSources.match(PostSources.rules("A Friend"), found));
    }

    @Test
    public void whatCantBeReadKeepsThePost() {
        assertEquals(PostSources.Outcome.NO_UNIT, read(null).outcome);
        assertEquals(PostSources.Outcome.NOT_A_STORY, read(new Object()).outcome);
        GraphQLStory post = new GraphQLStory();
        assertEquals("unpatched stubs", PostSources.Outcome.NO_ACCESSOR,
                PostSources.read(post, PostSources.ACTORS, PostSources.ATTACHMENTS, PostText.ATTACHED).outcome);
        StoryFlag.Accessor throwing = story -> {
            throw new IllegalStateException("renamed");
        };
        assertEquals(PostSources.Outcome.READ_FAILED, PostSources.read(post, throwing, attachments, s -> null).outcome);
        // A story with no authors or links reads cleanly as nothing to match.
        PostSources.Found empty = PostSources.read(post, s -> null, s -> null, s -> null);
        assertEquals(PostSources.Outcome.READ, empty.outcome);
        assertEquals(0, PostSources.match(PostSources.rules("example.com\nDaily Bugle"), empty));
    }

    @Test
    public void aFacebookLinkIsTheIdItCarriesAndOneWithoutIsLeftOut() {
        PostSources.Rule profile = PostSources.rule("https://www.facebook.com/profile.php?id=100044218155390", 1);
        assertEquals(PostSources.Kind.ID, profile.kind);
        assertEquals("100044218155390", profile.value);
        assertEquals("100044218155390",
                PostSources.rule("https://www.facebook.com/profile.php?id=100044218155390&sk=about", 2).value);
        assertEquals("100044218155390", PostSources.rule("facebook.com/people/Daily-Bugle/100044218155390/", 3).value);
        assertEquals("100044218155390", PostSources.rule("https://m.facebook.com/100044218155390", 4).value);
        assertNull("a username can't be matched", PostSources.rule("https://www.facebook.com/DailyBugle", 5));
        assertNull("a post's number isn't its author's",
                PostSources.rule("https://www.facebook.com/DailyBugle/posts/1234567890", 6));
        assertNull("an id with letters after it isn't one",
                PostSources.rule("https://www.facebook.com/profile.php?id=100044218155390x", 7));
        for (String own : new String[] {"facebook.com", "www.facebook.com", "https://web.facebook.com/", "fb.me/x",
                "https://fb.watch/abc", "https://l.facebook.com/l.php?u=x", "scontent.xx.fbcdn.net"}) {
            assertNull(own + " would take nearly every post", PostSources.rule(own, 8));
        }
        assertEquals("a site that only ends in the same letters isn't Facebook's",
                PostSources.Kind.SITE, PostSources.rule("notfacebook.com", 9).kind);
        assertEquals(1, PostSources.leftOut("https://www.facebook.com/DailyBugle\nexample.com"));
        assertEquals("the id and its link are one rule",
                1, PostSources.count("100044218155390\nhttps://www.facebook.com/profile.php?id=100044218155390"));

        GraphQLStory post = story(null, Arrays.asList(author("100044218155390", "Daily Bugle")),
                Arrays.asList(link("https://www.facebook.com/photo/?fbid=1"), link("https://www.facebook.com/DailyBugle")));
        assertEquals(1, PostSources.match(PostSources.rules("https://www.facebook.com/profile.php?id=100044218155390"),
                read(post)));
        assertEquals("a Facebook link never matches as a site", 0,
                PostSources.match(PostSources.rules("https://www.facebook.com/SomeoneElse"), read(post)));
    }

    @Test
    public void aBareLineWithADotIsASiteAndANameAtOnce() {
        PostSources.Rule bare = PostSources.rule("Mr.Beast", 1);
        assertEquals(PostSources.Kind.SITE, bare.kind);
        assertEquals("mr.beast", bare.name);
        assertNull("a link names a site only", PostSources.rule("https://booking.com/deals", 2).name);

        assertEquals(1, PostSources.match(PostSources.rules("Mr.Beast"),
                read(story(null, Arrays.asList(author("556", "Mr.Beast")), Collections.emptyList()))));
        GraphQLStory page = story(null, Arrays.asList(author("557", "Booking.com")),
                Arrays.asList(link("https://example.org/x")));
        assertEquals("the Page's own post with no link", 1, PostSources.match(PostSources.rules("Booking.com"), read(page)));
        assertEquals(0, PostSources.match(PostSources.rules("https://booking.com"), read(page)));
        GraphQLStory linking = story(null, Arrays.asList(author("558", "A Friend")),
                Arrays.asList(link("https://www.booking.com/hotel")));
        assertEquals(1, PostSources.match(PostSources.rules("Booking.com"), read(linking)));
    }

    @Test
    public void theListFitsItsRoomAsASettingsFileWritesIt() {
        StringBuilder typed = new StringBuilder();
        for (int i = 0; i < PostSources.MAX_RULES; i++) {
            StringBuilder line = new StringBuilder("https://s").append(i).append(".example.com");
            while (line.length() < PostSources.MAX_LENGTH) line.append("/x");
            typed.append(line, 0, PostSources.MAX_LENGTH).append('\n');
        }
        String clean = PostSources.clean(typed.toString());
        int bytes = PostWords.encodedBytes(clean);
        assertTrue(bytes + " bytes", bytes <= PostSources.MAX_LIST_BYTES);
        assertTrue("the room ran out before the count did", PostSources.count(clean) < PostSources.MAX_RULES);
        assertTrue(PostSources.isClean(clean));
    }

    @Test
    public void aLinkAStrictParserRefusesStillHasItsSite() {
        assertEquals("example.com", PostSources.host("https://example.com/a b|c{d}"));
        assertEquals("my_site.example.com", PostSources.host("https://my_site.example.com/x"));
        assertEquals("example.com", PostSources.host("https://user@www.example.com:8443/x"));
        assertEquals("example.org", PostSources.host("https://lm.facebook.com/l.php?h=AT0&u=https%3A%2F%2Fexample.org%2Fa#x"));
        assertEquals("a target that can't be decoded leaves the redirect's own host", "l.facebook.com",
                PostSources.host("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2F%ZZ&h=AT0"));
        assertNull(PostSources.host("example.com/no-scheme"));
        assertNull(PostSources.host("https:///nothing"));
    }

    @Test
    public void aBackslashEndsALinksHostAsABrowserReadsIt() {
        assertEquals("spam.example", PostSources.host("https://spam.example\\@news.example/x"));
        assertEquals("spam.example", PostSources.host("https://spam.example\\path"));
    }

    @Test
    public void aSiteInAnotherScriptMatchesItsLinksEitherWay() {
        PostSources.Rule typed = PostSources.rule("bücher.de", 1);
        assertEquals(PostSources.Kind.SITE, typed.kind);
        assertEquals("xn--bcher-kva.de", typed.value);
        assertEquals("bücher.de", typed.name);
        assertEquals("xn--bcher-kva.de", PostSources.host("https://www.bücher.de/angebote"));
        assertEquals("xn--bcher-kva.de", PostSources.host("https://xn--bcher-kva.de/"));

        GraphQLStory unicode = story(null, Arrays.asList(author("560", "A Friend")),
                Arrays.asList(link("https://bücher.de/x")));
        GraphQLStory punycode = story(null, Arrays.asList(author("561", "A Friend")),
                Arrays.asList(link("https://shop.xn--bcher-kva.de/x")));
        assertEquals(1, PostSources.match(PostSources.rules("bücher.de"), read(unicode)));
        assertEquals(1, PostSources.match(PostSources.rules("bücher.de"), read(punycode)));
        assertEquals(1, PostSources.match(PostSources.rules("https://xn--bcher-kva.de"), read(unicode)));
    }

    @Test
    public void aFacebookLinkWithTrackingStillGivesItsId() {
        String link = "https://www.facebook.com/people/Some-Long-Page-Name/100044218155390/?mibextid=ZbWKwL"
                + "&rdid=AbCdEfGhIjKlMnOp&share_url=https%3A%2F%2Fwww.facebook.com%2Fshare%2F1A2b3C";
        assertTrue(link.length() > PostSources.MAX_LENGTH);
        assertEquals("100044218155390", PostSources.rule(link, 1).value);
        assertEquals(0, PostSources.leftOut(link));

        StringBuilder longSite = new StringBuilder("https://example.com/");
        while (longSite.length() <= PostSources.MAX_LENGTH) longSite.append('x');
        assertNull("only a link to Facebook may run long", PostSources.rule(longSite.toString(), 2));
        StringBuilder tooLong = new StringBuilder("https://www.facebook.com/profile.php?id=100044218155390&x=");
        while (tooLong.length() <= PostSources.MAX_FACEBOOK_LINK_LENGTH) tooLong.append('y');
        assertNull(PostSources.rule(tooLong.toString(), 3));
    }

    @Test
    public void aLinkToASiteAndTheSiteTypedBareAreTwoRules() {
        String typed = "https://booking.com\nBooking.com";
        assertEquals(2, PostSources.count(typed));
        assertEquals(0, PostSources.leftOut(typed));
        GraphQLStory page = story(null, Arrays.asList(author("557", "Booking.com")),
                Arrays.asList(link("https://example.org/x")));
        assertEquals("the bare line still names the Page", 2, PostSources.match(PostSources.rules(typed), read(page)));
        assertEquals("the same bare line twice is one rule", 1, PostSources.count("Booking.com\nbooking.com"));
    }

    @Test
    public void aShareWhoseOriginalCantBeReadStillMatchesItsOwnAuthor() {
        GraphQLStory original = story(null, Arrays.asList(author("555", "Daily Bugle")), Collections.emptyList());
        GraphQLStory share = story(original, Arrays.asList(author("777", "A Friend")), Collections.emptyList());
        StoryFlag.Accessor failsOnTheOriginal = story -> {
            if (story == original) throw new IllegalStateException("renamed");
            return authors.get(story);
        };
        PostSources.Found found = PostSources.read(share, failsOnTheOriginal, attachments,
                story -> ((GraphQLStory) story).A04());
        assertEquals(PostSources.Outcome.READ, found.outcome);
        assertEquals(1, PostSources.match(PostSources.rules("A Friend"), found));
        assertEquals(0, PostSources.match(PostSources.rules("Daily Bugle"), found));
        PostSources.Found noShare = PostSources.read(share, actors, attachments, story -> {
            throw new IllegalStateException("renamed");
        });
        assertEquals(1, PostSources.match(PostSources.rules("A Friend"), noShare));
    }

    @Test
    public void theFeedReadsAListOnceUntilItChanges() {
        List<PostSources.Rule> first = PostSources.cachedRules("example.com\nDaily Bugle");
        assertEquals(2, first.size());
        assertSame(first, PostSources.cachedRules("example.com\nDaily Bugle"));
        assertEquals(1, PostSources.cachedRules("other.com").size());
        assertTrue(PostSources.cachedRules(null).isEmpty());
    }

    @Test
    public void theFeedGuardHidesAMatchOnlyWithTheSwitchOnAndAListAndNoPause() {
        GraphQLStory post = story(null, Arrays.asList(author("555", "Daily Bugle")), Collections.emptyList());
        assertFalse("the switch starts off", Settings.HIDE_POSTS_FROM_SOURCES.get());
        assertNull(FeedFilter.sourcesReason(post, actors, attachments, s -> null));

        Settings.HIDE_POSTS_FROM_SOURCES.save(true);
        assertNull("an empty list hides nothing", FeedFilter.sourcesReason(post, actors, attachments, s -> null));
        Settings.HIDDEN_SOURCES.save("other.com\nDaily Bugle");
        assertEquals(FeedFilter.SOURCES_REASON + " 2", FeedFilter.sourcesReason(post, actors, attachments, s -> null));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(FeedFilter.sourcesReason(post, actors, attachments, s -> null));
    }

    /** An author that answers [type] from {@code getTypeName()}, as a Page or a person does. */
    private static BaseModelWithTree typed(String type) {
        return new BaseModelWithTree(type) {
        };
    }

    @Test
    public void aPostIsAPagesOnlyWhenEveryOneOfItsOwnAuthorsIsAPage() {
        GraphQLStory page = story(null, Arrays.asList(typed("Page")), Collections.emptyList());
        GraphQLStory twoPages = story(null, Arrays.asList(typed("Page"), typed("Page")), Collections.emptyList());
        GraphQLStory withAFriend = story(null, Arrays.asList(typed("Page"), typed("User")), Collections.emptyList());
        GraphQLStory friend = story(null, Arrays.asList(typed("User")), Collections.emptyList());
        GraphQLStory friendSharesAPage = story(page, Arrays.asList(typed("User")), Collections.emptyList());
        GraphQLStory unread = story(null, Arrays.asList(typed("Page"), typed(null)), Collections.emptyList());
        GraphQLStory released = story(null, Arrays.asList(typed("Page").released()), Collections.emptyList());
        GraphQLStory nobody = story(null, Collections.emptyList(), Collections.emptyList());

        assertEquals(PostSources.PAGE, PostSources.authorKind(page, actors));
        assertEquals(PostSources.PAGE, PostSources.authorKind(twoPages, actors));
        assertEquals("User", PostSources.authorKind(withAFriend, actors));
        assertEquals("User", PostSources.authorKind(friend, actors));
        assertEquals("a share is its sharer's", "User", PostSources.authorKind(friendSharesAPage, actors));
        assertEquals(PostSources.UNKNOWN_AUTHOR, PostSources.authorKind(unread, actors));
        assertEquals("a released author isn't asked", PostSources.UNKNOWN_AUTHOR, PostSources.authorKind(released, actors));
        assertEquals(PostSources.NO_AUTHORS, PostSources.authorKind(nobody, actors));
        assertEquals(PostSources.Outcome.NOT_A_STORY.reason, PostSources.authorKind(new Object(), actors));
        assertEquals(PostSources.Outcome.NO_UNIT.reason, PostSources.authorKind(null, actors));
        assertEquals(PostSources.Outcome.NO_ACCESSOR.reason, PostSources.authorKind(page, PostSources.ACTORS));
        assertEquals(PostSources.Outcome.READ_FAILED.reason, PostSources.authorKind(page, story -> {
            throw new IllegalStateException("renamed");
        }));
    }

    @Test
    public void hidePostsFromPagesTakesAPagesPostAndCountsEveryKind() {
        GraphQLStory page = story(null, Arrays.asList(typed("Page")), Collections.emptyList());
        GraphQLStory friend = story(null, Arrays.asList(typed("User")), Collections.emptyList());
        GraphQLStory share = story(page, Arrays.asList(typed("User")), Collections.emptyList());

        assertEquals(PostSources.PAGE, FeedFilter.pagesReason(page, actors));
        assertNull(FeedFilter.pagesReason(friend, actors));
        assertNull("a friend's share of a Page's post stays", FeedFilter.pagesReason(share, actors));
        assertEquals(FeedFilter.PAGES_ROUTE + ": 3 lists, 3 items, 1 removed. Last reason: Page. "
                + "Removed: Page 1. Kinds: User 2, Page 1", line(FeedFilter.PAGES_ROUTE));
    }

    enum Category { ORGANIC }

    /** The news feed guard with Hide posts by words in or left out, reading no text. */
    private static boolean guard(Object unit, boolean wordsPatched) {
        return FeedFilter.hideEdge(Category.ORGANIC, unit, true, true, story -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, wordsPatched, story -> null, story -> null);
    }

    @Test
    public void theFeedGuardAsksAboutPagesOnlyWithTheSwitchOnAndNoPause() {
        GraphQLStory page = story(null, Arrays.asList(typed("Page")), Collections.emptyList());
        assertFalse("the switch starts off", Settings.HIDE_PAGE_POSTS.defaultValue);
        assertFalse(guard(page, true));
        assertNull("nothing read with the switch off", line(FeedFilter.PAGES_ROUTE));

        Settings.HIDE_PAGE_POSTS.save(true);
        assertFalse(guard(page, false));
        assertNull("nothing read without Hide posts by words", line(FeedFilter.PAGES_ROUTE));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(guard(page, true));
        assertNull("nothing read while paused", line(FeedFilter.PAGES_ROUTE));

        PauseForTests.resume();
        assertFalse("a build whose authors stub isn't filled keeps the post", guard(page, true));
        assertEquals(FeedFilter.PAGES_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: "
                + PostSources.Outcome.NO_ACCESSOR.reason + " 1", line(FeedFilter.PAGES_ROUTE));
    }

    /** A person author whose friendship status reads [status], as the feed's tree holds it. */
    private static BaseModelWithTree person(String status) {
        return typed(PostSources.PERSON).with(PostSources.FRIENDSHIP_FIELD, status);
    }

    @Test
    public void aPostIsAFriendsWhenOneOfItsOwnAuthorsIsAFriend() {
        GraphQLStory friendsPost = story(null, Arrays.asList(person("ARE_FRIENDS")), Collections.emptyList());
        GraphQLStory strangersPost = story(null, Arrays.asList(person("CAN_REQUEST")), Collections.emptyList());
        GraphQLStory asked = story(null, Arrays.asList(person("OUTGOING_REQUEST")), Collections.emptyList());
        GraphQLStory asking = story(null, Arrays.asList(person("INCOMING_REQUEST")), Collections.emptyList());
        GraphQLStory page = story(null, Arrays.asList(typed("Page")), Collections.emptyList());
        GraphQLStory pageWithAFriend = story(null, Arrays.asList(typed("Page"), person("ARE_FRIENDS")),
                Collections.emptyList());
        GraphQLStory own = story(null, Arrays.asList(person("CANNOT_REQUEST")), Collections.emptyList());
        GraphQLStory noStatus = story(null, Arrays.asList(typed(PostSources.PERSON)), Collections.emptyList());
        GraphQLStory friendSharesAStranger = story(strangersPost, Arrays.asList(person("ARE_FRIENDS")),
                Collections.emptyList());
        GraphQLStory strangerSharesAFriend = story(friendsPost, Arrays.asList(person("CAN_REQUEST")),
                Collections.emptyList());
        GraphQLStory unread = story(null, Arrays.asList(typed(null)), Collections.emptyList());
        GraphQLStory released = story(null, Arrays.asList(person("CAN_REQUEST").released()), Collections.emptyList());
        GraphQLStory nobody = story(null, Collections.emptyList(), Collections.emptyList());

        assertEquals(PostSources.FRIEND + "ARE_FRIENDS", PostSources.friendKind(friendsPost, actors));
        assertEquals(PostSources.NOT_A_FRIEND + "CAN_REQUEST", PostSources.friendKind(strangersPost, actors));
        assertEquals(PostSources.NOT_A_FRIEND + "OUTGOING_REQUEST", PostSources.friendKind(asked, actors));
        assertEquals(PostSources.NOT_A_FRIEND + "INCOMING_REQUEST", PostSources.friendKind(asking, actors));
        assertEquals(PostSources.NOT_A_FRIEND + "Page", PostSources.friendKind(page, actors));
        assertEquals(PostSources.FRIEND + "ARE_FRIENDS", PostSources.friendKind(pageWithAFriend, actors));
        assertEquals("your own posts stay", PostSources.FRIEND + "CANNOT_REQUEST", PostSources.friendKind(own, actors));
        assertEquals("a status that reads nothing keeps the post",
                PostSources.FRIEND + PostSources.NO_FRIENDSHIP, PostSources.friendKind(noStatus, actors));
        assertEquals("a share is its sharer's", PostSources.FRIEND + "ARE_FRIENDS",
                PostSources.friendKind(friendSharesAStranger, actors));
        assertEquals(PostSources.NOT_A_FRIEND + "CAN_REQUEST", PostSources.friendKind(strangerSharesAFriend, actors));
        assertEquals(PostSources.UNKNOWN_AUTHOR, PostSources.friendKind(unread, actors));
        assertEquals("a released author isn't asked", PostSources.UNKNOWN_AUTHOR, PostSources.friendKind(released, actors));
        assertEquals(PostSources.NO_AUTHORS, PostSources.friendKind(nobody, actors));
        assertEquals(PostSources.Outcome.NOT_A_STORY.reason, PostSources.friendKind(new Object(), actors));
        assertEquals(PostSources.Outcome.NO_UNIT.reason, PostSources.friendKind(null, actors));
        assertEquals(PostSources.Outcome.NO_ACCESSOR.reason, PostSources.friendKind(page, PostSources.ACTORS));
        assertEquals(PostSources.Outcome.READ_FAILED.reason, PostSources.friendKind(page, story -> {
            throw new IllegalStateException("renamed");
        }));

        assertTrue(PostSources.notAFriend(PostSources.friendKind(page, actors)));
        assertFalse(PostSources.notAFriend(PostSources.friendKind(friendsPost, actors)));
        assertFalse("an author it can't read keeps the post", PostSources.notAFriend(PostSources.UNKNOWN_AUTHOR));
        assertFalse(PostSources.notAFriend(PostSources.Outcome.NO_ACCESSOR.reason));
        assertFalse(PostSources.notAFriend(null));
    }

    @Test
    public void onlyFriendsPostsTakesEveryPostThatIsntAFriendsAndCountsEveryKind() {
        GraphQLStory friendsPost = story(null, Arrays.asList(person("ARE_FRIENDS")), Collections.emptyList());
        GraphQLStory page = story(null, Arrays.asList(typed("Page")), Collections.emptyList());
        GraphQLStory strangersPost = story(null, Arrays.asList(person("CAN_REQUEST")), Collections.emptyList());

        assertNull(FeedFilter.friendsReason(friendsPost, actors));
        assertEquals(PostSources.NOT_A_FRIEND + "Page", FeedFilter.friendsReason(page, actors));
        assertEquals(PostSources.NOT_A_FRIEND + "CAN_REQUEST", FeedFilter.friendsReason(strangersPost, actors));
        assertEquals(FeedFilter.FRIENDS_ROUTE + ": 3 lists, 3 items, 2 removed. Last reason: not a friend: CAN_REQUEST. "
                + "Removed: not a friend: CAN_REQUEST 1, not a friend: Page 1. "
                + "Kinds: friend: ARE_FRIENDS 1, not a friend: CAN_REQUEST 1, not a friend: Page 1",
                line(FeedFilter.FRIENDS_ROUTE));
    }

    @Test
    public void onlyFriendsPostsTurnsOnTheSwitchesThatTakeTheRestWithoutSavingThem() {
        assertFalse("off until picked", Settings.ONLY_FRIENDS_POSTS.defaultValue);
        List<app.morphe.extension.shared.settings.BooleanSetting> implied = Arrays.asList(
                Settings.HIDE_SPONSORED_POSTS, Settings.HIDE_SUGGESTED_POSTS, Settings.HIDE_PEOPLE_YOU_MAY_KNOW,
                Settings.HIDE_SUGGESTED_GROUPS, Settings.HIDE_GROUP_POSTS, Settings.HIDE_PAGE_POSTS,
                Settings.HIDE_FEED_REELS);
        for (app.morphe.extension.shared.settings.BooleanSetting setting : implied) {
            setting.save(false);
            assertFalse(setting.key, FeedFilter.withFriendsOnly(setting));
        }
        Settings.ONLY_FRIENDS_POSTS.save(true);
        for (app.morphe.extension.shared.settings.BooleanSetting setting : implied) {
            assertTrue(setting.key, FeedFilter.withFriendsOnly(setting));
            assertFalse("nothing saved: " + setting.key, setting.get());
            setting.resetToDefault();
        }
    }

    @Test
    public void theFeedGuardAsksAboutFriendsOnlyWithTheSwitchOnAndNoPause() {
        GraphQLStory page = story(null, Arrays.asList(typed("Page")), Collections.emptyList());
        assertFalse(guard(page, true));
        assertNull("nothing read with the switch off", line(FeedFilter.FRIENDS_ROUTE));

        Settings.ONLY_FRIENDS_POSTS.save(true);
        assertFalse(guard(page, false));
        assertNull("nothing read without Hide posts by words", line(FeedFilter.FRIENDS_ROUTE));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(guard(page, true));
        assertNull("nothing read while paused", line(FeedFilter.FRIENDS_ROUTE));

        PauseForTests.resume();
        assertFalse("a build whose authors stub isn't filled keeps the post", guard(page, true));
        assertEquals(FeedFilter.FRIENDS_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: "
                + PostSources.Outcome.NO_ACCESSOR.reason + " 1", line(FeedFilter.FRIENDS_ROUTE));
        assertEquals("Pages are asked about too, with their own switch off",
                FeedFilter.PAGES_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: "
                + PostSources.Outcome.NO_ACCESSOR.reason + " 1", line(FeedFilter.PAGES_ROUTE));
    }

    /** The line a route wrote, or null when nothing counted on it. */
    private static String line(String route) {
        for (String reported : FeedFilterCounters.report()) {
            if (reported.startsWith(route + ": ")) return reported;
        }
        return null;
    }
}
