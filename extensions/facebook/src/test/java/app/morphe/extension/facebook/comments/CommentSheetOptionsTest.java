/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Comment sheet options: with its switch on, the comment box's check answers no for the GIF and
 * sticker buttons, each counted, and leaves every other button to Facebook. With Like only on, the
 * reaction picker's method is told to return. With Open every reply thread on, a comment's state
 * starts with its replies open. With Hide related groups on, the check under a post's comments
 * answers no for the Related groups list and leaves every other plugin to Facebook (#101). Off,
 * paused, or before the settings are ready, nothing changes. The
 * row opens Facebook's own settings, in this package only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentSheetOptionsTest {
    /** Another button the comment box's socket asks about, which always stays. */
    private static final String PHOTO_BUTTON =
            "com.facebook.feedback.comments.plugins.commentcomposer.attachmentbutton.photo.PhotoAttachmentButtonPlugin";

    /** Another plugin the socket under a post's comments asks about, which always stays. */
    private static final String RELATED_CONTENT =
            "com.facebook.feedback.comments.plugins.bottomcontent.impl.relatedcontent.RelatedContentPlugin";

    /** Another plugin the socket under a post's buttons asks about, which always stays. */
    private static final String STORY_CONTEXTUAL_TRAY =
            "com.facebook.feed.plugins.belowufifooter.impl.storycontextualtray.StoryContextualTrayPlugin";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.LIKE_ONLY.resetToDefault();
        Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.resetToDefault();
        Settings.HIDE_COMMENT_EMOJI_ROW.resetToDefault();
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.resetToDefault();
        Settings.OPEN_REPLY_THREADS.resetToDefault();
        Settings.LOAD_WHOLE_REPLY_THREADS.resetToDefault();
        Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES.resetToDefault();
        Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS.resetToDefault();
        Settings.HIDE_POST_COMMENT_PREVIEWS.resetToDefault();
        Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.resetToDefault();
        Settings.HIDE_COMMENT_LIKE_BUTTON.resetToDefault();
        Settings.COMMENT_BLOCK_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheGifAndStickerButtonsAreHeldAndThePickerSkippedEachCounted() {
        Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.save(true);
        Settings.LIKE_ONLY.save(true);
        assertFalse("the photo button was held", CommentSheetOptions.holdsButton(PHOTO_BUTTON));
        assertFalse("a button with no name was held", CommentSheetOptions.holdsButton(null));
        assertTrue("the GIF button stayed", CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
        assertTrue("the sticker button stayed", CommentSheetOptions.holdsButton(CommentSheetOptions.STICKER_BUTTON));
        assertTrue("the reaction picker opened", CommentSheetOptions.skipReactionPicker());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 5, 2 found, 0 missing. Counted: "
                + CommentSheetOptions.BUTTON_HIDDEN + " 2, " + CommentSheetOptions.PICKER_SKIPPED + " 1", statusLine());
    }

    @Test
    public void onTheVideoViewersCommentBarDropsItsButtonsUnderTheSameSwitch() {
        Settings.LIKE_ONLY.save(true);
        assertFalse("Like only dropped the viewer bar's buttons", CommentSheetOptions.holdsViewerButtons());
        Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.save(true);
        assertTrue("the viewer bar kept its GIF and sticker buttons", CommentSheetOptions.holdsViewerButtons());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 2, 1 found, 0 missing. Counted: "
                + CommentSheetOptions.BUTTON_HIDDEN + " 1", statusLine());
    }

    @Test
    public void theEmojiRowGoesOnlyUnderItsOwnSwitch() {
        Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.save(true);
        assertFalse("the button switch dropped the emoji row", CommentSheetOptions.holdsEmojiRow());
        Settings.HIDE_COMMENT_EMOJI_ROW.save(true);
        assertTrue("the emoji row stayed", CommentSheetOptions.holdsEmojiRow());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 2, 1 found, 0 missing. Counted: "
                + CommentSheetOptions.EMOJI_ROW_HIDDEN + " 1", statusLine());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook dropped the emoji row", CommentSheetOptions.holdsEmojiRow());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("the emoji row went before the settings were ready",
                CommentSheetOptions.holdsEmojiRow()));
    }

    @Test
    public void videoCommentComposerUsesItsOwnSwitch() {
        assertFalse("the video comment composer was hidden by default",
                CommentSheetOptions.hidesVideoCommentComposer());
        Settings.LIKE_ONLY.save(true);
        assertFalse("Like only hid the video comment composer",
                CommentSheetOptions.hidesVideoCommentComposer());
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
        assertTrue("the video comment composer stayed visible",
                CommentSheetOptions.hidesVideoCommentComposer());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 3, 1 found, 0 missing. Counted: "
                + CommentSheetOptions.COMPOSER_HIDDEN + " 1", statusLine());
    }

    @Test
    public void onEveryReplyThreadStartsOpenAndIsCounted() {
        Settings.OPEN_REPLY_THREADS.save(true);
        assertTrue("a comment started with its replies closed", CommentSheetOptions.openReplyThreads(false));
        assertTrue("a comment started with its replies closed", CommentSheetOptions.openReplyThreads(false));
        assertTrue("a thread Facebook opened was closed", CommentSheetOptions.openReplyThreads(true));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 3, 1 found, 0 missing. Counted: "
                + CommentSheetOptions.THREAD_OPENED + " 2", statusLine());
    }

    @Test
    public void onAnOpenThreadsRepliesPageOnByThemselvesAndAreCounted() {
        Settings.LOAD_WHOLE_REPLY_THREADS.save(true);
        assertTrue("a thread's replies kept View more replies", CommentSheetOptions.loadWholeReplyThreads(false));
        assertTrue("a list Facebook pages on was stopped", CommentSheetOptions.loadWholeReplyThreads(true));
        assertFalse("loading whole threads opened every thread", CommentSheetOptions.openReplyThreads(false));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 3, 2 found, 0 missing. Counted: "
                + CommentSheetOptions.THREAD_LOADS_IN_FULL + " 1", statusLine());
    }

    @Test
    public void bothReplySwitchesWorkTogetherAndApart() {
        Settings.OPEN_REPLY_THREADS.save(true);
        assertFalse("Open every reply thread paged a thread's replies on", CommentSheetOptions.loadWholeReplyThreads(false));
        Settings.LOAD_WHOLE_REPLY_THREADS.save(true);
        assertTrue("with both on, a thread started closed", CommentSheetOptions.openReplyThreads(false));
        assertTrue("with both on, a thread's replies kept View more replies", CommentSheetOptions.loadWholeReplyThreads(false));
        Settings.OPEN_REPLY_THREADS.save(false);
        assertFalse("Load whole reply threads alone opened a thread", CommentSheetOptions.openReplyThreads(false));
        assertTrue("alone, a tapped thread's replies kept View more replies", CommentSheetOptions.loadWholeReplyThreads(false));
    }

    @Test
    public void onAViewMoreRepliesRowTakesATapAnywhereAndIsCounted() {
        Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES.save(true);
        assertFalse("a row Facebook gave words-only taps kept them", CommentSheetOptions.viewMoreRepliesTextOnly(true));
        assertFalse("a row Facebook already lets the whole row take was changed",
                CommentSheetOptions.viewMoreRepliesTextOnly(false));
        assertFalse("tapping anywhere on the row loaded whole threads", CommentSheetOptions.loadWholeReplyThreads(false));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 3, 2 found, 0 missing. Counted: "
                + CommentSheetOptions.WHOLE_ROW_TAPS + " 1", statusLine());
    }

    @Test
    public void offPausedOrColdAViewMoreRepliesRowKeepsFacebooksAnswer() {
        assertFalse("Tap anywhere on View more replies doesn't start off", Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES.get());
        assertTrue("off, a row's words-only taps were changed", CommentSheetOptions.viewMoreRepliesTextOnly(true));
        Settings.LOAD_WHOLE_REPLY_THREADS.save(true);
        assertTrue("Load whole reply threads changed a row's taps", CommentSheetOptions.viewMoreRepliesTextOnly(true));

        Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " changed a row's taps",
                    CommentSheetOptions.viewMoreRepliesTextOnly(true));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertTrue(
                "a row's taps changed before the settings were ready", CommentSheetOptions.viewMoreRepliesTextOnly(true)));

        String line = statusLine();
        assertFalse("a row left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertFalse("on again after the pause, a row kept words-only taps", CommentSheetOptions.viewMoreRepliesTextOnly(true));
    }

    @Test
    public void offPausedOrColdAThreadsRepliesKeepFacebooksPager() {
        assertFalse("Load whole reply threads doesn't start off", Settings.LOAD_WHOLE_REPLY_THREADS.get());
        assertFalse("off, a thread's replies paged on", CommentSheetOptions.loadWholeReplyThreads(false));
        assertTrue("off, a list Facebook pages on was stopped", CommentSheetOptions.loadWholeReplyThreads(true));

        Settings.LOAD_WHOLE_REPLY_THREADS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " paged a thread's replies on",
                    CommentSheetOptions.loadWholeReplyThreads(false));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(
                "a thread's replies paged on before the settings were ready", CommentSheetOptions.loadWholeReplyThreads(false)));

        String line = statusLine();
        assertFalse("a list left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, a thread's replies kept View more replies",
                CommentSheetOptions.loadWholeReplyThreads(false));
    }

    @Test
    public void onRelatedGroupsAreHeldUnderCommentsAndCounted() {
        Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS.save(true);
        assertFalse("another plugin under the comments was held", CommentSheetOptions.holdsBottomContent(RELATED_CONTENT));
        assertFalse("a plugin with no name was held", CommentSheetOptions.holdsBottomContent(null));
        assertFalse("the GIF button was held by the check under the comments",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.GIF_BUTTON));
        assertTrue("Related groups stayed", CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
        assertTrue("Related groups stayed", CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 5, 1 found, 0 missing. Counted: "
                + CommentSheetOptions.RELATED_GROUPS_HIDDEN + " 2", statusLine());
    }

    @Test
    public void offPausedOrColdRelatedGroupsAreFacebooks() {
        assertFalse("Hide related groups doesn't start off", Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS.get());
        assertFalse("off, Related groups were held",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));

        Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held Related groups",
                    CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse("Related groups were held before the settings were ready",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS)));

        String line = statusLine();
        assertFalse("a plugin left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, Related groups stayed",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
    }

    @Test
    public void onAPostsInlineCommentsAreHeldUnderItsButtonsAndCounted() {
        Settings.HIDE_POST_COMMENT_PREVIEWS.save(true);
        assertFalse("the story tray under a post was held", CommentSheetOptions.holdsPostComments(STORY_CONTEXTUAL_TRAY));
        assertFalse("Meta AI's summary was held by the comments switch", CommentSheetOptions.holdsPostComments(
                MetaAiSummaries.POST_SUMMARY));
        assertFalse("a plugin with no name was held", CommentSheetOptions.holdsPostComments(null));
        assertFalse("Related groups were held under a post's buttons",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.RELATED_GROUPS));
        assertTrue("the inline comments stayed", CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS));
        assertTrue("the dwell time comments stayed",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS_DWELL_TIME));
        assertTrue("the client ranked comments stayed",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS_CLIENT_RANKING));
        // The comment box draws the post's pinned comment above itself, so it goes with them (#305).
        assertTrue("the comment box with its pinned comment stayed",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENT_COMPOSER));
        assertTrue("the comment box with its lifted comment stayed",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENT_COMPOSER_HOISTED));
        assertTrue("the anonymous comment box stayed",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENT_COMPOSER_ANONYMOUS));
        assertFalse("the comments switch held Related groups under the comments",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 11, 2 found, 0 missing. Counted: "
                + CommentSheetOptions.POST_COMMENTS_HIDDEN + " 6", statusLine());
    }

    @Test
    public void offPausedOrColdAPostsInlineCommentsAreFacebooks() {
        assertFalse("Hide comments under posts doesn't start off", Settings.HIDE_POST_COMMENT_PREVIEWS.get());
        assertFalse("off, the inline comments were held",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS));
        assertFalse("off, the comment box was held",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENT_COMPOSER));
        Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS.save(true);
        assertFalse("Hide related groups held the inline comments",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS));

        Settings.HIDE_POST_COMMENT_PREVIEWS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held the inline comments",
                    CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS_DWELL_TIME));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse("the inline comments were held before the settings were ready",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS)));

        String line = statusLine();
        assertFalse("a plugin left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the inline comments stayed",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS));
    }

    @Test
    public void onACommentsDislikeIsHeldAndCountedWhileTheOtherSocketsStay() {
        Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.save(true);
        assertTrue("the dislike stayed", CommentSheetOptions.holdsCommentDislike());
        assertTrue("the dislike stayed on the next comment", CommentSheetOptions.holdsCommentDislike());
        assertFalse("the dislike switch held the inline comments under a post",
                CommentSheetOptions.holdsPostComments(CommentSheetOptions.INLINE_COMMENTS));
        assertFalse("the dislike switch held Related groups",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 4, 3 found, 0 missing. Counted: "
                + CommentSheetOptions.DOWNVOTE_HIDDEN + " 2", statusLine());
    }

    @Test
    public void theBlockButtonWinsOverHidingTheDislike() {
        Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.save(true);
        Settings.COMMENT_BLOCK_BUTTON.save(true);
        assertFalse("both on, the dislike was held, so there was no Block button", CommentSheetOptions.holdsCommentDislike());
        Settings.COMMENT_BLOCK_BUTTON.save(false);
        assertTrue("Block off again, the dislike stayed", CommentSheetOptions.holdsCommentDislike());
    }

    @Test
    public void onACommentsLikeIsHeldAndCountedWhileTheDislikeIsLeftAlone() {
        Settings.HIDE_COMMENT_LIKE_BUTTON.save(true);
        assertTrue("Like stayed", CommentSheetOptions.holdsCommentLike());
        assertTrue("Like stayed on the next comment", CommentSheetOptions.holdsCommentLike());
        assertFalse("the Like switch held the dislike", CommentSheetOptions.holdsCommentDislike());
        Settings.COMMENT_BLOCK_BUTTON.save(true);
        assertTrue("the Block button brought Like back", CommentSheetOptions.holdsCommentLike());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 4, 2 found, 0 missing. Counted: "
                + CommentSheetOptions.LIKE_HIDDEN + " 3", statusLine());
    }

    @Test
    public void offPausedOrColdACommentsLikeIsFacebooks() {
        assertFalse("Hide Like on comments doesn't start off", Settings.HIDE_COMMENT_LIKE_BUTTON.get());
        assertFalse("off, Like was held", CommentSheetOptions.holdsCommentLike());
        Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.save(true);
        assertFalse("Hide dislike on comments held Like", CommentSheetOptions.holdsCommentLike());
        Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.save(false);

        Settings.HIDE_COMMENT_LIKE_BUTTON.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held Like",
                    CommentSheetOptions.holdsCommentLike());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse("Like was held before the settings were ready",
                CommentSheetOptions.holdsCommentLike()));

        String line = statusLine();
        assertFalse("a plugin left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, Like stayed", CommentSheetOptions.holdsCommentLike());
    }

    @Test
    public void offPausedOrColdACommentsDislikeIsFacebooks() {
        assertFalse("Hide dislike on comments doesn't start off", Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.get());
        assertFalse("off, the dislike was held", CommentSheetOptions.holdsCommentDislike());
        Settings.HIDE_POST_COMMENT_PREVIEWS.save(true);
        assertFalse("Hide comments under posts held the dislike", CommentSheetOptions.holdsCommentDislike());

        Settings.HIDE_COMMENT_DOWNVOTE_BUTTON.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held the dislike",
                    CommentSheetOptions.holdsCommentDislike());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse("the dislike was held before the settings were ready",
                CommentSheetOptions.holdsCommentDislike()));

        String line = statusLine();
        assertFalse("a plugin left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the dislike stayed", CommentSheetOptions.holdsCommentDislike());
    }

    @Test
    public void offPausedOrColdTheReplyThreadIsFacebooks() {
        assertFalse("Open every reply thread doesn't start off", Settings.OPEN_REPLY_THREADS.get());
        assertFalse("off, a comment started with its replies open", CommentSheetOptions.openReplyThreads(false));
        assertTrue("off, a thread Facebook opened was closed", CommentSheetOptions.openReplyThreads(true));

        Settings.OPEN_REPLY_THREADS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " opened a reply thread",
                    CommentSheetOptions.openReplyThreads(false));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(
                "a reply thread was opened before the settings were ready", CommentSheetOptions.openReplyThreads(false)));

        String line = statusLine();
        assertFalse("a thread left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, a comment started with its replies closed",
                CommentSheetOptions.openReplyThreads(false));
    }

    @Test
    public void eachSwitchAnswersOnlyForItsOwnHook() {
        Settings.LIKE_ONLY.save(true);
        assertFalse("Like only held the GIF button", CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
        assertTrue("Like only left the picker to open", CommentSheetOptions.skipReactionPicker());
        Settings.LIKE_ONLY.save(false);
        Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.save(true);
        assertFalse("the button switch kept the picker closed", CommentSheetOptions.skipReactionPicker());
        assertFalse("the button switch opened a reply thread", CommentSheetOptions.openReplyThreads(false));
        assertTrue("the button switch left the GIF button", CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
        Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.save(false);
        Settings.OPEN_REPLY_THREADS.save(true);
        assertFalse("the reply switch held the GIF button", CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
        assertFalse("the reply switch kept the picker closed", CommentSheetOptions.skipReactionPicker());
        assertTrue("the reply switch left a thread closed", CommentSheetOptions.openReplyThreads(false));
        assertFalse("the reply switch held Related groups",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
        Settings.OPEN_REPLY_THREADS.save(false);
        Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS.save(true);
        assertFalse("the related groups switch held the GIF button",
                CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
        assertFalse("the related groups switch kept the picker closed", CommentSheetOptions.skipReactionPicker());
        assertFalse("the related groups switch opened a reply thread", CommentSheetOptions.openReplyThreads(false));
        assertTrue("the related groups switch left Related groups",
                CommentSheetOptions.holdsBottomContent(CommentSheetOptions.RELATED_GROUPS));
    }

    @Test
    public void offOrPausedNothingChanges() {
        assertFalse("Like only doesn't start off", Settings.LIKE_ONLY.get());
        assertFalse("the button switch doesn't start off", Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.get());
        assertFalse("off, the GIF button was held", CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
        assertFalse("off, the picker was kept closed", CommentSheetOptions.skipReactionPicker());
        assertFalse("off, the viewer bar lost its buttons", CommentSheetOptions.holdsViewerButtons());

        Settings.LIKE_ONLY.save(true);
        Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held the sticker button",
                    CommentSheetOptions.holdsButton(CommentSheetOptions.STICKER_BUTTON));
            assertFalse("a Hushfacebook paused by " + reason + " kept the picker closed",
                    CommentSheetOptions.skipReactionPicker());
            assertFalse("a Hushfacebook paused by " + reason + " dropped the viewer bar's buttons",
                    CommentSheetOptions.holdsViewerButtons());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse("a button was held before the settings were ready",
                    CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
            assertFalse("the picker was kept closed before the settings were ready",
                    CommentSheetOptions.skipReactionPicker());
            assertFalse("the viewer bar lost its buttons before the settings were ready",
                    CommentSheetOptions.holdsViewerButtons());
        });

        String line = statusLine();
        assertFalse("a hook left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the GIF button stayed",
                CommentSheetOptions.holdsButton(CommentSheetOptions.GIF_BUTTON));
    }

    @Test
    public void theSwitchesNeedNoRestartAndTravelWithTheirFamily() {
        for (app.morphe.extension.shared.settings.BooleanSetting setting : new app.morphe.extension.shared.settings.BooleanSetting[] {
                Settings.LIKE_ONLY, Settings.HIDE_COMMENT_GIF_STICKER_BUTTONS, Settings.OPEN_REPLY_THREADS,
                Settings.LOAD_WHOLE_REPLY_THREADS, Settings.HIDE_RELATED_GROUPS_UNDER_COMMENTS, Settings.HIDE_SHOP_LINK_COMMENTS,
                Settings.HIDE_COMMENTS_WITH_WORDS, Settings.HIDE_INCOMING_COMMENT_TYPING, Settings.HIDE_NEW_COMMENTS_PILL,
                Settings.TAP_ANYWHERE_ON_VIEW_MORE_REPLIES, Settings.HIDE_POST_COMMENT_PREVIEWS,
                Settings.HIDE_COMMENT_DOWNVOTE_BUTTON, Settings.HIDE_COMMENT_LIKE_BUTTON, Settings.HIDE_COMMENT_EMOJI_ROW}) {
            assertFalse(setting.key + " asks for a restart, but each hook reads it again", setting.rebootApp);
            assertNull(setting.key + " asks before it changes", setting.userDialogMessage);
            assertTrue("Pause and the report don't know " + setting.key,
                    PatchFamily.COMMENT_SHEET_OPTIONS.switches.contains(setting));
        }
    }

    @Test
    public void theRowOpensFacebooksOwnSettingsInThisPackageOnly() {
        Application context = RuntimeEnvironment.getApplication();
        Intent route = CommentSheetOptions.reactionSettingsIntent(context);
        assertEquals(Intent.ACTION_VIEW, route.getAction());
        assertEquals("fb://facebook_settings/", route.getDataString());
        assertEquals("the route could leave Facebook", context.getPackageName(), route.getPackage());

        assertFalse("a build with no activity for the route said it opened",
                CommentSheetOptions.openReactionSettings(context));
        assertNull(shadowOf(context).getNextStartedActivity());

        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = context.getPackageName();
        info.activityInfo.name = context.getPackageName() + ".IntentUriHandler";
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(route, info);
        assertTrue("the route didn't open", CommentSheetOptions.openReactionSettings(context));
        Intent started = shadowOf(context).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("fb://facebook_settings/", started.getDataString());
        assertEquals(context.getPackageName() + ".IntentUriHandler", started.getComponent().getClassName());
        assertTrue("an application context started the route outside a task",
                (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }
}
