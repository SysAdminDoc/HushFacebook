/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
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

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Fold long text (#497): with a line count picked, a post's line limit and a comment's become it, and
 * the characters beside them about that many lines' worth. Facebook's choice, paused, before the
 * settings are ready, a post Facebook never folds (-1) or folds to one line, and posts while Show full
 * post text is on all keep Facebook's numbers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FoldLongTextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's numbers as 587 hands them over: a post's 7 lines and the comment sheet's 400 characters and 5 lines. */
    private static final int POST_LINES = 7;
    private static final int POST_CHARS = 7 * 45 - 13;
    private static final int COMMENT_LINES = 5;
    private static final int COMMENT_CHARS = 400;

    @Before
    public void start() {
        HookStatus.clear();
        FoldLongText.fullTextInBuildForTests = true;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        FoldLongText.fullTextInBuildForTests = null;
        Settings.FOLD_POST_TEXT.resetToDefault();
        Settings.FOLD_COMMENTS.resetToDefault();
        Settings.SHOW_FULL_POST_TEXT.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FOLD_LONG_TEXT + ":")) return line;
        }
        return null;
    }

    private static void assertFacebooks(String why) {
        assertEquals(why + ": a post's lines", POST_LINES, FoldLongText.postLines(POST_LINES));
        assertEquals(why + ": a post's characters", POST_CHARS, FoldLongText.postChars(POST_CHARS, POST_LINES));
        assertEquals(why + ": a comment's lines", COMMENT_LINES, FoldLongText.commentLines(COMMENT_LINES));
        assertEquals(why + ": a comment's characters", COMMENT_CHARS, FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES));
    }

    @Test
    public void facebooksChoiceLeavesEveryNumberAlone() {
        assertEquals(FoldLines.FACEBOOK, Settings.FOLD_POST_TEXT.defaultValue);
        assertEquals(FoldLines.FACEBOOK, Settings.FOLD_COMMENTS.defaultValue);
        assertFacebooks("Facebook's choice");
        for (int lines : new int[] {-1, 0, 1, 2, 4, 6, 7, 30}) {
            assertEquals(lines, FoldLongText.postLines(lines));
            assertEquals(lines, FoldLongText.commentLines(lines));
        }
    }

    @Test
    public void eachPickedCountBecomesTheLineLimitAndItsCharacters() {
        for (FoldLines picked : FoldLines.values()) {
            if (picked == FoldLines.FACEBOOK) continue;
            Settings.FOLD_POST_TEXT.save(picked);
            Settings.FOLD_COMMENTS.save(picked);
            assertEquals(picked + ": a post's lines", picked.lines, FoldLongText.postLines(POST_LINES));
            assertEquals(picked + ": a post's characters", picked.lines * 45 - 10, FoldLongText.postChars(POST_CHARS, POST_LINES));
            assertEquals(picked + ": a comment's lines", picked.lines, FoldLongText.commentLines(COMMENT_LINES));
            assertEquals(picked + ": a comment's characters", picked.lines * 40 - 10,
                    FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES));
            // The characters beside a line limit that already went through here take the count too.
            assertEquals(picked + ": characters beside a picked limit", picked.lines * 45 - 10,
                    FoldLongText.postChars(POST_CHARS, picked.lines));
        }
    }

    @Test
    public void postsAndCommentsAreSeparateChoices() {
        Settings.FOLD_POST_TEXT.save(FoldLines.TWO);
        assertEquals(2, FoldLongText.postLines(POST_LINES));
        assertEquals(COMMENT_LINES, FoldLongText.commentLines(COMMENT_LINES));
        assertEquals(COMMENT_CHARS, FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES));

        Settings.FOLD_POST_TEXT.resetToDefault();
        Settings.FOLD_COMMENTS.save(FoldLines.TEN);
        assertEquals(POST_LINES, FoldLongText.postLines(POST_LINES));
        assertEquals(POST_CHARS, FoldLongText.postChars(POST_CHARS, POST_LINES));
        assertEquals(10, FoldLongText.commentLines(COMMENT_LINES));
    }

    @Test
    public void aPostFacebookNeverFoldsOrFoldsToOneLineKeepsItsNumbers() {
        Settings.FOLD_POST_TEXT.save(FoldLines.THREE);
        Settings.FOLD_COMMENTS.save(FoldLines.THREE);
        for (int lines : new int[] {-1, 0, 1}) {
            assertEquals(lines, FoldLongText.postLines(lines));
            assertEquals(POST_CHARS, FoldLongText.postChars(POST_CHARS, lines));
            assertEquals(lines, FoldLongText.commentLines(lines));
            assertEquals(COMMENT_CHARS, FoldLongText.commentChars(COMMENT_CHARS, lines));
        }
        // No character budget stays none.
        assertEquals(0, FoldLongText.postChars(0, POST_LINES));
        assertEquals(-1, FoldLongText.commentChars(-1, COMMENT_LINES));
    }

    @Test
    public void showFullPostTextOnWinsForPostsOnly() {
        Settings.FOLD_POST_TEXT.save(FoldLines.TWO);
        Settings.FOLD_COMMENTS.save(FoldLines.TWO);
        Settings.SHOW_FULL_POST_TEXT.save(true);
        assertEquals("Show full post text on, but a post still folded at 2", POST_LINES, FoldLongText.postLines(POST_LINES));
        assertEquals(POST_CHARS, FoldLongText.postChars(POST_CHARS, POST_LINES));
        assertEquals("comments aren't Show full post text's", 2, FoldLongText.commentLines(COMMENT_LINES));

        // Without the switch's patch in the build, its saved value means nothing.
        FoldLongText.fullTextInBuildForTests = false;
        assertEquals(2, FoldLongText.postLines(POST_LINES));

        FoldLongText.fullTextInBuildForTests = true;
        Settings.SHOW_FULL_POST_TEXT.save(false);
        assertEquals(2, FoldLongText.postLines(POST_LINES));
    }

    @Test
    public void pausedOrBeforeTheSettingsAreReadyFacebooksNumbersStand() {
        Settings.FOLD_POST_TEXT.save(FoldLines.FOUR);
        Settings.FOLD_COMMENTS.save(FoldLines.FOUR);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFacebooks("paused by " + reason);
            PauseForTests.resume();
        }
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFacebooks("before the pause is decided"));
        SettingsContextRule.withoutContext(() -> assertFacebooks("before Facebook's onCreate"));
        assertEquals(4, FoldLongText.postLines(POST_LINES));
    }

    @Test
    public void theReportCountsEachHookReachedAndEachFold() {
        assertNull(statusLine());
        FoldLongText.postLines(POST_LINES);
        FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES);
        assertEquals(FamilyNames.FOLD_LONG_TEXT + ": invoked 2, 2 found, 0 missing", statusLine());

        Settings.FOLD_POST_TEXT.save(FoldLines.SIX);
        Settings.FOLD_COMMENTS.save(FoldLines.EIGHT);
        FoldLongText.postLines(POST_LINES);
        FoldLongText.postChars(POST_CHARS, POST_LINES);
        FoldLongText.commentLines(COMMENT_LINES);
        // Facebook's own -1 isn't a fold.
        FoldLongText.postLines(-1);
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.startsWith(FamilyNames.FOLD_LONG_TEXT + ": invoked 6, 4 found, 0 missing. Counted: "));
        assertTrue(line, line.contains(FoldLongText.POST_FOLDED + " 1"));
        assertTrue(line, line.contains(FoldLongText.COMMENT_FOLDED + " 1"));
    }
}
