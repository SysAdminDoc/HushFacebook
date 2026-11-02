/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Photo carousel (#345): with the switch on, the swipeable row's feed check answers yes and the
 * three layouts a poster picks are passed over, so the row takes every post it can draw. The other
 * album layouts keep their answers, and off, paused or before the settings every answer is
 * Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PhotoCarouselTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PHOTO_CAROUSEL.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PHOTO_CAROUSEL + ":")) return line;
        }
        return "";
    }

    @Test
    public void theSwitchStartsOffAndOffEveryAnswerIsFacebooks() {
        assertFalse("the switch doesn't start off", Settings.PHOTO_CAROUSEL.get());
        assertFalse("posts already in the feed change as they're drawn again", Settings.PHOTO_CAROUSEL.rebootApp);
        assertTrue(PatchFamily.PHOTO_CAROUSEL.switches.contains(Settings.PHOTO_CAROUSEL));
        assertFalse("Facebook keeps the row out of this feed", PhotoCarousel.carousel(0));
        assertTrue("Facebook lets it in", PhotoCarousel.carousel(1));
        for (String layout : PhotoCarouselForTests.POSTER_LAYOUT_NAMES) {
            assertFalse(layout, PhotoCarousel.skipLayout(layout));
        }
        assertFalse(statusLine(), statusLine().contains(PhotoCarousel.LET_IN));
    }

    @Test
    public void onTheRowIsLetInAndThePostersLayoutsArePassedOver() {
        Settings.PHOTO_CAROUSEL.save(true);
        assertTrue("let in where Facebook keeps it out", PhotoCarousel.carousel(0));
        assertTrue(PhotoCarousel.carousel(1));
        assertTrue(statusLine(), statusLine().contains(PhotoCarousel.LET_IN + " 1"));

        for (String layout : PhotoCarouselForTests.POSTER_LAYOUT_NAMES) {
            assertTrue(layout, PhotoCarousel.skipLayout(layout));
        }
        assertTrue(statusLine(), statusLine().contains(PhotoCarousel.PASSED_OVER + " 3"));

        assertFalse("the row itself", PhotoCarousel.skipLayout(PhotoCarouselForTests.CAROUSEL));
        assertFalse("sensitive photos keep their cover", PhotoCarousel.skipLayout(PhotoCarouselForTests.SENSITIVE));
        assertFalse("Facebook's grid", PhotoCarousel.skipLayout(PhotoCarouselForTests.GRID));
        assertFalse("a name that only holds a poster's layout", PhotoCarousel.skipLayout(PhotoCarouselForTests.POSTER_LAYOUT_NAMES[0] + "Old"));
        assertFalse(PhotoCarousel.skipLayout(null));
        assertTrue(statusLine(), statusLine().contains(PhotoCarousel.PASSED_OVER + " 3"));
    }

    @Test
    public void pausedOrBeforeTheSettingsEveryAnswerIsFacebooks() {
        Settings.PHOTO_CAROUSEL.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(why);
            assertFalse("paused by " + why, PhotoCarousel.carousel(0));
            assertTrue("paused by " + why, PhotoCarousel.carousel(1));
            assertFalse("paused by " + why, PhotoCarousel.skipLayout(PhotoCarouselForTests.POSTER_LAYOUT_NAMES[1]));
            PauseForTests.resume();
        }

        boolean[] answers = new boolean[2];
        SettingsContextRule.withoutContext(() -> {
            answers[0] = PhotoCarousel.carousel(0);
            answers[1] = PhotoCarousel.skipLayout(PhotoCarouselForTests.POSTER_LAYOUT_NAMES[2]);
        });
        assertFalse("before the settings", answers[0]);
        assertFalse("before the settings", answers[1]);
        assertFalse("nothing was let in", statusLine().contains(PhotoCarousel.LET_IN));
    }
}
