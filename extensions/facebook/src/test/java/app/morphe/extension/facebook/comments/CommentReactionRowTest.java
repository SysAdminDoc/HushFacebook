/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import app.morphe.extension.facebook.settings.SettingsBackup;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Show every reaction on comments (#241): with the switch on, Facebook's pick of the newer row
 * under a comment, the one with only the top reaction beside the count, is answered no and
 * counted, so the row keeps Like, Reply and every reaction. The check the feed's inline comments
 * and the comment list share is answered no the same way, and the comment list's row layout is
 * answered 0, and the cap that keeps a single icon beside a comment's reaction count is answered no,
 * each counted under its own name so the report tells them apart. Off, paused, before the settings
 * are ready, or when Facebook already picked the full row, its answer goes back unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentReactionRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SHOW_EVERY_COMMENT_REACTION.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void startsOffAndLeavesFacebooksPick() {
        assertFalse(Settings.SHOW_EVERY_COMMENT_REACTION.get());
        assertTrue(CommentSheetOptions.topReactionOnly(true));
        String line = statusLine();
        assertNotNull(line);
        assertFalse("counted with the switch off: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_KEPT));
    }

    @Test
    public void onAnswersNoAndCounts() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertFalse(CommentSheetOptions.topReactionOnly(true));
        assertTrue(statusLine().contains(CommentSheetOptions.EVERY_REACTION_KEPT));
    }

    @Test
    public void theFullRowStaysAndIsNotCounted() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertFalse(CommentSheetOptions.topReactionOnly(false));
        assertFalse(statusLine().contains(CommentSheetOptions.EVERY_REACTION_KEPT));
    }

    @Test
    public void pausedLeavesThePickToFacebook() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(CommentSheetOptions.topReactionOnly(true));
    }

    @Test
    public void beforeTheSettingsAreReadyThePickStays() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        SettingsContextRule.withoutContext(() -> assertTrue(CommentSheetOptions.topReactionOnly(true)));
    }

    @Test
    public void theSharedCheckStartsOffAndLeavesFacebooksPick() {
        assertTrue(CommentSheetOptions.topReactionCheck(true));
        String line = statusLine();
        assertNotNull(line);
        assertFalse("counted with the switch off: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_CHECKED));
    }

    @Test
    public void theSharedCheckOnAnswersNoAndCountsUnderItsOwnName() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertFalse(CommentSheetOptions.topReactionCheck(true));
        String line = statusLine();
        assertTrue(line, line.contains(CommentSheetOptions.EVERY_REACTION_CHECKED));
        assertFalse("counted as the plugin's: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_KEPT));
        assertFalse("counted as the list layout's: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_LAID_OUT));
    }

    @Test
    public void theSharedCheckLeavesTheFullRowUncounted() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertFalse(CommentSheetOptions.topReactionCheck(false));
        assertFalse(statusLine().contains(CommentSheetOptions.EVERY_REACTION_CHECKED));
    }

    @Test
    public void theSharedCheckPausedOrEarlyLeavesThePick() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        SettingsContextRule.withoutContext(() -> assertTrue(CommentSheetOptions.topReactionCheck(true)));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(CommentSheetOptions.topReactionCheck(true));
    }

    @Test
    public void theListLayoutStartsOffAndKeepsFacebooksPick() {
        assertEquals(2, CommentSheetOptions.topReactionLayout(2));
        String line = statusLine();
        assertNotNull(line);
        assertFalse("counted with the switch off: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_LAID_OUT));
    }

    @Test
    public void theListLayoutOnAnswersTheFullRowsAndCountsUnderItsOwnName() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertEquals(0, CommentSheetOptions.topReactionLayout(1));
        assertEquals(0, CommentSheetOptions.topReactionLayout(2));
        assertEquals(0, CommentSheetOptions.topReactionLayout(3));
        String line = statusLine();
        assertTrue(line, line.contains(CommentSheetOptions.EVERY_REACTION_LAID_OUT + " 3"));
        assertFalse("counted as the shared check's: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_CHECKED));
    }

    @Test
    public void theListLayoutLeavesTheFullRowsUncounted() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertEquals(0, CommentSheetOptions.topReactionLayout(0));
        assertFalse(statusLine().contains(CommentSheetOptions.EVERY_REACTION_LAID_OUT));
    }

    @Test
    public void theListLayoutPausedOrEarlyKeepsThePick() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals(3, CommentSheetOptions.topReactionLayout(3)));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(3, CommentSheetOptions.topReactionLayout(3));
    }

    @Test
    public void theIconCapStartsOffAndKeepsOneIcon() {
        assertTrue(CommentSheetOptions.topReactionIcons(true));
        String line = statusLine();
        assertNotNull(line);
        assertFalse("counted with the switch off: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_ICON));
    }

    @Test
    public void theIconCapOnAnswersNoAndCountsUnderItsOwnName() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertFalse(CommentSheetOptions.topReactionIcons(true));
        String line = statusLine();
        assertTrue(line, line.contains(CommentSheetOptions.EVERY_REACTION_ICON));
        assertFalse("counted as the shared check's: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_CHECKED));
        assertFalse("counted as the plugin's: " + line, line.contains(CommentSheetOptions.EVERY_REACTION_KEPT));
    }

    @Test
    public void theIconCapLeavesThreeIconsUncounted() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertFalse(CommentSheetOptions.topReactionIcons(false));
        assertFalse(statusLine().contains(CommentSheetOptions.EVERY_REACTION_ICON));
    }

    @Test
    public void theIconCapPausedOrEarlyKeepsOneIcon() {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        SettingsContextRule.withoutContext(() -> assertTrue(CommentSheetOptions.topReactionIcons(true)));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(CommentSheetOptions.topReactionIcons(true));
    }

    @Test
    public void anExportCarriesTheSwitch() throws Exception {
        Settings.SHOW_EVERY_COMMENT_REACTION.save(true);
        assertTrue(SettingsBackup.create().contains("\"hushfacebook_show_every_comment_reaction\": true"));
    }
}
