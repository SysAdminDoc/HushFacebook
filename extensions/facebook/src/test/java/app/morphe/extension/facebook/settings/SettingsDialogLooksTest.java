/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.theme.TonePalette;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * A dialog on the settings screen, as HushGram draws one: an outlined panel 16 dp in from each
 * side, a medium title, a quieter message with room between its lines, buttons drawn as cards,
 * picked choices tinted in the accent and fields outlined in the switch's off colour.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsDialogLooksTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private final List<AlertDialog> opened = new ArrayList<>();

    @Before public void open() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
    }

    @After public void close() {
        for (AlertDialog dialog : opened) dialog.dismiss();
        controller.close();
    }

    private static ScreenColors[] looks() {
        return new ScreenColors[]{ScreenColors.DEFAULT,
                ScreenColors.of(TonePalette.fallback(), false), ScreenColors.of(TonePalette.fallback(), true)};
    }

    @Test public void thePanelStands16DpInFromEachSideAndNeverPast560() {
        for (ScreenColors colors : looks()) {
            AlertDialog dialog = show(new AlertDialog.Builder(controller.get())
                    .setTitle("Import settings").setMessage("Replace what's set now with the file's settings?")
                    .setPositiveButton("Import", null).setNegativeButton("Cancel", null));
            colors.paint(dialog);
            WindowManager.LayoutParams window = dialog.getWindow().getAttributes();
            int screen = controller.get().getResources().getDisplayMetrics().widthPixels;
            assertEquals(Math.min(screen - dp(32), dp(560)), window.width);
            GradientDrawable panel = (GradientDrawable) dialog.getWindow().getDecorView().getBackground();
            assertEquals(colors.dialog, panel.getColor().getDefaultColor());
            assertEquals(dp(12), panel.getCornerRadius(), 0.01);
        }
    }

    @Test public void theTitleIsMediumAndTheMessageQuieterWithRoomBetweenItsLines() {
        ScreenColors colors = ScreenColors.DEFAULT;
        AlertDialog dialog = show(new AlertDialog.Builder(controller.get())
                .setTitle("Import settings").setMessage("Replace what's set now with the file's settings?")
                .setPositiveButton("Import", null));
        colors.paint(dialog);
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = dialog.findViewById(titleId);
        assertTrue("the title isn't in the gradient", ShimmerTitle.wears(title));
        assertEquals(sp(20), title.getTextSize(), 0.01f);
        assertEquals(Typeface.create("sans-serif-medium", Typeface.NORMAL), title.getTypeface());
        assertEquals(Integer.MAX_VALUE, title.getMaxLines());
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(colors.summary, message.getCurrentTextColor());
        assertEquals(sp(14), message.getTextSize(), 0.01f);
        assertEquals(dp(2), message.getLineSpacingExtra(), 0.01f);
    }

    @Test public void theButtonsAreCardsWithA48DpTargetAndWrapTheirWords() {
        for (ScreenColors colors : looks()) {
            AlertDialog dialog = show(new AlertDialog.Builder(controller.get())
                    .setTitle("Import settings").setPositiveButton("Import", null).setNegativeButton("Cancel", null));
            colors.paint(dialog);
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                boolean primary = which == AlertDialog.BUTTON_POSITIVE;
                Button button = dialog.getButton(which);
                assertTrue(button.getMinHeight() >= dp(48));
                assertTrue(button.getMinWidth() >= dp(64));
                assertEquals(sp(16), button.getTextSize(), 0.01f);
                assertFalse(button.isAllCaps());
                assertEquals(Integer.MAX_VALUE, button.getMaxLines());
                RippleDrawable background = (RippleDrawable) button.getBackground();
                GradientDrawable surface = (GradientDrawable) background.getDrawable(0);
                assertEquals(primary ? colors.accent : colors.card, surface.getColor().getDefaultColor());
                assertTrue("an off-scale radius", surface.getCornerRadius() <= dp(12));
                Bitmap drawn = draw(button);
                int middle = drawn.getHeight() / 2;
                // The outline starts where the framework's 4 dp inset ends, and the fill inside it.
                assertPixel("the dialog past the button", Color.TRANSPARENT, drawn.getPixel(dp(4) - 1, middle), true);
                assertPixel("the button's edge", primary ? colors.accent : colors.outline, drawn.getPixel(dp(4), middle), false);
                assertPixel("the button's fill", primary ? colors.accent : colors.card, drawn.getPixel(dp(8), middle), false);
            }
            Button primary = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals(colors.onAccent, primary.getCurrentTextColor());
            assertEquals(colors.secondaryActionText(), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getCurrentTextColor());
        }
    }

    @Test public void aPressAndAFocusShowOnEveryButton() {
        ScreenColors colors = ScreenColors.DEFAULT;
        AlertDialog dialog = show(new AlertDialog.Builder(controller.get())
                .setTitle("Import settings").setPositiveButton("Import", null).setNegativeButton("Cancel", null));
        colors.paint(dialog);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            Button button = dialog.getButton(which);
            Bitmap resting = draw(button);
            button.getBackground().setState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused});
            Bitmap focused = drawBackground(button);
            int middle = resting.getHeight() / 2;
            assertNotEquals("focus doesn't show on " + button.getText(),
                    resting.getPixel(dp(4), middle), focused.getPixel(dp(4), middle));
        }
    }

    @Test public void aPickedChoiceIsTintedInTheAccentAndEveryChoiceIsACard() {
        ScreenColors colors = ScreenColors.DEFAULT;
        AlertDialog dialog = show(new AlertDialog.Builder(controller.get())
                .setTitle("Download quality")
                .setSingleChoiceItems(new String[]{"Best", "Smaller file"}, 0, null)
                .setNegativeButton("Cancel", null));
        colors.paint(dialog);
        ListView list = dialog.getListView();
        assertTrue(list.getAdapter() instanceof DialogChoices);
        assertEquals("the pick was lost", 0, list.getCheckedItemPosition());
        View row = list.getAdapter().getView(1, null, list);
        assertTrue(row instanceof CheckedTextView);
        RippleDrawable background = (RippleDrawable) row.getBackground();
        Drawable layer = background.getDrawable(0);
        assertTrue("the choice isn't inside the panel's corners", layer instanceof InsetDrawable);
        GradientDrawable card = (GradientDrawable) ((InsetDrawable) layer).getDrawable();
        assertTrue("an off-scale radius", card.getCornerRadius() <= dp(12));
        int picked = card.getColor().getColorForState(new int[]{android.R.attr.state_checked}, 0);
        assertEquals("the pick isn't the accent at 13%", (colors.accent & 0x00FFFFFF) | 0x22000000, picked);
        assertEquals(Color.TRANSPARENT, card.getColor().getDefaultColor());
        assertNotNull("an unpicked row's press has nothing to show in", background.findDrawableByLayerId(android.R.id.mask));
        assertTrue(row.getMinimumHeight() >= dp(48));
    }

    @Test public void aFieldIsOutlinedInTheOffColourAndTheHeadingWhenFocused() {
        for (ScreenColors colors : looks()) {
            EditText field = new EditText(controller.get());
            field.setHint("Folder");
            colors.paintField(field);
            GradientDrawable outline = (GradientDrawable) field.getBackground();
            assertEquals(colors.dialog, outline.getColor().getDefaultColor());
            assertEquals(dp(12), outline.getCornerRadius(), 0.01);
            assertEquals(sp(16), field.getTextSize(), 0.01f);
            assertTrue(field.getMinHeight() >= dp(48));
            assertEquals(dp(12), field.getPaddingStart());
            assertEquals(dp(10), field.getPaddingTop());
            field.measure(View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            field.layout(0, 0, field.getMeasuredWidth(), field.getMeasuredHeight());
            Bitmap resting = drawBackground(field);
            int middle = resting.getHeight() / 2;
            assertPixel("the field's edge at rest", colors.switchOff, resting.getPixel(0, middle), false);
            outline.setState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused});
            assertPixel("the field's edge when focused", colors.heading, drawBackground(field).getPixel(0, middle), false);
        }
    }

    private AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        opened.add(dialog);
        ShadowLooper.idleMainLooper();
        return dialog;
    }

    /** [view] drawn on its own, laid out at its measured size over a clear bitmap. */
    private Bitmap draw(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(140), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        return drawBackground(view);
    }

    private static Bitmap drawBackground(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Drawable background = view.getBackground();
        background.setBounds(0, 0, view.getWidth(), view.getHeight());
        background.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static void assertPixel(String where, int expected, int actual, boolean alphaOnly) {
        boolean close = alphaOnly ? Color.alpha(actual) == Color.alpha(expected)
                : Math.abs(Color.red(expected) - Color.red(actual)) <= 2
                && Math.abs(Color.green(expected) - Color.green(actual)) <= 2
                && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 2;
        assertTrue(where + ": expected #" + Integer.toHexString(expected) + ", drew #" + Integer.toHexString(actual), close);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                controller.get().getResources().getDisplayMetrics());
    }
}
