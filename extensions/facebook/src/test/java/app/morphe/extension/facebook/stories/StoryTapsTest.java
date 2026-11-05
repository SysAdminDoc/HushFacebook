/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.res.Resources;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.EnumSet;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryTapsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        StoryTaps.forgetForTests();
        Settings.STORY_PLAY_BUTTON.save(false);
        Settings.STORY_DOUBLE_TAP.save(false);
    }

    @After
    public void restore() {
        Settings.STORY_PLAY_BUTTON.resetToDefault();
        Settings.STORY_DOUBLE_TAP.resetToDefault();
        Settings.BLOCK_STORY_AUTO_ADVANCE.resetToDefault();
        StoryAdvance.forgetForTests();
        StoryTaps.forgetForTests();
    }

    private static boolean tap(long down, float x) {
        MotionEvent event = StoryTapsForTests.tap(down, x, 400f);
        try {
            return StoryTaps.onTap(new Object(), event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void withBothOffEveryTapNavigatesAsFacebookDoes() {
        assertFalse(tap(1000, 160));
        assertFalse(tap(2000, 20));
    }

    @Test
    public void withDoubleTapASingleTapIsHeldAndOnlyTheSecondNavigates() {
        Settings.STORY_DOUBLE_TAP.save(true);
        assertTrue(tap(1000, 300));
        // Down 140 ms after the first lift, a little to the side.
        assertFalse(tap(1200, 305));
        // The pair is used up, so the next tap starts a new one.
        assertTrue(tap(1600, 300));
    }

    @Test
    public void aSecondTapTooLateOrTooFarAwayIsHeldAsANewFirstTap() {
        Settings.STORY_DOUBLE_TAP.save(true);
        assertTrue(tap(1000, 300));
        long late = 1060 + ViewConfiguration.getDoubleTapTimeout() + 1;
        assertTrue(tap(late, 300));
        float far = 300 - 101 * Resources.getSystem().getDisplayMetrics().density;
        assertTrue(tap(late + 160, far));
    }

    @Test
    public void oneTapReachingTheControllerTwiceGetsTheSameAnswer() {
        Settings.STORY_DOUBLE_TAP.save(true);
        MotionEvent event = StoryTapsForTests.tap(1000, 300, 400);
        try {
            assertTrue(StoryTaps.onTap(new Object(), event));
            assertTrue("not taken for a second tap", StoryTaps.onTap(new Object(), event));
        } finally {
            event.recycle();
        }
    }

    /** The viewer in front, with a pauser whose reasons can't be read, so the pause from here is what counts. */
    private static float viewerWithAPauser(Activity activity) {
        StoryTaps.pauserForTests = new Object();
        StoryTaps.reasonForTests = Reason.BUTTON;
        StoryTaps.activityResumed(activity);
        View decor = activity.getWindow().getDecorView();
        return decor.getWidth() > 0 ? decor.getWidth() : Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    @Test
    public void withOnlyThePlayButtonTheMiddleShowsItAndTheSidesStillNavigate() {
        Settings.STORY_PLAY_BUTTON.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        float width = viewerWithAPauser(activity);
        View decor = activity.getWindow().getDecorView();
        assertFalse(tap(1000, width * .1f));
        assertFalse(tap(2000, width * .9f));
        assertTrue(tap(3000, width * .5f));
        View button = decor.findViewWithTag(StoryTaps.TAG);
        assertNotNull(button);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals("Pause story", button.getContentDescription().toString());
        // Another tap in the middle while the story plays puts it away.
        assertTrue(tap(4000, width * .5f));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(View.GONE, button.getVisibility());
        // Leaving the viewer puts it away too.
        assertTrue(tap(5000, width * .5f));
        StoryTaps.activityPaused(activity);
        assertEquals(View.GONE, button.getVisibility());
    }

    @Test
    public void aSideTapAfterThePauseTakesItOffAndPutsTheButtonAway() {
        Settings.STORY_PLAY_BUTTON.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        float width = viewerWithAPauser(activity);
        assertTrue(tap(1000, width * .5f));
        View button = activity.getWindow().getDecorView().findViewWithTag(StoryTaps.TAG);
        assertTrue(button.performClick());
        assertTrue(StoryTaps.paused());
        assertEquals("Play story", button.getContentDescription().toString());
        assertFalse("Facebook changes stories", tap(2000, width * .9f));
        assertFalse("the next story plays", StoryTaps.paused());
        assertEquals(View.GONE, button.getVisibility());
    }

    /**
     * #380: a story Stop Story auto-advance holds at its end shows the button with the replay
     * symbol, which stays, and a tap on it starts the story again with any pause from here off.
     */
    @Test
    public void aStoryHeldAtItsEndShowsAReplayButtonThatStartsItAgain() {
        Settings.STORY_PLAY_BUTTON.save(true);
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        float width = viewerWithAPauser(activity);
        assertTrue(tap(1000, width * .5f));
        View button = activity.getWindow().getDecorView().findViewWithTag(StoryTaps.TAG);
        assertTrue("paused from here", button.performClick());
        assertTrue(StoryTaps.paused());

        int[] restarts = {0};
        StoryAdvance.restartForTests = () -> restarts[0]++;
        Object callback = new Object();
        StoryAdvance.held(callback);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals("Replay story", button.getContentDescription().toString());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals("it waits on the last frame", View.VISIBLE, button.getVisibility());

        assertTrue(button.performClick());
        assertEquals(1, restarts[0]);
        assertFalse("the pause came off", StoryTaps.paused());
        assertEquals("Pause story", button.getContentDescription().toString());
        StoryAdvance.progressed(0);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals("put away once the story plays", View.GONE, button.getVisibility());
    }

    @Test
    public void withDoubleTapAndNoButtonASingleTapReplaysAHeldStory() {
        Settings.STORY_DOUBLE_TAP.save(true);
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        viewerWithAPauser(activity);
        int[] restarts = {0};
        StoryAdvance.restartForTests = () -> restarts[0]++;
        Object callback = new Object();
        StoryAdvance.held(callback);
        assertTrue(tap(1000, 300));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(1, restarts[0]);
        assertFalse(StoryAdvance.finished());
        // A story that's playing pauses as before.
        assertTrue(tap(5000, 300));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(1, restarts[0]);
        assertTrue(StoryTaps.paused());
    }

    @Test
    public void withNothingToPauseWithTheMiddleNavigatesAsFacebookDoes() {
        Settings.STORY_PLAY_BUTTON.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        StoryTaps.activityResumed(activity);
        float width = Resources.getSystem().getDisplayMetrics().widthPixels;
        assertFalse(tap(1000, width * .5f));
        assertNull(activity.getWindow().getDecorView().findViewWithTag(StoryTaps.TAG));
    }

    @Test
    public void leavingTheViewerTakesThePauseOff() {
        Settings.STORY_PLAY_BUTTON.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        float width = viewerWithAPauser(activity);
        assertTrue(tap(1000, width * .5f));
        assertTrue(activity.getWindow().getDecorView().findViewWithTag(StoryTaps.TAG).performClick());
        assertTrue(StoryTaps.paused());
        StoryTaps.activityPaused(activity);
        assertFalse(StoryTaps.paused());
    }

    @Test
    public void aSecondTapThatMovesLetsTheFirstTapGoAhead() {
        Settings.STORY_DOUBLE_TAP.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        viewerWithAPauser(activity);
        assertTrue(tap(1000, 300));
        MotionEvent down = MotionEvent.obtain(1200, 1200, MotionEvent.ACTION_DOWN, 300, 400, 0);
        MotionEvent moved = MotionEvent.obtain(1200, 1280, MotionEvent.ACTION_UP, 500, 400, 0);
        try {
            assertFalse(StoryTaps.secondTap(down));
            // The second tap's touch down holds the first tap back.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(StoryTaps.paused());
            assertFalse("not a tap", StoryTaps.secondTap(moved));
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the first tap paused the story", StoryTaps.paused());
        } finally {
            down.recycle();
            moved.recycle();
        }
    }

    @Test
    public void aDoubleTapsSecondLiftCountsOnlyWhereTheFingerWentDown() {
        Settings.STORY_DOUBLE_TAP.save(true);
        MotionEvent down = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, 300, 400, 0);
        MotionEvent up = MotionEvent.obtain(1000, 1080, MotionEvent.ACTION_UP, 302, 401, 0);
        MotionEvent moved = MotionEvent.obtain(1000, 1080, MotionEvent.ACTION_UP, 380, 400, 0);
        try {
            assertFalse(StoryTaps.secondTap(down));
            assertTrue(StoryTaps.secondTap(up));
            assertFalse(StoryTaps.secondTap(moved));
            Settings.STORY_DOUBLE_TAP.save(false);
            assertFalse(StoryTaps.secondTap(up));
        } finally {
            down.recycle();
            up.recycle();
            moved.recycle();
        }
    }

    enum Reason { FINGER, BUTTON }

    static final class Pauser {
        final EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
    }

    @Test
    public void thePauseIsReadFromThePausersSetOfReasons() throws Exception {
        Pauser pauser = new Pauser();
        assertEquals(Boolean.FALSE, StoryTaps.reasonsHold(pauser, Reason.BUTTON));
        pauser.reasons.add(Reason.FINGER);
        assertEquals(Boolean.FALSE, StoryTaps.reasonsHold(pauser, Reason.BUTTON));
        pauser.reasons.add(Reason.BUTTON);
        assertEquals(Boolean.TRUE, StoryTaps.reasonsHold(pauser, Reason.BUTTON));
        assertNull("nothing to read", StoryTaps.reasonsHold(new Object(), Reason.BUTTON));
        assertNull("no reason to look for", StoryTaps.reasonsHold(pauser, null));
    }
}
