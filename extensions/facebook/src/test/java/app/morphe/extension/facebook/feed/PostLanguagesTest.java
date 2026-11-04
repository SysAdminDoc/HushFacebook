/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide posts in other languages (#382) in the shared feed guard: a post the phone is sure is in a
 * language that isn't kept goes; a post in a kept language, one too short to guess and one the phone
 * isn't sure of stay. It reads nothing with its switch off, without Hide posts by words or while
 * Hushfacebook is paused, and the main thread never waits for a guess.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostLanguagesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Category { ORGANIC }

    /** The phone's guesses, by text, and every text it was asked about. */
    private final Map<String, String> languages = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private Locale phone;

    @Before
    public void standIn() {
        phone = Locale.getDefault();
        Locale.setDefault(Locale.US);
        languages.put("Mañana vamos al mercado con mis amigos", "es");
        languages.put("We're going to the market tomorrow", "en");
        languages.put("कल हम बाजार जा रहे हैं", "hi");
        languages.put("Kal hum market ja rahe hain", "hi-Latn");
        languages.put("Huh hmm okay sure", null);
        PostLanguages.forgetForTests();
        PostLanguages.detectorForTests(text -> {
            asked.add(text);
            return languages.get(text);
        }, true);
    }

    @After
    public void restore() {
        Locale.setDefault(phone);
        PauseForTests.resume();
        Settings.HIDE_OTHER_LANGUAGES.resetToDefault();
        Settings.KEPT_LANGUAGES.resetToDefault();
        PostLanguages.detectorForTests(null, false);
        PostLanguages.forgetForTests();
        PostText.cachedMembers = null;
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static boolean guard(String text, boolean wordsPatched) {
        GraphQLStory story = new GraphQLStory();
        Object message = FeedGuardForTests.postText(text);
        return FeedFilter.hideEdge(Category.ORGANIC, story, true, true, unit -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, wordsPatched, unit -> unit == story ? message : null, unit -> null);
    }

    private static String line() {
        for (String reported : FeedFilterCounters.report()) {
            if (reported.startsWith(PostLanguages.ROUTE + ": ")) return reported;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndKeepsThePhonesLanguageWithNothingPicked() {
        assertFalse(Settings.HIDE_OTHER_LANGUAGES.defaultValue);
        assertEquals("", Settings.KEPT_LANGUAGES.defaultValue);
        assertEquals(Arrays.asList("en"), PostLanguages.kept(""));
        assertEquals(Arrays.asList("hi", "en"), PostLanguages.kept("hi, en,"));
    }

    @Test
    public void aPostInALanguageNotKeptGoesAndKeptOnesStay() {
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        Settings.KEPT_LANGUAGES.save("hi,en");

        assertTrue(guard("Mañana vamos al mercado con mis amigos", true));
        assertFalse(guard("We're going to the market tomorrow", true));
        assertFalse(guard("कल हम बाजार जा रहे हैं", true));
        assertFalse("Hindi in Latin letters is Hindi", guard("Kal hum market ja rahe hain", true));
        assertFalse("a text the phone isn't sure of stays", guard("Huh hmm okay sure", true));
        assertFalse("emoji and a number are too short to guess", guard("🔥🔥 10", true));
        assertEquals(PostLanguages.ROUTE + ": 6 lists, 6 items, 1 removed. Last reason: other language. "
                + "Removed: other language 1. Kinds: kept language 3, language not sure 1, other language 1, "
                + "too few letters 1", line());
    }

    @Test
    public void withNothingPickedThePhonesLanguageStays() {
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        assertTrue(guard("कल हम बाजार जा रहे हैं", true));
        assertFalse(guard("We're going to the market tomorrow", true));
    }

    @Test
    public void aTextInAKeptLanguageKeepsAPostWhateverElseItHolds() {
        assertEquals(PostLanguages.KEPT, PostLanguages.judge(Arrays.asList("Mañana vamos al mercado con mis amigos",
                "We're going to the market tomorrow"), "en"));
        assertEquals(PostLanguages.REASON, PostLanguages.judge(Arrays.asList("Mañana vamos al mercado con mis amigos",
                "Huh hmm okay sure"), "en"));
        assertEquals(PostLanguages.TOO_SHORT, PostLanguages.judge(Arrays.asList("ok", "👍"), "en"));
    }

    @Test
    public void eachTextIsGuessedOnceAndAFailedGuessKeepsThePost() {
        PostLanguages.judge(Arrays.asList("Mañana vamos al mercado con mis amigos"), "en");
        PostLanguages.judge(Arrays.asList("Mañana vamos al mercado con mis amigos"), "en");
        assertEquals(1, asked.size());

        PostLanguages.detectorForTests(text -> {
            throw new IllegalStateException("no classifier");
        }, true);
        assertEquals(PostLanguages.UNSURE, PostLanguages.judge(Arrays.asList("Ein ganz normaler Satz hier"), "en"));

        languages.put("Ein ganz normaler Satz hier", "de");
        PostLanguages.detectorForTests(text -> {
            asked.add(text);
            return languages.get(text);
        }, true);
        assertEquals("a failed guess is asked again once the phone can answer",
                PostLanguages.REASON, PostLanguages.judge(Arrays.asList("Ein ganz normaler Satz hier"), "en"));
    }

    @Test
    public void theMainThreadNeverWaitsForAGuessAndTheNextPassUsesIt() throws Exception {
        PostLanguages.detectorForTests(text -> {
            asked.add(text);
            return languages.get(text);
        }, false);
        assertEquals(PostLanguages.NOT_YET, PostLanguages.judge(Arrays.asList("Mañana vamos al mercado con mis amigos"), "en"));

        Utils.awaitBackgroundTasksForTests();
        assertEquals(PostLanguages.REASON, PostLanguages.judge(Arrays.asList("Mañana vamos al mercado con mis amigos"), "en"));
        assertEquals(1, asked.size());
    }

    @Test
    public void nothingIsReadWithTheSwitchOffWithoutTheWordsPatchOrWhilePaused() {
        assertFalse(guard("Mañana vamos al mercado con mis amigos", true));
        Settings.HIDE_OTHER_LANGUAGES.save(true);
        assertFalse(guard("Mañana vamos al mercado con mis amigos", false));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(guard("Mañana vamos al mercado con mis amigos", true));
        assertNull(line());
        assertTrue(asked.isEmpty());
    }
}
