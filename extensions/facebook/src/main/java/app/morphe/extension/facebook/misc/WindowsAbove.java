/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.View;
import android.view.inspector.WindowInspector;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The windows an activity has open above its own: a dialog or a sheet, like the comment sheet
 * Facebook shows as a dialog fragment (#37). The framework draws them as windows of their own, so a
 * walk of the activity's window never reaches them.
 */
public final class WindowsAbove {

    private WindowsAbove() {}

    /**
     * The app's window roots [activity] has open above [decor], top one first, up to [max]. None on
     * Android 9, which has no public list of an app's windows.
     */
    public static List<View> of(Activity activity, View decor, int max) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return new ArrayList<>();
        return of(activity, decor, WindowInspector.getGlobalWindowViews(), max);
    }

    /**
     * Of [roots], in the order their windows were added, the ones after [decor] that are shown, laid
     * out and made from [activity]'s context, top one first, up to [max].
     */
    static List<View> of(Activity activity, View decor, List<View> roots, int max) {
        List<View> out = new ArrayList<>();
        for (int i = roots.size() - 1; i >= 0 && out.size() < max; i--) {
            View root = roots.get(i);
            if (root == decor) break;
            if (root.getVisibility() == View.VISIBLE && root.getWidth() > 0 && root.getHeight() > 0
                    && activityOf(root.getContext()) == activity) {
                out.add(root);
            }
        }
        return out;
    }

    @Nullable
    private static Activity activityOf(Context context) {
        for (int depth = 0; context != null && depth < 10; depth++) {
            if (context instanceof Activity) return (Activity) context;
            context = context instanceof ContextWrapper ? ((ContextWrapper) context).getBaseContext() : null;
        }
        return null;
    }
}
