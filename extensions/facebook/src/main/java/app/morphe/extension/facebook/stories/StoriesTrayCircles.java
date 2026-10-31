/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.content.Context;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Home's stories row drawn as round pictures in one row, the way Instagram does (#260).
 *
 * <p>Facebook builds the row as a horizontal list of tiles and has no round tile to switch to, so
 * the look is applied to the views. The patch tells {@link #trayId} which view id the row's list
 * has and hands {@link #container} every pull-to-refresh wrapper around a new list. A tile added to
 * the list that carries that id is clipped to a circle cut from the middle of the tile and scaled
 * to the diameter chosen in settings. Facebook's own tile size is asked of {@link #tileWidth} and
 * {@link #tileHeight} too, so the tiles, and the row with them, come out one circle tall. Every
 * other list is left alone, and so is the row when the switch is off. Any failure here leaves the
 * tile as Facebook drew it.
 */
public final class StoriesTrayCircles {
    /** The diameter used when the setting is blank, in dp. */
    public static final int DEFAULT_DP = 72;
    public static final int MIN_DP = 40;
    public static final int MAX_DP = 120;

    private static volatile int trayId;

    /** Tiles already given a layout listener. */
    private static final Map<View, Boolean> TILES = Collections.synchronizedMap(new WeakHashMap<>());

    /** Lists already watched, so a recycled wrapper is not given a second listener. */
    private static final Map<ViewGroup, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());

    private StoriesTrayCircles() {
    }

    /** Injection point, in the row's render: the view id its list gets. Never throws. */
    public static void trayId(int id) {
        trayId = id;
    }

    /**
     * Injection point, before each pull-to-refresh wrapper a list factory returns: watches the lists
     * inside it for tiles. Never throws.
     */
    public static void container(@Nullable View wrapper) {
        try {
            if (!(wrapper instanceof ViewGroup) || !Utils.settingsReady() || !Settings.STORIES_TRAY_CIRCLES.get()) return;
            HookStatus.invoked(FamilyNames.STORIES_TRAY_CIRCLES);
            ViewGroup group = (ViewGroup) wrapper;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ViewGroup) watch((ViewGroup) child);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY_CIRCLES, "tray container", failure);
            Logger.printException(() -> "Round stories tray: could not watch a list", failure);
        }
    }

    private static void watch(ViewGroup list) {
        if (WATCHED.put(list, Boolean.TRUE) != null) return;
        list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    if (parent.getId() != trayId || trayId == 0) return;
                    if (TILES.put(child, Boolean.TRUE) == null) {
                        child.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> round(view));
                    }
                    round(child);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.STORIES_TRAY_CIRCLES, "tray tile", failure);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
    }

    /**
     * Injection point, as Facebook works out how wide a story tile is, in pixels: the chosen diameter
     * while the switch is on, so the row holds circles of that size instead of tall cards. Never throws.
     */
    public static int tileWidth(@Nullable Context context, int width) {
        try {
            if (context == null || !Utils.settingsReady() || !Settings.STORIES_TRAY_CIRCLES.get()) return width;
            float density = context.getResources().getDisplayMetrics().density;
            HookStatus.bound(FamilyNames.STORIES_TRAY_CIRCLES, "tile size");
            return Math.round(diameterDp(Settings.STORIES_TRAY_SIZE.get()) * density);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY_CIRCLES, "tile width", failure);
            return width;
        }
    }

    /**
     * Injection point, as Facebook turns a tile's width into its height: as tall as it is wide while
     * the switch is on. Facebook's tiles are almost twice as tall as wide, so a circle cut from one
     * left a blank band under the row. Never throws.
     */
    public static int tileHeight(int width, int height) {
        try {
            if (width <= 0 || !Utils.settingsReady() || !Settings.STORIES_TRAY_CIRCLES.get()) return height;
            return width;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY_CIRCLES, "tile height", failure);
            return height;
        }
    }

    /** The diameter in dp a setting value means: its digits kept inside the allowed range, the default if blank. */
    public static int diameterDp(@Nullable String saved) {
        if (saved == null || saved.trim().isEmpty() || saved.trim().length() > 4) return DEFAULT_DP;
        try {
            int dp = Integer.parseInt(saved.trim());
            return Math.max(MIN_DP, Math.min(MAX_DP, dp));
        } catch (NumberFormatException notANumber) {
            return DEFAULT_DP;
        }
    }

    /** How far a square of [side] pixels is scaled to be [diameter] pixels across, never above full size. */
    public static float scale(int diameter, int side) {
        if (side <= 0) return 1f;
        return Math.min(1f, diameter / (float) side);
    }

    /**
     * Where the story's card sits inside [tile], as left, top, right, bottom. Facebook wraps the card
     * in a tile a few pixels wider and taller than it, so a circle cut from the tile ran past the
     * card's sides and came out with flat edges on a phone. A tile holding one laid out view is cut
     * from that view, any other from inside its padding.
     */
    static int[] card(View tile) {
        if (tile instanceof ViewGroup && ((ViewGroup) tile).getChildCount() == 1) {
            View card = ((ViewGroup) tile).getChildAt(0);
            if (card.getWidth() > 0 && card.getHeight() > 0) {
                return new int[] {card.getLeft(), card.getTop(), card.getRight(), card.getBottom()};
            }
        }
        return new int[] {tile.getPaddingLeft(), tile.getPaddingTop(),
            tile.getWidth() - tile.getPaddingRight(), tile.getHeight() - tile.getPaddingBottom()};
    }

    /** Clips [tile] to a circle cut from the middle of its card and scales it to the chosen diameter. */
    static void round(View tile) {
        if (tile.getWidth() <= 0 || tile.getHeight() <= 0) return;
        int[] card = card(tile);
        int width = card[2] - card[0];
        int height = card[3] - card[1];
        if (width <= 0 || height <= 0) return;
        final int side = Math.min(width, height);
        final int left = card[0] + (width - side) / 2;
        final int top = card[1] + (height - side) / 2;
        float density = tile.getResources().getDisplayMetrics().density;
        int diameter = Math.round(diameterDp(Settings.STORIES_TRAY_SIZE.get()) * density);
        float scale = scale(diameter, side);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(left, top, left + side, top + side);
            }
        });
        tile.setClipToOutline(true);
        tile.setPivotX(left + side / 2f);
        tile.setPivotY(top + side / 2f);
        if (tile.getScaleX() != scale) {
            tile.setScaleX(scale);
            tile.setScaleY(scale);
        }
        HookStatus.bound(FamilyNames.STORIES_TRAY_CIRCLES, "tray tile");
    }

    /** For tests: forgets the watched lists and the row's id. */
    static void forgetForTests() {
        WATCHED.clear();
        TILES.clear();
        trayId = 0;
    }
}
