/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.facebook.katana.immersiveactivity.ImmersiveActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * #466: a reel or video opened from a post plays on Facebook's full-screen player screen, which pads
 * the viewer up off the gesture bar and paints that band. With See-through bars and Hide the video
 * comment box on, the viewer runs under the gesture bar to the screen's bottom edge, and the reel's
 * name, caption and buttons and the Up next card sit above the bar. Any other page on that screen,
 * the main screen, the switches off, a pause, or reels that don't follow the taller list keep
 * Facebook's band.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ImmersiveEdgeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2316;
    /** The S22's gesture bar, 588's padding under the viewer. */
    private static final int BAR = 42;

    @Before
    public void start() {
        HookStatus.clear();
        ImmersiveEdge.resetForTests();
        ImmersiveEdge.nowForTests = 1_000;
    }

    @After
    public void restore() {
        Settings.TRANSLUCENT_BARS.resetToDefault();
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.resetToDefault();
        PauseForTests.resume();
        ImmersiveEdge.resetForTests();
        HookStatus.clear();
    }

    private static void switchesOn() {
        Settings.TRANSLUCENT_BARS.save(true);
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    /**
     * The video viewer the way 588 lays it out on the S22's player screen: layers over the whole
     * content view, the viewer's header at the top, its paging list with one reel paged in (a
     * branch of full-size taps, the details and the video), and the Up next card pinned to the
     * bottom. The content is padded by whatever Facebook's padding is answered.
     */
    private static final class Viewer {
        final Activity screen;
        final ViewGroup content;
        final FrameLayout layer;
        final FrameLayout header;
        final RecyclerView list;
        final FrameLayout row;
        final FrameLayout taps;
        final FrameLayout details;
        final FrameLayout video;
        final FrameLayout upNext;

        Viewer(Class<? extends Activity> type, int rowHeight) {
            screen = Robolectric.buildActivity(type).setup().get();
            content = screen.findViewById(android.R.id.content);
            layer = new FrameLayout(screen);
            content.addView(layer, new FrameLayout.LayoutParams(MATCH, MATCH));

            list = new RecyclerView(screen, true);
            layer.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
            row = new FrameLayout(screen);
            FrameLayout branch = new FrameLayout(screen);
            row.addView(branch, new FrameLayout.LayoutParams(MATCH, MATCH));
            taps = new FrameLayout(screen);
            taps.setClickable(true);
            branch.addView(taps, new FrameLayout.LayoutParams(MATCH, MATCH));
            details = new FrameLayout(screen);
            TextView name = new TextView(screen);
            name.setText("A Page");
            details.addView(name, new FrameLayout.LayoutParams(400, 60, Gravity.BOTTOM | Gravity.START));
            details.addView(new Button(screen), new FrameLayout.LayoutParams(96, 96, Gravity.BOTTOM | Gravity.END));
            branch.addView(details, new FrameLayout.LayoutParams(MATCH, MATCH));
            video = new FrameLayout(screen);
            video.addView(new SurfaceView(screen), new FrameLayout.LayoutParams(MATCH, MATCH));
            branch.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH));
            list.addView(row, new FrameLayout.LayoutParams(MATCH, rowHeight, Gravity.TOP));

            header = new FrameLayout(screen);
            header.addView(new Button(screen), new FrameLayout.LayoutParams(113, 113));
            layer.addView(header, new FrameLayout.LayoutParams(MATCH, 338, Gravity.TOP));
            upNext = new FrameLayout(screen);
            upNext.setClickable(true);
            layer.addView(upNext, new FrameLayout.LayoutParams(MATCH, 152, Gravity.BOTTOM));
            padded();
        }

        /** Facebook's padding pass: the content padded by its bottom inset as answered, then a layout. */
        int padded() {
            int bottom = ImmersiveEdge.bottomInset(content, BAR);
            content.setPadding(0, 0, 0, bottom);
            ShadowLooper.idleMainLooper();
            content.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
            content.layout(content.getLeft(), content.getTop(), content.getLeft() + WIDTH, content.getTop() + HEIGHT);
            return bottom;
        }

        /** The viewer's page made: its header handed over, as VideoHomeRootFragment stores it. */
        void made() {
            ReelsHeaderClear.headerMade(header);
        }

        ImmersiveEdge.Edge edge() {
            synchronized (ImmersiveEdge.EDGES) {
                return ImmersiveEdge.EDGES.get(content);
            }
        }

        /** One frame with Facebook's padding answered again first. */
        int frame() {
            int bottom = padded();
            ImmersiveEdge.Edge edge = edge();
            if (edge != null) edge.frame();
            return bottom;
        }
    }

    @Test
    public void aViewerRunsUnderTheGestureBarWithItsDetailsAndUpNextAboveIt() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        assertEquals("Facebook's band before the viewer's made", BAR, viewer.content.getPaddingBottom());

        viewer.made();
        assertNotNull("the player screen's content got an edge", viewer.edge());
        assertEquals("the band opens", 0, viewer.frame());
        assertEquals("the reel runs to the bottom edge", HEIGHT, viewer.row.getHeight());
        assertEquals("the details end above the gesture bar", -BAR, viewer.details.getTranslationY(), 0f);
        assertEquals("so does the Up next card", -BAR, viewer.upNext.getTranslationY(), 0f);
        assertEquals("the video stays under it", 0f, viewer.video.getTranslationY(), 0f);
        assertEquals("the taps stay the whole reel", 0f, viewer.taps.getTranslationY(), 0f);
        assertEquals("the header at the top stays", 0f, viewer.header.getTranslationY(), 0f);
        assertTrue(statusLine(), statusLine().contains(ImmersiveEdge.OPENED));

        viewer.frame();
        assertEquals("one frame's lift, not two", -BAR, viewer.upNext.getTranslationY(), 0f);
        assertEquals(-BAR, viewer.details.getTranslationY(), 0f);

        ImmersiveEdge.nowForTests += ImmersiveEdge.GRACE_MS * 2;
        assertEquals("reels that follow the list keep it open", 0, viewer.frame());

        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(false);
        viewer.frame();
        assertEquals("the band's back with the comment box shown", BAR, viewer.frame());
        assertEquals(0f, viewer.details.getTranslationY(), 0f);
        assertEquals(0f, viewer.upNext.getTranslationY(), 0f);

        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(true);
        viewer.frame();
        assertEquals("and open again with it hidden", 0, viewer.frame());
        Settings.TRANSLUCENT_BARS.save(false);
        viewer.frame();
        assertEquals("See-through bars off keeps the band", BAR, viewer.frame());
        assertEquals(0f, viewer.upNext.getTranslationY(), 0f);
    }

    /**
     * Litho's Y animation as 588 runs it with the patch: [host] set to [y] in the LithoView [root],
     * less where its parents are by the walk (LX/03lx;->A00), which adds up each one's Y below the
     * LithoView through {@link ViewLift#lithoY}.
     */
    static void lithoSetsY(View host, View root, float y) {
        float parents = 0;
        for (View at = (View) host.getParent(); at != root; at = (View) at.getParent()) parents += ViewLift.lithoY(at);
        host.setY(y - parents);
    }

    @Test
    public void theControlsHangingPastTheBottomComeUpWhileSheetsStay() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        // 588's controls: 1080 px tall, hanging 51 px past the viewer's bottom, the seek bar ending at it.
        FrameLayout controls = new FrameLayout(viewer.screen);
        View seekBar = new View(viewer.screen);
        seekBar.setClickable(true);
        FrameLayout.LayoutParams seekAt = new FrameLayout.LayoutParams(MATCH, 67, Gravity.BOTTOM);
        seekAt.bottomMargin = 51;
        controls.addView(seekBar, seekAt);
        FrameLayout.LayoutParams hang = new FrameLayout.LayoutParams(MATCH, 1080, Gravity.BOTTOM);
        hang.bottomMargin = -51;
        viewer.layer.addView(controls, hang);
        // A sheet sized to end at the bottom, one dragged half out, and one laid out under the screen.
        FrameLayout sheet = new FrameLayout(viewer.screen);
        viewer.layer.addView(sheet, new FrameLayout.LayoutParams(MATCH, 1600, Gravity.BOTTOM));
        FrameLayout dragged = new FrameLayout(viewer.screen);
        FrameLayout.LayoutParams half = new FrameLayout.LayoutParams(MATCH, 1600, Gravity.BOTTOM);
        half.bottomMargin = -800;
        viewer.layer.addView(dragged, half);
        FrameLayout parked = new FrameLayout(viewer.screen);
        FrameLayout.LayoutParams under = new FrameLayout.LayoutParams(MATCH, 300, Gravity.TOP);
        under.topMargin = HEIGHT;
        viewer.layer.addView(parked, under);

        viewer.made();
        assertEquals(0, viewer.frame());
        assertEquals("the controls come up with the reel", -BAR, controls.getTranslationY(), 0f);
        int[] at = new int[2];
        seekBar.getLocationInWindow(at);
        int[] top = new int[2];
        viewer.content.getLocationInWindow(top);
        assertEquals("the seek bar ends above the gesture bar", top[1] + HEIGHT - BAR, at[1] + seekBar.getHeight());
        assertEquals("a sheet sized to the screen stays", 0f, sheet.getTranslationY(), 0f);
        assertEquals("so does one dragged half out", 0f, dragged.getTranslationY(), 0f);
        assertEquals("and one under the screen", 0f, parked.getTranslationY(), 0f);
    }

    @Test
    public void whatLithoMovesInsideTheDetailsStaysLiftedAndFacebooksOwnMoveIsKept() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        // The reel's name and caption: a host Litho slides up 98 px when the comment preview comes in.
        View caption = viewer.details.getChildAt(0);
        viewer.made();
        viewer.frame();
        assertEquals(-BAR, viewer.details.getTranslationY(), 0f);
        float laidOut = caption.getTop();

        lithoSetsY(caption, viewer.row, laidOut - 98);
        assertEquals("Litho's slide lands where it meant, under the lift", -98f, caption.getTranslationY(), 0f);
        viewer.frame();
        assertEquals(-98f, caption.getTranslationY(), 0f);
        assertEquals(-BAR, viewer.details.getTranslationY(), 0f);

        // Facebook moves the lifted part itself: the lift goes on top of its new place, and giving it
        // back leaves Facebook's.
        viewer.details.setTranslationY(-98f);
        viewer.frame();
        assertEquals("on top of Facebook's new move", -98f - BAR, viewer.details.getTranslationY(), 0f);
        assertEquals("which Litho's walk still sees as Facebook's", viewer.details.getTop() - 98f, ViewLift.lithoY(viewer.details), 0f);
        Settings.HIDE_VIDEO_COMMENT_COMPOSER.save(false);
        viewer.frame();
        assertEquals(BAR, viewer.frame());
        assertEquals("Facebook's move is what's left", -98f, viewer.details.getTranslationY(), 0f);
        assertEquals(viewer.details.getTop() - 98f, ViewLift.lithoY(viewer.details), 0f);
    }

    @Test
    public void pausedTheViewerKeepsFacebooksBand() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        viewer.made();
        assertEquals(0, viewer.frame());

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        viewer.frame();
        assertEquals(BAR, viewer.frame());
        assertEquals(0f, viewer.details.getTranslationY(), 0f);
        assertEquals(0f, viewer.upNext.getTranslationY(), 0f);
    }

    @Test
    public void anotherPageOnThePlayerScreenKeepsItsBand() {
        // The Feeds page opens on the player screen too, and makes no viewer header.
        switchesOn();
        Viewer page = new Viewer(ImmersiveActivity.class, MATCH);
        assertEquals(BAR, page.frame());
        assertNull(page.edge());
        assertEquals(0f, page.upNext.getTranslationY(), 0f);
    }

    @Test
    public void theMainScreensReelsHeaderOpensNothing() {
        switchesOn();
        Viewer reels = new Viewer(Activity.class, MATCH);
        reels.made();
        assertNull("the Reels tab's header isn't a player screen's viewer", reels.edge());
        assertEquals(BAR, reels.frame());
    }

    @Test
    public void reelsThatStayShortOfTheListGetTheBandBack() {
        // Facebook keeps each reel at the height the viewer had with the band, so the next one would show under the bar.
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, HEIGHT - BAR);
        viewer.made();
        assertEquals("open while the reels have time to follow", 0, viewer.frame());

        ImmersiveEdge.nowForTests += ImmersiveEdge.GRACE_MS;
        viewer.frame();
        assertEquals("given back once they haven't", BAR, viewer.frame());
        assertTrue(statusLine(), statusLine().contains(ImmersiveEdge.LEFT_ALONE));

        ImmersiveEdge.nowForTests += ImmersiveEdge.GRACE_MS;
        assertEquals("for good, so it never flickers", BAR, viewer.frame());
        assertEquals(0f, viewer.upNext.getTranslationY(), 0f);
    }

    @Test
    public void aPageShownInPlaceOfTheViewerGetsTheBandBack() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        viewer.made();
        assertEquals(0, viewer.frame());

        // A profile opened from the reel takes the viewer's place.
        viewer.list.setVisibility(View.GONE);
        ImmersiveEdge.nowForTests += ImmersiveEdge.GRACE_MS;
        assertEquals("a list missing a moment keeps it", 0, viewer.frame());
        ImmersiveEdge.nowForTests += ImmersiveEdge.MISSING_MS;
        viewer.frame();
        assertEquals("missing for longer gives it back", BAR, viewer.frame());
        assertEquals(0f, viewer.upNext.getTranslationY(), 0f);

        // Back to the reel: Facebook makes the viewer's page again.
        viewer.list.setVisibility(View.VISIBLE);
        viewer.made();
        assertEquals("open again with the viewer back", 0, viewer.frame());
    }

    @Test
    public void theViewersPageGoingGivesTheBandBack() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        viewer.made();
        assertEquals(0, viewer.frame());

        viewer.layer.removeView(viewer.header);
        viewer.frame();
        assertEquals("the header gone, the page under it gets its band", BAR, viewer.frame());
        assertEquals(0f, viewer.upNext.getTranslationY(), 0f);
    }

    @Test
    public void aViewerJustMadeWaitsForItsListBeforeGivingItBack() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        viewer.list.setVisibility(View.GONE);
        viewer.made();
        assertEquals("opened as the page is made, before its reels load", 0, viewer.frame());
        ImmersiveEdge.nowForTests += ImmersiveEdge.GRACE_MS - 1;
        assertEquals(0, viewer.frame());
        ImmersiveEdge.nowForTests += 1 + ImmersiveEdge.MISSING_MS;
        viewer.frame();
        assertEquals("no reels at all keeps Facebook's band", BAR, viewer.frame());
    }

    @Test
    public void noEdgeAndNoBandAnswersAsTheyCame() {
        switchesOn();
        Viewer viewer = new Viewer(ImmersiveActivity.class, MATCH);
        viewer.made();
        assertEquals("no gesture bar, nothing to open", 0, ImmersiveEdge.bottomInset(viewer.content, 0));
        assertEquals("a view with no edge", BAR, ImmersiveEdge.bottomInset(new View(viewer.screen), BAR));
        assertEquals(BAR, ImmersiveEdge.bottomInset(null, BAR));
    }
}
