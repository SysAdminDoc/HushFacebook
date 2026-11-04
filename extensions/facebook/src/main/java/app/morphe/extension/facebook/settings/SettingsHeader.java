/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The top of the settings page: Hushfacebook's logo, its name in Facebook's blues and the
 * version line, centred over the search and the rows. Nothing on it can be tapped. HushGram's is a
 * row of its list; this list's rows are counted and placed by position, so the three parts go
 * straight into the page's {@link HeaderColumn}, which slides them up under the bar as the list
 * scrolls.
 */
final class SettingsHeader {
    /** Tags of its parts, for the tests. */
    static final String ICON = "hushfacebook-header-icon";
    static final String NAME = "hushfacebook-header-name";
    static final String VERSION = "hushfacebook-header-version";
    /** The drawable the settings patch adds for the icon, as SettingsLogo.kt names it. */
    static final String LOGO = "hushfacebook_settings_logo";

    private SettingsHeader() {
    }

    /** Adds the icon, the name and the version line to [column], in [colors]. Returns the name. */
    static TextView addTo(LinearLayout column, ScreenColors colors) {
        Context context = column.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int side = Math.round(24 * density);

        ImageView icon = new ImageView(context);
        icon.setTag(ICON);
        icon.setImageDrawable(icon(context, colors));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // The name under it says the same.
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(64 * density);
        LinearLayout.LayoutParams iconSpace = new LinearLayout.LayoutParams(size, size);
        iconSpace.gravity = Gravity.CENTER_HORIZONTAL;
        iconSpace.topMargin = Math.round(28 * density);
        column.addView(icon, iconSpace);

        TextView name = new TextView(context);
        name.setTag(NAME);
        name.setText("Hushfacebook");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setAccessibilityHeading(true);
        ShimmerTitle.wear(name, colors.background);
        LinearLayout.LayoutParams nameSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameSpace.gravity = Gravity.CENTER_HORIZONTAL;
        nameSpace.topMargin = Math.round(12 * density);
        nameSpace.setMarginStart(side);
        nameSpace.setMarginEnd(side);
        column.addView(name, nameSpace);

        TextView version = new TextView(context);
        version.setTag(VERSION);
        version.setText(L10n.f("Version %1$s for Facebook %2$s",
                L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName())));
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        version.setTextColor(colors.summary);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionSpace.gravity = Gravity.CENTER_HORIZONTAL;
        versionSpace.topMargin = Math.round(4 * density);
        versionSpace.bottomMargin = Math.round(20 * density);
        versionSpace.setMarginStart(side);
        versionSpace.setMarginEnd(side);
        column.addView(version, versionSpace);
        return name;
    }

    /**
     * Hushfacebook's logo, which the settings patch writes into res/drawable-nodpi so it ships with
     * every patched build. Without it, the app's own launcher icon, which Icon style may have redrawn,
     * or the patched mark the Built-in changes section wears when the package has none to give.
     */
    static Drawable icon(Context context, ScreenColors colors) {
        Drawable own = bundledLogo(context);
        if (own == null) own = launcherIcon(context);
        return own != null ? own : SettingsIcons.icon(context, SettingsIcons.PATCHED, colors.heading);
    }

    @Nullable
    private static Drawable bundledLogo(Context context) {
        try {
            int id = context.getResources().getIdentifier(LOGO, "drawable", context.getPackageName());
            return id == 0 ? null : context.getResources().getDrawable(id, context.getTheme());
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not load the settings logo", ex);
            return null;
        }
    }

    @Nullable
    private static Drawable launcherIcon(Context context) {
        try {
            int id = context.getApplicationInfo().icon;
            if (id == 0) return null;
            return context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not load the launcher icon", ex);
            return null;
        }
    }
}
