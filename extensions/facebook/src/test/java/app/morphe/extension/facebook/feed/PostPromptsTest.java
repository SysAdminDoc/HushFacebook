/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Hide post prompts: with the switch on, a story the bumper predicate says has a bumper is answered
 * as one without, and counted; a story without one keeps Facebook's no. Off or paused, Facebook's
 * yes stands.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostPromptsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_POST_PROMPTS.resetToDefault();
        Settings.HIDE_POST_FOLLOW_LINK.resetToDefault();
        Settings.HIDE_POST_JOIN_BUTTON.resetToDefault();
        Settings.HIDE_POST_X.resetToDefault();
        Settings.HIDE_POST_COUNTS.resetToDefault();
        Settings.HIDE_POST_REACTIONS.resetToDefault();
        Settings.HIDE_COMMENT_REACTIONS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.POST_PROMPTS + ":")) return line;
        }
        return null;
    }

    @Test
    public void aBumperIsAnsweredAwayAndNoBumperStaysNo() {
        assertTrue("the switch doesn't start on", Settings.HIDE_POST_PROMPTS.get());
        assertFalse("a post kept its bumper", PostPrompts.keep(true));
        assertFalse("a post without a bumper got one", PostPrompts.keep(false));
        assertEquals(FamilyNames.POST_PROMPTS + ": invoked 2, 1 found, 0 missing. Counted: "
                + PostPrompts.HIDDEN + " 1", statusLine());
    }

    @Test
    public void offOrPausedTheBumperStays() {
        Settings.HIDE_POST_PROMPTS.save(false);
        assertTrue(PostPrompts.keep(true));
        assertFalse(PostPrompts.keep(false));
        Settings.HIDE_POST_PROMPTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " hid a bumper", PostPrompts.keep(true));
            PauseForTests.resume();
        }
    }

    /**
     * The Follow link: the switch starts off, so the title plugin's yes stays a yes. Turned on, a yes
     * becomes a no, a no stays a no, and the count and the status line say so.
     */
    @Test
    public void theFollowLinkStaysUntilItsSwitchIsOn() {
        assertFalse("the switch doesn't start off", Settings.HIDE_POST_FOLLOW_LINK.get());
        assertTrue(PostPrompts.showFollowLink(true));
        assertFalse(PostPrompts.showFollowLink(false));

        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        assertFalse("a header kept its Follow link", PostPrompts.showFollowLink(true));
        assertFalse(PostPrompts.showFollowLink(false));
        assertTrue("the status line doesn't count the hidden link", statusLine().contains(PostPrompts.FOLLOW_HIDDEN + " 1"));
    }

    @Test
    public void theJoinButtonStaysUntilItsSwitchIsOn() {
        assertFalse("the switch doesn't start off", Settings.HIDE_POST_JOIN_BUTTON.get());
        assertFalse("the header was cut with the switch off", PostPrompts.hideJoinButton());
        Settings.HIDE_POST_JOIN_BUTTON.save(true);
        assertTrue("the header kept its Join button", PostPrompts.hideJoinButton());
        assertTrue("the status line doesn't count the hidden button", statusLine().contains(PostPrompts.JOIN_HIDDEN + " 1"));
        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        Settings.HIDE_POST_JOIN_BUTTON.save(false);
        assertFalse("the follow switch hid the Join button", PostPrompts.hideJoinButton());
    }

    @Test
    public void theJoinGroupBarFollowsTheJoinSwitch() {
        assertFalse("the bar was cut with the switch off", PostPrompts.hideVideoJoinBar());
        Settings.HIDE_POST_JOIN_BUTTON.save(true);
        assertTrue("a group's video kept its Join group bar", PostPrompts.hideVideoJoinBar());
        assertTrue("the status line doesn't count the hidden bar", statusLine().contains(PostPrompts.JOIN_BAR_HIDDEN + " 1"));
        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        Settings.HIDE_POST_JOIN_BUTTON.save(false);
        assertFalse("the follow switch hid the Join group bar", PostPrompts.hideVideoJoinBar());
    }

    /** The X: starts off, so Facebook's yes stands; on, a yes becomes a no; paused, the yes is back. */
    @Test
    public void theXStaysUntilItsSwitchIsOn() {
        assertFalse("the switch doesn't start off", Settings.HIDE_POST_X.get());
        assertTrue("the X went with the switch off", PostPrompts.showPostX(1));
        assertFalse(PostPrompts.showPostX(0));

        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        Settings.HIDE_POST_JOIN_BUTTON.save(true);
        assertTrue("another post switch hid the X", PostPrompts.showPostX(true));

        Settings.HIDE_POST_X.save(true);
        assertFalse("a post kept its X", PostPrompts.showPostX(1));
        assertFalse("a post without an X got one", PostPrompts.showPostX(false));
        assertTrue("the status line doesn't count the hidden X", statusLine().contains(PostPrompts.POST_X_HIDDEN + " 1"));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("a paused Hushfacebook hid the X", PostPrompts.showPostX(true));
    }

    @Test
    public void pausedTheJoinButtonStays() {
        Settings.HIDE_POST_JOIN_BUTTON.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook hid the Join button", PostPrompts.hideJoinButton());
        assertFalse("a paused Hushfacebook hid the Join group bar", PostPrompts.hideVideoJoinBar());
    }

    /** The two switches are independent: neither one's hook answers for the other. */
    @Test
    public void thePromptsSwitchAndTheFollowSwitchDontShareAnAnswer() {
        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        Settings.HIDE_POST_PROMPTS.save(false);
        assertTrue("the follow switch hid a bumper", PostPrompts.keep(true));
        assertFalse(PostPrompts.showFollowLink(true));
        Settings.HIDE_POST_FOLLOW_LINK.save(false);
        Settings.HIDE_POST_PROMPTS.save(true);
        assertFalse(PostPrompts.keep(true));
        assertTrue("the prompts switch hid a Follow link", PostPrompts.showFollowLink(true));
    }

    @Test
    public void pausedTheFollowLinkStays() {
        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " hid the Follow link", PostPrompts.showFollowLink(true));
            PauseForTests.resume();
        }
    }

    /** The follow hook hands Facebook's answer over as an int too. */
    @Test
    public void theFollowEntrysIntReadsNonZeroAsYes() {
        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        assertFalse(PostPrompts.showFollowLink(1));
        assertFalse(PostPrompts.showFollowLink(0));
        Settings.HIDE_POST_FOLLOW_LINK.save(false);
        assertTrue(PostPrompts.showFollowLink(1));
        assertFalse(PostPrompts.showFollowLink(0));
    }

    /**
     * A collab post's or a partnership ad's own " . Follow" text (#396): Facebook's text until the
     * switch is on, then an empty text of the same kind, so a span-carrying builder stays one.
     */
    @Test
    public void theCollabFollowTextEmptiesOnlyWithTheSwitchOn() {
        android.text.SpannableStringBuilder follow = new android.text.SpannableStringBuilder(" . Follow");
        assertEquals(follow, PostPrompts.followText(follow));

        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        CharSequence hidden = PostPrompts.followText(follow);
        assertEquals("a collab header kept its Follow text", 0, hidden.length());
        assertTrue("the empty text lost its span type", hidden instanceof android.text.Spannable);
        assertEquals("the plugin's own text was changed", " . Follow", follow.toString());
        assertNull(PostPrompts.followText(null));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Hushfacebook hid the collab Follow text", follow, PostPrompts.followText(follow));
        PauseForTests.resume();
    }

    /**
     * The counts (#180): the switch starts off, so the count builder writes Facebook's number. On,
     * the builder is told to give its zero answer, the empty text that leaves the word on the
     * button, and the status line counts it. Off again, the number is back.
     */
    @Test
    public void theCountsStayUntilTheirSwitchIsOn() {
        assertFalse("the switch doesn't start on", Settings.HIDE_POST_COUNTS.get());
        assertFalse("a count went with the switch off", PostPrompts.hidePostCounts());

        Settings.HIDE_POST_COUNTS.save(true);
        assertTrue("a count stayed with the switch on", PostPrompts.hidePostCounts());
        assertTrue("the status line doesn't count the hidden count",
                statusLine().contains(PostPrompts.COUNTS_HIDDEN + " 1"));

        Settings.HIDE_POST_COUNTS.save(false);
        assertFalse("turning the switch off didn't bring the count back", PostPrompts.hidePostCounts());
    }

    @Test
    public void pausedTheCountsStay() {
        Settings.HIDE_POST_COUNTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " hid a count", PostPrompts.hidePostCounts());
            PauseForTests.resume();
        }
        assertTrue("resumed, the count didn't go again", PostPrompts.hidePostCounts());
    }

    /** The counts switch answers for nothing else, and the other two don't answer for it. */
    @Test
    public void theCountsSwitchSharesNoAnswer() {
        Settings.HIDE_POST_COUNTS.save(true);
        Settings.HIDE_POST_PROMPTS.save(false);
        assertTrue("the counts switch hid a bumper", PostPrompts.keep(true));
        assertTrue("the counts switch hid a Follow link", PostPrompts.showFollowLink(true));
        Settings.HIDE_POST_COUNTS.save(false);
        Settings.HIDE_POST_PROMPTS.save(true);
        Settings.HIDE_POST_FOLLOW_LINK.save(true);
        assertFalse("the other switches hid a count", PostPrompts.hidePostCounts());
    }

    /** The reaction preview (#158): off draws it, on leaves it undrawn, off again brings it back. */
    @Test
    public void theReactionPreviewIsDrawnUntilItsSwitchIsOn() {
        assertFalse("the switch doesn't start on", Settings.HIDE_POST_REACTIONS.get());
        assertFalse("a preview went with the switch off", PostPrompts.hideReactionPreview());

        Settings.HIDE_POST_REACTIONS.save(true);
        assertTrue("a preview stayed with the switch on", PostPrompts.hideReactionPreview());
        assertTrue("the status line doesn't count the hidden preview",
                statusLine().contains(PostPrompts.REACTIONS_HIDDEN + " 1"));

        Settings.HIDE_POST_REACTIONS.save(false);
        assertFalse("turning the switch off didn't bring the preview back", PostPrompts.hideReactionPreview());
    }

    @Test
    public void pausedTheReactionPreviewIsDrawn() {
        Settings.HIDE_POST_REACTIONS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " hid the preview", PostPrompts.hideReactionPreview());
            PauseForTests.resume();
        }
        assertTrue("resumed, the preview didn't go again", PostPrompts.hideReactionPreview());
    }

    /** A comment's reaction faces answer to their own switch (#158): off draws them, on leaves them undrawn. */
    @Test
    public void aCommentsReactionPreviewIsDrawnUntilItsSwitchIsOn() {
        assertFalse("the switch doesn't start on", Settings.HIDE_COMMENT_REACTIONS.get());
        assertFalse("a comment preview went with the switch off", PostPrompts.hideReactionPreview(true));

        Settings.HIDE_COMMENT_REACTIONS.save(true);
        assertTrue("a comment preview stayed with the switch on", PostPrompts.hideReactionPreview(true));
        assertTrue("the status line doesn't count the hidden comment preview",
                statusLine().contains(PostPrompts.COMMENT_REACTIONS_HIDDEN + " 1"));

        Settings.HIDE_COMMENT_REACTIONS.save(false);
        assertFalse("turning the switch off didn't bring the preview back", PostPrompts.hideReactionPreview(true));
    }

    @Test
    public void pausedACommentsReactionPreviewIsDrawn() {
        Settings.HIDE_COMMENT_REACTIONS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " hid the comment preview",
                    PostPrompts.hideReactionPreview(true));
            PauseForTests.resume();
        }
        assertTrue("resumed, the comment preview didn't go again", PostPrompts.hideReactionPreview(true));
    }

    /** The post switch and the comment switch each answer for their own surface only. */
    @Test
    public void thePostAndCommentSwitchesDontShareAnAnswer() {
        Settings.HIDE_POST_REACTIONS.save(true);
        assertTrue("the post switch didn't hide a post's faces", PostPrompts.hideReactionPreview(false));
        assertTrue("the no-argument entry (the bling bar) ignored the post switch", PostPrompts.hideReactionPreview());
        assertFalse("the post switch hid a comment's faces", PostPrompts.hideReactionPreview(true));
        Settings.HIDE_POST_REACTIONS.save(false);
        Settings.HIDE_COMMENT_REACTIONS.save(true);
        assertFalse("the comment switch hid a post's faces", PostPrompts.hideReactionPreview(false));
        assertFalse("the comment switch hid the bling bar", PostPrompts.hideReactionPreview());
        assertTrue("the comment switch didn't hide a comment's faces", PostPrompts.hideReactionPreview(true));
    }

    /**
     * The flag the lambda leaves before the factory comes back once, to the take right after it, and
     * a take with nothing left reads as a post's faces.
     */
    @Test
    public void aStashedCommentFlagIsTakenOnce() {
        assertFalse("a take with nothing stashed read as a comment", PostPrompts.takeCommentFaces());
        PostPrompts.stashCommentFaces(true);
        assertTrue("the comment flag didn't come back", PostPrompts.takeCommentFaces());
        assertFalse("the comment flag came back twice", PostPrompts.takeCommentFaces());
        PostPrompts.stashCommentFaces(true);
        PostPrompts.stashCommentFaces(false);
        assertFalse("a later post's stash didn't replace a comment's", PostPrompts.takeCommentFaces());
    }

    /**
     * The count beside a post's faces (#158) answers to the post switch: off it's Facebook's text,
     * on it's null and counted, and a text Facebook left empty, or none, comes back as it was.
     */
    @Test
    public void theReactionCountIsShownUntilThePostSwitchIsOn() {
        assertEquals("a count went with the switch off", "56", PostPrompts.reactionCount("56"));

        Settings.HIDE_POST_REACTIONS.save(true);
        assertNull("a count stayed with the switch on", PostPrompts.reactionCount("56"));
        assertNull("the summary line stayed with the switch on", PostPrompts.reactionCount("Sam and 55 others"));
        assertTrue("the status line doesn't count the hidden counts",
                statusLine().contains(PostPrompts.COUNT_HIDDEN + " 2"));
        assertEquals("an empty text was counted or changed", "", PostPrompts.reactionCount(""));
        assertEquals("no text at all was changed", null, PostPrompts.reactionCount(null));
        assertTrue("an empty text was counted", statusLine().contains(PostPrompts.COUNT_HIDDEN + " 2"));

        Settings.HIDE_COMMENT_REACTIONS.save(true);
        Settings.HIDE_POST_REACTIONS.save(false);
        assertEquals("the comment switch hid a post's count", "56", PostPrompts.reactionCount("56"));
    }

    @Test
    public void pausedOrBeforeTheSettingsAreReadyTheReactionCountIsShown() {
        Settings.HIDE_POST_REACTIONS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertEquals("a Hushfacebook paused by " + reason + " hid the count", "56", PostPrompts.reactionCount("56"));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() ->
                assertEquals("before the settings were ready, the count went", "56", PostPrompts.reactionCount("56")));
        assertNull("resumed, the count didn't go again", PostPrompts.reactionCount("56"));
    }

    /** The reactions switch answers for nothing else, and the counts switch doesn't answer for it. */
    @Test
    public void theReactionSwitchSharesNoAnswer() {
        Settings.HIDE_POST_REACTIONS.save(true);
        Settings.HIDE_POST_PROMPTS.save(false);
        assertTrue("the reactions switch hid a bumper", PostPrompts.keep(true));
        assertTrue("the reactions switch hid a Follow link", PostPrompts.showFollowLink(true));
        assertFalse("the reactions switch hid a count", PostPrompts.hidePostCounts());
        Settings.HIDE_POST_REACTIONS.save(false);
        Settings.HIDE_POST_COUNTS.save(true);
        assertFalse("the counts switch hid the preview", PostPrompts.hideReactionPreview());
        assertEquals("the counts switch hid the count beside the faces", "56", PostPrompts.reactionCount("56"));
    }

    /** The patch hands Facebook's answer over as an int, since a boolean method may return one. */
    @Test
    public void thePatchsIntEntryReadsNonZeroAsYes() {
        assertFalse("a bumper handed over as 1 stayed", PostPrompts.keep(1));
        assertFalse(PostPrompts.keep(0));
        Settings.HIDE_POST_PROMPTS.save(false);
        assertTrue("with the switch off, 1 lost Facebook's yes", PostPrompts.keep(1));
        assertFalse(PostPrompts.keep(0));
    }
}
