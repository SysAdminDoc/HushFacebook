/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.facebook.inspiration.activity.InspirationCameraActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Show Reels in gray: on the Reels screen (a video filling the window) the content frame draws
 * through a layer whose paint takes the color out, so a red button comes out gray. A feed video, the
 * camera and the story viewer keep their colors, a swipe between reels keeps the gray, and leaving,
 * off and Pause bring the colors back. Native graphics, since the legacy RenderNode keeps no layer.
 *
 * <p>The page is laid out like a reel: the TextureView, the button column at the right with a red
 * button in it, all in one page in a pager.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GrayReelsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private View content;
    private FrameLayout page;
    private View redButton;

    @Before
    public void setUp() {
        Settings.GRAY_REELS.resetToDefault();
        Settings.GRAY_REELS_OVER.resetToDefault();
        Settings.REELS_MINUTES.resetToDefault();
        PauseForTests.resume();
        GrayReels.resetForTests();
        HookStatus.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);

        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        content = activity.findViewById(android.R.id.content);
        FrameLayout pager = new FrameLayout(activity);
        root.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page = new FrameLayout(activity);
        pager.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(new TextureView(activity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        redButton = new View(activity);
        redButton.setBackgroundColor(Color.RED);
        place(page, redButton, W - 140, 1500, W - 20, 1620);
        layout();
        controller.windowFocusChanged(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the window has focus", activity.getWindow().getDecorView().hasWindowFocus());
    }

    @After
    public void tearDown() {
        GrayReels.resetForTests();
        controller.close();
        Settings.GRAY_REELS.resetToDefault();
        Settings.GRAY_REELS_OVER.resetToDefault();
        Settings.REELS_MINUTES.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void offToStartAndNothingGraysOrListens() {
        assertFalse(Settings.GRAY_REELS.get());
        GrayReels.resumed(activity);
        after(GrayReels.KEEP_MS * 3);
        assertColor("off");
        assertFalse("no layout listener while off", GrayReels.watchingLayoutsForTests());
    }

    @Test
    public void theReelsScreenDrawsThroughTheGrayLayer() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on the Reels screen");
        assertTrue(GrayReels.watchingLayoutsForTests());
        assertTrue("the gray wasn't counted", reported(GrayReels.COUNT_GRAYED));
        int pixel = pixelOf(redButton);
        assertEquals("a red button came out with color: " + Integer.toHexString(pixel), Color.red(pixel), Color.green(pixel));
        assertEquals("a red button came out with color: " + Integer.toHexString(pixel), Color.green(pixel), Color.blue(pixel));
        assertTrue("the button went black or white rather than gray: " + Integer.toHexString(pixel),
                Color.red(pixel) > 20 && Color.red(pixel) < 235);
    }

    /** Positive control for the pixel check: with the switch off the same button draws red. */
    @Test
    public void offTheSameButtonDrawsRed() {
        GrayReels.resumed(activity);
        int pixel = pixelOf(redButton);
        assertEquals("the button isn't red with the switch off: " + Integer.toHexString(pixel), Color.RED, pixel | 0xFF000000);
    }

    /** The paint keeps each pixel's brightness and takes its color: every channel the same mix. */
    @Test
    public void theGrayPaintTakesTheColorOut() {
        Paint gray = GrayReels.GRAY;
        assertTrue(gray.getColorFilter() instanceof ColorMatrixColorFilter);
        ColorMatrix matrix = new ColorMatrix();
        ((ColorMatrixColorFilter) gray.getColorFilter()).getColorMatrix(matrix);
        float[] m = matrix.getArray();
        for (int column = 0; column < 5; column++) {
            assertEquals("red and green differ at " + column, m[column], m[5 + column], 1e-6f);
            assertEquals("green and blue differ at " + column, m[5 + column], m[10 + column], 1e-6f);
        }
        assertEquals("alpha is kept", 1f, m[18], 0f);
    }

    /** A feed video that takes 58% of the window isn't the Reels screen. */
    @Test
    public void aVideoThatDoesntFillTheScreenKeepsItsColors() {
        page.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.58f)));
        layout();
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertNull(BurnInGuard.reelOf(activity.getWindow().getDecorView()));
        after(GrayReels.KEEP_MS * 3);
        assertColor("a 58% video");
        assertFalse(reported(GrayReels.COUNT_GRAYED));
    }

    @Test
    public void theCameraKeepsItsColors() {
        ActivityController<InspirationCameraActivity> camera = Robolectric.buildActivity(InspirationCameraActivity.class).setup();
        try {
            Activity screen = camera.get();
            FrameLayout frame = new FrameLayout(screen);
            screen.setContentView(frame);
            frame.addView(new TextureView(screen), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View decor = screen.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, W, H);
            camera.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("the preview fills the screen", BurnInGuard.reelOf(decor));
            Settings.GRAY_REELS.save(true);

            GrayReels.resumed(screen);
            after(GrayReels.KEEP_MS * 2);

            assertEquals("the camera", View.LAYER_TYPE_NONE, screen.findViewById(android.R.id.content).getLayerType());
            assertFalse(GrayReels.grayForTests());
        } finally {
            camera.close();
        }
    }

    /** The story viewer's window shows the feed through it, and a story isn't a reel. */
    @Test
    public void aStoryKeepsItsColors() {
        ActivityController<Activity> story = Robolectric.buildActivity(Activity.class);
        story.get().setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        story.setup();
        try {
            Activity screen = story.get();
            FrameLayout frame = new FrameLayout(screen);
            screen.setContentView(frame);
            frame.addView(new TextureView(screen), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View decor = screen.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, W, H);
            story.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("the story's video fills the screen", BurnInGuard.reelOf(decor));
            Settings.GRAY_REELS.save(true);

            GrayReels.resumed(screen);
            after(GrayReels.KEEP_MS * 2);

            assertEquals("the story", View.LAYER_TYPE_NONE, screen.findViewById(android.R.id.content).getLayerType());
            assertFalse(GrayReels.grayForTests());
        } finally {
            story.close();
        }
    }

    /** Mid-swipe neither reel fills the screen: the gray stays through it, and goes once the feed has settled. */
    @Test
    public void aSwipeKeepsTheGrayAndLeavingTakesItOff() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on the Reels screen");

        page.setTranslationY(-H * 0.5f);
        relayout();
        assertGray("half of a reel, mid-swipe");
        page.setTranslationY(0f);
        relayout();
        after(BurnInGuard.SETTLE_MS * 2);
        assertGray("the next reel settled");

        page.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.58f)));
        relayout();
        assertGray("before the second look");
        after(BurnInGuard.SETTLE_MS + GrayReels.KEEP_MS);
        assertColor("back on the feed");
    }

    /** A sheet or dialog over the Reels screen takes the window's focus: the gray stays as it is. */
    @Test
    public void aSheetOverTheReelsScreenLeavesItGray() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on the Reels screen");
        controller.windowFocusChanged(false);
        after(GrayReels.KEEP_MS * 3);
        assertGray("under a sheet");
        controller.windowFocusChanged(true);
        focusBack();
        assertGray("the sheet gone");
    }

    @Test
    public void offOrPauseInTheSettingsBringsTheColorsBack() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on");

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        focusBack();
        assertColor("paused");
        assertFalse(GrayReels.watchingLayoutsForTests());

        PauseForTests.resume();
        focusBack();
        assertGray("on again after the pause");

        Settings.GRAY_REELS.save(false);
        focusBack();
        assertColor("off");
        assertFalse(GrayReels.watchingLayoutsForTests());
    }

    @Test
    public void leavingTheScreenBringsTheColorsBack() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on");
        GrayReels.paused(activity);
        assertColor("paused activity");
        assertFalse(GrayReels.watchingLayoutsForTests());
        after(GrayReels.KEEP_MS * 3);
        assertColor("still away");
    }

    /** A content frame that already draws through a layer of Facebook's own keeps it, and its paint. */
    @Test
    public void aLayerOfFacebooksOwnIsLeftAlone() {
        Paint own = new Paint();
        content.setLayerType(View.LAYER_TYPE_HARDWARE, own);
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        after(GrayReels.KEEP_MS * 2);
        assertEquals(View.LAYER_TYPE_HARDWARE, content.getLayerType());
        assertSame("Facebook's paint was swapped", own, ReflectionHelpers.getField(content, "mLayerPaint"));
        assertFalse(GrayReels.grayForTests());
        GrayReels.paused(activity);
        assertEquals("Facebook's layer was taken off", View.LAYER_TYPE_HARDWARE, content.getLayerType());
    }

    /**
     * A reel whose player is still on a SurfaceView (the one playing when the switch went on) keeps
     * its colors: graying around it would leave a color video under gray buttons.
     */
    @Test
    public void aReelOnASurfaceViewKeepsItsColors() {
        putASurfaceViewReel();
        layout();
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertNotNull("the reel fills the screen", BurnInGuard.reelOf(activity.getWindow().getDecorView()));
        after(GrayReels.KEEP_MS * 2);
        assertColor("a reel on a SurfaceView");
        assertFalse(reported(GrayReels.COUNT_GRAYED));
    }

    /** Swiping from a gray reel onto one still on a SurfaceView brings the colors back once it has settled. */
    @Test
    public void swipingOntoASurfaceViewReelBringsTheColorsBack() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on a TextureView reel");
        putASurfaceViewReel();
        relayout();
        assertGray("before the second look");
        after(BurnInGuard.SETTLE_MS + GrayReels.KEEP_MS);
        assertColor("the SurfaceView reel settled");
    }

    /** A sheet that opens mid-swipe doesn't use up the settle: once it's gone the gray waits the whole settle again. */
    @Test
    public void aSheetMidSwipeStartsTheSettleOver() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on the Reels screen");
        page.setTranslationY(-H * 0.5f);
        relayout();
        assertGray("half of a reel, mid-swipe");

        controller.windowFocusChanged(false);
        assertFalse("the sheet took the focus", activity.getWindow().getDecorView().hasWindowFocus());
        after(BurnInGuard.SETTLE_MS * 2);
        assertGray("under the sheet");

        controller.windowFocusChanged(true);
        focusBack();
        assertGray("the sheet gone, still half a reel");
        after(BurnInGuard.SETTLE_MS + GrayReels.KEEP_MS);
        assertColor("the swipe never finished");
    }

    /** A gray layer something took off goes back on at the next look. */
    @Test
    public void aGrayLayerTakenOffGoesBackOn() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on");
        content.setLayerType(View.LAYER_TYPE_NONE, null);
        after(GrayReels.KEEP_MS + 10);
        assertGray("the next look");
    }

    /** A software layer Facebook puts on while gray is Facebook's: it keeps it, and leaving doesn't take it off. */
    @Test
    public void aSoftwareLayerFacebookPutsOnWhileGrayStays() {
        Settings.GRAY_REELS.save(true);
        GrayReels.resumed(activity);
        assertGray("on");
        Paint own = new Paint();
        content.setLayerType(View.LAYER_TYPE_SOFTWARE, own);
        after(GrayReels.KEEP_MS + 10);
        assertEquals(View.LAYER_TYPE_SOFTWARE, content.getLayerType());
        assertSame("Facebook's paint was swapped", own, ReflectionHelpers.getField(content, "mLayerPaint"));
        assertFalse(GrayReels.grayForTests());
        GrayReels.paused(activity);
        assertEquals("Facebook's layer was taken off", View.LAYER_TYPE_SOFTWARE, content.getLayerType());
    }

    /** Turn gray over: from full color to gray in even steps over the minutes picked, at once by default. */
    @Test
    public void theSaturationFallsInStepsOverTheMinutesPicked() {
        assertEquals(GrayOver.AT_ONCE, Settings.GRAY_REELS_OVER.get());
        assertEquals(0f, GrayReels.saturation(0L, GrayOver.AT_ONCE), 0f);
        long step = 5 * 60_000L / GrayReels.STEPS;
        assertEquals(1f, GrayReels.saturation(0L, GrayOver.FIVE_MINUTES), 0f);
        assertEquals("a step came before its time", 1f, GrayReels.saturation(step - 1, GrayOver.FIVE_MINUTES), 0f);
        assertEquals(0.98f, GrayReels.saturation(step, GrayOver.FIVE_MINUTES), 1e-6f);
        assertEquals(0.5f, GrayReels.saturation(150_000L, GrayOver.FIVE_MINUTES), 1e-6f);
        assertEquals(0f, GrayReels.saturation(5 * 60_000L, GrayOver.FIVE_MINUTES), 0f);
        assertEquals(0f, GrayReels.saturation(Long.MAX_VALUE / 2, GrayOver.FIVE_MINUTES), 0f);
        assertEquals(0.5f, GrayReels.saturation(15 * 60_000L, GrayOver.THIRTY_MINUTES), 1e-6f);
    }

    /** Over five minutes: the red button starts red, is half gray at two and a half, and gray at five. */
    @Test
    public void overFiveMinutesTheReelsScreenGoesGrayAStepAtATime() {
        Settings.GRAY_REELS.save(true);
        Settings.GRAY_REELS_OVER.save(GrayOver.FIVE_MINUTES);
        GrayReels.resumed(activity);
        assertEquals(View.LAYER_TYPE_HARDWARE, content.getLayerType());
        assertSame(GrayReels.PART_GRAY, ReflectionHelpers.getField(content, "mLayerPaint"));
        assertEquals("the button isn't red at the start", Color.RED, pixelOf(redButton) | 0xFF000000);

        after(150_000L);
        int half = pixelOf(redButton);
        assertTrue("half way isn't half gray: " + Integer.toHexString(half),
                Color.red(half) > Color.green(half) + 60 && Color.green(half) > 10);
        assertEquals(Color.green(half), Color.blue(half));
        assertTrue("a step wasn't counted", reported(GrayReels.COUNT_STEP));

        after(151_000L);
        assertGray("after five minutes");
        int pixel = pixelOf(redButton);
        assertEquals(Color.red(pixel), Color.green(pixel));
    }

    /** A minute away keeps the steps without counting the minute; fifteen starts it over in color. */
    @Test
    public void timeAwayIsntCountedAndALongBreakStartsOver() {
        Settings.GRAY_REELS.save(true);
        Settings.GRAY_REELS_OVER.save(GrayOver.FIVE_MINUTES);
        GrayReels.resumed(activity);
        after(120_000L);
        long watched = GrayReels.watchedForTests();
        assertTrue("two minutes weren't counted: " + watched, watched >= 118_000L && watched <= 121_000L);

        GrayReels.paused(activity);
        assertColor("away");
        after(60_000L);
        GrayReels.resumed(activity);
        assertEquals("the minute away was counted", watched, GrayReels.watchedForTests());
        assertSame(GrayReels.PART_GRAY, ReflectionHelpers.getField(content, "mLayerPaint"));
        assertTrue("the steps were lost", Color.green(pixelOf(redButton)) > 10);

        after(200_000L);
        assertGray("gray again");
        GrayReels.paused(activity);
        after(GrayReels.AWAY_MS);
        GrayReels.resumed(activity);
        assertEquals("fifteen minutes away didn't start over", 0L, GrayReels.watchedForTests());
        assertEquals("the colors didn't come back", Color.RED, pixelOf(redButton) | 0xFF000000);
    }

    @Test
    public void theTimeReadsInWholeMinutes() {
        assertEquals("0 min", ReelsClock.label(0L));
        assertEquals("0 min", ReelsClock.label(59_999L));
        assertEquals("1 min", ReelsClock.label(60_000L));
        assertEquals("60 min", ReelsClock.label(3_600_000L));
        assertEquals("0 min", ReelsClock.label(-5L));
    }

    /**
     * Show time in Reels on its own: the pill shows at the top middle, darkening what's under it,
     * counts the minutes, and the screen keeps its colors. Leaving takes it off and gives that spot
     * back, and so does turning it off.
     */
    @Test
    public void theTimeShowsAtTheTopOfTheReelsScreenWithoutTheGray() {
        Settings.REELS_MINUTES.save(true);
        assertFalse(Settings.GRAY_REELS.get());
        GrayReels.resumed(activity);
        assertColor("the time alone");
        assertEquals("0 min", ReelsClock.shownForTests());
        assertTrue("the time wasn't counted", reported(ReelsClock.COUNT_SHOWN));
        assertTrue(GrayReels.watchingLayoutsForTests());

        Rect pill = ReelsClock.boundsForTests();
        assertFalse("the pill has no size", pill.isEmpty());
        assertTrue("the pill isn't in the middle: " + pill, Math.abs(pill.centerX() - W / 2) <= 1);
        assertTrue("the pill isn't at the top: " + pill, pill.top > 0 && pill.bottom < H / 4);
        int x = pill.left + Math.round(4 * activity.getResources().getDisplayMetrics().density);
        int y = pill.centerY();
        int dark = pixelAt(x, y);

        after(150_000L);
        assertEquals("2 min", ReelsClock.shownForTests());
        assertColor("two and a half minutes of the time alone");
        assertEquals("the red button lost its color", Color.RED, pixelOf(redButton) | 0xFF000000);

        GrayReels.paused(activity);
        assertNull("the time stayed after leaving", ReelsClock.shownForTests());
        int clear = pixelAt(x, y);
        assertTrue("the pill didn't darken its spot: " + Integer.toHexString(dark) + " over "
                + Integer.toHexString(clear), Color.red(dark) + 8 < Color.red(clear));

        GrayReels.resumed(activity);
        assertEquals("2 min", ReelsClock.shownForTests());
        assertEquals("the pill didn't come back where it was", dark, pixelAt(x, y));
        Settings.REELS_MINUTES.save(false);
        after(GrayReels.KEEP_MS + 10);
        assertNull("the time stayed with the switch off", ReelsClock.shownForTests());
        assertFalse("still listening with both switches off", GrayReels.watchingLayoutsForTests());
    }

    /** A reel still on a SurfaceView can't go gray, but its time still shows. */
    @Test
    public void theTimeShowsOverASurfaceViewReel() {
        putASurfaceViewReel();
        layout();
        Settings.GRAY_REELS.save(true);
        Settings.REELS_MINUTES.save(true);
        GrayReels.resumed(activity);
        assertColor("a reel on a SurfaceView");
        assertEquals("0 min", ReelsClock.shownForTests());
    }

    /** With both on, the screen goes gray and the time shows, from one count. */
    @Test
    public void withBothOnTheTimeAndTheGrayShareTheCount() {
        Settings.GRAY_REELS.save(true);
        Settings.GRAY_REELS_OVER.save(GrayOver.FIVE_MINUTES);
        Settings.REELS_MINUTES.save(true);
        GrayReels.resumed(activity);
        after(301_000L);
        assertGray("five minutes with both on");
        assertEquals("5 min", ReelsClock.shownForTests());
    }

    /** Swaps the page's TextureView for a SurfaceView, a reel whose player was built before the switch went on. */
    private void putASurfaceViewReel() {
        assertTrue(page.getChildAt(0) instanceof TextureView);
        page.removeViewAt(0);
        page.addView(new SurfaceView(activity), 0,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void assertGray(String why) {
        assertEquals(why + ": the layer", View.LAYER_TYPE_HARDWARE, content.getLayerType());
        assertSame(why + ": the paint", GrayReels.GRAY, ReflectionHelpers.getField(content, "mLayerPaint"));
        assertTrue(why, GrayReels.grayForTests());
    }

    private void assertColor(String why) {
        assertEquals(why + ": the layer", View.LAYER_TYPE_NONE, content.getLayerType());
        assertFalse(why, GrayReels.grayForTests());
    }

    /** The color at [x], [y] when the whole window is drawn, its overlay included. */
    private int pixelAt(int x, int y) {
        Bitmap shot = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        activity.getWindow().getDecorView().draw(new Canvas(shot));
        int pixel = shot.getPixel(x, y);
        shot.recycle();
        return pixel;
    }

    /** The color [view] shows at its middle when the whole window is drawn. */
    private int pixelOf(View view) {
        View decor = activity.getWindow().getDecorView();
        Bitmap shot = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(shot));
        int[] at = new int[2];
        view.getLocationInWindow(at);
        int pixel = shot.getPixel(at[0] + view.getWidth() / 2, at[1] + view.getHeight() / 2);
        shot.recycle();
        return pixel;
    }

    private boolean reported(String counted) {
        for (String line : HookStatus.report()) {
            if (line.contains(FamilyNames.REEL_DECLUTTER) && line.contains(counted)) return true;
        }
        return false;
    }

    private static void place(ViewGroup parent, View child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    /** Lets [millis] go by, running whatever the main thread had due in them. */
    private static void after(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** The window getting focus back, the way a sheet or the settings closing gives it. */
    private void focusBack() {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void relayout() {
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        after(BurnInGuard.LOOK_MS + 10);
    }
}
