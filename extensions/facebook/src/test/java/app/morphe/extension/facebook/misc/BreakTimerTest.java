/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.navigation.FacebookTabs;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Take breaks: browsing a feed for the use time puts a cover over it for the break length, and only
 * over feeds and reel screens. A second resume, a rotation, time with the screen off and other pages
 * don't count, time away as long as a break starts the count over, and a break holds across a
 * restart of Facebook or of the phone. The switch off or Hushfacebook paused end it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class BreakTimerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A browsing screen, as the feed screen check sees it, that counts the times it was sent away. */
    public static final class Feed extends Activity {
        int sentBack;

        @Override
        public boolean moveTaskToBack(boolean nonRoot) {
            sentBack++;
            return true;
        }
    }

    /** A page that isn't browsing, such as a profile or a chat. */
    public static final class Page extends Activity {
    }

    private final List<ActivityController<?>> controllers = new ArrayList<>();
    private final long[] wall = {1_800_000_000_000L};

    @Before
    public void freshProcess() {
        BreakTimer.forgetForTests();
        seams();
        Settings.TAKE_BREAKS.save(true);
        Settings.BREAK_AFTER.save(BreakTimer.Use.TEN_MINUTES);
        screenOn(true);
    }

    @After
    public void restore() {
        BreakTimer.forgetForTests();
        PauseForTests.resume();
        Settings.TAKE_BREAKS.resetToDefault();
        Settings.BREAK_AFTER.resetToDefault();
        Settings.BREAK_LENGTH.resetToDefault();
        Settings.BREAK_AFTER_REELS.resetToDefault();
        for (ActivityController<?> controller : controllers) controller.close();
    }

    /** What a new process starts with, but the feed check and the wall clock the tests' own. */
    private void seams() {
        BreakTimer.feedScreen = activity -> activity instanceof Feed;
        BreakTimer.wallClock = () -> wall[0];
    }

    /** Facebook starting a new process: what's on disk is all that's left. */
    private void restartFacebook() {
        BreakTimer.forgetForTests();
        seams();
    }

    private static void bootCount(int count) {
        android.provider.Settings.Global.putInt(RuntimeEnvironment.getApplication().getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, count);
    }

    private static void screenOn(boolean on) {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class)).setIsInteractive(on);
    }

    private <T extends Activity> T screen(Class<T> type) {
        ActivityController<T> controller = Robolectric.buildActivity(type).setup();
        controllers.add(controller);
        return controller.get();
    }

    /** [millis] of the main thread's time going by, the count's ticks with it. */
    private static void pass(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** A feed in front for the whole use time but a second, so the next second starts a break. */
    private Feed almostUsedUp() {
        Feed feed = screen(Feed.class);
        BreakTimer.resumed(feed);
        pass(599_000);
        assertFalse("a break came early", BreakTimer.covering());
        return feed;
    }

    /** A feed browsed until a break covers it. */
    private Feed onABreak() {
        Feed feed = almostUsedUp();
        pass(1_000);
        assertTrue("the use time ran out without a break", BreakTimer.covering());
        return feed;
    }

    private static int coversShowing() {
        int showing = 0;
        for (Dialog dialog : ShadowDialog.getShownDialogs()) {
            if (dialog.isShowing()) showing++;
        }
        return showing;
    }

    private static List<TextView> texts(View view, List<TextView> found) {
        if (view instanceof TextView) found.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) texts(group.getChildAt(i), found);
        }
        return found;
    }

    private static List<TextView> texts() {
        Dialog cover = ShadowDialog.getLatestDialog();
        assertNotNull("no cover was made", cover);
        return texts(cover.getWindow().getDecorView(), new ArrayList<>());
    }

    private static boolean shows(String line) {
        for (TextView text : texts()) {
            if (line.contentEquals(text.getText())) return true;
        }
        return false;
    }

    private static Button button(String label) {
        for (TextView text : texts()) {
            if (text instanceof Button && label.contentEquals(text.getText())) return (Button) text;
        }
        throw new AssertionError("no " + label + " button on the cover");
    }

    /** Reel [id] starting in front, as the player hook hands it over, and whether it plays on. */
    private static void reel(String id, boolean playsOn) {
        BreakTimer.reelStarted();
        BreakTimer.reelPlaying(id, () -> playsOn);
    }

    /** Reels "reel[from]" on, [count] of them, each played until the count checks it. */
    private static void watchReels(int from, int count) {
        for (int i = from; i < from + count; i++) {
            reel("reel" + i, true);
            pass(BreakTimer.REEL_WATCHED_AFTER_MS);
        }
    }

    /** A reel screen in front with reels counted. */
    private Page reelsCounted() {
        Settings.BREAK_AFTER_REELS.save(true);
        Page reels = screen(Page.class);
        BreakTimer.resumed(reels);
        return reels;
    }

    @Test
    public void offLeavesFacebookStock() {
        Settings.TAKE_BREAKS.save(false);
        BreakTimer.resumed(screen(Feed.class));
        pass(Duration.ofMinutes(61).toMillis());
        assertFalse(BreakTimer.covering());
        assertEquals(0, coversShowing());
    }

    @Test
    public void theUseTimeRunningOutCoversTheFeedForTheBreak() {
        onABreak();
        assertTrue(shows("Time for a break"));
        assertTrue(shows("5:00"));
        assertTrue(shows("Home, Feeds, Video and Reels open again in 5 minutes."));
        assertTrue(shows("Chats, your profile and the Menu still work."));

        pass(Duration.ofMinutes(4).plusSeconds(30).toMillis());
        assertTrue(shows("0:30"));
        assertTrue(shows("Home, Feeds, Video and Reels open again in under a minute."));
        assertTrue(BreakTimer.covering());

        pass(30_000);
        assertFalse("the break outlasted its length", BreakTimer.covering());
        assertEquals("Your break is over.", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theChosenTimesAreTheOnesUsed() {
        Settings.BREAK_AFTER.save(BreakTimer.Use.FIFTEEN_MINUTES);
        Settings.BREAK_LENGTH.save(BreakTimer.Length.ONE_MINUTE);
        BreakTimer.resumed(screen(Feed.class));
        pass(Duration.ofMinutes(14).toMillis());
        assertFalse(BreakTimer.covering());
        pass(Duration.ofMinutes(1).toMillis());
        assertTrue(BreakTimer.covering());
        assertTrue(shows("1:00"));
        pass(60_000);
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void otherPagesArentCountedOrCovered() {
        Page page = screen(Page.class);
        BreakTimer.resumed(page);
        pass(Duration.ofMinutes(11).toMillis());
        assertFalse("a page that isn't browsing counted", BreakTimer.covering());

        BreakTimer.paused(page);
        Feed feed = onABreak();
        BreakTimer.paused(feed);
        BreakTimer.resumed(page);
        pass(5_000);
        assertEquals("a profile or chat was covered", 1, coversShowing());
    }

    @Test
    public void aSecondResumeOrARotationDoesntCountTwice() {
        Feed before = screen(Feed.class);
        BreakTimer.resumed(before);
        BreakTimer.resumed(before);
        pass(300_000);
        // Android's order for a rotation: the old screen goes, then the new one comes.
        BreakTimer.paused(before);
        BreakTimer.destroyed(before);
        Feed after = screen(Feed.class);
        BreakTimer.resumed(after);
        pass(299_000);
        assertFalse("time was counted twice", BreakTimer.covering());
        pass(1_000);
        assertTrue(BreakTimer.covering());
    }

    @Test
    public void timeWithTheScreenOffIsntCounted() {
        screenOn(false);
        BreakTimer.resumed(screen(Feed.class));
        pass(Duration.ofMinutes(11).toMillis());
        assertFalse(BreakTimer.covering());

        screenOn(true);
        pass(Duration.ofMinutes(9).toMillis());
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void timeAwayAsLongAsABreakStartsTheCountOver() {
        Feed feed = screen(Feed.class);
        BreakTimer.resumed(feed);
        pass(Duration.ofMinutes(9).toMillis());
        BreakTimer.paused(feed);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        BreakTimer.resumed(feed);
        pass(Duration.ofMinutes(9).toMillis());
        assertFalse("five minutes away didn't start the count over", BreakTimer.covering());

        BreakTimer.paused(feed);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
        BreakTimer.resumed(feed);
        pass(Duration.ofMinutes(1).toMillis());
        assertTrue("four minutes away started the count over", BreakTimer.covering());
    }

    @Test
    public void leavingAndComingBackDoesntEndABreak() {
        Feed feed = onABreak();
        BreakTimer.paused(feed);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        BreakTimer.resumed(feed);
        pass(1_000);
        assertTrue(BreakTimer.covering());
        assertTrue(shows("Home, Feeds, Video and Reels open again in 3 minutes."));
    }

    @Test
    public void aBreakHoldsAcrossARestartOfFacebook() {
        bootCount(7);
        onABreak();
        pass(60_000);
        restartFacebook();
        BreakTimer.resumed(screen(Feed.class));
        assertTrue("a restart ended the break", BreakTimer.covering());
        assertTrue(shows("Home, Feeds, Video and Reels open again in 4 minutes."));

        pass(Duration.ofMinutes(4).toMillis());
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void aPhoneRestartKeepsTheSmallerTimeLeft() {
        bootCount(7);
        onABreak();
        bootCount(8);
        wall[0] += Duration.ofMinutes(2).toMillis();
        restartFacebook();
        BreakTimer.resumed(screen(Feed.class));
        assertTrue("a phone restart ended the break", BreakTimer.covering());
        assertTrue(shows("3:00"));
    }

    @Test
    public void movingTheClockBackCantLengthenABreak() {
        bootCount(7);
        onABreak();
        bootCount(8);
        wall[0] -= Duration.ofHours(1).toMillis();
        restartFacebook();
        BreakTimer.resumed(screen(Feed.class));
        assertTrue(BreakTimer.covering());
        assertTrue(shows("5:00"));
    }

    @Test
    public void aCountFromBeforeAPhoneRestartStartsOver() {
        bootCount(7);
        Feed feed = almostUsedUp();
        BreakTimer.paused(feed);
        bootCount(8);
        restartFacebook();
        BreakTimer.resumed(screen(Feed.class));
        pass(Duration.ofMinutes(9).toMillis());
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void switchingItOffEndsTheBreakAndTheCount() {
        onABreak();
        Settings.TAKE_BREAKS.save(false);
        BreakTimer.switched();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(BreakTimer.covering());

        Settings.TAKE_BREAKS.save(true);
        BreakTimer.switched();
        pass(Duration.ofMinutes(9).toMillis());
        assertFalse("the old count or break came back", BreakTimer.covering());
        pass(Duration.ofMinutes(1).toMillis());
        assertTrue("turning it on again didn't count", BreakTimer.covering());
    }

    @Test
    public void pausedHushfacebookEndsTheBreak() {
        onABreak();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        pass(1_000);
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void aScreenAReelStartedOnCountsAndIsCovered() {
        Page reels = screen(Page.class);
        BreakTimer.resumed(reels);
        BreakTimer.reelStarted();
        pass(600_000);
        assertTrue("reels didn't count", BreakTimer.covering());
    }

    @Test
    public void twentyDifferentReelsStartABreakBeforeTheUseTime() {
        reelsCounted();
        watchReels(0, 19);
        assertEquals(19, BreakTimer.reelsCounted());
        assertFalse("a break came before the 20th reel", BreakTimer.covering());
        watchReels(19, 1);
        assertTrue("20 reels didn't start a break", BreakTimer.covering());
        assertTrue(shows("5:00"));
        assertEquals("the reels didn't start over with the break", 0, BreakTimer.reelsCounted());
    }

    @Test
    public void reelsArentCountedWithTheirSwitchOff() {
        assertFalse("the reel switch starts on", Settings.BREAK_AFTER_REELS.get());
        BreakTimer.resumed(screen(Page.class));
        watchReels(0, 25);
        assertEquals(0, BreakTimer.reelsCounted());
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void aReelCountsOnceAndOnlyWhenItPlayedOn() {
        reelsCounted();
        reel("looped", true);
        pass(BreakTimer.REEL_WATCHED_AFTER_MS - 1);
        assertEquals("a reel counted before its check", 0, BreakTimer.reelsCounted());
        pass(1);
        assertEquals(1, BreakTimer.reelsCounted());
        for (int i = 0; i < 25; i++) {
            reel("looped", true);
            pass(BreakTimer.REEL_WATCHED_AFTER_MS);
        }
        assertEquals("a reel playing again counted again", 1, BreakTimer.reelsCounted());
        for (int i = 0; i < 25; i++) {
            reel("scrolled past " + i, false);
            pass(BreakTimer.REEL_WATCHED_AFTER_MS);
        }
        assertEquals("a reel scrolled past counted", 1, BreakTimer.reelsCounted());
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void theReelsStartOverAfterABreak() {
        reelsCounted();
        watchReels(0, 20);
        assertTrue(BreakTimer.covering());
        watchReels(100, 3);
        assertEquals("reels counted on a break", 0, BreakTimer.reelsCounted());
        pass(Duration.ofMinutes(5).toMillis());
        assertFalse(BreakTimer.covering());
        watchReels(0, 19);
        assertFalse("reels from before the break still counted", BreakTimer.covering());
        watchReels(19, 1);
        assertTrue(BreakTimer.covering());
    }

    @Test
    public void timeAwayTheSwitchesAndAPauseStartTheReelsOver() {
        Page reels = reelsCounted();
        watchReels(0, 10);
        BreakTimer.paused(reels);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        BreakTimer.resumed(reels);
        assertEquals("five minutes away didn't start the reels over", 0, BreakTimer.reelsCounted());

        watchReels(0, 10);
        Settings.BREAK_AFTER_REELS.save(false);
        pass(1_000);
        assertEquals("the reel switch off left the count", 0, BreakTimer.reelsCounted());
        Settings.BREAK_AFTER_REELS.save(true);

        watchReels(0, 10);
        Settings.TAKE_BREAKS.save(false);
        pass(1_000);
        assertEquals("Take breaks off left the reels' count", 0, BreakTimer.reelsCounted());
        Settings.TAKE_BREAKS.save(true);

        watchReels(0, 10);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        pass(1_000);
        assertEquals("a pause left the reels' count", 0, BreakTimer.reelsCounted());
        PauseForTests.resume();

        watchReels(0, 19);
        assertFalse("an old count came back", BreakTimer.covering());
    }

    @Test
    public void theReelsHoldAcrossARestartOfFacebookButNotOfThePhone() {
        bootCount(7);
        Page reels = reelsCounted();
        watchReels(0, 15);
        BreakTimer.paused(reels);
        restartFacebook();
        Page next = screen(Page.class);
        BreakTimer.resumed(next);
        watchReels(15, 4);
        assertEquals("a restart of Facebook lost the reels", 19, BreakTimer.reelsCounted());

        BreakTimer.paused(next);
        bootCount(8);
        restartFacebook();
        BreakTimer.resumed(screen(Page.class));
        watchReels(19, 1);
        assertEquals("reels from before a phone restart still counted", 1, BreakTimer.reelsCounted());
        assertFalse(BreakTimer.covering());
    }

    @Test
    public void aCheckFromBeforeTheReelsStartedOverCountsNothing() {
        reelsCounted();
        watchReels(0, 5);
        reel("late", true);
        pass(1_000);
        Settings.TAKE_BREAKS.save(false);
        BreakTimer.switched();
        shadowOf(Looper.getMainLooper()).idle();
        Settings.TAKE_BREAKS.save(true);
        BreakTimer.switched();
        pass(BreakTimer.REEL_WATCHED_AFTER_MS);
        assertEquals("a reel from the old count was counted", 0, BreakTimer.reelsCounted());
    }

    @Test
    public void reelsFromThePlayersOwnThreadAreCountedOnTheMainThread() throws Exception {
        Page reels = reelsCounted();
        for (int i = 0; i < BreakTimer.REELS_BEFORE_BREAK; i++) {
            String id = "reel" + i;
            Thread player = new Thread(() -> reel(id, true));
            player.start();
            player.join();
            if (i == 0) {
                assertFalse("the player's thread took the reel itself", BreakTimer.browsing(reels));
                shadowOf(Looper.getMainLooper()).idle();
                assertTrue("the main thread never took it", BreakTimer.browsing(reels));
            }
            pass(BreakTimer.REEL_WATCHED_AFTER_MS);
        }
        assertTrue(BreakTimer.covering());
    }

    @Test
    public void goToTheMenuOpensFacebooksMenuLink() {
        Feed feed = onABreak();
        button("Go to the Menu").performClick();
        Intent menu = shadowOf(feed).getNextStartedActivity();
        assertNotNull(menu);
        assertEquals(Intent.ACTION_VIEW, menu.getAction());
        assertEquals("fb://bookmarks", String.valueOf(menu.getData()));
        assertEquals("com.facebook.katana.IntentUriHandler", menu.getComponent().getClassName());
        assertFalse("asked the main screen for a tab its bar may not have", menu.hasExtra(FacebookTabs.TARGET_TAB_ID));
        assertTrue("a page under the main screen was left open", feed.isFinishing());
    }

    @Test
    public void closeAndBackLeaveTheFeed() {
        Feed feed = onABreak();
        button("Close Facebook").performClick();
        assertEquals(1, feed.sentBack);

        ShadowDialog.getLatestDialog().onBackPressed();
        assertTrue("Back left a page that isn't the main screen open", feed.isFinishing());
    }

    @Test
    public void theButtonsAreBigEnoughToTap() {
        Feed feed = onABreak();
        float density = feed.getResources().getDisplayMetrics().density;
        for (String label : Arrays.asList("Go to the Menu", "Close Facebook", "Break settings")) {
            assertTrue(label + " is under 48dp", button(label).getMinHeight() >= Math.round(48 * density));
        }
    }

    @Test
    public void theClockRoundsUpToTheSecond() {
        assertEquals("5:00", BreakTimer.clockText(300_000));
        assertEquals("4:01", BreakTimer.clockText(240_001));
        assertEquals("0:00", BreakTimer.clockText(0));
        assertEquals("0:00", BreakTimer.clockText(-5));
    }

    @Test
    public void choicesReadBackFromAFileOnly() {
        for (BreakTimer.Use use : BreakTimer.Use.values()) assertEquals(use, BreakTimer.Use.fromFile(use.fileValue));
        for (BreakTimer.Length length : BreakTimer.Length.values()) {
            assertEquals(length, BreakTimer.Length.fromFile(length.fileValue));
        }
        assertEquals(null, BreakTimer.Use.fromFile("TEN_MINUTES"));
        assertEquals(null, BreakTimer.Length.fromFile(5));
    }
}
