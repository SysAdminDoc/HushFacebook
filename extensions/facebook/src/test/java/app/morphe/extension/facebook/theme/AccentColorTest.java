/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The Accent color rules: a chosen accent replaces Facebook's blues and only them, keeps what
 * lightness and alpha Facebook gave, holds text to 4.5:1 against the cards of the palette Facebook
 * drew it from, and stock is stock (Facebook blue chosen, a paused Facebook, a start before the
 * settings are ready).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentColorTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's blues, from FDS's dark and light styles. */
    private static final int[] BLUES = {0xFF00488C, 0xFF0064D1, 0xFF0866FF, 0xFF1D85FC, 0xFF2D88FF, 0xFF3E93F8,
            0xFF5AA7FF, 0xFF75B6FF, 0xFFADD5FF};

    private static final AccentColor.Preset[] CHOICES = Arrays.stream(AccentColor.Preset.values())
            .filter(preset -> preset != AccentColor.Preset.FACEBOOK).toArray(AccentColor.Preset[]::new);

    /** Light mode's page and white card. */
    private static final int[] LIGHT_CARDS = {AccentColor.LIGHT_SURFACE, 0xFFFFFFFF};

    /** Dark mode's cards and pages, from FDS_DARK: the lightest card, the surfaces and the washes. */
    private static final int[] DARK_CARDS = {AccentColor.DARK_SURFACE, 0xFF252728, 0xFF1C1C1D, 0xFF101011};

    /** The blues Facebook's light palette gives text, where a token's dark list doesn't name them. */
    private static final int[] LIGHT_TEXT_BLUES = {0xFF0866FF, 0xFF0064D1};

    private static final Map<String, int[]> DARK_ONLY = MaterialYouTheme.parseTokens(MaterialYouTheme.FDS_DARK);
    private static final Map<String, int[]> BOTH = MaterialYouTheme.parseTokens(MaterialYouTheme.FDS_SHARED);

    private static boolean listed(int[] colours, int color) {
        if (colours == null) return false;
        for (int value : colours) {
            if ((value & 0xFFFFFF) == (color & 0xFFFFFF)) return true;
        }
        return false;
    }

    private static void holds(String what, int text, int[] cards) {
        for (int card : cards) {
            double ratio = AccentColor.contrast(text, card);
            assertTrue(what + ": #" + Integer.toHexString(text) + " on #" + Integer.toHexString(card) + " is " + ratio,
                    ratio >= 4.5);
        }
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.ACCENT_COLOR.resetToDefault();
    }

    /**
     * FDS picks light or dark per screen, so the app-wide answer can be wrong for a screen (light
     * mode's Video tab draws from the dark palette). Every text token's dark-only blues keep 4.5:1
     * on the dark cards and its light blues on the light cards, whatever the app-wide answer says;
     * a blue both palettes share follows the answer, and keeps Facebook's lightness before it.
     */
    @Test
    public void textKeepsFourAndAHalfToOneOnTheCardsOfThePaletteItCameFrom() {
        boolean[] both = {false, true};
        for (AccentColor.Preset preset : CHOICES) {
            for (String token : AccentColor.TEXT_TOKENS.split(";")) {
                int[] darkBlues = DARK_ONLY.get(token);
                assertTrue(token + " has no dark-only blue", darkBlues != null && darkBlues.length > 0);
                for (boolean answered : both) {
                    for (boolean appDark : both) {
                        String when = preset + " " + token + (answered ? (appDark ? " in dark mode" : " in light mode") : " before the answer");
                        for (int blue : darkBlues) {
                            holds(when + ", dark palette's #" + Integer.toHexString(blue),
                                    AccentColor.fds(blue, token, preset, answered, appDark), DARK_CARDS);
                        }
                        for (int blue : LIGHT_TEXT_BLUES) {
                            if (listed(darkBlues, blue) || listed(BOTH.get(token), blue)) continue;
                            holds(when + ", light palette's #" + Integer.toHexString(blue),
                                    AccentColor.fds(blue, token, preset, answered, appDark), LIGHT_CARDS);
                        }
                    }
                }
                int[] shared = BOTH.get(token);
                if (shared == null) continue;
                for (int blue : shared) {
                    holds(preset + " " + token + " shared #" + Integer.toHexString(blue) + " in dark mode",
                            AccentColor.fds(blue, token, preset, true, true), DARK_CARDS);
                    holds(preset + " " + token + " shared #" + Integer.toHexString(blue) + " in light mode",
                            AccentColor.fds(blue, token, preset, true, false), LIGHT_CARDS);
                    assertEquals(preset + " " + token + " shared before the answer keeps Facebook's lightness",
                            TonePalette.lstar(blue), TonePalette.lstar(AccentColor.fds(blue, token, preset, false, false)), 1.0);
                }
            }
        }
    }

    /** The finding's case: light mode's Video tab resolves BLUE_LINK to its dark #5AA7FF, and it stays light text. */
    @Test
    public void aDarkBlueInLightModeStaysLightText() {
        for (AccentColor.Preset preset : CHOICES) {
            int text = AccentColor.fds(0xFF5AA7FF, "BLUE_LINK", preset, true, false);
            assertTrue(preset + " went dark", TonePalette.lstar(text) >= AccentColor.DARK_TEXT_LIGHTNESS - 0.5);
            holds(preset + " BLUE_LINK on the Video tab", text, DARK_CARDS);
        }
    }

    /** Mig's dark scheme: its text and glyph blues keep 4.5:1 on the dark cards, and a button's fill keeps its lightness. */
    @Test
    public void migTextAndGlyphBluesHoldContrastAndFillsKeepTheirLightness() {
        for (AccentColor.Preset preset : CHOICES) {
            for (int blue : BLUES) {
                for (Enum<?> text : new Enum<?>[]{MigText.ACTIVE, MigText.LINK, MigGlyph.ACCENT, MigGlyph.DECORATIVE_BLUE}) {
                    holds(preset + " Mig " + text + " #" + Integer.toHexString(blue), AccentColor.mig(blue, preset, text), DARK_CARDS);
                }
                for (Object fill : new Object[]{MigButton.ACTIVE, MigButton.LINK, null, "LINK"}) {
                    int themed = AccentColor.mig(blue, preset, fill);
                    assertNotEquals(preset + " left Facebook's blue", blue, themed);
                    assertEquals(preset + " " + fill + " lightness of " + Integer.toHexString(blue),
                            TonePalette.lstar(blue), TonePalette.lstar(themed), 1.0);
                }
            }
        }
    }

    @Test
    public void aFillKeepsFacebooksLightnessAndAnAlphaStaysATint() {
        for (AccentColor.Preset preset : CHOICES) {
            for (int blue : BLUES) {
                int fill = AccentColor.fds(blue, "PRIMARY_BUTTON_BACKGROUND", preset, true, true);
                assertNotEquals(preset + " left Facebook's blue", blue, fill);
                assertEquals(preset + " lightness of " + Integer.toHexString(blue),
                        TonePalette.lstar(blue), TonePalette.lstar(fill), 1.0);
                int tint = AccentColor.fds((0x33 << 24) | (blue & 0xFFFFFF), "ACCENT_DEEMPHASIZED", preset, true, true);
                assertEquals(0x33, tint >>> 24);
            }
        }
    }

    @Test
    public void onlyFacebooksBluesOnTheAccentTokensChange() {
        AccentColor.Preset preset = AccentColor.Preset.TEAL;
        // Not a blue.
        assertEquals(0xFFE41E3F, AccentColor.fds(0xFFE41E3F, "ACCENT", preset, true, true));
        assertEquals(0xFF333334, AccentColor.fds(0xFF333334, "ACCENT", preset, true, true));
        // A blue on a token the accent doesn't own: a badge, a chart.
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "VERIFIED_BADGE", preset, true, true));
        assertEquals(0xFF1D85FC, AccentColor.fds(0xFF1D85FC, "DATAVIZ_BLUE_PRIMARY", preset, true, true));
        // The same blue on one it owns.
        assertNotEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "ACCENT", preset, true, true));
        // Facebook blue chosen is stock.
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "ACCENT", AccentColor.Preset.FACEBOOK, true, true));
        // Mig answers only opaque blues.
        assertEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, AccentColor.Preset.FACEBOOK, MigText.LINK));
        assertNotEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, preset, null));
        assertEquals(0xFF333334, AccentColor.mig(0xFF333334, preset, MigText.LINK));
    }

    @Test
    public void everyTokenIsOneTheMaterialYouListsReadFromFacebook() {
        Set<String> known = new HashSet<>();
        for (String list : new String[]{MaterialYouTheme.FDS_DARK, MaterialYouTheme.FDS_SHARED}) {
            for (String entry : list.split(";")) known.add(entry.substring(0, entry.indexOf('=')));
        }
        for (String token : AccentColor.TOKENS.split(";")) {
            assertTrue(token + " is in neither of Facebook's token lists", known.contains(token));
        }
        Set<String> all = new HashSet<>(Arrays.asList(AccentColor.TOKENS.split(";")));
        for (String token : AccentColor.TEXT_TOKENS.split(";")) {
            assertTrue(token + " holds text but is not an accent token", all.contains(token));
        }
    }

    @Test
    public void everyPresetsRampSpansLightToDarkAndHasAHue() {
        for (AccentColor.Preset preset : CHOICES) {
            TonePalette palette = AccentColor.palette(preset);
            int dark = palette.atLightness(TonePalette.ACCENT, 20, 0xFF000000);
            int mid = palette.atLightness(TonePalette.ACCENT, 50, 0xFF000000);
            int light = palette.atLightness(TonePalette.ACCENT, 90, 0xFF000000);
            assertTrue(preset + " ramp order", TonePalette.lstar(dark) < TonePalette.lstar(mid)
                    && TonePalette.lstar(mid) < TonePalette.lstar(light));
            int r = (mid >> 16) & 0xFF;
            int g = (mid >> 8) & 0xFF;
            int b = mid & 0xFF;
            assertTrue(preset + " is grey", Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 20);
        }
    }

    @Test
    public void theHooksAreStockWhileUnsetPausedOrBeforeTheSettingsAreReady() {
        Object token = Token.ACCENT;
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, token));
        assertEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, token));

        Settings.ACCENT_COLOR.save(AccentColor.Preset.GREEN);
        assertNotEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, token));
        assertNotEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, token));
        // A colour that isn't a Facebook blue is the same with an accent on.
        assertEquals(0xFF333334, AccentColor.fds(0xFF333334, token));
        // A token that is no enum is left alone.
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "ACCENT"));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Facebook reads the default", 0xFF0866FF, AccentColor.fds(0xFF0866FF, token));
        assertEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, token));
    }

    /**
     * #383: the Rounded tab bar's faint spot takes the chosen accent and keeps its own alpha, with
     * Accent color in the build only, so a saved accent from another build can't tint it alone.
     */
    @Test
    public void theTabBarSpotTakesTheChosenAccentWithThePatchInTheBuild() {
        int spot = 0x330866FF;
        assertEquals("Facebook's blue chosen", spot, AccentColor.themedBlue(spot, false, true));

        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        int teal = AccentColor.themedBlue(spot, false, true);
        assertEquals("the spot's own alpha", 0x33, teal >>> 24);
        int red = (teal >> 16) & 0xFF;
        int green = (teal >> 8) & 0xFF;
        int blue = teal & 0xFF;
        assertTrue("teal, not Facebook's blue: #" + Integer.toHexString(teal), green > red + 40 && green * 2 > blue);
        assertEquals("without Accent color in the build, Facebook's blue", spot, AccentColor.themedBlue(spot, false, false));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused, Facebook's blue", spot, AccentColor.themedBlue(spot, false, true));
    }

    /** Stands in for an FDS token: only the constant name matters. */
    enum Token {
        ACCENT
    }

    /**
     * Stand in for Mig's text and glyph colour enums, and for another of its colour enums that shares
     * a name with each but neither pair: only the constant names matter.
     */
    /**
     * This class's start parses MaterialYouTheme's token lists, and MaterialYouTheme's start reads
     * the chosen preset's palette from here (#272). Whichever starts first runs the other's start
     * inside its own, so the preset palettes have to be made before the token lists are asked for:
     * on a phone with the theme out of the build this class starts first, and with an accent picked
     * the read found no palette map yet, a toast at every start of Facebook (#300). The JVM hands
     * the fields back in the order they're declared.
     */
    @Test
    public void thePresetPalettesAreMadeBeforeMaterialYouIsAskedForItsTokens() {
        List<String> order = new ArrayList<>();
        for (Field field : AccentColor.class.getDeclaredFields()) order.add(field.getName());
        int palettes = order.indexOf("PALETTES");
        assertTrue("no PALETTES field in " + order, palettes >= 0);
        for (String tokens : new String[]{"DARK_ONLY", "BOTH"}) {
            int at = order.indexOf(tokens);
            assertTrue("no " + tokens + " field in " + order, at >= 0);
            assertTrue(tokens + " is declared before PALETTES: " + order, at > palettes);
        }
    }

    enum MigText {
        ACTIVE, LINK, SECONDARY_EMPHASIZED
    }

    enum MigGlyph {
        ACCENT, DECORATIVE_BLUE, TERTIARY
    }

    enum MigButton {
        ACTIVE, LINK, TERTIARY
    }
}
