/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The time on Facebook's older comment row, the one that sits left of Like and Reply (issue #138).
 * On a pure black AMOLED comment sheet one reporter saw that slot laid out but blank, while Like,
 * Reply and the reaction count showed in grey. The row's TimestampPlugin (a name Redex keeps) wraps
 * the time in one span whose colour is the theme attribute Facebook's own styles point at its
 * secondary text, read on the row's context, with SECONDARY_TEXT's token only as the fallback. No
 * account we test with is served that row, so the colour's source isn't proven. The patch hands the
 * colour here before the span is made, and in dark mode, with AMOLED or Material You in the build, a
 * colour too close to the page to read comes back as Facebook's dark secondary text, themed the way
 * every other secondary text is. In light mode, with AMOLED's Light background color row set, the
 * time is held the way light mode's other secondary text is ({@link AmoledTheme#lightNamed}), since
 * the sheet under it takes the row's colour (#214). Light mode otherwise, a build with neither
 * theme, and any colour that already reads come back as they were.
 */
public final class CommentTimeColour {

    private CommentTimeColour() {
    }

    /** Facebook's secondary text in its dark theme, SECONDARY_TEXT's first dark colour. */
    static final int DARK_SECONDARY_TEXT = 0xFFB0B3B8;

    /** The comment sheet's page in Facebook's own dark theme, SURFACE_BACKGROUND. */
    static final int DARK_PAGE = 0xFF252728;

    /** The token the time's colour stands in for, whose rules the lifted colour follows. */
    static final String TOKEN = "SECONDARY_TEXT";

    /**
     * The least contrast the time keeps against its page: WCAG's AA for normal text. Facebook's own
     * dark secondary greys clear it on every dark page it draws (#B0B3B8 is 7.2:1 on #252728 and
     * 10.6:1 on black, #A1A4A9 6.1:1 on #252728), and the light theme's #65686C on black, what a row
     * left on the light theme would draw, doesn't (3.7:1).
     */
    static final double MIN_CONTRAST = AmoledTheme.TEXT_CONTRAST;

    /** The time's colour, as the row was about to draw it. Gives the colour to draw. */
    public static int commentTime(int color) {
        boolean amoled = SettingsStatus.amoledTheme();
        boolean materialYou = MaterialYouSwitch.on();
        if (!amoled && !materialYou) return color;
        HookStatus.invoked(amoled ? FamilyNames.AMOLED_THEME : FamilyNames.MATERIAL_YOU_THEME);
        return commentTime(color, amoled, materialYou, DarkMode.on());
    }

    /** {@link #commentTime(int)} with the themes in the build and Facebook's dark answer given. */
    static int commentTime(int color, boolean amoled, boolean materialYou, boolean dark) {
        if (!amoled && !materialYou) return color;
        if (!dark) return amoled ? AmoledTheme.lightNamed(color, TOKEN) : color;
        int page = page(amoled);
        if (contrast(AmoledTheme.over(page, color), page) >= MIN_CONTRAST) return color;
        int lifted = DARK_SECONDARY_TEXT;
        if (amoled) lifted = AmoledTheme.foreground(lifted, TOKEN);
        if (materialYou) lifted = MaterialYouTheme.fdsNamed(lifted, TOKEN);
        return lifted;
    }

    /**
     * The page the time sits on: AMOLED's background colour with AMOLED in the build, otherwise
     * Facebook's dark sheet, which Material You keeps at the same lightness.
     */
    static int page(boolean amoled) {
        return amoled ? AmoledTheme.background() : DARK_PAGE;
    }

    /** WCAG's contrast ratio of two opaque colours. */
    static double contrast(int first, int second) {
        double a = TonePalette.luminance(first);
        double b = TonePalette.luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }
}
