/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;
import com.facebook.graphql.model.GraphQLTextWithEntities;
import com.facebook.graphql.modelutil.BaseModelWithTree;

import org.json.JSONObject;
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

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The word filter in the shared feed guard: a phrase from the hide list in a post's own words, or
 * in the post it shares, hides it; a phrase from the keep list keeps it; a post with no words, or
 * with a part that can't be read, stays. It counts on a route of its own with shapes only, and
 * reads nothing of a post while its switch is off, its hide list is empty or Hushfacebook is paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostWordsRuleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Category { ORGANIC, SPONSORED }

    /** Stands in for an accessor the patch fills in: a model per story, and how often it was asked. */
    private static final class Answers implements StoryFlag.Accessor {
        private final Map<Object, Object> models = new IdentityHashMap<>();
        private final Object otherwise;
        int calls;

        Answers(Object otherwise) {
            this.otherwise = otherwise;
        }

        Answers with(Object story, Object model) {
            models.put(story, model);
            return this;
        }

        @Override
        public Object model(Object story) {
            calls++;
            Object model = models.containsKey(story) ? models.get(story) : otherwise;
            if (model instanceof RuntimeException) throw (RuntimeException) model;
            return model;
        }
    }

    /** The count of hidden posts is the process's, and other test classes in this JVM hide posts too. */
    @Before
    public void forgetHiddenPosts() {
        PostWords.forgetForTests();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_POSTS_WITH_WORDS.resetToDefault();
        Settings.POST_WORDS_WHOLE_WORDS.resetToDefault();
        Settings.POST_WORDS_ON_FEED.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        PostText.cachedMembers = null;
        PostWords.forgetForTests();
        FeedFilterCounters.clear();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static boolean guard(Object category, Object unit, StoryFlag.Accessor message, StoryFlag.Accessor attached) {
        return FeedFilter.hideEdge(category, unit, true, true, story -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, true, message, attached);
    }

    /** The guard for a story whose message holds [text] and which shares nothing. */
    private static boolean guard(String text) {
        return guard(Category.ORGANIC, new GraphQLStory(), new Answers(FeedGuardForTests.postText(text)), new Answers(null));
    }

    private static void listen(String hide, String keep) {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDDEN_WORDS.save(hide);
        Settings.KEPT_WORDS.save(keep);
    }

    /** The line the word filter's route wrote, or null when the rule never ran. */
    private static String wordsLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedFilter.WORDS_ROUTE + ": ")) return line;
        }
        return null;
    }

    private static String feedLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedFilter.FEED_ROUTE + ": ")) return line;
        }
        return null;
    }

    /** The keys are the ones read from Facebook 577 and 580. The patch's own test pins the same numbers. */
    @Test
    public void theKeysAreTheOnesFacebookUses() {
        assertEquals(0xdb1d8904, PostText.TEXT_TYPE_TAG);
        assertEquals(0x36452d, PostText.TEXT_KEY);
        assertEquals(0x38eb0007, PostText.MESSAGE_FIELD.hashCode());
    }

    @Test
    public void theSwitchStartsOffAndTheListsStartEmpty() {
        assertFalse(Settings.HIDE_POSTS_WITH_WORDS.defaultValue);
        assertEquals("", Settings.HIDDEN_WORDS.defaultValue);
        assertEquals("", Settings.KEPT_WORDS.defaultValue);
    }

    /**
     * The News feed has its own switch under the rule (#458). It starts on, so the rule reaches
     * Home as it did, and off, Home's posts keep their words and none is read.
     */
    @Test
    public void theNewsFeedSwitchKeepsHomesPosts() {
        assertTrue(Settings.POST_WORDS_ON_FEED.defaultValue);
        listen("spoiler", "");
        Settings.POST_WORDS_ON_FEED.save(false);
        Answers answers = new Answers(FeedGuardForTests.postText("Huge SPOILER for tonight"));

        assertFalse(guard(Category.ORGANIC, new GraphQLStory(), answers, new Answers(null)));
        assertEquals("nothing was read with the News feed switch off", 0, answers.calls);
        assertNull(wordsLine());

        Settings.POST_WORDS_ON_FEED.save(true);
        assertTrue(guard("Huge SPOILER for tonight"));
    }

    @Test
    public void aPostWithAListedPhraseIsHidden() {
        listen("spoiler\ngiveaway", "");
        BaseModelWithTree message = FeedGuardForTests.postText("Huge SPOILER for tonight");
        Answers answers = new Answers(message);

        assertTrue(guard(Category.ORGANIC, new GraphQLStory(), answers, new Answers(null)));
        assertEquals("the message was asked for once", 1, answers.calls);
        assertEquals(FeedFilter.FEED_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: word filter. "
                + "Removed: word filter 1. Kinds: ORGANIC 1", feedLine());
        assertEquals(FeedFilter.WORDS_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: hide word. "
                + "Removed: hide word 1. Kinds: hide word 1", wordsLine());
        assertEquals(1, PostWords.hiddenSinceStart());
    }

    @Test
    public void theWholeWordSwitchStartsOffAndChangesOnlyBoundaries() {
        assertFalse(Settings.POST_WORDS_WHOLE_WORDS.defaultValue);
        assertFalse(Settings.POST_WORDS_WHOLE_WORDS.savedValue());
        listen("hat\ncopycat", "cat");
        assertTrue(guard("what"));
        Settings.POST_WORDS_WHOLE_WORDS.save(true);
        assertFalse(guard("what"));
        assertTrue(guard("hat!"));
        assertTrue(guard("copycat"));
        assertFalse(guard("copycat and cat"));
        Settings.POST_WORDS_WHOLE_WORDS.save(false);
        assertTrue(guard("what"));
    }

    @Test
    public void wholeWordModeReadsNothingWithTheFilterOffOrPaused() {
        Settings.POST_WORDS_WHOLE_WORDS.save(true);
        Settings.HIDDEN_WORDS.save("hat");
        Answers answers = new Answers(FeedGuardForTests.postText("hat!"));
        assertFalse(guard(Category.ORGANIC, new GraphQLStory(), answers, new Answers(null)));
        assertEquals(0, answers.calls);
        listen("hat", "");
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(guard(Category.ORGANIC, new GraphQLStory(), answers, new Answers(null)));
            assertEquals(0, answers.calls);
            assertTrue(Settings.POST_WORDS_WHOLE_WORDS.savedValue());
        }
        PauseForTests.resume();
        assertTrue(guard(Category.ORGANIC, new GraphQLStory(), answers, new Answers(null)));
        assertEquals(1, answers.calls);
    }

    @Test
    public void wholeWordDiagnosticsContainOnlyShapesAndCounts() {
        BaseSettings.DEBUG.save(true);
        listen("zanzibarquux", "pomegranatekeep");
        Settings.POST_WORDS_WHOLE_WORDS.save(true);
        assertFalse(guard("zanzibarquuxes"));
        assertTrue(guard("zanzibarquux!"));
        assertFalse(guard("zanzibarquux and pomegranatekeep"));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(FeedFilter.WORDS_ROUTE + ": 3 lists, 3 items, 1 removed"));
        assertFalse(report, report.toLowerCase().contains("zanzibarquux"));
        assertFalse(report, report.toLowerCase().contains("pomegranatekeep"));
    }

    /** The control for the one above: the same post without the phrase stays. */
    @Test
    public void aPostWithoutOneStays() {
        listen("spoiler", "");
        assertFalse(guard("Nothing to see here"));
        assertEquals(FeedFilter.WORDS_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: no match 1", wordsLine());
        assertEquals(0, PostWords.hiddenSinceStart());
    }

    @Test
    public void aKeepPhraseWinsInThePostOrInTheOneItShares() {
        listen("spoiler", "my team");
        assertFalse(guard("A spoiler about my team"));

        GraphQLStory shared = new GraphQLStory();
        GraphQLStory story = new GraphQLStory(shared);
        Answers messages = new Answers(null)
                .with(story, FeedGuardForTests.postText("MY TEAM, again"))
                .with(shared, FeedGuardForTests.postText("spoiler inside"));
        assertFalse(guard(Category.ORGANIC, story, messages, new Answers(null).with(story, shared)));
        assertEquals(FeedFilter.WORDS_ROUTE + ": 2 lists, 2 items, 0 removed. Kinds: keep word 2", wordsLine());
    }

    @Test
    public void aSharedPostsWordsCountToo() {
        listen("spoiler", "");
        GraphQLStory shared = new GraphQLStory();
        GraphQLStory story = new GraphQLStory(shared);
        Answers messages = new Answers(null).with(shared, FeedGuardForTests.postText("spoiler inside"));
        assertTrue("a share with no words of its own, of a post with the phrase",
                guard(Category.ORGANIC, story, messages, new Answers(null).with(story, shared)));
    }

    /**
     * A post with no words, or one with a part that can't be read, stays and says why: a keep
     * phrase could be in the part that wasn't read.
     */
    @Test
    public void missingOrUnreadableTextKeepsThePostAndSaysWhy() {
        listen("spoiler", "");
        GraphQLStory story = new GraphQLStory();
        Answers none = new Answers(null);

        assertFalse("no message", guard(Category.ORGANIC, story, none, none));
        assertFalse("an empty message", guard(""));
        assertFalse("a message of spaces", guard("  \n "));
        BaseModelWithTree otherType = new BaseModelWithTree(StoryFlag.typeTag("XFBTextWithEntities"))
                .with(PostText.TEXT_FIELD, "spoiler");
        assertFalse("a model of another type", guard(Category.ORGANIC, story, new Answers(otherType), none));
        assertFalse("a model that isn't a tree", guard(Category.ORGANIC, story, new Answers(new Object()), none));
        assertFalse("a message accessor that throws",
                guard(Category.ORGANIC, story, new Answers(new IllegalStateException()), none));
        assertFalse("a message accessor never filled in", guard(Category.ORGANIC, story, PostText.MESSAGE, none));
        Answers spoiler = new Answers(FeedGuardForTests.postText("spoiler"));
        assertFalse("a shared post accessor that throws",
                guard(Category.ORGANIC, story, spoiler, new Answers(new IllegalStateException())));
        assertFalse("a shared post accessor never filled in", guard(Category.ORGANIC, story, spoiler, PostText.ATTACHED));
        GraphQLStory shared = new GraphQLStory();
        Answers sharedUnreadable = new Answers(FeedGuardForTests.postText("spoiler")).with(shared, new Object());
        assertFalse("a shared post whose text can't be read",
                guard(Category.ORGANIC, story, sharedUnreadable, new Answers(null).with(story, shared)));
        assertFalse("not a story", guard(Category.ORGANIC, new Object(), spoiler, none));
        assertFalse("no feed unit", guard(Category.ORGANIC, null, spoiler, none));

        assertEquals(FeedFilter.WORDS_ROUTE + ": 12 lists, 12 items, 0 removed. Kinds: no text 3, "
                        + "accessor not patched 2, ambiguous: text not a tree model 2, read failed 2, "
                        + "ambiguous: text of another type 1, no feed unit 1, not a story 1",
                wordsLine());
        assertEquals(0, PostWords.hiddenSinceStart());
        List<String> misses = HookStatus.missing(FamilyNames.POST_WORDS);
        assertTrue(misses.toString(), misses.contains("method com.facebook.graphql.model.GraphQLStory#the message accessor"));
        assertTrue(misses.toString(), misses.contains("method com.facebook.graphql.model.GraphQLStory#the attached_story accessor"));
    }

    /** Off, with the hide list empty, or paused, the rule asks nothing of the post. */
    @Test
    public void offEmptyOrPausedTheRuleReadsNothing() {
        Settings.HIDDEN_WORDS.save("spoiler");
        Answers answers = new Answers(FeedGuardForTests.postText("spoiler"));
        assertFalse("switch off", guard(Category.ORGANIC, new GraphQLStory(), answers, answers));
        assertEquals(0, answers.calls);
        assertNull("the rule counted a post with its switch off", wordsLine());

        listen("", "my team");
        assertFalse("hide list empty", guard(Category.ORGANIC, new GraphQLStory(), answers, answers));
        assertEquals(0, answers.calls);
        assertEquals(FeedFilter.WORDS_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: no words listed 1", wordsLine());

        listen("spoiler", "");
        FeedFilterCounters.clear();
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(why + " hid a post", guard(Category.ORGANIC, new GraphQLStory(), answers, answers));
            assertEquals(why + " asked the accessor", 0, answers.calls);
        }
        assertNull(wordsLine());
        PauseForTests.resume();
        assertTrue("the rule didn't come back after the pause",
                guard(Category.ORGANIC, new GraphQLStory(), answers, new Answers(null)));
    }

    /** Without the patch the rule doesn't run whatever the switch says, and the public guard is that. */
    @Test
    public void anUnpatchedBuildHidesNothing() {
        listen("spoiler", "");
        Answers answers = new Answers(FeedGuardForTests.postText("spoiler"));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new GraphQLStory(), true, true, story -> null, false,
                GenAiLabel.PATCHED, false, ShowcaseType.PATCHED, false, answers, answers));
        assertEquals(0, answers.calls);
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new GraphQLStory()));
        assertNull(wordsLine());
    }

    /** A post an earlier rule already hid isn't read. */
    @Test
    public void anEarlierRuleWins() {
        listen("spoiler", "");
        Answers answers = new Answers(FeedGuardForTests.postText("spoiler"));
        assertTrue(guard(Category.SPONSORED, new GraphQLStory(), answers, answers));
        assertEquals(0, answers.calls);
        assertNull(wordsLine());
    }

    /**
     * Nothing of a post's words or of a phrase reaches the diagnostic report, the log or Hook
     * status, with Debug logging on: only the shapes and the counts do.
     */
    @Test
    public void noWordOrPhraseReachesTheReport() {
        BaseSettings.DEBUG.save(true);
        listen("zanzibarquux\nmangosteen", "pomegranatekeep");
        assertTrue(guard("I spotted a ZANZIBARQUUX"));
        assertFalse(guard("pomegranatekeep and mangosteen"));
        assertFalse(guard("plain words only, xylophonic"));
        assertFalse(guard(Category.ORGANIC, new GraphQLStory(), new Answers(new IllegalStateException()), new Answers(null)));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(FeedFilter.WORDS_ROUTE + ": 4 lists, 4 items, 1 removed"));
        assertTrue(report, report.contains(FamilyNames.POST_WORDS + ": invoked 4"));
        for (String secret : Arrays.asList("zanzibarquux", "mangosteen", "pomegranatekeep", "xylophonic", "spotted")) {
            assertFalse("the report carries " + secret + ":\n" + report, report.toLowerCase().contains(secret));
        }
    }

    /** A link card: an attachment with a headline, and the line under it when [description] isn't null. */
    private static GraphQLStoryAttachment card(String title, String description) {
        GraphQLStoryAttachment card = new GraphQLStoryAttachment((GraphQLMedia) null);
        if (title != null) card.with("title", title);
        if (description != null) card.withModel("description", new GraphQLTextWithEntities(description));
        return card;
    }

    private static String words(Object story, StoryFlag.Accessor message, StoryFlag.Accessor attached,
            StoryFlag.Accessor links) {
        return FeedFilter.wordsReason(story, message, attached, links, PostText.ACTION_LINKS);
    }

    /** An attachment's action link, holding the headline 584 shows on a link card. */
    private static BaseModelWithTree actionLink(String headline) {
        return new BaseModelWithTree(0).with("link_title", headline);
    }

    /**
     * #255 on 584: a card whose bold headline is its first action link's link_title or its
     * title_with_entities, with no plain title, as Juan Carlos's YouTube card in a Florida
     * gardening group read on the S22 2026-10-27, which the plain title alone kept.
     */
    @Test
    public void anActionLinksHeadlineOrTheTitleTextCounts() {
        listen("pineapple pleasure", "");
        Answers noMessage = new Answers(null);
        Answers noShare = new Answers(null);
        GraphQLStory linked = new GraphQLStory();
        GraphQLStory titled = new GraphQLStory();
        GraphQLStoryAttachment youtube = card(null, null);
        GraphQLStoryAttachment titleText = card(null, null);
        titleText.withModel("title_with_entities", new GraphQLTextWithEntities("Pineapple pleasure mango taste test"));
        Answers links = new Answers(null)
                .with(linked, Arrays.asList(youtube))
                .with(titled, Arrays.asList(titleText));
        Answers actionLinks = new Answers(null)
                .with(youtube, Arrays.asList(actionLink("Pineapple Pleasure mango taste test")));

        assertEquals(FeedFilter.WORDS_REASON, FeedFilter.wordsReason(linked, noMessage, noShare, links, actionLinks));
        assertEquals(FeedFilter.WORDS_REASON, FeedFilter.wordsReason(titled, noMessage, noShare, links, actionLinks));
        assertNull("the control: the same card read before this fix, without the action links",
                FeedFilter.wordsReason(linked, noMessage, noShare, links, PostText.ACTION_LINKS));
        assertNull("an action link that isn't a tree model is skipped", FeedFilter.wordsReason(linked, noMessage, noShare,
                links, new Answers(null).with(youtube, Arrays.asList(new Object(), null))));

        listen("pineapple pleasure", "mango");
        assertNull("a keep phrase in the action link keeps the post",
                FeedFilter.wordsReason(linked, noMessage, noShare, links, actionLinks));
        assertNull("an action links accessor that throws keeps the post", FeedFilter.wordsReason(linked,
                new Answers(FeedGuardForTests.postText("pineapple pleasure")), noShare, links,
                new Answers(new IllegalStateException())));
        assertEquals(FeedFilter.WORDS_ROUTE + ": 6 lists, 6 items, 2 removed. Last reason: hide word. "
                + "Removed: hide word 2. Kinds: hide word 2, no text 2, keep word 1, read failed 1", wordsLine());
    }

    /** #255: a news post whose only mention of the word is its link card's headline or description. */
    @Test
    public void aLinkCardsHeadlineOrDescriptionCounts() {
        listen("us election", "");
        Answers noMessage = new Answers(null);
        Answers noShare = new Answers(null);
        GraphQLStory headline = new GraphQLStory();
        GraphQLStory description = new GraphQLStory();
        Answers links = new Answers(null)
                .with(headline, Arrays.asList(card("What the US election means for you", null)))
                .with(description, Arrays.asList(card("Tonight's results", "Live updates from the US Election")));

        assertEquals(FeedFilter.WORDS_REASON, words(headline, noMessage, noShare, links));
        assertEquals(FeedFilter.WORDS_REASON, words(description, noMessage, noShare, links));
        assertEquals(FeedFilter.WORDS_ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: hide word. "
                + "Removed: hide word 2. Kinds: hide word 2", wordsLine());
    }

    /**
     * A Bloks-drawn card's debug string, shaped like the sagesacre.com card the S22 read 2026-10-27:
     * an image whose spoken label sits under the same key as a Text component's string, the domain,
     * an ellipsis, then the headline in a nested box.
     */
    private static String bloksCard(String headline) {
        String text = PostText.BLOKS_TEXT_COMPONENT;
        String box = String.valueOf((char) 0x3408);
        String image = String.valueOf((char) 0x340B);
        String layout = "{\"layout\":{\"bloks_payload\":{\"data\":[],\"tree\":{\"" + box + "\":{\" \":["
                + "{\"" + image + "\":{\"$\":\"Image\",\"&\":\"Image\"}},"
                + "{\"" + text + "\":{\"&\":\"sagesacre.com\",\"*\":\"13.00sp\"}},"
                + "{\"" + text + "\":{\"&\":" + JSONObject.quote(String.valueOf((char) 0x2026)) + "}},"
                + "{\"" + box + "\":{\" \":[{\"" + text + "\":{\"&\":" + JSONObject.quote(headline)
                + ",\"*\":\"15.00sp\",\"#\":\"sans-serif-medium\"}}]}}]}}}}}";
        return "{\"style_infos\":[{\"bundle\":{\"bloks_bundle_tree\":" + JSONObject.quote(layout) + "}}],\"title\":null}";
    }

    /**
     * #255 on 584: a card drawn from a Bloks layout with every field above null, as Zone9Garden.com's
     * sagesacre.com card in a Florida gardening group read on the S22 2026-10-27.
     */
    @Test
    public void aBloksCardsHeadlineCounts() {
        listen("fall & winter garden", "");
        Answers noMessage = new Answers(null);
        Answers noShare = new Answers(null);
        GraphQLStory story = new GraphQLStory();
        GraphQLStoryAttachment bloks = card(null, null);
        bloks.debugString(bloksCard("The 10 Best Vegetables to Plant Now for a Zone 9-10 Fall & Winter Garden"));
        Answers links = new Answers(null).with(story, Arrays.asList(bloks));
        assertEquals(FeedFilter.WORDS_REASON, words(story, noMessage, noShare, links));

        listen("image", "");
        assertNull("an image's spoken label isn't the card's text", words(story, noMessage, noShare, links));
        listen("fall & winter garden", "vegetables");
        assertNull("a keep phrase in the layout keeps the post", words(story, noMessage, noShare, links));

        listen("fall & winter garden", "");
        GraphQLStory titled = new GraphQLStory();
        GraphQLStoryAttachment plain = card("Tonight's results", null);
        plain.debugString(bloksCard("Fall & Winter Garden"));
        assertNull("a card with a title isn't read through its layout",
                words(titled, noMessage, noShare, new Answers(null).with(titled, Arrays.asList(plain))));
        assertEquals(0, plain.debugReads);

        GraphQLStory gone = new GraphQLStory();
        GraphQLStoryAttachment released = card(null, null);
        released.debugString(bloksCard("Fall & Winter Garden"));
        released.released();
        assertNull("a released card's layout isn't read",
                words(gone, noMessage, noShare, new Answers(null).with(gone, Arrays.asList(released))));
        assertEquals(0, released.debugReads);
        assertFalse(released.readAfterRelease);
        assertEquals(FeedFilter.WORDS_ROUTE + ": 5 lists, 5 items, 1 removed. Last reason: hide word. "
                + "Removed: hide word 1. Kinds: no match 2, hide word 1, keep word 1, no text 1", wordsLine());
    }

    /** Only Text components count, every layout in the string is read, and one that won't parse adds nothing. */
    @Test
    public void bloksTextsReadsEachTextComponent() {
        assertEquals(Arrays.asList("sagesacre.com", "Headline"), PostText.bloksTexts(bloksCard("Headline")));
        assertEquals(Arrays.asList("sagesacre.com", "One", "sagesacre.com", "Two"),
                PostText.bloksTexts(bloksCard("One") + bloksCard("Two")));
        assertEquals(Collections.emptyList(), PostText.bloksTexts("{\"bloks_bundle_tree\":\"{not json\"}"));
        assertEquals(Collections.emptyList(), PostText.bloksTexts("{\"bloks_bundle_tree\":null}"));
        assertEquals(Collections.emptyList(), PostText.bloksTexts("{\"bloks_bundle_tree\":"));
        assertEquals(Collections.emptyList(), PostText.bloksTexts("no layout here"));
    }

    /** The controls: a card without the phrase, a keep phrase on the card, and a card on the shared post. */
    @Test
    public void aLinkCardIsJudgedLikeThePostsOwnWords() {
        listen("us election", "my team");
        Answers noShare = new Answers(null);
        GraphQLStory plain = new GraphQLStory();
        GraphQLStory kept = new GraphQLStory();
        Answers links = new Answers(null)
                .with(plain, Arrays.asList(card("Weather this weekend", "Sunny and 72")))
                .with(kept, Arrays.asList(card("US election night", "What it means for my team")));
        assertNull(words(plain, new Answers(null), noShare, links));
        assertNull(words(kept, new Answers(null), noShare, links));
        assertNull("the message's keep phrase still keeps a post whose card has the hidden one",
                words(plain, new Answers(FeedGuardForTests.postText("Go my team")), noShare,
                        new Answers(Arrays.asList(card("US election", null)))));

        GraphQLStory shared = new GraphQLStory();
        GraphQLStory share = new GraphQLStory(shared);
        Answers sharedCard = new Answers(null).with(shared, Arrays.asList(card("US election recap", null)));
        assertEquals("a share of a post whose card has the phrase", FeedFilter.WORDS_REASON,
                words(share, new Answers(null), new Answers(null).with(share, shared), sharedCard));
        assertEquals(FeedFilter.WORDS_ROUTE + ": 4 lists, 4 items, 1 removed. Last reason: hide word. "
                + "Removed: hide word 1. Kinds: keep word 2, hide word 1, no match 1", wordsLine());
    }

    /**
     * An attachments accessor the patch never filled in reads the message alone, as before #255,
     * and one that throws keeps the post: a keep phrase could be on the card it couldn't read.
     */
    @Test
    public void anUnreadableLinkCardKeepsTheOldRuleOrThePost() {
        listen("spoiler", "");
        Answers spoiler = new Answers(FeedGuardForTests.postText("a spoiler"));
        Answers noShare = new Answers(null);
        assertEquals(FeedFilter.WORDS_REASON, words(new GraphQLStory(), spoiler, noShare, PostSources.ATTACHMENTS));
        assertNull(words(new GraphQLStory(), new Answers(null), noShare, PostSources.ATTACHMENTS));
        assertNull(words(new GraphQLStory(), spoiler, noShare, new Answers(new IllegalStateException())));
        assertNull("a list of things that aren't cards",
                words(new GraphQLStory(), new Answers(null), noShare, new Answers(Arrays.asList(new Object(), null))));
        assertEquals(FeedFilter.WORDS_ROUTE + ": 4 lists, 4 items, 1 removed. Last reason: hide word. "
                + "Removed: hide word 1. Kinds: no text 2, hide word 1, read failed 1", wordsLine());
    }

    /**
     * A build missing a member the reader needs keeps every post, and the Hook status row names
     * what's missing. The control, with every member there, reports three found.
     */
    @Test
    public void theReportSaysWhetherTheReaderFoundWhatItNeeds() {
        listen("spoiler", "");
        guard(Category.ORGANIC, new GraphQLStory(), new Answers(null), new Answers(null));
        List<String> found = HookStatus.report();
        assertTrue(String.join("\n", found), found.contains(FamilyNames.POST_WORDS + ": invoked 1, 3 found, 0 missing"));

        HookStatus.clear();
        FeedFilterCounters.clear();
        PostText.Members complete = PostText.Members.lookUp(PostText.class.getClassLoader());
        PostText.cachedMembers = new PostText.Members(complete.story, complete.treeModel, null, complete.typeTag,
                complete.text, complete.cachedModel, complete.debugString);
        assertFalse(guard("spoiler"));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.POST_WORDS + ": invoked 1, 2 found, 1 missing. "
                + "First missing: method com.facebook.graphql.modelutil.BaseModelWithTree#getCachedString(int)"));
        assertEquals(FeedFilter.WORDS_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: reader missing 1", wordsLine());
    }
}
