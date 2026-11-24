/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

/** The Material You theme's switch, asked from tests in other packages with the theme in the build. */
public final class MaterialYouForTests {
    private MaterialYouForTests() {
    }

    /**
     * Whether a server colour Facebook gives in dark mode takes the palette's place, read as a new
     * start would: the switch is read once per start, so each ask forgets the last answer.
     */
    public static boolean recolours() {
        MaterialYouSwitch.forgetForTests();
        MaterialYouSwitch.inBuildForTests = true;
        try {
            DarkMode.answer(true);
            MaterialYouTheme.use(TonePalette.fallback(), false);
            return MaterialYouTheme.parseColor("#252728") != 0xFF252728;
        } finally {
            MaterialYouSwitch.forgetForTests();
        }
    }
}
