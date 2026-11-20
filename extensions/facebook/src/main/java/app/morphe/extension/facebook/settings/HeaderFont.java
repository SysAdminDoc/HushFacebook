/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import androidx.annotation.Nullable;

/**
 * Header text font (#532): the font the Home header text draws in. Bold is the one it always had,
 * so it's the default. Serif, script and monospace are Android's own families, on every phone.
 * Your font is the file picked under Font file, in bold, and Bold while none is picked.
 */
public enum HeaderFont {
    BOLD("bold"),
    REGULAR("regular"),
    SERIF("serif"),
    SCRIPT("script"),
    MONOSPACE("monospace"),
    YOUR_FONT("own");

    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;

    HeaderFont(String fileValue) {
        this.fileValue = fileValue;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static HeaderFont fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (HeaderFont font : values()) {
            if (font.fileValue.equals(value)) return font;
        }
        return null;
    }
}
