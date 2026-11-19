/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Paint;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Fold long text (#497): with a line count picked, a post's line limit and a comment's become it, and
 * the characters beside them about that many lines' worth. Facebook's choice, paused, before the
 * settings are ready, a post Facebook never folds (-1) or folds to one line, and posts while Show full
 * post text is on all keep Facebook's numbers. The rest of #506: a post's fixed cut and Facebook's
 * choice to show a post whole give way to the count, a comment's characters are measured so it really
 * ends at the count, and a video's caption takes the count picked for posts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FoldLongTextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's numbers as 587 hands them over: a post's 7 lines and the comment sheet's 400 characters and 5 lines. */
    private static final int POST_LINES = 7;
    private static final int POST_CHARS = 7 * 45 - 13;
    private static final int COMMENT_LINES = 5;
    private static final int COMMENT_CHARS = 400;

    /** The place Facebook cuts a community's posts at. */
    private static final Integer FIXED_CUT = 650;

    /** What the old budget gave a comment folded at 8 lines: 8 lines of 40 characters, less room for See more. */
    private static final int OLD_EIGHT_LINES = 310;

    /** Facebook ellipsis, then the words after it. */
    private static final String SEE_MORE = (char) 0x2026 + " See more";

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
        assertEquals(why + ": a comment's characters", COMMENT_CHARS,
                FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, repeat("W", 300)));
        assertSame(why + ": a post's fixed cut", FIXED_CUT, FoldLongText.postCut(FIXED_CUT));
        assertEquals(why + ": a post shown whole", 1, FoldLongText.hoisted(1));
        assertEquals(why + ": a caption's lines", 2, FoldLongText.captionLines(2));
    }

    private static String repeat(String text, int times) {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < times; i++) all.append(text);
        return all.toString();
    }

    /**
     * How much of a comment's line Facebook may really fill. On a Galaxy S22, 588 drew a comment a
     * hair narrower than the measure, so its first line wrapped a word the measure kept (#506).
     */
    private static final float FACEBOOKS_SHARE = 0.96f;

    /** Comments made of ordinary words, long enough to fold at any count picked here. */
    private static final String[] WORDY_COMMENTS = {
            "We call them our bonus plants every season we get a bonus plant. This spring we had a "
                    + repeat("volunteer squash that took over the whole bed and climbed the fence before we noticed it. ", 8),
            "My question would be ,in Nov zone 9. Why are some plants germinating and "
                    + repeat("others are not, even when they went in the same day in the same soil and the same sun. ", 8),
            repeat("Toss them. They will not get better, and several people have said how to stop it next time. ", 9),
    };

    /** The lines [text] takes at the width and size a comment is measured at. */
    private static int laidOutLines(String text) {
        return laidOutLines(text, 1f);
    }

    /** The lines [text] takes at the size a comment is measured at, in [share] of its width. */
    private static int laidOutLines(String text, float share) {
        DisplayMetrics metrics = Utils.getContext().getResources().getDisplayMetrics();
        int side = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, FoldLongText.COMMENT_SIDE_DP, metrics));
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, FoldLongText.COMMENT_TEXT_SP, metrics));
        int width = Math.round((metrics.widthPixels - side) * share);
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false).build().getLineCount();
    }

    @Test
    public void aPickedCountDropsAPostsFixedCut() {
        assertSame(FIXED_CUT, FoldLongText.postCut(FIXED_CUT));
        assertNull(FoldLongText.postCut(null));

        Settings.FOLD_POST_TEXT.save(FoldLines.THREE);
        assertNull("the fixed cut beat the picked count", FoldLongText.postCut(FIXED_CUT));
        assertNull(FoldLongText.postCut(null));

        // Comments have their own choice, and Show full post text keeps posts Facebook's.
        Settings.FOLD_POST_TEXT.resetToDefault();
        Settings.FOLD_COMMENTS.save(FoldLines.THREE);
        assertSame(FIXED_CUT, FoldLongText.postCut(FIXED_CUT));
        Settings.FOLD_POST_TEXT.save(FoldLines.THREE);
        Settings.SHOW_FULL_POST_TEXT.save(true);
        assertSame(FIXED_CUT, FoldLongText.postCut(FIXED_CUT));
    }

    @Test
    public void aPickedCountFoldsAPostFacebookShowsWhole() {
        assertEquals(1, FoldLongText.hoisted(1));
        assertEquals(0, FoldLongText.hoisted(0));

        Settings.FOLD_POST_TEXT.save(FoldLines.THREE);
        assertEquals("the post stayed whole", 0, FoldLongText.hoisted(1));
        assertEquals(0, FoldLongText.hoisted(0));

        Settings.SHOW_FULL_POST_TEXT.save(true);
        assertEquals(1, FoldLongText.hoisted(1));
    }

    @Test
    public void aVideosCaptionTakesTheCountPickedForPosts() {
        assertEquals(2, FoldLongText.captionLines(2));
        Settings.FOLD_COMMENTS.save(FoldLines.TEN);
        assertEquals("the comments' count isn't the caption's", 2, FoldLongText.captionLines(2));

        Settings.FOLD_POST_TEXT.save(FoldLines.EIGHT);
        for (int lines : new int[] {0, 1, 2, 30}) {
            assertEquals("Facebook's " + lines + " lines stayed", 8, FoldLongText.captionLines(lines));
        }

        // Captions have their own show-whole switch, so Show full post text doesn't reach them.
        Settings.SHOW_FULL_POST_TEXT.save(true);
        assertEquals(8, FoldLongText.captionLines(2));
    }

    @Test
    public void aCommentOfWideLettersFoldsAtThePickedLines() {
        Settings.FOLD_COMMENTS.save(FoldLines.EIGHT);
        String wide = repeat("W", 300);
        assertTrue("this text has to run past 8 lines", laidOutLines(wide) > 8);
        int budget = FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, wide);
        // Facebook keeps a text within its budget and See more whole, and the old 310 did that here.
        assertTrue("no fold, budget " + budget, budget + SEE_MORE.length() < wide.length());
    }

    @Test
    public void aCommentOfNarrowLettersShowsAllItsPickedLines() {
        Settings.FOLD_COMMENTS.save(FoldLines.EIGHT);
        String narrow = repeat("i", 600);
        assertTrue("this text has to run past 8 lines", laidOutLines(narrow) > 8);
        int budget = FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, narrow);
        assertTrue("cut early, budget " + budget, budget > OLD_EIGHT_LINES);
        assertTrue("no fold, budget " + budget, budget < narrow.length());
    }

    @Test
    public void aFoldedCommentAndItsSeeMoreFitThePickedLines() {
        Settings.FOLD_COMMENTS.save(FoldLines.EIGHT);
        String[] texts = {repeat("W", 300), repeat("i", 600), repeat("word ", 200), repeat("two words\n", 12) + repeat("x", 400)};
        for (String text : texts) {
            int budget = FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, text);
            assertTrue(text.length() + " characters, budget " + budget, budget < text.length());
            String shown = text.substring(0, budget).trim() + SEE_MORE;
            assertTrue(budget + " characters took " + laidOutLines(shown) + " lines", laidOutLines(shown) <= 8);
        }
    }

    @Test
    public void aFoldedCommentFitsWhenFacebooksLinesRunALittleShort() {
        FoldLines[] counts = {FoldLines.ONE, FoldLines.TWO, FoldLines.THREE, FoldLines.FIVE, FoldLines.EIGHT, FoldLines.TEN};
        for (FoldLines picked : counts) {
            Settings.FOLD_COMMENTS.save(picked);
            for (String text : WORDY_COMMENTS) {
                assertTrue("this text has to run past " + picked.lines + " lines",
                        laidOutLines(text, FACEBOOKS_SHARE) > picked.lines);
                int budget = FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, text);
                assertTrue(picked.lines + " lines, budget " + budget, budget < text.length());
                String shown = text.substring(0, budget).trim() + SEE_MORE;
                int lines = laidOutLines(shown, FACEBOOKS_SHARE);
                assertTrue(picked.lines + " lines picked, but \"" + shown + "\" took " + lines, lines <= picked.lines);
            }
        }
    }

    @Test
    public void aCommentThatFitsIsLeftWhole() {
        Settings.FOLD_COMMENTS.save(FoldLines.EIGHT);
        String[] texts = {"short", repeat("word ", 30), repeat("a\n", 6) + "a"};
        for (String text : texts) {
            int budget = FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, text);
            assertTrue(text.length() + " characters, budget " + budget, budget >= text.length());
        }
    }

    @Test
    public void aCommentOfShortLinesFoldsAtThePickedLineBreak() {
        Settings.FOLD_COMMENTS.save(FoldLines.EIGHT);
        assertEquals(16, FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, repeat("a\n", 10)));
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
            // Without the comment's text there's nothing to measure.
            assertEquals(picked + ": a comment's characters", picked.lines * 40 - 10,
                    FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, null));
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
        assertEquals(COMMENT_CHARS, FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, null));

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
            assertEquals(COMMENT_CHARS, FoldLongText.commentChars(COMMENT_CHARS, lines, repeat("W", 300)));
        }
        // No character budget stays none.
        assertEquals(0, FoldLongText.postChars(0, POST_LINES));
        assertEquals(-1, FoldLongText.commentChars(-1, COMMENT_LINES, repeat("W", 300)));
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
        FoldLongText.commentChars(COMMENT_CHARS, COMMENT_LINES, null);
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
