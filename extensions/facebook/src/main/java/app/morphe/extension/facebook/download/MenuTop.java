/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Where a post menu's save row goes. The row under a post holds only icons with counts and gives a
 * new child no room, so Hushfacebook's Download rows ride in the post's three-dot menu. With
 * {@link Settings#POST_MENU_DOWNLOAD_FIRST} on they sit above Facebook's rows, else below them.
 * "Above" means first in the menu's own order. Facebook's bottom sheet then files each row by the
 * kind and section tag Facebook gave it ({@code Vq3.A05} on 583) and shows its tagged top rows
 * before the untagged ones, so ours would lead the options list under those, which on a video or
 * photo post is the More options page. So the rows put on top of Facebook's post menu are
 * remembered, and the post menu sheet's hook moves them to the end of the first page, above
 * Playback speed (#603, {@link app.morphe.extension.facebook.misc.PostMenuItems}).
 *
 * <p>The two kinds of menu sort differently. Facebook's post menu keeps its rows in the order
 * number each was added with, smallest first, so a row goes on top with a number below every
 * other row's (583 on the S22, 2026-10-18: the category below sank Save photo to the bottom).
 * Android's own menu sorts by category first and refuses a negative number, and there category 5
 * (0x50000) ranks ahead of every public category, so a row added in it lands on top.
 */
public final class MenuTop {
    private MenuTop() {}

    /** A category no public constant names, which Android's menu sorts before all the others. */
    static final int FIRST_CATEGORY = 0x50000;

    /** How many rows put on top are remembered: a post menu gets three at most. */
    static final int REMEMBERED = 8;

    /** The rows put on top of Facebook's post menu lately, oldest first, held weakly. */
    private static final List<WeakReference<Object>> ROWS = new ArrayList<>();

    /** Adds [title] to [menu], on top when the switch is on and below Facebook's rows otherwise. */
    static MenuItem add(Menu menu, CharSequence title) {
        if (!Settings.POST_MENU_DOWNLOAD_FIRST.get()) return menu.add(title);
        if (androids(menu)) return menu.add(Menu.NONE, Menu.NONE, FIRST_CATEGORY, title);
        int lowest = 0;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem row = menu.getItem(i);
            if (row != null) lowest = Math.min(lowest, row.getOrder());
        }
        MenuItem row = menu.add(Menu.NONE, Menu.NONE, lowest == Integer.MIN_VALUE ? lowest : lowest - 1, title);
        remember(row);
        return row;
    }

    /** Keeps [row], so the sheet's hook can tell it from Facebook's rows by the object itself. */
    static void remember(@Nullable Object row) {
        if (row == null) return;
        synchronized (ROWS) {
            for (Iterator<WeakReference<Object>> kept = ROWS.iterator(); kept.hasNext(); ) {
                if (kept.next().get() == null) kept.remove();
            }
            while (ROWS.size() >= REMEMBERED) ROWS.remove(0);
            ROWS.add(new WeakReference<>(row));
        }
    }

    static void forgetForTests() {
        synchronized (ROWS) {
            ROWS.clear();
        }
    }

    /** Whether [item] is a row this put on top of Facebook's post menu. */
    public static boolean isRow(@Nullable Object item) {
        if (item == null) return false;
        synchronized (ROWS) {
            for (WeakReference<Object> kept : ROWS) {
                if (kept.get() == item) return true;
            }
        }
        return false;
    }

    /**
     * Whether the post menu sheet's hook puts the rows on the menu's first page (#603): the switch
     * on, not paused, with a row put on top lately. Never throws.
     */
    public static boolean onFirstPage() {
        try {
            if (!Utils.settingsReady() || !Settings.POST_MENU_DOWNLOAD_FIRST.get()) return false;
            synchronized (ROWS) {
                for (WeakReference<Object> kept : ROWS) {
                    if (kept.get() != null) return true;
                }
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "post menu Download on the first page", failure);
            return false;
        }
    }

    /** Whether [menu] is one of Android's own, the platform's or AndroidX's, rather than Facebook's. */
    static boolean androids(Menu menu) {
        String name = menu.getClass().getName();
        return name.startsWith("com.android.internal.view.menu.") || name.startsWith("androidx.appcompat.view.menu.")
                || name.startsWith("android.");
    }
}
