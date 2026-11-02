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

import android.text.SpannableString;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Show full post text: with the switch on, the text Facebook cut for See more is answered with
 * null, the answer Facebook gets for text that already fits, and counted. A post starts whole, a
 * tap folds it back to See more and another opens it (#354). Off, paused or with nothing cut,
 * Facebook's answer stands.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FullPostTextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        FullPostText.forgetForTests();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SHOW_FULL_POST_TEXT.resetToDefault();
        HookStatus.clear();
        FullPostText.forgetForTests();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FULL_POST_TEXT + ":")) return line;
        }
        return null;
    }

    /** A story as Facebook's GraphQL model hands it over: its id read by key, like BaseModelWithTree. */
    public static final class Story {
        private final String id;

        Story(String id) {
            this.id = id;
        }

        public final String getCachedString(int key) {
            return key == FullPostText.STORY_ID ? id : null;
        }
    }

    private static SpannableString cut() {
        return new SpannableString("A long post... See more");
    }

    @Test
    public void offByDefaultTheCutStands() {
        assertFalse("the switch doesn't start off", Settings.SHOW_FULL_POST_TEXT.get());
        SpannableString cut = cut();
        assertSame(cut, FullPostText.cut(cut, new Story("1")));
        assertTrue("a post doesn't start cut", FullPostText.allowTruncation(true));
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 1, 2 found, 0 missing", statusLine());
    }

    @Test
    public void onTheWholeTextShows() {
        Settings.SHOW_FULL_POST_TEXT.save(true);
        assertNull("the post's text is still cut", FullPostText.cut(cut(), new Story("1")));
        assertNull("text that fits was given a cut", FullPostText.cut(null, new Story("1")));
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 2, 1 found, 0 missing. Counted: "
                + FullPostText.KEPT_WHOLE + " 1", statusLine());
    }

    @Test
    public void onAPostStartsWhole() {
        Settings.SHOW_FULL_POST_TEXT.save(true);
        assertFalse("a post still starts cut", FullPostText.allowTruncation(true));
        assertFalse(FullPostText.allowTruncation(false));
    }

    @Test
    public void aTapFoldsThePostAndAnotherOpensIt() {
        Settings.SHOW_FULL_POST_TEXT.save(true);
        Story story = new Story("1234");

        FullPostText.truncation(story, true);
        SpannableString cut = cut();
        assertSame("the folded post shows whole", cut, FullPostText.cut(cut, story));
        // Facebook builds the post again with a fresh model of the same story.
        assertSame("the folded post shows whole once it's built again", cut, FullPostText.cut(cut, new Story("1234")));
        assertNull("another post was folded too", FullPostText.cut(cut(), new Story("5678")));

        FullPostText.truncation(story, false);
        assertNull("the opened post is still cut", FullPostText.cut(cut(), story));
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 4, 2 found, 0 missing. Counted: "
                + FullPostText.KEPT_FOLDED + " 2, " + FullPostText.KEPT_WHOLE + " 2", statusLine());
    }

    @Test
    public void aStoryWithNoIdIsKnownByItself() {
        Settings.SHOW_FULL_POST_TEXT.save(true);
        Object story = new Object();
        FullPostText.truncation(story, true);
        SpannableString cut = cut();
        assertSame(cut, FullPostText.cut(cut, story));
        assertNull(FullPostText.cut(cut(), new Object()));
        assertNull("a missing story was folded", FullPostText.cut(cut(), null));
    }

    @Test
    public void offTapsAreFacebooksOwn() {
        Story story = new Story("1234");
        FullPostText.truncation(story, true);
        Settings.SHOW_FULL_POST_TEXT.save(true);
        assertNull("a tap made with the switch off folded the post", FullPostText.cut(cut(), story));
    }

    @Test
    public void theOldestFoldGoesFirst() {
        Settings.SHOW_FULL_POST_TEXT.save(true);
        for (int i = 0; i <= FullPostText.MAX_FOLDED; i++) FullPostText.truncation(new Story("s" + i), true);
        assertNull("the oldest fold stayed", FullPostText.cut(cut(), new Story("s0")));
        SpannableString cut = cut();
        assertSame(cut, FullPostText.cut(cut, new Story("s" + FullPostText.MAX_FOLDED)));
    }

    @Test
    public void pausedTheCutStands() {
        Settings.SHOW_FULL_POST_TEXT.save(true);
        SpannableString cut = cut();
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame("a Hushfacebook paused by " + reason + " still showed the whole text", cut, FullPostText.cut(cut, new Story("1")));
            assertTrue("a Hushfacebook paused by " + reason + " still started a post whole", FullPostText.allowTruncation(true));
            PauseForTests.resume();
        }
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 2, 2 found, 0 missing", statusLine());
    }
}
