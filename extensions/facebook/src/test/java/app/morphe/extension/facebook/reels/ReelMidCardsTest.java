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
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.reels.ReelMidCardsForTests.MidCard;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests.MidCardType;
import app.morphe.extension.facebook.reels.ReelMidCardsForTests.Section;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The mid-card filter Clean up Reels and Hide reel interest prompts share: on, the Threads card
 * and the interest survey each come off their section while their own patch is in and their
 * switch is on, and the report counts them, while a reel and every other mid-card stay. Off,
 * paused, unpatched or failing, the page goes on as Facebook sent it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelMidCardsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void bothPatchesInTheBuild() {
        ReelMidCards.threadsPatchForTests = Boolean.TRUE;
        ReelMidCards.promptsPatchForTests = Boolean.TRUE;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        ReelMidCards.threadsPatchForTests = null;
        ReelMidCards.promptsPatchForTests = null;
        ReelMidCards.groupsPatchForTests = null;
        Settings.HIDE_REEL_THREADS_CARDS.resetToDefault();
        Settings.HIDE_REEL_PROMPTS.resetToDefault();
        Settings.HIDE_SUGGESTED_GROUPS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String line() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(ReelMidCards.SECTIONS_ROUTE + ": ")) return line;
        }
        return null;
    }

    private static Section section(Object... items) {
        return new Section(new ArrayList<>(Arrays.asList(items)));
    }

    /** Nobody has seen the card go on a signed-in Reels feed yet, so the switch waits to be turned on. */
    @Test
    public void theSwitchStartsOffAndOffTheCardStays() {
        assertFalse(Settings.HIDE_REEL_THREADS_CARDS.defaultValue);
        MidCard card = new MidCard(MidCardType.THREADS_MIDCARD);
        Section section = section(new Object(), card);
        List<Section> page = Collections.singletonList(section);

        assertSame(page, ReelMidCardsForTests.withoutThreadsCards(page));
        assertTrue(section.items.contains(card));
    }

    /** On, the Threads card goes, its neighbours keep their order, and the report names what it read. */
    @Test
    public void onTheThreadsCardComesOffAndIsCounted() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        Object reel = new Object();
        MidCard threads = new MidCard(MidCardType.THREADS_MIDCARD);
        MidCard people = new MidCard(MidCardType.PYML_MIDCARD);
        Section section = section(reel, threads, people);
        List<Section> page = Collections.singletonList(section);

        assertSame("a section that still holds items stays", page, ReelMidCardsForTests.withoutThreadsCards(page));
        assertEquals(Arrays.asList(reel, people), section.items);

        String line = line();
        assertNotNull(FeedFilterCounters.report().toString(), line);
        assertTrue(line, line.contains("1 removed"));
        assertTrue(line, line.contains("Removed: mid-card THREADS_MIDCARD 1"));
        assertTrue(line, line.contains("Kinds: "));
        assertTrue(line, line.contains("mid-card THREADS_MIDCARD 1"));
        assertTrue(line, line.contains("mid-card PYML_MIDCARD 1"));
        assertTrue(line, line.contains(ReelMidCards.NOT_A_MID_CARD + " Object 1"));
    }

    /**
     * #405: the "To what extent does this video match your interests?" survey between reels comes
     * off with Hide reel interest prompts, on from the start, while the Threads card stays with its
     * own switch off.
     */
    @Test
    public void theInterestSurveyComesOffWithTheInterestPromptSwitch() {
        assertTrue(Settings.HIDE_REEL_PROMPTS.defaultValue);
        Object reel = new Object();
        MidCard survey = new MidCard(MidCardType.USER_SURVEY);
        MidCard threads = new MidCard(MidCardType.THREADS_MIDCARD);
        Section section = section(reel, survey, threads);
        List<Section> page = Collections.singletonList(section);

        assertSame(page, ReelMidCardsForTests.withoutThreadsCards(page));
        assertEquals(Arrays.asList(reel, threads), section.items);

        String line = line();
        assertNotNull(FeedFilterCounters.report().toString(), line);
        assertTrue(line, line.contains("Removed: mid-card USER_SURVEY 1"));
        assertFalse(line, line.contains("Removed: mid-card THREADS_MIDCARD"));
    }

    /** With both switches on, both cards come off and the report names each. */
    @Test
    public void bothSwitchesOnTakeBothCards() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        Object reel = new Object();
        Section section = section(new MidCard(MidCardType.THREADS_MIDCARD), reel, new MidCard(MidCardType.USER_SURVEY));

        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(section));
        assertEquals(Collections.singletonList(reel), section.items);

        String line = line();
        assertTrue(line, line.contains("2 removed"));
        assertTrue(line, line.contains("mid-card THREADS_MIDCARD 1"));
        assertTrue(line, line.contains("mid-card USER_SURVEY 1"));
    }

    /** With Hide reel interest prompts turned off, the survey stays. */
    @Test
    public void theInterestPromptSwitchOffKeepsTheSurvey() {
        Settings.HIDE_REEL_PROMPTS.save(false);
        MidCard survey = new MidCard(MidCardType.USER_SURVEY);
        Section section = section(new Object(), survey);
        List<Section> page = Collections.singletonList(section);

        assertSame(page, ReelMidCardsForTests.withoutThreadsCards(page));
        assertTrue(section.items.contains(survey));
    }

    /**
     * The hook goes in with either patch, so each card answers to its own: a build without Hide
     * reel interest prompts keeps the survey and one without Clean up Reels keeps the Threads card,
     * whatever a restored backup saved for their switches.
     */
    @Test
    public void aCardStaysWhenItsPatchIsNotInTheBuild() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        MidCard survey = new MidCard(MidCardType.USER_SURVEY);
        MidCard threads = new MidCard(MidCardType.THREADS_MIDCARD);

        ReelMidCards.promptsPatchForTests = Boolean.FALSE;
        Section noPrompts = section(new Object(), survey, threads);
        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(noPrompts));
        assertTrue("no Hide reel interest prompts, the survey stays", noPrompts.items.contains(survey));
        assertFalse(noPrompts.items.contains(threads));

        ReelMidCards.promptsPatchForTests = Boolean.TRUE;
        ReelMidCards.threadsPatchForTests = Boolean.FALSE;
        Section noCleanUp = section(new Object(), survey, threads);
        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(noCleanUp));
        assertTrue("no Clean up Reels, the Threads card stays", noCleanUp.items.contains(threads));
        assertFalse(noCleanUp.items.contains(survey));
    }

    /**
     * #441: the "Suggested for you" groups card with its Join buttons comes off with Hide suggested
     * groups, on from the start, while Hide suggested and promoted posts is in the build, even with
     * both Reels switches off. Without that patch, or with the switch off, it stays.
     */
    @Test
    public void theGroupsCardComesOffWithHideSuggestedGroups() {
        assertTrue(Settings.HIDE_SUGGESTED_GROUPS.defaultValue);
        Settings.HIDE_REEL_PROMPTS.save(false);
        Object reel = new Object();
        MidCard groups = new MidCard(MidCardType.GYSJ_MIDCARD);
        MidCard pages = new MidCard(MidCardType.PYML_MIDCARD);

        ReelMidCards.groupsPatchForTests = Boolean.FALSE;
        Section noPatch = section(reel, groups, pages);
        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(noPatch));
        assertTrue("no Hide suggested and promoted posts, the groups card stays", noPatch.items.contains(groups));

        ReelMidCards.groupsPatchForTests = Boolean.TRUE;
        Section section = section(reel, groups, pages);
        List<Section> page = Collections.singletonList(section);
        assertSame(page, ReelMidCardsForTests.withoutThreadsCards(page));
        assertEquals(Arrays.asList(reel, pages), section.items);
        String line = line();
        assertNotNull(FeedFilterCounters.report().toString(), line);
        assertTrue(line, line.contains("Removed: mid-card GYSJ_MIDCARD 1"));

        Settings.HIDE_SUGGESTED_GROUPS.save(false);
        Section off = section(reel, groups);
        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(off));
        assertTrue("switched off, the groups card stays", off.items.contains(groups));
    }

    /** Every item that isn't a mid-card is counted by its class, so a report tells two kinds of reel item apart. */
    @Test
    public void otherItemsAreCountedByTheirClass() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        Section section = section(new Object(), new StringBuilder("photo"), new Object());

        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(section));

        String line = line();
        assertNotNull(FeedFilterCounters.report().toString(), line);
        assertTrue(line, line.contains(ReelMidCards.NOT_A_MID_CARD + " Object 2"));
        assertTrue(line, line.contains(ReelMidCards.NOT_A_MID_CARD + " StringBuilder 1"));
        assertEquals("3 items, none removed", 3, section.items.size());
    }

    /** A section that held only the card goes off the page, so the screen doesn't show an empty one. */
    @Test
    public void aSectionHoldingOnlyTheCardGoesOffThePage() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        Section reels = section(new Object());
        Section card = section(new MidCard(MidCardType.THREADS_MIDCARD));

        assertEquals(Collections.singletonList(reels),
                ReelMidCardsForTests.withoutThreadsCards(Arrays.asList(reels, card)));
    }

    /** Paused, the page is Facebook's own whatever is saved. */
    @Test
    public void pausedTheCardStays() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        MidCard card = new MidCard(MidCardType.THREADS_MIDCARD);
        Section section = section(new Object(), card);

        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(section));
        assertTrue(section.items.contains(card));
    }

    /** Until the patch fills the stub in, no card's type can be read, and every card stays. */
    @Test
    public void anUnpatchedTypeReaderKeepsTheCard() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        MidCard card = new MidCard(MidCardType.THREADS_MIDCARD);
        Section section = section(card);

        ReelMidCards.withoutThreadsCards(Collections.singletonList(section), ReelMidCardsForTests.MID_CARD);
        assertTrue(section.items.contains(card));
        assertTrue(line(), line().contains(ReelMidCards.NOT_PATCHED + " 1"));
    }

    /** A read that throws keeps the card, and the page goes on. */
    @Test
    public void aReadThatThrowsKeepsTheCard() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        MidCard card = new MidCard(MidCardType.THREADS_MIDCARD);
        Section section = section(card);

        ReelMidCards.withoutThreadsCards(Collections.singletonList(section), ReelMidCardsForTests.MID_CARD, item -> {
            throw new IllegalStateException("released tree");
        });
        assertTrue(section.items.contains(card));
        assertTrue(line(), line().contains(ReelMidCards.READ_FAILED + " 1"));
    }

    /** A card with no type, or one that isn't an enum constant, isn't taken for the Threads card. */
    @Test
    public void aCardWithoutATypeStays() {
        Settings.HIDE_REEL_THREADS_CARDS.save(true);
        MidCard card = new MidCard(null);
        Section section = section(card);

        ReelMidCardsForTests.withoutThreadsCards(Collections.singletonList(section));
        assertTrue(section.items.contains(card));
        assertTrue(line(), line().contains(ReelMidCards.NO_TYPE + " 1"));
    }
}
