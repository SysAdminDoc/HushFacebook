/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import androidx.annotation.Nullable;

/**
 * The icon Facebook shows on the home screen ({@link AppIcon}, #467). Each but the patched one is an
 * alias the Pick the app icon patch adds, named after Facebook's launcher entry and {@link #key}, and
 * drawn the way the Icon style option of the same name draws it.
 */
public enum AppIconChoice {
    /** Facebook's own launcher entry, with the icon Morphe drew when you patched. */
    AS_PATCHED(null),
    /** Facebook's blue icon with the white "f". */
    FACEBOOK("facebook"),
    /** Hushfacebook's H with its two shadows on blue. */
    HUSHFACEBOOK("hushfacebook"),
    /** Hushfacebook's H in white on black. */
    HUSHFACEBOOK_WHITE_ON_BLACK("hushfacebook_white_on_black"),
    /** Hushfacebook's H in black on white. */
    HUSHFACEBOOK_BLACK_ON_WHITE("hushfacebook_black_on_white"),
    /** Facebook's "f" in white on black. */
    WHITE_ON_BLACK("white_on_black"),
    /** Facebook's "f" in black on white. */
    BLACK_ON_WHITE("black_on_white");

    /** The end of this icon's alias, as the patch names it, or null for Facebook's own entry. */
    @Nullable public final String key;

    AppIconChoice(@Nullable String key) {
        this.key = key;
    }
}
