/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Open comments at full height (#179): with its switch on, a sheet asked to open part way up opens
 * at one, the full height, each split-screen launcher is told to answer no, and a full-height sheet
 * is told it fills the screen, each counted. Facebook's own full height, and any share that isn't a
 * fraction, come back as they were. A sheet's header holding a video, the reel above its comments,
 * is hidden, at once or at the first layout after the video lands, and so is a watch-and-comment
 * sheet's header over the reel playing behind it, while a post's header stays. With Tap a reel
 * above its comments on (#315), a part-height sheet opened from the reel viewer stays open on a tap
 * outside it and passes that tap on, counted, and one from anywhere else is left to Facebook. The
 * Reels tab's full-screen sheet keeps a tap on the reel showing through its header for the reel,
 * counted, while the sound button and a drag keep theirs, and with no reel found the tap closes it.
 * Off, paused, or before the settings are ready, nothing changes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FullHeightCommentsTest {
    /** The share of the screen a reel's comment sheet asks for on 583. */
    private static final float REEL_SHEET = 0.66f;

    /** The header's id in 583, which the patch reads from Facebook's comments helper. */
    private static final int HEADER = 0x7f0a0612;

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    /** A sheet's views as Facebook's comments helper gets them: the header over the comment list. */
    private static ViewGroup sheet(View inHeader) {
        LinearLayout content = new LinearLayout(context());
        FrameLayout header = new FrameLayout(context());
        header.setId(HEADER);
        header.addView(inHeader);
        content.addView(header);
        content.addView(new FrameLayout(context()));
        return content;
    }

    /** A video a few hosts down, the way Litho mounts the reel's player in the header. */
    private static View hostedVideo() {
        FrameLayout outer = new FrameLayout(context());
        FrameLayout inner = new FrameLayout(context());
        inner.addView(new TextureView(context()));
        outer.addView(inner);
        return outer;
    }

    private static View header(ViewGroup sheet) {
        return sheet.findViewById(HEADER);
    }

    private static void layOut(ViewGroup sheet) {
        header(sheet).getViewTreeObserver().dispatchOnGlobalLayout();
    }

    /** A part-height sheet's dialog over a screen, set to close on a tap outside it the way Facebook's is. */
    private static Dialog sheetDialog() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        Dialog dialog = new Dialog(screen);
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    private static boolean passesTouchesOn(Dialog dialog) {
        return (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0;
    }

    private static boolean leftToFacebook(Dialog dialog) {
        return shadowOf(dialog).isCancelableOnTouchOutside() && !passesTouchesOn(dialog);
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private BooleanSupplier bridge;

    @Before
    public void start() {
        HookStatus.clear();
        bridge = FullHeightComments.reelTap;
        FullHeightComments.reelTap = () -> {
            reelTaps++;
            return reelFound;
        };
    }

    @After
    public void restore() {
        FullHeightComments.reelTap = bridge;
        PauseForTests.resume();
        Settings.OPEN_COMMENTS_FULL_HEIGHT.resetToDefault();
        Settings.TAP_REELS_ABOVE_COMMENTS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FULL_HEIGHT_COMMENTS + ":")) return line;
        }
        return null;
    }

    @Test
    public void onAPartSheetOpensAtFullHeightAndTheSplitScreenIsPassedOverEachCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertEquals("a reel's sheet stayed part way up", 1f, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertEquals("a server share stayed part way up", 1f, FullHeightComments.sheetHeight(0.75f), 0f);
        assertEquals("Facebook's full height changed", 1f, FullHeightComments.sheetHeight(1f), 0f);
        assertEquals("a zero share was raised", 0f, FullHeightComments.sheetHeight(0f), 0f);
        assertEquals("a share over one was lowered", 1.5f, FullHeightComments.sheetHeight(1.5f), 0f);
        assertTrue("the split screen opened", FullHeightComments.skipSplitScreen());
        assertTrue("a full-height sheet was drawn as a box", FullHeightComments.fillsScreen(1f));
        assertFalse("a part sheet was made full screen", FullHeightComments.fillsScreen(REEL_SHEET));
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 8, 3 found, 0 missing. Counted: "
                + FullHeightComments.SHEET_RAISED + " 2, " + FullHeightComments.SPLIT_SCREEN_SKIPPED + " 1, "
                + FullHeightComments.SCREEN_FILLED + " 1",
                statusLine());
    }

    @Test
    public void onTheVideoAboveASheetsCommentsIsHiddenAndCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        ViewGroup sheet = sheet(hostedVideo());
        FullHeightComments.watchHeader(sheet, HEADER, false);
        assertEquals("the reel above the comments still shows", View.GONE, header(sheet).getVisibility());
        assertEquals("the comment list was hidden", View.VISIBLE, sheet.getChildAt(1).getVisibility());
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 1, 1 found, 0 missing. Counted: "
                + FullHeightComments.VIDEO_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aVideoThatLandsAfterTheSetupIsHiddenAtTheNextLayout() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FrameLayout host = new FrameLayout(context());
        ViewGroup sheet = sheet(host);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        assertEquals("a header without a video was hidden", View.VISIBLE, header(sheet).getVisibility());
        host.addView(new SurfaceView(context()));
        layOut(sheet);
        assertEquals("the video that landed later still shows", View.GONE, header(sheet).getVisibility());
    }

    @Test
    public void aWatchAndCommentSheetsSeeThroughHeaderIsHiddenAndCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        // The Reels tab's sheet: the header is an empty tap area over the reel playing behind it.
        ViewGroup sheet = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(sheet, HEADER, true);
        assertEquals("the reel behind the sheet still shows above the comments", View.GONE, header(sheet).getVisibility());
        assertEquals("the comment list was hidden", View.VISIBLE, sheet.getChildAt(1).getVisibility());
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 1, 1 found, 0 missing. Counted: "
                + FullHeightComments.VIDEO_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aWatchAndCommentHeaderShownAgainIsHiddenAtTheNextLayout() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        ViewGroup sheet = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(sheet, HEADER, true);
        header(sheet).setVisibility(View.VISIBLE);
        layOut(sheet);
        assertEquals("the header Facebook showed again stayed", View.GONE, header(sheet).getVisibility());
    }

    @Test
    public void offOrPausedAWatchAndCommentSheetKeepsItsHeader() {
        ViewGroup off = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(off, HEADER, true);
        layOut(off);
        assertEquals("off, the header over the reel was hidden", View.VISIBLE, header(off).getVisibility());

        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ViewGroup paused = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(paused, HEADER, true);
        layOut(paused);
        assertEquals("paused, the header over the reel was hidden", View.VISIBLE, header(paused).getVisibility());
    }

    @Test
    public void aPostsHeaderStays() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        TextView reactions = new TextView(context());
        reactions.setText("You and 23 others");
        ViewGroup sheet = sheet(reactions);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        layOut(sheet);
        assertEquals("a post's reactions were hidden", View.VISIBLE, header(sheet).getVisibility());
    }

    @Test
    public void aSheetSetUpTwiceIsWatchedOnce() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FrameLayout host = new FrameLayout(context());
        ViewGroup sheet = sheet(host);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        host.addView(new TextureView(context()));
        // The second set-up stops at the header already watched, so the video it now holds waits
        // for the first watch's next layout instead of being hidden by a second one.
        FullHeightComments.watchHeader(sheet, HEADER, false);
        assertEquals(View.VISIBLE, header(sheet).getVisibility());
        layOut(sheet);
        assertEquals(View.GONE, header(sheet).getVisibility());
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 2, 1 found, 0 missing. Counted: "
                + FullHeightComments.VIDEO_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aSheetWithoutTheHeaderOrViewsIsLeftAlone() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FullHeightComments.watchHeader(null, HEADER, false);
        ViewGroup sheet = sheet(hostedVideo());
        FullHeightComments.watchHeader(sheet, HEADER + 1, false);
        assertEquals(View.VISIBLE, header(sheet).getVisibility());
    }

    @Test
    public void offOrPausedTheVideoAboveTheCommentsStays() {
        ViewGroup off = sheet(hostedVideo());
        FullHeightComments.watchHeader(off, HEADER, false);
        layOut(off);
        assertEquals("off, the reel above the comments was hidden", View.VISIBLE, header(off).getVisibility());

        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ViewGroup paused = sheet(hostedVideo());
        FullHeightComments.watchHeader(paused, HEADER, false);
        layOut(paused);
        assertEquals("paused, the reel above the comments was hidden", View.VISIBLE, header(paused).getVisibility());
    }

    @Test
    public void pausedAfterTheSetupALaterVideoStays() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FrameLayout host = new FrameLayout(context());
        ViewGroup sheet = sheet(host);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        host.addView(new TextureView(context()));
        layOut(sheet);
        assertEquals(View.VISIBLE, header(sheet).getVisibility());
    }

    @Test
    public void notANumberIsNotFullScreen() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertFalse("NaN was made full screen", FullHeightComments.fillsScreen(Float.NaN));
    }

    @Test
    public void notANumberIsLeftAsItCame() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertTrue("NaN was changed", Float.isNaN(FullHeightComments.sheetHeight(Float.NaN)));
    }

    @Test
    public void onTappingAReelsDescriptionExpandsItInPlaceInsteadOfOpeningTheCommentsCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertFalse("more on a reel's description opened the comments", FullHeightComments.descriptionOpensComments(true));
        assertFalse("a closed gate was opened", FullHeightComments.descriptionOpensComments(false));
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 2, 1 found, 0 missing. Counted: "
                + FullHeightComments.DESCRIPTION_EXPANDED + " 1", statusLine());
    }

    @Test
    public void offTheSheetKeepsFacebooksHeightAndTheSplitScreenRuns() {
        assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertFalse(FullHeightComments.skipSplitScreen());
        assertFalse(FullHeightComments.fillsScreen(1f));
        assertTrue("off, a reel's description stopped opening the comments", FullHeightComments.descriptionOpensComments(true));
    }

    @Test
    public void pausedTheSheetKeepsFacebooksHeightAndTheSplitScreenRuns() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertFalse(FullHeightComments.skipSplitScreen());
        assertFalse(FullHeightComments.fillsScreen(1f));
        assertTrue("paused, a reel's description stopped opening the comments", FullHeightComments.descriptionOpensComments(true));
    }

    @Test
    public void beforeTheSettingsAreReadyNothingChanges() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        ViewGroup sheet = sheet(hostedVideo());
        SettingsContextRule.withoutContext(() -> {
            assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
            assertFalse(FullHeightComments.skipSplitScreen());
            assertFalse(FullHeightComments.fillsScreen(1f));
            assertTrue(FullHeightComments.descriptionOpensComments(true));
            FullHeightComments.watchHeader(sheet, HEADER, false);
        });
        assertEquals("a sheet set up before the context lost its header", View.VISIBLE, header(sheet).getVisibility());
    }

    @Test
    public void onASheetOverTheReelViewerPassesTapsToTheReelEachCounted() {
        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        // 20 is the Reels tab, the one Facebook's own tap-through takes; 24 and 36 reach the same viewer.
        for (int source : new int[] {20, 24, 36}) {
            Dialog dialog = sheetDialog();
            assertTrue("a sheet from surface " + source + " was left to Facebook",
                    FullHeightComments.tapThrough(dialog, source));
            assertFalse("a sheet from surface " + source + " still closes on a tap above it",
                    shadowOf(dialog).isCancelableOnTouchOutside());
            assertTrue("a sheet from surface " + source + " still keeps the tap", passesTouchesOn(dialog));
        }
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 3, 1 found, 0 missing. Counted: "
                + FullHeightComments.TAPS_THROUGH + " 3", statusLine());
    }

    @Test
    public void aSheetOpenedAwayFromTheReelViewerIsLeftToFacebook() {
        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        // 23 is a profile's chain of videos, 0 a feed post: neither has a reel behind the sheet.
        for (int source : new int[] {23, 0, 1}) {
            Dialog dialog = sheetDialog();
            assertFalse("a sheet from surface " + source + " was set up", FullHeightComments.tapThrough(dialog, source));
            assertTrue("a sheet from surface " + source + " was changed", leftToFacebook(dialog));
        }
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 3, 1 found, 0 missing", statusLine());
    }

    @Test
    public void offPausedOrBeforeTheSettingsAreReadyASheetOverAReelIsLeftToFacebook() {
        Dialog off = sheetDialog();
        assertFalse(FullHeightComments.tapThrough(off, 20));
        assertTrue("off, the sheet was changed", leftToFacebook(off));

        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Dialog paused = sheetDialog();
        assertFalse(FullHeightComments.tapThrough(paused, 20));
        assertTrue("paused, the sheet was changed", leftToFacebook(paused));
        PauseForTests.resume();

        Dialog early = sheetDialog();
        SettingsContextRule.withoutContext(() -> assertFalse(FullHeightComments.tapThrough(early, 20)));
        assertTrue("a sheet set up before the context was changed", leftToFacebook(early));
    }

    @Test
    public void aMissingDialogIsLeftToFacebook() {
        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        assertFalse(FullHeightComments.tapThrough(null, 20));
    }

    /**
     * The Reels tab's sheet as 585 draws it: a see-through header over the comment list, the reel's
     * tap area filling it, whose tap closes the sheet, with the small sound button inside.
     */
    private ViewGroup reelsTabSheet(Activity screen) {
        LinearLayout content = new LinearLayout(screen);
        content.setOrientation(LinearLayout.VERTICAL);
        FrameLayout header = new FrameLayout(screen);
        header.setId(HEADER);
        FrameLayout tapArea = new FrameLayout(screen);
        tapArea.setOnClickListener(view -> closed++);
        View sound = new View(screen);
        sound.setOnClickListener(view -> sounds++);
        tapArea.addView(sound, new FrameLayout.LayoutParams(20, 20, Gravity.BOTTOM | Gravity.END));
        header.addView(tapArea, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200));
        content.addView(new FrameLayout(screen), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return content;
    }

    private static void show(Activity screen, ViewGroup sheet) {
        screen.setContentView(sheet);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Activity screen() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    /** A finger down at one spot and up at another, in the sheet's own coordinates. */
    private static void touch(ViewGroup sheet, float x, float y, float toX, float toY) {
        long now = SystemClock.uptimeMillis();
        sheet.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        if (toX != x || toY != y) {
            sheet.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, toX, toY, 0));
        }
        sheet.dispatchTouchEvent(MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, toX, toY, 0));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tapReel(ViewGroup sheet) {
        touch(sheet, 30, 30, 30, 30);
    }

    private int closed;
    private int sounds;
    private int reelTaps;
    private boolean reelFound = true;

    @Test
    public void onATapOnTheReelAboveTheReelsTabsCommentsPlaysOrPausesItAndTheSheetStays() {
        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        Activity screen = screen();
        ViewGroup sheet = reelsTabSheet(screen);
        show(screen, sheet);
        FullHeightComments.watchHeader(sheet, HEADER, true);
        tapReel(sheet);
        tapReel(sheet);
        assertEquals("the taps didn't reach the reel", 2, reelTaps);
        assertEquals("a tap on the reel closed the sheet", 0, closed);
        assertEquals("the header over the reel was hidden", View.VISIBLE, header(sheet).getVisibility());
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 1, 1 found, 0 missing. Counted: "
                + FullHeightComments.REEL_TAPPED + " 2", statusLine());
    }

    @Test
    public void aReelsTabSheetSetUpBeforeItsFirstLayoutTakesTheTapsOnceLaidOut() {
        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        Activity screen = screen();
        ViewGroup sheet = reelsTabSheet(screen);
        // Facebook sets the views up before the sheet is laid out, when every view is still 0 by 0.
        FullHeightComments.watchHeader(sheet, HEADER, true);
        show(screen, sheet);
        tapReel(sheet);
        assertEquals(1, reelTaps);
        assertEquals(0, closed);
    }

    @Test
    public void theSoundButtonAndADragAboveTheCommentsKeepTheirOwnTouches() {
        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        Activity screen = screen();
        ViewGroup sheet = reelsTabSheet(screen);
        show(screen, sheet);
        FullHeightComments.watchHeader(sheet, HEADER, true);
        View header = header(sheet);
        touch(sheet, header.getWidth() - 10, header.getHeight() - 10, header.getWidth() - 10, header.getHeight() - 10);
        assertEquals("the sound button lost its tap", 1, sounds);
        touch(sheet, 30, 30, 30, 150);
        assertEquals("a tap on the sound button or a drag played or paused the reel", 0, reelTaps);
        assertEquals("a drag closed the sheet", 0, closed);
    }

    @Test
    public void withNoReelFoundATapAboveTheCommentsClosesTheSheetAsBefore() {
        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        reelFound = false;
        Activity screen = screen();
        ViewGroup sheet = reelsTabSheet(screen);
        show(screen, sheet);
        FullHeightComments.watchHeader(sheet, HEADER, true);
        tapReel(sheet);
        assertEquals(1, reelTaps);
        assertEquals("the tap was lost", 1, closed);
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    @Test
    public void offPausedOrOnAPostsSheetATapAboveTheCommentsClosesTheSheet() {
        Activity screen = screen();
        ViewGroup off = reelsTabSheet(screen);
        show(screen, off);
        FullHeightComments.watchHeader(off, HEADER, true);
        tapReel(off);
        assertEquals("off, the tap went to the reel", 1, closed);

        Settings.TAP_REELS_ABOVE_COMMENTS.save(true);
        ViewGroup post = reelsTabSheet(screen);
        show(screen, post);
        FullHeightComments.watchHeader(post, HEADER, false);
        tapReel(post);
        assertEquals("a sheet that isn't over a reel sent its tap to one", 2, closed);

        ViewGroup paused = reelsTabSheet(screen);
        show(screen, paused);
        FullHeightComments.watchHeader(paused, HEADER, true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        tapReel(paused);
        assertEquals("paused, the tap went to the reel", 3, closed);
        assertEquals(0, reelTaps);
    }

    @Test
    public void tappingThroughHasItsOwnSwitch() {
        // Open comments at full height on doesn't turn it on; a full-height sheet never reaches it anyway.
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        Dialog dialog = sheetDialog();
        assertFalse(FullHeightComments.tapThrough(dialog, 20));
        assertTrue(leftToFacebook(dialog));
    }
}
