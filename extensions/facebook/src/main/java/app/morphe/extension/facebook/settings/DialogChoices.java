/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

/** Styles the framework's real list rows without replacing their selection or checkbox behavior. */
final class DialogChoices extends BaseAdapter {
    private final ListAdapter source;
    private final ScreenColors colors;

    DialogChoices(ListAdapter source, ScreenColors colors) {
        this.source = source;
        this.colors = colors;
    }

    /** Puts [adapter] on [list] with the same rows checked: a new adapter clears ListView's checks. */
    static void swap(ListView list, ListAdapter adapter) {
        SparseBooleanArray checked = list.getCheckedItemPositions();
        checked = checked == null ? null : checked.clone();
        list.setAdapter(adapter);
        if (checked == null) return;
        for (int index = 0; index < checked.size(); index++) {
            if (checked.valueAt(index)) list.setItemChecked(checked.keyAt(index), true);
        }
    }

    @Override public int getCount() { return source.getCount(); }
    @Override public Object getItem(int position) { return source.getItem(position); }
    @Override public long getItemId(int position) { return source.getItemId(position); }
    @Override public boolean hasStableIds() { return source.hasStableIds(); }
    @Override public boolean isEnabled(int position) { return source.isEnabled(position); }
    @Override public boolean areAllItemsEnabled() { return source.areAllItemsEnabled(); }
    @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
    @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        if (row instanceof TextView) {
            TextView choice = (TextView) row;
            float density = row.getResources().getDisplayMetrics().density;
            choice.setTextColor(ScreenColors.dimmedWhenDisabled(colors.title));
            choice.setTextSize(16);
            choice.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            choice.setSingleLine(false);
            choice.setMaxLines(Integer.MAX_VALUE);
            if (choice instanceof CheckedTextView) {
                choice.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                choice.setMinHeight(Math.round(48 * density));
                choice.setMinimumHeight(Math.round(48 * density));
                ColorStateList indicator = new ColorStateList(new int[][]{
                        {-android.R.attr.state_enabled, android.R.attr.state_checked},
                        {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                        new int[]{ScreenColors.half(colors.heading), ScreenColors.half(colors.summary),
                                colors.heading, colors.summary});
                ((CheckedTextView) choice).setCheckMarkTintList(indicator);
                // Material's framework layout uses a start drawable for its radio indicator.
                for (android.graphics.drawable.Drawable drawable : choice.getCompoundDrawablesRelative()) {
                    if (drawable != null) drawable.mutate().setTintList(indicator);
                }
                GradientDrawable background = new GradientDrawable();
                background.setColor(colors.choiceFill(Color.TRANSPARENT));
                background.setStroke(Math.round(density), colors.interactionOutline(Color.TRANSPARENT));
                background.setCornerRadius(Math.round(ScreenColors.ELEMENT_RADIUS * density));
                // The picked row's tint sits inside the panel's rounded corners instead of running
                // into its edges, and the radio keeps its place. The press shows in the same shape:
                // masked by the clear background of a row that isn't picked, it didn't show at all.
                int inset = Math.round(12 * density);
                GradientDrawable press = new GradientDrawable();
                press.setColor(Color.WHITE);
                press.setCornerRadius(Math.round(ScreenColors.ELEMENT_RADIUS * density));
                choice.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading),
                        new InsetDrawable(background, inset, 0, inset, 0),
                        new InsetDrawable(press, inset, 0, inset, 0)).mutate());
                // A choice is a compact full-width row, with room to grow when its text wraps.
                // Rounding both sides up at densities such as 450dpi exceeds the measured 48dp row.
                int verticalPadding = (int) (8 * density);
                choice.setPaddingRelative(Math.round(24 * density), verticalPadding,
                        Math.round(24 * density), verticalPadding);
                return row;
            }
            int gap = Math.round(4 * density);
            int side = Math.round(18 * density);
            choice.setMinHeight(Math.round(48 * density) + 2 * gap);
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(colors.choiceFill(colors.card));
            surface.setStroke(Math.round(density), colors.interactionOutline(colors.outline));
            surface.setCornerRadius(Math.round(ScreenColors.CONTAINER_RADIUS * density));
            choice.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading),
                    new InsetDrawable(surface, side, gap, side, gap), null).mutate());
            int verticalPadding = (int) (12 * density) + gap;
            choice.setPaddingRelative(side + Math.round(16 * density), verticalPadding,
                    side + Math.round(16 * density), verticalPadding);
        }
        return row;
    }
}
