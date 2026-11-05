/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.theme.AmoledBackground;
import app.morphe.extension.facebook.theme.AmoledForTests;
import app.morphe.extension.facebook.theme.AmoledTheme;
import app.morphe.extension.facebook.theme.ForceDarkModeForTests;
import app.morphe.extension.facebook.theme.PalettesForTests;
import app.morphe.extension.facebook.theme.TonePalette;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * WCAG 2.2 AA for what Hushfacebook draws itself with the Material You theme in the build: the
 * settings screen, its dialogs and its switches, in the dark and light setting, for the fixed
 * palette, the framework's own and wallpapers of three other hues. Text needs 4.5:1 against what
 * it sits on, a switch 3:1 against the page (success criteria 1.4.3 and 1.4.11). Tones fix
 * lightness, so the ratios barely move from one wallpaper to the next.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ScreenColorsTest {
    /** WCAG 2.2 AA for text smaller than 18pt, or 14pt bold. */
    static final double TEXT = 4.5;
    /** WCAG 2.2 AA for large text and for the parts of a control that show its state. */
    static final double NON_TEXT = 3.0;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The Background colours issue #214 names: a dark navy and the lightest grey the row takes. */
    private static final int NAVY = 0xFF0D1117;
    private static final int LIGHTEST_GREY = 0xFF535353;

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.FONT_SOURCE.resetToDefault();
        Settings.FORCE_DARK_MODE.resetToDefault();
        ForceDarkModeForTests.inBuild(null);
        AmoledForTests.reset();
    }

    /**
     * Force dark mode keeps Facebook dark on a phone in light mode, and the settings page opened
     * from it goes dark with it. It stayed light on a tablet whose Facebook has no Dark mode row
     * (#259). A build without the patch doesn't read the switch.
     */
    @Test
    public void forceDarkModeTakesThePageDarkOnALightPhone() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.MATERIAL_YOU_THEME);
        RuntimeEnvironment.setQualifiers("+notnight");
        Context context = RuntimeEnvironment.getApplication();
        ForceDarkModeForTests.inBuild(true);
        assertTrue("a light phone without the switch shows the light page", ScreenColors.forScreen(context).light);

        Settings.FORCE_DARK_MODE.save(true);
        assertFalse("Force dark mode left the page light", ScreenColors.forScreen(context).light);
        assertEquals(android.R.style.Theme_Material_NoActionBar, ScreenColors.themeFor(context));

        ForceDarkModeForTests.inBuild(false);
        assertTrue("a build without the patch read its switch", ScreenColors.forScreen(context).light);
    }

    /** The WCAG contrast ratio of two opaque colours, 1 to 21. */
    static double contrast(int one, int two) {
        double a = TonePalette.luminance(one) + 0.05;
        double b = TonePalette.luminance(two) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }

    /** Every pair on the screen and in its dialogs, by where it's drawn. */
    static Map<String, int[]> pairs(ScreenColors c) {
        Map<String, int[]> text = new LinkedHashMap<>();
        text.put("row title on its card", new int[]{c.title, c.card});
        text.put("title of a row a tap acts on, on its card", new int[]{c.heading, c.card});
        text.put("row summary on its card", new int[]{c.summary, c.card});
        text.put("section title on the page", new int[]{c.heading, c.background});
        text.put("title bar and back arrow on the page", new int[]{c.title, c.background});
        text.put("dialog title on the dialog", new int[]{c.title, c.dialog});
        text.put("dialog message on the dialog", new int[]{c.summary, c.dialog});
        // A dialog's choice cards are outlined cards in the card tone (ChoiceCards), so their text
        // is read against that. This pair used to name the page's tone, which no card is painted in.
        text.put("dialog choice's name on its card", new int[]{c.title, c.card});
        text.put("dialog choice's detail on its card", new int[]{c.summary, c.card});
        text.put("primary action text on its fill", new int[]{c.onAccent, c.accent});
        text.put("secondary action on the dialog", new int[]{c.secondaryActionText(), c.dialog});
        text.put("secondary recovery action on its card", new int[]{c.secondaryActionText(), c.card});
        return text;
    }

    static Map<String, int[]> controls(ScreenColors c) {
        Map<String, int[]> parts = new LinkedHashMap<>();
        parts.put("switch on, against its card", new int[]{c.accent, c.card});
        parts.put("switch off, against its card", new int[]{c.switchOff, c.card});
        // The thumb shows where the switch is, so it needs 3:1 against the track it sits on.
        parts.put("switch off's thumb, against its track", new int[]{c.offThumb(), c.switchOff});
        parts.put("chevron of a row a tap opens something from, against its card", new int[]{c.summary, c.card});
        return parts;
    }

    /** Fails naming the pair when a ratio is under what it needs. */
    static void assertMeets(String palette, Map<String, int[]> pairs, double needed) {
        for (Map.Entry<String, int[]> pair : pairs.entrySet()) {
            double ratio = contrast(pair.getValue()[0], pair.getValue()[1]);
            if (ratio < needed) {
                fail(String.format(java.util.Locale.ROOT, "%s: %s is %.2f:1, under %.1f:1 (#%08X on #%08X)",
                        palette, pair.getKey(), ratio, needed, pair.getValue()[0], pair.getValue()[1]));
            }
        }
    }

    private static Map<String, TonePalette> palettes() {
        Map<String, TonePalette> palettes = new LinkedHashMap<>();
        palettes.put("fixed palette (Android 11)", TonePalette.fallback());
        palettes.put("red wallpaper", PalettesForTests.palette(PalettesForTests.RED));
        palettes.put("yellow wallpaper", PalettesForTests.palette(PalettesForTests.YELLOW));
        palettes.put("green wallpaper", PalettesForTests.palette(PalettesForTests.GREEN));
        return palettes;
    }

    @Test
    public void everyPairMeetsAaInDarkAndLight() {
        for (Map.Entry<String, TonePalette> palette : palettes().entrySet()) {
            for (boolean light : new boolean[]{false, true}) {
                ScreenColors colors = ScreenColors.of(palette.getValue(), light);
                String name = palette.getKey() + (light ? ", light" : ", dark");
                assertMeets(name, pairs(colors), TEXT);
                assertMeets(name, controls(colors), NON_TEXT);
            }
        }
    }

    /**
     * The black page, which every build without the Material You theme shows. Nothing held it to
     * AA, and its dialogs' Cancel read at 3.4:1, accent blue on the dialog's grey.
     */
    @Test
    public void theBlackPageMeetsAa() {
        assertMeets("black page", pairs(ScreenColors.DEFAULT), TEXT);
        assertMeets("black page", controls(ScreenColors.DEFAULT), NON_TEXT);
    }

    /**
     * The black page over another Background colour (issue #214) holds to AA as the black one does:
     * for the two colours the issue names and for every colour the row admits on a grid across
     * the whole range, saturated ones included.
     */
    @Test
    public void theBlackPageOverABackgroundColourMeetsAa() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        List<Integer> colours = new ArrayList<>(java.util.Arrays.asList(NAVY, LIGHTEST_GREY));
        for (int red = 0; red <= 0xFF; red += 0x11) {
            for (int green = 0; green <= 0xFF; green += 0x11) {
                for (int blue = 0; blue <= 0xFF; blue += 0x11) {
                    Integer admitted = AmoledBackground.colour(
                            String.format(java.util.Locale.ROOT, "#%02X%02X%02X", red, green, blue));
                    if (admitted != null && admitted != Color.BLACK) colours.add(admitted);
                }
            }
        }
        assertTrue("the grid admitted only " + colours.size() + " colours", colours.size() > 20);
        for (int colour : colours) {
            AmoledForTests.useBackground(colour);
            ScreenColors colors = ScreenColors.black();
            String name = String.format(java.util.Locale.ROOT, "black page over #%06X", colour & 0xFFFFFF);
            assertEquals(name, colour, colors.background);
            assertEquals(name, AmoledTheme.surfaces()[1], colors.card);
            assertMeets(name, pairs(colors), TEXT);
            assertMeets(name, controls(colors), NON_TEXT);
        }
    }

    /**
     * Navy is dark enough for the black page's text and switches to read on its cards as they are,
     * so only the page, the cards, the dialogs and their edge change.
     */
    @Test
    public void navyTakesTheSurfacesAndKeepsTheText() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        AmoledForTests.useBackground(NAVY);
        ScreenColors colors = ScreenColors.black();
        ScreenColors black = ScreenColors.DEFAULT;
        assertEquals(NAVY, colors.background);
        // Navy plus the card step AMOLED gives Facebook's own cards, #121213, and the input fill's, #262627.
        assertEquals(0xFF1F232A, colors.card);
        assertEquals(colors.card, colors.dialog);
        assertEquals(0xFF33373E, colors.outline);
        assertEquals(black.title, colors.title);
        assertEquals(black.summary, colors.summary);
        assertEquals(black.heading, colors.heading);
        assertEquals(black.accent, colors.accent);
        assertEquals(black.switchOff, colors.switchOff);
        assertEquals(black.onAccent, colors.onAccent);
        assertSame(colors, ScreenColors.black());
    }

    /** The lightest grey lifts what would read under AA on its cards, and the primary action's text turns black. */
    @Test
    public void theLightestGreyLiftsWhatItMust() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        AmoledForTests.useBackground(LIGHTEST_GREY);
        ScreenColors colors = ScreenColors.black();
        assertEquals(LIGHTEST_GREY, colors.background);
        assertNotEquals(ScreenColors.DEFAULT.summary, colors.summary);
        assertNotEquals(ScreenColors.DEFAULT.accent, colors.accent);
        assertEquals(Color.BLACK, colors.onAccent);
        assertTrue(TonePalette.luminance(colors.card) > TonePalette.luminance(colors.background));
        assertTrue(TonePalette.luminance(colors.outline) > TonePalette.luminance(colors.card));
    }

    /** A black Background colour, a build without the AMOLED theme, or one with Material You keeps the black page. */
    @Test
    public void blackOrNoAmoledKeepsTheBlackPage() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        assertSame(ScreenColors.DEFAULT, ScreenColors.black());
        assertSame(ScreenColors.DEFAULT, ScreenColors.current());
        AmoledForTests.useBackground(NAVY);
        PatchFamily.inBuildForTests = EnumSet.complementOf(
                EnumSet.of(PatchFamily.MATERIAL_YOU_THEME, PatchFamily.AMOLED_THEME));
        assertSame(ScreenColors.DEFAULT, ScreenColors.black());
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        assertSame(ScreenColors.DEFAULT, ScreenColors.black());
    }

    /** The settings screen over navy: its page, its bars, the card under each row and a dialog. */
    @Test
    public void theScreenTakesTheBackgroundColour() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        AmoledForTests.useBackground(NAVY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            assertNull(ScreenColors.shown);
            ScreenColors colors = ScreenColors.black();
            assertEquals(NAVY, ((ColorDrawable) dialog.getView().getBackground()).getColor());
            assertEquals(NAVY, dialog.getDialog().getWindow().getStatusBarColor());
            assertEquals(NAVY, dialog.getDialog().getWindow().getNavigationBarColor());
            ListView list = dialog.getView().findViewById(android.R.id.list);
            assertEquals(NAVY, ((ColorDrawable) list.getBackground()).getColor());
            int cards = 0;
            for (View row : rows(dialog)) {
                Integer surface = surfaceColour(row.getBackground());
                if (surface == null) continue;
                assertEquals(colors.card, surface.intValue());
                cards++;
            }
            assertTrue("no row sits on a card", cards > 10);
            android.app.AlertDialog alert = new android.app.AlertDialog.Builder(controller.get())
                    .setTitle("Import settings").setPositiveButton("Import", null).create();
            alert.show();
            try {
                ScreenColors.dialog(alert);
                Integer panel = surfaceColour(alert.getWindow().getDecorView().getBackground());
                assertNotNull("the dialog has no panel", panel);
                assertEquals(colors.dialog, panel.intValue());
            } finally {
                alert.dismiss();
            }
        }
    }

    /**
     * The black page wears Astryx's dark tokens (issue #302): the card, popover, text and accent
     * as the kit gives them, and its border, white-grey at 0x19, laid over the card. White reads
     * at 3.1:1 on that accent, so what sits on it is black.
     */
    @Test
    public void theBlackPageWearsAstryxDarkTokens() {
        ScreenColors black = ScreenColors.DEFAULT;
        assertEquals(Color.BLACK, black.background);
        assertEquals(0xFF1F1F22, black.card);
        assertEquals(0xFF28292C, black.dialog);
        assertEquals(0xFFDFE2E5, black.title);
        assertEquals(0xFFAAAFB5, black.summary);
        assertEquals(0xFF3E9EFB, black.heading);
        assertEquals(0xFF2694FE, black.accent);
        assertEquals(over(0x19F2F4F6, black.card), black.outline);
        assertTrue(contrast(Color.WHITE, black.accent) < TEXT);
        assertEquals(Color.BLACK, black.onAccent);
        assertEquals(Color.BLACK, black.offThumb());
    }

    /** Rows space in Astryx's 4 dp steps, dialogs round at its container radius and buttons at its element radius. */
    @Test
    public void theBlackPageTakesAstryxSpacingAndCorners() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            float density = controller.get().getResources().getDisplayMetrics().density;
            int cards = 0;
            for (View row : rows(dialog)) {
                if (surfaceColour(row.getBackground()) == null) continue;
                assertEquals(Math.round(8 * density), row.getPaddingTop());
                assertEquals(Math.round(8 * density), row.getPaddingBottom());
                cards++;
            }
            assertTrue("no row sits on a card", cards > 10);
            android.app.AlertDialog alert = new android.app.AlertDialog.Builder(controller.get())
                    .setTitle("Import settings").setPositiveButton("Import", null).create();
            alert.show();
            try {
                ScreenColors.dialog(alert);
                GradientDrawable panel = (GradientDrawable) alert.getWindow().getDecorView().getBackground();
                assertEquals(Math.round(12 * density), panel.getCornerRadius(), 0.01);
                RippleDrawable button = (RippleDrawable) alert.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getBackground();
                assertEquals(Math.round(8 * density), ((GradientDrawable) button.getDrawable(0)).getCornerRadius(), 0.01);
            } finally {
                alert.dismiss();
            }
        }
    }

    /** [colour], with its own alpha, laid over the opaque [under]. */
    private static int over(int colour, int under) {
        float alpha = Color.alpha(colour) / 255f;
        return Color.rgb(Math.round(Color.red(colour) * alpha + Color.red(under) * (1 - alpha)),
                Math.round(Color.green(colour) * alpha + Color.green(under) * (1 - alpha)),
                Math.round(Color.blue(colour) * alpha + Color.blue(under) * (1 - alpha)));
    }

    /** The fill of a row's card or a dialog's panel, through the ripple and inset around it. */
    private static Integer surfaceColour(android.graphics.drawable.Drawable drawable) {
        if (drawable instanceof RippleDrawable && ((RippleDrawable) drawable).getNumberOfLayers() > 0) {
            return surfaceColour(((RippleDrawable) drawable).getDrawable(0));
        }
        if (drawable instanceof android.graphics.drawable.InsetDrawable) {
            return surfaceColour(((android.graphics.drawable.InsetDrawable) drawable).getDrawable());
        }
        if (drawable instanceof GradientDrawable && ((GradientDrawable) drawable).getColor() != null) {
            return ((GradientDrawable) drawable).getColor().getDefaultColor();
        }
        return null;
    }

    /** The framework's own wallpaper palette, as a phone on Android 12 and newer reads it. */
    @Test
    @Config(sdk = 34)
    public void theFrameworksPaletteMeetsAaInDarkAndLight() {
        TonePalette phone = TonePalette.of(RuntimeEnvironment.getApplication());
        assertTrue(phone.dynamic);
        for (boolean light : new boolean[]{false, true}) {
            ScreenColors colors = ScreenColors.of(phone, light);
            assertMeets("framework palette" + (light ? ", light" : ", dark"), pairs(colors), TEXT);
            assertMeets("framework palette" + (light ? ", light" : ", dark"), controls(colors), NON_TEXT);
        }
    }

    /** What the ratios actually are, so a reader of a failure elsewhere knows the margin. */
    @Test
    public void theFixedPalettesRatios() {
        ScreenColors dark = ScreenColors.of(TonePalette.fallback(), false);
        ScreenColors light = ScreenColors.of(TonePalette.fallback(), true);
        assertEquals(13.35, contrast(dark.title, dark.background), 0.01);
        assertEquals(10.11, contrast(dark.summary, dark.background), 0.01);
        assertEquals(10.10, contrast(dark.heading, dark.background), 0.01);
        assertEquals(16.73, contrast(light.title, light.background), 0.01);
        assertEquals(9.12, contrast(light.summary, light.background), 0.01);
        assertEquals(6.32, contrast(light.heading, light.background), 0.01);
    }

    /**
     * The negative control: a low-contrast pair has to fail the same check. Grey tone 60 on tone 50
     * is legible to nobody.
     */
    @Test
    public void aLowContrastPairFails() {
        TonePalette palette = TonePalette.fallback();
        Map<String, int[]> weak = new LinkedHashMap<>();
        weak.put("tone 60 on tone 50", new int[]{palette.tone(TonePalette.NEUTRAL, 60), palette.tone(TonePalette.NEUTRAL, 50)});
        try {
            assertMeets("control", weak, TEXT);
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("tone 60 on tone 50"));
            // A pair that clears 3:1 and not 4.5:1 passes as a control and fails as text.
            Map<String, int[]> between = new LinkedHashMap<>();
            between.put("neutral 60 on white", new int[]{palette.tone(TonePalette.NEUTRAL, 60), Color.WHITE});
            assertMeets("control", between, NON_TEXT);
            try {
                assertMeets("control", between, TEXT);
            } catch (AssertionError alsoExpected) {
                return;
            }
            fail("neutral tone 60 on white passed as text at "
                    + contrast(palette.tone(TonePalette.NEUTRAL, 60), Color.WHITE));
        }
        fail("a pair at " + contrast(weak.values().iterator().next()[0], weak.values().iterator().next()[1]) + ":1 passed");
    }

    /** Without the theme in the build the screen stays what it was: black, with the dark Material theme. */
    @Test
    public void withoutTheThemeTheScreenStaysBlack() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        Context context = RuntimeEnvironment.getApplication();
        assertNull(ScreenColors.forScreen(context));
        assertEquals(android.R.style.Theme_Material_NoActionBar, ScreenColors.themeFor(context));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            assertNull(ScreenColors.shown);
            assertEquals(Color.BLACK, ((ColorDrawable) dialog.getView().getBackground()).getColor());
            // The black page leaves the window's bars to the framework theme, which a window only
            // reads once it has its decor.
            android.view.Window plain = new android.app.Dialog(controller.get(), dialog.getTheme()).getWindow();
            plain.getDecorView();
            android.view.Window window = dialog.getDialog().getWindow();
            assertEquals(plain.getStatusBarColor(), window.getStatusBarColor());
            assertEquals(plain.getNavigationBarColor(), window.getNavigationBarColor());
        }
    }

    @Test
    public void withTheThemeALightPhoneGetsALightScreen() {
        assertScreenPainted(true);
    }

    @Test
    @Config(qualifiers = "night")
    public void withTheThemeADarkPhoneGetsADarkScreen() {
        assertScreenPainted(false);
    }

    private void assertScreenPainted(boolean light) {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        // A picked font puts the way back to the phone's font on the page, so its row is painted too.
        Settings.FONT_SOURCE.save("Inter.ttf");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            ScreenColors colors = ScreenColors.shown;
            assertNotNull("the page has no palette colours", colors);
            assertEquals(light, colors.light);
            assertEquals(ScreenColors.of(TonePalette.fallback(), light).background, colors.background);
            assertEquals(colors.background, ((ColorDrawable) dialog.getView().getBackground()).getColor());
            // Below Android 15 the window draws its own bars; the theme's grey and black under a
            // light page's dark icons can't be read, so the page's colour goes there.
            assertEquals(colors.background, dialog.getDialog().getWindow().getStatusBarColor());
            assertEquals(colors.background, dialog.getDialog().getWindow().getNavigationBarColor());

            int titles = 0;
            int switches = 0;
            for (View row : rows(dialog)) {
                TextView title = row.findViewById(android.R.id.title);
                if (title == null) continue;
                int drawn = title.getCurrentTextColor();
                int enabledColor = drawn | 0xFF000000;
                assertTrue("\"" + title.getText() + "\" is " + Integer.toHexString(drawn),
                        enabledColor == colors.title || enabledColor == colors.heading
                                || (row.isAccessibilityHeading() && enabledColor == colors.summary));
                // Disabled Marketplace options use the existing 38% Material tint. The old
                // assertion assumed every row on a newly opened page could be tapped.
                assertEquals(title.getText().toString(),
                        title.isEnabled() || !title.getTextColors().isStateful() ? 255 : 0x61, Color.alpha(drawn));
                assertTrue(contrast(enabledColor, colors.background) >= TEXT);
                titles++;
                TextView summary = row.findViewById(android.R.id.summary);
                if (summary != null && summary.getVisibility() == View.VISIBLE) {
                    int expected = summary.isEnabled() ? colors.summary : (colors.summary & 0x00FFFFFF) | 0x61000000;
                    assertEquals(expected, summary.getCurrentTextColor());
                }
                View widget = row.findViewById(android.R.id.switch_widget);
                if (widget instanceof Switch) {
                    assertNotNull(((Switch) widget).getThumbTintList());
                    assertEquals(colors.onAccent, ((Switch) widget).getThumbTintList()
                            .getColorForState(new int[]{android.R.attr.state_enabled, android.R.attr.state_checked}, 0));
                    assertEquals(colors.accent, ((Switch) widget).getTrackTintList()
                            .getColorForState(new int[]{android.R.attr.state_enabled, android.R.attr.state_checked}, 0));
                    assertEquals(ScreenColors.half(colors.accent), ((Switch) widget).getTrackTintList()
                            .getColorForState(new int[]{android.R.attr.state_checked}, 0));
                    switches++;
                }
            }
            assertTrue("no row titles were painted", titles > 10);
            assertTrue("no switch was painted", switches > 3);
            assertFalse(colors.light != light);
        }
    }

    /**
     * The filled primary action keeps the framework button's own insets, 4dp at the sides and 6dp
     * above and below, like every Material button. The fill used to take the button's whole
     * bounds, which left it about 3dp from the dialog's bottom edge against 11dp at the side.
     */
    @Test
    public void theFilledDialogActionKeepsItsInsets() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(controller.get())
                    .setTitle("Import settings").setPositiveButton("Import", null).setNegativeButton("Cancel", null).create();
            dialog.show();
            try {
                ScreenColors.DEFAULT.paint(dialog);
                for (int which : new int[]{android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE}) {
                    Button button = dialog.getButton(which);
                    RippleDrawable background = (RippleDrawable) button.getBackground();
                    float density = button.getResources().getDisplayMetrics().density;
                    assertEquals(Math.round(4 * density), background.getLayerInsetStart(0));
                    assertEquals(Math.round(6 * density), background.getLayerInsetTop(0));
                    assertEquals(Math.round(4 * density), background.getLayerInsetEnd(0));
                    assertEquals(Math.round(6 * density), background.getLayerInsetBottom(0));
                }
            } finally {
                dialog.dismiss();
            }
        }
    }

    /**
     * The recovery page's title and message are centred, and so is the space they're centred in.
     * Wrapping its own text, the message sat at the start while the title, wide enough to fill the
     * row, looked centred.
     */
    @Test
    public void theRecoveryMessageIsCentredAcrossTheRow() {
        android.widget.RelativeLayout row = new android.widget.RelativeLayout(RuntimeEnvironment.getApplication());
        TextView title = new TextView(row.getContext());
        title.setId(android.R.id.title);
        TextView summary = new TextView(row.getContext());
        summary.setId(android.R.id.summary);
        row.addView(title, new android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(summary, new android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        ScreenColors.recoveryMessage(row);
        assertTrue("the recovery title wears Facebook's gradient", ShimmerTitle.wears(title));
        for (TextView text : new TextView[]{title, summary}) {
            assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, text.getLayoutParams().width);
            assertEquals(android.view.Gravity.CENTER, text.getGravity() & android.view.Gravity.CENTER);
        }
    }

    /** The save folder's outlined field and the filled primary action use the same accent. */
    @Test
    public void theFolderDialogsFieldTakesTheAccent() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            HushfacebookPreferenceFragment page = (HushfacebookPreferenceFragment)
                    dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
            ValueRows.FolderRow row =
                    (ValueRows.FolderRow) page.findPreference(Settings.SAVE_FOLDER.key);
            assertNotNull("no save folder row", row);
            row.showDialog(null);
            ShadowLooper.idleMainLooper();
            try {
                ScreenColors colors = ScreenColors.shown;
                assertNotNull(colors);
                EditText field = row.getEditText();
                assertNull("the old underline tint remains", field.getBackgroundTintList());
                assertTrue("the field has no outlined surface", field.getBackground() instanceof GradientDrawable);
                assertEquals(colors.dialog, ((GradientDrawable) field.getBackground()).getColor().getDefaultColor());
                assertEquals(ScreenColors.half(colors.accent), field.getHighlightColor());
                assertTrue(contrast(colors.accent, colors.dialog) >= NON_TEXT);
                Button primary = ((android.app.AlertDialog) row.getDialog())
                        .getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                assertEquals(colors.onAccent, primary.getCurrentTextColor());
                assertTrue(primary.getBackground() instanceof RippleDrawable);
                assertEquals(colors.accent, ((GradientDrawable) ((RippleDrawable) primary.getBackground())
                        .getDrawable(0)).getColor().getDefaultColor());
                // The message on show is the preference layout's own, below a GONE one of AlertDialog's.
                List<TextView> shownMessages = new ArrayList<>();
                collectMessages(row.getDialog().getWindow().getDecorView(), shownMessages);
                assertFalse("no message on show in the dialog", shownMessages.isEmpty());
                for (TextView message : shownMessages) {
                    assertEquals("\"" + message.getText() + "\" keeps the theme's colour", colors.summary, message.getCurrentTextColor());
                    assertTrue(contrast(message.getCurrentTextColor(), colors.dialog) >= TEXT);
                }
                assertEquals(colors.summary, field.getCurrentHintTextColor());
                assertTrue(contrast(field.getCurrentHintTextColor(), colors.dialog) >= TEXT);
            } finally {
                row.getDialog().dismiss();
            }
        }
    }

    /**
     * The download quality's list. Its title and its Cancel button take the screen's colours, as
     * every other dialog here does, and each choice reads on the dialog.
     */
    @Test
    public void theQualityListTakesTheScreensColours() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            HushfacebookPreferenceFragment page = (HushfacebookPreferenceFragment)
                    dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
            ValueRows.QualityRow row =
                    (ValueRows.QualityRow) page.findPreference(Settings.DOWNLOAD_QUALITY.key);
            assertNotNull("no download quality row", row);
            row.showDialog(null);
            ShadowLooper.idleMainLooper();
            try {
                ScreenColors colors = ScreenColors.shown;
                assertNotNull(colors);
                android.app.AlertDialog list = (android.app.AlertDialog) row.getDialog();
                int titleId = list.getContext().getResources().getIdentifier("alertTitle", "id", "android");
                TextView title = list.findViewById(titleId);
                assertNotNull("the list has no title", title);
                assertEquals("Download quality", String.valueOf(title.getText()));
                assertTrue("the dialog title wears Facebook's gradient", ShimmerTitle.wears(title));
                assertEquals(colors.accent, list.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).getCurrentTextColor());
                assertEquals(DownloadQuality.values().length, list.getListView().getAdapter().getCount());
            } finally {
                row.getDialog().dismiss();
            }
        }
    }

    @Test public void disabledDialogActionsDifferWithoutRelyingOnWholeViewOpacity() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(controller.get())
                    .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
            dialog.show();
            try {
                for (ScreenColors colors : new ScreenColors[]{ScreenColors.DEFAULT,
                        ScreenColors.of(TonePalette.fallback(), false), ScreenColors.of(TonePalette.fallback(), true)}) {
                    colors.paint(dialog);
                    Button save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                    int enabledText = save.getCurrentTextColor();
                    save.setEnabled(false);
                    assertNotEquals("disabled Save still looks active", enabledText, save.getCurrentTextColor());
                    assertEquals("the button must retain its own state colors", 1f, save.getAlpha(), 0);
                    save.setEnabled(true);
                    assertEquals(enabledText, save.getCurrentTextColor());
                }
            } finally { dialog.dismiss(); }
        }
    }

    @Test public void selectedAndFocusedControlsKeepReadableBoundaries() {
        for (ScreenColors colors : new ScreenColors[]{ScreenColors.DEFAULT,
                ScreenColors.of(TonePalette.fallback(), false), ScreenColors.of(TonePalette.fallback(), true)}) {
            android.content.res.ColorStateList border = ScreenColors.focusStroke(colors.heading, colors.outline);
            assertEquals(colors.heading, border.getColorForState(
                    new int[]{android.R.attr.state_enabled, android.R.attr.state_focused}, 0));
            assertEquals(colors.heading, border.getColorForState(
                    new int[]{android.R.attr.state_enabled, android.R.attr.state_selected}, 0));
            assertEquals(colors.outline, border.getColorForState(new int[]{}, 0));
            assertTrue(contrast(colors.heading, colors.card) >= NON_TEXT);
            assertTrue(contrast(colors.heading, colors.dialog) >= NON_TEXT);
        }
    }

    @Test public void fieldsHaveDistinctFocusAndDisabledBoundaries() {
        EditText field = new EditText(RuntimeEnvironment.getApplication());
        for (ScreenColors colors : new ScreenColors[]{ScreenColors.DEFAULT,
                ScreenColors.of(TonePalette.fallback(), false), ScreenColors.of(TonePalette.fallback(), true)}) {
            colors.paintField(field);
            assertTrue("field border ignores focus", field.getBackground().isStateful());
            field.clearFocus();
            int enabledText = field.getCurrentTextColor();
            field.setEnabled(false);
            assertNotEquals(enabledText, field.getCurrentTextColor());
            field.setEnabled(true);
            assertEquals(enabledText, field.getCurrentTextColor());
        }
    }

    private static void collectMessages(View view, List<TextView> shown) {
        if (view instanceof TextView && view.getId() == android.R.id.message && view.isShown()) shown.add((TextView) view);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) collectMessages(group.getChildAt(index), shown);
        }
    }

    private static SettingsDialog show(Activity activity) {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(activity.getFragmentManager(), "hushfacebook_settings");
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no preference page", page instanceof HushfacebookPreferenceFragment);
        return dialog;
    }

    /** Every row the list draws, laid out tall enough that none is left off. */
    private static List<View> rows(SettingsDialog dialog) {
        ListView list = dialog.getView().findViewById(android.R.id.list);
        HushfacebookPreferenceFragment page = (HushfacebookPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        // Verify every preference row independently of the category shell.
        list.setAdapter(page.getPreferenceScreen().getRootAdapter());
        list.setOnItemClickListener(page.getPreferenceScreen());
        assertNotNull("no list in the dialog", list);
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80000, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 80000);
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < list.getChildCount(); index++) rows.add(list.getChildAt(index));
        return rows;
    }
}
