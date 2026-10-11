/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.theme.TonePalette;
import app.morphe.extension.shared.settings.preference.ImmediateAction;

/**
 * The colours of the Hushfacebook screen, its rows and its dialogs when the Material You theme is
 * in this build. The screen then follows the phone's dark or light setting, in the tones of the
 * wallpaper palette (Android 12 and newer) or of the fixed palette Android 11 gets.
 *
 * <p>A tone is a lightness, so the contrast of each pair below is the same for any wallpaper. The
 * pairs are Material 3's: the page is the neutral at tone 10 in dark and 99 in light, text is the
 * neutral at 90 or 10, secondary text the neutral variant at 80 or 30, and section titles, switches
 * and dialog buttons the accent at 80 or 40. Every pair meets WCAG 2.2 AA with room to spare,
 * which ScreenColorsTest works out.
 *
 * <p>Without the theme in the build, {@link #forScreen} answers null and the screen keeps the black
 * page and dark Material rows it always had.
 */
final class ScreenColors {
    final boolean light;
    /** The page, behind the title bar and every row. */
    final int background;
    /** A dialog's surface. */
    final int dialog;
    /** Row and dialog titles, the title bar and its back arrow. */
    final int title;
    /** Row summaries and a dialog's message. */
    final int summary;
    /** Section titles. */
    final int heading;
    /** A switch that's on, and the buttons of a dialog. */
    final int accent;
    /** A switch that's off. */
    final int switchOff;
    /** Grouped preference rows and their edge. */
    final int card;
    final int outline;
    /** Text on the filled primary dialog action. */
    final int onAccent;

    /** The colours the screen on show was built with, or null for the black page. */
    @Nullable
    static volatile ScreenColors shown;
    static final ScreenColors DEFAULT = new ScreenColors();

    private ScreenColors() {
        light = false;
        background = Color.BLACK;
        dialog = 0xFF11151D;
        title = 0xFFF3F5F9;
        summary = 0xFFA6AFBE;
        heading = 0xFF6AA7FF;
        accent = 0xFF236BE7;
        switchOff = 0xFF858D9C;
        card = 0xFF11151D;
        outline = 0xFF252B36;
        onAccent = Color.WHITE;
    }

    private ScreenColors(TonePalette palette, boolean light) {
        this.light = light;
        if (light) {
            background = palette.tone(TonePalette.NEUTRAL, 99);
            dialog = palette.tone(TonePalette.NEUTRAL, 95);
            title = palette.tone(TonePalette.NEUTRAL, 10);
            summary = palette.tone(TonePalette.NEUTRAL_VARIANT, 30);
            heading = palette.tone(TonePalette.ACCENT, 40);
            accent = palette.tone(TonePalette.ACCENT, 40);
            switchOff = palette.tone(TonePalette.NEUTRAL_VARIANT, 50);
            card = palette.tone(TonePalette.NEUTRAL, 95);
            outline = palette.tone(TonePalette.NEUTRAL_VARIANT, 80);
            onAccent = Color.WHITE;
        } else {
            background = palette.tone(TonePalette.NEUTRAL, 10);
            dialog = palette.tone(TonePalette.NEUTRAL, 20);
            title = palette.tone(TonePalette.NEUTRAL, 90);
            summary = palette.tone(TonePalette.NEUTRAL_VARIANT, 80);
            heading = palette.tone(TonePalette.ACCENT, 80);
            accent = palette.tone(TonePalette.ACCENT, 80);
            switchOff = palette.tone(TonePalette.NEUTRAL_VARIANT, 60);
            card = palette.tone(TonePalette.NEUTRAL, 20);
            outline = palette.tone(TonePalette.NEUTRAL_VARIANT, 30);
            onAccent = background;
        }
    }

    /**
     * The thumb of a switch that's off, read against its track. On a dark wallpaper palette the
     * title's light tone sat at 2.46:1 on the neutral track, under the 3:1 a control's state
     * needs; the dark tone that fills the on thumb reads above 5:1 there. The black page's white
     * and the light page's dark title already clear 3:1.
     */
    int offThumb() {
        return light ? title : onAccent;
    }

    static ScreenColors of(TonePalette palette, boolean light) {
        return new ScreenColors(palette, light);
    }

