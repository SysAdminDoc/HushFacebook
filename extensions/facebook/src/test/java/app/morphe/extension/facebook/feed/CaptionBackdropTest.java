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
}
