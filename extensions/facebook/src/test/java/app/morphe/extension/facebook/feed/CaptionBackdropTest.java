/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Dark box behind open captions (#419): an open reel caption, the one built with no line limit,
 * gets a dark rounded box with no padding, so the caption keeps its place. A folded caption, the
 * switch off, or a paused Hushfacebook keeps Facebook's look.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CaptionBackdropTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The few lines Facebook gives a folded caption (585 passes a small count like this). */
    private static final int FOLDED = 2;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.DIM_OPEN_REEL_CAPTIONS.resetToDefault();
        Settings.DIM_FOLDED_REEL_CAPTIONS.resetToDefault();
        Settings.DIM_STORY_NAMES.resetToDefault();
        Settings.DIM_REEL_NAMES.resetToDefault();
        Settings.DIM_FEED_VIDEO_NAMES.resetToDefault();
        Settings.SHOW_FULL_NAMES.resetToDefault();
        Settings.TEXT_BOX_DARKNESS.resetToDefault();
        Settings.SOFT_TEXT_BOXES.resetToDefault();
        CaptionBackdrop.header(null, null);
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FULL_POST_TEXT + ":")) return line;
        }
        return null;
    }

    @Test
    public void offByDefaultNoCaptionGetsABox() {
        assertFalse("the switch doesn't start off", Settings.DIM_OPEN_REEL_CAPTIONS.get());
        assertNull("an open caption got a box with the switch off", CaptionBackdrop.forCaption(Integer.MAX_VALUE));
        assertNull(CaptionBackdrop.forCaption(FOLDED));
    }

    @Test
    public void onAnOpenCaptionGetsADarkRoundedBoxThatKeepsItsPlace() {
        Settings.DIM_OPEN_REEL_CAPTIONS.save(true);
        Drawable box = CaptionBackdrop.forCaption(Integer.MAX_VALUE);
        assertNotNull("an open caption got no box", box);
        assertTrue(box instanceof GradientDrawable);
        GradientDrawable gradient = (GradientDrawable) box;
        assertEquals("the box isn't the subtitle black", CaptionBackdrop.COLOR,
                gradient.getColor().getDefaultColor());
        assertEquals(CaptionBackdrop.CORNER_DP * Resources.getSystem().getDisplayMetrics().density,
                gradient.getCornerRadius(), 0.01f);
        // Litho turns a background's padding into the component's padding, which would move the text.
        Rect padding = new Rect();
        assertFalse("the box carries padding", box.getPadding(padding));
        assertEquals(new Rect(), padding);
        assertNotSame("two captions share one drawable", box, CaptionBackdrop.forCaption(Integer.MAX_VALUE));
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 2, 1 found, 0 missing. Counted: "
                + CaptionBackdrop.DIMMED + " 2", statusLine());
    }

    @Test
    public void aFoldedCaptionKeepsFacebooksLook() {
        Settings.DIM_OPEN_REEL_CAPTIONS.save(true);
        assertNull("a folded caption got a box", CaptionBackdrop.forCaption(FOLDED));
        assertNull(CaptionBackdrop.forCaption(1));
        assertNull(CaptionBackdrop.forCaption(0));
    }

    @Test
    public void pausedNoCaptionGetsABox() {
        Settings.DIM_OPEN_REEL_CAPTIONS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertNull("a Hushfacebook paused by " + reason + " still gave a caption its box",
                    CaptionBackdrop.forCaption(Integer.MAX_VALUE));
            PauseForTests.resume();
        }
        assertNotNull("resumed, an open caption got no box", CaptionBackdrop.forCaption(Integer.MAX_VALUE));
    }

    /** Dark box behind folded captions (#472): a folded caption gets the very box an open one does, and an open one is left to its own switch. */
    @Test
    public void theFoldedSwitchGivesAFoldedCaptionTheSameBox() {
        assertFalse("the switch doesn't start off", Settings.DIM_FOLDED_REEL_CAPTIONS.get());
        Settings.DIM_FOLDED_REEL_CAPTIONS.save(true);
        Drawable box = CaptionBackdrop.forCaption(FOLDED);
        assertNotNull("a folded caption got no box", box);
        GradientDrawable gradient = (GradientDrawable) box;
        assertEquals(CaptionBackdrop.COLOR, gradient.getColor().getDefaultColor());
        assertEquals(CaptionBackdrop.CORNER_DP * Resources.getSystem().getDisplayMetrics().density,
                gradient.getCornerRadius(), 0.01f);
        Rect padding = new Rect();
        assertFalse("the box carries padding", box.getPadding(padding));
        assertNotNull("a one-line caption is folded too", CaptionBackdrop.forCaption(1));
        assertNull("a caption with no lines has no text to box", CaptionBackdrop.forCaption(0));
        assertNull("an open caption follows its own switch", CaptionBackdrop.forCaption(Integer.MAX_VALUE));
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 2, 1 found, 0 missing. Counted: "
                + CaptionBackdrop.FOLDED_DIMMED + " 2", statusLine());
    }

    @Test
    public void bothSwitchesOnBoxEveryCaption() {
        Settings.DIM_OPEN_REEL_CAPTIONS.save(true);
        Settings.DIM_FOLDED_REEL_CAPTIONS.save(true);
        assertNotNull(CaptionBackdrop.forCaption(FOLDED));
        assertNotNull(CaptionBackdrop.forCaption(Integer.MAX_VALUE));
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 2, 1 found, 0 missing. Counted: "
                + CaptionBackdrop.FOLDED_DIMMED + " 1, " + CaptionBackdrop.DIMMED + " 1", statusLine());

        Settings.DIM_FOLDED_REEL_CAPTIONS.save(false);
        assertNull("turned off, a folded caption keeps Facebook's look", CaptionBackdrop.forCaption(FOLDED));
        Settings.DIM_FOLDED_REEL_CAPTIONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused, a folded caption keeps Facebook's look", CaptionBackdrop.forCaption(FOLDED));
    }

    /** Box darkness (#490): a percent from 10 to 100, blank or not a number for the subtitle black. */
    @Test
    public void boxDarknessReadsItsPercent() {
        assertEquals(70, CaptionBackdrop.darknessPercent(""));
        assertEquals(70, CaptionBackdrop.darknessPercent(null));
        assertEquals(70, CaptionBackdrop.darknessPercent("dark"));
        assertEquals(45, CaptionBackdrop.darknessPercent(" 45 "));
        assertEquals("too light to see is held at the least", 10, CaptionBackdrop.darknessPercent("3"));
        assertEquals(100, CaptionBackdrop.darknessPercent("250"));
        assertEquals("70 percent is the subtitle black", CaptionBackdrop.COLOR, CaptionBackdrop.colorFor(70));
        assertEquals(0xFF000000, CaptionBackdrop.colorFor(100));
        assertEquals(0x1A000000, CaptionBackdrop.colorFor(10));
    }

    @Test
    public void everyBoxTakesTheDarknessFromItsRow() {
        Settings.DIM_OPEN_REEL_CAPTIONS.save(true);
        Settings.DIM_STORY_NAMES.save(true);
        Settings.TEXT_BOX_DARKNESS.save("40");
        assertEquals(0x66000000, ((GradientDrawable) CaptionBackdrop.forCaption(Integer.MAX_VALUE)).getColor().getDefaultColor());
        assertEquals(0x66000000, ((GradientDrawable) CaptionBackdrop.forStoryName()).getColor().getDefaultColor());
    }

    /** Soft box edges (#490): the box stays whole under the text and fades out past its edge, still with no padding. */
    @Test
    public void softEdgesFadeTheBoxOutPastItsEdge() {
        Settings.DIM_OPEN_REEL_CAPTIONS.save(true);
        Settings.SOFT_TEXT_BOXES.save(true);
        Settings.TEXT_BOX_DARKNESS.save("80");
        Drawable box = CaptionBackdrop.forCaption(Integer.MAX_VALUE);
        assertTrue("soft edges gave a plain box", box instanceof CaptionBackdrop.SoftBox);
        CaptionBackdrop.SoftBox soft = (CaptionBackdrop.SoftBox) box;
        float density = Resources.getSystem().getDisplayMetrics().density;
        assertEquals(CaptionBackdrop.colorFor(80), soft.paint.getColor());
        assertTrue("the edge isn't blurred", soft.paint.getMaskFilter() instanceof BlurMaskFilter);
        assertEquals(CaptionBackdrop.SOFT_DP * density, soft.blur, 0.01f);
        assertEquals(CaptionBackdrop.CORNER_DP * density, soft.corner, 0.01f);
        Rect padding = new Rect();
        assertFalse("the soft box carries padding", box.getPadding(padding));
        assertEquals(new Rect(), padding);
        assertEquals(PixelFormat.TRANSLUCENT, box.getOpacity());
        box.setBounds(0, 0, 200, 40);
        box.draw(new Canvas(Bitmap.createBitmap(220, 60, Bitmap.Config.ARGB_8888)));
        box.setAlpha(128);
        assertEquals("a faded box keeps its own darkness under it", Math.round(0xCC * 128 / 255f), soft.paint.getAlpha());
        assertNotSame(box, CaptionBackdrop.forCaption(Integer.MAX_VALUE));
    }

    /** Dark box behind story names (#490): the names in the stories row and on an open story. */
    @Test
    public void storyNamesFollowTheirOwnSwitch() {
        assertFalse(CaptionBackdrop.storyNamesInBuild());
        assertFalse("the switch doesn't start off", Settings.DIM_STORY_NAMES.get());
        assertNull("a story name got a box with the switch off", CaptionBackdrop.forStoryName());
        Settings.DIM_OPEN_REEL_CAPTIONS.save(true);
        assertNull("a story name follows the caption switch", CaptionBackdrop.forStoryName());

        Settings.DIM_STORY_NAMES.save(true);
        Drawable box = CaptionBackdrop.forStoryName();
        assertNotNull("a story name got no box", box);
        assertEquals(CaptionBackdrop.COLOR, ((GradientDrawable) box).getColor().getDefaultColor());
        assertFalse(box.getPadding(new Rect()));
        assertNotNull(CaptionBackdrop.forStoryName());
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 2, 1 found, 0 missing. Counted: "
                + CaptionBackdrop.STORY_NAMED + " 2", statusLine());

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused, a story name keeps Facebook's look", CaptionBackdrop.forStoryName());
    }

    /**
     * Dark box behind reel names (#490): only a header the reel's author component made gets it. The
     * same header draws names on feed posts, and those keep Facebook's look.
     */
    @Test
    public void onlyTheReelAuthorsNameGetsTheBox() {
        assertFalse(CaptionBackdrop.reelNamesInBuild());
        Object reelAuthor = new Object();
        Object feedPost = new Object();
        CaptionBackdrop.reelAuthor(reelAuthor);
        CaptionBackdrop.header(reelAuthor, null);
        assertNull("a reel name got a box with the switch off", CaptionBackdrop.forReelName());

        Settings.DIM_REEL_NAMES.save(true);
        CaptionBackdrop.reelAuthor(reelAuthor);
        CaptionBackdrop.header(feedPost, null);
        assertNull("a feed post's name got the reel box", CaptionBackdrop.forReelName());
        CaptionBackdrop.header(feedPost, Look.ON_MEDIA);
        assertNull("a feed video's name got the reel box", CaptionBackdrop.forReelName());
        CaptionBackdrop.header(reelAuthor, null);
        Drawable box = CaptionBackdrop.forReelName();
        assertNotNull("the reel author's name got no box", box);
        assertEquals(CaptionBackdrop.COLOR, ((GradientDrawable) box).getColor().getDefaultColor());
        CaptionBackdrop.header(feedPost, null);
        assertNull("the next header, a feed post's, kept the box", CaptionBackdrop.forReelName());
        CaptionBackdrop.header(null, null);
        assertNull(CaptionBackdrop.forReelName());
        assertEquals(FamilyNames.FULL_POST_TEXT + ": invoked 1, 1 found, 0 missing. Counted: "
                + CaptionBackdrop.REEL_NAMED + " 1", statusLine());

        CaptionBackdrop.header(reelAuthor, null);
        Settings.DIM_REEL_NAMES.save(false);
        assertNull("turned off, a reel name keeps Facebook's look", CaptionBackdrop.forReelName());
        Settings.DIM_REEL_NAMES.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused, a reel name keeps Facebook's look", CaptionBackdrop.forReelName());
    }

    @Test
    public void aHeaderMadeWhileTheSwitchWasOffIsntMarked() {
        Object reelAuthor = new Object();
        CaptionBackdrop.reelAuthor(reelAuthor);
        Settings.DIM_REEL_NAMES.save(true);
        CaptionBackdrop.header(reelAuthor, null);
        assertNull("a header made with the switch off got the box", CaptionBackdrop.forReelName());
        Object next = new Object();
        CaptionBackdrop.reelAuthor(next);
        CaptionBackdrop.header(next, null);
        assertNotNull("the next reel's name got no box", CaptionBackdrop.forReelName());
    }

    /** The post header's looks, by the names 588's enum gives them. */
    private enum Look { DEFAULT, ON_SURFACE, ON_MEDIA, ON_OVERLAY_LIGHT, ON_OVERLAY_EXTRA_LIGHT }

    /**
     * Dark box behind feed video names (#533): a feed post's header drawn over its video, in the
     * ON_MEDIA or an ON_OVERLAY look, gets the box. A header in its everyday look, and a reel's, which
     * has its own switch, keep Facebook's look.
     */
    @Test
    public void aNameDrawnOverAFeedVideoGetsTheBox() {
        assertFalse(CaptionBackdrop.feedVideoNamesInBuild());
        Object feedPost = new Object();
        CaptionBackdrop.header(feedPost, Look.ON_MEDIA);
        assertNull("a feed video's name got a box with the switch off", CaptionBackdrop.forReelName());

        Settings.DIM_FEED_VIDEO_NAMES.save(true);
        CaptionBackdrop.header(feedPost, Look.ON_MEDIA);
        Drawable box = CaptionBackdrop.forReelName();
        assertNotNull("a name over a feed video got no box", box);
        assertEquals(CaptionBackdrop.COLOR, ((GradientDrawable) box).getColor().getDefaultColor());
        CaptionBackdrop.header(feedPost, Look.ON_OVERLAY_LIGHT);
        assertNotNull("a name over a light overlay got no box", CaptionBackdrop.forReelName());
        CaptionBackdrop.header(feedPost, Look.ON_OVERLAY_EXTRA_LIGHT);
        assertNotNull(CaptionBackdrop.forReelName());
        for (Object look : new Object[] {Look.DEFAULT, Look.ON_SURFACE, "ON_MEDIA", null}) {
            CaptionBackdrop.header(feedPost, look);
            assertNull("a name in the " + look + " look got the box", CaptionBackdrop.forReelName());
        }
        assertTrue(statusLine(), statusLine().contains(CaptionBackdrop.MEDIA_NAMED + " 3"));

        // A reel's header keeps to Dark box behind reel names, which is off here.
        Object reelAuthor = new Object();
        CaptionBackdrop.reelAuthor(reelAuthor);
        CaptionBackdrop.header(reelAuthor, Look.ON_MEDIA);
        assertNull("a reel's name got the feed video box", CaptionBackdrop.forReelName());

        CaptionBackdrop.header(feedPost, Look.ON_MEDIA);
        Settings.DIM_FEED_VIDEO_NAMES.save(false);
        assertNull("turned off, a feed video's name keeps Facebook's look", CaptionBackdrop.forReelName());
        Settings.DIM_FEED_VIDEO_NAMES.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused, a feed video's name keeps Facebook's look", CaptionBackdrop.forReelName());
    }

    /**
     * Show full names (#533): a feed post's name Facebook cuts to a line or two gets room for four, so
     * a long one wraps instead of ending in "...". A reel's name, a limit already that big, no limit,
     * the switch off and a paused Hushfacebook keep Facebook's count.
     */
    @Test
    public void aLongPostNameGetsMoreLines() {
        assertFalse(CaptionBackdrop.fullNamesInBuild());
        Object feedPost = new Object();
        CaptionBackdrop.header(feedPost, Look.DEFAULT);
        assertEquals("the switch off changed the lines", 1, CaptionBackdrop.nameLines(1));

        Settings.SHOW_FULL_NAMES.save(true);
        CaptionBackdrop.header(feedPost, Look.DEFAULT);
        assertEquals(CaptionBackdrop.FULL_NAME_LINES, CaptionBackdrop.nameLines(1));
        assertEquals(CaptionBackdrop.FULL_NAME_LINES, CaptionBackdrop.nameLines(2));
        CaptionBackdrop.header(feedPost, Look.ON_MEDIA);
        assertEquals("a name over a feed video kept one line", CaptionBackdrop.FULL_NAME_LINES, CaptionBackdrop.nameLines(1));
        assertEquals(CaptionBackdrop.FULL_NAME_LINES, CaptionBackdrop.nameLines(CaptionBackdrop.FULL_NAME_LINES));
        assertEquals("a bigger limit got smaller", 6, CaptionBackdrop.nameLines(6));
        assertEquals("no limit got one", Integer.MAX_VALUE, CaptionBackdrop.nameLines(Integer.MAX_VALUE));
        assertEquals(0, CaptionBackdrop.nameLines(0));
        assertTrue(statusLine(), statusLine().contains(CaptionBackdrop.NAME_WIDENED + " 3"));

        Object reelAuthor = new Object();
        CaptionBackdrop.reelAuthor(reelAuthor);
        CaptionBackdrop.header(reelAuthor, null);
        assertEquals("a reel's name got more lines", 1, CaptionBackdrop.nameLines(1));

        CaptionBackdrop.header(feedPost, null);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused, a post's name got more lines", 1, CaptionBackdrop.nameLines(1));
    }
}
