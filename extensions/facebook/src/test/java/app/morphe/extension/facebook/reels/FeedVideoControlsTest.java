/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.media.ResumePlaybackForTests;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1080dp-h2400dp-mdpi")
public class FeedVideoControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> window;
    private Activity activity;
    private FrameLayout root;
    private RecyclerView list;
    private FrameLayout row;
    private final Player player = new Player();
    private final List<Boolean> commands = new ArrayList<>();
    private final List<String> casts = new ArrayList<>();
    private final List<Integer> seeks = new ArrayList<>();
    private boolean fail;

    private static final class Media { String id = "video"; boolean ordinary = true; }
    private static final class Player {
        ViewGroup view;
        Media params = new Media();
        String origin = "newsfeed::feed_story";
        boolean playing;
        int position, length, positionReads;
    }

    @Before public void setUp() {
        FeedVideoControls.reset();
        FeedVideoControls.inBuildForTests = true;
        FeedVideoControls.access = new FeedVideoControls.Players() {
            public ViewGroup view(Object of) {
                if (fail) throw new IllegalStateException("unavailable player");
                return ((Player) of).view;
            }
            public Object params(Object of) { return ((Player) of).params; }
            public Object origin(Object of) { return ((Player) of).origin; }
            public String videoId(Object params) { return ((Media) params).id; }
            public boolean eligible(Object params) { return ((Media) params).ordinary; }
            public boolean playing(Object of) { return ((Player) of).playing; }
            public void command(Object of, boolean play) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper());
                assertTrue(of == player);
                commands.add(play);
                ((Player) of).playing = play;
            }
            public void cast(View from, String videoId) {
                assertTrue(from == FeedVideoControls.showingCast(player));
                casts.add(videoId);
            }
            public int position(Object of) {
                if (fail) throw new IllegalStateException("unavailable player");
                ((Player) of).positionReads++;
                return ((Player) of).position;
            }
            public int length(Object of, Object params) { return ((Player) of).length; }
            public void seek(Object of, int positionMs) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper());
                assertTrue(of == player);
                seeks.add(positionMs);
            }
        };
        Settings.TAP_TO_PLAY.save(false);
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(true);
        window = Robolectric.buildActivity(Activity.class).setup();
        activity = window.get();
        root = new FrameLayout(activity);
        list = new RecyclerView(activity, true);
        place(root, list, 0, 100, 1080, 2100);
        row = new FrameLayout(activity);
        place(list, row, 0, 0, 1080, 1100);
        player.view = container(row, 300);
        place(player.view, new SurfaceView(activity), 0, 0, 1080, 600);
        activity.setContentView(root);
        layout();
        window.windowFocusChanged(true);
        attach(player);
    }

    @After public void restore() {
        FeedVideoControls.reset();
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        ResumePlaybackForTests.forget();
        Settings.INLINE_FEED_VIDEO_CONTROLS.resetToDefault();
        Settings.FEED_VIDEO_CAST_BUTTON.resetToDefault();
        Settings.FEED_VIDEO_SEEK_BAR.resetToDefault();
        Settings.TAP_TO_PLAY.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        PauseForTests.resume();
        HookStatus.clear();
        if (window != null) window.pause().stop().destroy();
    }

    private FrameLayout container(ViewGroup into, int top) {
        FrameLayout container = new FrameLayout(activity);
        place(into, container, 0, top, 1080, 600);
        return container;
    }

    private static void place(ViewGroup parent, View child, int left, int top, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    private void layout() {
        activity.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }

    private void attach(Player of) {
        FeedVideoControls.attached(of.view, of);
        drain();
    }

    private static void drain() { shadowOf(Looper.getMainLooper()).idle(); }

    private View button() { return FeedVideoControls.showing(player); }

    private View cast() { return FeedVideoControls.showingCast(player); }

    private void castOn() {
        FeedVideoControls.videoPatchInBuildForTests = true;
        Settings.FEED_VIDEO_CAST_BUTTON.save(true);
        FeedVideoControls.settingsChanged();
        drain();
    }

    private SeekBar bar() { return FeedVideoControls.showingBar(player); }

    /** A minute long video a quarter of the way through, and the bar laid out on it. */
    private void barOn() {
        player.length = 60000;
        player.position = 15000;
        Settings.FEED_VIDEO_SEEK_BAR.save(true);
        FeedVideoControls.settingsChanged();
        drain();
        layout();
    }

    private void refreshAfterLayout() {
        layout();
        FeedVideoControls.changed(player);
        drain();
    }

    private static void tick() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)); }

    private static void touch(View view, int action, int x) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, view.getHeight() / 2f, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private static int[] on(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at;
    }

    private View paint(ViewGroup into, int top, int height) {
        View view = new View(activity);
        view.setBackgroundColor(0xFF000000);
        place(into, view, 0, top, 1080, height);
        return view;
    }

    private static FrameLayout.LayoutParams at(View button) {
        return (FrameLayout.LayoutParams) button.getLayoutParams();
    }

    @Test public void seekBarRunsJustAboveTheVideosBottomEdgeAndFollowsThePlayer() {
        assertNull("it starts off", bar());
        barOn();
        SeekBar bar = bar();
        assertNotNull(bar);
        assertEquals("Feed video position", bar.getContentDescription());
        assertTrue(bar.getParent() == button().getParent());
        assertEquals(0, on(bar)[0]);
        assertEquals(1080, bar.getWidth());
        assertEquals(FeedVideoControls.BAR_DP, bar.getHeight());
        assertEquals("it leaves the video's bottom corners to Facebook's buttons",
                on(player.view)[1] + 600 - FeedVideoControls.BAR_LIFT_DP, on(bar)[1] + bar.getHeight());
        assertEquals(60000, bar.getMax());
        assertEquals(15000, bar.getProgress());
        player.position = 30000;
        tick();
        assertEquals(30000, bar.getProgress());
        player.position = 90000;
        tick();
        assertEquals("a position past the end stays on the bar", 60000, bar.getProgress());
        assertTrue("following the player moves nothing", seeks.isEmpty());
        assertTrue(commands.isEmpty());
    }

    @Test public void aDragMovesOnlyTheBarUntilItsLetGoThenSeeksOnceAndLeavesPlaybackAlone() {
        barOn();
        Settings.RESUME_LONG_VIDEOS.save(true);
        SeekBar bar = bar();
        Bitmap frame = Bitmap.createBitmap(1080, 100, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(frame));
        touch(bar, MotionEvent.ACTION_DOWN, 270);
        touch(bar, MotionEvent.ACTION_MOVE, 540);
        assertTrue("the video waits for the let go", seeks.isEmpty());
        assertEquals(30000, bar.getProgress());
        player.position = 16000;
        tick();
        assertEquals("the player's position leaves a drag alone", 30000, bar.getProgress());
        refreshAfterLayout();
        layout();
        assertEquals("a drag has room for its time", FeedVideoControls.BAR_DP + FeedVideoControls.TIME_DP, bar.getHeight());
        assertEquals("and the line stays where it was",
                on(player.view)[1] + 600 - FeedVideoControls.BAR_LIFT_DP, on(bar)[1] + bar.getHeight());
        bar.draw(new Canvas(frame));
        touch(bar, MotionEvent.ACTION_UP, 810);
        assertEquals(java.util.Arrays.asList(45000), seeks);
        assertTrue("Resume long videos leaves a video you placed alone", ResumePlaybackForTests.placedBeforeStart(player));
        assertTrue("a paused video stays paused", commands.isEmpty());
        assertFalse(player.playing);
        refreshAfterLayout();
        layout();
        assertEquals(FeedVideoControls.BAR_DP, bar.getHeight());
    }

    @Test public void theButtonsAndBarStayOnTheVideoWhenTheContentFrameHasPadding() {
        castOn();
        barOn();
        assertEquals("below the channel picture", FeedVideoControls.TOP_DP, on(button())[1] - on(player.view)[1]);
        assertEquals(FeedVideoControls.TOP_DP, on(cast())[1] - on(player.view)[1]);
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.setPadding(20, 128, 0, 0);
        refreshAfterLayout();
        layout();
        assertEquals("the status bar's padding changes nothing", FeedVideoControls.TOP_DP, on(button())[1] - on(player.view)[1]);
        assertEquals(FeedVideoControls.TOP_DP, on(cast())[1] - on(player.view)[1]);
        assertEquals(on(player.view)[0], on(bar())[0]);
        assertEquals(on(player.view)[1] + 600 - FeedVideoControls.BAR_LIFT_DP, on(bar())[1] + bar().getHeight());
    }

    @Test public void aTapOnTheBarSeeksThereOnce() {
        barOn();
        touch(bar(), MotionEvent.ACTION_DOWN, 540);
        touch(bar(), MotionEvent.ACTION_UP, 540);
        assertEquals(java.util.Arrays.asList(30000), seeks);
    }

    @Test public void theBarWaitsForASeekToLandInsteadOfSpringingBack() {
        barOn();
        SeekBar bar = bar();
        touch(bar, MotionEvent.ACTION_DOWN, 270);
        touch(bar, MotionEvent.ACTION_UP, 810);
        assertEquals(java.util.Arrays.asList(45000), seeks);
        player.position = 16000;
        tick();
        assertEquals("the player still reads the old spot", 45000, bar.getProgress());
        player.position = 45300;
        tick();
        assertEquals(45300, bar.getProgress());
        player.position = 5000;
        tick();
        assertEquals("once landed it follows the player again", 5000, bar.getProgress());
    }

    @Test public void aSeekThatNeverLandsLetsTheBarGoBackToThePlayer() {
        barOn();
        SeekBar bar = bar();
        touch(bar, MotionEvent.ACTION_DOWN, 270);
        touch(bar, MotionEvent.ACTION_UP, 810);
        player.position = 16000;
        tick();
        assertEquals(45000, bar.getProgress());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedVideoControls.LANDING_MS));
        assertEquals(16000, bar.getProgress());
    }

    @Test public void aCancelledDragDoesntSeekAndTheBarGoesBackToThePlayer() {
        barOn();
        SeekBar bar = bar();
        touch(bar, MotionEvent.ACTION_DOWN, 540);
        touch(bar, MotionEvent.ACTION_CANCEL, 540);
        assertTrue(seeks.isEmpty());
        tick();
        assertEquals(15000, bar.getProgress());
    }

    @Test public void aScreenReadersScrollSeeksOnce() {
        barOn();
        assertTrue(bar().performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
        assertEquals(java.util.Arrays.asList(18000), seeks);
    }

    @Test public void seekBarStandsAloneAndNeedsItsSwitchTheBuildAndNoPause() {
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
        barOn();
        assertNull(button());
        assertNotNull(bar());
        Settings.FEED_VIDEO_SEEK_BAR.save(false);
        drain();
        assertNull(bar());
        int reads = player.positionReads;
        tick();
        assertEquals("the tick stops with it", reads, player.positionReads);
        barOn();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(bar());
        PauseForTests.resume();
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            FeedVideoControls.settingsChanged();
            drain();
            assertNull(bar());
        });
        barOn();
        assertNotNull(bar());
        FeedVideoControls.inBuildForTests = false;
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(bar());
        assertTrue(seeks.isEmpty());
    }

    @Test public void aLetGoAfterTheVideoChangedNeverSeeksTheNewOne() {
        barOn();
        SeekBar old = bar();
        touch(old, MotionEvent.ACTION_DOWN, 540);
        player.params = new Media();
        touch(old, MotionEvent.ACTION_UP, 540);
        assertTrue(seeks.isEmpty());
        assertNull(bar());
        FeedVideoControls.changed(player);
        drain();
        layout();
        SeekBar next = bar();
        assertNotNull(next);
        assertFalse(next == old);
        touch(next, MotionEvent.ACTION_DOWN, 540);
        FeedVideoControls.rebound(player);
        touch(next, MotionEvent.ACTION_UP, 540);
        assertTrue(seeks.isEmpty());
        drain();
        layout();
        touch(bar(), MotionEvent.ACTION_DOWN, 540);
        touch(bar(), MotionEvent.ACTION_UP, 540);
        assertEquals(java.util.Arrays.asList(30000), seeks);
    }

    @Test public void aVideoWithNoLengthYetHidesTheBarUntilItKnows() {
        barOn();
        SeekBar bar = bar();
        int[] at = on(bar);
        player.length = 0;
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        assertFalse(FeedVideoControls.barAt(activity.getWindow().getDecorView(), at[0] + 540, at[1] + 12));
        player.length = 60000;
        tick();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(60000, bar.getMax());
    }

    @Test public void aPlayerThatFailsTakesTheBarOff() {
        barOn();
        fail = true;
        tick();
        assertNull(bar());
        assertTrue(seeks.isEmpty());
    }

    @Test public void liveVideosAdsAndOtherOriginsGetNoBar() {
        barOn();
        player.params.ordinary = false;
        FeedVideoControls.changed(player);
        drain();
        assertNull(bar());
        player.params.ordinary = true;
        FeedVideoControls.changed(player);
        drain();
        assertNotNull(bar());
        player.origin = "fb_shorts_tab::fb_shorts_viewer";
        FeedVideoControls.changed(player);
        drain();
        assertNull(bar());
    }

    @Test public void aVideoCutOffAtTheBottomKeepsItsButtonsAndGetsTheBarWhenItsEdgeShows() {
        barOn();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) row.getLayoutParams();
        params.topMargin = 1500;
        row.setLayoutParams(params);
        refreshAfterLayout();
        assertNull(bar());
        assertNotNull("the buttons need only the top", button());
        params.topMargin = 0;
        row.setLayoutParams(params);
        refreshAfterLayout();
        assertNotNull(bar());
    }

    private static WindowInsets gestureStrip(int height) {
        return new WindowInsets.Builder().setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, height)).build();
    }

    @Test public void aBarInTheScreensGestureStripStepsAsideUntilItsEdgeIsClear() {
        barOn();
        int below = bar().getRootView().getHeight() - (on(player.view)[1] + 600 - FeedVideoControls.BAR_LIFT_DP);
        FeedVideoControls.insetsForTests = gestureStrip(below);
        refreshAfterLayout();
        assertNotNull("ending right where the strip starts is fine", bar());
        FeedVideoControls.insetsForTests = gestureStrip(below + 1);
        refreshAfterLayout();
        assertNull("a pixel into it can't be dragged", bar());
        assertNotNull("the buttons need only the top", button());
        FeedVideoControls.insetsForTests = gestureStrip(below);
        refreshAfterLayout();
        assertNotNull(bar());
    }

    @Test public void theWholeBarKeepsADragFromTheSystemsSideSwipes() {
        barOn();
        SeekBar bar = bar();
        assertEquals(java.util.Collections.singletonList(new Rect(0, 0, 1080, FeedVideoControls.BAR_DP)), bar.getSystemGestureExclusionRects());
        touch(bar, MotionEvent.ACTION_DOWN, 270);
        touch(bar, MotionEvent.ACTION_MOVE, 540);
        refreshAfterLayout();
        layout();
        assertEquals("a drag's taller bar stays covered",
                java.util.Collections.singletonList(new Rect(0, 0, 1080, FeedVideoControls.BAR_DP + FeedVideoControls.TIME_DP)),
                bar.getSystemGestureExclusionRects());
    }

    @Test public void aSurfaceTallerThanItsContainerEndsAtTheContainersEdge() {
        player.view.removeAllViews();
        place(player.view, new TextureView(activity), 0, 0, 1080, 1067);
        layout();
        barOn();
        assertNotNull(bar());
        assertEquals(on(player.view)[1] + 600 - FeedVideoControls.BAR_LIFT_DP, on(bar())[1] + bar().getHeight());
    }

    @Test public void somethingOverTheBottomEdgeTakesTheBarOffUntilItMoves() {
        barOn();
        View tabs = paint(root, on(bar())[1] - on(root)[1], 120);
        refreshAfterLayout();
        assertNull(bar());
        assertNotNull(button());
        tabs.setVisibility(View.GONE);
        refreshAfterLayout();
        assertNotNull(bar());
    }

    @Test public void onlyWhatShowsAcrossTheEdgeCountsNotAFrameAroundIt() {
        barOn();
        FrameLayout frame = new FrameLayout(activity);
        place(root, frame, 0, 0, 1080, 2300);
        place(root, new View(activity), 0, 0, 1080, 2300);
        paint(frame, 2000, 100);
        refreshAfterLayout();
        assertNotNull("an empty frame and a bare spacer", bar());
        paint(frame, 900, 200);
        refreshAfterLayout();
        assertNull("what the frame holds", bar());
    }

    @Test public void debugLoggingNamesWhatLiesOverTheEdgeOnce() {
        barOn();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        paint(root, on(bar())[1] - on(root)[1], 120);
        for (int pass = 0; pass < 3; pass++) refreshAfterLayout();
        String log = LogBufferManager.buildExportText();
        assertEquals(log, 1, occurrences(log, "Feed video seek bar: none, something lies over its bottom edge android.view.View"));
    }

    @Test public void barAtAnswersForTheBarAndAHoldThereIsntAHoldOnTheVideo() {
        barOn();
        View decor = activity.getWindow().getDecorView();
        int[] at = new int[2];
        bar().getLocationOnScreen(at);
        assertTrue(FeedVideoControls.barAt(decor, at[0] + 540, at[1] + 12));
        assertFalse(FeedVideoControls.barAt(decor, at[0] + 540, at[1] - 1));
        assertFalse("another window", FeedVideoControls.barAt(root, at[0] + 540, at[1] + 12));
        FeedVideoHold.reset();
        try {
            FeedVideoHold.inBuildForTests = true;
            FeedVideoHold.access = new FeedVideoHold.Players() {
                @Override public ViewGroup view(Object of) { return player.view; }
                @Override public float speed(Object of) { return 1f; }
                @Override public void speed(Object of, float speed) { }
            };
            FeedVideoHold.attached(player.view, player);
            assertNull(FeedVideoHold.videoAt(decor, at[0] + 540, at[1] + 12));
            assertTrue("just above the bar it's the video", FeedVideoHold.videoAt(decor, at[0] + 540, at[1] - 1) == player);
        } finally {
            FeedVideoHold.reset();
        }
    }

    @Test public void castButtonSitsAcrossFromPlayAndHandsOverTheVideoOnScreen() {
        assertNull("the cast button starts off", cast());
        castOn();
        assertNotNull(cast());
        assertEquals("Cast this video", cast().getContentDescription());
        assertTrue(cast().getParent() == button().getParent());
        // 1080 px wide surface at mdpi: a 40 px button 4 px in from each side, same row (#301).
        assertEquals(4, at(button()).leftMargin);
        assertEquals(1080 - 4 - 40, at(cast()).leftMargin);
        assertEquals(40, at(cast()).width);
        assertEquals(40, at(button()).height);
        assertEquals(at(button()).topMargin, at(cast()).topMargin);
        cast().performClick();
        assertEquals(java.util.Arrays.asList("video"), casts);
        assertTrue("a cast tap never plays or pauses", commands.isEmpty());
        button().performClick();
        assertEquals(java.util.Arrays.asList(true), commands);
        assertEquals(1, casts.size());
    }

    @Test public void castButtonStandsAloneWhenPlayPauseIsOff() {
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
        castOn();
        assertNull(button());
        assertNotNull(cast());
        cast().performClick();
        assertEquals(java.util.Arrays.asList("video"), casts);
    }

    @Test public void castButtonNeedsItsSwitchTheVideoPatchAndNoPause() {
        castOn();
        View old = cast();
        Settings.FEED_VIDEO_CAST_BUTTON.save(false);
        old.performClick();
        drain();
        assertNull(cast());
        assertNotNull("play/pause stays", button());
        castOn();
        FeedVideoControls.videoPatchInBuildForTests = false;
        FeedVideoControls.settingsChanged();
        drain();
        assertNull("no player record to hand over without the video patch", cast());
        castOn();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(cast());
        PauseForTests.resume();
        castOn();
        FeedVideoControls.inBuildForTests = false;
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(cast());
        assertTrue(casts.isEmpty());
    }

    @Test public void oldCastClickNeverSendsAnotherVideo() {
        castOn();
        View old = cast();
        player.params.id = "another video";
        old.performClick();
        assertTrue(casts.isEmpty());
        assertNull(cast());
        FeedVideoControls.changed(player);
        drain();
        View next = cast();
        assertNotNull(next);
        player.params = new Media();
        FeedVideoControls.rebound(player);
        next.performClick();
        assertTrue(casts.isEmpty());
        drain();
        cast().performClick();
        assertEquals(java.util.Arrays.asList("video"), casts);
    }

    @Test public void aSurfaceTooNarrowForTwoButtonsGetsNeitherButOneFits() {
        player.view.removeAllViews();
        place(player.view, new SurfaceView(activity), 0, 0, 90, 600);
        layout();
        castOn();
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        assertNull(cast());
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
        drain();
        assertNotNull(cast());
        assertEquals(90 - 4 - 40, at(cast()).leftMargin);
    }

    @Test public void buttonStartsPausesAndResumesWithoutChangingNativeTaps() {
        assertNotNull(button());
        assertEquals("Play feed video", button().getContentDescription());
        button().performClick();
        assertEquals("Pause feed video", button().getContentDescription());
        button().performClick();
        button().performClick();
        assertEquals(java.util.Arrays.asList(true, false, true), commands);
        List<String> nativeActions = new ArrayList<>();
        player.view.setOnClickListener(v -> nativeActions.add("fullscreen"));
        View comment = new View(activity);
        comment.setOnClickListener(v -> nativeActions.add("comments"));
        place(row, comment, 0, 950, 200, 100);
        player.view.performClick();
        comment.performClick();
        assertEquals(java.util.Arrays.asList("fullscreen", "comments"), nativeActions);
        assertEquals(3, commands.size());
    }

    @Test public void nativePlaybackChangesUpdateButtonWithAutoplaySettingIndependent() {
        Settings.TAP_TO_PLAY.save(true);
        player.playing = true;
        FeedVideoControls.changed(player);
        drain();
        assertEquals("Pause feed video", button().getContentDescription());
        player.playing = false;
        FeedVideoControls.changed(player);
        drain();
        assertEquals("Play feed video", button().getContentDescription());
        assertTrue(commands.isEmpty());
    }

    @Test public void oldClickCannotControlAReboundVideoEvenBeforeRefresh() {
        View old = button();
        player.params = new Media();
        FeedVideoControls.rebound(player);
        old.performClick();
        assertTrue(commands.isEmpty());
        drain();
        assertNotNull(button());
        assertFalse(old == button());
        button().performClick();
        assertEquals(java.util.Arrays.asList(true), commands);
    }

    @Test public void paramsChangedWithoutANotificationStillRejectOldClick() {
        View old = button();
        player.params = new Media();
        old.performClick();
        assertTrue(commands.isEmpty());
        assertNull(button());
    }

    @Test public void changedMediaIdRejectsAClickEvenWithTheSameParamsObject() {
        View old = button();
        player.params.id = "another video";
        old.performClick();
        assertTrue(commands.isEmpty());
        assertNull(button());
    }

    @Test public void switchStartsOffAndRestoresTheNativeFeed() {
        Settings.INLINE_FEED_VIDEO_CONTROLS.resetToDefault();
        drain();
        assertFalse(Settings.INLINE_FEED_VIDEO_CONTROLS.get());
        assertNull(button());
    }

    @Test public void recycledPlayerUsesItsCurrentContainer() {
        View old = button();
        FrameLayout next = container(row, 100);
        place(next, new TextureView(activity), 0, 0, 1080, 500);
        FeedVideoControls.attached(next, player);
        player.view = next;
        old.performClick();
        assertTrue(commands.isEmpty());
        layout();
        drain();
        assertNotNull(button());
        assertFalse(old == button());
        button().performClick();
        assertEquals(java.util.Arrays.asList(true), commands);
    }

    @Test public void sameBoundPlayerRestoresItsButtonAfterScrollingOutAndBack() {
        View old = button();
        ViewGroup container = player.view;
        row.removeView(container);
        drain();
        assertNull(button());
        old.performClick();
        assertTrue(commands.isEmpty());
        place(row, container, 0, 300, 1080, 600);
        layout();
        drain();
        assertNotNull(button());
        assertFalse(old == button());
        button().performClick();
        assertEquals(java.util.Arrays.asList(true), commands);
    }

    @Test public void playerBoundBeforeWindowAttachmentGetsControlsWhenItAppears() {
        row.removeView(player.view);
        drain();
        FeedVideoControls.attached(player.view, player);
        drain();
        assertNull(button());
        place(row, player.view, 0, 300, 1080, 600);
        layout();
        drain();
        assertNotNull(button());
        button().performClick();
        assertEquals(java.util.Arrays.asList(true), commands);
    }

    @Test public void releaseRetiresOldParamsUntilAnotherAttach() {
        View old = button();
        FeedVideoControls.released(player);
        old.performClick();
        drain();
        assertNull(button());
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        attach(player);
        assertNotNull(button());
        assertTrue(commands.isEmpty());
    }

    @Test public void offPauseUnreadyAndAbsentPatchRemoveControlsAndRejectOldClicks() {
        View old = button();
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
        old.performClick();
        drain();
        assertNull(button());
        assertTrue(commands.isEmpty());
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(true);
        drain();
        assertNotNull(button());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(button());
        PauseForTests.resume();
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            FeedVideoControls.settingsChanged();
            drain();
            assertNull(button());
        });
        FeedVideoControls.inBuildForTests = false;
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(button());
    }

    @Test public void aHeldPlayerWithNoSurfaceYetGetsItsButtonOnTheContainer() {
        // Tap to play holds a feed video before it draws, so its surface may not exist yet (#239).
        player.view.removeAllViews();
        FeedVideoControls.changed(player);
        drain();
        assertNotNull(button());
        assertEquals(4, at(button()).leftMargin);
        button().performClick();
        assertEquals(java.util.Arrays.asList(true), commands);
        player.view.setAlpha(0f);
        FeedVideoControls.changed(player);
        drain();
        assertNull("a container that shows nothing", button());
    }

    @Test public void aContainerHoldingAnotherPlayerGetsNoButtonOfItsOwn() {
        player.view.removeAllViews();
        Player inner = new Player();
        inner.view = new FrameLayout(activity);
        place(player.view, inner.view, 0, 0, 1080, 600);
        layout();
        attach(inner);
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
    }

    @Test public void debugLoggingNamesWhyAVideoHasNoButtonOncePerReason() {
        player.origin = "fb_shorts_tab::fb_shorts_viewer";
        FeedVideoControls.changed(player);
        drain();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        for (int pass = 0; pass < 3; pass++) {
            FeedVideoControls.changed(player);
            drain();
        }
        String log = LogBufferManager.buildExportText();
        assertEquals("a video already without one is named once Debug logging is on", 1,
                occurrences(log, "Inline feed button: none, origin fb_shorts_tab::fb_shorts_viewer"));
        player.origin = "newsfeed::feed_story";
        player.params.ordinary = false;
        FeedVideoControls.changed(player);
        drain();
        log = LogBufferManager.buildExportText();
        assertEquals(log, 1, occurrences(log, "Inline feed button: none, no video id, or a live video, ad, audio or GIF"));
        assertEquals(log, 1, occurrences(log, "Inline feed button: none, origin"));
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    @Test public void ambiguousSurfacesGetNoButton() {
        player.view.removeAllViews();
        place(player.view, new TextureView(activity), 0, 0, 500, 500);
        place(player.view, new SurfaceView(activity), 500, 0, 500, 500);
        layout();
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        assertTrue(commands.isEmpty());
    }

    @Test public void twoCurrentPlayersCannotOwnTheSameSurface() {
        Player other = new Player();
        other.view = player.view;
        attach(other);
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        assertNull(FeedVideoControls.showing(other));
    }

    @Test public void wrongOriginAdsLiveAudioAndMissingMediaAreRefused() {
        player.origin = "fb_shorts_tab::fb_shorts_viewer";
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        player.origin = "newsfeed::feed_story";
        player.params.ordinary = false;
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        player.params.ordinary = true;
        player.params.id = "";
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        player.params = null;
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
    }

    @Test public void detachedHiddenAndFailedOwnershipRemoveAnExistingButton() {
        player.view.setVisibility(View.GONE);
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        player.view.setVisibility(View.VISIBLE);
        layout();
        FeedVideoControls.changed(player);
        drain();
        assertNotNull(button());
        fail = true;
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        fail = false;
        attach(player);
        assertNotNull(button());
        row.removeView(player.view);
        drain();
        assertNull(button());
    }

    @Test public void aRowRecycledDuringALayoutPassTakesTheButtonsOffAfterThePass() {
        castOn();
        barOn();
        View play = button();
        View cast = cast();
        View bar = bar();
        assertNotNull(play);
        assertNotNull(cast);
        assertNotNull(bar);
        ViewGroup content = activity.findViewById(android.R.id.content);
        assertEquals(4, content.getChildCount());
        // Like the feed list recycling a video row in its own onLayout, while the content frame lays out its children.
        Runnable[] recycle = { () -> row.removeView(player.view) };
        View lister = new View(activity) {
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                Runnable now = recycle[0];
                recycle[0] = null;
                if (now != null) now.run();
            }
        };
        place(root, lister, 0, 0, 10, 10);
        layout();
        assertTrue(recycle[0] == null);
        assertNull(button());
        assertNull(cast());
        assertNull(bar());
        drain();
        assertTrue(play.getParent() == null);
        assertTrue(cast.getParent() == null);
        assertTrue(bar.getParent() == null);
        assertEquals(1, content.getChildCount());
    }
}
