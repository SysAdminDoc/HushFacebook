/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

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
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Burn-in guard (#288): with a video filling the screen, what sits beside it on the way up to the
 * content frame dims after the Dim after wait, the video and the views holding it don't, a tap on the
 * video leaves the dim and a tap on a button or a swipe ends it. Off, Pause and a video that doesn't
 * fill the screen leave Facebook's views as they were.
 *
 * <p>The page is laid out like a reel: the TextureView, a layer over it the size of the video that
 * takes the taps that pause it, the button column at the right and the caption at the bottom, all
 * in one page in a pager, with a top bar beside the pager.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w384dp-h823dp-xxhdpi")
public class BurnInGuardTest {
    /** The Dim after choice to start with. */
    private static final long IDLE = BurnInWait.FIVE_SECONDS.millis;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FrameLayout pager;
    private FrameLayout page;
    private TextureView video;
    private View tapLayer;
    private FrameLayout column;
    private View like;
    private View caption;
    private View topBar;

    @Before
    public void setUp() {
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        PauseForTests.resume();
        BurnInGuard.resetForTests();
        HookStatus.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);

        root = new FrameLayout(activity);
        activity.setContentView(root);
        pager = new FrameLayout(activity);
        root.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page = new FrameLayout(activity);
        pager.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video = new TextureView(activity);
        page.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tapLayer = new View(activity);
        tapLayer.setOnClickListener(view -> { });
        page.addView(tapLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column = new FrameLayout(activity);
        place(page, column, W - 140, 1500, W - 20, 1860);
        like = button(column, 0, 0, 120, 120);
        caption = new View(activity);
        caption.setOnClickListener(view -> { });
        place(page, caption, 30, 1950, 630, 2050);
        topBar = new View(activity);
        place(root, topBar, 0, 0, W, 150);
        layout();
        controller.windowFocusChanged(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the window has focus", activity.getWindow().getDecorView().hasWindowFocus());
    }

    @After
    public void tearDown() {
        BurnInGuard.resetForTests();
        controller.close();
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void offToStartAndNothingDimsOrListens() {
        assertEquals(BurnInMode.OFF, Settings.BURN_IN_GUARD.get());
        Object before = activity.getWindow().getCallback();
        BurnInGuard.resumed(activity);
        after(IDLE * 3);
        assertUp("off");
        assertFalse("no layout listener while off", BurnInGuard.watchingLayoutsForTests());
        assertSame("no touch watcher while off", before, activity.getWindow().getCallback());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
    }

    @Test
    public void whatSitsBesideTheVideoDimsAndTheVideosOwnBranchDoesnt() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        assertTrue(BurnInGuard.watchingLayoutsForTests());
        after(IDLE - 100);
        assertUp("before five seconds");
        after(200);
        assertDimmed("after five seconds");
        assertEquals("the video's page", 1f, page.getAlpha(), 0f);
        assertEquals("the pager", 1f, pager.getAlpha(), 0f);
        assertEquals("the screen", 1f, root.getAlpha(), 0f);
        assertEquals("a button inside the column isn't dimmed twice", 1f, like.getAlpha(), 0f);
        assertEquals("the layer that takes the video's taps", BurnInGuard.IDLE_LEVEL, tapLayer.getAlpha(), 0f);
        assertEquals("Dim alone never moves the screen", 0f, root.getTranslationX(), 0f);
        assertTrue(reported(BurnInGuard.COUNT_DIMMED));
    }

    /** A feed video that takes 58% of the window isn't the Reels screen. */
    @Test
    public void aVideoThatDoesntFillTheScreenIsLeftAlone() {
        page.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.58f)));
        layout();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        Object before = activity.getWindow().getCallback();
        BurnInGuard.resumed(activity);
        assertNull(BurnInGuard.reelOf(activity.getWindow().getDecorView()));
        after(IDLE * 3);
        assertUp("a 58% video");
        assertSame("touches aren't watched off the Reels screen", before, activity.getWindow().getCallback());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
    }

    /** The camera's preview fills the screen as a reel does, and its shutter stays as it is (S22, 585). */
    @Test
    public void theCameraIsLeftAlone() {
        ActivityController<InspirationCameraActivity> camera = Robolectric.buildActivity(InspirationCameraActivity.class).setup();
        try {
            Activity screen = camera.get();
            FrameLayout frame = new FrameLayout(screen);
            screen.setContentView(frame);
            frame.addView(new TextureView(screen), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View shutter = new View(screen);
            shutter.setOnClickListener(view -> { });
            place(frame, shutter, W / 2 - 90, H - 400, W / 2 + 90, H - 220);
            View decor = screen.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, W, H);
            camera.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("the preview fills the screen", BurnInGuard.reelOf(decor));
            Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);

            BurnInGuard.resumed(screen);
            after(IDLE * 3);

            assertEquals("the shutter", 1f, shutter.getAlpha(), 0f);
            assertEquals("the screen stays put", 0f, frame.getTranslationX(), 0f);
            assertEquals(-1L, BurnInGuard.nextPassAtForTests());
        } finally {
            camera.close();
        }
    }

    /** The story viewer's window shows the feed through it, so a step would uncover a strip of the feed (#369). */
    @Test
    public void aSeeThroughScreenDimsButNeverMoves() {
        ActivityController<Activity> story = Robolectric.buildActivity(Activity.class);
        story.get().setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        story.setup();
        try {
            Activity screen = story.get();
            assertTrue(BurnInGuard.seeThrough(screen));
            assertFalse("the Reels screen isn't see-through", BurnInGuard.seeThrough(activity));
            FrameLayout frame = new FrameLayout(screen);
            screen.setContentView(frame);
            frame.addView(new TextureView(screen), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View reply = new View(screen);
            reply.setOnClickListener(view -> { });
            place(frame, reply, 30, H - 300, W - 30, H - 150);
            View decor = screen.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, W, H);
            story.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("the story's video fills the screen", BurnInGuard.reelOf(decor));
            Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);

            BurnInGuard.resumed(screen);
            View content = screen.findViewById(android.R.id.content);
            // Once around the nine places the Reels screen would move to.
            for (int place = 0; place < 9; place++) {
                after(BurnInGuard.SHIFT_EVERY_MS);
                assertEquals("the story stays put", 0f, content.getTranslationX(), 0f);
                assertEquals("the story stays put", 0f, content.getTranslationY(), 0f);
            }
            assertEquals("its reply bar still dims", BurnInGuard.IDLE_LEVEL, reply.getAlpha(), 0f);
        } finally {
            story.close();
        }
    }

    /**
     * A wide video opened from the feed plays small on a page of Facebook's full-screen viewer, a page
     * the pager's own size, and the same video in a post of the feed is left alone (#370). The pager
     * is a list holding just that video, so it doesn't scroll (585 on the emulator).
     */
    @Test
    public void aWideVideoOnAFullScreenViewerPageDimsAndInAPostDoesnt() {
        FrameLayout screen = new FrameLayout(activity);
        activity.setContentView(screen);
        RecyclerView pagerView = new RecyclerView(activity, false);
        screen.addView(pagerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout onePage = new FrameLayout(activity);
        pagerView.addView(onePage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextureView wide = new TextureView(activity);
        place(onePage, wide, 0, H / 3, W, H / 3 + W * 9 / 16);
        View share = button(onePage, W - 140, H - 600, W - 20, H - 480);
        View bar = new View(activity);
        place(screen, bar, 0, 0, W, 150);
        layout();
        View decor = activity.getWindow().getDecorView();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);

        assertSame("the page is the viewer's own size", wide, BurnInGuard.reelOf(decor));
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertEquals("the viewer's buttons", BurnInGuard.IDLE_LEVEL, share.getAlpha(), 0f);
        assertEquals("the viewer's top bar", BurnInGuard.IDLE_LEVEL, bar.getAlpha(), 0f);
        assertEquals("the video", 1f, wide.getAlpha(), 0f);

        // A post in the feed: a page of the list, shorter than it.
        BurnInGuard.paused(activity);
        onePage.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.8f)));
        layout();
        assertNull(BurnInGuard.reelOf(decor));
        BurnInGuard.resumed(activity);
        after(IDLE * 3);
        assertEquals("a post's buttons", 1f, share.getAlpha(), 0f);
        assertEquals("the feed's top bar", 1f, bar.getAlpha(), 0f);

        // The same page in a frame that's neither a list nor scrolls isn't a viewer's page.
        BurnInGuard.paused(activity);
        screen.removeView(pagerView);
        pagerView.removeView(onePage);
        FrameLayout frame = new FrameLayout(activity);
        screen.addView(frame, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(onePage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        assertNull("a frame the page's size", BurnInGuard.reelOf(decor));
    }

    @Test
    public void aTapOnTheVideoLeavesThemDimmedAndATapOnAButtonBringsThemBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");

        tapOn(video);
        assertDimmed("a tap on the video to pause it");
        tapOn(video);
        tapOn(video);
        assertDimmed("a double tap on the video to like it");
        assertTrue(reported(BurnInGuard.COUNT_QUIET));
        tapLayer.setClickable(false);
        tapOn(video);
        assertDimmed("a tap where nothing takes taps");
        tapLayer.setClickable(true);

        tapOn(like);
        assertUp("Like");
        after(IDLE + 100);
        assertDimmed("idle again");
        tapOn(caption);
        assertUp("the caption");
    }

    @Test
    public void aTapOnTheVideoDoesntStartTheWaitOver() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE - 1_000);
        tapOn(video);
        assertUp("still waiting");
        after(1_100);
        assertDimmed("the wait the tap didn't start over ran out");
    }

    /** A swipe to the next reel counts from the moment it moves farther than a tap does. */
    @Test
    public void aSwipeOnTheVideoBringsThemBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        float[] at = middleOf(video);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        sendAt(MotionEvent.ACTION_MOVE, at[0], at[1] + 1);
        assertDimmed("a finger that hasn't moved past a tap");
        sendAt(MotionEvent.ACTION_MOVE, at[0], at[1] - 150);
        assertUp("a swipe");
        after(IDLE * 2);
        assertUp("a held swipe is a touch");
        sendAt(MotionEvent.ACTION_UP, at[0], at[1] - 150);
        after(IDLE - 100);
        assertUp("the wait starts at the lift");
        after(200);
        assertDimmed("the wait after the swipe ran out");
    }

    /**
     * Stay dim when I tap: Like, the caption, a swipe and a sheet closing all leave the controls
     * dimmed, and no tap starts the wait over. With the switch off a tap on Like brings them back
     * again, and leaving the Reels screen always does.
     */
    @Test
    public void stayDimKeepsThemDimmedWhateverYouTapOrSwipe() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        Settings.BURN_IN_STAY_DIM.save(true);
        BurnInGuard.resumed(activity);
        after(IDLE - 1_000);
        tapOn(like);
        assertUp("still waiting");
        after(1_100);
        assertDimmed("the wait a tap on Like didn't start over ran out");

        tapOn(like);
        assertDimmed("Like");
        tapOn(caption);
        assertDimmed("the caption");
        float[] at = middleOf(video);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        sendAt(MotionEvent.ACTION_MOVE, at[0], at[1] - 150);
        assertDimmed("a swipe to the next reel");
        sendAt(MotionEvent.ACTION_UP, at[0], at[1] - 150);
        after(IDLE * 2);
        assertDimmed("after the swipe");
        focusBack();
        assertDimmed("a sheet closing");
        assertTrue(reported(BurnInGuard.COUNT_STAY_DIM));

        Settings.BURN_IN_STAY_DIM.save(false);
        tapOn(like);
        assertUp("switch off, Like brings them back");
        after(IDLE + 100);
        assertDimmed("idle again");
        Settings.BURN_IN_STAY_DIM.save(true);

        root.removeView(pager);
        relayout();
        assertEquals("a moment off the Reels screen keeps the dim", BurnInGuard.IDLE_LEVEL, topBar.getAlpha(), 0f);
        after(BurnInGuard.SETTLE_MS);
        assertEquals("leaving the Reels screen brings the top bar back", 1f, topBar.getAlpha(), 0f);
        after(IDLE * 2);
        assertEquals("and nothing dims off it", 1f, topBar.getAlpha(), 0f);
    }

    /**
     * While dimmed, a page the pager brings in is dimmed as the layout that adds it ends, before
     * it's drawn: whole when it holds no video yet, and only its buttons when it holds one.
     */
    @Test
    public void aPageScrolledInWhileDimmedIsDimmedBeforeItsFirstFrame() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");

        FrameLayout bare = new FrameLayout(activity);
        place(pager, bare, 0, H, W, 2 * H);
        FrameLayout next = new FrameLayout(activity);
        place(pager, next, 0, H, W, 2 * H);
        TextureView nextVideo = new TextureView(activity);
        next.addView(nextVideo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View nextButtons = new View(activity);
        place(next, nextButtons, W - 140, 1500, W - 20, 1860);
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("a page with no video yet", BurnInGuard.IDLE_LEVEL, bare.getAlpha(), 0f);
        assertEquals("a page with its video stays", 1f, next.getAlpha(), 0f);
        assertEquals("its video", 1f, nextVideo.getAlpha(), 0f);
        assertEquals("its buttons", BurnInGuard.IDLE_LEVEL, nextButtons.getAlpha(), 0f);

        tapOn(like);
        assertEquals("a touch brings the bare page back", 1f, bare.getAlpha(), 0f);
        assertEquals("and the next page's buttons", 1f, nextButtons.getAlpha(), 0f);
    }

    @Test
    public void offOrPauseInTheSettingsPutsEverythingBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        BurnInGuard.resumed(activity);
        after(BurnInGuard.SHIFT_EVERY_MS);
        assertDimmed("idle");

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        focusBack();
        assertUp("paused");
        View content = activity.findViewById(android.R.id.content);
        assertEquals(0f, content.getTranslationX(), 0f);
        assertEquals(0f, content.getTranslationY(), 0f);
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        after(BurnInGuard.SHIFT_EVERY_MS);
        assertUp("still paused");

        PauseForTests.resume();
        focusBack();
        assertTrue("on again after the pause", BurnInGuard.watchingLayoutsForTests());
        assertNotSame("touches are watched", activity, activity.getWindow().getCallback());
        after(IDLE + 100);
        assertDimmed("idle after the pause");

        Settings.BURN_IN_GUARD.save(BurnInMode.OFF);
        focusBack();
        assertUp("off");
        assertEquals(0f, content.getTranslationX(), 0f);
        assertEquals(0f, content.getTranslationY(), 0f);
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        after(IDLE * 3);
        assertUp("still off");
    }

    @Test
    public void facebooksOwnAlphaIsWhatTheDimIsTakenFromAndAChangeItMakesIsKept() {
        column.setAlpha(0.8f);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertEquals(0.8f * BurnInGuard.IDLE_LEVEL, column.getAlpha(), 0f);
        tapOn(like);
        assertEquals("back to Facebook's own", 0.8f, column.getAlpha(), 0f);

        after(IDLE + 100);
        column.setAlpha(0.5f);
        tapOn(like);
        assertEquals("Facebook's change while dimmed stays", 0.5f, column.getAlpha(), 0f);
    }

    /** What Facebook brings back while dimmed is dimmed again before the next frame, so it never flashes. */
    @Test
    public void whatFacebookBringsBackIsDimmedBeforeTheNextFrame() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");
        column.setAlpha(1f);
        assertFalse("the frame is still drawn",
                activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw());
        assertDimmed("the frame Facebook's alpha would show in");
        tapOn(like);
        assertUp("a touch still brings it back to Facebook's own");
    }

    @Test
    public void dimAndShiftMovesTheScreenAStepEveryTwoMinutesAndPutsItBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        float step = BurnInGuard.STEP_DP * activity.getResources().getDisplayMetrics().density;
        // Start right at a place's first moment, so the next one is two minutes away.
        long now = SystemClock.uptimeMillis();
        after(BurnInGuard.SHIFT_EVERY_MS - now % BurnInGuard.SHIFT_EVERY_MS);
        BurnInGuard.resumed(activity);
        View content = activity.findViewById(android.R.id.content);
        int[] at = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertEquals(at[0] * step, content.getTranslationX(), 0.01f);
        assertEquals(at[1] * step, content.getTranslationY(), 0.01f);

        after(BurnInGuard.SHIFT_EVERY_MS);
        int[] next = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertTrue("a different place", next[0] != at[0] || next[1] != at[1]);
        assertEquals(next[0] * step, content.getTranslationX(), 0.01f);
        assertEquals(next[1] * step, content.getTranslationY(), 0.01f);
        assertDimmed("two minutes untouched");

        BurnInGuard.paused(activity);
        assertEquals("leaving the front puts it back", 0f, content.getTranslationX(), 0f);
        assertEquals(0f, content.getTranslationY(), 0f);
        assertUp("leaving the front");
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
    }

    @Test
    public void theShiftKeepsATranslationSomethingElseWrote() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        View content = activity.findViewById(android.R.id.content);
        BurnInGuard.resumed(activity);
        content.setTranslationY(40f);
        BurnInGuard.paused(activity);
        assertEquals("not Hushfacebook's to put back", 40f, content.getTranslationY(), 0f);
    }

    @Test
    public void atOnceDimsThemAsSoonAsTheFingerLifts() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        Settings.BURN_IN_WAIT.save(BurnInWait.AT_ONCE);
        BurnInGuard.resumed(activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertDimmed("at once");
        float[] at = middleOf(like);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        assertUp("a finger on Like");
        after(IDLE);
        assertUp("held");
        sendAt(MotionEvent.ACTION_UP, at[0], at[1]);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertDimmed("lifted");
    }

    /**
     * The story eye sits on the decor view beside the content frame (HushGram#479's screenshot): it
     * dims and comes back with the rest, and a view of Facebook's or the system's there is left alone.
     */
    @Test
    public void hushfacebooksOwnButtonOnTheDecorDimsWithTheControls() {
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        View eye = new View(activity);
        eye.setOnClickListener(view -> { });
        place(decor, eye, W - 160, 200, W - 40, 320);
        View systemBar = new View(activity);
        place(decor, systemBar, 0, 0, W, 60);
        BurnInGuard.overlay(eye);
        layout();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");
        assertEquals("the eye", BurnInGuard.IDLE_LEVEL, eye.getAlpha(), 0f);
        assertEquals("a decor child that isn't Hushfacebook's", 1f, systemBar.getAlpha(), 0f);
        tapOn(like);
        assertUp("a tap on Like");
        assertEquals("the eye comes back", 1f, eye.getAlpha(), 0f);
        eye.setVisibility(View.INVISIBLE);
        after(IDLE + 100);
        assertDimmed("idle again");
        eye.setVisibility(View.VISIBLE);
        assertEquals("a hidden eye isn't dimmed", 1f, eye.getAlpha(), 0f);
    }

    @Test
    public void theWatcherPassesTouchesOnToTheActivity() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        int[] seen = new int[1];
        root.setOnTouchListener((view, event) -> {
            seen[0]++;
            return true;
        });
        tapLayer.setClickable(false);
        BurnInGuard.resumed(activity);
        assertNotSame("touches are watched", activity, activity.getWindow().getCallback());
        tapOn(video);
        assertEquals("the down and the up both reached the screen", 2, seen[0]);
    }

    private void assertDimmed(String why) {
        assertEquals(why + ": the button column", BurnInGuard.IDLE_LEVEL, column.getAlpha(), 0f);
        assertEquals(why + ": the caption", BurnInGuard.IDLE_LEVEL, caption.getAlpha(), 0f);
        assertEquals(why + ": the top bar", BurnInGuard.IDLE_LEVEL, topBar.getAlpha(), 0f);
        assertEquals(why + ": the video itself", 1f, video.getAlpha(), 0f);
    }

    private void assertUp(String why) {
        assertEquals(why + ": the button column", 1f, column.getAlpha(), 0f);
        assertEquals(why + ": the caption", 1f, caption.getAlpha(), 0f);
        assertEquals(why + ": the top bar", 1f, topBar.getAlpha(), 0f);
        assertEquals(why + ": the tap layer", 1f, tapLayer.getAlpha(), 0f);
    }

    private boolean reported(String counted) {
        for (String line : HookStatus.report()) {
            if (line.contains(FamilyNames.REEL_DECLUTTER) && line.contains(counted)) return true;
        }
        return false;
    }

    private View button(ViewGroup parent, int left, int top, int right, int bottom) {
        View button = new View(activity);
        button.setOnClickListener(view -> { });
        place(parent, button, left, top, right, bottom);
        return button;
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

    /** A finger event at [x, y] in the window, delivered to the decor view, which hands it to the window's callback. */
    private void sendAt(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static float[] middleOf(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new float[]{at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f};
    }

    private void tapOn(View view) {
        float[] at = middleOf(view);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        sendAt(MotionEvent.ACTION_UP, at[0], at[1]);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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
