/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * The Marketplace filters dialog is Hushfacebook's own, so its title wears the gradient every
 * Hushfacebook title does, and so does the help it opens. The ? beside the title is a 48 dp target
 * with a rounded square outline, not a circle.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
public class MarketplaceFilterTitleTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;

    @Before public void open() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
    }

    @After public void close() {
        ShadowDialog.reset();
        controller.close();
    }

    @Test public void theFiltersTitleAndItsHelpWearTheGradient() {
        MarketplaceFilterButton.open(controller.get());
        ShadowLooper.idleMainLooper();
        AlertDialog filters = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("the filters dialog didn't open", filters);
        View decor = filters.getWindow().getDecorView();
        TextView title = findText(decor, "Advanced Marketplace filters");
        assertNotNull("the dialog has no title", title);
        assertTrue("the title isn't in the gradient", ShimmerTitle.wears(title));
        assertEquals(Typeface.create("sans-serif-medium", Typeface.NORMAL), title.getTypeface());

        View help = findDescribed(decor, "How Adv. Filters works");
        assertNotNull("no ? beside the title", help);
        assertTrue("the ? is under 48 dp", help.getLayoutParams().width >= dp(48) && help.getLayoutParams().height >= dp(48));
        assertTrue(help.getBackground() instanceof InsetDrawable);
        GradientDrawable outline = (GradientDrawable) ((InsetDrawable) help.getBackground()).getDrawable();
        assertEquals("the ? sits in a circle", GradientDrawable.RECTANGLE, outline.getShape());
        assertEquals(dp(8), outline.getCornerRadius(), 0.01);

        assertTrue(help.performClick());
        ShadowLooper.idleMainLooper();
        AlertDialog how = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame("the help didn't open", filters, how);
        int titleId = how.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView howTitle = how.findViewById(titleId);
        assertEquals("How Adv. Filters works", String.valueOf(howTitle.getText()));
        assertTrue("the help's title isn't in the gradient", ShimmerTitle.wears(howTitle));
    }

    private static TextView findText(View view, String wanted) {
        if (view instanceof TextView && wanted.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescribed(View view, String wanted) {
        if (wanted.contentEquals(String.valueOf(view.getContentDescription()))) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescribed(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }
}
