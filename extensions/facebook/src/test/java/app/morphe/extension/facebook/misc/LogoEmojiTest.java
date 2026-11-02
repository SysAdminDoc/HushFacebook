/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;


import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.PatchFamilyForTests;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The emoji after the Facebook logo (#360): what a saved string keeps, where the emoji is drawn
 * (right after the wordmark, at its height), that the header's color filter reaches the wordmark and
 * not the emoji, and that a reloaded wordmark gets its emoji back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LogoEmojiTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String PUMPKIN = new String(Character.toChars(0x1F383));
    private static final String JOINER = String.valueOf((char) 0x200D);
    private static final String FAMILY = new String(Character.toChars(0x1F468)) + JOINER
            + new String(Character.toChars(0x1F469)) + JOINER + new String(Character.toChars(0x1F467));

    @After
    public void restore() {
        PatchFamilyForTests.reset();
        Settings.LOGO_EMOJI.resetToDefault();
        Settings.HEADER_TEXT.resetToDefault();
        HookStatus.clear();
    }

    /** A canvas that notes each piece of text and what its paint carried. */
    private static final class Recorder extends Canvas {
        final List<String> texts = new ArrayList<>();
        final List<float[]> places = new ArrayList<>();
        final List<ColorFilter> filters = new ArrayList<>();

        Recorder() {
            super(Bitmap.createBitmap(400, 60, Bitmap.Config.ARGB_8888));
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            texts.add(text);
            places.add(new float[]{x, y});
            filters.add(paint.getColorFilter());
        }
    }

    /** A wordmark that notes the bounds it was drawn in and the filter it was given. */
    private static final class Wordmark extends Drawable {
        final Rect drawnIn = new Rect();
        ColorFilter filter;
        boolean drawn;

        @Override
        public void draw(Canvas canvas) {
            drawn = true;
            drawnIn.set(getBounds());
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            filter = colorFilter;
        }

        @Override
        public ColorFilter getColorFilter() {
            return filter;
        }

        @Override
        public int getIntrinsicWidth() {
            return 120;
        }

        @Override
        public int getIntrinsicHeight() {
            return 24;
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static ImageView header(Wordmark wordmark) {
        Context context = RuntimeEnvironment.getApplication();
        ImageView logo = new ImageView(context);
        logo.setImageDrawable(wordmark);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        return logo;
    }

    @Test
    public void cleanKeepsOneShortLineWithItsEmoji() {
        assertEquals("", LogoEmoji.clean(null));
        assertEquals("", LogoEmoji.clean("   "));
        assertEquals(PUMPKIN, LogoEmoji.clean("  " + PUMPKIN + "\n"));
        assertEquals("a family keeps its joiners", FAMILY, LogoEmoji.clean(FAMILY));
        assertEquals("spaces and controls go", "ab", LogoEmoji.clean(" a\tb\u0007 "));
        assertEquals("a joiner with nothing to join goes", PUMPKIN, LogoEmoji.clean(JOINER + PUMPKIN + JOINER + JOINER));
        assertEquals("at most eight code points", 8,
                LogoEmoji.clean("123456789" + PUMPKIN).codePointCount(0, LogoEmoji.clean("123456789" + PUMPKIN).length()));
        assertTrue(LogoEmoji.isClean(PUMPKIN));
        assertFalse(LogoEmoji.isClean(" " + PUMPKIN));
    }

    @Test
    public void theEmojiIsDrawnRightAfterTheWordmarkAtItsHeight() {
        Wordmark wordmark = new Wordmark();
        LogoEmoji.Tail tail = new LogoEmoji.Tail(wordmark, PUMPKIN, 24);
        assertEquals("as tall as the wordmark", 24, tail.getIntrinsicHeight());
        assertTrue("wider than the wordmark by the emoji", tail.getIntrinsicWidth() > 120);

        tail.setBounds(0, 0, tail.getIntrinsicWidth(), 24);
        Recorder canvas = new Recorder();
        tail.draw(canvas);

        assertTrue("the wordmark is drawn", wordmark.drawn);
        assertEquals("at its own width, at the left", new Rect(0, 0, 120, 24), wordmark.drawnIn);
        assertEquals(1, canvas.texts.size());
        assertEquals(PUMPKIN, canvas.texts.get(0));
        assertTrue("the emoji starts after the wordmark ends", canvas.places.get(0)[0] >= 120);
        assertTrue("and inside the drawable", canvas.places.get(0)[0] < tail.getIntrinsicWidth());
    }

    @Test
    public void theHeadersColorGoesToTheWordmarkAndNotTheEmoji() {
        Wordmark wordmark = new Wordmark();
        ImageView logo = header(wordmark);
        Settings.LOGO_EMOJI.save(PUMPKIN);
        HeaderText.dress(logo);
        Drawable shown = logo.getDrawable();
        assertTrue(shown instanceof LogoEmoji.Tail);

        PorterDuffColorFilter white = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        logo.setColorFilter(white);
        assertSame("the wordmark took the header's color", white, wordmark.filter);

        shown.setBounds(0, 0, shown.getIntrinsicWidth(), shown.getIntrinsicHeight());
        Recorder canvas = new Recorder();
        shown.draw(canvas);
        assertEquals(1, canvas.filters.size());
        assertNull("the emoji was drawn in its own colors", canvas.filters.get(0));
    }

    @Test
    public void savedEmojiGoesAfterFacebooksWordmarkAndLogsWhereItAttached() {
        Wordmark wordmark = new Wordmark();
        ImageView logo = header(wordmark);
        Settings.LOGO_EMOJI.save(PUMPKIN);

        HeaderText.dress(logo);

        assertTrue(logo.getDrawable() instanceof LogoEmoji.Tail);
        LogoEmoji.Tail tail = (LogoEmoji.Tail) logo.getDrawable();
        assertSame("Facebook's wordmark stays", wordmark, tail.base);
        assertEquals(PUMPKIN, tail.emoji());
        assertEquals("the wordmark's height", 24, tail.getIntrinsicHeight());
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
    }

    @Test
    public void noEmojiLeavesTheWordmarkAlone() {
        Wordmark wordmark = new Wordmark();
        ImageView logo = header(wordmark);
        HeaderText.dress(logo);
        assertSame(wordmark, logo.getDrawable());
        assertEquals(ImageView.ScaleType.MATRIX, logo.getScaleType());
    }

    @Test
    public void theEmojiFollowsTheHeaderTextWhenThereIsOne() {
        Wordmark wordmark = new Wordmark();
        ImageView logo = header(wordmark);
        Settings.HEADER_TEXT.save("Matt");
        Settings.LOGO_EMOJI.save(PUMPKIN);

        HeaderText.dress(logo);

        assertTrue(logo.getDrawable() instanceof LogoEmoji.Tail);
        Drawable base = ((LogoEmoji.Tail) logo.getDrawable()).base;
        assertTrue("the words are what the emoji follows", base instanceof HeaderText.TextMark);
        assertEquals(24, logo.getDrawable().getIntrinsicHeight());
    }

    @Test
    public void aReloadedWordmarkGetsItsEmojiBack() {
        Wordmark wordmark = new Wordmark();
        ImageView logo = header(wordmark);
        Settings.LOGO_EMOJI.save(PUMPKIN);
        HeaderText.dress(logo);
        Drawable first = logo.getDrawable();
        assertFalse("with the emoji in place, drawing goes on", logo.getViewTreeObserver().dispatchOnPreDraw());

        Wordmark reloaded = new Wordmark();
        logo.setImageDrawable(reloaded);
        assertTrue("that draw is skipped for the one with the emoji", logo.getViewTreeObserver().dispatchOnPreDraw());

        assertNotSame(first, logo.getDrawable());
        assertTrue(logo.getDrawable() instanceof LogoEmoji.Tail);
        assertSame("the new wordmark has the emoji", reloaded, ((LogoEmoji.Tail) logo.getDrawable()).base);
        assertNotNull(((LogoEmoji.Tail) logo.getDrawable()).emoji());
    }

    @Test
    public void aBitmapWordmarkWorksToo() {
        Context context = RuntimeEnvironment.getApplication();
        ImageView logo = new ImageView(context);
        logo.setImageDrawable(new BitmapDrawable(context.getResources(),
                Bitmap.createBitmap(120, 24, Bitmap.Config.ARGB_8888)));
        Settings.LOGO_EMOJI.save(FAMILY);
        HeaderText.dress(logo);
        assertTrue(logo.getDrawable() instanceof LogoEmoji.Tail);
        assertEquals(24, logo.getDrawable().getIntrinsicHeight());
        logo.getDrawable().setBounds(0, 0, logo.getDrawable().getIntrinsicWidth(), 24);
        logo.getDrawable().draw(new Canvas(Bitmap.createBitmap(300, 24, Bitmap.Config.ARGB_8888)));
    }
}
