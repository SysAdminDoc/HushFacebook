/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A reel's place as its screen comes back (#44): Facebook seeks it to 0 then, from the window and
 * from another app alike. That seek is dropped while the screen is away and just after it returns.
 * The same seek later, as a swipe does it, a drag to the start, a seek elsewhere, another player,
 * the switch off or a pause leave Facebook's seek alone. So does the seek of a reel that just
 * ended, which is its loop.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PictureInPicturePlaceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Facebook's enum of seek triggers: only the names count. */
    private enum Trigger { BY_SHORT_FORM_VIDEO_INVISIBLE, BY_GROOT_TRANSITION, BY_AUTOPLAY, BY_USER, BY_PLAYER }

    private final Object player = new Object();
    private final Object other = new Object();
    private final PictureInPicture.Arrivals arrivals = new PictureInPicture.Arrivals();
    private Activity screen;

    @Before
    public void start() {
        HookStatus.clear();
        PictureInPicture.forget();
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.PICTURE_IN_PICTURE.save(true);
        PictureInPicture.armed(screen, player, null);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PICTURE_IN_PICTURE.resetToDefault();
        PictureInPicture.forget();
        HookStatus.clear();
    }

    private void tripAway() {
        arrivals.onActivityPaused(screen);
        arrivals.onActivityResumed(screen);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PICTURE_IN_PICTURE + ":")) return line;
        }
        return null;
    }

    @Test
    public void everyRewindIsDroppedAsTheScreenComesBack() {
        tripAway();
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_GROOT_TRANSITION, player, 0));
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_AUTOPLAY, player, 0));
        assertNotNull(statusLine());
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + PictureInPicture.PLACE_KEPT + " 3"));
    }

    @Test
    public void aRewindWhileTheScreenIsAwayIsDropped() {
        arrivals.onActivityPaused(screen);
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
    }

    @Test
    public void aRewindStaysDroppedHoweverLongTheScreenWasAway() {
        arrivals.onActivityPaused(screen);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5));
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_GROOT_TRANSITION, player, 0));
        arrivals.onActivityResumed(screen);
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_GROOT_TRANSITION, player, 0));
    }

    @Test
    public void aReelThatEndsInTheWindowIsLeftToLoop() {
        arrivals.onActivityPaused(screen);
        PictureInPicture.playerEnded(player);
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_AUTOPLAY, player, 0));
        assertNotNull(statusLine());
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + PictureInPicture.LOOP_LEFT + " 1"));
    }

    @Test
    public void aReelThatEndsJustAfterItsReturnIsLeftToLoop() {
        tripAway();
        PictureInPicture.playerEnded(player);
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_AUTOPLAY, player, 0));
    }

    @Test
    public void aRewindLongAfterTheEndIsStillDropped() {
        arrivals.onActivityPaused(screen);
        PictureInPicture.playerEnded(player);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PictureInPicture.LOOP_MS));
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_AUTOPLAY, player, 0));
    }

    @Test
    public void anotherPlayerEndingKeepsTheReelsPlace() {
        arrivals.onActivityPaused(screen);
        PictureInPicture.playerEnded(other);
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_AUTOPLAY, player, 0));
        PictureInPicture.playerEnded(null);
        assertTrue(PictureInPicture.keepPlace(Trigger.BY_AUTOPLAY, player, 0));
    }

    @Test
    public void aSwipeLaterStillRewindsTheReelItLeaves() {
        tripAway();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PictureInPicture.RETURN_MS));
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
    }

    @Test
    public void aSwipeWithNoTripAwayIsLeftAlone() {
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
        arrivals.onActivityResumed(screen);
        assertFalse("a resume with no pause isn't a return",
                PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
    }

    @Test
    public void aDragToTheStartIsLeftAlone() {
        tripAway();
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_USER, player, 0));
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_PLAYER, player, 0));
    }

    @Test
    public void aSeekElsewhereIsLeftAlone() {
        tripAway();
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_GROOT_TRANSITION, player, 5000));
    }

    @Test
    public void anotherPlayerIsLeftAlone() {
        tripAway();
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, other, 0));
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, null, 0));
    }

    @Test
    public void anotherScreenPausingLeavesTheArmedScreenInPlace() {
        Activity page = Robolectric.buildActivity(Activity.class).setup().get();
        arrivals.onActivityPaused(page);
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
    }

    @Test
    public void theSwitchOffLeavesFacebooksSeekAlone() {
        tripAway();
        Settings.PICTURE_IN_PICTURE.save(false);
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
    }

    @Test
    public void aPauseLeavesFacebooksSeekAlone() {
        tripAway();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(PictureInPicture.keepPlace(Trigger.BY_SHORT_FORM_VIDEO_INVISIBLE, player, 0));
    }

    @Test
    public void aTriggerThatIsNoEnumIsLeftAlone() {
        tripAway();
        assertFalse(PictureInPicture.keepPlace("BY_GROOT_TRANSITION", player, 0));
        assertFalse(PictureInPicture.keepPlace(null, player, 0));
    }
}
