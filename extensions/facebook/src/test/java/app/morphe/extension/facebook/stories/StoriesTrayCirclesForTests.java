/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
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

    /**
     * A tile shaped like the S22's: 332 by 635 holding its card at 6, 23 to 326, 613, the margins
     * that left the circles flat sided when they were cut from the tile.
     */
    public static ViewGroup tileWithCard() {
        Context context = RuntimeEnvironment.getApplication();
        StoriesTrayCircles.forgetForTests();
        StoriesTrayCircles.trayId(TRAY);
        FrameLayout wrapper = new FrameLayout(context);
        FrameLayout list = new FrameLayout(context);
        wrapper.addView(list);
        StoriesTrayCircles.container(wrapper);
        list.setId(TRAY);
        // Leaves the card where it was put, as Facebook's tile does, where a FrameLayout would move it.
        ViewGroup tile = new ViewGroup(context) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
            }
        };
        View card = new View(context);
        tile.addView(card);
        list.addView(tile);
        card.layout(6, 23, 326, 613);
        tile.layout(0, 0, 332, 635);
        return tile;
    }

    /** Whether a tile in the stories row comes out round and smaller than it was. */
    public static boolean rounds() {
        View tile = tileInRow(TRAY);
        return tile.getClipToOutline() && tile.getScaleX() < 1f;
    }
}
