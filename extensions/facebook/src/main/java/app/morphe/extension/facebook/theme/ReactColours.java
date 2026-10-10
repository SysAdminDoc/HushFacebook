/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.SettingsStatus;

/**
 * The colours React Native screens, such as Marketplace home, set on their views. They come from
 * the screen's JavaScript as ints on view, text and image props, so none of the four colour routes
 * or the FDS night styles sees them. The patch calls these first thing in React's background,
 * border and image tint setters, and on the colour React's text colour span is built with.
 *
 * <p>No token comes with them, so the colour alone decides, as in route four. AMOLED goes first
 * for a background and leaves text and borders alone, and Material You follows with the dark
 * surfaces and Facebook's blues it knows, and for a background the greys its dark token table
 * lists as well. Either one runs only when its patch is in the build. Without Material You, Accent
 * color (route seven) gives Facebook's blues on text and backgrounds the chosen accent.
 */
public final class ReactColours {
    private ReactColours() {
    }

    /** A view's background colour. */
    public static int background(int color) {
        boolean materialYou = SettingsStatus.materialYouTheme();
        int themed = background(color, SettingsStatus.amoledTheme(), materialYou);
        return materialYou || !SettingsStatus.accentColor() ? themed : AccentColor.reactBackground(themed);
    }

    /** A text span's colour. */
    public static int text(int color) {
        return text(color, SettingsStatus.materialYouTheme(), SettingsStatus.accentColor());
    }

    /** {@link #text(int)} with the themes in the build given: Material You decides when it's there. */
    static int text(int color, boolean materialYou, boolean accent) {
        return materialYou || !accent ? text(color, materialYou) : AccentColor.reactText(color);
    }

    /** A border's or an image tint's colour, or null when the screen set none. */
    @Nullable
    public static Integer colour(@Nullable Integer color) {
        return colour(color, SettingsStatus.materialYouTheme());
    }

    /**
     * {@link #background(int)} with the themes in the build given. A colour AMOLED decides is its
     * own, so Material You only gives it the rule text gets: with a Background colour of #212121,
     * AMOLED's card is #333334, a grey Material You would otherwise tint on an untinted page.
     */
    static int background(int color, boolean amoled, boolean materialYou) {
        if (amoled) {
            int themed = AmoledTheme.react(color);
            if (AmoledTheme.ownsWithoutToken(color)) return materialYou ? MaterialYouTheme.react(themed) : themed;
        }
        return materialYou ? MaterialYouTheme.reactBackground(color) : color;
    }

    /** {@link #text(int)} with the theme in the build given. */
    static int text(int color, boolean materialYou) {
        return materialYou ? MaterialYouTheme.react(color) : color;
    }

    /** {@link #colour(Integer)} with the theme in the build given. */
    @Nullable
    static Integer colour(@Nullable Integer color, boolean materialYou) {
        if (color == null || !materialYou) return color;
        int recoloured = MaterialYouTheme.react(color);
        return recoloured == color ? color : Integer.valueOf(recoloured);
    }
}
