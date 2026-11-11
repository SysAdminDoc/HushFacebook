/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SavedShortcut;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Saved button in search (#433): a bookmark at the end of Facebook's search bar that opens Saved,
 * like the Saved button on Instagram's Search tab.
 *
 * <p>The search bar is a LinearLayout of the search field, the clear button and two stubs. The
 * patch calls {@link #attach} from each of its constructors once those children are there, and the
 * button goes after them, so the field gives up the room it takes. The tap opens Facebook's own
 * Saved screen through the same route as the Menu's Saved row ({@link SavedShortcut#open}), and a
 * build without that route gets a toast.
 *
 * <p>With the switch off, or Hushfacebook paused, the bar is left as Facebook builds it. A button
 * already in a bar hides itself when it's shown again with the switch off. Nothing here throws into
 * Facebook.
 */
public final class SearchSavedButton {
    /** Marks the button, so a bar never gets two. */
    static final String TAG = "hushfacebook_search_saved";

    /** Counted under the patch's name each time a tap opens Saved, or finds no Saved route. */
    static final String OPENED = "Saved opened from the search bar";
    static final String UNAVAILABLE = "Saved not available";

    static final String HOOK = "Search bar";

    private static final String FAMILY = FamilyNames.SEARCH_SAVED_BUTTON;

    /** The button's width, and the icon's box inside it. */
    static final int WIDTH_DP = 40;
    static final int ICON_DP = 24;

    /** Facebook's secondary icon grey, for a bar whose field has no hint colour to borrow. */
    private static final int FALLBACK_COLOR = 0xFF65676B;

    private SearchSavedButton() {
    }

    /** Puts the button at the end of a search bar Facebook just built. Called from its constructors. */
    public static void attach(LinearLayout bar) {
        try {
            HookStatus.invoked(FAMILY);
            if (bar == null || !Utils.settingsReady() || !Settings.SEARCH_SAVED_BUTTON.get()) return;
            if (bar.findViewWithTag(TAG) != null) return;
            bar.addView(button(bar), new LinearLayout.LayoutParams(px(bar.getContext(), WIDTH_DP),
                    LinearLayout.LayoutParams.MATCH_PARENT));
            HookStatus.bound(FAMILY, HOOK);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    static ImageView button(LinearLayout bar) {
        Context context = bar.getContext();
        ImageView button = new ImageView(context);
        button.setTag(TAG);
        button.setImageDrawable(new Bookmark(color(bar), px(context, 2)));
        int side = (px(context, WIDTH_DP) - px(context, ICON_DP)) / 2;
        button.setPadding(side, 0, side, 0);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(L10n.t(context, "Saved"));
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(SearchSavedButton::onTap);
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                view.setVisibility(shown() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
            }
        });
        return button;
    }

    /** Whether a button already in a bar shows: the switch is still on and Hushfacebook isn't paused. */
    static boolean shown() {
        return Utils.settingsReady() && Settings.SEARCH_SAVED_BUTTON.get();
    }

    /** The tap. Opens Saved, or says this build can't. */
    static void onTap(View button) {
        try {
            if (!shown()) return;
            Activity activity = activityOf(button.getContext());
            Context context = activity != null ? activity : button.getContext();
            if (SavedShortcut.open(context)) {
                HookStatus.counted(FAMILY, OPENED);
                return;
            }
            HookStatus.counted(FAMILY, UNAVAILABLE);
            Logger.printInfo(() -> "Saved button in search: couldn't open Saved");
            Utils.showToastShort(L10n.t(context, "Saved isn't available in this build."));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** The search field's hint colour, which Facebook's clear button and icons in the field share. */
    static int color(LinearLayout bar) {
        for (int at = 0; at < bar.getChildCount(); at++) {
            View child = bar.getChildAt(at);
            if (child instanceof TextView) return ((TextView) child).getHintTextColors().getDefaultColor();
        }
        return FALLBACK_COLOR;
    }

    private static int px(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /** The activity a view's context wraps, or null. The depth guards against a wrapper that wraps itself. */
    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 20; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** A bookmark outline, drawn in code since Facebook's APK has none of ours. */
    static final class Bookmark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Bookmark(int color, float stroke) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect box = getBounds();
            float size = Math.min(box.width(), box.height());
            float x = box.exactCenterX();
            float y = box.exactCenterY();
            float half = size * 0.28f;
            float top = y - size * 0.36f;
            float bottom = y + size * 0.36f;
            path.rewind();
            path.moveTo(x - half, top);
            path.lineTo(x + half, top);
            path.lineTo(x + half, bottom);
            path.lineTo(x, bottom - size * 0.2f);
            path.lineTo(x - half, bottom);
            path.close();
            canvas.drawPath(path, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return -1;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
