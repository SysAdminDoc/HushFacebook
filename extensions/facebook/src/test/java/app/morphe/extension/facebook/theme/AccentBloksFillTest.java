/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Route six: a Bloks box's fill (the profile's Add to story button) takes the accent the way a
 * primary button's fill does, keeps anything that isn't one of Facebook's blues, and is stock while
 * Facebook blue is chosen, while paused and before the settings are ready.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentBloksFillTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The Add to story fill 582 sends in light mode, and the blue its dark styles use. */
    private static final int[] FILLS = {0xFF0866FF, 0xFF1D85FC};

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.ACCENT_COLOR.resetToDefault();
    }

    @Test
    public void theFillTokenIsAThemedNonTextToken() {
        assertTrue(AccentColor.TOKEN_SET.contains(AccentColor.BLOKS_FILL_TOKEN));
        assertFalse(AccentColor.TEXT_TOKEN_SET.contains(AccentColor.BLOKS_FILL_TOKEN));
    }

    @Test
    public void aBlueFillBecomesTheAccentAtItsOwnLightness() {
        for (AccentColor.Preset preset : AccentColor.Preset.values()) {
            for (int fill : FILLS) {
                int themed = AccentColor.bloksFill(fill, preset);
                if (preset == AccentColor.Preset.FACEBOOK) {
                    assertEquals(fill, themed);
                    continue;
                }
                assertNotEquals(preset + " leaves the fill blue", fill, themed);
                assertEquals(preset + " moves the lightness", TonePalette.lstar(fill), TonePalette.lstar(themed), 1.5);
                assertEquals("alpha", fill >>> 24, themed >>> 24);
            }
        }
    }

    @Test
    public void otherFillsKeepTheirColour() {
        for (int fill : new int[]{0xFFE4E6EB, 0xFF3A3B3C, 0xFFFFFFFF, 0xFF42B72A, 0xFFE41E3F}) {
            assertEquals(String.format("#%08X", fill), fill, AccentColor.bloksFill(fill, AccentColor.Preset.TEAL));
        }
    }

    @Test
    public void theHookIsStockWhileUnsetOrPaused() {
        assertEquals(0xFF0866FF, AccentColor.bloksFill(0xFF0866FF));

        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        assertEquals(AccentColor.bloksFill(0xFF0866FF, AccentColor.Preset.TEAL), AccentColor.bloksFill(0xFF0866FF));
        assertNotEquals(0xFF0866FF, AccentColor.bloksFill(0xFF0866FF));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Facebook reads the default", 0xFF0866FF, AccentColor.bloksFill(0xFF0866FF));
    }
}
