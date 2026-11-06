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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
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

import app.morphe.extension.facebook.theme.AmoledTheme;
import app.morphe.extension.facebook.theme.ForceDarkMode;
import app.morphe.extension.facebook.theme.MaterialYouTheme;
import app.morphe.extension.facebook.theme.TonePalette;
import app.morphe.extension.shared.settings.preference.ImmediateAction;

/**
 * The colours of the Hushfacebook screen, its rows and its dialogs when the Material You theme is
 * in this build. The screen then follows the phone's dark or light setting, and stays dark while
 * Force dark mode keeps Facebook dark, in the tones of the wallpaper palette (Android 12 and newer)
 * or of the fixed palette Android 11 gets.
 *
 * <p>A tone is a lightness, so the contrast of each pair below is the same for any wallpaper. The
 * pairs are Material 3's: the page is the neutral at tone 10 in dark and 99 in light, text is the
 * neutral at 90 or 10, secondary text the neutral variant at 80 or 30, and section titles, switches
 * and dialog buttons the accent at 80 or 40. Every pair meets WCAG 2.2 AA with room to spare,
 * which ScreenColorsTest works out.
 *
 * <p>Without the theme in the build, {@link #forScreen} answers null and the screen keeps the black
 * page and dark Material rows it always had, in the AMOLED theme's Background colour when that
 * isn't black ({@link #black}).
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
    /** The placeholder of an empty field, quieter than a summary and still 4.5:1 on every surface. */
    final int hint;
    /** Section titles. */
    final int heading;
    /** A switch that's on, and the buttons of a dialog. */
    final int accent;
    /** A switch that's off. */
    final int switchOff;
    /** Grouped preference rows and their edge. */
    final int card;
    final int outline;
    /** A tonal step above the card, behind an icon that has a surface of its own. */
    final int iconSurface;
    /** Text on the filled primary dialog action. */
    final int onAccent;
    /** A field's error line, calm on the dialog. */
    final int error;

    /** The colours the screen on show was built with, or null for the black page. */
    @Nullable
    static volatile ScreenColors shown;
    static final ScreenColors DEFAULT = new ScreenColors();

    /**
     * Astryx's corner radii in dp (issue #302): a small mark inside a control, a button or field,
     * and a dialog or a card that holds others.
     */
    static final int INNER_RADIUS = 4;
    static final int ELEMENT_RADIUS = 8;
    static final int CONTAINER_RADIUS = 12;

    /** WCAG 2.2 AA for text, and for the parts of a control that show its state. */
    private static final double TEXT_CONTRAST = 4.5;
    private static final double CONTROL_CONTRAST = 3.0;

    /** {@link #black} for the last Background colour it was asked about. */
    @Nullable
    private static volatile ScreenColors tinted;

    /**
     * The black page, in the dark tokens of Astryx, Meta's own design system (issue #302): its card,
     * popover, primary and secondary text, text accent and accent, and its 10% border laid over the
     * card. The page stays black for AMOLED. Astryx puts white on its accent, which reads at 3.1:1,
     * so the text and thumbs on it are black, as Material's dark scheme draws them. Its switch track
     * is under 3:1 on the card, so a switch that's off takes its disabled text tone, the nearest
     * grey of its scale that reads at 3:1 under the black thumb and on the card.
     */
    private ScreenColors() {
        // WCAG 2.2 ratios on the black page, its card and the dialog, measured 2026-11-03.
        light = false;
        background = Color.BLACK;
        dialog = 0xFF28292C;
        // 16.15 on the page, 12.64 on the card, 11.18 on the dialog.
        title = 0xFFDFE2E5;
        // 9.51, 7.44, 6.59.
        summary = 0xFFAAAFB5;
        // 6.71, 5.25, 4.65: the darkest step of the summary grey that keeps 4.5 on the dialog.
        hint = 0xFF8E9297;
        // 7.49 on the page, 5.86 on the card, 5.19 on the dialog.
        heading = 0xFF3E9EFB;
        // 5.28 on the card; black reads on it at 6.75.
        accent = 0xFF2694FE;
        // 3.50 on the card, 3.09 on the dialog; the black thumb reads on it at 4.47.
        switchOff = 0xFF6F747C;
        card = 0xFF1F1F22;
        // Astryx's 10% border over the card, 1.32 on it: an edge, not a control's boundary. A
        // focused row's outline is the heading, 5.86.
        outline = 0xFF343437;
        // The heading reads on it at 5.17.
        iconSurface = 0xFF29292D;
        onAccent = Color.BLACK;
        // 9.68 on the card, 8.57 on the dialog.
        error = 0xFFFFB4AB;
    }

    private ScreenColors(TonePalette palette, boolean light) {
        this.light = light;
        if (light) {
            background = palette.tone(TonePalette.NEUTRAL, 99);
            dialog = palette.tone(TonePalette.NEUTRAL, 95);
            title = palette.tone(TonePalette.NEUTRAL, 10);
            summary = palette.tone(TonePalette.NEUTRAL_VARIANT, 30);
            // Tone 40 on tone 95 is 5.7:1 for any wallpaper.
            hint = palette.tone(TonePalette.NEUTRAL_VARIANT, 40);
            heading = palette.tone(TonePalette.ACCENT, 40);
            accent = palette.tone(TonePalette.ACCENT, 40);
            switchOff = palette.tone(TonePalette.NEUTRAL_VARIANT, 50);
            card = palette.tone(TonePalette.NEUTRAL, 95);
            outline = palette.tone(TonePalette.NEUTRAL_VARIANT, 80);
            // The accent at tone 40 reads on tone 90 at about 5:1.
            iconSurface = palette.tone(TonePalette.NEUTRAL, 90);
            onAccent = Color.WHITE;
            // Material 3's error at tone 40, 5.7 on the dialog.
            error = 0xFFBA1A1A;
        } else {
            background = palette.tone(TonePalette.NEUTRAL, 10);
            dialog = palette.tone(TonePalette.NEUTRAL, 20);
            title = palette.tone(TonePalette.NEUTRAL, 90);
            summary = palette.tone(TonePalette.NEUTRAL_VARIANT, 80);
            // Tone 70 on tone 20 is 5.7:1 for any wallpaper.
            hint = palette.tone(TonePalette.NEUTRAL_VARIANT, 70);
            heading = palette.tone(TonePalette.ACCENT, 80);
            accent = palette.tone(TonePalette.ACCENT, 80);
            switchOff = palette.tone(TonePalette.NEUTRAL_VARIANT, 60);
            card = palette.tone(TonePalette.NEUTRAL, 20);
            outline = palette.tone(TonePalette.NEUTRAL_VARIANT, 30);
            // The accent at tone 80 reads on tone 30 at about 5.5:1.
            iconSurface = palette.tone(TonePalette.NEUTRAL, 30);
            onAccent = background;
            // Material 3's error at tone 80, 7.7 on the dialog.
            error = 0xFFFFB4AB;
        }
    }

    /**
     * The black page over another Background colour: the page in it, cards and dialogs on the card
     * step AMOLED raises Facebook's own cards by, and their edge on the input fill's step, so the
     * screen matches the app around it. The black page's text and switches move toward white
     * wherever the lighter card leaves them under AA, and the primary action's text turns black
     * when the lifted accent leaves white under it.
     */
    private ScreenColors(int[] surfaces) {
        light = false;
        background = surfaces[0];
        card = surfaces[1];
        dialog = card;
        outline = surfaces[surfaces.length - 1];
        // The popover step, between the card and its edge.
        iconSurface = surfaces[2];
        title = AmoledTheme.readable(DEFAULT.title, card, TEXT_CONTRAST);
        summary = AmoledTheme.readable(DEFAULT.summary, card, TEXT_CONTRAST);
        hint = AmoledTheme.readable(DEFAULT.hint, card, TEXT_CONTRAST);
        heading = AmoledTheme.readable(DEFAULT.heading, card, TEXT_CONTRAST);
        accent = AmoledTheme.readable(DEFAULT.accent, card, CONTROL_CONTRAST);
        switchOff = AmoledTheme.readable(DEFAULT.switchOff, card, CONTROL_CONTRAST);
        onAccent = TonePalette.luminance(accent) <= (1.05 / TEXT_CONTRAST) - 0.05 ? Color.WHITE : Color.BLACK;
        error = AmoledTheme.readable(DEFAULT.error, card, TEXT_CONTRAST);
    }

    /**
     * The black page's colours: {@link #DEFAULT}, unless the AMOLED theme is in this build with a
     * Background colour other than black. Then they're built from that colour (issue #214), which
     * left the page black with dark blue cards under an app in #0D1117. A Material You build hides
     * the Background row, so a colour saved before it doesn't count.
     */
    static ScreenColors black() {
        if (!PatchFamily.AMOLED_THEME.inBuild() || PatchFamily.MATERIAL_YOU_THEME.inBuild()) return DEFAULT;
        int[] surfaces = AmoledTheme.surfaces();
        if (surfaces[0] == Color.BLACK) return DEFAULT;
        ScreenColors colors = tinted;
        if (colors == null || colors.background != surfaces[0] || colors.card != surfaces[1]
                || colors.outline != surfaces[surfaces.length - 1]) {
            colors = new ScreenColors(surfaces);
            tinted = colors;
        }
        return colors;
    }

    /** The colours on show, or {@link #black} when the black page is. */
    static ScreenColors current() {
        ScreenColors colors = shown;
        return colors == null ? black() : colors;
    }

    /**
     * The thumb of a switch that's off, read against its track. On a dark wallpaper palette the
     * title's light tone sat at 2.46:1 on the neutral track, under the 3:1 a control's state
     * needs; the dark tone that fills the on thumb reads above 5:1 there. The black page's black
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
     * The text of a dialog's outlined actions, such as Cancel: the heading colour, the text accent
     * that's held to 4.5:1 on the dialog. The black page's accent once read at 3.4:1 there. The
     * Material You palettes draw both in one tone, so only the black page changes.
     */
    int secondaryActionText() {
        return heading;
    }

    /**
     * The colours for a screen shown in this context: the phone's palette, dark or light as the
     * context's configuration says, and dark while Force dark mode keeps Facebook dark. Null when
     * the Material You theme isn't in this build.
     */
    @Nullable
    static ScreenColors forScreen(Context context) {
        if (!PatchFamily.MATERIAL_YOU_THEME.inBuild()) return null;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean light = night != Configuration.UI_MODE_NIGHT_YES && !ForceDarkMode.forcing();
        return new ScreenColors(MaterialYouTheme.phonePalette(context), light);
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
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (title.getParent() instanceof View) {
                View text = (View) title.getParent();
                text.setPadding(0, 0, 0, 0);
                text.setMinimumHeight(0);
            }
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(dimmedWhenDisabled(this.summary));
            summary.setLineSpacing(0, 1);
        }
        View widget = row.findViewById(android.R.id.switch_widget);
        if (widget instanceof Switch) {
            int[][] states = {{-android.R.attr.state_enabled, android.R.attr.state_checked},
                    {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
            Switch toggle = (Switch) widget;
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.RECTANGLE);
            thumb.setCornerRadius(dp(row, 4));
            thumb.setSize(dp(row, 20), dp(row, 20));
            thumb.setColor(Color.WHITE);
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(dp(row, 6));
            track.setSize(dp(row, 40), dp(row, 24));
            track.setColor(Color.WHITE);
            toggle.setThumbDrawable(thumb);
            toggle.setTrackDrawable(track);
            toggle.setSwitchMinWidth(dp(row, 40));
            toggle.setSplitTrack(false);
            toggle.setThumbTintList(new ColorStateList(states, new int[]{half(onAccent), half(offThumb()), onAccent, offThumb()}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{half(accent), half(switchOff), accent, switchOff}));
        }
        paintChevron(row, preference);
        ImageView icon = row.findViewById(android.R.id.icon);
        // Decoration: the row's title already says what it is, so a screen reader skips the icon.
        if (icon != null) icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icon != null && preference.getIcon() != null) {
            ViewGroup.LayoutParams imageSize = icon.getLayoutParams();
            imageSize.width = dp(row, 24);
            imageSize.height = dp(row, 24);
            icon.setLayoutParams(imageSize);
            icon.setPadding(0, 0, 0, 0);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setBackground(null);
            if (icon.getParent() instanceof ViewGroup) {
                ViewGroup frame = (ViewGroup) icon.getParent();
                frame.setPaddingRelative(0, 0, dp(row, 14), 0);
                frame.setMinimumWidth(0);
                ViewGroup.LayoutParams size = frame.getLayoutParams();
                size.width = dp(row, 38);
                frame.setLayoutParams(size);
            }
        }
        PreferenceGroup parent = preference.getParent();
        boolean grouped = parent instanceof PreferenceCategory;
        boolean first = !grouped || parent.getPreference(0) == preference;
        boolean last = !grouped || parent.getPreference(parent.getPreferenceCount() - 1) == preference;
        paintSurface(row, preference, first, last);
    }

    /** {@link #paintSurface(View, Preference, boolean, boolean, boolean)} for a row a section doesn't hold, on the page itself. */
    void paintSurface(View row, Preference preference, boolean first, boolean last) {
        paintSurface(row, preference, first, last, !(preference.getParent() instanceof PreferenceCategory));
    }

    /**
     * Group boundaries follow the visible rows, including a filtered search result. A row on the
     * page itself ([overview]) runs edge to edge with square corners and its side and top strokes
     * pushed a dp off its bounds, so the page has no hairline down either edge and one between
     * rows. A section's rows share one card, inset 18 dp, rounded at its top and bottom, with
     * 4 dp more room above its first row and below its last. The card's height is the row's: its
     * text wraps at any font size and the row grows to fit.
     */
    void paintSurface(View row, Preference preference, boolean first, boolean last, boolean overview) {
        float radius = overview ? 0 : dp(row, 10);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(card);
        surface.setStroke(dp(row, 1), preference.isSelectable() ? interactionOutline(outline)
                : ColorStateList.valueOf(outline));
        surface.setCornerRadii(new float[]{
                first ? radius : 0, first ? radius : 0,
                first ? radius : 0, first ? radius : 0,
                last ? radius : 0, last ? radius : 0,
                last ? radius : 0, last ? radius : 0});
        int side = overview ? -dp(row, 1) : dp(row, 18);
        Drawable inset = new InsetDrawable(surface, side, overview ? -dp(row, 1) : first ? dp(row, 4) : -dp(row, 1),
                side, !overview && last ? dp(row, 4) : 0);
        row.setBackground(preference.isSelectable()
                ? new RippleDrawable(interactionRipple(heading), inset, null).mutate() : inset);
        // Explicit padding after the inset background prevents its padding from moving the text.
        // Astryx spaces in 4 dp steps, so 8 above and below; a section's row puts its 2 dp more,
        // and the card's 4 dp of room, on its parts, which stay centred on the card.
        int horizontal = overview ? 20 : 30;
        row.setPaddingRelative(dp(row, horizontal), dp(row, 8), dp(row, horizontal), dp(row, 8));
        int above = overview ? 0 : 2 + (first ? 4 : 0);
        int below = overview ? 0 : 2 + (last ? 4 : 0);
        if (row instanceof LinearLayout && ((LinearLayout) row).getOrientation() == LinearLayout.HORIZONTAL) {
            ViewGroup parts = (ViewGroup) row;
            for (int index = 0; index < parts.getChildCount(); index++) {
                ViewGroup.LayoutParams place = parts.getChildAt(index).getLayoutParams();
                if (!(place instanceof ViewGroup.MarginLayoutParams)) continue;
                ((ViewGroup.MarginLayoutParams) place).topMargin = dp(row, above);
                ((ViewGroup.MarginLayoutParams) place).bottomMargin = dp(row, below);
                parts.getChildAt(index).setLayoutParams(place);
            }
        }
        row.setMinimumHeight(dp(row, overview ? 52 : 64));
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) title.setTextSize(TypedValue.COMPLEX_UNIT_SP, overview ? 15 : 16);
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, overview ? 12 : 13);
    }

    /** A section title, in the heading colour, over the cards it names. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(heading);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setLetterSpacing(0.055f);
            title.setAllCaps(true);
        }
        row.setPaddingRelative(dp(row, 22), dp(row, 20), dp(row, 22), dp(row, 8));
    }

    /**
     * The restart row, drawn as the button it is: a card on the 18 dp line with 8 dp of page above
     * and below, rounded 12 dp and outlined in the heading colour, at least 56 dp tall. It stays a
     * row of the list, at its top, so it shows on whatever page is open the moment a change waits.
     */
    void paintRestartBar(View row) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(card);
        surface.setCornerRadius(dp(row, 12));
        surface.setStroke(dp(row, 1), interactionOutline(heading, title));
        Drawable inset = new InsetDrawable(surface, dp(row, 18), dp(row, 8), dp(row, 18), dp(row, 8));
        row.setBackground(new RippleDrawable(interactionRipple(heading), inset, null).mutate());
        row.setPaddingRelative(dp(row, 18 + 16), dp(row, 8 + 6), dp(row, 18 + 20), dp(row, 8 + 6));
        row.setMinimumHeight(dp(row, 8 + 56 + 8));
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) title.setTextColor(dimmedWhenDisabled(this.title));
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
        surface.setCornerRadius(dp(row, ELEMENT_RADIUS));
        if (primary) surface.setStroke(dp(row, 1), accent);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)),
                new InsetDrawable(surface, dp(row, 20), dp(row, 4), dp(row, 20), dp(row, 4)), null));
        row.setMinimumHeight(dp(row, 56));
        row.setPadding(dp(row, 36), dp(row, 8), dp(row, 36), dp(row, 8));
    }

    static void recoveryMessage(View row) {
        ScreenColors palette = forScreen(row.getContext());
        if (palette == null) palette = black();
        HushfacebookPreferenceFragment.showAllText(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        // Centred across the row: text wrapped to its own width has nothing to centre in.
        if (title != null) {
            fillWidth(title);
            title.setGravity(android.view.Gravity.CENTER);
            title.setTextSize(22);
            ShimmerTitle.wear(title, palette.background);
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
        int height = row.getResources().getConfiguration().screenHeightDp;
        boolean largeText = row.getResources().getConfiguration().fontScale >= 1.5f;
        int top = largeText ? 32 : Math.max(24, Math.min(100, height / 8));
        int bottom = largeText ? 24 : Math.max(16, Math.min(56, height / 16));
        row.setPadding(dp(row, 32), dp(row, top), dp(row, 32), dp(row, bottom));
    }

    private static void fillWidth(View text) {
        ViewGroup.LayoutParams size = text.getLayoutParams();
        if (size == null) return;
        size.width = ViewGroup.LayoutParams.MATCH_PARENT;
        text.setLayoutParams(size);
    }

    /**
     * A dialog on show: its surface, title, message, list rows, buttons and text field. Every list's
     * rows take the screen's colours through {@link DialogChoices}, with their picks kept.
     */
    void paint(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(this.dialog);
            panel.setCornerRadius(dp(window.getDecorView(), CONTAINER_RADIUS));
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
            ShimmerTitle.wear(title, this.dialog);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
        }
        // AlertDialog's own message, and the one a preference's dialog layout brings, which is the
        // one on show there: AlertDialog's own sits GONE above it, and findViewById finds that first.
        if (window != null) paintMessages(window.getDecorView());
        android.widget.ListView choices = dialog.getListView();
        if (choices != null && choices.getAdapter() != null && !(choices.getAdapter() instanceof DialogChoices)
                && !(choices.getAdapter() instanceof ChoiceCards)) {
            DialogChoices.swap(choices, new DialogChoices(choices.getAdapter(), this));
            choices.setDivider(null);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            paintAction(button, which == AlertDialog.BUTTON_POSITIVE);
            // The framework button's own insets: the 48dp touch height keeps a 36dp fill, clear of
            // the dialog's edge like every Material button.
            ((RippleDrawable) button.getBackground()).setLayerInsetRelative(
                    0, dp(button, 4), dp(button, 6), dp(button, 4), dp(button, 6));
        }
        View field = dialog.findViewById(android.R.id.edit);
        if (field instanceof EditText) paintField((EditText) field);
    }

    /**
     * A dialog's button as a card: the primary one filled in the accent, the others on the card
     * colour, each outlined, at least 48 dp tall and 64 wide, wrapping a long word rather than
     * cutting it off.
     */
    private void paintAction(TextView action, boolean primary) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(dimmedWhenDisabled(primary ? accent : card));
        surface.setCornerRadius(dp(action, ELEMENT_RADIUS));
        surface.setStroke(dp(action, 2), interactionOutline(primary ? accent : outline,
                primary ? onAccent : heading));
        action.setBackgroundTintList(null);
        // View's tint pass mutates the background after setting its state. Mutate first so
        // that pass can't replace enabled children with fresh, empty-state copies.
        action.setBackground(new RippleDrawable(
                interactionRipple(primary ? onAccent : heading), surface, null).mutate());
        action.setTextColor(dimmedWhenDisabled(primary ? onAccent : secondaryActionText()));
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        action.setAllCaps(false);
        action.setSingleLine(false);
        action.setMaxLines(Integer.MAX_VALUE);
        action.setEllipsize(null);
        action.setHorizontallyScrolling(false);
        action.setGravity(android.view.Gravity.CENTER);
        action.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        action.setMinHeight(dp(action, 48));
        action.setMinimumHeight(dp(action, 48));
        action.setMinWidth(dp(action, 64));
        action.setMinimumWidth(dp(action, 64));
        action.setPaddingRelative(dp(action, 16), dp(action, 12), dp(action, 16), dp(action, 12));
    }

    /** A list row's fill: [normal] at rest, the accent at 13% once it's picked. */
    ColorStateList choiceFill(int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{(accent & 0x00FFFFFF) | 0x22000000, normal});
    }

    /**
     * A dialog's text field. The framework theme draws its underline, cursor and selection in
     * Facebook's teal, whatever the wallpaper, so they take the accent the buttons have.
     */
    void paintField(EditText field) {
        field.setHintTextColor(summary);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(dialog);
        outline.setCornerRadius(dp(field, CONTAINER_RADIUS));
        outline.setStroke(dp(field, 2), interactionOutline(switchOff));
        field.setBackgroundTintList(null);
        field.setBackground(outline);
        field.setPaddingRelative(dp(field, 12), dp(field, 10), dp(field, 12), dp(field, 10));
        field.setTextColor(dimmedWhenDisabled(title));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setMinHeight(dp(field, 48));
        field.setHighlightColor(half(accent));
        // Android 9 can't swap the cursor or handles, so they keep the framework's colour there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
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
        if (view instanceof TextView && view.getId() == android.R.id.message) {
            TextView message = (TextView) view;
            message.setTextColor(dimmedWhenDisabled(summary));
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            message.setLineSpacing(dp(view, 2), 1);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) paintMessages(group.getChildAt(index));
        }
    }

    /** Paints a row with the colours on show, if there are any. */
    static void row(View row, Preference preference) {
        current().paintRow(row, preference);
    }

    /** Paints a section title with the colours on show, if there are any. */
    static void heading(View row) {
        current().paintHeading(row);
    }

    /** Paints both recovery actions even if the ordinary settings page failed to initialize. */
    static void recoveryAction(View row, boolean primary) {
        ScreenColors colors = forScreen(row.getContext());
        (colors == null ? black() : colors).paintRecoveryAction(row, primary);
    }

    /** Paints a dialog with the colours on show, if there are any. */
    static void dialog(@Nullable AlertDialog dialog) {
        current().paint(dialog);
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
     * borderless button, in [color] at half strength and kept inside the button's bounds, a small rounded rectangle for
     * an icon button when [round]. A plain transparent background showed nothing when tapped.
     */
    static void pressFeedback(View button, int color, boolean round) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(button, round ? ELEMENT_RADIUS : INNER_RADIUS));
        mask.setColor(Color.WHITE);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(dp(button, round ? ELEMENT_RADIUS : INNER_RADIUS));
        focus.setColor(Color.TRANSPARENT);
        focus.setStroke(dp(button, 2), focusStroke(color, Color.TRANSPARENT));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(half(color)), focus, mask));
    }

    /** A focused control has an explicit boundary even when no press ripple is running. */
    static ColorStateList focusStroke(int focused, int resting) {
        return new ColorStateList(new int[][]{{android.R.attr.state_enabled, android.R.attr.state_focused},
                {android.R.attr.state_enabled, android.R.attr.state_selected}, {}},
                new int[]{focused, focused, resting});
    }

    /**
     * A row's or a field's edge: [normal] at rest, at 38% when it's disabled, and in the heading
     * colour while a keyboard, a mouse or a list's own selection is on it (focused, hovered,
     * selected, activated or checked), so each of those stays visible.
     */
    ColorStateList interactionOutline(int normal) {
        return interactionOutline(normal, heading);
    }

    private static ColorStateList interactionOutline(int normal, int focused) {
        return new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                {android.R.attr.state_hovered}, {android.R.attr.state_selected},
                {android.R.attr.state_activated}, {android.R.attr.state_checked}, {}},
                new int[]{(normal & 0x00FFFFFF) | (Math.round(Color.alpha(normal) * 0.38f) << 24),
                        focused, focused, focused, focused, focused, normal});
    }

    /** A press ripple in [color] at 20%, and none on a disabled control. */
    static ColorStateList interactionRipple(int color) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.TRANSPARENT, (color & 0x00FFFFFF) | 0x33000000});
    }

    /** A quiet outlined action with the same touch target as the dialog buttons. */
    void outlineAction(Button button) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(card);
        surface.setCornerRadius(dp(button, ELEMENT_RADIUS));
        surface.setStroke(dp(button, 2), focusStroke(title, heading));
        RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(half(heading)), surface, null);
        background.setLayerInsetRelative(0, 0, dp(button, 6), 0, dp(button, 6));
        button.setBackground(background);
        button.setTextColor(dimmedWhenDisabled(heading));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPaddingRelative(dp(button, 14), 0, dp(button, 14), 0);
    }
}
