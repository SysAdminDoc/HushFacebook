/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;


/** Reaches the accent code's package-private reads from the settings tests. */
public final class AccentColorForTests {
    private AccentColorForTests() { }

    /** The accent in force, as the hooks read it. */
    public static AccentColor.Preset chosen() {
        return AccentColor.chosen();
    }

    /** What one of Facebook's blues becomes under {@code preset}, at the lightness it had. */
    public static int accentFor(AccentColor.Preset preset, int blue) {
        return AccentColor.palette(preset).sameLightness(TonePalette.ACCENT, blue);
    }
}
