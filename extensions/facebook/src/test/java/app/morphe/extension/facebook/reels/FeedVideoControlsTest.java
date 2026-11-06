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
import android.widget.ImageView;
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
    private final List<String> opens = new ArrayList<>();
    private final List<Boolean> wides = new ArrayList<>();
    private final List<Integer> seeks = new ArrayList<>();
    private boolean fail;

    private static final class Media { String id = "video"; boolean ordinary = true; boolean live; }
    private static final class Player {
        ViewGroup view;
        Media params = new Media();
        String origin = "newsfeed::feed_story";
        boolean playing;
        int position, length, positionReads;
        float speed = 1f;
    }
    private final List<Float> speeds = new ArrayList<>();

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
            public boolean live(Object params) { return ((Media) params).live; }
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
            public void open(View from, String videoId, boolean wide) {
                assertTrue(from == FeedVideoControls.showingOpen(player));
                opens.add(videoId);
                wides.add(wide);
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
            public float speed(Object of) { return ((Player) of).speed; }
            public void speed(Object of, float speed) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper());
                assertTrue(of == player);
                speeds.add(speed);
                ((Player) of).speed = speed;
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
        Settings.FEED_VIDEO_SEEK_BAR_FADE.resetToDefault();
        Settings.FEED_VIDEO_SEEK_BAR_LOWER.resetToDefault();
        Settings.STORY_SEEK_BAR.resetToDefault();
        Settings.STORY_SEEK_BAR_FADE.resetToDefault();
        Settings.FEED_VIDEO_FULL_PLAYER_BUTTON.resetToDefault();
        Settings.FEED_VIDEO_SKIP_BUTTONS.resetToDefault();
        Settings.FEED_VIDEO_SPEED_BUTTON.resetToDefault();
        Settings.FEED_VIDEO_SEEK_BAR_TIMES.resetToDefault();
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

    private View open() { return FeedVideoControls.showingOpen(player); }

    private void openOn() {
        Settings.FEED_VIDEO_FULL_PLAYER_BUTTON.save(true);
        FeedVideoControls.settingsChanged();
        drain();
    }

    private View back() { return FeedVideoControls.showingSkip(player, false); }

    private View ahead() { return FeedVideoControls.showingSkip(player, true); }

    private void skipOn() {
        Settings.FEED_VIDEO_SKIP_BUTTONS.save(true);
        FeedVideoControls.settingsChanged();
        drain();
    }

    private FeedVideoControls.Button speed() { return (FeedVideoControls.Button) FeedVideoControls.showingSpeed(player); }

    private void speedOn() {
        FeedVideoControls.speedInBuildForTests = true;
        Settings.FEED_VIDEO_SPEED_BUTTON.save(true);
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

    /** One of Facebook's buttons on the video, a 32 px image a screen reader names. */
    private View cornerButton(int left, int top, String said) {
        ImageView view = new ImageView(activity);
        view.setContentDescription(said);
        place(player.view, view, left, top, 32, 32);
        return view;
    }

    /** A playing video moving on for [ms], a tick at a time. */
    private void play(long ms) {
        for (long gone = 0; gone < ms; gone += 250) {
            if (player.playing) player.position += 250;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        }
    }

    private static void save(app.morphe.extension.shared.settings.BooleanSetting setting, boolean value) {
        setting.save(value);
        FeedVideoControls.settingsChanged();
        drain();
    }

    @Test public void lowerMovesTheBarAndFacebooksCornerButtonsDownTogetherAndPutsThemBack() {
        View speaker = cornerButton(1040, 540, "Unmute sound");
        View tagged = cornerButton(8, 540, "Tagged people");
        View caption = cornerButton(8, 480, "Above the strip the bar leaves");
        View middle = cornerButton(524, 540, "Not near a side");
        speaker.setTranslationY(3);
        layout();
        barOn();
        int edge = on(player.view)[1] + 600;
        assertEquals(edge - FeedVideoControls.BAR_LIFT_DP, on(bar())[1] + bar().getHeight());
        assertEquals("off, nothing of Facebook's moves", 3f, speaker.getTranslationY(), 0f);

        save(Settings.FEED_VIDEO_SEEK_BAR_LOWER, true);
        refreshAfterLayout();
        layout();
        assertEquals("the speaker keeps the offset Facebook gave it", 3f + FeedVideoControls.LOWER_DP, speaker.getTranslationY(), 0f);
        assertEquals((float) FeedVideoControls.LOWER_DP, tagged.getTranslationY(), 0f);
        assertEquals(0f, caption.getTranslationY(), 0f);
        assertEquals(0f, middle.getTranslationY(), 0f);
        assertEquals("the bar comes down with them", edge - FeedVideoControls.BAR_LIFT_DP + FeedVideoControls.LOWER_DP,
                on(bar())[1] + bar().getHeight());
        assertEquals(FeedVideoControls.BAR_DP, bar().getHeight());

        // Facebook moves the speaker 10 lower itself, which leaves 11 under it above the margin.
        speaker.setTranslationY(speaker.getTranslationY() + 10);
        tick();
        refreshAfterLayout();
        layout();
        assertEquals("all of them stop that much lower, clear of the edge", 13f + 11f, speaker.getTranslationY(), 0f);
        assertEquals(11f, tagged.getTranslationY(), 0f);
        assertEquals("and the bar stays clear of them", edge - FeedVideoControls.BAR_LIFT_DP + 11, on(bar())[1] + bar().getHeight());

        save(Settings.FEED_VIDEO_SEEK_BAR_LOWER, false);
        refreshAfterLayout();
        layout();
        assertEquals("off, Facebook's own offset is all that's left", 13f, speaker.getTranslationY(), 0f);
        assertEquals(0f, tagged.getTranslationY(), 0f);
        assertEquals(edge - FeedVideoControls.BAR_LIFT_DP, on(bar())[1] + bar().getHeight());

        save(Settings.FEED_VIDEO_SEEK_BAR_LOWER, true);
        refreshAfterLayout();
        assertEquals(11f, tagged.getTranslationY(), 0f);
        player.view.removeView(tagged);
        assertEquals("a button that leaves the screen goes back first, so a recycled view never keeps the move",
                0f, tagged.getTranslationY(), 0f);
        save(Settings.FEED_VIDEO_SEEK_BAR, false);
        assertEquals("without the bar nothing stays moved", 13f, speaker.getTranslationY(), 0f);
    }

    @Test public void fadeDimsTheBarAndTheSpeakerWhileItPlaysAndATouchOrAPauseBringsThemBack() {
        View speaker = cornerButton(1040, 540, "Unmute sound");
        View tagged = cornerButton(8, 540, "Tagged people");
        layout();
        player.playing = true;
        barOn();
        save(Settings.FEED_VIDEO_SEEK_BAR_FADE, true);
        SeekBar bar = bar();
        long fade = FeedVideoControls.FADE_AFTER_MS + FeedVideoControls.FADE_ANIMATION_MS + 250;
        play(1000);
        assertEquals("it stays bright a few seconds after it starts", 1f, bar.getAlpha(), 0f);
        assertEquals(1f, speaker.getAlpha(), 0f);
        play(fade);
        assertEquals(FeedVideoControls.FADED_ALPHA, bar.getAlpha(), 0.01f);
        assertEquals("the speaker fades with it", FeedVideoControls.FADED_ALPHA, speaker.getAlpha(), 0.01f);
        assertEquals("the tagged people across from it don't", 1f, tagged.getAlpha(), 0f);
        assertEquals("fading moves nothing", 0f, speaker.getTranslationY(), 0f);

        touch(bar, MotionEvent.ACTION_DOWN, 270);
        play(FeedVideoControls.FADE_ANIMATION_MS + 250);
        assertEquals("a touch brings them back", 1f, bar.getAlpha(), 0.01f);
        assertEquals(1f, speaker.getAlpha(), 0.01f);
        play(fade);
        assertEquals("and they stay while it's held", 1f, bar.getAlpha(), 0.01f);
        touch(bar, MotionEvent.ACTION_UP, 270);
        play(fade);
        assertEquals("let go, they fade again", FeedVideoControls.FADED_ALPHA, bar.getAlpha(), 0.01f);

        speaker.setContentDescription("Mute sound");
        play(FeedVideoControls.FADE_ANIMATION_MS + 500);
        assertEquals("a tap on the speaker brings them back", 1f, speaker.getAlpha(), 0.01f);
        assertEquals(1f, bar.getAlpha(), 0.01f);
        play(fade);
        assertEquals(FeedVideoControls.FADED_ALPHA, speaker.getAlpha(), 0.01f);

        player.playing = false;
        FeedVideoControls.changed(player);
        drain();
        play(fade);
        assertEquals("a paused video keeps them bright", 1f, bar.getAlpha(), 0.01f);
        assertEquals(1f, speaker.getAlpha(), 0.01f);

        player.playing = true;
        FeedVideoControls.changed(player);
        drain();
        play(fade);
        assertEquals(FeedVideoControls.FADED_ALPHA, bar.getAlpha(), 0.01f);
        save(Settings.FEED_VIDEO_SEEK_BAR_FADE, false);
        play(FeedVideoControls.FADE_ANIMATION_MS + 250);
        assertEquals("off, they come back for good", 1f, bar.getAlpha(), 0.01f);
        assertEquals(1f, speaker.getAlpha(), 0.01f);
    }

    @Test public void fadeLeavesAButtonFacebookHidItselfHidden() {
        View speaker = cornerButton(1040, 540, "Unmute sound");
        layout();
        player.playing = true;
        barOn();
        save(Settings.FEED_VIDEO_SEEK_BAR_FADE, true);
        play(FeedVideoControls.FADE_AFTER_MS + FeedVideoControls.FADE_ANIMATION_MS + 250);
        assertEquals(FeedVideoControls.FADED_ALPHA, speaker.getAlpha(), 0.01f);
        speaker.setAlpha(0f);
        player.playing = false;
        FeedVideoControls.changed(player);
        drain();
        play(FeedVideoControls.FADE_ANIMATION_MS + 250);
        assertEquals(1f, bar().getAlpha(), 0.01f);
        assertEquals(0f, speaker.getAlpha(), 0f);
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

    @Test public void fullPlayerButtonSitsNextToPlayStopsTheFeedCopyAndOpensTheVideo() {
        assertNull("it starts off", open());
        openOn();
        assertNotNull(open());
        assertEquals("Open in the full player", open().getContentDescription());
        assertTrue(open().getParent() == button().getParent());
        // 40 px buttons 4 px apart at mdpi, play first (#239).
        assertEquals(4, at(button()).leftMargin);
        assertEquals(4 + 40 + 4, at(open()).leftMargin);
        assertEquals(at(button()).topMargin, at(open()).topMargin);
        player.playing = true;
        FeedVideoControls.changed(player);
        drain();
        assertEquals("Pause feed video", button().getContentDescription());
        open().performClick();
        assertEquals("the feed's copy stops first", java.util.Arrays.asList(false), commands);
        assertEquals("its play button shows the stop at once", "Play feed video", button().getContentDescription());
        assertEquals(java.util.Arrays.asList("video"), opens);
        open().performClick();
        assertEquals("a stopped video isn't told again", 1, commands.size());
        assertEquals(2, opens.size());
    }

    @Test public void fullPlayerButtonStandsAloneAndNeedsItsSwitchTheBuildAndNoPause() {
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
        openOn();
        assertNull(button());
        assertNotNull(open());
        assertEquals("alone it takes play's spot", 4, at(open()).leftMargin);
        View old = open();
        Settings.FEED_VIDEO_FULL_PLAYER_BUTTON.save(false);
        old.performClick();
        drain();
        assertNull(open());
        openOn();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(open());
        PauseForTests.resume();
        openOn();
        assertNotNull(open());
        FeedVideoControls.inBuildForTests = false;
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(open());
        assertTrue(opens.isEmpty());
        assertTrue(commands.isEmpty());
    }

    @Test public void oldFullPlayerClickNeverOpensAnotherVideo() {
        openOn();
        View old = open();
        player.params.id = "another video";
        old.performClick();
        assertTrue(opens.isEmpty());
        FeedVideoControls.changed(player);
        drain();
        open().performClick();
        assertEquals(java.util.Arrays.asList("another video"), opens);
    }

    @Test public void aLiveVideoGetsPlayPauseAndTheFullPlayerButtonButNoCastOrSeekBar() {
        castOn();
        openOn();
        barOn();
        assertNotNull("an ordinary video gets the cast button", cast());
        assertNotNull("and the bar", bar());
        player.params.live = true;
        FeedVideoControls.changed(player);
        drain();
        assertNotNull(button());
        assertNotNull(open());
        assertNull("a live video has no file to hand over", cast());
        assertNull("or an end to seek to", bar());
        button().performClick();
        assertEquals(java.util.Arrays.asList(true), commands);
        open().performClick();
        assertEquals(java.util.Arrays.asList("video"), opens);
        player.params.live = false;
        FeedVideoControls.changed(player);
        drain();
        layout();
        assertNotNull(cast());
        assertNotNull(bar());
    }

    @Test public void aLiveVideoNeedsRoomOnlyForTheButtonsItGets() {
        player.view.removeAllViews();
        // Room for two 40 px buttons and their three 4 px gaps, not three and four.
        place(player.view, new SurfaceView(activity), 0, 0, 100, 600);
        layout();
        castOn();
        openOn();
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        assertNull(open());
        player.params.live = true;
        FeedVideoControls.changed(player);
        drain();
        assertNotNull(button());
        assertNotNull(open());
        assertNull(cast());
    }

    @Test public void theFullPlayerOpensTheVideosWatchLinkInFacebooksLinkHandler() {
        FeedVideoControls.openFullPlayer(activity, "10153038129956772", false);
        android.content.Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(android.content.Intent.ACTION_VIEW, opened.getAction());
        assertEquals("https://www.facebook.com/watch/?v=10153038129956772", opened.getDataString());
        assertEquals(FeedVideoControls.URI_HANDLER, opened.getComponent().getClassName());
        assertEquals(activity.getPackageName(), opened.getComponent().getPackageName());
        FeedVideoControls.openFullPlayer(activity, "not a video", false);
        assertNull("anything but a video id opens nothing", shadowOf(activity).getNextStartedActivity());
    }

    @Test public void aWideVideoOpensInTheFullScreenPlayerNotTheReelsViewer() {
        FeedVideoControls.openFullPlayer(activity, "10153038129956772", true);
        android.content.Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        // Facebook's link map needs a loop value to match the link at all (without one it lands on
        // Home), and force_fs=1 keeps its video link interceptor from sending it to the reels viewer (#239).
        assertEquals("fb://fullscreen_video/10153038129956772?loop=false&force_fs=1", opened.getDataString());
        assertEquals("false", opened.getData().getQueryParameter("loop"));
        assertEquals("1", opened.getData().getQueryParameter("force_fs"));
        assertEquals(FeedVideoControls.URI_HANDLER, opened.getComponent().getClassName());
        FeedVideoControls.openFullPlayer(activity, "1&x=2", true);
        assertNull("a wide video needs a real id too", shadowOf(activity).getNextStartedActivity());
    }

    @Test public void theFullPlayerButtonTellsAWideVideoFromAnUprightOne() {
        openOn();
        open().performClick();
        assertEquals("the 1080 by 600 video is wide", java.util.Arrays.asList(true), wides);
        player.view.removeAllViews();
        place(player.view, new SurfaceView(activity), 0, 0, 600, 600);
        layout();
        FeedVideoControls.changed(player);
        drain();
        open().performClick();
        assertEquals("a square one isn't", java.util.Arrays.asList(true, false), wides);
        player.view.removeAllViews();
        // A tall reel the feed crops to its container still counts as upright.
        place(player.view, new TextureView(activity), 0, 0, 540, 960);
        layout();
        FeedVideoControls.changed(player);
        drain();
        open().performClick();
        assertEquals(java.util.Arrays.asList(true, false, false), wides);
        assertEquals(3, opens.size());
    }

    @Test public void skipButtonsSitBetweenPlayAndTheFullPlayerAndJumpTenSeconds() {
        assertNull("they start off", back());
        skipOn();
        openOn();
        barOn();
        assertEquals("Back 10 seconds", back().getContentDescription());
        assertEquals("Ahead 10 seconds", ahead().getContentDescription());
        assertTrue(back().getParent() == button().getParent());
        // Play, back, ahead, then the full player, 40 px buttons 4 px apart (#239).
        assertEquals(4, at(button()).leftMargin);
        assertEquals(4 + 44, at(back()).leftMargin);
        assertEquals(4 + 2 * 44, at(ahead()).leftMargin);
        assertEquals(4 + 3 * 44, at(open()).leftMargin);
        assertEquals(at(button()).topMargin, at(ahead()).topMargin);
        ahead().performClick();
        assertEquals(java.util.Arrays.asList(25000), seeks);
        assertEquals("the bar moves with it", 25000, bar().getProgress());
        tick();
        assertEquals("and doesn't spring back while the player catches up", 25000, bar().getProgress());
        ahead().performClick();
        ahead().performClick();
        assertEquals("quick taps add up while the player still reads 0:15",
                java.util.Arrays.asList(25000, 35000, 45000), seeks);
        player.position = 45200;
        back().performClick();
        assertEquals("once it lands a tap counts from the player", 35200, (int) seeks.get(3));
        assertTrue("playback is left alone", commands.isEmpty());
    }

    @Test public void skipsStayInsideTheVideo() {
        skipOn();
        player.length = 60000;
        player.position = 55000;
        ahead().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedVideoControls.LANDING_MS));
        player.position = 4000;
        back().performClick();
        assertEquals(java.util.Arrays.asList(60000, 0), seeks);
        player.length = 0;
        ahead().performClick();
        assertEquals("a video with no length yet doesn't move", 2, seeks.size());
    }

    @Test public void aSkipRightAfterADragCountsFromWhereTheBarWasLetGo() {
        skipOn();
        barOn();
        touch(bar(), MotionEvent.ACTION_DOWN, 810);
        touch(bar(), MotionEvent.ACTION_UP, 810);
        ahead().performClick();
        assertEquals(java.util.Arrays.asList(45000, 55000), seeks);
    }

    @Test public void skipButtonsStandAloneAndNeedTheirSwitchAndTheBuild() {
        Settings.INLINE_FEED_VIDEO_CONTROLS.save(false);
        skipOn();
        assertNull(button());
        assertEquals("alone the first takes play's spot", 4, at(back()).leftMargin);
        assertEquals(4 + 44, at(ahead()).leftMargin);
        View old = ahead();
        player.length = 60000;
        Settings.FEED_VIDEO_SKIP_BUTTONS.save(false);
        old.performClick();
        drain();
        assertNull(back());
        assertNull(ahead());
        skipOn();
        assertNotNull(ahead());
        FeedVideoControls.inBuildForTests = false;
        FeedVideoControls.settingsChanged();
        drain();
        assertNull(ahead());
        assertTrue(seeks.isEmpty());
    }

    @Test public void speedButtonShowsThePlayersSpeedAndEachTapMovesToTheNext() {
        assertNull("it starts off", speed());
        speedOn();
        skipOn();
        openOn();
        assertEquals("Playback speed", speed().getContentDescription());
        // Play, the two skips, speed, then the full player (#239).
        assertEquals(4 + 3 * 44, at(speed()).leftMargin);
        assertEquals(4 + 4 * 44, at(open()).leftMargin);
        assertEquals("1x", speed().speed);
        assertEquals("1x", String.valueOf(speed().getStateDescription()));
        for (int i = 0; i < 5; i++) speed().performClick();
        assertEquals(java.util.Arrays.asList(1.25f, 1.5f, 2f, 0.5f, 1f), speeds);
        speed().performClick();
        assertEquals("1.25x", speed().speed);
        assertEquals("a screen reader hears it too", "1.25x", String.valueOf(speed().getStateDescription()));
        // Hold a reel for 2x, say.
        player.speed = 2f;
        FeedVideoControls.changed(player);
        drain();
        assertEquals("it shows a speed set elsewhere", "2x", speed().speed);
        player.speed = 1.75f;
        FeedVideoControls.changed(player);
        drain();
        assertEquals("1.75x", speed().speed);
        speed().performClick();
        assertEquals("one off the list goes back to 1x", 1f, player.speed, 0f);
        assertTrue("playback is left alone", commands.isEmpty() && seeks.isEmpty());
    }

    @Test public void speedButtonNeedsHoldAReelFor2xInTheBuildAndLiveVideosGetNoSkipOrSpeed() {
        FeedVideoControls.speedInBuildForTests = false;
        Settings.FEED_VIDEO_SPEED_BUTTON.save(true);
        FeedVideoControls.settingsChanged();
        drain();
        assertNull("its patch is what sets the speed", speed());
        speedOn();
        skipOn();
        openOn();
        assertNotNull(speed());
        player.params.live = true;
        FeedVideoControls.changed(player);
        drain();
        assertNull("a live video plays at its own pace", speed());
        assertNull("and has no end to skip toward", back());
        assertNull(ahead());
        assertEquals("the full player moves up next to play", 4 + 44, at(open()).leftMargin);
        player.params.live = false;
        FeedVideoControls.changed(player);
        drain();
        assertNotNull(speed());
        assertNotNull(back());
    }

    @Test public void theSkipAndSpeedButtonsNeedRoomToo() {
        player.view.removeAllViews();
        // Five 40 px buttons and their six 4 px gaps take 224 px.
        place(player.view, new SurfaceView(activity), 0, 0, 220, 600);
        layout();
        skipOn();
        speedOn();
        openOn();
        FeedVideoControls.changed(player);
        drain();
        assertNull(button());
        assertNull(speed());
        Settings.FEED_VIDEO_SPEED_BUTTON.save(false);
        drain();
        assertNotNull(button());
        assertNotNull(ahead());
        assertNull(speed());
    }

    @Test public void timesOnTheSeekBarShowAtItsEndsAndTheLineMakesRoomForThem() {
        barOn();
        SeekBar bar = bar();
        assertEquals("they start off", 0, bar.getPaddingLeft());
        Settings.FEED_VIDEO_SEEK_BAR_TIMES.save(true);
        drain();
        tick();
        int room = bar.getPaddingLeft();
        assertTrue("room for the text and a gap: " + room, room > 8);
        assertEquals("the line stays centered", room, bar.getPaddingRight());
        List<String> texts = new ArrayList<>();
        bar.draw(new android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1080, bar.getHeight(),
                android.graphics.Bitmap.Config.ARGB_8888)) {
            @Override public void drawText(String text, float x, float y, android.graphics.Paint paint) { texts.add(text); }
        });
        assertEquals(java.util.Arrays.asList("0:15", "-0:45"), texts);
        touch(bar, MotionEvent.ACTION_DOWN, 540);
        touch(bar, MotionEvent.ACTION_UP, 540);
        touch(bar, MotionEvent.ACTION_DOWN, room / 2);
        touch(bar, MotionEvent.ACTION_UP, room / 2);
        assertEquals("a touch maps onto the shorter line, and one on a time takes that end",
                java.util.Arrays.asList(30000, 0), seeks);
        Settings.FEED_VIDEO_SEEK_BAR_TIMES.save(false);
        drain();
        tick();
        assertEquals(0, bar().getPaddingLeft());
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
        assertEquals(log, 1, occurrences(log, "Inline feed button: none, no video id, or an ad, audio or GIF"));
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

    @Test public void pageGroupAndProfileVideosGetTheButtonsLikeTheHomeFeed() {
        for (String origin : new String[] {"user_timeline::feed_story", "group::feed_story",
                "page_timeline::feed_story", "profile::feed_story"}) {
            player.origin = "fb_shorts_tab::fb_shorts_viewer";
            FeedVideoControls.changed(player);
            drain();
            assertNull(origin, button());
            player.origin = origin;
            FeedVideoControls.changed(player);
            drain();
            assertNotNull(origin, button());
        }
        player.origin = "search_results_page::feed_story";
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

    private FrameLayout card;

    /** The story viewer in place of the feed: a card most of the window tall, its video filling it, half a minute long. */
    private void storyShows() {
        root.removeAllViews();
        card = new FrameLayout(activity);
        place(root, card, 0, 100, 1080, 1800);
        FrameLayout video = new FrameLayout(activity);
        place(card, video, 0, 0, 1080, 1800);
        place(video, new SurfaceView(activity), 0, 0, 1080, 1800);
        player.view = video;
        player.origin = "fb_stories_video_view";
        player.length = 30000;
        player.position = 3000;
        layout();
        attach(player);
        layout();
    }

    private void storyBarOn() {
        save(Settings.STORY_SEEK_BAR, true);
        layout();
    }

    private int cardBottom() { return on(card)[1] + 1800 - FeedVideoControls.STORY_ABOVE_DP; }

    @Test public void aStoryGetsOnlyItsOwnSeekBarAlongTheCardsBottom() {
        storyShows();
        save(Settings.FEED_VIDEO_SEEK_BAR, true);
        layout();
        assertNull("the feed's switch leaves stories alone", bar());
        assertNull("and so do the feed's buttons", button());
        assertFalse(FeedVideoControls.storyBarShowing());
        storyBarOn();
        SeekBar bar = bar();
        assertNotNull(bar);
        assertEquals("Story position", bar.getContentDescription());
        assertNull(button());
        assertNull(cast());
        assertNull(open());
        assertEquals(on(card)[0] + FeedVideoControls.STORY_SIDE_DP, on(bar)[0]);
        assertEquals(1080 - 2 * FeedVideoControls.STORY_SIDE_DP, bar.getWidth());
        assertEquals(FeedVideoControls.STORY_BAR_DP, bar.getHeight());
        assertEquals("its line sits where Progress bar at the bottom draws", cardBottom(), on(bar)[1] + bar.getHeight());
        assertEquals(30000, bar.getMax());
        assertEquals(3000, bar.getProgress());
        assertTrue("Progress bar at the bottom makes way", FeedVideoControls.storyBarShowing());
        player.position = 9000;
        tick();
        assertEquals(9000, bar.getProgress());
        assertTrue(seeks.isEmpty());
        assertTrue(commands.isEmpty());
        save(Settings.STORY_SEEK_BAR, false);
        assertNull(bar());
        assertFalse("and gets its spot back", FeedVideoControls.storyBarShowing());
    }

    @Test public void theStorySwitchLeavesFeedVideosAlone() {
        save(Settings.STORY_SEEK_BAR, true);
        player.length = 60000;
        layout();
        assertNull(bar());
        barOn();
        assertEquals("Feed video position", bar().getContentDescription());
        assertFalse(FeedVideoControls.storyBarShowing());
    }

    @Test public void aDragHoldsAPlayingStoryAndTheLetGoSeeksThenPlaysItOn() {
        storyShows();
        player.playing = true;
        storyBarOn();
        SeekBar bar = bar();
        int middle = bar.getWidth() / 2;
        touch(bar, MotionEvent.ACTION_DOWN, 100);
        assertEquals("the story waits while it's dragged", java.util.Arrays.asList(false), commands);
        touch(bar, MotionEvent.ACTION_MOVE, middle);
        assertTrue("and moves on the let go", seeks.isEmpty());
        touch(bar, MotionEvent.ACTION_UP, middle);
        assertEquals(java.util.Arrays.asList(15000), seeks);
        assertEquals(java.util.Arrays.asList(false, true), commands);
        assertTrue(player.playing);

        commands.clear();
        seeks.clear();
        player.playing = false;
        FeedVideoControls.changed(player);
        drain();
        touch(bar, MotionEvent.ACTION_DOWN, 100);
        touch(bar, MotionEvent.ACTION_MOVE, middle / 2);
        touch(bar, MotionEvent.ACTION_UP, middle / 2);
        assertEquals(1, seeks.size());
        assertTrue("a paused story stays paused", commands.isEmpty());
        assertFalse(player.playing);

        seeks.clear();
        player.playing = true;
        FeedVideoControls.changed(player);
        drain();
        touch(bar, MotionEvent.ACTION_DOWN, 100);
        touch(bar, MotionEvent.ACTION_MOVE, middle);
        touch(bar, MotionEvent.ACTION_CANCEL, middle);
        assertTrue("a cancelled drag moves nothing", seeks.isEmpty());
        assertEquals("but the story plays on", java.util.Arrays.asList(false, true), commands);
    }

    @Test public void aStorysBarFadesOnlyWithItsOwnSwitch() {
        storyShows();
        player.playing = true;
        storyBarOn();
        save(Settings.FEED_VIDEO_SEEK_BAR, true);
        save(Settings.FEED_VIDEO_SEEK_BAR_FADE, true);
        SeekBar bar = bar();
        long fade = FeedVideoControls.FADE_AFTER_MS + FeedVideoControls.FADE_ANIMATION_MS + 250;
        play(fade);
        assertEquals("the feed's fade leaves stories alone", 1f, bar.getAlpha(), 0f);
        save(Settings.STORY_SEEK_BAR_FADE, true);
        play(fade);
        assertEquals(FeedVideoControls.FADED_ALPHA, bar.getAlpha(), 0.01f);
        touch(bar, MotionEvent.ACTION_DOWN, 100);
        play(FeedVideoControls.FADE_ANIMATION_MS + 250);
        assertEquals("a touch brings it back", 1f, bar.getAlpha(), 0.01f);
        touch(bar, MotionEvent.ACTION_UP, 100);
        play(fade);
        assertEquals("it fades again as the story plays on", FeedVideoControls.FADED_ALPHA, bar.getAlpha(), 0.01f);
        save(Settings.STORY_SEEK_BAR_FADE, false);
        play(FeedVideoControls.FADE_ANIMATION_MS + 250);
        assertEquals(1f, bar.getAlpha(), 0.01f);
    }

    @Test public void liveStoriesAdsMovingCardsAndTheTraysPreviewsGetNoBar() {
        storyShows();
        storyBarOn();
        assertNotNull(bar());
        player.params.live = true;
        FeedVideoControls.changed(player);
        drain();
        assertNull("a live story has no end to seek to", bar());
        player.params.live = false;
        player.params.ordinary = false;
        FeedVideoControls.changed(player);
        drain();
        assertNull(bar());
        player.params.ordinary = true;
        FeedVideoControls.changed(player);
        drain();
        assertNotNull(bar());

        card.setTranslationX(400);
        refreshAfterLayout();
        assertNull("a card part way through a swipe", bar());
        card.setTranslationX(0);
        refreshAfterLayout();
        assertNotNull(bar());

        FrameLayout.LayoutParams small = (FrameLayout.LayoutParams) player.view.getLayoutParams();
        small.width = 300;
        small.height = 500;
        player.view.setLayoutParams(small);
        refreshAfterLayout();
        assertNull("a narrow preview, like the feed's row of stories", bar());
        small.width = 1080;
        player.view.setLayoutParams(small);
        refreshAfterLayout();
        layout();
        assertNotNull("a wide video in the viewer still gets it", bar());
        assertEquals("along the card's bottom, not the video's", cardBottom(), on(bar())[1] + bar().getHeight());
    }

    @Test public void aStoryBarInTheGestureStripMovesUpOutOfIt() {
        storyShows();
        storyBarOn();
        int strip = bar().getRootView().getHeight() - (cardBottom() - 50);
        FeedVideoControls.insetsForTests = gestureStrip(strip);
        refreshAfterLayout();
        layout();
        assertNotNull("lifted, never dropped", bar());
        assertEquals(cardBottom() - 50, on(bar())[1] + bar().getHeight());
        assertEquals(FeedVideoControls.STORY_BAR_DP, bar().getHeight());
    }
}
