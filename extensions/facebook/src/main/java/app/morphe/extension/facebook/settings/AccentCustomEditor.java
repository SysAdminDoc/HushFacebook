/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;

/**
 * The Accent color row's Custom choice (#355): red, green and blue sliders from 0 to 255 with a
 * swatch that shows the color as it moves, and the color code beside them. The sliders and the code
 * follow each other, so a typed code moves the sliders and a slider rewrites the code. Save keeps
 * the code in {@link Settings#ACCENT_CUSTOM}, which {@link AccentColor} reads.
 */
@SuppressWarnings("deprecation")
final class AccentCustomEditor {
    private AccentCustomEditor() { }

    /** Facebook's own blue, where the sliders start when no color is saved. */
    static final int START = 0x0866FF;

    /** The editor's content: swatch, code field and three sliders, kept in step. */
    static final class Picker {
        final LinearLayout view;
        final View swatch;
        final EditText code;
        final SeekBar[] bars = new SeekBar[3];
        private final TextView[] numbers = new TextView[3];
        private final GradientDrawable swatchFill = new GradientDrawable();
        private boolean syncing;

        Picker(Context context, ScreenColors colors, int rgb) {
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setTag(this);
            int side = dp(context, 24);
            view.setPaddingRelative(side, dp(context, 8), side, dp(context, 8));

            swatchFill.setCornerRadius(dp(context, 8));
            swatchFill.setStroke(dp(context, 1), colors.summary);
            swatch = new View(context);
            swatch.setBackground(swatchFill);
            swatch.setContentDescription(L10n.t("Preview"));
            view.addView(swatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 56)));

            code = new EditText(context);
            code.setSingleLine(true);
            code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            HushfacebookPreferenceFragment.keepEditorInDialog(code, false);
            code.setHint("#2ECC71");
            code.setTextColor(colors.title);
            code.setHintTextColor(colors.summary);
            code.setBackgroundTintList(ColorStateList.valueOf(colors.accent));
            LinearLayout.LayoutParams codePlace = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            codePlace.topMargin = dp(context, 12);
            view.addView(code, codePlace);

            String[] names = {L10n.t("Red"), L10n.t("Green"), L10n.t("Blue")};
            for (int channel = 0; channel < 3; channel++) {
                LinearLayout line = new LinearLayout(context);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = label(context, colors, names[channel], 64);
                SeekBar bar = new SeekBar(context);
                bar.setMax(255);
                bar.setContentDescription(names[channel]);
                bar.setProgressTintList(ColorStateList.valueOf(colors.accent));
                bar.setThumbTintList(ColorStateList.valueOf(colors.accent));
                TextView number = label(context, colors, "0", 36);
                number.setGravity(Gravity.END);
                bars[channel] = bar;
                numbers[channel] = number;
                line.addView(name);
                line.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                line.addView(number);
                LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48));
                view.addView(line, place);
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (syncing) return;
                        showColor(rgb(), true);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) { }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) { }
                });
            }
            code.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) { }

                @Override
                public void afterTextChanged(Editable s) {
                    if (syncing) return;
                    String stored = AccentColor.customStored(s.toString());
                    // A half-typed code leaves the sliders where they are.
                    if (stored != null) showColor(Integer.parseInt(stored.substring(1), 16), false);
                }
            });
            showColor(rgb & 0xFFFFFF, true);
        }

        /** The sliders' color, {@code 0xRRGGBB}. */
        int rgb() {
            return (bars[0].getProgress() << 16) | (bars[1].getProgress() << 8) | bars[2].getProgress();
        }

        /** The code as {@link Settings#ACCENT_CUSTOM} would keep it, or null when the field isn't a color yet. */
        @Nullable
        String stored() {
            return AccentColor.customStored(code.getText().toString());
        }

        /** Moves the sliders, swatch and numbers to [rgb], and rewrites the code field when [writeCode]. */
        private void showColor(int rgb, boolean writeCode) {
            syncing = true;
            try {
                int[] channels = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
                for (int i = 0; i < 3; i++) {
                    if (bars[i].getProgress() != channels[i]) bars[i].setProgress(channels[i]);
                    numbers[i].setText(String.valueOf(channels[i]));
                }
                swatchFill.setColor(0xFF000000 | rgb);
                if (writeCode) {
                    String text = String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
                    if (!text.equals(code.getText().toString())) {
                        code.setText(text);
                        code.setSelection(text.length());
                    }
                }
            } finally {
                syncing = false;
            }
        }
    }

    private static TextView label(Context context, ScreenColors colors, String text, int widthDp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(colors.title);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setMinWidth(dp(context, widthDp));
        return view;
    }

    /** The color the editor opens on: the saved Custom color, else Facebook's blue. */
    static int startColor() {
        Integer saved = AccentColor.customColour();
        return saved == null ? START : saved & 0xFFFFFF;
    }

    /**
     * Opens the editor through [page], so it closes with the page and takes the screen's colors. Save
     * keeps the color, makes Custom the accent and says a restart is owed.
     */
    @Nullable
    static AlertDialog show(HushfacebookPreferenceFragment page, Context context) {
        Picker picker = new Picker(context, ScreenColors.current(), startColor());
        AlertDialog dialog = page.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Custom color"))
                .setView(picker.view)
                .setPositiveButton(L10n.t("Save"), null)
                .setNegativeButton(L10n.t("Cancel"), null));
        if (dialog == null) return null;
        HushfacebookPreferenceFragment.fitAboveKeyboard(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String stored = picker.stored();
            if (stored == null) {
                Utils.showToastLong(L10n.t("Type a six-digit color code, like #2ECC71."));
                return;
            }
            if (save(page, stored)) dialog.dismiss();
        });
        return dialog;
    }

    /** Saves [stored] as the Custom color with Custom as the accent. Says a restart is owed when something changed. */
    static boolean save(HushfacebookPreferenceFragment page, String stored) {
        Map<Setting<?>, Object> before = new LinkedHashMap<>();
        before.put(Settings.ACCENT_CUSTOM, Settings.ACCENT_CUSTOM.savedValue());
        before.put(Settings.ACCENT_COLOR, Settings.ACCENT_COLOR.savedValue());
        Map<Setting<?>, Object> values = new LinkedHashMap<>();
        values.put(Settings.ACCENT_CUSTOM, stored);
        values.put(Settings.ACCENT_COLOR, AccentColor.Preset.CUSTOM);
        try {
            Setting.saveAll(values);
        } catch (java.io.IOException | RuntimeException failure) {
            Logger.printInfo(() -> "Custom accent color not saved", failure);
            Utils.showToastLong(L10n.t("Couldn't save the change. Try again."));
            return false;
        }
        Logger.printDebug(() -> "Accent color: saved custom " + stored);
        page.savedTogether(before);
        return true;
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