    /**
     * [color], at Material's 38% on a disabled row. A plain colour replaced the theme's own
     * disabled state, so a row that ignored taps, such as Import while an export runs, looked
     * the same as one that would take them.
     */
    static ColorStateList dimmedWhenDisabled(int color) {
        int dimmed = (color & 0x00FFFFFF) | 0x61000000;
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{dimmed, color});
    }

    /**
     * The text of a dialog's outlined actions, such as Cancel. The black page's accent reads at
     * 3.4:1 on its dialog, under the 4.5:1 text needs, so these take the heading colour. The
     * Material You palettes draw both in one tone, so only the black page changes.
     */
    int secondaryActionText() {
        return heading;
    }

    /**
     * The colours for a screen shown in this context: the phone's palette, dark or light as the
     * context's configuration says. Null when the Material You theme isn't in this build.
     */
    @Nullable
    static ScreenColors forScreen(Context context) {
        if (!PatchFamily.MATERIAL_YOU_THEME.inBuild()) return null;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new ScreenColors(TonePalette.of(context), night != Configuration.UI_MODE_NIGHT_YES);
    }

    /** The framework theme rows and dialogs are built with, so what this class doesn't paint still reads. */
    int theme() {
        return light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar;
    }

    /** {@link #theme()} of {@link #forScreen}, or the dark Material theme the black page has always had. */
    static int themeFor(Context context) {
        ScreenColors colors = forScreen(context);
        return colors == null ? android.R.style.Theme_Material_NoActionBar : colors.theme();
    }

    /** A row's title and summary, its switch if it has one, and its chevron if a tap opens something. */
    void paintRow(View row, Preference preference) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(this.title));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            if (title.getParent() instanceof View) {
                View text = (View) title.getParent();
                text.setPadding(0, 0, 0, 0);
                text.setMinimumHeight(0);
            }
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(dimmedWhenDisabled(this.summary));
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            summary.setLineSpacing(dp(row, 2), 1);
        }
        View widget = row.findViewById(android.R.id.switch_widget);
        if (widget instanceof Switch) {
            int[][] states = {{-android.R.attr.state_enabled, android.R.attr.state_checked},
                    {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
            Switch toggle = (Switch) widget;
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setSize(dp(row, 20), dp(row, 20));
            thumb.setColor(Color.WHITE);
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(dp(row, 12));
            track.setSize(dp(row, 42), dp(row, 24));
            track.setColor(Color.WHITE);
            toggle.setThumbDrawable(thumb);
            toggle.setTrackDrawable(track);
            toggle.setSwitchMinWidth(dp(row, 44));
            toggle.setThumbTintList(new ColorStateList(states, new int[]{half(onAccent), half(offThumb()), onAccent, offThumb()}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{half(accent), half(switchOff), accent, switchOff}));
        }
        paintChevron(row, preference);
        ImageView icon = row.findViewById(android.R.id.icon);
        if (icon != null && preference.getIcon() != null) {
            ViewGroup.LayoutParams imageSize = icon.getLayoutParams();
            imageSize.width = dp(row, 24);
            imageSize.height = dp(row, 24);
            icon.setLayoutParams(imageSize);
            if (icon.getParent() instanceof ViewGroup) {
                ViewGroup frame = (ViewGroup) icon.getParent();
                frame.setPaddingRelative(0, 0, dp(row, 16), 0);
                ViewGroup.LayoutParams size = frame.getLayoutParams();
                size.width = dp(row, 40);
                frame.setLayoutParams(size);
            }
        }
        PreferenceGroup parent = preference.getParent();
        boolean grouped = parent instanceof PreferenceCategory;
        boolean first = !grouped || parent.getPreference(0) == preference;
        boolean last = !grouped || parent.getPreference(parent.getPreferenceCount() - 1) == preference;
        paintSurface(row, preference, first, last);
    }

    /** Group boundaries follow the visible rows, including a filtered search result. */
    void paintSurface(View row, Preference preference, boolean first, boolean last) {
        float radius = dp(row, 10);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(card);
        surface.setStroke(dp(row, 1), outline);
        surface.setCornerRadii(new float[]{
                first ? radius : 0, first ? radius : 0,
                first ? radius : 0, first ? radius : 0,
                last ? radius : 0, last ? radius : 0,
                last ? radius : 0, last ? radius : 0});
        Drawable inset = new InsetDrawable(surface, dp(row, 16), first ? dp(row, 6) : -dp(row, 1),
                dp(row, 16), last ? dp(row, 6) : 0);
        row.setBackground(preference.isSelectable()
                ? new RippleDrawable(ColorStateList.valueOf(half(accent)), inset, null) : inset);
        // Explicit padding after the inset background prevents its padding from moving the text.
        int vertical = preference.getIcon() == null ? 20 : 12;
        row.setPaddingRelative(dp(row, 34), dp(row, vertical + (first ? 6 : 0)),
                dp(row, 34), dp(row, vertical + (last ? 6 : 0)));
        row.setMinimumHeight(dp(row, 72));
    }

    /** A section title. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(heading);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setLetterSpacing(0.07f);
            title.setAllCaps(true);
        }
        row.setPaddingRelative(dp(row, 20), dp(row, 20), dp(row, 20), dp(row, 8));
    }

    /** Marks the chevron a row was given, so a recycled row's can be found and taken off. */
    static final String CHEVRON = "hushfacebook_chevron";

    /**
     * Whether a tap on [preference] opens something: a dialog, a file picker, the browser. A
     * switch says what a tap does with its switch, and a row that acts the moment it's tapped
     * goes without a chevron, as the shared ImmediateAction asks, so the chevron keeps one meaning.
     */
    static boolean opensSomething(Preference preference) {
        return preference.isSelectable() && !(preference instanceof TwoStatePreference)
                && !(preference instanceof ImmediateAction && ((ImmediateAction) preference).actsOnTap());
    }

    /**
     * The chevron at the end of every row a tap opens something from. Only its title's colour set
     * such a row apart before, which is how "Licenses" and "Version" looked alike (WCAG 1.4.1). It
     * sits in the row's widget frame, where a switch row keeps its switch. Rows are recycled, and
     * one Row class draws plain lines too, so a row that shouldn't have one has it taken off.
     */
    private void paintChevron(View row, Preference preference) {
        View found = row.findViewById(android.R.id.widget_frame);
        if (!(found instanceof ViewGroup)) return;
        ViewGroup frame = (ViewGroup) found;
        View chevron = frame.findViewWithTag(CHEVRON);
        if (!opensSomething(preference)) {
            if (chevron != null) {
                frame.removeView(chevron);
                // Preference hides an empty frame only when it builds the row, which a recycled row
                // skips, and an empty frame still takes its padding's width from the text.
                if (frame.getChildCount() == 0) frame.setVisibility(View.GONE);
            }
            return;
        }
        ImageView image;
        if (chevron instanceof ImageView) {
            image = (ImageView) chevron;
        } else {
            image = new ImageView(row.getContext());
            image.setTag(CHEVRON);
            // The row already tells a screen reader it's a button; the shape adds nothing to hear.
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(image, new LinearLayout.LayoutParams(dp(row, 16), dp(row, 24)));
        }
        Drawable drawn = image.getDrawable();
        if (!(drawn instanceof Chevron) || ((Chevron) drawn).color != summary) {
            image.setImageDrawable(new Chevron(summary, dp(row, 2)));
        }
        image.setEnabled(preference.isEnabled());
        frame.setVisibility(View.VISIBLE);
    }

    /**
     * A chevron drawn in code, since Facebook's APK has no resource for one. It points the way
     * the row reads, so it mirrors in a right-to-left layout, and it dims with its row.
     */
    static final class Chevron extends Drawable {
        /** The colour it's drawn in on a row that can be tapped now. */
        final int color;
        private final ColorStateList colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(int color, float stroke) {
            this.color = color;
            colors = dimmedWhenDisabled(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        /**
         * One arm's end, the tip and the other arm's end, as x and y pairs. The tip is at the end
         * the row reads towards: the right, or the left in a right-to-left layout. Material's own
         * chevron is 4.6 dp wide and 9.2 dp tall in a 24 dp icon, and this keeps that shape.
         */
        float[] points() {
            Rect bounds = getBounds();
            float half = bounds.height() * 0.19f;
            float reach = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -half / 2 : half / 2;
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            return new float[]{x - reach, y - half, x + reach, y, x - reach, y + half};
        }

        /** The colour it draws in now, which follows its row's state. */
        int currentColor() {
            return paint.getColor();
        }

        @Override
        public void draw(Canvas canvas) {
            float[] at = points();
            path.rewind();
            path.moveTo(at[0], at[1]);
            path.lineTo(at[2], at[3]);
            path.lineTo(at[4], at[5]);
            canvas.drawPath(path, paint);
        }

        @Override
        public boolean isAutoMirrored() {
            return true;
        }

        @Override
        public boolean onLayoutDirectionChanged(int layoutDirection) {
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int now = colors.getColorForState(state, color);
            if (now == paint.getColor()) return false;
            paint.setColor(now);
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Recovery is a choice, not another pair of settings rows. */
    private void paintRecoveryAction(View row, boolean primary) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(primary ? onAccent : secondaryActionText());
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(android.view.Gravity.CENTER);
            ViewGroup.LayoutParams size = title.getLayoutParams();
            size.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(size);
        }
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(primary ? accent : Color.TRANSPARENT);
        surface.setCornerRadius(dp(row, 8));
        if (primary) surface.setStroke(dp(row, 1), accent);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)),
                new InsetDrawable(surface, dp(row, 16), dp(row, 4), dp(row, 16), dp(row, 4)), null));
        row.setMinimumHeight(dp(row, 56));
        row.setPadding(dp(row, 32), dp(row, 8), dp(row, 32), dp(row, 8));
    }

    static void recoveryMessage(View row) {
        ScreenColors palette = forScreen(row.getContext());
        if (palette == null) palette = DEFAULT;
        HushfacebookPreferenceFragment.showAllText(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        // Centred across the row: text wrapped to its own width has nothing to centre in.
        if (title != null) {
            fillWidth(title);
            title.setGravity(android.view.Gravity.CENTER);
            title.setTextSize(22);
            title.setTextColor(palette.title);
            title.setTypeface(Typeface.create("sans-serif-medium", 0));
            Drawable icon = SettingsIcons.icon(row.getContext(), SettingsIcons.ABOUT, palette.heading);
            icon.setBounds(0, 0, dp(row, 56), dp(row, 56));
            title.setCompoundDrawablesRelative(null, icon, null, null);
            title.setCompoundDrawablePadding(dp(row, 24));
        }
        if (summary != null) {
            fillWidth(summary);
            summary.setGravity(android.view.Gravity.CENTER);
            summary.setTextColor(palette.summary);
            summary.setTextSize(16);
            summary.setPadding(0, dp(row, 12), 0, 0);
        }
        row.setPadding(dp(row, 32), dp(row, 100), dp(row, 32), dp(row, 56));
    }

    private static void fillWidth(View text) {
        ViewGroup.LayoutParams size = text.getLayoutParams();
        if (size == null) return;
        size.width = ViewGroup.LayoutParams.MATCH_PARENT;
        text.setLayoutParams(size);
    }

    /**
     * A dialog on show: its surface, title, message, buttons and text field. A single or multiple
     * choice list's rows take the screen's colours through {@link DialogChoices}; any other list's
     * rows keep the theme's.
     */
    void paint(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(this.dialog);
            panel.setCornerRadius(dp(window.getDecorView(), 10));
            panel.setStroke(dp(window.getDecorView(), 1), outline);
            window.setBackgroundDrawable(panel);
            int width = Math.min(dialog.getContext().getResources().getDisplayMetrics().widthPixels
                    - dp(window.getDecorView(), 32), dp(window.getDecorView(), 560));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        @SuppressWarnings("DiscouragedApi")
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) {
            title.setTextColor(this.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
        }
        // AlertDialog's own message, and the one a preference's dialog layout brings, which is the
        // one on show there: AlertDialog's own sits GONE above it, and findViewById finds that first.
        if (window != null) paintMessages(window.getDecorView());
        android.widget.ListView choices = dialog.getListView();
        if (choices != null && choices.getAdapter() != null && !(choices.getAdapter() instanceof DialogChoices)) {
            if (choices.getChoiceMode() == android.widget.ListView.CHOICE_MODE_SINGLE) {
                int selected = choices.getCheckedItemPosition();
                choices.setAdapter(new DialogChoices(choices.getAdapter(), this));
                if (selected >= 0) choices.setItemChecked(selected, true);
                choices.setDivider(null);
            } else if (choices.getChoiceMode() == android.widget.ListView.CHOICE_MODE_MULTIPLE) {
                // A new adapter clears the list's ticks, so they go back on after it.
                android.util.SparseBooleanArray ticked = choices.getCheckedItemPositions();
                ticked = ticked == null ? new android.util.SparseBooleanArray() : ticked.clone();
                choices.setAdapter(new DialogChoices(choices.getAdapter(), this));
                for (int i = 0; i < ticked.size(); i++) {
                    if (ticked.valueAt(i)) choices.setItemChecked(ticked.keyAt(i), true);
                }
                choices.setDivider(null);
            }
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            boolean primary = which == AlertDialog.BUTTON_POSITIVE;
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(primary ? accent : this.dialog);
            surface.setCornerRadius(dp(button, 8));
            if (primary) surface.setStroke(dp(button, 1), accent);
            RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)),
                    surface, null);
            // The framework button's own insets: the 48dp touch height keeps a 36dp fill, clear of
            // the dialog's edge like every Material button.
            background.setLayerInsetRelative(0, dp(button, 4), dp(button, 6), dp(button, 4), dp(button, 6));
            button.setBackground(background);
            button.setTextColor(primary ? onAccent : secondaryActionText());
            button.setAllCaps(false);
            button.setMinHeight(dp(button, 48));
            button.setPaddingRelative(dp(button, 16), dp(button, 6), dp(button, 16), dp(button, 6));
        }
        View field = dialog.findViewById(android.R.id.edit);
        if (field instanceof EditText) paintField((EditText) field);
    }

    /**
     * A dialog's text field. The framework theme draws its underline, cursor and selection in
     * Facebook's teal, whatever the wallpaper, so they take the accent the buttons have.
     */
    void paintField(EditText field) {
        field.setHintTextColor(summary);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(dialog);
        outline.setCornerRadius(dp(field, 8));
        outline.setStroke(dp(field, 2), accent);
        field.setBackgroundTintList(null);
        field.setBackground(outline);
        field.setPaddingRelative(dp(field, 12), dp(field, 10), dp(field, 12), dp(field, 10));
        field.setTextColor(title);
        field.setHighlightColor(half(accent));
        Drawable cursor = field.getTextCursorDrawable();
        if (cursor != null) {
            cursor = cursor.mutate();
            cursor.setTint(accent);
            field.setTextCursorDrawable(cursor);
        }
        Drawable handle = field.getTextSelectHandle();
        if (handle != null) {
            handle = handle.mutate();
            handle.setTint(accent);
            field.setTextSelectHandle(handle);
        }
        Drawable left = field.getTextSelectHandleLeft();
        if (left != null) {
            left = left.mutate();
            left.setTint(accent);
            field.setTextSelectHandleLeft(left);
        }
        Drawable right = field.getTextSelectHandleRight();
        if (right != null) {
            right = right.mutate();
            right.setTint(accent);
            field.setTextSelectHandleRight(right);
        }
    }

    private void paintMessages(View view) {
        if (view instanceof TextView && view.getId() == android.R.id.message) ((TextView) view).setTextColor(summary);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) paintMessages(group.getChildAt(index));
        }
    }

    /** Paints a row with the colours on show, if there are any. */
    static void row(View row, Preference preference) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paintRow(row, preference);
    }

    /** Paints a section title with the colours on show, if there are any. */
    static void heading(View row) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paintHeading(row);
    }

    /** Paints both recovery actions even if the ordinary settings page failed to initialize. */
    static void recoveryAction(View row, boolean primary) {
        ScreenColors colors = forScreen(row.getContext());
        (colors == null ? DEFAULT : colors).paintRecoveryAction(row, primary);
    }

    /** Paints a dialog with the colours on show, if there are any. */
    static void dialog(@Nullable AlertDialog dialog) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paint(dialog);
    }

    private static int dp(View view, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics()));
    }

    /** A switch's track: the thumb's colour at half strength, as Material's own switch draws it. */
    static int half(int color) {
        return (color & 0x00FFFFFF) | 0x80000000;
    }

    /**
     * The press ripple of a button drawn straight on the page, with no surface of its own: Material's
     * borderless button, in [color] at half strength and kept inside the button's bounds, a disc for
     * an icon button when [round]. A plain transparent background showed nothing when tapped.
     */
    static void pressFeedback(View button, int color, boolean round) {
        Drawable mask;
        if (round) {
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(Color.WHITE);
            mask = disc;
        } else {
            mask = new ColorDrawable(Color.WHITE);
        }
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(half(color)), null, mask));
    }
}
