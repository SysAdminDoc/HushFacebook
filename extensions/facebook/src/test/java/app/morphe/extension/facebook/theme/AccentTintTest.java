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
 * Facebook's pale blues in light mode: on the S22 (582, 2026-10-10, teal) a selected chip and
 * Marketplace's pill kept #DDEDFE while the same chip took teal in dark mode. Each of
 * {@link AccentColor#FACEBOOK_TINTS} on an accent token becomes a pale accent at its own lightness;
 * other pale blues, other tokens, Facebook blue and a paused Facebook keep it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentTintTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.ACCENT_COLOR.resetToDefault();
    }

    @Test
    public void eachTintIsTooGreyForTheBlueCheckAlone() {
        for (int tint : AccentColor.FACEBOOK_TINTS) {
            assertFalse(String.format("#%06X", tint), MaterialYouTheme.isFacebookBlue(0xFF000000 | tint));
            assertTrue(String.format("#%06X", tint), AccentColor.isAccentBlue(0xFF000000 | tint));
        }
    }

    @Test
    public void aTintOnAnAccentTokenBecomesAPaleAccentAtItsLightness() {
        for (AccentColor.Preset preset : AccentColor.Preset.values()) {
            if (preset == AccentColor.Preset.FACEBOOK) continue;
            for (int tint : AccentColor.FACEBOOK_TINTS) {
                int colour = 0xFF000000 | tint;
                for (String token : new String[]{"ACCENT_DEEMPHASIZED", "NEW_NOTIFICATION_BACKGROUND",
                        "PRIMARY_DEEMPHASIZED_BUTTON_BACKGROUND", AccentColor.BLOKS_FILL_TOKEN}) {
                    int themed = AccentColor.fds(colour, token, preset, true, false);
                    String what = preset + " " + token + String.format(" #%06X", tint);
                    assertNotEquals(what + " stayed blue", colour, themed);
                    assertEquals(what + " lightness", TonePalette.lstar(colour), TonePalette.lstar(themed), 1.5);
                    assertEquals(what + " alpha", 0xFF, themed >>> 24);
                    assertEquals(what + " is the accent's tone there", AccentColor.palette(preset)
                            .sameLightness(TonePalette.ACCENT, colour), themed);
                }
                // A translucent tint keeps its alpha.
                int half = (0x80 << 24) | tint;
                assertEquals(0x80, AccentColor.fds(half, "ACCENT_DEEMPHASIZED", preset, true, false) >>> 24);
            }
        }
    }

    @Test
    public void bloksAndReactTintsFollow() {
        int chip = 0xFFDDEDFE;
        assertNotEquals(chip, AccentColor.bloksFill(chip, AccentColor.Preset.TEAL));
        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        assertEquals(AccentColor.bloksFill(chip, AccentColor.Preset.TEAL), AccentColor.reactBackground(chip));
    }

    @Test
    public void otherPaleBluesOtherTokensAndFacebookBlueKeepTheTint() {
        AccentColor.Preset teal = AccentColor.Preset.TEAL;
        for (int pale : new int[]{0xFFE3F2FD, 0xFFDCEBFA, 0xFFF0F2F5, 0xFFE4E6EB}) {
            assertEquals(String.format("#%08X", pale), pale, AccentColor.fds(pale, "ACCENT_DEEMPHASIZED", teal, true, false));
        }
        assertEquals(0xFFDDEDFE, AccentColor.fds(0xFFDDEDFE, "VERIFIED_BADGE", teal, true, false));
        assertEquals(0xFFDDEDFE, AccentColor.fds(0xFFDDEDFE, "WASH", teal, true, false));
        assertEquals(0xFFDDEDFE, AccentColor.fds(0xFFDDEDFE, "ACCENT_DEEMPHASIZED", AccentColor.Preset.FACEBOOK, true, false));
    }

    @Test
    public void theHooksKeepTheTintWhileUnsetOrPaused() {
        assertEquals(0xFFDDEDFE, AccentColor.bloksFill(0xFFDDEDFE));
        Settings.ACCENT_COLOR.save(AccentColor.Preset.TEAL);
        assertNotEquals(0xFFDDEDFE, AccentColor.bloksFill(0xFFDDEDFE));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(0xFFDDEDFE, AccentColor.bloksFill(0xFFDDEDFE));
    }
}
