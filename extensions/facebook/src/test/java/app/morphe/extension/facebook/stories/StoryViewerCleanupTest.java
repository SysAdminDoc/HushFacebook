/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
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
        Settings.HIDE_STORY_CREATE_CARDS.resetToDefault();
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
