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

import com.facebook.graphql.model.GraphQLStory;

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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.feed.PostLanguagesForTests;
import app.morphe.extension.facebook.reels.FollowedReelsForTests.FollowStatus;
import app.morphe.extension.facebook.reels.FollowedReelsForTests.Model;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests.Section;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide posts in other languages on the Reels tab (#382): on, with English kept, a reel whose caption
 * the phone is sure is Spanish comes off its page and its section, while English, captionless and
 * emoji-only reels stay, each counted under why. Off or paused, the page goes on as Facebook sent it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelLanguagesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String SPANISH = "Mañana vamos al mercado con mis amigos";
    private static final String ENGLISH = "Tomorrow we go to the market with friends";
    private static final String EMOJI = "😂😂 🔥";

    /** A Reels item keeping the reel's model and its story side by side, as the tab's items do. */
    public static final class Reel {
        final Model model;
        final GraphQLStory story = new GraphQLStory();

        public Reel(FollowStatus status) {
            this.model = new Model(status);
        }
    }

    /** Each story's caption, null for a reel with none. */
    private final Map<Object, String> captions = new IdentityHashMap<>();

    private final ReelLanguages.Captions readCaption =
            story -> PostLanguagesForTests.storyKind(story, captions::get);

    @Before
    public void guessFromTheTexts() {
        Map<String, String> languages = new HashMap<>();
        languages.put(SPANISH, "es");
        languages.put(ENGLISH, "en");
        PostLanguagesForTests.guessFrom(languages);
        Settings.KEPT_LANGUAGES.save("en");
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_OTHER_LANGUAGES.resetToDefault();
        Settings.KEPT_LANGUAGES.resetToDefault();
        Settings.FOLLOWED_REELS_ONLY.resetToDefault();
        PostLanguagesForTests.restore();
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
                readCaption, FollowedReels.PAGES_ROUTE);
    }

    private static String line(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ": ")) return line;
        }
        return null;
    }

    /** Off, which is how it ships, the Reels tab is stock. */
    @Test
    public void offEveryReelStays() {
        assertFalse(Settings.HIDE_OTHER_LANGUAGES.defaultValue);
        List<Object> page = Arrays.asList(reel(SPANISH), reel(ENGLISH));

        assertSame(page, filter(page));
    }

    /** On, only the reel in a language that isn't kept goes, the rest keep their order, and each read is counted. */
    @Test
    public void onOnlyAReelInAnotherLanguageComesOff() {
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        Reel spanish = reel(SPANISH);
        Reel english = reel(ENGLISH);
        Reel captionless = reel(null);
        Reel emoji = reel(EMOJI);
        Object card = new Object();

        Collection<?> kept = filter(Arrays.asList(spanish, english, captionless, emoji, card));

        assertEquals(Arrays.asList(english, captionless, emoji, card), new ArrayList<>(kept));
        String reels = line(ReelLanguages.ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), reels);
        assertTrue(reels, reels.contains("Removed: " + PostLanguagesForTests.REASON + " 1"));
        assertTrue(reels, reels.contains(PostLanguagesForTests.KEPT + " 1"));
        assertTrue(reels, reels.contains(PostLanguagesForTests.NO_TEXT + " 1"));
        assertTrue(reels, reels.contains(PostLanguagesForTests.TOO_SHORT + " 1"));
        assertTrue(reels, reels.contains(ReelLanguages.NO_STORY + " 1"));
        String pages = line(FollowedReels.PAGES_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), pages);
        assertTrue(pages, pages.contains(PostLanguagesForTests.REASON + " 1"));
    }

    /** A Spanish reel inside a section comes out of it too, and a section left empty goes. */
    @Test
    public void onASectionLosesItsReelsInAnotherLanguage() {
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        Reel english = reel(ENGLISH);
        Section mixed = new Section(new ArrayList<>(Arrays.asList(english, reel(SPANISH))));
        Section onlySpanish = new Section(new ArrayList<>(Collections.singletonList(reel(SPANISH))));

        List<?> kept = FollowedReels.followedSections(Arrays.asList(mixed, onlySpanish), FollowedReelsForTests.MODEL,
                FollowedReelsForTests.STATUSES, readCaption);

        assertEquals(Collections.singletonList(mixed), kept);
        assertEquals(Collections.singletonList(english), mixed.items);
    }

    /** With both switches on, a reel either rule takes goes, counted once under the first rule that took it. */
    @Test
    public void bothSwitchesOnEachRuleTakesItsOwn() {
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        Settings.FOLLOWED_REELS_ONLY.save(true);
        Reel notFollowedSpanish = reel(FollowStatus.CAN_SUBSCRIBE, SPANISH);
        Reel followedSpanish = reel(FollowStatus.IS_SUBSCRIBED, SPANISH);
        Reel followedEnglish = reel(FollowStatus.IS_SUBSCRIBED, ENGLISH);

        Collection<?> kept = filter(Arrays.asList(notFollowedSpanish, followedSpanish, followedEnglish));

        assertEquals(Collections.singletonList(followedEnglish), new ArrayList<>(kept));
        String pages = line(FollowedReels.PAGES_ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), pages);
        assertTrue(pages, pages.contains(FollowedReels.NOT_FOLLOWED + " 1"));
        assertTrue(pages, pages.contains(PostLanguagesForTests.REASON + " 1"));
        String reels = line(ReelLanguages.ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), reels);
        assertTrue("the reel the follow rule took isn't read again: " + reels, reels.contains("Removed: "
                + PostLanguagesForTests.REASON + " 1") && !reels.contains(PostLanguagesForTests.REASON + " 2"));
    }

    /** Paused, the tab is stock whatever the switch says. */
    @Test
    public void pausedEveryReelStays() {
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        List<Object> page = Collections.singletonList(reel(SPANISH));

        assertSame(page, filter(page));
    }

    /** A caption reader that throws keeps the reel and says so. */
    @Test
    public void aFailingReadKeepsTheReel() {
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        List<Object> page = Collections.singletonList(reel(SPANISH));

        Collection<?> kept = FollowedReels.followedOnly(page, FollowedReelsForTests.MODEL, FollowedReelsForTests.STATUSES,
                story -> { throw new IllegalStateException("caption"); }, FollowedReels.PAGES_ROUTE);

        assertSame(page, kept);
        String reels = line(ReelLanguages.ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), reels);
        assertTrue(reels, reels.contains(ReelLanguages.READ_FAILED + " 1"));
    }
}
