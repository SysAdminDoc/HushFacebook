/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoriesTrayCirclesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        StoriesTrayCircles.forgetForTests();
        Settings.STORIES_TRAY_CIRCLES.save(true);
        Settings.STORIES_TRAY_SIZE.save("");
    }

    @After
    public void restore() {
        Settings.STORIES_TRAY_CIRCLES.resetToDefault();
        Settings.STORIES_TRAY_SIZE.resetToDefault();
        StoriesTrayCircles.forgetForTests();
    }

    @Test
    public void aTileInTheStoriesRowComesOutRoundAtTheChosenSize() {
        Settings.STORIES_TRAY_SIZE.save("80");
        View tile = StoriesTrayCirclesForTests.tileInRow(StoriesTrayCirclesForTests.TRAY);
        float density = tile.getResources().getDisplayMetrics().density;
        assertTrue(tile.getClipToOutline());
        assertEquals(Math.min(1f, 80 * density / 332f), tile.getScaleX(), 0.001f);
        assertEquals(tile.getScaleX(), tile.getScaleY(), 0f);
        assertEquals(166f, tile.getPivotX(), 0.01f);
        assertEquals(317f, tile.getPivotY(), 0.01f);
    }

    @Test
    public void theCircleIsCutFromTheCardNotTheWiderTile() {
        Settings.STORIES_TRAY_SIZE.save("80");
        ViewGroup tile = StoriesTrayCirclesForTests.tileWithCard();
        float density = tile.getResources().getDisplayMetrics().density;
        Outline outline = new Outline();
        tile.getOutlineProvider().getOutline(tile, outline);
        Rect circle = new Rect();
        assertTrue("the outline is a circle", outline.getRect(circle));
        // The card is 320 by 590 at 6, 23: the circle is 320 across, inside the card's sides.
        assertEquals(new Rect(6, 158, 326, 478), circle);
        assertEquals(Math.min(1f, 80 * density / 320f), tile.getScaleX(), 0.001f);
        assertEquals(166f, tile.getPivotX(), 0.01f);
        assertEquals(318f, tile.getPivotY(), 0.01f);
    }

    @Test
    public void tilesComeOutOneCircleWideAndTall() {
        Settings.STORIES_TRAY_SIZE.save("80");
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        float density = context.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(80 * density), StoriesTrayCircles.tileWidth(context, 320));
        // Facebook's tiles are about 1.84 times as tall as wide: a round tile is square, so the row loses its blank band.
        assertEquals(203, StoriesTrayCircles.tileHeight(203, 374));
    }

    @Test
    public void switchedOffTilesKeepFacebooksSize() {
        Settings.STORIES_TRAY_CIRCLES.save(false);
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        assertEquals(320, StoriesTrayCircles.tileWidth(context, 320));
        assertEquals(590, StoriesTrayCircles.tileHeight(320, 590));
        assertEquals(320, StoriesTrayCircles.tileWidth(null, 320));
    }

    @Test
    public void anotherListIsLeftAlone() {
        View tile = StoriesTrayCirclesForTests.tileInRow(7);
        assertFalse(tile.getClipToOutline());
        assertEquals(1f, tile.getScaleX(), 0f);
    }

    @Test
    public void switchedOffTheRowIsUnchanged() {
        Settings.STORIES_TRAY_CIRCLES.save(false);
        View tile = StoriesTrayCirclesForTests.tileInRow(StoriesTrayCirclesForTests.TRAY);
        assertFalse(tile.getClipToOutline());
        assertEquals(1f, tile.getScaleX(), 0f);
    }

    @Test
    public void sizesStayInRange() {
        assertEquals(StoriesTrayCircles.DEFAULT_DP, StoriesTrayCircles.diameterDp(""));
        assertEquals(StoriesTrayCircles.DEFAULT_DP, StoriesTrayCircles.diameterDp(null));
        assertEquals(StoriesTrayCircles.DEFAULT_DP, StoriesTrayCircles.diameterDp("abc"));
        assertEquals(StoriesTrayCircles.MIN_DP, StoriesTrayCircles.diameterDp("5"));
        assertEquals(StoriesTrayCircles.MAX_DP, StoriesTrayCircles.diameterDp("999"));
        assertEquals(64, StoriesTrayCircles.diameterDp(" 64 "));
        assertEquals(1f, StoriesTrayCircles.scale(500, 332), 0f);
        assertEquals(0.5f, StoriesTrayCircles.scale(100, 200), 0f);
        assertEquals(1f, StoriesTrayCircles.scale(100, 0), 0f);
    }

    @Test
    public void aTileIsNotGivenTwoListeners() {
        View tile = StoriesTrayCirclesForTests.tileInRow(StoriesTrayCirclesForTests.TRAY);
        tile.layout(0, 0, 300, 600);
        tile.layout(0, 0, 332, 635);
        assertTrue(tile.getClipToOutline());
    }
}
