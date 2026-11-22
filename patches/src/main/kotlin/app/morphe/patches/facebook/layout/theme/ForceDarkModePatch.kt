/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

/**
 * Facebook's dark mode controller answers dark while the switch is on (#64). Facebook's Settings
 * page comes from its server, and on some tablets it has no Dark mode row. The controller's
 * answer already goes through the extension for the two themes ([hookDarkModeAnswer]), and the
 * extension turns a light answer dark there, before it keeps it, so the themes see dark mode on.
 * Meta's end-to-end tests force dark mode in the same method: it answers dark before it reads the
 * setting when their switch is set. With a theme in the build too, the one hook serves both.
 */
@Suppress("unused")
val forceDarkModePatch = patch(
    // The README table check reads this literal.
    name = "Force dark mode",
    description = "Keeps Facebook in dark mode whatever its own setting says. Useful on tablets, where " +
        "Facebook's settings have no Dark mode. Starts off. Turn it on in Hushfacebook settings > Appearance, " +
        "then restart Facebook.",
) {
    category("Theme")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        hookDarkModeAnswer()
        enableStatus("forceDarkMode")
    }
}
