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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * With Debug logging on, an FDS token that comes with a dark surface's colour and is left alone is
 * named in the log once, so a report says which token is behind a gray Material You missed (#37),
 * and in light mode one that comes with a light surface grey (#141).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MaterialYouUnlistedTokensTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Facebook's FDS token enum. */
    enum Token { SURFACE_BACKGROUND, COMMENT_LIST_BACKGROUND, COMMENT_BACKGROUND, LATER_TOKEN, LIGHT_TEXT_TOKEN }

    @Before
    public void prepare() {
        BaseSettings.DEBUG.save(true);
        DarkMode.answer(true);
        MaterialYouTheme.forgetUnlistedForTests();
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        BaseSettings.DEBUG.resetToDefault();
        DarkMode.forget();
        MaterialYouTheme.forgetUnlistedForTests();
        LogBufferManager.clearLogBuffer();
    }

    @Test
    public void aTokenLeftAtADarkSurfaceIsNamedOnce() {
        assertEquals(0xFF252728, MaterialYouTheme.fds(0xFF252728, Token.COMMENT_LIST_BACKGROUND));
        MaterialYouTheme.fds(0xFF252728, Token.COMMENT_LIST_BACKGROUND);
        assertNotEquals("a listed token still takes the palette", 0xFF252728,
                MaterialYouTheme.fds(0xFF252728, Token.SURFACE_BACKGROUND));
        MaterialYouTheme.fds(0xFFC9CCD1, Token.LATER_TOKEN);

        String log = LogBufferManager.buildExportText();
        String line = "Material You left token COMMENT_LIST_BACKGROUND at #252728";
        assertEquals(log, log.indexOf(line), log.lastIndexOf(line));
        assertTrue(log, log.contains(line));
        assertFalse("a listed token isn't named", log.contains("left token SURFACE_BACKGROUND"));
        assertFalse("nor a colour that is no dark surface", log.contains("left token LATER_TOKEN"));
    }

    @Test
    public void withDebugLoggingOffNothingIsWrittenAndTheTokenWaits() {
        BaseSettings.DEBUG.save(false);
        MaterialYouTheme.fds(0xFF252728, Token.LATER_TOKEN);
        assertFalse(LogBufferManager.buildExportText().contains("left token LATER_TOKEN"));

        BaseSettings.DEBUG.save(true);
        MaterialYouTheme.fds(0xFF252728, Token.LATER_TOKEN);
        assertTrue(LogBufferManager.buildExportText().contains("Material You left token LATER_TOKEN at #252728"));
    }

    /**
     * Issue #141: in light mode the S22 (586) drew the comment composer and the Settings search field
     * in stock #F0F2F5, so a token left at one of the light greys FDS_LIGHT lists for a surface is
     * named too. A listed token, white and a text grey aren't.
     */
    @Test
    public void inLightModeATokenLeftAtALightSurfaceGreyIsNamedOnce() {
        DarkMode.answer(false);
        assertEquals(0xFFF0F2F5, MaterialYouTheme.fds(0xFFF0F2F5, Token.LATER_TOKEN));
        MaterialYouTheme.fds(0xFFF0F2F5, Token.LATER_TOKEN);
        MaterialYouTheme.fds(0xFFF0F2F5, Token.COMMENT_BACKGROUND);
        MaterialYouTheme.fds(0xFFFFFFFF, Token.SURFACE_BACKGROUND);
        MaterialYouTheme.fds(0xFF65686C, Token.LIGHT_TEXT_TOKEN);

        String log = LogBufferManager.buildExportText();
        String line = "Material You left token LATER_TOKEN at #F0F2F5";
        assertTrue(log, log.contains(line));
        assertEquals(log, log.indexOf(line), log.lastIndexOf(line));
        assertFalse("a token FDS_LIGHT lists isn't named", log.contains("left token COMMENT_BACKGROUND"));
        assertFalse("nor white, which cards keep", log.contains("left token SURFACE_BACKGROUND"));
        assertFalse("nor a text grey", log.contains("left token LIGHT_TEXT_TOKEN"));
    }
}
