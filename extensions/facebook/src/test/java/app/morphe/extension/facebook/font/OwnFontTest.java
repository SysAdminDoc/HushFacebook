/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Typeface;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * The font swap: which families it touches, what it answers, what the variation string of a
 * builder tells it, and which names a React Native screen asks for are Meta's. Whether it takes
 * Facebook's own path while paused or before the context is PausedHooksTest's and
 * ColdStartHooksTest's to say, and what a picked file draws in is OwnFontFileTest's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OwnFontTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Every constant of Facebook 580's font family enum, as the fixture test reads them, with 587's OPTIMISTIC_MONO. */
    enum Family {
        AVENY_T_REGULAR, FACEBOOK_NARROW, FACEBOOK_SANS_HEAVY_ITALIC, OPTIMISTIC_DISPLAY_APP,
        OPTIMISTIC_DISPLAY_APP_MEDIUM, OPTIMISTIC_TEXT_APP_REGULAR, OPTIMISTIC_TEXT_APP_MEDIUM,
        OPTIMISTIC_TEXT_APP_BOLD, OPTIMISTIC_VARIABLE_APP_LITE, OPTIMISTIC_AI, OPTIMISTIC_AI_1_BETA,
        OPTIMISTIC_AI_2_BETA, OPTIMISTIC_AI_3_BETA, OPTIMISTIC_MONO, OPTIMISTIC_VF_APP_LITE, FACEBOOK_SANS_VARIABLE,
        OLD_STANDARD_TT_REGULAR, SF_UI_TEXT_REGULAR, AVENIR_NEXT_BOLD_ITALIC, MONTSERRAT_EXTRA_BOLD,
        MONTSERRAT_EXTRA_BOLD_ITALIC, OLD_STANDARD_TT_BOLD, OLD_STANDARD_TT_ITALIC, ROBOTO_LIGHT,
        ROBOTO_LIGHT_ITALIC, SFUI_TEXT_REGULAR, SFUI_TEXT_REGULAR_ITALIC, BARLOW_SEMI_BOLD,
        COURIER_PRIME_BOLD, MONTSERRAT_REGULAR, ROBOTO_MONO_REGULAR, ARAPEY_ITALIC, BUBBLE_REGULAR,
        SERIF_REGULAR
    }

    /**
     * The families Meta draws its interface in, every Optimistic family of 580 to 587. 587's
     * OPTIMISTIC_MONO is left out: monospace text keeps its fixed width.
     */
    private static final List<Family> META = Arrays.asList(
            Family.OPTIMISTIC_DISPLAY_APP, Family.OPTIMISTIC_DISPLAY_APP_MEDIUM, Family.OPTIMISTIC_TEXT_APP_REGULAR,
            Family.OPTIMISTIC_TEXT_APP_MEDIUM, Family.OPTIMISTIC_TEXT_APP_BOLD, Family.OPTIMISTIC_VARIABLE_APP_LITE,
            Family.OPTIMISTIC_AI, Family.OPTIMISTIC_AI_1_BETA, Family.OPTIMISTIC_AI_2_BETA, Family.OPTIMISTIC_AI_3_BETA,
            Family.OPTIMISTIC_VF_APP_LITE, Family.FACEBOOK_SANS_VARIABLE);

    /** The patch is in Morphe Manager's default selection with its switch off; these tests turn it on. */
    @Before
    public void turnTheSwitchOn() {
        Settings.USE_SYSTEM_FONT.save(true);
    }

    @After
    public void restore() {
        Settings.USE_SYSTEM_FONT.resetToDefault();
        Settings.FONT_SOURCE.resetToDefault();
        OwnFont.fileChanged();
    }

    @Test
    public void onlyMetasInterfaceFamiliesAreSwapped() {
        for (Family family : Family.values()) {
            assertEquals(family.name(), META.contains(family), OwnFont.isInterfaceFamily(family.name()));
        }
        assertFalse(OwnFont.isInterfaceFamily(null));
        assertEquals(12, META.size());
        // A monospace word anywhere in the name keeps a family off the list, and only a word's start counts.
        assertFalse(OwnFont.isInterfaceFamily("OPTIMISTIC_MONOSPACE_APP"));
        assertTrue(OwnFont.isInterfaceFamily("OPTIMISTIC_HARMONOUS"));
    }

    @Test
    public void aMonospaceFamilyKeepsMetasFont() {
        assertSame(Typeface.MONOSPACE, OwnFont.replace(Typeface.MONOSPACE, Family.OPTIMISTIC_MONO, 400));
        assertSame(Typeface.MONOSPACE, OwnFont.replace(Typeface.MONOSPACE, Family.ROBOTO_MONO_REGULAR, -1));
    }

    /**
     * A builder made for a family the switch leaves alone keeps what it built, whatever it was told;
     * one made for an interface family, the positive control, gets the phone's font.
     */
    @Test
    public void aBuilderMadeForAMonospaceFamilyKeepsItsFace() {
        Object mono = new Object();
        assertSame("the maker gets its builder back", mono, OwnFont.rememberFamily(mono, Family.OPTIMISTIC_MONO));
        OwnFont.rememberVariation(mono, "'wght' 700");
        assertSame(Typeface.MONOSPACE, OwnFont.replaceBuilt(Typeface.MONOSPACE, mono));

        Object text = new Object();
        assertSame(text, OwnFont.rememberFamily(text, Family.OPTIMISTIC_VARIABLE_APP_LITE));
        OwnFont.rememberVariation(text, "'wght' 700");
        assertNotSame(Typeface.SERIF, OwnFont.replaceBuilt(Typeface.SERIF, text));

        assertNull(OwnFont.rememberFamily(null, Family.OPTIMISTIC_MONO));
        Object unnamed = new Object();
        assertSame(unnamed, OwnFont.rememberFamily(unnamed, null));
        assertNotSame(Typeface.SERIF, OwnFont.replaceBuilt(Typeface.SERIF, unnamed));
    }

    @Test
    public void theRepositorysAnswerBecomesThePhonesFont() {
        Typeface swapped = OwnFont.replace(Typeface.SERIF, Family.OPTIMISTIC_TEXT_APP_BOLD, 700);
        assertNotNull(swapped);
        assertNotSame(Typeface.SERIF, swapped);
        assertEquals("built from the phone's default family",
                shadowOf(Typeface.DEFAULT).getFontDescription().getFamilyName(),
                shadowOf(swapped).getFontDescription().getFamilyName());
        // A weight the caller left out is fine too: the built typeface's own is used.
        assertNotSame(Typeface.SERIF, OwnFont.replace(Typeface.SERIF, Family.OPTIMISTIC_DISPLAY_APP, -1));
    }

    @Test
    public void aCreativeFamilyPassesThrough() {
        assertSame(Typeface.SERIF, OwnFont.replace(Typeface.SERIF, Family.MONTSERRAT_REGULAR, 700));
        assertSame(Typeface.SERIF, OwnFont.replace(Typeface.SERIF, Family.FACEBOOK_NARROW, -1));
        assertSame(Typeface.SERIF, OwnFont.replace(Typeface.SERIF, Family.BUBBLE_REGULAR, -1));
    }

    @Test
    public void offTheSwitchLeavesMetasFont() {
        Settings.USE_SYSTEM_FONT.save(false);
        assertSame(Typeface.SERIF, OwnFont.replace(Typeface.SERIF, Family.OPTIMISTIC_TEXT_APP_BOLD, 700));
        Object builder = new Object();
        OwnFont.rememberVariation(builder, "'wght' 700");
        assertSame(Typeface.SERIF, OwnFont.replaceBuilt(Typeface.SERIF, builder));
        assertSame(Typeface.SERIF, OwnFont.replaceReactNative(Typeface.SERIF, "Optimistic VF App Lite 700"));
    }

    @Test
    public void nothingIsMadeOfNothing() {
        assertNull(OwnFont.replace(null, Family.OPTIMISTIC_TEXT_APP_BOLD, -1));
        assertSame(Typeface.SERIF, OwnFont.replace(Typeface.SERIF, null, -1));
        assertNull(OwnFont.replaceBuilt(null, new Object()));
        // A builder nobody told anything, or none at all, still gets the phone's font.
        assertNotSame(Typeface.SERIF, OwnFont.replaceBuilt(Typeface.SERIF, new Object()));
        assertNotSame(Typeface.SERIF, OwnFont.replaceBuilt(Typeface.SERIF, null));
        OwnFont.rememberVariation(null, "'wght' 700");
        assertNull(OwnFont.replaceReactNative(null, "Optimistic VF App Lite 700"));
        assertSame(Typeface.SERIF, OwnFont.replaceReactNative(Typeface.SERIF, null));
    }

    @Test
    public void variationSettingsGiveTheWeightAndSlant() {
        assertEquals(700, OwnFont.weightOf("'wght' 700", 400));
        assertEquals(650, OwnFont.weightOf("'wdth' 90, 'wght' 650.4", 400));
        assertEquals(700, OwnFont.weightOf("\"wght\" 700", 400));
        assertEquals(400, OwnFont.weightOf("'wdth' 90", 400));
        assertEquals(500, OwnFont.weightOf(null, 500));
        assertEquals(400, OwnFont.weightOf("'wght' 5000", 400));
        assertEquals(400, OwnFont.weightOf("'wght' 0", 400));
        assertTrue(OwnFont.italicOf("'ital' 1", false));
        assertTrue(OwnFont.italicOf("'slnt' -10", false));
        assertFalse(OwnFont.italicOf("'ital' 0", true));
        assertTrue(OwnFont.italicOf("'wght' 700", true));
        assertFalse(OwnFont.italicOf(null, false));
    }

    @Test
    public void aBuilderIsAnsweredWithWhatItWasTold() {
        Object builder = new Object();
        OwnFont.rememberVariation(builder, "'wght' 700");
        Typeface swapped = OwnFont.replaceBuilt(Typeface.SERIF, builder);
        assertNotSame(Typeface.SERIF, swapped);
        assertEquals(shadowOf(Typeface.DEFAULT).getFontDescription().getFamilyName(),
                shadowOf(swapped).getFontDescription().getFamilyName());
        // An empty string forgets the builder's settings, as Facebook's setter ignores one.
        OwnFont.rememberVariation(builder, "");
        assertNotSame(Typeface.SERIF, OwnFont.replaceBuilt(Typeface.SERIF, builder));
    }

    @Test
    public void outOfRangeWeightsFallBackToRegular() {
        assertNotNull(OwnFont.typeface(0, false));
        assertNotNull(OwnFont.typeface(1001, true));
        assertNotNull(OwnFont.typeface(400, false));
    }

    /**
     * The names React Native screens ask for. Facebook's font prefetcher registers Meta's variable
     * fonts in React Native's font manager as a name and a weight, the manager finds others among the
     * app's font assets by file name, and a screen's own style can name one outright. The story
     * display fonts, the system's names and the app's other assets are someone's choice and stay.
     */
    @Test
    public void reactNativeNamesMetasInterfaceFontsAndNothingElse() {
        for (String meta : new String[]{"Optimistic VF App Lite 400", "Optimistic VF App Lite 700",
                "Facebook Sans VF App 500", "Optimistic Display App", "Optimistic Text App Bold",
                "Optimistic_Text_A_Bd", "Optimistic_Display_A_Md", "Optimistic_A_SemiBold", "OptimisticDisplay-Bold",
                "optimistic text", "Facebook Sans Variable"}) {
            assertTrue(meta, OwnFont.isReactNativeInterfaceFamily(meta));
            assertTrue(meta, OwnFont.reactNativeFamily(meta) >= 0);
        }
        for (String other : new String[]{"FacebookSans_A_HeIt", "FacebookNarrow_A_Rg", "sans-serif",
                "sans-serif-medium", "Roboto", "System", "InstagramSansCondensed-Regular", "WhatsAppIcons",
                "Montserrat Regular", "Facebook Sans", "", "Optimisti"}) {
            assertFalse(other, OwnFont.isReactNativeInterfaceFamily(other));
            assertEquals(other, -1, OwnFont.reactNativeFamily(other));
        }
        assertFalse(OwnFont.isReactNativeInterfaceFamily(null));
    }

    @Test
    public void aRegisteredNameCarriesItsWeight() {
        assertEquals(700, OwnFont.weightSuffix("Optimistic VF App Lite 700"));
        assertEquals(400, OwnFont.weightSuffix("Facebook Sans VF App 400"));
        assertEquals(1000, OwnFont.weightSuffix("Optimistic 1000"));
        assertEquals(0, OwnFont.weightSuffix("Optimistic Display App"));
        assertEquals("a number joined to the name isn't a weight", 0, OwnFont.weightSuffix("Optimistic700"));
        assertEquals(0, OwnFont.weightSuffix("Optimistic 0"));
        assertEquals(0, OwnFont.weightSuffix("Optimistic 1001"));
        assertEquals(0, OwnFont.weightSuffix("Optimistic 12345"));
        assertEquals(0, OwnFont.weightSuffix("700"));
        assertEquals(0, OwnFont.weightSuffix(""));
        assertEquals(700, OwnFont.reactNativeFamily("Optimistic VF App Lite 700"));
        assertEquals(0, OwnFont.reactNativeFamily("Optimistic Display App"));
    }

    /**
     * React Native's answer for one of Meta's families becomes the chosen font. The weight it's
     * drawn at is OwnFontFileTest's to say: only real typefaces carry one.
     */
    @Test
    public void reactNativeTextInMetasFontsBecomesThePhonesFont() {
        Typeface swapped = OwnFont.replaceReactNative(Typeface.SERIF, "Optimistic VF App Lite 700");
        assertNotSame(Typeface.SERIF, swapped);
        assertEquals(shadowOf(Typeface.DEFAULT).getFontDescription().getFamilyName(),
                shadowOf(swapped).getFontDescription().getFamilyName());
        assertNotSame(Typeface.SERIF, OwnFont.replaceReactNative(Typeface.SERIF, "Facebook Sans VF App 500"));
        assertNotSame(Typeface.SERIF, OwnFont.replaceReactNative(Typeface.SERIF, "Optimistic_Text_A_Rg"));
        assertSame(Typeface.SERIF, OwnFont.replaceReactNative(Typeface.SERIF, "FacebookSans_A_HeIt"));
        assertSame(Typeface.SERIF, OwnFont.replaceReactNative(Typeface.SERIF, "sans-serif"));
    }

    /**
     * A picked file whose copy is missing draws in the phone's font, as though none were picked,
     * and the switch off still means Meta's.
     */
    @Test
    public void aPickedFileThatIsGoneMeansThePhonesFont() {
        Settings.FONT_SOURCE.save("Missing.ttf");
        OwnFont.fileChanged();
        Typeface swapped = OwnFont.replace(Typeface.SERIF, Family.OPTIMISTIC_TEXT_APP_REGULAR, 400);
        assertNotSame(Typeface.SERIF, swapped);
        assertEquals(shadowOf(Typeface.DEFAULT).getFontDescription().getFamilyName(),
                shadowOf(swapped).getFontDescription().getFamilyName());
        Settings.USE_SYSTEM_FONT.save(false);
        assertSame(Typeface.SERIF, OwnFont.replace(Typeface.SERIF, Family.OPTIMISTIC_TEXT_APP_REGULAR, 400));
    }
}
