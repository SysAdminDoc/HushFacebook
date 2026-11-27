/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

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
 * What a held comment offers (#607): each switch leaves out only its own kinds of option, Report
 * covering Facebook's three report kinds, and every other kind stays. The reactions go to null
 * under their switch. A glimpse the switches emptied keeps Facebook's comment sheet shut once, and
 * that's forgotten as soon as the main thread is done building it. The older comment sheet some
 * accounts get in its place loses the same rows, told apart by plugin name and icon name, and its
 * reactions under the same switch. Off, paused, or before the settings are ready, nothing changes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HeldCommentTest {
    /** Stands in for Facebook's kinds of option, which keep their names. */
    private enum Kind { SHARE_SHEET, GROUP_SHARE, FIND_SUPPORT, REPLY, REPORT_TO_ADMIN, COPY, PIN, HIDE, DELETE, EDIT, REPORT }

    /** The package Facebook's older comment sheet keeps its plugins' names in. */
    private static final String MENU = "com.facebook.feedback.comments.plugins.commentbottomsheet.commentbottomsheetmenu.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        // Another test class's held comment may have left its reset in a main looper since thrown away.
        HeldComment.forget();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_HELD_COMMENT_REACTIONS.resetToDefault();
        Settings.HIDE_HELD_COMMENT_REPLY.resetToDefault();
        Settings.HIDE_HELD_COMMENT_COPY.resetToDefault();
        Settings.HIDE_HELD_COMMENT_REPORT.resetToDefault();
        Settings.HIDE_HELD_COMMENT_HIDE.resetToDefault();
        idle();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    private static String dropped() {
        StringBuilder kinds = new StringBuilder();
        for (Kind kind : Kind.values()) {
            if (HeldComment.dropsOption(kind)) kinds.append(kinds.length() == 0 ? "" : " ").append(kind.name());
        }
        return kinds.toString();
    }

    @Test
    public void eachSwitchLeavesOutOnlyItsOwnOptions() {
        assertEquals("an option went with every switch off", "", dropped());
        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        assertEquals("REPLY", dropped());
        Settings.HIDE_HELD_COMMENT_REPLY.save(false);
        Settings.HIDE_HELD_COMMENT_COPY.save(true);
        assertEquals("COPY", dropped());
        Settings.HIDE_HELD_COMMENT_COPY.save(false);
        Settings.HIDE_HELD_COMMENT_REPORT.save(true);
        assertEquals("FIND_SUPPORT REPORT_TO_ADMIN REPORT", dropped());
        Settings.HIDE_HELD_COMMENT_REPORT.save(false);
        Settings.HIDE_HELD_COMMENT_HIDE.save(true);
        assertEquals("HIDE", dropped());
        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        Settings.HIDE_HELD_COMMENT_COPY.save(true);
        Settings.HIDE_HELD_COMMENT_REPORT.save(true);
        Settings.HIDE_HELD_COMMENT_REACTIONS.save(true);
        assertEquals("Edit, Delete, Pin and Share went with the rest",
                "FIND_SUPPORT REPLY REPORT_TO_ADMIN COPY HIDE REPORT", dropped());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 66, 1 found, 0 missing. Counted: "
                + HeldComment.OPTION_HIDDEN + " 12", statusLine());
    }

    @Test
    public void anythingButANamedKindStays() {
        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        assertFalse("a kind that isn't an enum went", HeldComment.dropsOption("REPLY"));
        assertFalse("no kind at all went", HeldComment.dropsOption(null));
        assertFalse("nothing was taken, yet the sheet stayed shut", HeldComment.leftEmpty());
    }

    @Test
    public void theReactionsGoOnlyUnderTheirSwitch() {
        Object reactions = new Object();
        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        assertSame("the Reply switch took the reactions", reactions, HeldComment.reactions(reactions));
        Settings.HIDE_HELD_COMMENT_REACTIONS.save(true);
        assertNull("the reactions stayed", HeldComment.reactions(reactions));
        assertNull("a comment with no reactions got some", HeldComment.reactions(null));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 3, 1 found, 0 missing. Counted: "
                + HeldComment.REACTIONS_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aGlimpseTheSwitchesEmptiedKeepsTheSheetShutOnce() {
        Settings.HIDE_HELD_COMMENT_COPY.save(true);
        assertTrue(HeldComment.dropsOption(Kind.COPY));
        assertTrue("the emptied glimpse opened the comment sheet", HeldComment.leftEmpty());
        assertFalse("the next comment's sheet stayed shut too", HeldComment.leftEmpty());

        Settings.HIDE_HELD_COMMENT_REACTIONS.save(true);
        assertNull(HeldComment.reactions(new Object()));
        assertTrue("a glimpse emptied of its reactions opened the comment sheet", HeldComment.leftEmpty());

        assertTrue(HeldComment.dropsOption(Kind.COPY));
        idle();
        assertFalse("what the last held comment lost carried over to the next", HeldComment.leftEmpty());
        assertTrue("Copy stayed on the next held comment", HeldComment.dropsOption(Kind.COPY));
        assertTrue("a second held comment's emptied glimpse opened the comment sheet", HeldComment.leftEmpty());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 9, 3 found, 0 missing. Counted: "
                + HeldComment.OPTION_HIDDEN + " 3, " + HeldComment.SHEET_KEPT_SHUT + " 3, "
                + HeldComment.REACTIONS_HIDDEN + " 1", statusLine());
    }

    @Test
    public void theOlderSheetsPluginRowsGoUnderTheirSwitches() {
        String reply = MENU + "commentreply.CommentReplyBottomSheetMenuPlugin";
        String admins = MENU + "reporttoadmin.ReportToAdminPlugin";
        String report = MENU + "commentfrx.CommentFRXBottomSheetMenuPlugin";
        String share = MENU + "commentshare.GroupCommentShareBottomSheetMenuPlugin";
        String privateReply = MENU + "pageprivatereply.PagePrivateReplyBottomSheetMenuPlugin";
        assertFalse("Reply went with every switch off", HeldComment.dropsSheetPlugin(reply));
        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        assertTrue("Reply stayed", HeldComment.dropsSheetPlugin(reply));
        assertFalse("a page's private reply went with Reply", HeldComment.dropsSheetPlugin(privateReply));
        assertFalse("Report to group admins went with Reply", HeldComment.dropsSheetPlugin(admins));
        Settings.HIDE_HELD_COMMENT_REPORT.save(true);
        assertTrue("Report to group admins stayed", HeldComment.dropsSheetPlugin(admins));
        assertTrue("Report comment stayed", HeldComment.dropsSheetPlugin(report));
        Settings.HIDE_HELD_COMMENT_COPY.save(true);
        Settings.HIDE_HELD_COMMENT_HIDE.save(true);
        Settings.HIDE_HELD_COMMENT_REACTIONS.save(true);
        assertFalse("Share went with the rest", HeldComment.dropsSheetPlugin(share));
        assertFalse("no plugin at all went", HeldComment.dropsSheetPlugin(null));
        assertFalse("a plugin row emptied the next glimpse", HeldComment.leftEmpty());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 9, 2 found, 0 missing. Counted: "
                + HeldComment.OPTION_HIDDEN + " 3", statusLine());
    }

    @Test
    public void theOlderSheetsOwnRowsAreToldApartByTheirIcons() {
        assertEquals("REPLY", HeldComment.iconKind("fb_ic_comment_outline_24"));
        assertEquals("COPY", HeldComment.iconKind("fb_ic_copy_outline_24"));
        assertEquals("REPORT", HeldComment.iconKind("fb_ic_feedback_outline_24"));
        assertEquals("REPORT", HeldComment.iconKind("fb_ic_caution_triangle_outline_24"));
        assertEquals("HIDE", HeldComment.iconKind("fb_ic_hide_outline_24"));
        // Pin, Delete, Edit, Block and the Save rows stay whatever the switches say.
        for (String stays : new String[] {"fb_ic_pushpin_outline_24", "fb_ic_trash_outline_24", "fb_ic_pencil_outline_24",
                "fb_ic_friend_block_outline_24", "fb_ic_photo_outline_24"}) {
            assertNull(stays + " has a switch", HeldComment.iconKind(stays));
        }
        assertNull(HeldComment.iconKind(null));
        assertEquals("an icon's id wasn't read back as its name", "ic_menu_edit", HeldComment.iconName(android.R.drawable.ic_menu_edit));
        assertNull("no icon got a name", HeldComment.iconName(0));
        assertNull("an id with no resource got a name", HeldComment.iconName(0x7f7ffff1));

        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        Settings.HIDE_HELD_COMMENT_COPY.save(true);
        Settings.HIDE_HELD_COMMENT_REPORT.save(true);
        Settings.HIDE_HELD_COMMENT_HIDE.save(true);
        assertFalse("a row no switch covers went", HeldComment.dropsSheetRow(android.R.drawable.ic_menu_edit));
        assertFalse("a row with no icon went", HeldComment.dropsSheetRow(0));
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 2, 1 found, 0 missing", statusLine());
    }

    @Test
    public void theOlderSheetsReactionsGoOnlyUnderTheirSwitch() {
        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        assertFalse("the Reply switch took the sheet's reactions", HeldComment.dropsSheetReactions());
        Settings.HIDE_HELD_COMMENT_REACTIONS.save(true);
        assertTrue("the sheet's reactions stayed", HeldComment.dropsSheetReactions());
        assertFalse("the sheet's reactions emptied the next glimpse", HeldComment.leftEmpty());
        assertEquals(FamilyNames.COMMENT_SHEET_OPTIONS + ": invoked 3, 2 found, 0 missing. Counted: "
                + HeldComment.REACTIONS_HIDDEN + " 1", statusLine());
    }

    @Test
    public void pausedOrBeforeTheSettingsNothingChanges() {
        Settings.HIDE_HELD_COMMENT_REPLY.save(true);
        Settings.HIDE_HELD_COMMENT_REACTIONS.save(true);
        Object reactions = new Object();
        String reply = MENU + "commentreply.CommentReplyBottomSheetMenuPlugin";
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook took Reply", HeldComment.dropsOption(Kind.REPLY));
        assertSame("a paused Hushfacebook took the reactions", reactions, HeldComment.reactions(reactions));
        assertFalse("a paused Hushfacebook kept the sheet shut", HeldComment.leftEmpty());
        assertFalse("a paused Hushfacebook took the sheet's Reply", HeldComment.dropsSheetPlugin(reply));
        assertFalse("a paused Hushfacebook took the sheet's reactions", HeldComment.dropsSheetReactions());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertFalse("Reply went before the settings were ready", HeldComment.dropsOption(Kind.REPLY));
            assertSame("the reactions went before the settings were ready", reactions, HeldComment.reactions(reactions));
            assertFalse("the sheet's Reply went before the settings were ready", HeldComment.dropsSheetPlugin(reply));
            assertFalse("the sheet's reactions went before the settings were ready", HeldComment.dropsSheetReactions());
        });
    }
}
