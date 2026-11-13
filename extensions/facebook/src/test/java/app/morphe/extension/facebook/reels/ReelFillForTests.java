/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.content.res.Resources;
import android.util.DisplayMetrics;

/** Asks {@link ReelFill} about a reel the way Facebook's sizing would, on whatever screen the test has. */
public final class ReelFillForTests {
    private ReelFillForTests() {
    }

    /**
     * Hands the hook a container the size of the screen in fit mode, holding a video a tenth wider
     * than the screen's shape. True when the hook asked for fill, which is the switch changing what
     * Facebook would have done.
     */
    public static boolean fillsAFullScreenReel() {
        DisplayMetrics screen = Resources.getSystem().getDisplayMetrics();
        double box = (double) screen.widthPixels / screen.heightPixels;
        return ReelFill.fills(box * 1.1, screen.widthPixels, screen.heightPixels, ReelFill.FIT, 0);
    }
}
