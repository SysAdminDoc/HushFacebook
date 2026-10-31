/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.robolectric.RuntimeEnvironment;

/** A stories row as the round tray hook sees it: a wrapper, a list with an id and a tile. */
public final class StoriesTrayCirclesForTests {
    public static final int TRAY = 4242;

    private StoriesTrayCirclesForTests() {
    }

    /** Runs the hook on a fresh wrapper and returns the tile it was given, laid out at 332 by 635. */
    public static View tileInRow(int listId) {
        Context context = RuntimeEnvironment.getApplication();
        StoriesTrayCircles.forgetForTests();
        StoriesTrayCircles.trayId(TRAY);
        FrameLayout wrapper = new FrameLayout(context);
        FrameLayout list = new FrameLayout(context);
        wrapper.addView(list);
        StoriesTrayCircles.container(wrapper);
        list.setId(listId);
        View tile = new View(context);
        list.addView(tile);
        tile.layout(0, 0, 332, 635);
        return tile;
    }

    /** Whether a tile in the stories row comes out round and smaller than it was. */
    public static boolean rounds() {
        View tile = tileInRow(TRAY);
        return tile.getClipToOutline() && tile.getScaleX() < 1f;
    }
}
