/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import app.morphe.extension.facebook.settings.SettingsStatus;

/**
 * The colour of the selected tab in Facebook's tab bar, its icon and the line over it. One hook
 * hands it here for both patches that change it, Material You and Badge and logo colors, so the
 * two never hook the same reads twice. Material You goes first and the picked Selected tab color
 * (#297) last, so a colour you typed beats the wallpaper's.
 */
public final class SelectedTab {
    private SelectedTab() {}

    public static int colour(int color) {
        return colour(color, MaterialYouSwitch.on(), SettingsStatus.markColors());
    }

    static int colour(int color, boolean materialYou, boolean markColors) {
        if (materialYou) color = MaterialYouTheme.tabBarSelected(color);
        if (markColors) color = MarkColors.tabBarSelected(color);
        return color;
    }
}
