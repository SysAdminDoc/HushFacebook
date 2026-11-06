/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The feed video buttons look like Facebook's speaker icon: a small see-through disc, no dark square (#301). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeedVideoButtonLookTest {
    private static final float DENSITY = 2.625f;
    private static final int SIZE = Math.round(FeedVideoControls.SIZE_DP * DENSITY);

    private static Bitmap draw(boolean cast, boolean playing) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        new FeedVideoControls.Look().draw(new Canvas(bitmap), SIZE, SIZE, DENSITY, cast, playing);
        return bitmap;
    }

    @Test public void eachSymbolSitsOnASmallSeeThroughDisc() {
        int radius = Math.round(FeedVideoControls.DISC_DP / 2f * DENSITY);
        for (boolean[] kind : new boolean[][] {{false, false}, {false, true}, {true, false}}) {
            Bitmap bitmap = draw(kind[0], kind[1]);
            // The target's corners and the ring outside the disc stay clear, so no square shows.
            assertEquals(0, Color.alpha(bitmap.getPixel(1, 1)));
            assertEquals(0, Color.alpha(bitmap.getPixel(SIZE - 2, SIZE - 2)));
            assertEquals(0, Color.alpha(bitmap.getPixel(SIZE / 2, SIZE / 2 - radius - 3)));
            // Just inside the disc's edge it's a light black, the video shows through.
            int edge = bitmap.getPixel(SIZE / 2, SIZE / 2 - radius + 3);
            assertEquals(0x66, Color.alpha(edge));
            assertEquals(0, Color.red(edge));
            // The symbol is white.
            int white = 0;
            for (int x = 0; x < SIZE; x++) {
                for (int y = 0; y < SIZE; y++) if (bitmap.getPixel(x, y) == Color.WHITE) white++;
            }
            assertTrue("a white symbol for cast=" + kind[0] + " playing=" + kind[1], white > SIZE);
        }
    }

    private static Bitmap draw(int kind, String speed) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        new FeedVideoControls.Look().draw(new Canvas(bitmap), SIZE, SIZE, DENSITY, kind, false, speed);
        return bitmap;
    }

    private static int white(Bitmap bitmap) {
        int white = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) if (bitmap.getPixel(x, y) == Color.WHITE) white++;
        }
        return white;
    }

    @Test public void theSkipAndSpeedSymbolsDrawWhiteInsideTheirTarget() {
        for (int kind : new int[] {FeedVideoControls.BACK, FeedVideoControls.AHEAD, FeedVideoControls.SPEED}) {
            Bitmap bitmap = draw(kind, "1x");
            assertEquals(0, Color.alpha(bitmap.getPixel(1, 1)));
            assertEquals(0, Color.alpha(bitmap.getPixel(SIZE - 2, SIZE - 2)));
            assertTrue("a white symbol for kind " + kind, white(bitmap) > (kind == FeedVideoControls.SPEED ? 20 : SIZE));
        }
        assertFalse("back and ahead point different ways",
                draw(FeedVideoControls.BACK, "1x").sameAs(draw(FeedVideoControls.AHEAD, "1x")));
        assertFalse("the speed button writes its speed",
                draw(FeedVideoControls.SPEED, "1x").sameAs(draw(FeedVideoControls.SPEED, "1.25x")));
    }
}
