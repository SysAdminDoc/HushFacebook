/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.finalizeBytecode

internal const val MARK_FDS = "Lapp/morphe/extension/facebook/theme/MarkColors;->fds(ILjava/lang/Object;)I"

/** The FDS tokens the extension's MarkColors recolours, by name. */
internal val MARK_TOKENS = listOf("NOTIFICATION_BADGE", "FB_WORDMARK", "FB_LOGO")

/** Which of [MARK_TOKENS] the token enum's [constants] lack. */
internal fun missingMarkTokens(constants: TokenConstants): List<String> =
    MARK_TOKENS - constants.fields.values.toSet()

/**
 * Colours of your own for the count badges on the tabs and the Facebook logo at the top of Home
 * (#263). Both are FDS tokens (NOTIFICATION_BADGE, FB_WORDMARK and FB_LOGO), so this rides the
 * themes' FDS colour resolvers: the extension answers Facebook's colour unchanged for every other
 * token, while a row is blank, and while Hushfacebook is paused.
 *
 * The selected tab (#297) isn't one token: Facebook picks TAB_BAR_ACTIVE_ICON, PRIMARY_ICON or
 * ACCENT by its own switches, and PRIMARY_ICON colours far more than the tab. So it takes Material
 * You's selected tab hook ([selectedTabColourHook]), which goes in once whichever of the two asks.
 *
 * In Morphe Manager's default selection: with every row blank, nothing changes.
 */
@Suppress("unused")
val markColorsPatch = patch(
    // The README table check reads this literal.
    name = "Badge and logo colors",
    description = "Paints the count badges on the tabs, the Facebook logo at the top of Home and the selected tab " +
        "in colors you pick. Nothing changes until you type a color. Set them in Hushfacebook settings > Appearance.",
) {
    category("Theme")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(facebookExtensionPatch)

    executeBytecode {
        requireStatusMethod("markColors")
        // The extension goes by the tokens' names, so a rename would leave the rows doing nothing.
        val missing = missingMarkTokens(fdsTokens().second)
        if (missing.isNotEmpty()) throw PatchException("The FDS token enum has no ${missing.joinToString()}")
        enableStatus("markColors")
    }

    // After every patch's execute, as the themes do, so these hooks go after theirs and a picked
    // colour is the last word for its token.
    finalizeBytecode {
        // First, so a build without the selected tab's reads fails before any hook goes in.
        selectedTabColourHook()()
        hookFdsColorsResolvers(target = MARK_FDS)
        fdsThemeResolver().hookColorReturns(tokenParameterIndex = 1, target = MARK_FDS)
    }
}
