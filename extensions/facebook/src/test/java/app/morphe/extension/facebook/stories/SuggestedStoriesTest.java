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

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests.Bucket;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests.Copy;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests.Label;
import app.morphe.extension.facebook.stories.SuggestedStoriesForTests.Type;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The filter first in the tray data's constructor: a bucket Facebook marks as suggested, by its
 * flag or by its first label, doesn't make it into the tray, and neither do the People you may know
 * and "Find friends from contacts" cards, by their bucket type, each behind its own switch, nor
 * Pages' stories and stories you've watched when those switches are on; every other bucket does,
 * in order; and a switch off, a pause, anything it can't read or any failure leaves the tray as
 * Facebook sent it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SuggestedStoriesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_STORIES.resetToDefault();
        Settings.HIDE_PEOPLE_YOU_MAY_KNOW.resetToDefault();
        Settings.HIDE_CONTACT_IMPORT_CARD.resetToDefault();
        Settings.HIDE_STORY_PROMPTS.resetToDefault();
        Settings.HIDE_PAGE_STORIES.resetToDefault();
        Settings.HIDE_SEEN_STORIES.resetToDefault();
        Settings.SHOW_ONLY_WATCHED_STORIES.resetToDefault();
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(null);
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(SuggestedStories.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_STORIES + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOnAndASuggestedBucketLeavesTheTray() {
        assertTrue("the switch starts off", Settings.HIDE_SUGGESTED_STORIES.get());
        Bucket own = new Bucket(false, null);
        Bucket suggested = new Bucket(true, null);
        Bucket friend = new Bucket(false, Label.NEWFRIEND);
        Object tray = SuggestedStoriesForTests.keptBuckets(Arrays.asList(own, suggested, friend));
        assertEquals(Arrays.asList(own, friend), tray);
        assertEquals(SuggestedStories.ROUTE + ": 1 lists, 3 items, 1 removed. Last reason: suggested. Removed: "
                + "suggested 1. Kinds: not suggested 2, suggested 1", counterLine());
        assertEquals(FamilyNames.SUGGESTED_STORIES + ": invoked 1, 3 found, 0 missing", statusLine());
        assertEquals("Hide suggested stories", FamilyNames.SUGGESTED_STORIES);
    }

    /** The label the tray's card also says "Suggested" for takes a bucket out on its own. */
    @Test
    public void aBucketLabelledSuggestedGoesToo() {
        Bucket labelled = new Bucket(false, Label.SUGGESTED);
        Bucket both = new Bucket(true, Label.SUGGESTED);
        Bucket friend = new Bucket(false, Label.FAMILY);
        Object tray = SuggestedStoriesForTests.keptBuckets(Arrays.asList(labelled, friend, both));
        assertEquals(Collections.singletonList(friend), tray);
        assertTrue(counterLine(), counterLine().contains("Removed: labelled suggested 1, suggested 1."));
    }

    /**
     * What comes back in place of Facebook's list is the copy the patch makes, an ImmutableList,
     * with the buckets that stay in their order.
     */
    @Test
    public void whatComesBackIsTheCopyInTheSameOrder() {
        List<Object> tray = new ArrayList<>();
        List<Object> followed = new ArrayList<>();
        for (Label label : Label.values()) {
            Bucket bucket = new Bucket(false, label == Label.SUGGESTED ? null : label);
            tray.add(bucket);
            followed.add(bucket);
            tray.add(new Bucket(true, label));
        }
        Object kept = SuggestedStoriesForTests.keptBuckets(tray);
        assertEquals(followed, kept);
        assertTrue("the answer isn't the copy the stub made", kept instanceof List && !(kept instanceof Copy)
                && kept.getClass().getName().startsWith("java.util.Collections$Unmodifiable"));
        assertTrue(counterLine(), counterLine().contains(" " + Label.values().length + " removed"));
    }

    /**
     * Negative control: a tray with no suggestion keeps the very list Facebook built, so the
     * constructor stores what it was handed. Labels other than SUGGESTED, NEWFRIEND and TRENDING
     * among them, don't count.
     */
    @Test
    public void aTrayWithNoSuggestionIsFacebooks() {
        List<Bucket> tray = new ArrayList<>();
        for (Label label : Label.values()) {
            if (label != Label.SUGGESTED) tray.add(new Bucket(false, label));
        }
        tray.add(new Bucket(false, null));
        assertSame(tray, SuggestedStoriesForTests.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains(" 0 removed"));
    }

    @Test
    public void offTheTrayIsFacebooks() {
        Settings.HIDE_SUGGESTED_STORIES.save(false);
        List<Bucket> tray = Arrays.asList(new Bucket(true, null), new Bucket(false, Label.SUGGESTED), new Bucket(false, null));
        assertSame(tray, SuggestedStoriesForTests.keptBuckets(tray));
        // Still counted, so a report says the hook is reached and what the tray held.
        assertEquals(SuggestedStories.ROUTE + ": 1 lists, 3 items, 0 removed. Kinds: labelled suggested 1, "
                + "not suggested 1, suggested 1", counterLine());
    }

    @Test
    public void pausedTheTrayIsFacebooks() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            List<Bucket> tray = Arrays.asList(new Bucket(true, null), new Bucket(false, null));
            assertSame(why + " filtered the tray", tray, SuggestedStoriesForTests.keptBuckets(tray));
        }
        PauseForTests.resume();
        assertTrue("the filter didn't come back after the pause", SuggestedStoriesForTests.hidesSuggestions());
    }

    /** Until the settings are ready, the tray is Facebook's: a tray fetched at start-up keeps every bucket. */
    @Test
    public void untilTheSettingsAreReadyTheTrayIsFacebooks() {
        boolean[] hid = {true, true};
        SettingsContextRule.withoutContext(() -> hid[0] = SuggestedStoriesForTests.hidesSuggestions());
        SettingsContextRule.beforeThePauseIsDecided(() -> hid[1] = SuggestedStoriesForTests.hidesSuggestions());
        assertFalse(hid[0]);
        assertFalse(hid[1]);
        assertTrue(SuggestedStoriesForTests.hidesSuggestions());
    }

    /** Items that aren't buckets, the Create story tile or a loading placeholder, pass through and are counted by shape. */
    @Test
    public void whatIsntABucketStays() {
        Object tile = new Object();
        Bucket suggested = new Bucket(true, null);
        Object tray = SuggestedStoriesForTests.keptBuckets(Arrays.asList(tile, suggested, null, "loading"));
        assertEquals(Arrays.asList(tile, null, "loading"), tray);
        assertTrue(counterLine(), counterLine().contains("Kinds: not a bucket 3, suggested 1"));
    }

    /** A label that isn't an enum constant isn't SUGGESTED, even when it reads the same. */
    @Test
    public void aLabelThatIsntAnEnumStays() {
        List<Bucket> tray = Collections.singletonList(new Bucket(false, "SUGGESTED"));
        assertSame(tray, SuggestedStoriesForTests.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains(SuggestedStories.READ_FAILED + " 1"));
    }

    /** Before the patch fills the stubs in, every item reads as no bucket and the tray stays. */
    @Test
    public void theUnpatchedStubsLeaveTheTray() {
        List<Bucket> tray = Collections.singletonList(new Bucket(true, Label.SUGGESTED));
        assertSame(tray, SuggestedStories.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains("Kinds: " + SuggestedStories.NOT_A_BUCKET + " 1"));
        assertFalse(SuggestedStories.isBucket(new Object()));
        assertSame(SuggestedStories.UNPATCHED, SuggestedStories.bucketType(new Object()));
        assertSame(SuggestedStories.UNPATCHED, SuggestedStories.suggested(new Object()));
        assertSame(SuggestedStories.UNPATCHED, SuggestedStories.label(new Object()));
        assertNull(SuggestedStories.immutableCopy(Collections.emptyList()));
    }

    /** A flag or label stub the patch didn't fill is reported in Hook status, and the bucket stays. */
    @Test
    public void anUnfilledStubIsReported() {
        SuggestedStories.Buckets noFlag = standIn(true, true, false, true);
        List<Bucket> tray = Collections.singletonList(new Bucket(true, null));
        assertSame(tray, SuggestedStories.keptBuckets(tray, noFlag));
        assertTrue(statusLine(), statusLine().contains("1 missing"));
        assertTrue(statusLine(), statusLine().contains("tray bucket#" + SuggestedStories.SUGGESTED_FLAG));

        HookStatus.clear();
        SuggestedStories.Buckets noLabel = standIn(true, true, true, false);
        List<Bucket> labelled = Collections.singletonList(new Bucket(false, Label.SUGGESTED));
        assertSame(labelled, SuggestedStories.keptBuckets(labelled, noLabel));
        assertTrue(statusLine(), statusLine().contains("tray bucket#first label"));
        assertTrue(counterLine(), counterLine().contains(SuggestedStories.NOT_PATCHED + " 2"));
    }

    /**
     * A copy stub the patch didn't fill leaves Facebook's list, every bucket in it, and nothing is
     * counted as removed.
     */
    @Test
    public void noCopyLeavesTheTrayWhole() {
        SuggestedStories.Buckets noCopy = standIn(true, true, true, true);
        List<Bucket> tray = Arrays.asList(new Bucket(true, null), new Bucket(false, null));
        assertSame(tray, SuggestedStories.keptBuckets(tray, noCopy));
        assertTrue(counterLine(), counterLine().contains(" 0 removed"));
        assertTrue(statusLine(), statusLine().contains("ImmutableList#copyOf(Collection)"));
    }

    /** A read that throws is recorded, the rest of the tray is still judged, and nothing crashes. */
    @Test
    public void aThrowingReadKeepsThatBucket() {
        SuggestedStories.Buckets throwing = new SuggestedStories.Buckets() {
            @Override
            public boolean isBucket(Object item) {
                return item instanceof Bucket || item instanceof String;
            }

            @Override
            public Object type(Object bucket) {
                return bucket instanceof Bucket ? SuggestedStoriesForTests.STAND_IN.type(bucket) : null;
            }

            @Override
            public Object suggested(Object bucket) {
                if (bucket instanceof String) throw new IllegalStateException("released tree");
                return SuggestedStoriesForTests.STAND_IN.suggested(bucket);
            }

            @Override
            public Object label(Object bucket) {
                return SuggestedStoriesForTests.STAND_IN.label(bucket);
            }

            @Override
            public Object copy(List<Object> kept) {
                return SuggestedStoriesForTests.STAND_IN.copy(kept);
            }
        };
        Bucket suggested = new Bucket(true, null);
        Object tray = SuggestedStories.keptBuckets(Arrays.asList("broken", suggested, new Bucket(false, null)), throwing);
        assertEquals(2, ((List<?>) tray).size());
        assertFalse(((List<?>) tray).contains(suggested));
        assertTrue(counterLine(), counterLine().contains(SuggestedStories.READ_FAILED + " 1"));
        assertTrue(statusLine(), statusLine().contains("IllegalStateException"));
    }

    /** A list that fails as it's walked hands Facebook back its own list: the filter fails open. */
    @Test
    public void aFailureMidTrayFailsOpen() {
        List<Object> broken = new ArrayList<Object>(Arrays.asList(new Bucket(true, null), new Bucket(false, null))) {
            @Override
            public Object get(int index) {
                if (index == 1) throw new IndexOutOfBoundsException("changed under the filter");
                return super.get(index);
            }
        };
        assertSame(broken, SuggestedStoriesForTests.keptBuckets(broken));
        assertTrue(statusLine(), statusLine().contains("IndexOutOfBoundsException"));
    }

    @Test
    public void anEmptyOrMissingTrayIsHandedBack() {
        List<Bucket> empty = Collections.emptyList();
        assertSame(empty, SuggestedStoriesForTests.keptBuckets(empty));
        assertNull(SuggestedStoriesForTests.keptBuckets(null));
    }

    /**
     * With debug logging on, each tray says how many buckets it held and kept and of which kinds,
     * never whose stories they are.
     */
    @Test
    public void theDebugLineNamesKindsOnly() {
        LogBufferManager.clearLogBuffer();
        try {
            BaseSettings.DEBUG.save(true);
            SuggestedStoriesForTests.keptBuckets(Arrays.asList(new Bucket(true, null), new Bucket(false, Label.NEWFRIEND),
                    new Bucket(false, null)));
            Settings.HIDE_SUGGESTED_STORIES.save(false);
            SuggestedStoriesForTests.keptBuckets(Collections.singletonList(new Bucket(true, null)));
            String log = LogBufferManager.buildExportText();
            assertTrue(log, log.contains("Stories tray: 3 buckets, 2 kept. Kinds: suggested 1, not suggested 2"));
            assertTrue(log, log.contains("Stories tray: 1 buckets, 1 kept. Kinds: suggested 1 (off: suggested)"));
            assertFalse(log, log.contains("NEWFRIEND"));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test
    public void theDebugSummaryNamesKindsInTheOrderTheyCame() {
        assertEquals("not suggested 2, suggested 1, not a bucket 1",
                SuggestedStories.summary(Arrays.asList("not suggested", "suggested", "not suggested", "not a bucket")));
        assertEquals("", SuggestedStories.summary(Collections.emptyList()));
    }

    @Test
    public void theProbeHidesBothKindsOfSuggestion() {
        assertTrue(SuggestedStoriesForTests.hidesSuggestions());
    }

    // The cards the tray draws for a bucket's type.

    /**
     * With Hide suggested and promoted posts in the build and its People you may know switch on,
     * both kinds of People you may know card leave the tray, and so does the contacts card, which
     * has its own switch. The stories stay in order.
     */
    @Test
    public void theFriendSuggestionCardsLeaveTheTray() {
        assertTrue("the contacts switch starts on", Settings.HIDE_CONTACT_IMPORT_CARD.get());
        assertTrue(Settings.HIDE_PEOPLE_YOU_MAY_KNOW.get());
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(true);
        Bucket own = Bucket.of(Type.STORY);
        Bucket friend = new Bucket(false, Label.NEWFRIEND);
        List<Bucket> tray = Arrays.asList(own, Bucket.of(Type.CONTACT_IMPORTER_STORY), Bucket.of(Type.PYMK_STORY),
                friend, Bucket.of(Type.PYMK_PROFILE_FORWARD_STORY), Bucket.of(Type.PYMK_STORY));
        assertEquals(Arrays.asList(own, friend), SuggestedStoriesForTests.keptBuckets(tray));
        // The counter lists kinds most often seen first, then by name.
        assertEquals(SuggestedStories.ROUTE + ": 1 lists, 6 items, 4 removed. Last reason: people you may know. "
                + "Removed: people you may know 3, contact import card 1. Kinds: people you may know 3, "
                + "not suggested 2, contact import card 1", counterLine());
        assertEquals(FamilyNames.SUGGESTED_STORIES + ": invoked 1, 3 found, 0 missing", statusLine());
    }

    /**
     * The People you may know switch belongs to Hide suggested and promoted posts. Without that
     * patch the switch isn't on the screen, so its cards stay; the contacts card still goes.
     */
    @Test
    public void withoutItsPatchThePeopleYouMayKnowCardsStay() {
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(false);
        Bucket card = Bucket.of(Type.PYMK_STORY);
        Bucket forward = Bucket.of(Type.PYMK_PROFILE_FORWARD_STORY);
        Bucket friend = new Bucket(false, null);
        Object kept = SuggestedStoriesForTests.keptBuckets(Arrays.asList(card, Bucket.of(Type.CONTACT_IMPORTER_STORY), friend, forward));
        assertEquals(Arrays.asList(card, friend, forward), kept);
        assertTrue(counterLine(), counterLine().contains("Removed: contact import card 1."));
        // With no test saying, SettingsStatus answers, and unpatched it says the patch isn't in.
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(null);
        List<Bucket> alone = Collections.singletonList(card);
        assertSame(alone, SuggestedStoriesForTests.keptBuckets(alone));
    }

    /**
     * Issue #21: Hide story prompts starts off, and Facebook's own skip_srtt_item_list goes to the
     * server as it was. On, every tray fetch asks the server to leave the cards beside Create story
     * out. The Video tab's tray, which Facebook already asks it for, asks either way.
     */
    @Test
    public void storyPromptsLeaveOnlyWithTheirSwitchOn() {
        assertFalse("the switch starts off", Settings.HIDE_STORY_PROMPTS.get());
        assertFalse(SuggestedStories.skipPromptCards(false));
        assertTrue("the Video tab's tray", SuggestedStories.skipPromptCards(true));

        Settings.HIDE_STORY_PROMPTS.save(true);
        assertTrue(SuggestedStories.skipPromptCards(false));
        assertTrue(SuggestedStories.skipPromptCards(true));
        assertEquals(FamilyNames.SUGGESTED_STORIES + ": invoked 4, 0 found, 0 missing", statusLine());
    }

    /** The mutation controls for issue #21: a pause, or only the other switches on, leaves Facebook's answer. */
    @Test
    public void aPauseOrTheOtherSwitchesLeaveThePromptsAlone() {
        assertTrue(Settings.HIDE_SUGGESTED_STORIES.get());
        assertTrue(Settings.HIDE_CONTACT_IMPORT_CARD.get());
        assertFalse("the other switches", SuggestedStories.skipPromptCards(false));

        Settings.HIDE_STORY_PROMPTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", SuggestedStories.skipPromptCards(false));
        PauseForTests.resume();
        assertTrue("resumed", SuggestedStories.skipPromptCards(false));
    }

    /** Each card kind answers to its own switch, and Hide suggested stories reaches neither. */
    @Test
    public void eachCardHasItsOwnSwitch() {
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(true);
        Bucket card = Bucket.of(Type.PYMK_STORY);
        Bucket contacts = Bucket.of(Type.CONTACT_IMPORTER_STORY);
        Bucket suggested = new Bucket(true, null);

        Settings.HIDE_PEOPLE_YOU_MAY_KNOW.save(false);
        assertEquals(Collections.singletonList(card), SuggestedStoriesForTests.keptBuckets(Arrays.asList(card, contacts, suggested)));
        Settings.HIDE_PEOPLE_YOU_MAY_KNOW.save(true);
        Settings.HIDE_CONTACT_IMPORT_CARD.save(false);
        assertEquals(Collections.singletonList(contacts), SuggestedStoriesForTests.keptBuckets(Arrays.asList(card, contacts, suggested)));
        Settings.HIDE_CONTACT_IMPORT_CARD.save(true);
        Settings.HIDE_SUGGESTED_STORIES.save(false);
        assertEquals(Collections.singletonList(suggested), SuggestedStoriesForTests.keptBuckets(Arrays.asList(card, contacts, suggested)));
    }

    /**
     * The type picks the card Facebook draws, so a People you may know card that also carries the
     * suggested flag or label is a card: it stays with its own switch off, whatever the suggested
     * switch says.
     */
    @Test
    public void theTypeDecidesTheCardBeforeTheFlags() {
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(true);
        Settings.HIDE_PEOPLE_YOU_MAY_KNOW.save(false);
        List<Bucket> tray = Arrays.asList(new Bucket(true, null, Type.PYMK_STORY), new Bucket(false, Label.SUGGESTED, Type.PYMK_STORY));
        assertSame(tray, SuggestedStoriesForTests.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains("Kinds: people you may know 2"));
    }

    /**
     * Negative control: stories and the types that aren't these cards stay, a friend request and a
     * new friendship among them, and so do the People you may know types the tray doesn't draw as a
     * card, a bucket with no type and an unrecognised one.
     */
    @Test
    public void everyOtherTypeStays() {
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(true);
        List<Bucket> tray = new ArrayList<>();
        for (Type type : Type.values()) {
            if (type != Type.PYMK_STORY && type != Type.PYMK_PROFILE_FORWARD_STORY && type != Type.CONTACT_IMPORTER_STORY) {
                tray.add(Bucket.of(type));
            }
        }
        tray.add(Bucket.of(null));
        assertSame(tray, SuggestedStoriesForTests.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains("Kinds: " + SuggestedStories.NOT_SUGGESTED + " " + tray.size()));
    }

    /** A type that isn't an enum constant isn't a card, even when it reads the same; the flags still count. */
    @Test
    public void aTypeThatIsntAnEnumGoesByTheFlags() {
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(true);
        Bucket plain = new Bucket(false, null, "PYMK_STORY");
        Bucket suggested = new Bucket(true, null, "CONTACT_IMPORTER_STORY");
        assertEquals(Collections.singletonList(plain), SuggestedStoriesForTests.keptBuckets(Arrays.asList(plain, suggested)));
        assertTrue(counterLine(), counterLine().contains("Kinds: not suggested 1, suggested 1"));
    }

    /**
     * A type stub the patch didn't fill, or a type read that throws, is reported in Hook status, no
     * card leaves, and the suggested flag still does its work.
     */
    @Test
    public void anUnreadableTypeFallsBackToTheFlags() {
        SuggestedStoriesForTests.peopleYouMayKnowInBuild(true);
        Bucket card = Bucket.of(Type.PYMK_STORY);
        Bucket suggested = new Bucket(true, null, Type.CONTACT_IMPORTER_STORY);
        assertEquals(Collections.singletonList(card),
                SuggestedStories.keptBuckets(Arrays.asList(card, suggested), typeReadBy(SuggestedStories::bucketType)));
        assertTrue(statusLine(), statusLine().contains("tray bucket#" + SuggestedStories.BUCKET_TYPE_FIELD));
        assertTrue(statusLine(), statusLine().contains("1 missing"));

        HookStatus.clear();
        SuggestedStories.Buckets throwing = typeReadBy(bucket -> {
            throw new IllegalStateException("released tree");
        });
        assertEquals(Collections.singletonList(card), SuggestedStories.keptBuckets(Arrays.asList(card, suggested), throwing));
        assertTrue(statusLine(), statusLine().contains("IllegalStateException"));
    }

    /**
     * The test stand-in with its type read swapped for {@code type}, and every other read, the copy
     * included, as the stand-in has it. {@link #standIn} can't serve here: its copy is the unfilled
     * stub, so a tray it filters always comes back whole.
     */
    private static SuggestedStories.Buckets typeReadBy(java.util.function.Function<Object, Object> type) {
        return new SuggestedStories.Buckets() {
            @Override
            public boolean isBucket(Object item) {
                return SuggestedStoriesForTests.STAND_IN.isBucket(item);
            }

            @Override
            public Object type(Object bucket) {
                return type.apply(bucket);
            }

            @Override
            public Object suggested(Object bucket) {
                return SuggestedStoriesForTests.STAND_IN.suggested(bucket);
            }

            @Override
            public Object label(Object bucket) {
                return SuggestedStoriesForTests.STAND_IN.label(bucket);
            }

            @Override
            public Object copy(List<Object> kept) {
                return SuggestedStoriesForTests.STAND_IN.copy(kept);
            }
        };
    }

    /** Paused, or before the settings are ready, every card stays. */
    @Test
    public void pausedOrAtStartUpTheCardsStay() {
        assertTrue(SuggestedStoriesForTests.hidesPeopleYouMayKnow());
        assertTrue(SuggestedStoriesForTests.hidesContactImportCard());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(SuggestedStoriesForTests.hidesPeopleYouMayKnow());
        assertFalse(SuggestedStoriesForTests.hidesContactImportCard());
        PauseForTests.resume();
        boolean[] hid = {true, true};
        SettingsContextRule.withoutContext(() -> hid[0] = SuggestedStoriesForTests.hidesPeopleYouMayKnow()
                || SuggestedStoriesForTests.hidesContactImportCard());
        SettingsContextRule.beforeThePauseIsDecided(() -> hid[1] = SuggestedStoriesForTests.hidesPeopleYouMayKnow()
                || SuggestedStoriesForTests.hidesContactImportCard());
        assertFalse(hid[0]);
        assertFalse(hid[1]);
        assertNull("the probe left its build answer behind", SuggestedStories.peopleYouMayKnowInBuildForTests);
    }

    /** The Debug line counts the cards by kind and names the switches that are off, never whose cards they are. */
    @Test
    public void theDebugLineCountsTheCards() {
        LogBufferManager.clearLogBuffer();
        try {
            BaseSettings.DEBUG.save(true);
            SuggestedStoriesForTests.peopleYouMayKnowInBuild(true);
            List<Bucket> tray = Arrays.asList(Bucket.of(Type.STORY), Bucket.of(Type.CONTACT_IMPORTER_STORY),
                    Bucket.of(Type.PYMK_STORY), Bucket.of(Type.PYMK_STORY));
            SuggestedStoriesForTests.keptBuckets(tray);
            Settings.HIDE_PEOPLE_YOU_MAY_KNOW.save(false);
            Settings.HIDE_CONTACT_IMPORT_CARD.save(false);
            SuggestedStoriesForTests.keptBuckets(tray);
            String log = LogBufferManager.buildExportText();
            String hidden = "Stories tray: 4 buckets, 1 kept. Kinds: not suggested 1, contact import card 1, people you may know 2";
            int at = log.indexOf(hidden);
            assertTrue(log, at >= 0);
            assertFalse("every switch is on, so no note: " + log, log.startsWith(" (off", at + hidden.length()));
            assertTrue(log, log.contains("Stories tray: 4 buckets, 4 kept. Kinds: not suggested 1, contact import card 1, "
                    + "people you may know 2 (off: people you may know, contact import card)"));
            assertFalse(log, log.contains("PYMK"));
            assertFalse(log, log.contains("CONTACT_IMPORTER"));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
        }
    }

    /** Before the settings are ready, or when they can't be read, the debug line doesn't call the switches off. */
    @Test
    public void switchesNotReadArentCalledOff() {
        assertEquals(" (switches not read)", SuggestedStories.Switches.NONE.offNote());
        assertEquals(" (off: suggested, contact import card)",
                new SuggestedStories.Switches(false, false, false, false, false, false, false).offNote());
    }

    @Test
    public void theProbesHideTheCards() {
        assertTrue(SuggestedStoriesForTests.hidesPeopleYouMayKnow());
        assertTrue(SuggestedStoriesForTests.hidesContactImportCard());
        assertFalse("Hide stories from Pages starts off", SuggestedStoriesForTests.hidesPageStories());
        assertFalse("Hide seen stories starts off", SuggestedStoriesForTests.hidesSeenStories());
        Settings.HIDE_PAGE_STORIES.save(true);
        Settings.HIDE_SEEN_STORIES.save(true);
        assertTrue(SuggestedStoriesForTests.hidesPageStories());
        assertTrue(SuggestedStoriesForTests.hidesSeenStories());
        assertFalse("Show only watched stories starts off", SuggestedStoriesForTests.keepsOnlyWatchedStories());
        Settings.SHOW_ONLY_WATCHED_STORIES.save(true);
        assertTrue(SuggestedStoriesForTests.keepsOnlyWatchedStories());
    }

    /**
     * Issue #313: with Hide stories from Pages on, a Page's bucket leaves the tray, whether its owner
     * says Page or its type is one a Page's stories come as, and whether or not Facebook suggests it.
     * A profile-style Page's bucket goes by its type though its owner says User (the S22's tray kept
     * one, 2026-10-31). A person's stories stay, professional mode included, and so does a bucket with
     * no owner. Off, the tray is Facebook's.
     */
    @Test
    public void pageStoriesLeaveWithTheirSwitch() {
        assertFalse("the switch starts off", Settings.HIDE_PAGE_STORIES.get());
        Bucket friend = new Bucket(false, null).ownedBy("User");
        Bucket page = new Bucket(false, null).ownedBy(SuggestedStories.PAGE_OWNER);
        Bucket suggestedPage = new Bucket(true, null).ownedBy(SuggestedStories.PAGE_OWNER);
        Bucket typed = Bucket.of(Type.PAGE_STORY);
        Bucket shared = Bucket.of(Type.SHARED_PAGE_STORY);
        Bucket profilePage = new Bucket(false, null, Type.ADMINED_ADDITIONAL_PROFILE_STORY).ownedBy("User");
        Bucket profilePlus = new Bucket(false, null, Type.PROFILE_PLUS_STORY).ownedBy("User");
        Bucket creator = new Bucket(false, null, Type.PROFESSIONAL_STORY).ownedBy("User");
        Bucket ownerless = new Bucket(false, null);
        List<Bucket> tray = Arrays.asList(friend, page, suggestedPage, typed, shared, profilePage, profilePlus,
                creator, ownerless);
        Settings.HIDE_SUGGESTED_STORIES.save(false);
        assertSame("off, Facebook's tray", tray, SuggestedStoriesForTests.keptBuckets(tray));

        Settings.HIDE_PAGE_STORIES.save(true);
        assertEquals(Arrays.asList(friend, creator, ownerless), SuggestedStoriesForTests.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains("Removed: " + SuggestedStories.PAGE_STORY + " 6."));
        assertTrue(statusLine(), statusLine().endsWith(" 0 missing"));
    }

    /**
     * Issue #317: with Hide seen stories on, a bucket you've watched to the end leaves the tray,
     * a person's or a Page's alike. The Add to story tiles and the cards aren't stories you watch, so
     * they stay whatever their flag says.
     */
    @Test
    public void watchedStoriesLeaveWithTheirSwitch() {
        assertFalse("the switch starts off", Settings.HIDE_SEEN_STORIES.get());
        Bucket fresh = new Bucket(false, null).ownedBy("User");
        Bucket watched = new Bucket(false, Label.FAMILY).ownedBy("User").watched();
        Bucket page = new Bucket(false, null).ownedBy(SuggestedStories.PAGE_OWNER).watched();
        Bucket create = new Bucket(false, null, Type.ADD_TO_STORY).watched();
        Bucket self = new Bucket(false, null, Type.ADD_TO_STORY_SELF_VIEW).watched();
        Bucket card = new Bucket(false, null, Type.CONTACT_IMPORTER_STORY).watched();
        List<Bucket> tray = Arrays.asList(create, self, fresh, watched, page, card);
        Settings.HIDE_CONTACT_IMPORT_CARD.save(false);
        assertSame("off, Facebook's tray", tray, SuggestedStoriesForTests.keptBuckets(tray));

        Settings.HIDE_SEEN_STORIES.save(true);
        assertEquals(Arrays.asList(create, self, fresh, card), SuggestedStoriesForTests.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains("Last reason: " + SuggestedStories.SEEN + "."));
        assertTrue(counterLine(), counterLine().contains("Removed: " + SuggestedStories.SEEN + " 2."));
    }

    /**
     * Issue #340: with Show only watched stories on, the tray keeps only the stories you've watched
     * to the end, a person's or a Page's, suggested or not, so one can be found again. The Add to
     * story tiles and the cards stay, and Hide seen stories waits while it's on.
     */
    @Test
    public void onlyWatchedStoriesStayWithTheirSwitch() {
        assertFalse("the switch starts off", Settings.SHOW_ONLY_WATCHED_STORIES.get());
        Bucket fresh = new Bucket(false, null).ownedBy("User");
        Bucket watched = new Bucket(false, Label.FAMILY).ownedBy("User").watched();
        Bucket page = new Bucket(false, null).ownedBy(SuggestedStories.PAGE_OWNER);
        Bucket watchedPage = new Bucket(false, null).ownedBy(SuggestedStories.PAGE_OWNER).watched();
        Bucket suggested = new Bucket(true, null).ownedBy("User");
        Bucket create = new Bucket(false, null, Type.ADD_TO_STORY);
        Bucket self = new Bucket(false, null, Type.ADD_TO_STORY_SELF_VIEW);
        Bucket card = new Bucket(false, null, Type.CONTACT_IMPORTER_STORY);
        List<Bucket> tray = Arrays.asList(create, self, fresh, watched, page, watchedPage, suggested, card);
        Settings.HIDE_SUGGESTED_STORIES.save(false);
        Settings.HIDE_CONTACT_IMPORT_CARD.save(false);
        assertSame("off, Facebook's tray", tray, SuggestedStoriesForTests.keptBuckets(tray));

        Settings.SHOW_ONLY_WATCHED_STORIES.save(true);
        Settings.HIDE_SEEN_STORIES.save(true);
        assertEquals("Hide seen stories didn't wait", Arrays.asList(create, self, watched, watchedPage, card),
                SuggestedStoriesForTests.keptBuckets(tray));
        assertTrue(counterLine(), counterLine().contains("Last reason: " + SuggestedStories.UNWATCHED + "."));
        assertTrue(counterLine(), counterLine().contains("Removed: " + SuggestedStories.UNWATCHED + " 3."));

        // Off again, Hide seen stories takes its turn.
        Settings.SHOW_ONLY_WATCHED_STORIES.save(false);
        assertEquals(Arrays.asList(create, self, fresh, page, suggested, card), SuggestedStoriesForTests.keptBuckets(tray));
    }

    /** A build where the patch couldn't fill the seen stub: with Show only watched stories on, every bucket stays. */
    @Test
    public void anUnreadableSeenFlagKeepsTheBucketWithOnlyWatchedOn() {
        List<Bucket> tray = Arrays.asList(new Bucket(false, null).ownedBy("User"),
                new Bucket(false, null).ownedBy("User").watched());
        Settings.SHOW_ONLY_WATCHED_STORIES.save(true);
        assertSame(tray, SuggestedStories.keptBuckets(tray, typeReadBy(SuggestedStoriesForTests.STAND_IN::type)));
        assertTrue(statusLine(), statusLine().endsWith(" 1 missing. First missing: method tray bucket#" + SuggestedStories.SEEN_FIELD));
    }

    /**
     * A build where the patch couldn't fill the seen or owner stub: with those switches off nothing
     * asks them, and with them on Hook status names what's missing and every bucket stays.
     */
    @Test
    public void unfilledSeenAndOwnerStubsAreReportedOnlyWhenTheirSwitchIsOn() {
        List<Bucket> tray = Arrays.asList(new Bucket(false, null).ownedBy("User").watched(),
                new Bucket(false, null).ownedBy(SuggestedStories.PAGE_OWNER));
        SuggestedStories.Buckets unfilled = typeReadBy(SuggestedStoriesForTests.STAND_IN::type);
        assertSame(tray, SuggestedStories.keptBuckets(tray, unfilled));
        assertTrue(statusLine(), statusLine().endsWith(" 0 missing"));

        // Hook status names the first stub found missing and counts the rest.
        Settings.HIDE_SEEN_STORIES.save(true);
        assertSame(tray, SuggestedStories.keptBuckets(tray, unfilled));
        assertTrue(statusLine(), statusLine().endsWith(" 1 missing. First missing: method tray bucket#" + SuggestedStories.SEEN_FIELD));

        Settings.HIDE_PAGE_STORIES.save(true);
        assertSame(tray, SuggestedStories.keptBuckets(tray, unfilled));
        assertTrue(statusLine(), statusLine().endsWith(" 2 missing. First missing: method tray bucket#" + SuggestedStories.SEEN_FIELD));
    }

    /**
     * The stand-in stubs with one or more of them left as the extension ships them: [bucket] false
     * leaves isBucket unpatched, [type], [flag] and [label] false leave those, and the copy is
     * always the unpatched one here.
     */
    private static SuggestedStories.Buckets standIn(boolean bucket, boolean type, boolean flag, boolean label) {
        return new SuggestedStories.Buckets() {
            @Override
            public boolean isBucket(Object item) {
                return bucket ? SuggestedStoriesForTests.STAND_IN.isBucket(item) : SuggestedStories.isBucket(item);
            }

            @Override
            public Object type(Object item) {
                return type ? SuggestedStoriesForTests.STAND_IN.type(item) : SuggestedStories.bucketType(item);
            }

            @Override
            public Object suggested(Object item) {
                return flag ? SuggestedStoriesForTests.STAND_IN.suggested(item) : SuggestedStories.suggested(item);
            }

            @Override
            public Object label(Object item) {
                return label ? SuggestedStoriesForTests.STAND_IN.label(item) : SuggestedStories.label(item);
            }

            @Override
            public Object copy(List<Object> kept) {
                return SuggestedStories.immutableCopy(kept);
            }
        };
    }
}
