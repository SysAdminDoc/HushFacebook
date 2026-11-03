/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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

    @Test
    public void aRowsPhotoIsKnownByTheClassNameLithoViewsTextEndsWith() {
        String view = "com.facebook.litho.LithoView{36d1429 VFED..C.. ........ 0,0-950,760}";
        assertTrue(PhotoCarousel.isPhotoText(view + "<cls>X.0cNH</cls>", "X.0cNH"));
        assertTrue("a simple name Redex left alone", PhotoCarousel.isPhotoText(view + "X.0cNH", "X.0cNH"));
        assertFalse("the end card", PhotoCarousel.isPhotoText(view + "<cls>X.0cM6</cls>", "X.0cNH"));
        assertFalse("a longer name", PhotoCarousel.isPhotoText(view + "<cls>X.0cNHa</cls>", "X.0cNH"));
        assertFalse("a feed post", PhotoCarousel.isPhotoText(view + "LoggingComponent(NewsFeedFeedUnitComponent)", "X.0cNH"));
    }

    @Test
    public void theCurrentPhotoIsTheOneMostOfTheRowShows() {
        // The 585 row on a 1080 px screen: 950 px photos 974 px apart, the next one peeking in.
        assertEquals("the first photo, settled", 0,
                PhotoCarousel.current(new int[]{0, 974}, new int[]{950, 1924}, 2, 1080, 0, 4));
        assertEquals("most of the way to the second", 1,
                PhotoCarousel.current(new int[]{-600, 374}, new int[]{350, 1324}, 2, 1080, 0, 4));
        assertEquals("the items in any order", 1,
                PhotoCarousel.current(new int[]{374, -600}, new int[]{1324, 350}, 2, 1080, 0, 4));
        assertEquals("the last of ten, the one before it peeking", 9,
                PhotoCarousel.current(new int[]{-844, 130}, new int[]{106, 1080}, 2, 1080, 8, 10));
        assertEquals("never past the end", 3,
                PhotoCarousel.current(new int[]{0, 974}, new int[]{950, 1924}, 2, 1080, 3, 4));
        assertEquals("a row of one", -1, PhotoCarousel.current(new int[]{0}, new int[]{950}, 1, 1080, 0, 1));
        assertEquals("a row that reports nothing", -1,
                PhotoCarousel.current(new int[]{0, 974}, new int[]{950, 1924}, 2, 1080, -1, -1));
        assertEquals("nothing shown", -1, PhotoCarousel.current(new int[0], new int[0], 0, 1080, 0, 4));
    }

    @Test
    public void theCountReadsLikeTheReportersAndTheDotsFollowAlong() {
        assertEquals("4/10", PhotoCarousel.label(3, 10));
        assertEquals("1/2", PhotoCarousel.label(0, 2));

        assertEquals("all four fit", 0, PhotoCarousel.firstDot(3, 4, PhotoCarousel.MAX_DOTS));
        assertEquals("the start of a long row", 0, PhotoCarousel.firstDot(1, 50, PhotoCarousel.MAX_DOTS));
        assertEquals("the middle keeps the current dot centred", 17, PhotoCarousel.firstDot(20, 50, PhotoCarousel.MAX_DOTS));
        assertEquals("the end", 43, PhotoCarousel.firstDot(49, 50, PhotoCarousel.MAX_DOTS));
    }

    @Test
    public void eachPhotoShownIsNumberedLeftToRight() {
        Integer[] order = PhotoCarousel.byLeft(new int[]{974, -500, 0, 9999}, 3);
        assertEquals("only the shown places", 3, order.length);
        assertEquals("the one scrolled half out first", 1, (int) order[0]);
        assertEquals(2, (int) order[1]);
        assertEquals("the one peeking last", 0, (int) order[2]);
    }

    @Test
    public void aListThatDoesntScrollSidewaysGetsNothingDrawn() {
        Settings.PHOTO_CAROUSEL.save(true);
        FrameLayout list = new FrameLayout(RuntimeEnvironment.getApplication());
        list.addView(new View(list.getContext()));
        list.layout(0, 0, 1080, 2000);
        Bitmap bitmap = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888);
        PhotoCarousel.drawCount(list, new Canvas(bitmap));
        assertEquals("nothing drawn", 0, bitmap.getPixel(1000, 40));
        assertFalse(statusLine(), statusLine().contains(PhotoCarousel.COUNT_SHOWN));
        assertFalse(statusLine(), statusLine().contains("threw"));
    }
}
