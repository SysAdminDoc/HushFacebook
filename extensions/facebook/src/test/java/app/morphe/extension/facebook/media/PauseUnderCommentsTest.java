/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Pause reels while comments are open (#266): with its switch on, the reel playing on screen is
 * paused when a comment sheet opens, a reel start Facebook makes by itself waits while the sheet is
 * up, and the reel plays on, armed for Tap to play, once the sheet has been gone a moment. A start
 * you ask for plays and isn't given back twice, a sheet that comes straight back isn't closed, and
 * off, or with no reel playing, nothing is paused or held. A tap on the reel above the comments
 * (#315) pauses it or plays it, armed, switch or not, and closing the sheet leaves it that way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PauseUnderCommentsTest {
    /** Stands in for Facebook's EventTriggerType, which the hook reads by name. */
    enum Trigger { BY_USER, BY_AUTOPLAY }

    /** Players as Tap to play's bridge would show them, with every play and pause written down. */
    private static final class FakePlayers implements PauseUnderComments.Players {
        final List<Object> known = new ArrayList<>();
        final Set<Object> playing = new HashSet<>();
        final Set<Object> reels = new HashSet<>();
        final Map<Object, View> views = new HashMap<>();
        final List<String> commands = new ArrayList<>();

        public List<Object> known() { return new ArrayList<>(known); }
        public boolean playing(Object player) { return playing.contains(player); }
        public boolean reel(Object player) { return reels.contains(player); }
        public View view(Object player) { return views.get(player); }
        public void command(Object player, boolean play) {
            commands.add((play ? "play " : "pause ") + player);
            if (play) playing.add(player);
            else playing.remove(player);
        }
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FakePlayers players;
    private Activity screen;
    private final String reel = "reel";
    private final String feedVideo = "feed video";

    @Before
    public void start() {
        HookStatus.clear();
        TapToPlay.forget();
        players = new FakePlayers();
        PauseUnderComments.access = players;
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        players.known.addAll(Arrays.asList(feedVideo, reel));
        players.playing.addAll(Arrays.asList(feedVideo, reel));
        players.reels.add(reel);
    }

    @After
    public void restore() {
        PauseUnderComments.reset();
        TapToPlay.forget();
        Settings.PAUSE_REELS_UNDER_COMMENTS.resetToDefault();
        Settings.TAP_TO_PLAY.resetToDefault();
        HookStatus.clear();
    }

    /** A comment sheet's views, put on screen the way the dialog shows them. */
    private View openSheet() {
        FrameLayout sheet = new FrameLayout(screen);
        screen.setContentView(sheet);
        PauseUnderComments.sheetOpened(sheet);
        return sheet;
    }

    private void closeSheet(View sheet) {
        ((FrameLayout) sheet.getParent()).removeView(sheet);
        settle();
    }

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(PauseUnderComments.CLOSE_SETTLE_MS, TimeUnit.MILLISECONDS);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FULL_HEIGHT_COMMENTS + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheReelPausesForItsCommentsAndPlaysOnArmedWhenTheyClose() {
        Settings.PAUSE_REELS_UNDER_COMMENTS.save(true);
        View sheet = openSheet();
        assertEquals("only the reel should pause", Arrays.asList("pause reel"), players.commands);
        assertTrue("Facebook's own reel start went ahead under the sheet",
                PauseUnderComments.holdsStart(reel, Trigger.BY_AUTOPLAY));
        assertFalse("a feed video's start was held", PauseUnderComments.holdsStart(feedVideo, Trigger.BY_AUTOPLAY));

        closeSheet(sheet);
        assertEquals(Arrays.asList("pause reel", "play reel"), players.commands);
        assertTrue("Tap to play would hold the reel's start", TapToPlay.armed(reel));
        assertFalse("a reel start was held after the sheet closed",
                PauseUnderComments.holdsStart(reel, Trigger.BY_AUTOPLAY));
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 1, 1 found, 0 missing. Counted: "
                + PauseUnderComments.PAUSED + " 1, " + PauseUnderComments.HELD + " 1, "
                + PauseUnderComments.RESUMED + " 1", statusLine());
    }

    @Test
    public void tapToPlayLetsNoReelStartThroughWhileItsCommentsAreOpen() {
        Settings.PAUSE_REELS_UNDER_COMMENTS.save(true);
        openSheet();
        assertFalse("the reel started under its comments", TapToPlay.allowStart(reel, Trigger.BY_AUTOPLAY));
        assertTrue("a start you asked for was held", TapToPlay.allowStart(reel, Trigger.BY_USER));
    }

    @Test
    public void aReelYouPlayUnderTheSheetIsntPlayedAgainWhenItCloses() {
        Settings.PAUSE_REELS_UNDER_COMMENTS.save(true);
        View sheet = openSheet();
        assertFalse("your own start was held", PauseUnderComments.holdsStart(reel, Trigger.BY_USER));
        assertFalse("nothing should be left to give back", PauseUnderComments.pausedOne());
        closeSheet(sheet);
        assertEquals(Arrays.asList("pause reel"), players.commands);
    }

    @Test
    public void aSheetThatComesStraightBackStaysOpen() {
        Settings.PAUSE_REELS_UNDER_COMMENTS.save(true);
        View sheet = openSheet();
        FrameLayout parent = (FrameLayout) sheet.getParent();
        parent.removeView(sheet);
        parent.addView(sheet);
        settle();
        assertEquals("the reel played on under an open sheet", Arrays.asList("pause reel"), players.commands);
        assertTrue(PauseUnderComments.holdsStart(reel, Trigger.BY_AUTOPLAY));
    }

    @Test
    public void aReelAlreadyPausedStaysPausedAndNothingIsHeld() {
        Settings.PAUSE_REELS_UNDER_COMMENTS.save(true);
        players.playing.remove(reel);
        View sheet = openSheet();
        assertFalse(PauseUnderComments.holdsStart(reel, Trigger.BY_AUTOPLAY));
        closeSheet(sheet);
        assertTrue("a paused reel was played or paused", players.commands.isEmpty());
    }

    @Test
    public void aTapAboveTheCommentsPausesThePlayingReelAndPlaysItAgainArmed() {
        // Tap a reel above its comments uses the bridge whether this switch is on or not (#315).
        View reelView = new View(screen);
        screen.setContentView(reelView);
        players.views.put(reel, reelView);
        assertTrue("no reel was found to pause", PauseUnderComments.tapReel());
        assertTrue("no reel was found to play", PauseUnderComments.tapReel());
        assertEquals(Arrays.asList("pause reel", "play reel"), players.commands);
        assertTrue("Tap to play would hold the reel's start", TapToPlay.armed(reel));
        assertNull("the taps were counted under this switch", statusLine());
    }

    @Test
    public void aReelTheSheetPausedPlaysOnATapAndStaysAsYouLeftItWhenTheSheetCloses() {
        Settings.PAUSE_REELS_UNDER_COMMENTS.save(true);
        View sheet = openSheet();
        assertTrue(PauseUnderComments.tapReel());
        assertFalse("the reel you played would be played again", PauseUnderComments.pausedOne());
        assertFalse("the reel you played was held", PauseUnderComments.holdsStart(reel, Trigger.BY_AUTOPLAY));
        assertTrue(PauseUnderComments.tapReel());
        closeSheet(sheet);
        assertEquals("the reel you paused played on when the sheet closed",
                Arrays.asList("pause reel", "play reel", "pause reel"), players.commands);
    }

    /**
     * #617: with comments open, the next reel waits under the sheet, its view shown and ahead of
     * the reel you're on in the bridge's list. A tap that paused your reel plays that reel again,
     * not the next one, whose sound came on with the switch off.
     */
    @Test
    public void aTapPlaysBackTheReelItPausedRatherThanTheNextOneWaitingBelow() {
        String next = "next reel";
        FrameLayout page = new FrameLayout(screen);
        View nextView = new View(screen);
        View reelView = new View(screen);
        page.addView(nextView);
        page.addView(reelView);
        screen.setContentView(page);
        players.known.add(0, next);
        players.reels.add(next);
        players.views.put(next, nextView);
        players.views.put(reel, reelView);
        assertTrue("no reel was found to pause", PauseUnderComments.tapReel());
        assertTrue("no reel was found to play", PauseUnderComments.tapReel());
        assertEquals(Arrays.asList("pause reel", "play reel"), players.commands);
        assertTrue("Tap to play would hold the reel's start", TapToPlay.armed(reel));
    }

    /**
     * #617: with no reel paused by a tap or a sheet, the one a tap plays is the one on the screen,
     * not the next reel laid out below it.
     */
    @Test
    public void withNoReelRememberedATapPlaysTheOneOnTheScreen() {
        String next = "next reel";
        FrameLayout page = new FrameLayout(screen);
        View nextView = new View(screen);
        View reelView = new View(screen);
        page.addView(nextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(reelView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        screen.setContentView(page);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the page wasn't laid out", page.getHeight() > 0);
        nextView.setTranslationY(page.getHeight());
        players.known.add(0, next);
        players.reels.add(next);
        players.views.put(next, nextView);
        players.views.put(reel, reelView);
        players.playing.remove(reel);
        assertTrue("no reel was found to play", PauseUnderComments.tapReel());
        assertEquals(Arrays.asList("play reel"), players.commands);
    }

    @Test
    public void withNoReelATapIsLeftToFacebook() {
        players.known.remove(reel);
        assertFalse("a feed video was taken for the reel", PauseUnderComments.tapReel());
        players.known.add(reel);
        players.playing.remove(reel);
        assertFalse("a reel that isn't shown was played", PauseUnderComments.tapReel());
        assertTrue(players.commands.isEmpty());
    }

    @Test
    public void offTheReelPlaysOnAndTheReportStaysAsItWas() {
        View sheet = openSheet();
        assertFalse(PauseUnderComments.holdsStart(reel, Trigger.BY_AUTOPLAY));
        closeSheet(sheet);
        assertTrue("the switch is off", players.commands.isEmpty());
        assertNull(statusLine());
    }
}
