/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.feed.TypedFeedUnit;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The Reels clean-up hooks: the chip filter keeps every chip it doesn't know and the order of the
 * rest, the Follow and footer hooks answer their switches, and every one of them takes Facebook's
 * path when paused, before the settings are ready, switched off, or when it fails.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelDeclutterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REMIX = "XFBFBShortsRemixAttribution";
    private static final String STARS = "XFBFBShortsSendStarsAttribution";
    private static final String SONG = "XFBFBShortsSoundtrackTitle";
    private static final String LOCATION = "XFBFBShortsLocationAttribution";

    /** The patch is in Morphe Manager's default selection with its switches off; these tests turn them on. */
    @Before
    public void turnTheSwitchOn() {
        Settings.HIDE_REEL_CHIPS.save(true);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_REEL_CHIPS.resetToDefault();
        Settings.HIDE_REEL_FOLLOW_BUTTON.resetToDefault();
        Settings.HIDE_REEL_SOCIAL_FOOTER.resetToDefault();
        Settings.HIDE_REEL_COUNTS.resetToDefault();
        Settings.HIDE_REEL_SAVE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_WHATSAPP_BUTTON.resetToDefault();
        Settings.HIDE_REEL_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_REEL_SHARE_BUTTON.resetToDefault();
        Settings.SHOW_REEL_COMMENT_BUTTON.resetToDefault();
        Settings.SHOW_REEL_POST_DATE.resetToDefault();
        Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_SOUND_BUTTON.resetToDefault();
        Settings.REEL_SPEAKER_ABOVE_MENU.resetToDefault();
        ReelDeclutter.sideSpeaker(null);
        Settings.HIDE_REEL_AUDIO_LINE.resetToDefault();
        Settings.HIDE_SLOW_CONNECTION_MESSAGE.resetToDefault();
        Settings.PLAY_FEED_REELS_WHOLE.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
        ReelDeclutter.forgetLoggedForTests();
    }

    private static TypedFeedUnit chip(String type) {
        return new TypedFeedUnit(type);
    }

    private static String line(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ": ")) return line;
        }
        return null;
    }

    /** The patch is in Morphe Manager's default selection, so all three switches start off. */
    @Test
    public void everySwitchStartsOff() {
        assertFalse(Settings.HIDE_REEL_CHIPS.defaultValue);
        assertFalse(Settings.HIDE_REEL_FOLLOW_BUTTON.defaultValue);
        assertFalse(Settings.HIDE_REEL_SOCIAL_FOOTER.defaultValue);
    }

    /**
     * Every type the switch names goes, and nothing else: the song line, a location, a type
     * Facebook adds later and a chip whose type can't be read all stay, in the order they came.
     */
    @Test
    public void onlyTheNamedChipsGoAndTheRestKeepTheirOrder() {
        List<Object> chips = new ArrayList<>();
        for (String hidden : ReelDeclutter.HIDDEN_CHIPS) chips.add(chip(hidden));
        TypedFeedUnit song = chip(SONG);
        TypedFeedUnit location = chip(LOCATION);
        TypedFeedUnit future = chip("XFBFBShortsSomethingNewAttribution");
        TypedFeedUnit.Unreadable unreadable = new TypedFeedUnit.Unreadable();
        chips.add(1, song);
        chips.add(4, location);
        chips.add(future);
        chips.add(0, unreadable);

        Object[] kept = ReelDeclutter.filterChips(Collections.unmodifiableList(chips));
        assertArrayEquals(new Object[]{unreadable, song, location, future}, kept);
        assertEquals(8, ReelDeclutter.HIDDEN_CHIPS.length);
    }

    /** Each hidden type on its own, so a name dropped from the list fails here by name. */
    @Test
    public void eachNamedChipGoesOnItsOwn() {
        for (String hidden : ReelDeclutter.HIDDEN_CHIPS) {
            TypedFeedUnit song = chip(SONG);
            assertArrayEquals(hidden, new Object[]{song},
                    ReelDeclutter.filterChips(Arrays.asList(chip(hidden), song)));
        }
    }

    /** A list with nothing to drop is Facebook's own: null tells the patch to keep it. */
    @Test
    public void aListWithNothingToDropIsLeftAsItCame() {
        assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(SONG), chip(LOCATION))));
        assertNull(ReelDeclutter.filterChips(Collections.emptyList()));
        assertNull("not a list", ReelDeclutter.filterChips(new Object()));
        assertNull(ReelDeclutter.filterChips(null));
    }

    /** Facebook's list can't hold null, and neither can the one rebuilt from the answer. */
    @Test
    public void aListHoldingNullIsLeftAsItCame() {
        assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), null)));
    }

    @Test
    public void switchedOffEveryChipStays() {
        Settings.HIDE_REEL_CHIPS.save(false);
        assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(STARS))));
        assertEquals(ReelDeclutter.CHIPS_ROUTE + ": 1 lists, 2 items, 0 removed", line(ReelDeclutter.CHIPS_ROUTE));
    }

    /**
     * The audio line (#200), "Original audio" or a song: the chips switch leaves it, and its own
     * switch takes it out alone, the other chips staying in the order they came. A reel with only
     * the line gets an empty list, which the footer draws as nothing. Paused, it stays.
     */
    @Test
    public void theAudioLineGoesOnlyWhileItsSwitchIsOn() {
        assertFalse(Settings.HIDE_REEL_AUDIO_LINE.defaultValue);
        assertEquals(SONG, ReelDeclutter.SOUNDTRACK_CHIP);
        TypedFeedUnit remix = chip(REMIX);
        TypedFeedUnit song = chip(SONG);
        TypedFeedUnit location = chip(LOCATION);
        assertNull("the chips switch took the audio line", ReelDeclutter.filterChips(Arrays.asList(song, location)));

        Settings.HIDE_REEL_CHIPS.save(false);
        Settings.HIDE_REEL_AUDIO_LINE.save(true);
        assertArrayEquals("the audio line switch took another chip", new Object[]{remix, location},
                ReelDeclutter.filterChips(Arrays.asList(remix, song, location)));
        assertArrayEquals(new Object[0], ReelDeclutter.filterChips(Collections.singletonList(song)));
        Settings.HIDE_REEL_CHIPS.save(true);
        assertArrayEquals(new Object[]{location}, ReelDeclutter.filterChips(Arrays.asList(remix, song, location)));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("the audio line went while paused", ReelDeclutter.filterChips(Arrays.asList(song, location)));
        PauseForTests.resume();
        Settings.HIDE_REEL_AUDIO_LINE.save(false);
        assertArrayEquals("turning the switch off didn't bring the audio line back", new Object[]{song, location},
                ReelDeclutter.filterChips(Arrays.asList(remix, song, location)));
    }

    /**
     * The Reels tab's own audio line row (#200) is drawn until the switch is on, then not, and it
     * comes back paused, before the settings are ready and once the switch is off again. The chips
     * switch alone doesn't touch it.
     */
    @Test
    public void theReelsTabAudioLineIsDrawnUntilItsSwitchIsOn() {
        assertFalse("the chips switch took the Reels tab audio line", ReelDeclutter.hideAudioLine());
        Settings.HIDE_REEL_AUDIO_LINE.save(true);
        assertTrue(ReelDeclutter.hideAudioLine());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("the audio line went while paused", ReelDeclutter.hideAudioLine());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("the audio line went before the settings were ready",
                ReelDeclutter.hideAudioLine()));
        Settings.HIDE_REEL_AUDIO_LINE.save(false);
        assertFalse("turning the switch off didn't bring the audio line back", ReelDeclutter.hideAudioLine());
        assertEquals(ReelDeclutter.AUDIO_ROUTE + ": 5 lists, 5 items, 1 removed. Last reason: " + ReelDeclutter.AUDIO_LINE
                + ". Removed: " + ReelDeclutter.AUDIO_LINE + " 1. Kinds: " + ReelDeclutter.AUDIO_LINE + " 5",
                line(ReelDeclutter.AUDIO_ROUTE));
    }

    /**
     * "Made with Edits" under a caption (#314) goes with the chips switch, which already hides the
     * Edits chip, and comes back paused, before the settings are ready and once the switch is off.
     * The audio line switch doesn't touch it.
     */
    @Test
    public void theEditsLineGoesWithTheChipsSwitch() {
        Settings.HIDE_REEL_CHIPS.save(false);
        Settings.HIDE_REEL_AUDIO_LINE.save(true);
        assertFalse("the audio line switch took the Edits line", ReelDeclutter.hideEditsLabel());
        Settings.HIDE_REEL_CHIPS.save(true);
        assertTrue(ReelDeclutter.hideEditsLabel());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("the Edits line went while paused", ReelDeclutter.hideEditsLabel());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("the Edits line went before the settings were ready",
                ReelDeclutter.hideEditsLabel()));
        Settings.HIDE_REEL_CHIPS.save(false);
        assertFalse("turning the switch off didn't bring the Edits line back", ReelDeclutter.hideEditsLabel());
        assertEquals(ReelDeclutter.EDITS_ROUTE + ": 5 lists, 5 items, 1 removed. Last reason: " + ReelDeclutter.EDITS_LABEL
                + ". Removed: " + ReelDeclutter.EDITS_LABEL + " 1. Kinds: " + ReelDeclutter.EDITS_LABEL + " 5",
                line(ReelDeclutter.EDITS_ROUTE));
    }

    /** A chips switch that can't be read draws the Edits line, and the report names the hook. */
    @Test
    public void anEditsLineSwitchThatCantBeReadDrawsTheLine() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_CHIPS);
        try {
            assertFalse(ReelDeclutter.hideEditsLabel());
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_CHIPS);
        }
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working '" + ReelDeclutter.EDITS_LABEL + "' hook (it threw " + NullPointerException.class.getName() + ")"));
    }

    /** A Reels tab audio line switch that can't be read draws the line, and the report names the hook. */
    @Test
    public void aReelsTabAudioLineSwitchThatCantBeReadDrawsTheLine() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_AUDIO_LINE);
        try {
            assertFalse(ReelDeclutter.hideAudioLine());
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_AUDIO_LINE);
        }
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working '" + ReelDeclutter.AUDIO_LINE + "' hook (it threw " + NullPointerException.class.getName() + ")"));
    }

    /**
     * A stalled video's slow connection message (#222) shows until its switch is on, then not, and it
     * comes back paused, before the settings are ready and once the switch is off again.
     */
    @Test
    public void theSlowConnectionMessageShowsUntilItsSwitchIsOn() {
        assertFalse(Settings.HIDE_SLOW_CONNECTION_MESSAGE.defaultValue);
        assertFalse("the other Reels switches took the message", ReelDeclutter.hideSlowConnectionMessage());
        Settings.HIDE_SLOW_CONNECTION_MESSAGE.save(true);
        assertTrue(ReelDeclutter.hideSlowConnectionMessage());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("the message went while paused", ReelDeclutter.hideSlowConnectionMessage());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("the message went before the settings were ready",
                ReelDeclutter.hideSlowConnectionMessage()));
        Settings.HIDE_SLOW_CONNECTION_MESSAGE.save(false);
        assertFalse("turning the switch off didn't bring the message back", ReelDeclutter.hideSlowConnectionMessage());
        assertEquals(ReelDeclutter.SLOW_ROUTE + ": 5 lists, 5 items, 1 removed. Last reason: " + ReelDeclutter.SLOW_MESSAGE
                + ". Removed: " + ReelDeclutter.SLOW_MESSAGE + " 1. Kinds: " + ReelDeclutter.SLOW_MESSAGE + " 5",
                line(ReelDeclutter.SLOW_ROUTE));
    }

    /**
     * A tile of the feed's Reels row (#228) keeps Facebook's clip and end until its switch is on, then
     * skips the clip and plays to the end, and both come back paused, before the settings are ready
     * and once the switch is off again. A check that already said no stays no either way.
     */
    @Test
    public void feedReelTilesPlayWholeOnlyWithTheSwitchOn() {
        assertFalse(Settings.PLAY_FEED_REELS_WHOLE.defaultValue);
        assertFalse("asked as each tile is drawn, so no restart", Settings.PLAY_FEED_REELS_WHOLE.rebootApp);
        assertTrue(ReelDeclutter.allowsPreviewClip(true));
        assertFalse(ReelDeclutter.allowsPreviewClip(false));
        assertEquals(1000, ReelDeclutter.previewClipEnd(1000));
        Settings.PLAY_FEED_REELS_WHOLE.save(true);
        assertFalse(ReelDeclutter.allowsPreviewClip(true));
        assertFalse(ReelDeclutter.allowsPreviewClip(false));
        assertEquals(ReelDeclutter.NO_END, ReelDeclutter.previewClipEnd(1000));
        assertEquals(ReelDeclutter.NO_END, ReelDeclutter.previewClipEnd(4500));
        assertEquals("a tile with no end isn't counted as changed", ReelDeclutter.NO_END,
                ReelDeclutter.previewClipEnd(ReelDeclutter.NO_END));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("the clip went while paused", ReelDeclutter.allowsPreviewClip(true));
        assertEquals("the end went while paused", 1000, ReelDeclutter.previewClipEnd(1000));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertTrue("the clip went before the settings were ready", ReelDeclutter.allowsPreviewClip(true));
            assertEquals("the end went before the settings were ready", 1000, ReelDeclutter.previewClipEnd(1000));
        });
        Settings.PLAY_FEED_REELS_WHOLE.save(false);
        assertTrue("turning the switch off didn't bring the clip back", ReelDeclutter.allowsPreviewClip(true));
        assertEquals(1000, ReelDeclutter.previewClipEnd(1000));
        assertEquals("every tile counts at its end, the clip check only as a kind", ReelDeclutter.PREVIEW_ROUTE
                + ": 7 lists, 7 items, 2 removed. Last reason: " + ReelDeclutter.PREVIEW_END
                + ". Removed: " + ReelDeclutter.PREVIEW_END + " 2. Kinds: " + ReelDeclutter.PREVIEW_CLIP + " 7, "
                + ReelDeclutter.PREVIEW_END + " 6, " + ReelDeclutter.PREVIEW_NO_END + " 1",
                line(ReelDeclutter.PREVIEW_ROUTE));
    }

    /**
     * A reel with comments turned off (#299) keeps Facebook's no until the switch is on, then gets
     * the Comment button, and the no comes back paused, before the settings are ready, once the
     * switch is off again and when it can't be read. A yes stays yes throughout.
     */
    @Test
    public void theCommentButtonShowsWithCommentsOffOnlyWithTheSwitchOn() {
        assertFalse(Settings.SHOW_REEL_COMMENT_BUTTON.defaultValue);
        assertFalse("asked as each reel's sidebar is built, so no restart", Settings.SHOW_REEL_COMMENT_BUTTON.rebootApp);
        assertTrue(ReelDeclutter.showsCommentButton(true));
        assertFalse(ReelDeclutter.showsCommentButton(false));
        Settings.SHOW_REEL_COMMENT_BUTTON.save(true);
        assertTrue(ReelDeclutter.showsCommentButton(false));
        assertTrue(ReelDeclutter.showsCommentButton(true));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("the button showed while paused", ReelDeclutter.showsCommentButton(false));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the button showed before the settings were ready", ReelDeclutter.showsCommentButton(false)));
        SettingReadsForTests.breakReads(Settings.SHOW_REEL_COMMENT_BUTTON);
        try {
            assertFalse("a switch that can't be read showed the button", ReelDeclutter.showsCommentButton(false));
            assertTrue(ReelDeclutter.showsCommentButton(true));
        } finally {
            SettingReadsForTests.mend(Settings.SHOW_REEL_COMMENT_BUTTON);
        }
        Settings.SHOW_REEL_COMMENT_BUTTON.save(false);
        assertFalse("turning the switch off didn't take the button away", ReelDeclutter.showsCommentButton(false));
        assertTrue(ReelDeclutter.showsCommentButton(true));
    }

    /**
     * A reel's post date (#296) keeps the server's no until the switch is on, then shows, and the no
     * comes back paused, before the settings are ready, once the switch is off again and when it
     * can't be read. A yes stays yes throughout.
     */
    @Test
    public void thePostDateShowsOnlyWithTheSwitchOn() {
        assertFalse(Settings.SHOW_REEL_POST_DATE.defaultValue);
        assertFalse("asked as each reel's footer is built, so no restart", Settings.SHOW_REEL_POST_DATE.rebootApp);
        assertTrue(ReelDeclutter.showsPostDate(true));
        assertFalse(ReelDeclutter.showsPostDate(false));
        Settings.SHOW_REEL_POST_DATE.save(true);
        assertTrue(ReelDeclutter.showsPostDate(false));
        assertTrue(ReelDeclutter.showsPostDate(true));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("the date showed while paused", ReelDeclutter.showsPostDate(false));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("the date showed before the settings were ready", ReelDeclutter.showsPostDate(false)));
        SettingReadsForTests.breakReads(Settings.SHOW_REEL_POST_DATE);
        try {
            assertFalse("a switch that can't be read showed the date", ReelDeclutter.showsPostDate(false));
            assertTrue(ReelDeclutter.showsPostDate(true));
        } finally {
            SettingReadsForTests.mend(Settings.SHOW_REEL_POST_DATE);
        }
        Settings.SHOW_REEL_POST_DATE.save(false);
        assertFalse("turning the switch off didn't take the date away", ReelDeclutter.showsPostDate(false));
        assertTrue(ReelDeclutter.showsPostDate(true));
    }

    /**
     * A video author's Subscribe pill (#291) goes only with the switch on, from the viewer's row and
     * from a shared row's slot. A Follow pill stays, a slot takes the builder's note once, and the
     * pill comes back paused, before the settings are ready and when the switch can't be read.
     */
    @Test
    public void theSubscribePillGoesOnlyWithTheSwitchOn() {
        assertFalse(Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON.defaultValue);
        assertFalse("asked as each row is built, so no restart", Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON.rebootApp);
        Object slot = new Object();
        assertTrue(ReelDeclutter.showAuthorPill(true, true));
        ReelDeclutter.noteAuthorPill(true);
        assertSame(slot, ReelDeclutter.authorPillSlot(slot));
        assertEquals("the row's own Subscribe with the switch off", 1, ReelDeclutter.authorPillAnswer(true));
        assertEquals("the row's own Follow with the switch off", 0, ReelDeclutter.authorPillAnswer(false));

        Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON.save(true);
        assertEquals("the row kept its own Subscribe", -1, ReelDeclutter.authorPillAnswer(true));
        assertEquals("the row lost its own Follow", 0, ReelDeclutter.authorPillAnswer(false));
        assertFalse("the row built the Subscribe pill", ReelDeclutter.showAuthorPill(true, true));
        assertTrue("the row lost its Follow pill", ReelDeclutter.showAuthorPill(false, true));
        assertFalse("a row with no pill got one", ReelDeclutter.showAuthorPill(false, false));
        assertFalse(ReelDeclutter.showAuthorPill(true, false));
        ReelDeclutter.noteAuthorPill(true);
        assertNull("the slot kept the Subscribe pill", ReelDeclutter.authorPillSlot(slot));
        assertSame("the note outlived its slot", slot, ReelDeclutter.authorPillSlot(slot));
        ReelDeclutter.noteAuthorPill(false);
        assertSame("the slot lost its Follow pill", slot, ReelDeclutter.authorPillSlot(slot));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("the pill went while paused", ReelDeclutter.showAuthorPill(true, true));
        assertEquals("the row's own pill went while paused", 1, ReelDeclutter.authorPillAnswer(true));
        ReelDeclutter.noteAuthorPill(true);
        assertSame("the slot emptied while paused", slot, ReelDeclutter.authorPillSlot(slot));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertTrue("the pill went before the settings were ready", ReelDeclutter.showAuthorPill(true, true)));
        SettingReadsForTests.breakReads(Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON);
        try {
            assertTrue("a switch that can't be read hid the pill", ReelDeclutter.showAuthorPill(true, true));
            ReelDeclutter.noteAuthorPill(true);
            assertSame("a switch that can't be read emptied the slot", slot, ReelDeclutter.authorPillSlot(slot));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON);
        }
        Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON.save(false);
        assertTrue("turning the switch off didn't bring the pill back", ReelDeclutter.showAuthorPill(true, true));
    }

    /** The report counts each pill under its kind and the Subscribe pills it took away. */
    @Test
    public void theReportCountsBothPillsAndTheSubscribeOnesTaken() {
        Settings.HIDE_VIDEO_SUBSCRIBE_BUTTON.save(true);
        assertTrue(ReelDeclutter.showAuthorPill(false, true));
        assertFalse(ReelDeclutter.showAuthorPill(true, true));
        assertEquals(ReelDeclutter.SUBSCRIBE_ROUTE + ": 2 lists, 2 items, 1 removed. Last reason: " + ReelDeclutter.SUBSCRIBE
                + ". Removed: " + ReelDeclutter.SUBSCRIBE + " 1. Kinds: " + ReelDeclutter.FOLLOW + " 1, "
                + ReelDeclutter.SUBSCRIBE + " 1", line(ReelDeclutter.SUBSCRIBE_ROUTE));
    }

    /** A whole reel switch that can't be read keeps Facebook's clip and end, and the report names the hook. */
    @Test
    public void aFeedReelSwitchThatCantBeReadKeepsTheClip() {
        SettingReadsForTests.breakReads(Settings.PLAY_FEED_REELS_WHOLE);
        try {
            assertTrue(ReelDeclutter.allowsPreviewClip(true));
            assertFalse(ReelDeclutter.allowsPreviewClip(false));
            assertEquals(1000, ReelDeclutter.previewClipEnd(1000));
        } finally {
            SettingReadsForTests.mend(Settings.PLAY_FEED_REELS_WHOLE);
        }
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working '" + ReelDeclutter.PREVIEW_CLIP + "' hook (it threw " + NullPointerException.class.getName() + ")"));
    }

    /** A slow connection message switch that can't be read shows the message, and the report names the hook. */
    @Test
    public void aSlowConnectionSwitchThatCantBeReadShowsTheMessage() {
        SettingReadsForTests.breakReads(Settings.HIDE_SLOW_CONNECTION_MESSAGE);
        try {
            assertFalse(ReelDeclutter.hideSlowConnectionMessage());
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_SLOW_CONNECTION_MESSAGE);
        }
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working '" + ReelDeclutter.SLOW_MESSAGE + "' hook (it threw " + NullPointerException.class.getName() + ")"));
    }

    /** An audio line switch that can't be read keeps Facebook's list, and the report names the hook. */
    @Test
    public void anAudioLineSwitchThatCantBeReadKeepsFacebooksList() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_AUDIO_LINE);
        try {
            assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG))));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_AUDIO_LINE);
        }
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working 'reel chips' hook (it threw " + NullPointerException.class.getName() + ")"));
    }

    /** A list that throws while it's read is kept, and the report names the hook that threw. */
    @Test
    public void aListThatThrowsIsKeptAndReported() {
        List<Object> broken = new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("released");
            }

            @Override
            public int size() {
                return 2;
            }
        };
        assertNull(ReelDeclutter.filterChips(broken));
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working 'reel chips' hook (it threw java.lang.IllegalStateException)"));
    }

    /** The report counts every list and chip type seen, and each chip taken out under its type. */
    @Test
    public void theReportCountsEachChipTypeSeenAndRemoved() {
        ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG)));
        ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(STARS), new TypedFeedUnit.Unreadable()));
        assertEquals(ReelDeclutter.CHIPS_ROUTE + ": 2 lists, 5 items, 3 removed. Last reason: " + STARS
                + ". Removed: " + REMIX + " 2, " + STARS + " 1. Kinds: " + REMIX + " 2, " + STARS + " 1, " + SONG
                + " 1, " + ReelDeclutter.UNREADABLE + " 1", line(ReelDeclutter.CHIPS_ROUTE));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains(FamilyNames.REEL_DECLUTTER + ": invoked 2, 0 found, 0 missing"));
    }

    /**
     * Facebook's Follow check answers no while the switch is on, so the author row is built with no
     * Follow button, on the Reels tab as anywhere else. The report counts the check under its kind.
     */
    @Test
    public void theFollowCheckAnswersNoWhileTheSwitchIsOn() {
        assertTrue(ReelDeclutter.hideFollowButton());
        assertEquals(ReelDeclutter.FOLLOW_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: " + ReelDeclutter.FOLLOW
                + ". Removed: " + ReelDeclutter.FOLLOW + " 1. Kinds: " + ReelDeclutter.FOLLOW + " 1",
                line(ReelDeclutter.FOLLOW_ROUTE));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains(FamilyNames.REEL_DECLUTTER + ": invoked 1, 0 found, 0 missing"));
    }

    /** The mutation control: off, Facebook's own check runs, and only the asking is counted. */
    @Test
    public void switchedOffFacebooksFollowCheckRuns() {
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        assertFalse(ReelDeclutter.hideFollowButton());
        assertEquals(ReelDeclutter.FOLLOW_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: " + ReelDeclutter.FOLLOW + " 1",
                line(ReelDeclutter.FOLLOW_ROUTE));
    }

    /**
     * One switch runs both of the row's hooks: the Follow check, and the getter that removes the
     * Following button an author you already follow gets. Each is counted under its own kind, so a
     * report says which of them Facebook asked.
     */
    @Test
    public void bothFollowButtonsGoWhileTheSwitchIsOn() {
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideFollowingButton());
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        assertFalse(ReelDeclutter.hideFollowButton());
        assertFalse(ReelDeclutter.hideFollowingButton());
        assertEquals(ReelDeclutter.FOLLOW_ROUTE + ": 4 lists, 4 items, 2 removed. Last reason: " + ReelDeclutter.FOLLOWING
                + ". Removed: " + ReelDeclutter.FOLLOW + " 1, " + ReelDeclutter.FOLLOWING + " 1. Kinds: "
                + ReelDeclutter.FOLLOW + " 2, " + ReelDeclutter.FOLLOWING + " 2", line(ReelDeclutter.FOLLOW_ROUTE));
    }

    /**
     * A Follow switch that can't be read leaves both of Facebook's answers alone, and the report
     * names each hook that threw. The read fails after the call is counted, which stands in for
     * anything that can throw there.
     */
    @Test
    public void aFollowSwitchThatCantBeReadLeavesFacebooksAnswers() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_FOLLOW_BUTTON);
        try {
            assertFalse(ReelDeclutter.hideFollowButton());
            assertFalse(ReelDeclutter.hideFollowingButton());
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_FOLLOW_BUTTON);
        }
        String threw = " hook (it threw " + NullPointerException.class.getName() + ")";
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.FOLLOW + "'" + threw,
                        "a working '" + ReelDeclutter.FOLLOWING + "'" + threw),
                HookStatus.missing(FamilyNames.REEL_DECLUTTER));
        assertTrue("the Follow hook didn't come back once its switch could be read", ReelDeclutter.hideFollowButton());
    }

    /** The search pill switch starts off, builds the pill while off, and answers the empty component while on. */
    @Test
    public void theSearchPillIsBuiltUntilItsSwitchIsOn() {
        assertFalse(Settings.HIDE_REEL_SEARCH_PILL.defaultValue);
        assertFalse(ReelDeclutter.hideSearchPill());
        Settings.HIDE_REEL_SEARCH_PILL.save(true);
        try {
            assertTrue(ReelDeclutter.hideSearchPill());
        } finally {
            Settings.HIDE_REEL_SEARCH_PILL.resetToDefault();
        }
        assertEquals(ReelDeclutter.SEARCH_ROUTE + ": 2 lists, 2 items, 1 removed. Last reason: " + ReelDeclutter.SEARCH_PILL
                + ". Removed: " + ReelDeclutter.SEARCH_PILL + " 1. Kinds: " + ReelDeclutter.SEARCH_PILL + " 2",
                line(ReelDeclutter.SEARCH_ROUTE));
    }

    /**
     * The same switch takes the prompt away from over a reel's comments (#197): handed back while
     * off, null while on, and a sheet with no prompt stays without one and isn't counted.
     */
    @Test
    public void theCommentSearchPromptGoesWithTheSearchPillSwitch() {
        Object prompt = new Object();
        assertSame(prompt, ReelDeclutter.commentSearchPrompt(prompt));
        Settings.HIDE_REEL_SEARCH_PILL.save(true);
        try {
            assertNull(ReelDeclutter.commentSearchPrompt(prompt));
            assertNull(ReelDeclutter.commentSearchPrompt(null));
        } finally {
            Settings.HIDE_REEL_SEARCH_PILL.resetToDefault();
        }
        assertSame("the prompt didn't come back once the switch was off", prompt,
                ReelDeclutter.commentSearchPrompt(prompt));
        assertEquals(ReelDeclutter.SEARCH_ROUTE + ": 3 lists, 3 items, 1 removed. Last reason: "
                        + ReelDeclutter.COMMENT_SEARCH_PILL + ". Removed: " + ReelDeclutter.COMMENT_SEARCH_PILL
                        + " 1. Kinds: " + ReelDeclutter.COMMENT_SEARCH_PILL + " 3",
                line(ReelDeclutter.SEARCH_ROUTE));
    }

    /**
     * And the search bar over a reel opened from a profile, with its send button (#201): each part
     * comes back while off, paused and before the settings are ready, null while on, and a part
     * Facebook didn't build stays missing and isn't counted.
     */
    @Test
    public void theSearchBarGoesWithTheSearchPillSwitch() {
        Object bar = new Object();
        Object send = new Object();
        assertSame(bar, ReelDeclutter.searchBarPart(bar));
        Settings.HIDE_REEL_SEARCH_PILL.save(true);
        assertNull(ReelDeclutter.searchBarPart(bar));
        assertNull(ReelDeclutter.searchBarPart(send));
        assertNull(ReelDeclutter.searchBarPart(null));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame("the search bar went while paused", bar, ReelDeclutter.searchBarPart(bar));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("the send button went before the settings were ready",
                send, ReelDeclutter.searchBarPart(send)));
        Settings.HIDE_REEL_SEARCH_PILL.save(false);
        assertSame("the search bar didn't come back once the switch was off", bar, ReelDeclutter.searchBarPart(bar));
        assertEquals(ReelDeclutter.SEARCH_ROUTE + ": 6 lists, 6 items, 2 removed. Last reason: "
                        + ReelDeclutter.SEARCH_BAR + ". Removed: " + ReelDeclutter.SEARCH_BAR
                        + " 2. Kinds: " + ReelDeclutter.SEARCH_BAR + " 6",
                line(ReelDeclutter.SEARCH_ROUTE));
    }

    /** A search pill switch that can't be read keeps the search bar, and the report names the hook. */
    @Test
    public void aSearchBarSwitchThatCantBeReadKeepsTheBar() {
        Object bar = new Object();
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_SEARCH_PILL);
        try {
            assertSame(bar, ReelDeclutter.searchBarPart(bar));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_SEARCH_PILL);
        }
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working '" + ReelDeclutter.SEARCH_BAR + "' hook (it threw " + NullPointerException.class.getName() + ")"));
    }

    /**
     * The sidebar counts (#180): the switch starts off, so every button keeps its text. On, a text
     * holding a number becomes one zero-width space, which keeps the text row and so the icons'
     * places, while a word, a missing text and an empty one stay as they came. Off again, the
     * numbers are back.
     */
    @Test
    public void theReelCountsStayUntilTheirSwitchIsOn() {
        assertFalse(Settings.HIDE_REEL_COUNTS.defaultValue);
        assertEquals("1.2K", ReelDeclutter.countText("1.2K"));

        Settings.HIDE_REEL_COUNTS.save(true);
        String hidden = ReelDeclutter.countText("1.2K");
        assertEquals(ReelDeclutter.NO_COUNT, hidden);
        assertEquals("the hidden count isn't one character", 1, hidden.length());
        assertEquals("the hidden count isn't a zero-width space", 0x200B, hidden.charAt(0));
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText("0"));
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText("12 M"));
        // A count in Eastern Arabic digits, as Arabic shows them.
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText(String.valueOf((char) 0x0661) + (char) 0x0662));
        assertEquals("a word lost its text", "Share", ReelDeclutter.countText("Share"));
        assertEquals("Like", ReelDeclutter.countText("Like"));
        assertEquals("", ReelDeclutter.countText(""));
        assertNull("a missing text got one", ReelDeclutter.countText(null));
        assertEquals(ReelDeclutter.COUNTS_ROUTE + ": 4 lists, 4 items, 4 removed. Last reason: " + ReelDeclutter.COUNT
                + ". Removed: " + ReelDeclutter.COUNT + " 4. Kinds: " + ReelDeclutter.COUNT + " 4",
                line(ReelDeclutter.COUNTS_ROUTE));

        Settings.HIDE_REEL_COUNTS.save(false);
        assertEquals("turning the switch off didn't bring the count back", "1.2K", ReelDeclutter.countText("1.2K"));
    }

    @Test
    public void pausedOrNotReadyTheReelCountsStay() {
        Settings.HIDE_REEL_COUNTS.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertEquals(why + " hid a reel count", "345", ReelDeclutter.countText("345"));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertEquals("345", ReelDeclutter.countText("345")));
        assertEquals("the count didn't go again after the pause", ReelDeclutter.NO_COUNT, ReelDeclutter.countText("345"));
    }

    /** A counts switch that can't be read leaves the text, and the report names the hook that threw. */
    @Test
    public void aCountsSwitchThatCantBeReadLeavesTheText() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_COUNTS);
        try {
            assertEquals("27", ReelDeclutter.countText("27"));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_COUNTS);
        }
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.COUNT + "' hook (it threw "
                + NullPointerException.class.getName() + ")"), HookStatus.missing(FamilyNames.REEL_DECLUTTER));
    }

    /** Stands in for Facebook's icon enum: the sidebar walks one constant per button. */
    private enum SidebarIcon { LIKE, COMMENT, SHARE, BOOKMARK, APP_WHATSAPP, APP_WHATSAPP_BUSINESS,
        APP_WHATSAPP_STATUS, DOWNLOAD, MORE }

    /** Not an enum, though its text is Save's name. */
    private static final String SAVE_AS_TEXT = "BOOKMARK";

    /**
     * The sidebar's Save button (#190): the switch starts off, so every button is built. On, only
     * the BOOKMARK constant is skipped. Like, Comment, Share, a missing type and a string that only
     * spells Save's name are built as before. Off again, Save is back.
     */
    @Test
    public void onlySaveIsSkippedAndOnlyWhileItsSwitchIsOn() {
        assertFalse(Settings.HIDE_REEL_SAVE_BUTTON.defaultValue);
        assertFalse("Save went with its switch off", ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));

        Settings.HIDE_REEL_SAVE_BUTTON.save(true);
        assertTrue("Save was built", ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
        assertFalse("Like went", ReelDeclutter.hideSidebarButton(SidebarIcon.LIKE));
        assertFalse("Comment went", ReelDeclutter.hideSidebarButton(SidebarIcon.COMMENT));
        assertFalse("Share went", ReelDeclutter.hideSidebarButton(SidebarIcon.SHARE));
        assertFalse("WhatsApp went with Save", ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP));
        assertFalse("a missing type was skipped", ReelDeclutter.hideSidebarButton(null));
        assertFalse("a string was taken for the enum", ReelDeclutter.hideSidebarButton(SAVE_AS_TEXT));
        assertTrue(ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
        assertEquals(ReelDeclutter.SIDEBAR_ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + ReelDeclutter.SAVE_BUTTON
                + ". Removed: " + ReelDeclutter.SAVE_BUTTON + " 2. Kinds: " + ReelDeclutter.SAVE_BUTTON + " 2",
                line(ReelDeclutter.SIDEBAR_ROUTE));

        Settings.HIDE_REEL_SAVE_BUTTON.save(false);
        assertFalse("turning the switch off didn't bring Save back", ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
    }

    @Test
    public void onlyTheDirectWhatsAppTypeIsSkippedByItsSwitch() {
        assertFalse(Settings.HIDE_REEL_WHATSAPP_BUTTON.defaultValue);
        assertFalse(ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP));
        Settings.HIDE_REEL_WHATSAPP_BUTTON.save(true);
        for (SidebarIcon icon : SidebarIcon.values()) {
            assertEquals(icon.name(), icon == SidebarIcon.APP_WHATSAPP, ReelDeclutter.hideSidebarButton(icon));
        }
        assertFalse(ReelDeclutter.hideSidebarButton("APP_WHATSAPP"));
        assertFalse(ReelDeclutter.hideSidebarButton(null));
        assertEquals(ReelDeclutter.SIDEBAR_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: " + ReelDeclutter.WHATSAPP_BUTTON
                + ". Removed: " + ReelDeclutter.WHATSAPP_BUTTON + " 1. Kinds: " + ReelDeclutter.WHATSAPP_BUTTON + " 1",
                line(ReelDeclutter.SIDEBAR_ROUTE));
        Settings.HIDE_REEL_WHATSAPP_BUTTON.save(false);
        assertFalse("off did not restore the direct button", ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP));
        Settings.HIDE_REEL_SAVE_BUTTON.save(true);
        assertFalse("Save's switch hid WhatsApp", ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP));
    }

    @Test
    public void pausedOrNotReadyWhatsAppIsBuilt() {
        Settings.HIDE_REEL_WHATSAPP_BUTTON.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(why + " skipped WhatsApp", ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP)));
        assertTrue(ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP));
    }

    @Test
    public void anUnreadableWhatsAppSwitchBuildsTheButtonAndNamesItsOwnHook() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_WHATSAPP_BUTTON);
        try {
            assertFalse(ReelDeclutter.hideSidebarButton(SidebarIcon.APP_WHATSAPP));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_WHATSAPP_BUTTON);
        }
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.WHATSAPP_BUTTON + "' hook (it threw "
                + NullPointerException.class.getName() + ")"), HookStatus.missing(FamilyNames.REEL_DECLUTTER));
    }

    @Test
    public void likeCommentAndShareEachHaveTheirOwnSwitch() {
        BooleanSetting[] switches = {Settings.HIDE_REEL_LIKE_BUTTON, Settings.HIDE_REEL_COMMENT_BUTTON,
                Settings.HIDE_REEL_SHARE_BUTTON};
        SidebarIcon[] icons = {SidebarIcon.LIKE, SidebarIcon.COMMENT, SidebarIcon.SHARE};
        String[] buttons = {ReelDeclutter.LIKE_BUTTON, ReelDeclutter.COMMENT_BUTTON, ReelDeclutter.SHARE_BUTTON};
        for (int i = 0; i < switches.length; i++) {
            assertFalse(switches[i].key, switches[i].defaultValue);
            assertFalse(switches[i].key, ReelDeclutter.hideSidebarButton(icons[i]));
            switches[i].save(true);
            for (SidebarIcon icon : SidebarIcon.values()) {
                assertEquals(switches[i].key + " " + icon, icon == icons[i], ReelDeclutter.hideSidebarButton(icon));
            }
            assertFalse("a string was taken for the enum", ReelDeclutter.hideSidebarButton(icons[i].name()));
            assertTrue(line(ReelDeclutter.SIDEBAR_ROUTE), line(ReelDeclutter.SIDEBAR_ROUTE).contains("Last reason: " + buttons[i]));
            PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
            assertFalse("paused skipped " + icons[i], ReelDeclutter.hideSidebarButton(icons[i]));
            PauseForTests.resume();
            switches[i].save(false);
            assertFalse("off did not bring " + icons[i] + " back", ReelDeclutter.hideSidebarButton(icons[i]));
        }
    }

    @Test
    public void anUnreadableLikeSwitchBuildsTheButtonAndNamesItsOwnHook() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_LIKE_BUTTON);
        try {
            assertFalse(ReelDeclutter.hideSidebarButton(SidebarIcon.LIKE));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_LIKE_BUTTON);
        }
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.LIKE_BUTTON + "' hook (it threw "
                + NullPointerException.class.getName() + ")"), HookStatus.missing(FamilyNames.REEL_DECLUTTER));
    }

    @Test
    public void pausedOrNotReadySaveIsBuilt() {
        Settings.HIDE_REEL_SAVE_BUTTON.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(why + " skipped Save", ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK)));
        assertTrue("Save wasn't skipped again after the pause", ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
    }

    /** A Save switch that can't be read builds the button, and the report names the hook that threw. */
    @Test
    public void aSaveSwitchThatCantBeReadBuildsTheButton() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_SAVE_BUTTON);
        try {
            assertFalse(ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_SAVE_BUTTON);
        }
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.SAVE_BUTTON + "' hook (it threw "
                + NullPointerException.class.getName() + ")"), HookStatus.missing(FamilyNames.REEL_DECLUTTER));
    }

    /** The Save switch answers for nothing else, and the other switches don't skip Save. */
    @Test
    public void theSaveSwitchRunsOnlyItsOwnHook() {
        Settings.HIDE_REEL_COUNTS.save(true);
        assertFalse("the other switches skipped Save", ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
        Settings.HIDE_REEL_CHIPS.save(false);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        Settings.HIDE_REEL_COUNTS.save(false);
        Settings.HIDE_REEL_SAVE_BUTTON.save(true);
        assertFalse("the Save switch hid the Follow button", ReelDeclutter.hideFollowButton());
        assertFalse("the Save switch skipped a footer query", ReelDeclutter.skipHotComment());
        assertFalse("the Save switch hid the search pill", ReelDeclutter.hideSearchPill());
        assertEquals("the Save switch hid a count", "9", ReelDeclutter.countText("9"));
        assertTrue("the Save switch hid the speaker", ReelDeclutter.showsSoundButton(true));
        assertTrue(ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
    }

    /**
     * The speaker button (#190): the switch starts off, so Facebook's answer stands. On, a yes
     * becomes no and is counted, and a no stays no without being counted. Off again, the speaker is
     * back.
     */
    @Test
    public void theSpeakerGoesOnlyWhileItsSwitchIsOn() {
        assertFalse(Settings.HIDE_REEL_SOUND_BUTTON.defaultValue);
        assertTrue("the speaker went with its switch off", ReelDeclutter.showsSoundButton(true));
        assertFalse("a reel Facebook builds without the speaker got one", ReelDeclutter.showsSoundButton(false));

        Settings.HIDE_REEL_SOUND_BUTTON.save(true);
        assertFalse("the speaker was built", ReelDeclutter.showsSoundButton(true));
        assertFalse("a reel Facebook builds without the speaker got one", ReelDeclutter.showsSoundButton(false));
        assertFalse(ReelDeclutter.showsSoundButton(true));
        assertEquals(ReelDeclutter.SIDEBAR_ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + ReelDeclutter.SOUND_BUTTON
                + ". Removed: " + ReelDeclutter.SOUND_BUTTON + " 2. Kinds: " + ReelDeclutter.SOUND_BUTTON + " 2",
                line(ReelDeclutter.SIDEBAR_ROUTE));

        Settings.HIDE_REEL_SOUND_BUTTON.save(false);
        assertTrue("turning the switch off didn't bring the speaker back", ReelDeclutter.showsSoundButton(true));
    }

    /**
     * The footer's speaker (#190): what Facebook built comes back with the switch off and becomes
     * null with it on, counted like the controls' speaker. A footer that built nothing stays null
     * and isn't counted.
     */
    @Test
    public void theFootersSpeakerGoesOnlyWhileItsSwitchIsOn() {
        Object button = new Object();
        assertSame("the footer's speaker went with its switch off", button, ReelDeclutter.soundButton(button));
        assertNull(ReelDeclutter.soundButton(null));

        Settings.HIDE_REEL_SOUND_BUTTON.save(true);
        assertNull("the footer's speaker was built", ReelDeclutter.soundButton(button));
        assertNull("a footer without the speaker got one", ReelDeclutter.soundButton(null));
        assertEquals(ReelDeclutter.SIDEBAR_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: " + ReelDeclutter.SOUND_BUTTON
                + ". Removed: " + ReelDeclutter.SOUND_BUTTON + " 1. Kinds: " + ReelDeclutter.SOUND_BUTTON + " 1",
                line(ReelDeclutter.SIDEBAR_ROUTE));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame("the footer's speaker went while paused", button, ReelDeclutter.soundButton(button));
        PauseForTests.resume();
        Settings.HIDE_REEL_SOUND_BUTTON.save(false);
        assertSame("turning the switch off didn't bring the footer's speaker back", button, ReelDeclutter.soundButton(button));
    }

    /**
     * Move the speaker above the three dots (#331): off, each place keeps Facebook's answer. On, a reel
     * marked by its builder has its speaker built by the side column and dropped everywhere else, and
     * the builder is told to build it only while the side column asks.
     */
    @Test
    public void aMarkedReelsSpeakerGoesToItsSideColumnOnlyWhileTheSwitchIsOn() {
        Object reel = new Object();
        Object corner = new Object();
        Object button = new Object();
        assertFalse(Settings.REEL_SPEAKER_ABOVE_MENU.defaultValue);
        ReelDeclutter.sideColumnComing(reel);
        ReelDeclutter.rememberCorner(reel, corner);
        assertNull("off, the side column got a speaker", ReelDeclutter.cornerForSide(reel));
        assertSame("off, the corner lost its speaker", button, ReelDeclutter.speakerStays(button, reel));
        assertFalse("off, the side column's layout changed", ReelDeclutter.speakerLayout(false));
        assertTrue(ReelDeclutter.speakerLayout(true));
        assertTrue("off, the builder took the controls' speaker", ReelDeclutter.footerLeavesSpeaker(true));

        Settings.REEL_SPEAKER_ABOVE_MENU.save(true);
        assertTrue("the side column kept the layout with no buttons list", ReelDeclutter.speakerLayout(false));
        ReelDeclutter.sideColumnComing(reel);
        assertSame("a mark alone took the corner's speaker", button, ReelDeclutter.speakerStays(button, reel));
        ReelDeclutter.rememberCorner(reel, corner);
        assertNull("the corner kept a second speaker", ReelDeclutter.speakerStays(button, reel));
        assertNull("nothing built became something", ReelDeclutter.speakerStays(null, reel));

        assertTrue("the builder was told before the side column asked", ReelDeclutter.footerLeavesSpeaker(true));
        assertSame(corner, ReelDeclutter.cornerForSide(reel));
        assertFalse("the builder left the side column's speaker to the controls", ReelDeclutter.footerLeavesSpeaker(true));
        assertFalse(ReelDeclutter.footerLeavesSpeaker(false));
        assertSame(button, ReelDeclutter.sideSpeaker(button));
        assertTrue("the builder went on building it after the side column", ReelDeclutter.footerLeavesSpeaker(true));
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().toString().contains("Counted: " + ReelDeclutter.SPEAKER_MOVED + " 1"));

        Settings.REEL_SPEAKER_ABOVE_MENU.save(false);
        assertSame("turning the switch off didn't hand the speaker back", button, ReelDeclutter.speakerStays(button, reel));
        assertNull(ReelDeclutter.cornerForSide(reel));
    }

    /** A reel no side column can take the speaker for, or a builder that hasn't got to the corner, keeps Facebook's. */
    @Test
    public void aReelWithoutAMarkAndACornerKeepsItsSpeaker() {
        Object reel = new Object();
        Object corner = new Object();
        Object button = new Object();
        Settings.REEL_SPEAKER_ABOVE_MENU.save(true);
        ReelDeclutter.rememberCorner(reel, corner);
        assertSame("a corner alone took the speaker", button, ReelDeclutter.speakerStays(button, reel));
        assertNull("a corner alone put one in the side column", ReelDeclutter.cornerForSide(reel));
        ReelDeclutter.sideColumnComing(reel);
        assertNull("a mark alone put one in the side column", ReelDeclutter.cornerForSide(reel));

        // Built again, the reel waits for the new corner.
        ReelDeclutter.rememberCorner(reel, corner);
        assertNotNull(ReelDeclutter.cornerForSide(reel));
        ReelDeclutter.sideSpeaker(null);
        ReelDeclutter.sideColumnComing(reel);
        assertSame("the old corner stood in for the new one", button, ReelDeclutter.speakerStays(button, reel));

        assertNull(ReelDeclutter.cornerForSide(null));
        assertSame(button, ReelDeclutter.speakerStays(button, null));
        ReelDeclutter.sideColumnComing(null);
        ReelDeclutter.rememberCorner(null, corner);
    }

    @Test
    public void twoReelsDontShareAMoveAndTheOldestMarkGoesFirst() {
        Object button = new Object();
        Settings.REEL_SPEAKER_ABOVE_MENU.save(true);
        Object[] reels = new Object[ReelDeclutter.MOVED_SPEAKERS_KEPT + 1];
        Object[] corners = new Object[reels.length];
        for (int i = 0; i < reels.length; i++) {
            reels[i] = new Object();
            corners[i] = new Object();
            ReelDeclutter.sideColumnComing(reels[i]);
            ReelDeclutter.rememberCorner(reels[i], corners[i]);
        }
        Object last = reels[reels.length - 1];
        assertSame(corners[reels.length - 1], ReelDeclutter.cornerForSide(last));
        ReelDeclutter.sideSpeaker(null);
        assertSame(corners[1], ReelDeclutter.cornerForSide(reels[1]));
        ReelDeclutter.sideSpeaker(null);
        assertSame("a reel past the bound stayed marked", button, ReelDeclutter.speakerStays(button, reels[0]));
        assertNull(ReelDeclutter.cornerForSide(reels[0]));
        assertSame("a reel never seen lost its speaker", button, ReelDeclutter.speakerStays(button, new Object()));
    }

    /** Hide the speaker button still wins with the switch on: no place has a speaker. */
    @Test
    public void hidingTheSpeakerBeatsMovingIt() {
        Object reel = new Object();
        Object corner = new Object();
        Object button = new Object();
        Settings.REEL_SPEAKER_ABOVE_MENU.save(true);
        Settings.HIDE_REEL_SOUND_BUTTON.save(true);
        ReelDeclutter.sideColumnComing(reel);
        ReelDeclutter.rememberCorner(reel, corner);
        assertNull("the side column kept the speaker", ReelDeclutter.cornerForSide(reel));
        assertNull("the corner kept the speaker", ReelDeclutter.soundButton(ReelDeclutter.speakerStays(button, reel)));

        Settings.REEL_SPEAKER_ABOVE_MENU.save(false);
        assertSame(button, ReelDeclutter.speakerStays(button, reel));
        assertNull("the hide switch stopped reaching the corner's speaker", ReelDeclutter.soundButton(button));
    }

    @Test
    public void pausedOrNotReadyTheSpeakerStaysWhereFacebookPutIt() {
        Object reel = new Object();
        Object corner = new Object();
        Object button = new Object();
        Settings.REEL_SPEAKER_ABOVE_MENU.save(true);
        ReelDeclutter.sideColumnComing(reel);
        ReelDeclutter.rememberCorner(reel, corner);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertNull(why + " moved the speaker", ReelDeclutter.cornerForSide(reel));
            assertSame(why + " took the corner's speaker", button, ReelDeclutter.speakerStays(button, reel));
            assertFalse(why + " changed the layout", ReelDeclutter.speakerLayout(false));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertNull(ReelDeclutter.cornerForSide(reel));
            assertSame(button, ReelDeclutter.speakerStays(button, reel));
            assertFalse(ReelDeclutter.speakerLayout(false));
        });
    }

    /** A switch that can't be read leaves the speaker where Facebook put it, and the report names the hook. */
    @Test
    public void aMoveSwitchThatCantBeReadKeepsFacebooksAnswer() {
        Object reel = new Object();
        Object corner = new Object();
        Object button = new Object();
        Settings.REEL_SPEAKER_ABOVE_MENU.save(true);
        ReelDeclutter.sideColumnComing(reel);
        ReelDeclutter.rememberCorner(reel, corner);
        SettingReadsForTests.breakReads(Settings.REEL_SPEAKER_ABOVE_MENU);
        try {
            assertNull(ReelDeclutter.cornerForSide(reel));
            assertSame(button, ReelDeclutter.speakerStays(button, reel));
            assertFalse(ReelDeclutter.speakerLayout(false));
            assertTrue(ReelDeclutter.speakerLayout(true));
        } finally {
            SettingReadsForTests.mend(Settings.REEL_SPEAKER_ABOVE_MENU);
        }
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ReelDeclutter.SPEAKER_MOVED));
    }

    @Test
    public void pausedOrNotReadyTheSpeakerIsBuilt() {
        Settings.HIDE_REEL_SOUND_BUTTON.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertTrue(why + " hid the speaker", ReelDeclutter.showsSoundButton(true));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertTrue(ReelDeclutter.showsSoundButton(true)));
        assertFalse("the speaker wasn't hidden again after the pause", ReelDeclutter.showsSoundButton(true));
    }

    /** A speaker switch that can't be read keeps Facebook's answer, and the report names the hook that threw. */
    @Test
    public void aSpeakerSwitchThatCantBeReadKeepsFacebooksAnswer() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_SOUND_BUTTON);
        try {
            assertTrue(ReelDeclutter.showsSoundButton(true));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_SOUND_BUTTON);
        }
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.SOUND_BUTTON + "' hook (it threw "
                + NullPointerException.class.getName() + ")"), HookStatus.missing(FamilyNames.REEL_DECLUTTER));
    }

    /** The speaker switch answers for nothing else, and the other switches don't hide the speaker. */
    @Test
    public void theSpeakerSwitchRunsOnlyItsOwnHook() {
        Settings.HIDE_REEL_COUNTS.save(true);
        Settings.HIDE_REEL_SAVE_BUTTON.save(true);
        assertTrue("the other switches hid the speaker", ReelDeclutter.showsSoundButton(true));
        Settings.HIDE_REEL_CHIPS.save(false);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        Settings.HIDE_REEL_COUNTS.save(false);
        Settings.HIDE_REEL_SAVE_BUTTON.save(false);
        Settings.HIDE_REEL_SOUND_BUTTON.save(true);
        assertFalse("the speaker switch hid the Follow button", ReelDeclutter.hideFollowButton());
        assertFalse("the speaker switch skipped a footer query", ReelDeclutter.skipHotComment());
        assertFalse("the speaker switch hid the search pill", ReelDeclutter.hideSearchPill());
        assertEquals("the speaker switch hid a count", "9", ReelDeclutter.countText("9"));
        assertFalse("the speaker switch skipped Save", ReelDeclutter.hideSidebarButton(SidebarIcon.BOOKMARK));
        assertFalse(ReelDeclutter.showsSoundButton(true));
    }

    /** The counts switch answers for nothing else, and the other switches don't answer for it. */
    @Test
    public void theCountsSwitchRunsOnlyItsOwnHook() {
        assertEquals("the other switches hid a count", "9", ReelDeclutter.countText("9"));
        Settings.HIDE_REEL_CHIPS.save(false);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        Settings.HIDE_REEL_COUNTS.save(true);
        assertFalse("the counts switch hid the Follow button", ReelDeclutter.hideFollowButton());
        assertFalse("the counts switch skipped a footer query", ReelDeclutter.skipHotComment());
        assertFalse("the counts switch hid the search pill", ReelDeclutter.hideSearchPill());
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText("9"));
    }

    @Test
    public void bothFooterQueriesAreSkippedWhileTheirSwitchIsOn() {
        assertTrue(ReelDeclutter.skipHotComment());
        assertTrue(ReelDeclutter.skipSocialBubbles());
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        assertFalse(ReelDeclutter.skipHotComment());
        assertFalse(ReelDeclutter.skipSocialBubbles());
        assertEquals(ReelDeclutter.FOOTER_ROUTE + ": 4 lists, 4 items, 2 removed. Last reason: "
                + ReelDeclutter.SOCIAL_BUBBLES + ". Removed: " + ReelDeclutter.SOCIAL_BUBBLES + " 1, "
                + ReelDeclutter.HOT_COMMENT + " 1. Kinds: " + ReelDeclutter.SOCIAL_BUBBLES + " 2, "
                + ReelDeclutter.HOT_COMMENT + " 2", line(ReelDeclutter.FOOTER_ROUTE));
    }

    /** One switch never answers for another. */
    @Test
    public void eachSwitchRunsOnlyItsOwnHooks() {
        Settings.HIDE_REEL_CHIPS.save(false);
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideFollowingButton());
        assertTrue(ReelDeclutter.skipHotComment());
        Settings.HIDE_REEL_CHIPS.save(true);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        assertTrue(ReelDeclutter.skipSocialBubbles());
        assertEquals(1, ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG))).length);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideFollowingButton());
        assertEquals(1, ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG))).length);
    }

    @Test
    public void pausedEveryHookTakesFacebooksPath() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertNull(why + " filtered the chips", ReelDeclutter.filterChips(Arrays.asList(chip(REMIX))));
            assertFalse(why + " hid the Follow button", ReelDeclutter.hideFollowButton());
            assertFalse(why + " hid the Following button", ReelDeclutter.hideFollowingButton());
            assertFalse(why + " skipped the hot comment", ReelDeclutter.skipHotComment());
            assertFalse(why + " skipped the bubbles", ReelDeclutter.skipSocialBubbles());
        }
        PauseForTests.resume();
        assertTrue("the Follow hook didn't come back after the pause", ReelDeclutter.hideFollowButton());
        assertTrue("the Following hook didn't come back after the pause", ReelDeclutter.hideFollowingButton());
    }

    @Test
    public void untilTheSettingsAreReadyEveryHookTakesFacebooksPath() {
        SettingsContextRule.withoutContext(() -> {
            assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(REMIX))));
            assertFalse(ReelDeclutter.hideFollowButton());
            assertFalse(ReelDeclutter.hideFollowingButton());
            assertFalse(ReelDeclutter.skipHotComment());
            assertFalse(ReelDeclutter.skipSocialBubbles());
        });
        assertEquals(1, ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG))).length);
    }

    private static int occurrences(String text, String of) {
        int count = 0;
        for (int at = text.indexOf(of); at >= 0; at = text.indexOf(of, at + 1)) count++;
        return count;
    }

    /**
     * With debug logging on, each kind of change is logged once, which tells a report what the
     * switches did on this phone without a line for every reel. Off, nothing is remembered.
     */
    @Test
    public void eachKindOfChangeIsLoggedOnce() {
        LogBufferManager.clearLogBuffer();
        try {
            ReelDeclutter.hideFollowButton();
            ReelDeclutter.hideFollowingButton();
            BaseSettings.DEBUG.save(true);
            for (int i = 0; i < 3; i++) {
                ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(STARS)));
                ReelDeclutter.hideFollowButton();
                ReelDeclutter.hideFollowingButton();
                ReelDeclutter.skipHotComment();
                ReelDeclutter.skipSocialBubbles();
            }
            String log = LogBufferManager.buildExportText();
            assertEquals(log, 1, occurrences(log, "Reel chips: hid " + REMIX));
            assertEquals(log, 1, occurrences(log, "Reel chips: hid " + STARS));
            assertEquals(log, 1, occurrences(log, "Reel Follow button: hid the Follow button"));
            assertEquals(log, 1, occurrences(log, "Reel Follow button: hid the Following button"));
            assertEquals(log, 1, occurrences(log, "Reel footer: skipped the " + ReelDeclutter.HOT_COMMENT + " query"));
            assertEquals(log, 1, occurrences(log, "Reel footer: skipped the " + ReelDeclutter.SOCIAL_BUBBLES + " query"));
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }
}
