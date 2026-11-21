/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
 * Clean up the story viewer: each switch answers only for its own part of a story and is counted,
 * and off, paused, or before the settings are ready, both parts draw as Facebook has them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryViewerCleanupTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_STORY_CREDITS.resetToDefault();
        Settings.HIDE_STORY_REPLY_SUGGESTIONS.resetToDefault();
        Settings.HIDE_STORY_SUBSCRIBE.resetToDefault();
        Settings.HIDE_STORY_FOLLOW.resetToDefault();
        Settings.HIDE_STORY_UP_NEXT.resetToDefault();
        Settings.HIDE_STORY_REPLY_BAR.resetToDefault();
        Settings.HIDE_STORY_CREATE_CARDS.resetToDefault();
        Settings.STORY_FLAT_SLIDE.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_VIEWER_CLEANUP + ":")) return line;
        }
        return null;
    }

    @Test
    public void bothSwitchesStartOffAndBelongToTheirPatch() {
        assertFalse("Hide song and effect credits doesn't start off", Settings.HIDE_STORY_CREDITS.defaultValue);
        assertFalse("Hide reply suggestions doesn't start off", Settings.HIDE_STORY_REPLY_SUGGESTIONS.defaultValue);
        assertEquals(FamilyNames.STORY_VIEWER_CLEANUP, PatchFamily.STORY_VIEWER_CLEANUP.patchName);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.HIDE_STORY_CREDITS));
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.HIDE_STORY_REPLY_SUGGESTIONS));
    }

    @Test
    public void eachSwitchHidesOnlyItsOwnPartAndIsCounted() {
        Settings.HIDE_STORY_CREDITS.save(true);
        assertTrue("the credit line kept its credits", StoryViewerCleanup.hideCredits());
        assertFalse("the credits switch hid the reply suggestions", StoryViewerCleanup.hideReplySuggestions());

        Settings.HIDE_STORY_CREDITS.save(false);
        Settings.HIDE_STORY_REPLY_SUGGESTIONS.save(true);
        assertTrue("the reply suggestions still drew", StoryViewerCleanup.hideReplySuggestions());
        assertFalse("the reply suggestions switch hid the credits", StoryViewerCleanup.hideCredits());

        assertEquals(FamilyNames.STORY_VIEWER_CLEANUP + ": invoked 4, 2 found, 0 missing. Counted: "
                        + StoryViewerCleanup.CREDITS_HIDDEN + " 1, " + StoryViewerCleanup.SUGGESTIONS_HIDDEN + " 1",
                statusLine());
    }

    /** Stand-ins for Facebook's pill types, which keep their names in the app. */
    private enum Pill { MUSIC, SOUND_SYNC, EFFECT, AI_STYLES, CLOSE_FRIEND, POST_RESHARED_TO_STORY, SOMETHING_NEW }

    /** #380: the song and effect pills under the poster's name go with the credit line, the rest stay. */
    @Test
    public void theCreditsSwitchDropsOnlySongAndEffectPills() {
        assertFalse("off, a song pill went", StoryViewerCleanup.dropCredit(Pill.MUSIC));
        Settings.HIDE_STORY_CREDITS.save(true);
        for (Pill pill : new Pill[] {Pill.MUSIC, Pill.SOUND_SYNC, Pill.EFFECT, Pill.AI_STYLES}) {
            assertTrue(pill + " stayed", StoryViewerCleanup.dropCredit(pill));
        }
        for (Pill pill : new Pill[] {Pill.CLOSE_FRIEND, Pill.POST_RESHARED_TO_STORY, Pill.SOMETHING_NEW}) {
            assertFalse(pill + " went", StoryViewerCleanup.dropCredit(pill));
        }
        assertFalse("a pill with no type went", StoryViewerCleanup.dropCredit(null));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, a song pill went", StoryViewerCleanup.dropCredit(Pill.MUSIC));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("a song pill went before the settings were ready", StoryViewerCleanup.dropCredit(Pill.MUSIC)));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.PILLS_DROPPED + " 4"));
    }

    /** #380: a story whose first credit is a song draws its own pill, with "Try it", which goes with the credits. */
    @Test
    public void theCreditsSwitchHidesTheSongPillAndNothingElseDoes() {
        assertFalse("off, the song pill went", StoryViewerCleanup.hideSongPill());
        Settings.HIDE_STORY_REPLY_SUGGESTIONS.save(true);
        Settings.HIDE_STORY_SUBSCRIBE.save(true);
        Settings.HIDE_STORY_CREATE_CARDS.save(true);
        assertFalse("another switch hid the song pill", StoryViewerCleanup.hideSongPill());
        Settings.HIDE_STORY_CREDITS.save(true);
        assertTrue("on, the song pill stayed", StoryViewerCleanup.hideSongPill());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, the song pill went", StoryViewerCleanup.hideSongPill());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the song pill went before the settings were ready", StoryViewerCleanup.hideSongPill()));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.SONG_PILL_HIDDEN + " 1"));
    }

    @Test
    public void theSubscribeSwitchStartsOffAndHidesOnlyTheSubscribeButton() {
        assertFalse(Settings.HIDE_STORY_SUBSCRIBE.defaultValue);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.HIDE_STORY_SUBSCRIBE));
        assertFalse("off, the button went", StoryViewerCleanup.hideSubscribe());
        Settings.HIDE_STORY_SUBSCRIBE.save(true);
        assertTrue(StoryViewerCleanup.hideSubscribe());
        assertFalse("it hid the credits", StoryViewerCleanup.hideCredits());
        assertFalse("it dropped a song pill", StoryViewerCleanup.dropCredit(Pill.MUSIC));
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse("paused, the button went", StoryViewerCleanup.hideSubscribe());
        PauseForTests.resume();
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.SUBSCRIBE_HIDDEN + " 1"));
    }

    /** #543: the Follow button on a story goes only with its own switch. */
    @Test
    public void theFollowSwitchStartsOffAndHidesOnlyTheFollowButton() {
        assertFalse(Settings.HIDE_STORY_FOLLOW.defaultValue);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.HIDE_STORY_FOLLOW));
        assertFalse("off, the button went", StoryViewerCleanup.hideFollow());
        Settings.HIDE_STORY_SUBSCRIBE.save(true);
        Settings.HIDE_STORY_REPLY_BAR.save(true);
        assertFalse("another switch hid the Follow button", StoryViewerCleanup.hideFollow());
        Settings.HIDE_STORY_FOLLOW.save(true);
        assertTrue("on, the button stayed", StoryViewerCleanup.hideFollow());
        Settings.HIDE_STORY_SUBSCRIBE.save(false);
        assertFalse("it hid the Subscribe button", StoryViewerCleanup.hideSubscribe());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, the button went", StoryViewerCleanup.hideFollow());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the button went before the settings were ready", StoryViewerCleanup.hideFollow()));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.FOLLOW_HIDDEN + " 1"));
    }

    /**
     * #544: with the switch on, a page Facebook opens on the Up next card opens as if the card were
     * dismissed, and one it opens without the card stays that way whatever the switch says.
     */
    @Test
    public void theUpNextSwitchOpensThePageWithoutTheCard() {
        assertFalse(Settings.HIDE_STORY_UP_NEXT.defaultValue);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.HIDE_STORY_UP_NEXT));
        assertTrue("off, the card went", StoryViewerCleanup.showUpNext(true));
        assertFalse("off, a page without the card got one", StoryViewerCleanup.showUpNext(false));
        Settings.HIDE_STORY_REPLY_BAR.save(true);
        Settings.HIDE_STORY_FOLLOW.save(true);
        assertTrue("another switch took the card away", StoryViewerCleanup.showUpNext(true));
        Settings.HIDE_STORY_UP_NEXT.save(true);
        assertFalse("on, the card stayed", StoryViewerCleanup.showUpNext(true));
        assertFalse("on, a page without the card got one", StoryViewerCleanup.showUpNext(false));
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertTrue("paused, the card went", StoryViewerCleanup.showUpNext(true));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertTrue("the card went before the settings were ready", StoryViewerCleanup.showUpNext(true)));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.UP_NEXT_SKIPPED + " 1"));
    }

    /** #542: the bar under a story, repost, Send message and reactions, goes only with its own switch. */
    @Test
    public void theReplyBarSwitchStartsOffAndHidesOnlyTheBar() {
        assertFalse(Settings.HIDE_STORY_REPLY_BAR.defaultValue);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.HIDE_STORY_REPLY_BAR));
        assertFalse("off, the bar went", StoryViewerCleanup.hideReplyBar());
        Settings.HIDE_STORY_REPLY_SUGGESTIONS.save(true);
        assertFalse("the reply suggestions switch took the whole bar", StoryViewerCleanup.hideReplyBar());
        Settings.HIDE_STORY_REPLY_BAR.save(true);
        assertTrue("on, the bar stayed", StoryViewerCleanup.hideReplyBar());
        Settings.HIDE_STORY_REPLY_SUGGESTIONS.save(false);
        assertFalse("it hid the reply suggestions too", StoryViewerCleanup.hideReplySuggestions());
        assertFalse("it hid the Follow button", StoryViewerCleanup.hideFollow());
        PauseForTests.pause(HushfacebookPause.Reason.MARKER_FILE);
        assertFalse("paused, the bar went", StoryViewerCleanup.hideReplyBar());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the bar went before the settings were ready", StoryViewerCleanup.hideReplyBar()));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.REPLY_BAR_HIDDEN + " 1"));
    }

    /** #439: the "Add to story" cards between stories go only with their own switch. */
    @Test
    public void theCreateCardsSwitchStartsOffAndLeavesOnlyTheCreateCardsOut() {
        assertFalse(Settings.HIDE_STORY_CREATE_CARDS.defaultValue);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.HIDE_STORY_CREATE_CARDS));
        assertFalse("off, the card went", StoryViewerCleanup.hideCreateStoryCard());
        Settings.HIDE_STORY_CREATE_CARDS.save(true);
        assertTrue("on, the card stayed", StoryViewerCleanup.hideCreateStoryCard());
        assertFalse("it hid the Subscribe button", StoryViewerCleanup.hideSubscribe());
        assertFalse("it hid the credits", StoryViewerCleanup.hideCredits());
        PauseForTests.pause(HushfacebookPause.Reason.MARKER_FILE);
        assertFalse("paused, the card went", StoryViewerCleanup.hideCreateStoryCard());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the card went before the settings were ready", StoryViewerCleanup.hideCreateStoryCard()));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.CREATE_CARDS_HIDDEN + " 1"));
    }

    /**
     * #521: the "continue the story" card built from the gallery, and every other card between stories
     * that asks you to make one, leaves the viewer's list with the same switch, whichever source put it
     * there. The rest keep their order, and a list with no such card comes back as the same list.
     */
    @Test
    public void theCreateCardsSwitchTakesEveryCreateCardOutOfTheViewersList() {
        Object friend = new Object();
        Object page = new Object();
        List<Object> buckets = Arrays.asList(new StoryViewerCleanupForTests.CreateCard(), friend,
                new StoryViewerCleanupForTests.CreateCard(), page);
        assertSame("off, the list changed", buckets, StoryViewerCleanupForTests.kept(buckets));

        Settings.HIDE_STORY_CREATE_CARDS.save(true);
        assertEquals(Arrays.asList(friend, page), StoryViewerCleanupForTests.kept(buckets));
        List<Object> noCards = Arrays.asList(friend, page);
        assertSame("a list with no card in it was copied", noCards, StoryViewerCleanupForTests.kept(noCards));
        List<Object> empty = Collections.emptyList();
        assertSame("an empty list was copied", empty, StoryViewerCleanupForTests.kept(empty));
        assertNull("no list came back as one", StoryViewerCleanupForTests.kept(null));
        // Until the patch fills in its question, the real hook reads no bucket as a card.
        assertSame("the unpatched hook read a bucket as a card", buckets, StoryViewerCleanup.withoutCreateCards(buckets));

        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertSame("paused, the list changed", buckets, StoryViewerCleanupForTests.kept(buckets));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertSame("the list changed before the settings were ready", buckets, StoryViewerCleanupForTests.kept(buckets)));
        assertFalse("it hid the Subscribe button", StoryViewerCleanup.hideSubscribe());
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + StoryViewerCleanup.CREATE_CARDS_DROPPED + " 2"));
    }

    /** #521: a card check that throws leaves the list as Facebook built it and names the hook. */
    @Test
    public void aFailingCardCheckLeavesTheListAsItCame() {
        Settings.HIDE_STORY_CREATE_CARDS.save(true);
        List<Object> buckets = Arrays.asList(new Object(), new StoryViewerCleanupForTests.CreateCard());
        assertSame(buckets, StoryViewerCleanup.withoutCreateCards(buckets, bucket -> {
            throw new IllegalStateException("a bucket that can't be read");
        }));
        assertEquals(Collections.singletonList(
                        "a working 'story create card list' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.STORY_VIEWER_CLEANUP));
    }

    /**
     * #521: with Plain slide between people on, the cube Facebook turns between people's stories keeps
     * each page square to the screen, so the pages only slide. Off, paused or before the settings are
     * ready, a page turns as far as Facebook asks.
     */
    @Test
    public void theFlatSlideSwitchStartsOffAndKeepsThePagesFromTurning() {
        assertFalse(Settings.STORY_FLAT_SLIDE.defaultValue);
        assertTrue(PatchFamily.STORY_VIEWER_CLEANUP.switches.contains(Settings.STORY_FLAT_SLIDE));
        View page = new View(RuntimeEnvironment.getApplication());
        StoryViewerCleanup.cubeTurn(page, 45f);
        assertEquals("off, the page didn't turn", 45f, page.getRotationY(), 0f);

        Settings.STORY_FLAT_SLIDE.save(true);
        StoryViewerCleanup.cubeTurn(page, -30f);
        assertEquals("on, the page still turned", 0f, page.getRotationY(), 0f);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        StoryViewerCleanup.cubeTurn(page, 20f);
        assertEquals("paused, the page didn't turn", 20f, page.getRotationY(), 0f);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            StoryViewerCleanup.cubeTurn(page, 10f);
            assertEquals("before the settings were ready, the page didn't turn", 10f, page.getRotationY(), 0f);
        });
        String line = statusLine();
        assertTrue(line, line != null && line.startsWith(FamilyNames.STORY_VIEWER_CLEANUP + ": invoked 4, 1 found, 0 missing"));
        assertFalse("a turn was counted: " + line, line.contains("Counted"));
        assertFalse("it hid the credits", StoryViewerCleanup.hideCredits());
    }

    @Test
    public void offPausedOrColdBothPartsDrawAsFacebookHasThem() {
        assertFalse("off, the credits went", StoryViewerCleanup.hideCredits());
        assertFalse("off, the reply suggestions went", StoryViewerCleanup.hideReplySuggestions());

        Settings.HIDE_STORY_CREDITS.save(true);
        Settings.HIDE_STORY_REPLY_SUGGESTIONS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " hid the credits", StoryViewerCleanup.hideCredits());
            assertFalse("a Hushfacebook paused by " + reason + " hid the reply suggestions",
                    StoryViewerCleanup.hideReplySuggestions());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse("the credits went before the settings were ready", StoryViewerCleanup.hideCredits());
            assertFalse("the reply suggestions went before the settings were ready",
                    StoryViewerCleanup.hideReplySuggestions());
        });

        String line = statusLine();
        assertFalse("a part left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the credits stayed", StoryViewerCleanup.hideCredits());
        assertTrue("on again after the pause, the reply suggestions stayed", StoryViewerCleanup.hideReplySuggestions());
    }
}
