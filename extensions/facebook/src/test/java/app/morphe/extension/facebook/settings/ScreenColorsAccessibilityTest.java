/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.facebook.theme.AmoledForTests;
import app.morphe.extension.facebook.theme.PalettesForTests;
import app.morphe.extension.facebook.theme.TonePalette;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Every colour role of the settings page, in every look it takes, at the contrast HushGram's layout
 * holds it to: text 4.5:1 on the page, its cards and its dialogs, a switch's off track, an icon and
 * a focus outline 3:1, the primary action's text 4.5:1 on its fill, and every stop of a gradient
 * title 7:1 on what it's drawn over. The resting card edge is decoration (WCAG 1.4.11 asks nothing
 * of it), so a focused, hovered or selected row is outlined in the heading colour instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ScreenColorsAccessibilityTest {
    private static final double TEXT = 4.5;
    private static final double NON_TEXT = 3.0;
    private static final double TITLE = 7.0;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        AmoledForTests.reset();
    }

    private static double contrast(int one, int two) {
        double a = TonePalette.luminance(one) + 0.05;
        double b = TonePalette.luminance(two) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }

    private static void meets(String what, int foreground, int surface, double needed) {
        double ratio = contrast(foreground, surface);
        assertTrue(String.format(Locale.ROOT, "%s: #%06X on #%06X is %.2f:1, under %.1f:1",
                what, foreground & 0xFFFFFF, surface & 0xFFFFFF, ratio, needed), ratio >= needed);
    }

    /** The black page, the Background colours the AMOLED theme takes, and Material You's dark and light pages. */
    private static Map<String, ScreenColors> looks() {
        Map<String, ScreenColors> looks = new LinkedHashMap<>();
        looks.put("black page", ScreenColors.DEFAULT);
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        // Issue #214's navy, Facebook's own dark page, Catppuccin's base and the lightest grey the row takes.
        for (int background : new int[]{0xFF0D1117, 0xFF18191A, 0xFF1E1E2E, 0xFF535353}) {
            AmoledForTests.useBackground(background);
            looks.put(String.format(Locale.ROOT, "black page over #%06X", background & 0xFFFFFF), ScreenColors.black());
        }
        AmoledForTests.reset();
        PatchFamily.inBuildForTests = null;
        Map<String, TonePalette> palettes = new LinkedHashMap<>();
        palettes.put("fixed palette", TonePalette.fallback());
        palettes.put("red wallpaper", PalettesForTests.palette(PalettesForTests.RED));
        palettes.put("yellow wallpaper", PalettesForTests.palette(PalettesForTests.YELLOW));
        palettes.put("green wallpaper", PalettesForTests.palette(PalettesForTests.GREEN));
        palettes.put("Galaxy S25 wallpaper", PalettesForTests.palette(PalettesForTests.GALAXY_S25));
        palettes.put("near-grey wallpaper", PalettesForTests.palette(PalettesForTests.NEAR_GREY));
        for (Map.Entry<String, TonePalette> palette : palettes.entrySet()) {
            looks.put("Material You dark, " + palette.getKey(), ScreenColors.of(palette.getValue(), false));
            looks.put("Material You light, " + palette.getKey(), ScreenColors.of(palette.getValue(), true));
        }
        return looks;
    }

    @Test
    public void everyTextRoleReadsOnThePageItsCardsAndItsDialogs() {
        for (Map.Entry<String, ScreenColors> look : looks().entrySet()) {
            ScreenColors colors = look.getValue();
            int[] surfaces = {colors.background, colors.card, colors.dialog};
            for (int surface : surfaces) {
                meets(look.getKey() + " title", colors.title, surface, TEXT);
                meets(look.getKey() + " summary", colors.summary, surface, TEXT);
                meets(look.getKey() + " hint", colors.hint, surface, TEXT);
                meets(look.getKey() + " heading", colors.heading, surface, TEXT);
                meets(look.getKey() + " error", colors.error, surface, TEXT);
            }
        }
    }

    /** The heading aims for 7:1 and the black page's reaches it on the page itself. */
    @Test
    public void theBlackPagesHeadingReachesSevenToOneOnThePage() {
        meets("black page heading", ScreenColors.DEFAULT.heading, ScreenColors.DEFAULT.background, TITLE);
    }

    @Test
    public void switchesIconsAndFocusOutlinesShowOnEverySurface() {
        for (Map.Entry<String, ScreenColors> look : looks().entrySet()) {
            ScreenColors colors = look.getValue();
            for (int surface : new int[]{colors.background, colors.card, colors.dialog}) {
                meets(look.getKey() + " off track", colors.switchOff, surface, NON_TEXT);
                meets(look.getKey() + " on track", colors.accent, surface, NON_TEXT);
                // Icons and the outline of a focused, hovered or selected row are the heading colour.
                meets(look.getKey() + " icon and focus outline", colors.heading, surface, NON_TEXT);
            }
            meets(look.getKey() + " icon on its own surface", colors.heading, colors.iconSurface, NON_TEXT);
            meets(look.getKey() + " off thumb", colors.offThumb(), colors.switchOff, NON_TEXT);
        }
    }

    @Test
    public void thePrimaryActionsTextReadsOnItsFill() {
        for (Map.Entry<String, ScreenColors> look : looks().entrySet()) {
            meets(look.getKey() + " primary action", look.getValue().onAccent, look.getValue().accent, TEXT);
        }
    }

    /**
     * A placeholder is never louder than a summary, so an empty field can't pass for a filled one.
     * Over the lightest grey both are lifted to the 4.5:1 floor, so there they read the same.
     */
    @Test
    public void aPlaceholderStaysQuieterThanASummary() {
        for (Map.Entry<String, ScreenColors> look : looks().entrySet()) {
            ScreenColors colors = look.getValue();
            for (int surface : new int[]{colors.background, colors.card, colors.dialog}) {
                double hint = contrast(colors.hint, surface);
                double summary = contrast(colors.summary, surface);
                assertTrue(String.format(Locale.ROOT, "%s: the hint reads %.2f:1 and the summary %.2f:1 on #%06X",
                        look.getKey(), hint, summary, surface & 0xFFFFFF), hint <= summary + 0.05);
            }
        }
    }

    @Test
    public void everyGradientStopReadsAtSevenToOneOnWhatItsDrawnOver() {
        for (Map.Entry<String, ScreenColors> look : looks().entrySet()) {
            ScreenColors colors = look.getValue();
            for (int surface : new int[]{colors.background, colors.dialog, Color.BLACK}) {
                for (int stop : ShimmerTitle.colorsOn(surface)) {
                    meets(look.getKey() + " title stop", stop, surface, TITLE);
                }
            }
        }
    }
}
