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

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Bloks text (a profile's bio link, route six) and React Native text (route seven) take the accent
 * the way a link does, held to 4.5:1 on the palette it came from, and are stock while Facebook blue
 * is chosen, while paused, without Accent color in the build, and whenever Material You is in the
 * build.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentLinkTextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The bio link 582 draws on a light profile. */
    private static final int LIGHT_LINK = 0xFF0064D1;

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.ACCENT_COLOR.resetToDefault();
    }

    @Test
    public void reactTextIsThemedAsALink() {
        assertTrue(AccentColor.TEXT_TOKEN_SET.contains(AccentColor.LINK_TEXT_TOKEN));
        for (AccentColor.Preset preset : AccentColor.Preset.values()) {
            Settings.ACCENT_COLOR.save(preset);
            int themed = ReactColours.text(LIGHT_LINK, false, true);
            if (preset == AccentColor.Preset.FACEBOOK) {
                assertEquals(LIGHT_LINK, themed);
                continue;
            }
            assertNotEquals(preset + " leaves the link blue", LIGHT_LINK, themed);
            assertTrue(preset + " link on a light card", AccentColor.contrast(themed, 0xFFFFFFFF) >= 4.5);
            assertTrue(preset + " link on light mode's page", AccentColor.contrast(themed, AccentColor.LIGHT_SURFACE) >= 4.5);
            assertEquals(preset + ": Bloks text, such as a bio link, takes the same", themed, AccentColor.bloksText(LIGHT_LINK));
        }
    }

    @Test
    public void bloksTextIsStockWhileUnsetOrPaused() {
        assertEquals(LIGHT_LINK, AccentColor.bloksText(LIGHT_LINK));
        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        assertNotEquals(LIGHT_LINK, AccentColor.bloksText(LIGHT_LINK));
        assertEquals("Bloks' black text", 0xFF080809, AccentColor.bloksText(0xFF080809));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Facebook reads the default", LIGHT_LINK, AccentColor.bloksText(LIGHT_LINK));
    }

    @Test
    public void otherTextKeepsItsColour() {
        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        for (int colour : new int[]{0xFF050505, 0xFF65676B, 0xFFE4E6EB, 0xFF42B72A, 0xFFE41E3F}) {
            assertEquals(String.format("#%08X", colour), colour, ReactColours.text(colour, false, true));
        }
    }

    @Test
    public void aBlueBackgroundIsFilledLikeABloksBox() {
        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        assertEquals(AccentColor.bloksFill(0xFF0866FF, AccentColor.Preset.TEAL), AccentColor.reactBackground(0xFF0866FF));
        assertEquals(0xFFE4E6EB, AccentColor.reactBackground(0xFFE4E6EB));
    }

    @Test
    public void reactColoursAreStockWithoutTheAccentOrWithMaterialYou() {
        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        assertEquals("Accent color not in the build", LIGHT_LINK, ReactColours.text(LIGHT_LINK, false, false));
        assertEquals("Material You decides", ReactColours.text(LIGHT_LINK, true), ReactColours.text(LIGHT_LINK, true, true));
        // The public hooks read SettingsStatus, whose flags stay off outside a patched build.
        assertEquals(LIGHT_LINK, ReactColours.text(LIGHT_LINK));
        assertEquals(0xFF0866FF, ReactColours.background(0xFF0866FF));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Facebook reads the default", LIGHT_LINK, ReactColours.text(LIGHT_LINK, false, true));
        assertEquals(0xFF0866FF, AccentColor.reactBackground(0xFF0866FF));
    }
}
