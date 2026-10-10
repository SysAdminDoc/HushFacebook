/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.theme.AccentColor.Preset;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Route five's table: which of the patch's colour resources it answers for, with which token's
 * colour, and how route one turns those colours back into Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentResourcesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int LINK_BLUE = 0xFF0064D1;
    private static final int BUTTON_BLUE = 0xFF0866FF;
    private static final int NIGHT_LINK_BLUE = 0xFF5AA7FF;

    /** 582's two blues as its light FDS style hands them out, plus a night value and a badge-only one. */
    private static final String TABLE = "7f0601d4=ff0064d1:BLUE_LINK,DECORATIVE_ICON_BLUE,TOGGLE_ACTIVE_TEXT;"
            + "7f0601d5=ff0866ff:ACCENT,BLUE_BADGE,PRIMARY_BUTTON_BACKGROUND,STORY_UNSEEN,VERIFIED_BADGE;"
            + "7f06044a=ff0064d1/ff5aa7ff:BLUE_LINK;"
            + "7f060608=ff0866ff:VERIFIED_BADGE";

    @After
    public void restore() {
        AccentResources.useTable(null);
        PauseForTests.resume();
        Settings.ACCENT_COLOR.resetToDefault();
    }

    @Test
    public void onlyColoursBehindAnAccentTokenAreKeptEachWithTheTokenItsDrawnFor() {
        List<AccentResources.Blue> blues = AccentResources.parse(TABLE);
        assertEquals("the badge-only colour is left out", 3, blues.size());
        assertEquals(0x7f0601d4, blues.get(0).id);
        assertEquals(LINK_BLUE, blues.get(0).colour);
        assertEquals("text keeps text's contrast", "BLUE_LINK", blues.get(0).token);
        assertEquals("a fill among the tokens makes it a fill", "PRIMARY_BUTTON_BACKGROUND", blues.get(1).token);
        assertFalse(blues.get(1).hasNight);
        assertTrue(blues.get(2).hasNight);
        assertEquals(NIGHT_LINK_BLUE, blues.get(2).night);

        assertTrue(AccentResources.parse(null).isEmpty());
        assertTrue(AccentResources.parse("").isEmpty());
    }

    @Test
    public void aColourIsDrawnForATextTokenOnlyWhenAllItsAccentTokensAreText() {
        assertEquals("ACCENT", AccentResources.drawnFor(new String[]{"ACCENT", "BLUE_LINK"}));
        assertEquals("CURSOR", AccentResources.drawnFor(new String[]{"ACCENT", "CURSOR", "BLUE_LINK"}));
        assertNull(AccentResources.drawnFor(new String[]{"VERIFIED_BADGE", "STORY_UNSEEN"}));
    }

    @Test
    public void eachPresetGivesRouteOnesColourForTheToken() {
        List<AccentResources.Blue> blues = AccentResources.parse(TABLE);
        for (Preset preset : Preset.values()) {
            AccentResources.Answer answer = AccentResources.answer(blues, preset);
            if (preset == Preset.FACEBOOK) {
                assertEquals("Facebook blue gives nothing", 0, answer.ids.length);
                continue;
            }
            assertArrayEquals(preset.name(), new int[]{0x7f0601d4, 0x7f0601d5, 0x7f06044a}, answer.ids);
            assertEquals(AccentColor.fds(LINK_BLUE, "BLUE_LINK", preset, false, false), answer.colours[0]);
            assertEquals(AccentColor.fds(BUTTON_BLUE, "PRIMARY_BUTTON_BACKGROUND", preset, false, false), answer.colours[1]);
            assertNotEquals(preset.name(), BUTTON_BLUE, answer.colours[1]);
            assertFalse(answer.hasNight[0]);
            assertTrue("the night value goes in too", answer.hasNight[2]);
            assertEquals(AccentColor.fds(NIGHT_LINK_BLUE, "BLUE_LINK", preset, false, false), answer.nights[2]);

            // Each colour the table gives turns back into Facebook's.
            assertEquals(LINK_BLUE, AccentResources.facebookColour(answer.colours[0], answer));
            assertEquals(BUTTON_BLUE, AccentResources.facebookColour(answer.colours[1], answer));
            assertEquals(NIGHT_LINK_BLUE, AccentResources.facebookColour(answer.nights[2], answer));
            assertEquals("any other colour stays", 0xFF333334, AccentResources.facebookColour(0xFF333334, answer));
        }
    }

    /**
     * The verified badge shares the button's colour resource, so with the table in use a resolver
     * hands route one the accent for it. Route one gives it Facebook's blue back, and the button its
     * accent as before.
     */
    @Test
    public void routeOneTurnsTheTablesColoursBackBeforeItDecides() {
        AccentResources.useTable(TABLE);
        Settings.ACCENT_COLOR.save(Preset.TEAL);
        int given = AccentResources.answer(Preset.TEAL).colours[1];

        assertEquals("a badge stays Facebook's", BUTTON_BLUE, AccentColor.fds(given, Token.VERIFIED_BADGE));
        assertEquals("a button takes the accent as it did", AccentColor.fds(BUTTON_BLUE, Token.PRIMARY_BUTTON_BACKGROUND),
                AccentColor.fds(given, Token.PRIMARY_BUTTON_BACKGROUND));
        assertNotEquals(BUTTON_BLUE, AccentColor.fds(given, Token.PRIMARY_BUTTON_BACKGROUND));
        assertEquals("Mig's dark scheme decides from Facebook's blue too", AccentColor.mig(BUTTON_BLUE, Token.VERIFIED_BADGE),
                AccentColor.mig(given, Token.VERIFIED_BADGE));

        // With no table, a colour that happens to be the accent's is nobody's to turn back.
        AccentResources.useTable(null);
        assertEquals(given, AccentColor.fds(given, Token.VERIFIED_BADGE));
    }

    @Test
    public void facebookBlueAndAPausedFacebookGiveNothing() {
        AccentResources.useTable(TABLE);
        assertNull("unset is Facebook blue", AccentColor.chosen());
        Settings.ACCENT_COLOR.save(Preset.GREEN);
        assertEquals(Preset.GREEN, AccentColor.chosen());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused", AccentColor.chosen());
    }

    /** Stands in for FDS tokens: only the constant names matter. */
    enum Token {
        PRIMARY_BUTTON_BACKGROUND, VERIFIED_BADGE
    }
}
