/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.headertext

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.appicons.dressLogoAfter
import app.morphe.patches.facebook.misc.appicons.logoDressSite
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.CREATE_WORDMARK_VIEW
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

internal const val PATCH = "Home header text"

/** The extension call that puts the saved words in place of the wordmark Facebook just loaded. */
internal const val DRESS_HEADER = "$EXTENSION_PACKAGE/misc/HeaderText;->dress(Landroid/view/View;)V"

/**
 * Your own words at the top of Home in place of Facebook's wordmark (#230). The header's builder
 * loads the stock wordmark right after the logo gets its touch listener, the same site Unlock app
 * icons dresses, and the extension's HeaderText takes the logo after that load. Blank text keeps the
 * wordmark, so the patch sits in the default selection.
 */
@Suppress("unused")
val homeHeaderTextPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Home header text",
    description = "Shows your own words or picture at the top of Home instead of the Facebook logo. Words take " +
        "Facebook's own colors for light and dark mode. Type them in Hushfacebook settings > Appearance > Home " +
        "header text, or pick a picture under Home header picture. Blank keeps Facebook's logo.",
) {
    category("Theme")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val bar = mutableClassDefByOrNull(WORDMARK_NAVIGATION_BAR)
            ?: throw PatchException("$PATCH: this Facebook has no $WORDMARK_NAVIGATION_BAR, the home feed's top bar")
        val builders = bar.methods.filter { holdsString(it, CREATE_WORDMARK_VIEW) }
        val builder = builders.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one method of $WORDMARK_NAVIGATION_BAR holding \"$CREATE_WORDMARK_VIEW\", found ${builders.size}",
        )
        builder.dressLogoAfter(logoDressSite(builder, PATCH), DRESS_HEADER)
        enableStatus("headerText")
    }
}
