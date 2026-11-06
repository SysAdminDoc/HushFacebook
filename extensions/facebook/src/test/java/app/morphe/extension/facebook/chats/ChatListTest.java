/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

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

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The five hooks of Clean up Facebook's chat list: with a switch on, the notes tiles and the row
 * drawing them go, a promotion banner answers no, the search row's Meta AI button is handed back as none or the search
 * screen's row is told not to show its button, and with it off, paused, not ready or failing,
 * Facebook gets what it would have built.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ChatListTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_CHAT_NOTES_TRAY.resetToDefault();
        Settings.HIDE_CHAT_PROMOTIONS.resetToDefault();
        Settings.HIDE_CHAT_META_AI_BUTTON.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.CHAT_LIST + ":")) return line;
        }
        return null;
    }

    @Test
    public void bothSwitchesStartOffAndChatsIsFacebooks() {
        assertFalse(Settings.HIDE_CHAT_NOTES_TRAY.get());
        assertFalse(Settings.HIDE_CHAT_PROMOTIONS.get());
        List<String> tiles = ChatListForTests.tiles();
        assertSame(tiles, ChatList.notesTiles(tiles));
        assertFalse(ChatList.hidesPromotion());
        // Counted with the switches off too, so the report shows Chats asked.
        assertEquals(ChatList.NOTES_ROUTE + ": 1 lists, 3 items, 0 removed", counterLine(ChatList.NOTES_ROUTE));
        assertEquals(ChatList.PROMOTIONS_ROUTE + ": 1 lists, 1 items, 0 removed", counterLine(ChatList.PROMOTIONS_ROUTE));
    }

    @Test
    public void withTheNotesSwitchOnTheTilesGoAndAreCounted() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        List<String> tiles = ChatListForTests.tiles();
        assertEquals(Collections.emptyList(), ChatList.notesTiles(tiles));
        assertEquals(ChatList.NOTES_ROUTE + ": 1 lists, 3 items, 3 removed. Last reason: " + ChatList.NOTES_HIDDEN
                + ". Removed: " + ChatList.NOTES_HIDDEN + " 3", counterLine(ChatList.NOTES_ROUTE));
        assertEquals(FamilyNames.CHAT_LIST + ": invoked 1, 0 found, 0 missing", statusLine());
        // The other switch is its own: promotions stay while only notes are on.
        assertFalse(ChatList.hidesPromotion());
    }

    @Test
    public void withTheNotesSwitchOnTheWholeRowGoes() {
        assertFalse("off, Chats draws its own row", ChatList.hidesNotesRow(CHATS));
        assertEquals(ChatList.NOTES_ROW_ROUTE + ": 1 lists, 1 items, 0 removed", counterLine(ChatList.NOTES_ROW_ROUTE));

        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        assertTrue("on, the section hands back its own empty result", ChatList.hidesNotesRow(CHATS));
        assertEquals(ChatList.NOTES_ROW_ROUTE + ": 2 lists, 2 items, 1 removed. Last reason: " + ChatList.NOTES_HIDDEN
                + ". Removed: " + ChatList.NOTES_HIDDEN + " 1", counterLine(ChatList.NOTES_ROW_ROUTE));
        // The tiles' own count is separate from the row's.
        assertNull(counterLine(ChatList.NOTES_ROUTE));

        // The other switches don't hide the row.
        Settings.HIDE_CHAT_NOTES_TRAY.save(false);
        Settings.HIDE_CHAT_PROMOTIONS.save(true);
        Settings.HIDE_CHAT_META_AI_BUTTON.save(true);
        assertFalse(ChatList.hidesNotesRow(CHATS));
    }

    @Test
    public void pausedOrNotReadyTheNotesRowIsFacebooks() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(ChatList.hidesNotesRow(CHATS));
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(ChatList.hidesNotesRow(CHATS));
        PauseForTests.resume();
        boolean[] hidden = {true, true};
        SettingsContextRule.withoutContext(() -> hidden[0] = ChatList.hidesNotesRow(CHATS));
        SettingsContextRule.beforeThePauseIsDecided(() -> hidden[1] = ChatList.hidesNotesRow(CHATS));
        assertFalse(hidden[0]);
        assertFalse(hidden[1]);
        assertTrue(ChatList.hidesNotesRow(CHATS));
    }

    /** Stands in for Facebook's ThreadListParams: an inbox with its style among other text fields. */
    static final class InboxForTests {
        final String surface;
        final String style;
        final long id = 7;

        InboxForTests(String surface, String style) {
            this.surface = surface;
            this.style = style;
        }
    }

    /** Chats' inbox, and Dating's Matches screen, which Facebook gives Dating's own style. */
    static final InboxForTests CHATS = new InboxForTests("inbox", "mib_style_default");
    static final InboxForTests DATING = new InboxForTests("inbox", "mib_style_gemstone");

    /** Stands in for Facebook's tile: a Dating match or conversation in fields typed from Dating's package, often empty. */
    static final class TileForTests {
        final String name;
        final app.morphe.extension.facebook.chats.gemstone.NewMatchForTests match;

        TileForTests(String name, app.morphe.extension.facebook.chats.gemstone.NewMatchForTests match) {
            this.name = name;
            this.match = match;
        }
    }

    /** #337: Dating's Matches screen draws its new matches with the notes section, and keeps them with the switch on. */
    @Test
    public void datingsNewMatchesRowStaysWithTheNotesSwitchOn() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        assertFalse("Dating's inbox keeps its row", ChatList.hidesNotesRow(DATING));
        assertTrue("Chats' row still goes", ChatList.hidesNotesRow(CHATS));
        assertTrue("an inbox the patch couldn't read is taken as Chats", ChatList.hidesNotesRow(null));
        assertEquals(ChatList.NOTES_ROW_ROUTE + ": 3 lists, 3 items, 2 removed. Last reason: " + ChatList.NOTES_HIDDEN
                + ". Removed: " + ChatList.NOTES_HIDDEN + " 2. Kinds: " + ChatList.DATING_KEPT + " 1",
                counterLine(ChatList.NOTES_ROW_ROUTE));
        List<String> missing = HookStatus.missing(FamilyNames.CHAT_LIST);
        assertTrue(missing.toString(), missing.isEmpty());
    }

    @Test
    public void datingsNewMatchTilesStayWithTheNotesSwitchOn() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        List<TileForTests> matches = Arrays.asList(
                new TileForTests("a new match", new app.morphe.extension.facebook.chats.gemstone.NewMatchForTests()),
                new TileForTests("another", null));
        assertSame(matches, ChatList.notesTiles(matches));
        // Facebook's tile with its Dating fields empty is an ordinary tile, and goes.
        List<TileForTests> plain = Arrays.asList(new TileForTests("a friend note", null), new TileForTests("active", null));
        assertEquals(Collections.emptyList(), ChatList.notesTiles(plain));
        assertEquals(ChatList.NOTES_ROUTE + ": 2 lists, 4 items, 2 removed. Last reason: " + ChatList.NOTES_HIDDEN
                + ". Removed: " + ChatList.NOTES_HIDDEN + " 2. Kinds: " + ChatList.DATING_KEPT + " 1",
                counterLine(ChatList.NOTES_ROUTE));
    }

    @Test
    public void withThePromotionsSwitchOnABannerAnswersNo() {
        Settings.HIDE_CHAT_PROMOTIONS.save(true);
        assertTrue(ChatList.hidesPromotion());
        assertEquals(ChatList.PROMOTIONS_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: "
                + ChatList.PROMOTIONS_HIDDEN + ". Removed: " + ChatList.PROMOTIONS_HIDDEN + " 1",
                counterLine(ChatList.PROMOTIONS_ROUTE));
        // The notes tiles stay while only promotions are on.
        List<String> tiles = ChatListForTests.tiles();
        assertSame(tiles, ChatList.notesTiles(tiles));
    }

    @Test
    public void anEmptyOrMissingListIsHandedBackAsItIs() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        assertNull(ChatList.notesTiles(null));
        List<String> none = Collections.emptyList();
        assertSame(none, ChatList.notesTiles(none));
        assertNull("nothing was counted for a list with no tiles", counterLine(ChatList.NOTES_ROUTE));
    }

    @Test
    public void pausedChatsIsFacebooks() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        Settings.HIDE_CHAT_PROMOTIONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(ChatListForTests.dropsTheNotesTiles());
        assertFalse(ChatList.hidesPromotion());
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(ChatListForTests.dropsTheNotesTiles());
        assertFalse(ChatList.hidesPromotion());
        PauseForTests.resume();
        assertTrue(ChatListForTests.dropsTheNotesTiles());
        assertTrue(ChatList.hidesPromotion());
    }

    /** Until the settings are ready, Facebook builds its own list and no switch is read. */
    @Test
    public void untilTheSettingsAreReadyChatsIsFacebooks() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        Settings.HIDE_CHAT_PROMOTIONS.save(true);
        boolean[] changed = {true, true};
        SettingsContextRule.withoutContext(() -> {
            changed[0] = ChatListForTests.dropsTheNotesTiles();
            changed[1] = ChatList.hidesPromotion();
        });
        assertFalse(changed[0]);
        assertFalse(changed[1]);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            changed[0] = ChatListForTests.dropsTheNotesTiles();
            changed[1] = ChatList.hidesPromotion();
        });
        assertFalse(changed[0]);
        assertFalse(changed[1]);
        assertTrue(ChatListForTests.dropsTheNotesTiles());
    }

    /** A list that throws when asked its size is Facebook's own again, and the report names the hook. */
    @Test
    public void aFailureHandsTheListBackAndTheReportSaysSo() {
        Settings.HIDE_CHAT_NOTES_TRAY.save(true);
        List<String> broken = new AbstractList<String>() {
            @Override public String get(int index) {
                return "tile";
            }

            @Override public int size() {
                throw new IllegalStateException("no size");
            }

            @Override public boolean isEmpty() {
                return false;
            }
        };
        assertSame(broken, ChatList.notesTiles(broken));
        List<String> missing = HookStatus.missing(FamilyNames.CHAT_LIST);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(
                "'notes tray tiles' hook (it threw " + IllegalStateException.class.getName() + ")"));
    }

    @Test
    public void theMetaAiButtonStaysUntilItsSwitchIsOn() {
        assertFalse(Settings.HIDE_CHAT_META_AI_BUTTON.get());
        Object button = new Object();
        assertSame(button, ChatList.metaAiButton(button));
        assertEquals(ChatList.META_AI_ROUTE + ": 1 lists, 1 items, 0 removed", counterLine(ChatList.META_AI_ROUTE));

        Settings.HIDE_CHAT_META_AI_BUTTON.save(true);
        assertNull("the row's own null check leaves it out", ChatList.metaAiButton(button));
        assertEquals(ChatList.META_AI_ROUTE + ": 2 lists, 2 items, 1 removed. Last reason: " + ChatList.META_AI_HIDDEN
                + ". Removed: " + ChatList.META_AI_HIDDEN + " 1", counterLine(ChatList.META_AI_ROUTE));
        // The other switches are their own.
        assertFalse(ChatList.hidesPromotion());
        List<String> tiles = ChatListForTests.tiles();
        assertSame(tiles, ChatList.notesTiles(tiles));
    }

    @Test
    public void noButtonIsHandedBackAsNoneAndNotCounted() {
        Settings.HIDE_CHAT_META_AI_BUTTON.save(true);
        assertNull(ChatList.metaAiButton(null));
        assertNull(counterLine(ChatList.META_AI_ROUTE));
    }

    @Test
    public void pausedOrNotReadyTheMetaAiButtonIsFacebooks() {
        Settings.HIDE_CHAT_META_AI_BUTTON.save(true);
        Object button = new Object();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(button, ChatList.metaAiButton(button));
        PauseForTests.resume();
        Object[] kept = new Object[1];
        SettingsContextRule.withoutContext(() -> kept[0] = ChatList.metaAiButton(button));
        assertSame(button, kept[0]);
        assertNull(ChatList.metaAiButton(button));
    }

    @Test
    public void theSearchScreensMetaAiButtonGoesWithTheSameSwitch() {
        assertTrue("off, the row keeps its button", ChatList.showsSearchMetaAiButton(true));
        assertEquals(ChatList.META_AI_ROUTE + ": 1 lists, 1 items, 0 removed", counterLine(ChatList.META_AI_ROUTE));

        Settings.HIDE_CHAT_META_AI_BUTTON.save(true);
        assertFalse("on, the row is built without it", ChatList.showsSearchMetaAiButton(true));
        assertEquals(ChatList.META_AI_ROUTE + ": 2 lists, 2 items, 1 removed. Last reason: " + ChatList.META_AI_HIDDEN
                + ". Removed: " + ChatList.META_AI_HIDDEN + " 1", counterLine(ChatList.META_AI_ROUTE));
    }

    @Test
    public void aSearchScreenWithNoButtonIsLeftAloneAndNotCounted() {
        assertFalse(ChatList.showsSearchMetaAiButton(false));
        Settings.HIDE_CHAT_META_AI_BUTTON.save(true);
        assertFalse(ChatList.showsSearchMetaAiButton(false));
        assertNull(counterLine(ChatList.META_AI_ROUTE));
    }

    @Test
    public void pausedOrNotReadyTheSearchScreensButtonIsFacebooks() {
        Settings.HIDE_CHAT_META_AI_BUTTON.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(ChatList.showsSearchMetaAiButton(true));
        PauseForTests.resume();
        boolean[] shown = new boolean[1];
        SettingsContextRule.withoutContext(() -> shown[0] = ChatList.showsSearchMetaAiButton(true));
        assertTrue(shown[0]);
        assertFalse(ChatList.showsSearchMetaAiButton(true));
    }

    @Test
    public void theHookReportsUnderThePatchsName() {
        assertEquals("Clean up Facebook's chat list", FamilyNames.CHAT_LIST);
        ChatList.notesTiles(ChatListForTests.tiles());
        ChatList.hidesPromotion();
        assertEquals(FamilyNames.CHAT_LIST + ": invoked 2, 0 found, 0 missing", statusLine());
    }
}
