/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The ring between the overlapping reaction icons at the bottom right of a post (issue #147).
 *
 * <p>Facebook's FDSInlineReactions draws the icons as one drawable and separates each from the one
 * it overlaps in one of two ways, decided by the colour its style hands it. A colour with any
 * transparency makes it cut a ring out of the icon behind, so the page shows through; an opaque one
 * makes it paint a disc of that colour behind the next icon. The bling bar's COLOR and MEDIA styles
 * hand it ALWAYS_TRANSPARENT and get the cut. The SURFACE style hands it SURFACE_BACKGROUND, resolved
 * on the row's context, which in Facebook's dark styles is its #252728 surface and in the light ones
 * white. One reporter on a black AMOLED page saw white discs behind the icons: that resolve came back
 * light. A ring painted in any one colour can only match one page, while the cut matches every page,
 * AMOLED's black, a picked background colour and Material You's tint alike.
 *
 * <p>So in dark mode, with AMOLED or Material You in the build, an opaque ring colour comes back fully
 * transparent and the drawable cuts the ring instead. A colour that's already translucent, light
 * mode, and a build with neither theme come back as they were. The reactions token pile, a second
 * facepile that paints discs by default, gets the page colour through {@link #pile} instead.
 */
public final class ReactionRing {

    private ReactionRing() {
    }

    /** The colour that makes the facepile cut its rings: any alpha under 255 does, this one is clear. */
    static final int CUT = 0x00000000;

    /** The ring colour the facepile was about to be made with. Gives the colour to make it with. */
    public static int ring(int color) {
        boolean amoled = SettingsStatus.amoledTheme();
        boolean materialYou = MaterialYouSwitch.on();
        if (!amoled && !materialYou) return color;
        HookStatus.invoked(amoled ? FamilyNames.AMOLED_THEME : FamilyNames.MATERIAL_YOU_THEME);
        return ring(color, true, DarkMode.on());
    }

    /** {@link #ring(int)} with whether a theme is in the build and Facebook's dark answer given. */
    static int ring(int color, boolean themed, boolean dark) {
        if (!themed || !dark || color >>> 24 != 0xFF) return color;
        return CUT;
    }

    /**
     * The colour the reactions token pile paints the disc behind each of its icons with. That's a
     * second facepile, a Drawable built from a Context alone, drawn by the reaction summary under a
     * post (UFIFeedbackSummaryComponent), PillsBlingBarView, the likes line and the reactions header
     * of the feedback sheet. Unless its maker switches it to cutting, it paints a disc of one colour,
     * a token resolved on the context it's built with, which a theme attribute can override. A
     * context that resolves light while Facebook is dark paints white discs on a dark page.
     *
     * <p>So in dark mode, with AMOLED or Material You in the build, a light opaque colour comes back
     * as AMOLED's page colour, its Background colour option, or with Material You alone clear, so no
     * disc is drawn at all. A dark colour, which the themes' own colour routes already handle, light
     * mode and a build with neither theme come back as they were.
     */
    public static int pile(int color) {
        boolean amoled = SettingsStatus.amoledTheme();
        boolean materialYou = MaterialYouSwitch.on();
        if (!amoled && !materialYou) return color;
        HookStatus.invoked(amoled ? FamilyNames.AMOLED_THEME : FamilyNames.MATERIAL_YOU_THEME);
        return pile(color, amoled, DarkMode.on(), amoled ? AmoledTheme.background() : CUT);
    }

    /**
     * {@link #pile(int)} with a theme in the build, whether it's AMOLED, Facebook's dark answer and
     * AMOLED's page colour given.
     */
    static int pile(int color, boolean amoled, boolean dark, int page) {
        if (!dark || color >>> 24 != 0xFF || !light(color)) return color;
        return amoled ? page : CUT;
    }

    /** Whether [color] reads as a light surface: Facebook's white and light greys, never a dark one. */
    static boolean light(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return red * 299 + green * 587 + blue * 114 >= 160_000;
    }
}
