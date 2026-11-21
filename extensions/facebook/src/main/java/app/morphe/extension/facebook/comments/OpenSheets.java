/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The comment sheets Facebook has set up, by their views, weakly (#535). Open comments at full
 * height's sheet hook hands each one over ({@link FullHeightComments#watchHeader}), whatever its
 * switches say. A sheet counts as open while its views show on screen, so a closed sheet whose
 * views Facebook keeps around, or one slid out of sight, doesn't.
 *
 * <p>The feed video seek bar sits in the activity's own content frame, while a comment sheet can
 * end above a three-button navigation bar: a video behind it kept its bar showing in the strip
 * between the two, right where a tap meant for the sheet or the back button lands.
 */
public final class OpenSheets {
    private static final Map<View, Boolean> SHEETS = Collections.synchronizedMap(new WeakHashMap<>());

    private OpenSheets() {
    }

    /** Hands over a comment sheet's views as Facebook sets them up. */
    public static void opened(@Nullable View content) {
        if (content != null) SHEETS.put(content, Boolean.TRUE);
    }

    /** Whether any comment sheet handed over shows on screen now. */
    public static boolean anyShown() {
        List<View> sheets;
        synchronized (SHEETS) {
            sheets = new ArrayList<>(SHEETS.keySet());
        }
        Rect rect = new Rect();
        for (View sheet : sheets) {
            if (sheet != null && sheet.isShown() && sheet.getGlobalVisibleRect(rect)) return true;
        }
        return false;
    }

    /** Forgets every sheet. For tests. */
    public static void reset() {
        SHEETS.clear();
    }
}
