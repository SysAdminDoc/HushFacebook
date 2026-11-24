/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.facebook.media.PostMenuSpeed;
import app.morphe.extension.facebook.media.ReelSpeedForTests;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.PatchFamilyForTests;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.FailingStore;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Post menu items and the first page's Playback speed: the sheet's holder loses the rows of every
 * picked kind, in its lists and its rows of their own, the kinds a menu shows are written down, and
 * Keep the reel speed's row moves from More options to the end of the first page when its switch is
 * on. Off, paused, with nothing picked or with a holder of another shape, the sheet stays Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostMenuItemsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for 587's LX/0Hq4, the enum that tags each row with a kind. */
    enum Kind { SEE_MORE, SEE_LESS, TUNE_YOUR_ALGORITHM, SAVE, REPORT, CAST, WAIST }

    /** Stands in for 587's LX/0Hy0, a row's metadata, whose A01 is the kind. */
    static final class Meta {
        Object A00;
        Kind A01;

        Meta(Kind kind) {
            A01 = kind;
        }
    }

    /** Stands in for 587's LX/0QMn, the MenuItem a row is, whose A07 holds its metadata. */
    static final class Row {
        final String title;
        int A00;
        Meta A07;

        Row(String title, Kind kind) {
            this.title = title;
            A07 = kind == null ? null : new Meta(kind);
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Stands in for 587's LX/0Hwt, the sorted sheet, field for field. */
    static final class Sheet {
        public Row A00;
        public Row A01;
        public Row A02;
        public Row A03;
        public boolean A04;
        public boolean A05;
        public final List<Row> A06 = new ArrayList<>();
        public final List<Row> A07 = new ArrayList<>();
        public final List<Row> A08 = new ArrayList<>();
    }

    /** A holder with no first page list, as a build that moved it would hand over. */
    static final class Elsewhere {
        public Row A00;
        public final List<Row> A07 = new ArrayList<>();
    }

    private static final String META = Meta.class.getName();

    private final Row interested = new Row("Interested", Kind.SEE_MORE);
    private final Row notInterested = new Row("Not interested", Kind.SEE_LESS);
    private final Row manage = new Row("Manage your Feed", Kind.TUNE_YOUR_ALGORITHM);
    private final Row save = new Row("Save", Kind.SAVE);
    private final Row report = new Row("Report", Kind.REPORT);
    private final Row cast = new Row("Cast", Kind.CAST);
    private final Row why = new Row("Why am I seeing this post?", Kind.WAIST);
    private final Row speed = new Row("Playback speed", null);

    private Sheet sheet() {
        Sheet sheet = new Sheet();
        sheet.A00 = notInterested;
        sheet.A01 = interested;
        sheet.A02 = manage;
        sheet.A08.addAll(Arrays.asList(save, report));
        sheet.A07.addAll(Arrays.asList(cast, why, speed));
        return sheet;
    }

    private static void arrange(Object holder) {
        PostMenuItems.arrange(holder, META, "A01", "A08");
    }

    @After
    public void restore() {
        PatchFamilyForTests.reset();
        PauseForTests.resume();
        Settings.HIDDEN_POST_MENU_ITEMS.resetToDefault();
        Settings.SEEN_POST_MENU_ITEMS.resetToDefault();
        Settings.POST_MENU_SPEED.resetToDefault();
        Settings.POST_MENU_SPEED_ON_TOP.resetToDefault();
        PostMenuItems.forgetForTests();
        ReelSpeedForTests.forgetPostMenuRows();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private static String statusLine(String family) {
        for (String line : HookStatus.report()) {
            if (line.startsWith(family + ":")) return line;
        }
        return null;
    }

    @Test
    public void nothingPickedTheSheetIsFacebooksAndItsKindsAreWrittenDown() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        Sheet sheet = sheet();
        arrange(sheet);
        assertSame(notInterested, sheet.A00);
        assertSame(interested, sheet.A01);
        assertEquals(Arrays.asList(save, report), sheet.A08);
        assertEquals(Arrays.asList(cast, why, speed), sheet.A07);
        // Lists first, then the rows of their own; the order within each is the class's field order.
        List<String> seen = new ArrayList<>(PostMenuItems.parse(Settings.SEEN_POST_MENU_ITEMS.savedValue()));
        assertEquals(seen.toString(), 7, seen.size());
        assertEquals(new HashSet<>(Arrays.asList("SAVE", "REPORT", "CAST", "WAIST")),
                new HashSet<>(seen.subList(0, 4)));
        assertEquals(new HashSet<>(Arrays.asList("SEE_LESS", "SEE_MORE", "TUNE_YOUR_ALGORITHM")),
                new HashSet<>(seen.subList(4, 7)));
    }

    @Test
    public void pickedKindsLeaveEveryPartAndTheRestKeepTheirOrder() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PostMenuItems.hide(Arrays.asList("SEE_MORE", "SEE_LESS", "CAST", "REPORT"));
        Sheet sheet = sheet();
        arrange(sheet);
        assertNull("Not interested stayed", sheet.A00);
        assertNull("Interested stayed", sheet.A01);
        assertSame("Manage your Feed went too", manage, sheet.A02);
        assertEquals(Collections.singletonList(save), sheet.A08);
        assertEquals("the row with no kind stays", Arrays.asList(why, speed), sheet.A07);
        String line = statusLine(FamilyNames.POST_MENU_ITEMS);
        assertNotNull(String.join("\n", HookStatus.report()), line);
        assertTrue(line, line.contains(PostMenuItems.HIDDEN + " 4"));
    }

    @Test
    public void outOfTheBuildOrPausedTheSheetIsFacebooks() {
        PostMenuItems.hide(Arrays.asList("SEE_MORE", "CAST"));
        PatchFamilyForTests.carry();
        Sheet sheet = sheet();
        arrange(sheet);
        assertSame(interested, sheet.A01);
        assertEquals(3, sheet.A07.size());
        assertEquals("a build without the patch wrote kinds down", "", Settings.SEEN_POST_MENU_ITEMS.savedValue());

        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        arrange(sheet);
        assertSame(interested, sheet.A01);
        assertEquals(3, sheet.A07.size());
        PauseForTests.resume();
        arrange(sheet);
        assertNull("the control: running, Interested goes", sheet.A01);
        assertEquals(2, sheet.A07.size());
    }

    @Test
    public void missingArgumentsOrAnotherShapeLeaveTheSheetAlone() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PostMenuItems.hide(Collections.singletonList("CAST"));
        Sheet sheet = sheet();
        PostMenuItems.arrange(null, META, "A01", "A08");
        PostMenuItems.arrange(sheet, null, "A01", "A08");
        PostMenuItems.arrange(sheet, META, null, "A08");
        PostMenuItems.arrange(sheet, META, "A01", null);
        assertEquals(3, sheet.A07.size());

        PostMenuItems.arrange(sheet, "app.morphe.NoSuchMeta", "A01", "A08");
        PostMenuItems.arrange(sheet, META, "A09", "A08");
        assertEquals(3, sheet.A07.size());
        assertFalse(HookStatus.missing(FamilyNames.POST_MENU_ITEMS).isEmpty());

        Elsewhere other = new Elsewhere();
        other.A07.add(cast);
        arrange(other);
        assertEquals("a holder with no first page list was changed", Collections.singletonList(cast), other.A07);
    }

    @Test
    public void playbackSpeedMovesToTheEndOfTheFirstPageWithItsSwitchOn() {
        PatchFamilyForTests.carry(PatchFamily.KEEP_REEL_SPEED);
        Settings.POST_MENU_SPEED.save(true);
        PostMenuSpeed.remember(speed);
        assertTrue("the switch starts on", Settings.POST_MENU_SPEED_ON_TOP.get());
        Sheet sheet = sheet();
        arrange(sheet);
        assertEquals(Arrays.asList(save, report, speed), sheet.A08);
        assertEquals(Arrays.asList(cast, why), sheet.A07);
        assertSame("Post menu items is out of the build, so nothing was hidden", interested, sheet.A01);
        assertEquals("", Settings.SEEN_POST_MENU_ITEMS.savedValue());
        String line = statusLine(FamilyNames.KEEP_REEL_SPEED);
        assertNotNull(String.join("\n", HookStatus.report()), line);
        assertTrue(line, line.contains(PostMenuItems.SPEED_MOVED + " 1"));

        arrange(sheet);
        assertEquals("a second pass moved it again", Arrays.asList(save, report, speed), sheet.A08);
    }

    @Test
    public void offTheSwitchesOrForAnotherRowPlaybackSpeedStaysUnderMoreOptions() {
        Settings.POST_MENU_SPEED.save(true);
        Settings.POST_MENU_SPEED_ON_TOP.save(false);
        PostMenuSpeed.remember(speed);
        Sheet sheet = sheet();
        arrange(sheet);
        assertEquals(Arrays.asList(cast, why, speed), sheet.A07);

        Settings.POST_MENU_SPEED_ON_TOP.save(true);
        Settings.POST_MENU_SPEED.save(false);
        arrange(sheet);
        assertEquals("Playback speed in the post menu is off", Arrays.asList(cast, why, speed), sheet.A07);

        Settings.POST_MENU_SPEED.save(true);
        ReelSpeedForTests.forgetPostMenuRows();
        PostMenuSpeed.remember(new Row("Playback speed", null));
        arrange(sheet);
        assertEquals("a row with the same title isn't the one added", Arrays.asList(cast, why, speed), sheet.A07);

        PostMenuSpeed.remember(speed);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        arrange(sheet);
        assertEquals(Arrays.asList(cast, why, speed), sheet.A07);
        PauseForTests.resume();
        arrange(sheet);
        assertEquals("the control: running, it moves", Arrays.asList(cast, why), sheet.A07);
    }

    @Test
    public void bothRunTogether() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS, PatchFamily.KEEP_REEL_SPEED);
        PostMenuItems.hide(Arrays.asList("SAVE", "WAIST"));
        Settings.POST_MENU_SPEED.save(true);
        PostMenuSpeed.remember(speed);
        Sheet sheet = sheet();
        arrange(sheet);
        assertEquals(Arrays.asList(report, speed), sheet.A08);
        assertEquals(Collections.singletonList(cast), sheet.A07);
    }

    @Test
    public void theWrittenDownKindsStayInOrderOnceEachAndStopAtTheLimit() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PostMenuItems.remember(Arrays.asList("CAST", "SAVE", "CAST"));
        PostMenuItems.remember(Arrays.asList("SAVE", "REPORT"));
        assertEquals("CAST,SAVE,REPORT", Settings.SEEN_POST_MENU_ITEMS.savedValue());

        List<String> full = new ArrayList<>();
        for (int i = 0; i < PostMenuItems.SEEN_LIMIT; i++) full.add("KIND_" + i);
        Settings.SEEN_POST_MENU_ITEMS.save(String.join(",", full));
        PostMenuItems.forgetForTests();
        arrange(sheet());
        assertEquals(String.join(",", full), Settings.SEEN_POST_MENU_ITEMS.savedValue());
    }

    @Test
    public void theListShowsSeenKindsThenCommonOnesThenEarlierPicksEachOnce() {
        Settings.SEEN_POST_MENU_ITEMS.save("ASK_META_AI,CAST");
        PostMenuItems.hide(Arrays.asList("TRANSLATE_POST", "CAST"));
        List<String> choices = PostMenuItems.choices();
        assertEquals(Arrays.asList("ASK_META_AI", "CAST"), choices.subList(0, 2));
        assertEquals("TRANSLATE_POST", choices.get(choices.size() - 1));
        assertEquals(2 + PostMenuItems.COMMON.size(), choices.size());
    }

    @Test
    public void savedListsKeepOnlyKinds() {
        assertEquals(Arrays.asList("CAST", "SAVE"),
                new ArrayList<>(PostMenuItems.parse(" CAST, ,lower,SAVE,CAST,A-B")));
        PostMenuItems.hide(Arrays.asList("nope", "CAST", "CAST"));
        assertEquals("CAST", Settings.HIDDEN_POST_MENU_ITEMS.savedValue());
        assertEquals(1, PostMenuItems.count("CAST,,x"));
        assertTrue(PostMenuItems.isClean("CAST,SAVE"));
        assertFalse(PostMenuItems.isClean("CAST, SAVE"));
        assertFalse(PostMenuItems.isClean(null));
    }

    /** A pick past the list's room saves nothing rather than dropping a kind in silence. */
    @Test
    public void aPickPastTheListsRoomSavesNothing() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 128; i++) many.add(String.format(Locale.ROOT, "UNKNOWN_%07d", i));
        String saved = String.join(",", many);
        assertTrue(PostMenuItems.isClean(saved));
        Settings.HIDDEN_POST_MENU_ITEMS.save(saved);
        List<String> withCast = new ArrayList<>(many);
        withCast.add(0, "CAST");
        assertSame(PostMenuItems.Hidden.NO_ROOM, PostMenuItems.hide(withCast));
        assertEquals(saved, Settings.HIDDEN_POST_MENU_ITEMS.savedValue());

        many.remove(many.size() - 1);
        many.add(0, "CAST");
        assertSame(PostMenuItems.Hidden.SAVED, PostMenuItems.hide(many));
        assertEquals(new ArrayList<>(many), new ArrayList<>(PostMenuItems.hidden()));
    }

    @Test
    public void aPickThePhoneDoesntKeepSaysSoAndSavesOnRetry() {
        assertSame(PostMenuItems.Hidden.SAVED, PostMenuItems.hide(Collections.singletonList("CAST")));
        List<String> picked = Arrays.asList("CAST", "SEE_MORE");
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_THROWS)) {
            assertSame(PostMenuItems.Hidden.NOT_STORED, PostMenuItems.hide(picked));
        }
        assertEquals("CAST", Settings.HIDDEN_POST_MENU_ITEMS.get());
        assertEquals("CAST", Settings.HIDDEN_POST_MENU_ITEMS.savedValue());
        assertSame(PostMenuItems.Hidden.SAVED, PostMenuItems.hide(picked));
        assertEquals(picked, new ArrayList<>(PostMenuItems.hidden()));
    }

    @Test
    public void labelsUseFacebooksWordsOrTheKindInWords() {
        assertEquals("Interested", PostMenuItems.label("SEE_MORE"));
        assertEquals("Not interested", PostMenuItems.label("SEE_LESS"));
        assertEquals("Why am I seeing this post?", PostMenuItems.label("WAIST"));
        assertEquals("Manage your Feed / Your algorithm", PostMenuItems.label("TUNE_YOUR_ALGORITHM"));
        assertEquals("Add to Favorites", PostMenuItems.label("FAVORITE"));
        assertEquals("Ask meta ai", PostMenuItems.label("ASK_META_AI"));
        assertEquals("Clear mode", PostMenuItems.label("CLEAR_MODE"));
        assertEquals("Scroll to next reel automatically", PostMenuItems.label("AUTO_ADVANCE"));
        // A reel's More sheet shows these rows by other words than their ids (588, #475).
        assertEquals("Quality settings", PostMenuItems.label(PostMenuItems.reelKind("fds_control_type_best_guess_post_control")));
        assertEquals("Request community note", PostMenuItems.label(PostMenuItems.reelKind("fds_control_type_other")));
        assertEquals("Something went wrong", PostMenuItems.label(PostMenuItems.reelKind("fds_control_type_bug")));
        // #475: a kind that kept an fb_control_type_ wrapping reads without it, and Skip silent parts by its title.
        assertEquals("View edit privacy", PostMenuItems.label(PostMenuItems.reelKind("fb_control_type_view_edit_privacy")));
        assertEquals("Skip silent parts", PostMenuItems.label(PostMenuItems.reelKind("hushfacebook_skip_silence")));
    }

    /**
     * A reel's More sheet draws Interested and Not interested in a header of their own: both picked,
     * its render draws nothing, and one picked leaves the other of its two rows.
     */
    @Test
    public void theReelSheetHeaderLosesThePickedPreferenceRows() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        List<String> pair = Arrays.asList("interested row", "not interested row");
        assertFalse("nothing picked draws the header", PostMenuItems.reelPreferencesHidden());
        assertSame(pair, PostMenuItems.reelPreferenceRows(pair));

        PostMenuItems.hide(Collections.singletonList("SEE_MORE"));
        assertFalse(PostMenuItems.reelPreferencesHidden());
        assertEquals(Collections.singletonList("not interested row"), PostMenuItems.reelPreferenceRows(pair));
        PostMenuItems.hide(Collections.singletonList("SEE_LESS"));
        assertEquals(Collections.singletonList("interested row"), PostMenuItems.reelPreferenceRows(pair));

        PostMenuItems.hide(Arrays.asList("SEE_MORE", "SEE_LESS"));
        assertTrue("both picked draws nothing", PostMenuItems.reelPreferencesHidden());
        assertSame("the render already drew nothing", pair, PostMenuItems.reelPreferenceRows(pair));
        assertTrue(statusLine(FamilyNames.POST_MENU_ITEMS), statusLine(FamilyNames.POST_MENU_ITEMS).contains(PostMenuItems.REEL_HIDDEN));

        List<String> three = Arrays.asList("a", "b", "c");
        PostMenuItems.hide(Collections.singletonList("SEE_MORE"));
        assertSame("another shape is left alone", three, PostMenuItems.reelPreferenceRows(three));
        assertNull(PostMenuItems.reelPreferenceRows(null));
    }

    /**
     * Some reel More sheets build their top card (Interested, Not interested, Your algorithm) row by
     * row with the same builder as the sections: a picked kind gets no row, the rest and our own do.
     */
    @Test
    public void aPickedKindGetsNoRowFromTheReelRowBuilder() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        ReelModel interested = new ReelModel("fds_control_type_preference_signal_positive");
        ReelModel notInterested = new ReelModel("fds_control_type_preference_signal_negative");
        ReelModel algorithm = new ReelModel("fds_control_type_tune_your_algorithm");
        assertFalse("nothing picked builds every row", PostMenuItems.reelRowHidden(interested, REEL_MODEL, "A04"));

        PostMenuItems.hide(Arrays.asList("SEE_MORE", "TUNE_YOUR_ALGORITHM"));
        assertTrue(PostMenuItems.reelRowHidden(interested, REEL_MODEL, "A04"));
        assertTrue("Your algorithm is Manage your Feed's kind", PostMenuItems.reelRowHidden(algorithm, REEL_MODEL, "A04"));
        assertFalse(PostMenuItems.reelRowHidden(notInterested, REEL_MODEL, "A04"));
        assertFalse("our own rows have switches of their own",
                PostMenuItems.reelRowHidden(new ReelModel("hushfacebook_download_reel"), REEL_MODEL, "A04"));
        assertFalse(PostMenuItems.reelRowHidden(null, REEL_MODEL, "A04"));
        assertFalse("a model it can't load builds its row", PostMenuItems.reelRowHidden(interested, "X.Missing", "A04"));
        assertTrue(statusLine(FamilyNames.POST_MENU_ITEMS), statusLine(FamilyNames.POST_MENU_ITEMS).contains(PostMenuItems.REEL_HIDDEN));

        PatchFamilyForTests.carry();
        assertFalse("out of the build Facebook builds it", PostMenuItems.reelRowHidden(interested, REEL_MODEL, "A04"));
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused Facebook builds it", PostMenuItems.reelRowHidden(interested, REEL_MODEL, "A04"));
        PauseForTests.resume();
        assertTrue("the control: running, it goes", PostMenuItems.reelRowHidden(interested, REEL_MODEL, "A04"));
    }

    @Test
    public void outOfTheBuildOrPausedTheReelSheetHeaderIsFacebooks() {
        List<String> pair = Arrays.asList("interested row", "not interested row");
        PostMenuItems.hide(Arrays.asList("SEE_MORE", "SEE_LESS"));
        PatchFamilyForTests.carry();
        assertFalse(PostMenuItems.reelPreferencesHidden());
        PostMenuItems.hide(Collections.singletonList("SEE_MORE"));
        assertSame(pair, PostMenuItems.reelPreferenceRows(pair));

        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(pair, PostMenuItems.reelPreferenceRows(pair));
        PauseForTests.resume();
        assertEquals("the control: running, Interested goes",
                Collections.singletonList("not interested row"), PostMenuItems.reelPreferenceRows(pair));
    }

    /** Stands in for 587's LX/0Hyj, a reel sheet row's model, whose A04 answers the row's id. */
    static class ReelModel {
        private final String id;

        ReelModel(String id) {
            this.id = id;
        }

        final String A04() {
            return id;
        }
    }

    /** Stands in for 587's LX/0HKE, a reel sheet row that is a model itself. */
    static final class ReelRow extends ReelModel {
        private final String title;

        ReelRow(String title, String id) {
            super(id);
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Stands in for 587's LX/0Htm, a reel sheet row holding its model in A00. */
    static final class HeldRow {
        final ReelModel A00;

        HeldRow(String id) {
            A00 = new ReelModel(id);
        }
    }

    private static final String REEL_MODEL = ReelModel.class.getName();

    private final ReelRow reelInterested = new ReelRow("Interested", "fds_control_type_preference_signal_positive");
    private final ReelRow reelSpeed = new ReelRow("Playback speed", "fds_control_playback_speed");
    private final ReelRow captions = new ReelRow("Captions", "fds_control_type_captions_and_translations");
    private final ReelRow ownDownload = new ReelRow("Download", "hushfacebook_download_reel");
    private final ReelRow clearMode = new ReelRow("Clear mode", "fds_control_clear_mode");
    private final ReelRow transcript = new ReelRow("Transcript", "fds_transcript_control");
    private final HeldRow saveReel = new HeldRow("fds_control_type_save");

    private List<Object> reelSection() {
        return new ArrayList<>(Arrays.asList(reelInterested, reelSpeed, captions, ownDownload, clearMode, transcript,
                saveReel));
    }

    @Test
    public void reelIdsReadAsTheListsKinds() {
        assertEquals("SEE_MORE", PostMenuItems.reelKind("fds_control_type_preference_signal_positive"));
        assertEquals("SEE_LESS", PostMenuItems.reelKind("fds_control_type_preference_signal_negative"));
        assertEquals("SAVE", PostMenuItems.reelKind("fds_control_type_save"));
        assertEquals("COPY_LINK", PostMenuItems.reelKind("fds_control_type_copy_link"));
        assertEquals("TUNE_YOUR_ALGORITHM", PostMenuItems.reelKind("fds_control_type_tune_your_algorithm"));
        assertEquals("PLAYBACK_SPEED", PostMenuItems.reelKind("fds_control_playback_speed"));
        assertEquals("CLEAR_MODE", PostMenuItems.reelKind("fds_control_clear_mode"));
        assertEquals("TRANSCRIPT", PostMenuItems.reelKind("fds_transcript_control"));
        assertEquals("AUDIO_AND_LANGUAGE", PostMenuItems.reelKind("fds_audio_and_language_control"));
        assertEquals("LEGAL_REPORT", PostMenuItems.reelKind("legal_report"));
        assertNull("Hushfacebook's own row", PostMenuItems.reelKind("hushfacebook_download_reel"));
        assertEquals("Skip silent parts has no switch of its own (#475)", "SKIP_SILENT_PARTS",
                PostMenuItems.reelKind("hushfacebook_skip_silence"));
        assertNull(PostMenuItems.reelKind("fds_control_"));
        assertNull(PostMenuItems.reelKind("9 lives"));
        assertNull(PostMenuItems.reelKind(null));
    }

    @Test
    public void nothingPickedAReelSectionIsFacebooksAndItsKindsAreWrittenDown() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        List<Object> rows = reelSection();
        assertSame(rows, PostMenuItems.reelRows(rows, REEL_MODEL, "A04"));
        assertEquals(7, rows.size());
        assertEquals(Arrays.asList("SEE_MORE", "PLAYBACK_SPEED", "CAPTIONS_AND_TRANSLATIONS", "CLEAR_MODE", "TRANSCRIPT",
                "SAVE"), new ArrayList<>(PostMenuItems.parse(Settings.SEEN_POST_MENU_ITEMS.savedValue())));
        assertTrue("the reel rows are offered in the list", PostMenuItems.choices().containsAll(
                Arrays.asList("PLAYBACK_SPEED", "CLEAR_MODE", "TRANSCRIPT")));
    }

    @Test
    public void pickedKindsLeaveAReelSectionAndHushfacebooksRowsStay() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PostMenuItems.hide(Arrays.asList("SEE_MORE", "CLEAR_MODE", "SAVE", "CAST"));
        List<Object> rows = Collections.unmodifiableList(reelSection());
        List<?> shown = PostMenuItems.reelRows(rows, REEL_MODEL, "A04");
        assertEquals(Arrays.asList(reelSpeed, captions, ownDownload, transcript), shown);
        assertEquals("Facebook's own list is left alone", 7, rows.size());
        String line = statusLine(FamilyNames.POST_MENU_ITEMS);
        assertNotNull(String.join("\n", HookStatus.report()), line);
        assertTrue(line, line.contains(PostMenuItems.REEL_HIDDEN + " 3"));
    }

    /** #475: Skip silent parts' row is offered once a sheet showed it, and leaves the sheet when picked. */
    @Test
    public void skipSilentPartsIsOfferedAndLeavesTheSheetWhenPicked() {
        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        ReelRow skip = new ReelRow("Skip silent parts: on", "hushfacebook_skip_silence");
        List<Object> rows = Arrays.asList(reelSpeed, skip, ownDownload, captions);
        assertSame("nothing picked keeps it", rows, PostMenuItems.reelRows(rows, REEL_MODEL, "A04"));
        assertTrue(PostMenuItems.choices().contains("SKIP_SILENT_PARTS"));

        PostMenuItems.hide(Collections.singletonList("SKIP_SILENT_PARTS"));
        assertEquals(Arrays.asList(reelSpeed, ownDownload, captions), PostMenuItems.reelRows(rows, REEL_MODEL, "A04"));
        assertTrue(PostMenuItems.reelRowHidden(skip, REEL_MODEL, "A04"));
        assertFalse("the Download row keeps its own switch", PostMenuItems.reelRowHidden(ownDownload, REEL_MODEL, "A04"));
    }

    @Test
    public void outOfTheBuildPausedOrUnreadableAReelSectionIsFacebooks() {
        PostMenuItems.hide(Arrays.asList("CLEAR_MODE", "SAVE"));
        PatchFamilyForTests.carry();
        List<Object> rows = reelSection();
        assertSame(rows, PostMenuItems.reelRows(rows, REEL_MODEL, "A04"));
        assertEquals("a build without the patch wrote kinds down", "", Settings.SEEN_POST_MENU_ITEMS.savedValue());

        PatchFamilyForTests.carry(PatchFamily.POST_MENU_ITEMS);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(7, PostMenuItems.reelRows(rows, REEL_MODEL, "A04").size());
        PauseForTests.resume();
        assertSame("a model class this build doesn't have", rows,
                PostMenuItems.reelRows(rows, "X.Missing", "A04"));
        assertSame("an id method the model doesn't have", rows,
                PostMenuItems.reelRows(rows, REEL_MODEL, "A99"));
        List<Object> odd = new ArrayList<>(Arrays.asList("not a row", null, clearMode));
        assertEquals("a row of no known shape stays", Arrays.asList("not a row", null),
                PostMenuItems.reelRows(odd, REEL_MODEL, "A04"));
        assertEquals("the control: running, Clear mode and Save go", 5,
                PostMenuItems.reelRows(rows, REEL_MODEL, "A04").size());
    }
}
