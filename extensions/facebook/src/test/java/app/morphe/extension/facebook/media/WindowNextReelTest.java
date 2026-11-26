/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import static android.os.Looper.getMainLooper;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.media.BackgroundPlayButtonTest.Screen;
import app.morphe.extension.facebook.media.NextReelTest.PlayerOrigin;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Next reel in the window (#578): a reel that ends in the picture-in-picture window used to play
 * again and again. With the switch on, the Reels viewer hidden behind the window's viewer is moved on
 * a page, and the next reel Facebook binds on the page now on top is armed for the window, the window's
 * viewer closed and opened again for it and the reel started. No reel bound within the wait, or one the
 * window won't take, moves the viewer back. Off, or with no window, nothing moves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WindowNextReelTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final PlayerOrigin REELS = new PlayerOrigin("fb_shorts_tab::reels_tab");
    private static final int WIDTH = 720;
    private static final int HEIGHT = 1600;

    /** Stands in for the Reels viewer's pager: pages its own height stacked down, moved by a scroll. */
    static final class Pager extends RecyclerView {
        final List<Integer> scrolls = new ArrayList<>();
        /** What Facebook binds as the pager scrolls, as its own pager binds the pages it brings in. */
        Runnable bindsOnScroll;
        private int offset;

        Pager(Context context) {
            super(context, true);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int height = bottom - top;
            for (int i = 0; i < getChildCount(); i++) {
                int pageTop = i * height - offset;
                getChildAt(i).layout(0, pageTop, right - left, pageTop + height);
            }
        }

        @Override
        public void scrollBy(int x, int y) {
            scrolls.add(y);
            offset += y;
            onLayout(true, getLeft(), getTop(), getRight(), getBottom());
            if (bindsOnScroll != null) bindsOnScroll.run();
        }
    }

    /** Stands in for Facebook's window: what was asked of it, in order. */
    static final class Facebook implements WindowNextReel.Window {
        final List<String> calls = new ArrayList<>();
        boolean arms = true;
        double shapeRead = 1.25;
        float opened;

        @Override
        public boolean arm(Object player) {
            calls.add("arm " + name(player));
            // The arming reads the next reel's shape, as ReelsPipUtil's does through shape().
            if (arms) PictureInPicture.shape(shapeRead);
            return arms;
        }

        @Override
        public boolean close(Activity screen) {
            calls.add("close");
            return true;
        }

        @Override
        public boolean open(Activity screen, float aspect) {
            calls.add("open");
            opened = aspect;
            return true;
        }

        @Override
        public void play(Object player) {
            calls.add("play " + name(player));
        }
    }

    private final Map<Object, View> videos = new IdentityHashMap<>();
    private final Map<Object, String> names = new IdentityHashMap<>();
    private final List<FrameLayout> pages = new ArrayList<>();
    private static WindowNextReelTest running;
    private final Facebook facebook = new Facebook();
    private WindowNextReel.Window window;
    private PictureInPicture.Views views;
    private PictureInPicture.Views videoViews;
    private Screen screen;
    private Pager pager;
    private int page;
    private Object first;

    private static String name(Object player) {
        String name = running.names.get(player);
        return name == null ? "?" : name;
    }

    /** A player named [name], its video on page [page], or nowhere with -1. */
    private Object player(String name, int page) {
        Object player = new Object();
        names.put(player, name);
        if (page >= 0) {
            View video = new View(screen);
            pages.get(page).addView(video, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
            videos.put(player, video);
        }
        return player;
    }

    @Before
    public void start() {
        running = this;
        SystemClock.sleep(60_000);
        PictureInPicture.forget();
        HookStatus.clear();
        Settings.PICTURE_IN_PICTURE.save(true);
        Settings.NEXT_REEL_IN_WINDOW.save(true);
        window = WindowNextReel.window;
        views = PictureInPicture.views;
        videoViews = PictureInPicture.videoViews;
        WindowNextReel.window = facebook;
        PictureInPicture.views = player -> null;
        PictureInPicture.videoViews = videos::get;

        screen = Robolectric.buildActivity(Screen.class).setup().get();
        FrameLayout root = new FrameLayout(screen);
        FrameLayout main = new FrameLayout(screen);
        pager = new Pager(screen);
        for (int i = 0; i < 3; i++) {
            FrameLayout page = new FrameLayout(screen);
            pager.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pages.add(page);
        }
        main.addView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(main, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout windowViewer = new FrameLayout(screen);
        root.addView(windowViewer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.setContentView(root);
        first = player("first", 0);
        View decor = screen.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, WIDTH, HEIGHT);
        // A page is the pager's height, what the decor leaves of the screen.
        page = pager.getHeight();
        assertTrue("the pager wasn't laid out", page > 0);
        // The window's viewer hides the rest of the screen, the Reels viewer with it.
        main.setVisibility(View.GONE);
        PictureInPicture.armed(screen, first, null);
        idle(0);
    }

    @After
    public void restore() {
        WindowNextReel.window = window;
        PictureInPicture.views = views;
        PictureInPicture.videoViews = videoViews;
        Settings.NEXT_REEL_IN_WINDOW.resetToDefault();
        Settings.PICTURE_IN_PICTURE.resetToDefault();
        PictureInPicture.forget();
        HookStatus.clear();
        running = null;
    }

    private static void idle(long millis) {
        shadowOf(getMainLooper()).idleFor(Duration.ofMillis(millis));
        shadowOf(getMainLooper()).idle();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PICTURE_IN_PICTURE + ":")) return line;
        }
        return null;
    }

    private static boolean counted(String what) {
        String line = statusLine();
        return line != null && line.contains(what + " 1");
    }

    @Test
    public void theSwitchStartsOff() {
        assertFalse(Settings.NEXT_REEL_IN_WINDOW.defaultValue);
    }

    @Test
    public void theHiddenReelsViewerIsFoundOnlyWhenAskedForHiddenOnes() {
        assertNull("the viewer behind the window was taken for a visible one", NextReel.pagerIn(screen));
        assertSame(pager, NextReel.pagerIn(screen, true));
    }

    @Test
    public void theReelEndingInTheWindowMovesOnToTheNextOne() {
        PictureInPicture.playerEnded(first);
        idle(0);
        assertEquals("the Reels viewer behind the window wasn't moved on a page", List.of(page), pager.scrolls);
        Object next = player("next", 1);
        NextReel.bound(next, REELS, null);
        idle(0);
        assertEquals(List.of("arm next", "close", "open", "play next"), facebook.calls);
        assertEquals("the window's viewer opened in another shape than the next reel's", 1.25f, facebook.opened, 0.001f);
        assertTrue(statusLine(), counted(WindowNextReel.MOVED_ON));
        idle(WindowNextReel.WAIT_MS);
        assertEquals("the viewer went back after the window took the next reel", List.of(page), pager.scrolls);
    }

    @Test
    public void aStartOfTheNextReelHandsItToTheWindowToo() {
        PictureInPicture.playerEnded(first);
        idle(0);
        Object next = player("next", 1);
        PictureInPicture.playerPlaying(next);
        idle(0);
        assertEquals(List.of("arm next", "close", "open", "play next"), facebook.calls);
    }

    @Test
    public void aReelWithNoKnownShapeOpensPortrait() {
        facebook.shapeRead = 0;
        PictureInPicture.playerEnded(first);
        idle(0);
        NextReel.bound(player("next", 1), REELS, null);
        idle(0);
        assertEquals(WindowNextReel.PORTRAIT, facebook.opened, 0.001f);
    }

    @Test
    public void aReelReadyOnTheNextPageButOneLeavesTheWindowWaiting() {
        PictureInPicture.playerEnded(first);
        idle(0);
        Object ahead = player("ahead", 2);
        NextReel.bound(ahead, REELS, null);
        idle(0);
        assertTrue("the reel two pages on was taken: " + facebook.calls, facebook.calls.isEmpty());
        NextReel.bound(player("next", 1), REELS, null);
        idle(0);
        assertEquals(List.of("arm next", "close", "open", "play next"), facebook.calls);
    }

    @Test
    public void theReelBoundAPageAheadBeforeTheEndIsTakenOnceItsPageIsOnTop() {
        // Facebook binds the reel a page ahead while the one in the window still plays, so nothing
        // binds for that page after the move on (S22, 588: the second reel in the window played again).
        Object ahead = player("ahead", 1);
        NextReel.bound(ahead, REELS, null);
        idle(0);
        assertTrue("a reel bound before the end was taken: " + facebook.calls, facebook.calls.isEmpty());
        PictureInPicture.playerEnded(first);
        idle(0);
        assertEquals(List.of("arm ahead", "close", "open", "play ahead"), facebook.calls);
        assertTrue(statusLine(), counted(WindowNextReel.MOVED_ON));
        idle(WindowNextReel.WAIT_MS);
        assertEquals("the viewer went back after the window took the reel bound ahead", List.of(page), pager.scrolls);
    }

    @Test
    public void theReelBoundAheadIsTakenOverTheOneBoundForThePageAfterIt() {
        Object ahead = player("ahead", 1);
        NextReel.bound(ahead, REELS, null);
        Object after = player("after", 2);
        pager.bindsOnScroll = () -> NextReel.bound(after, REELS, null);
        PictureInPicture.playerEnded(first);
        idle(0);
        assertEquals(List.of("arm ahead", "close", "open", "play ahead"), facebook.calls);
        idle(WindowNextReel.WAIT_MS);
        assertEquals(List.of(page), pager.scrolls);
        assertFalse(statusLine(), counted(WindowNextReel.NONE_BOUND));
    }

    @Test
    public void aReelBoundAheadOnAPageNotOnTopIsLeftWaiting() {
        NextReel.bound(player("further", 2), REELS, null);
        PictureInPicture.playerEnded(first);
        idle(0);
        assertTrue("a reel two pages on was taken: " + facebook.calls, facebook.calls.isEmpty());
        idle(WindowNextReel.WAIT_MS);
        assertEquals(List.of(page, -page), pager.scrolls);
    }

    @Test
    public void theEndedReelPlayingAgainIsNotTaken() {
        PictureInPicture.playerEnded(first);
        idle(0);
        NextReel.bound(first, REELS, null);
        idle(0);
        assertTrue(facebook.calls.isEmpty());
    }

    @Test
    public void noReelBoundMovesTheViewerBackAndTheReelPlaysAgain() {
        PictureInPicture.playerEnded(first);
        idle(0);
        idle(WindowNextReel.WAIT_MS);
        assertEquals(List.of(page, -page), pager.scrolls);
        assertTrue(facebook.calls.isEmpty());
        assertTrue(statusLine(), counted(WindowNextReel.NONE_BOUND));
        NextReel.bound(player("late", 1), REELS, null);
        idle(0);
        assertTrue("a reel bound after the viewer went back was taken", facebook.calls.isEmpty());
    }

    @Test
    public void aReelTheWindowWontTakeMovesTheViewerBack() {
        facebook.arms = false;
        PictureInPicture.playerEnded(first);
        idle(0);
        NextReel.bound(player("next", 1), REELS, null);
        idle(0);
        assertEquals(List.of("arm next"), facebook.calls);
        assertEquals(List.of(page, -page), pager.scrolls);
    }

    @Test
    public void offOrWithNoWindowNothingMoves() {
        Settings.NEXT_REEL_IN_WINDOW.save(false);
        PictureInPicture.playerEnded(first);
        idle(0);
        assertTrue("the switch off moved the viewer", pager.scrolls.isEmpty());

        Settings.NEXT_REEL_IN_WINDOW.save(true);
        PictureInPicture.playerEnded(player("elsewhere", -1));
        idle(0);
        assertTrue("a reel not in the window moved the viewer", pager.scrolls.isEmpty());

        screen.inWindow = false;
        PictureInPicture.playerEnded(first);
        idle(0);
        assertTrue("a reel ending full screen moved the viewer", pager.scrolls.isEmpty());

        screen.inWindow = true;
        Settings.PICTURE_IN_PICTURE.save(false);
        PictureInPicture.playerEnded(first);
        idle(0);
        assertTrue("Picture-in-picture off moved the viewer", pager.scrolls.isEmpty());
        NextReel.bound(player("next", 1), REELS, null);
        idle(0);
        assertTrue(facebook.calls.isEmpty());
    }
}
