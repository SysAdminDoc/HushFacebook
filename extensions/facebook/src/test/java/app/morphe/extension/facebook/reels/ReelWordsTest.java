/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.feed.PostWordsForTests;
import app.morphe.extension.facebook.reels.FollowedReelsForTests.FollowStatus;
import app.morphe.extension.facebook.reels.ReelLanguagesTest.Reel;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests.Section;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide posts with words you choose and Hide link posts on the Reels tab (#458): on, a reel whose
 * caption holds a hidden phrase, or a web address off Facebook like the "Watch More: https://..."
 * drama reels, comes off its page and its section. A kept phrase wins, a facebook.com link stays,
 * and a captionless reel stays, each counted under why. Off or paused, the tab is stock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelWordsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String DRAMA = "She married the billionaire's son 😱 Watch More: https://page.dramabuzzs.co";
    private static final String BARE_SITE = "Full episode at www.shortdrama.example/ep1";
    private static final String FACEBOOK_LINK = "More from us at https://www.facebook.com/somepage";
    private static final String PLAIN = "Morning walk by the lake with the dog";
    private static final String SPOILER = "Big spoiler for the finale tonight";
    private static final String KEPT_SPOILER = "Big spoiler for the finale tonight, recipe inside";

    /** Each story's caption, null for a reel with none. */
    private final Map<Object, String> captions = new IdentityHashMap<>();

    private final ReelWords.Readers readers = new ReelWords.Readers(
            story -> { throw new AssertionError("the language rule is off"); },
            story -> PostWordsForTests.storyKind(story, captions::get),
            story -> PostWordsForTests.captionLinkKind(story, captions::get));

    @Before
    public void lists() {
        PostWordsForTests.forget();
        Settings.HIDDEN_WORDS.save("spoiler");
        Settings.KEPT_WORDS.save("recipe");
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_POSTS_WITH_WORDS.resetToDefault();
        Settings.HIDE_LINK_POSTS.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        Settings.FOLLOWED_REELS_ONLY.resetToDefault();
        PostWordsForTests.forget();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private Reel reel(FollowStatus status, String caption) {
        Reel reel = new Reel(status);
        if (caption != null) captions.put(reel.story, caption);
        return reel;
    }

    private Reel reel(String caption) {
        return reel(FollowStatus.CAN_SUBSCRIBE, caption);
    }

    private Collection<?> filter(Collection<?> page) {
        return FollowedReels.followedOnly(page, FollowedReelsForTests.MODEL, FollowedReelsForTests.STATUSES,
                readers, FollowedReels.PAGES_ROUTE);
    }

    private static String line(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ": ")) return line;
        }
        return null;
    }

    /** Off, which is how both switches ship, the Reels tab is stock even with words listed. */
    @Test
    public void offEveryReelStays() {
        assertFalse(Settings.HIDE_POSTS_WITH_WORDS.defaultValue);
        assertFalse(Settings.HIDE_LINK_POSTS.defaultValue);
        List<Object> page = Arrays.asList(reel(SPOILER), reel(DRAMA));

        assertSame(page, filter(page));
    }

    /** Words on, the reel with a hidden phrase goes, the kept phrase saves its reel, and every read is counted. */
    @Test
    public void wordsOnAReelWithAHiddenPhraseComesOff() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Reel spoiler = reel(SPOILER);
        Reel keptSpoiler = reel(KEPT_SPOILER);
        Reel plain = reel(PLAIN);
        Reel drama = reel(DRAMA);
        Reel captionless = reel(null);
        Object card = new Object();

        Collection<?> kept = filter(Arrays.asList(spoiler, keptSpoiler, plain, drama, captionless, card));

        assertEquals("only the words rule is on, so the linked reel stays",
                Arrays.asList(keptSpoiler, plain, drama, captionless, card), new ArrayList<>(kept));
        String reels = line(ReelWords.WORDS.route);
        assertNotNull(FeedFilterCounters.report().toString(), reels);
        assertTrue(reels, reels.contains("Removed: " + PostWordsForTests.HIDE_WORD + " 1"));
        assertTrue(reels, reels.contains(PostWordsForTests.KEEP_WORD + " 1"));
        assertTrue(reels, reels.contains(PostWordsForTests.NO_MATCH + " 2"));
        assertTrue(reels, reels.contains(PostWordsForTests.NO_TEXT + " 1"));
        assertTrue(reels, reels.contains(ReelLanguages.NO_STORY + " 1"));
        String pages = line(FollowedReels.PAGES_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), pages);
        assertTrue(pages, pages.contains(FollowedReels.WORDS_REASON + " 1"));
        assertEquals("the settings row counts the reel with the posts", 1, PostWordsForTests.hidden());
    }

    /** Links on, a caption naming a website goes, with or without its https, and a Facebook link stays. */
    @Test
    public void linksOnAReelWhoseCaptionNamesASiteComesOff() {
        Settings.HIDE_LINK_POSTS.save(true);
        Reel drama = reel(DRAMA);
        Reel bareSite = reel(BARE_SITE);
        Reel facebook = reel(FACEBOOK_LINK);
        Reel spoiler = reel(SPOILER);

        Collection<?> kept = filter(Arrays.asList(drama, bareSite, facebook, spoiler));

        assertEquals(Arrays.asList(facebook, spoiler), new ArrayList<>(kept));
        String reels = line(ReelWords.LINKS.route);
        assertNotNull(FeedFilterCounters.report().toString(), reels);
        assertTrue(reels, reels.contains("Removed: " + PostWordsForTests.CAPTION_LINK + " 2"));
        assertTrue(reels, reels.contains(PostWordsForTests.NO_SITE_NAMED + " 2"));
        String pages = line(FollowedReels.PAGES_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), pages);
        assertTrue(pages, pages.contains(FollowedReels.LINKS_REASON + " 2"));
        assertEquals("a link isn't counted as words", 0, PostWordsForTests.hidden());
    }

    /** A reel inside a section comes out of it too, and a section left empty goes. */
    @Test
    public void aSectionLosesItsLinkedAndWordedReels() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        Reel plain = reel(PLAIN);
        Section mixed = new Section(new ArrayList<>(Arrays.asList(reel(DRAMA), plain, reel(SPOILER))));
        Section onlyDrama = new Section(new ArrayList<>(Collections.singletonList(reel(DRAMA))));

        List<?> kept = FollowedReels.followedSections(null, Arrays.asList(mixed, onlyDrama),
                FollowedReelsForTests.MODEL, FollowedReelsForTests.STATUSES, readers);

        assertEquals(Collections.singletonList(mixed), kept);
        assertEquals(Collections.singletonList(plain), mixed.items);
        String sections = line(FollowedReels.SECTIONS_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), sections);
        assertTrue(sections, sections.contains(FollowedReels.LINKS_REASON + " 2"));
        assertTrue(sections, sections.contains(FollowedReels.WORDS_REASON + " 1"));
    }

    /** With Followed reels only on too, a reel it takes is counted once, as not followed, and its caption isn't read. */
    @Test
    public void theFollowRuleReadsFirst() {
        Settings.HIDE_LINK_POSTS.save(true);
        Settings.FOLLOWED_REELS_ONLY.save(true);
        Reel notFollowedDrama = reel(FollowStatus.CAN_SUBSCRIBE, DRAMA);
        Reel followedDrama = reel(FollowStatus.IS_SUBSCRIBED, DRAMA);
        Reel followedPlain = reel(FollowStatus.IS_SUBSCRIBED, PLAIN);

        Collection<?> kept = filter(Arrays.asList(notFollowedDrama, followedDrama, followedPlain));

        assertEquals(Collections.singletonList(followedPlain), new ArrayList<>(kept));
        String pages = line(FollowedReels.PAGES_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), pages);
        assertTrue(pages, pages.contains(FollowedReels.NOT_FOLLOWED + " 1"));
        assertTrue(pages, pages.contains(FollowedReels.LINKS_REASON + " 1"));
        String reels = line(ReelWords.LINKS.route);
        assertNotNull(FeedFilterCounters.report().toString(), reels);
        assertTrue("only the two followed reels were read for links: " + reels,
                reels.contains(PostWordsForTests.CAPTION_LINK + " 1")
                        && reels.contains(PostWordsForTests.NO_SITE_NAMED + " 1"));
    }

    /** Paused, the tab is stock whatever the switches say. */
    @Test
    public void pausedEveryReelStays() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        List<Object> page = Arrays.asList(reel(SPOILER), reel(DRAMA));

        assertSame(page, filter(page));
    }

    /** A caption reader that throws keeps the reel and says so. */
    @Test
    public void aFailingReadKeepsTheReel() {
        Settings.HIDE_LINK_POSTS.save(true);
        List<Object> page = Collections.singletonList(reel(DRAMA));
        ReelWords.Readers failing = new ReelWords.Readers(readers.languages, readers.words,
                story -> { throw new IllegalStateException("caption"); });

        Collection<?> kept = FollowedReels.followedOnly(page, FollowedReelsForTests.MODEL,
                FollowedReelsForTests.STATUSES, failing, FollowedReels.PAGES_ROUTE);

        assertSame(page, kept);
        String reels = line(ReelWords.LINKS.route);
        assertNotNull(FeedFilterCounters.report().toString(), reels);
        assertTrue(reels, reels.contains(ReelLanguages.READ_FAILED + " 1"));
    }
}
