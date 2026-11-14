/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postmenu

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Lets people pick which rows a post's three-dot menu shows (#475), such as Interested, Not
 * interested or Cast, so a stray tap can't land on one. The hook is postMenuSheetHookPatch's; this
 * turns on the extension's rule and the list under News feed in Hushfacebook settings, where the
 * kinds of row Facebook has shown on the phone are listed beside the common ones. It works by each
 * row's kind, never its words, so it holds in every language.
 *
 * In the default selection, but it changes nothing until rows are picked.
 */
@Suppress("unused")
val postMenuItemsPatch = bytecodePatch(
    name = "Post menu items",
    description = "Lets you hide rows you never use from a post's three-dot menu, such as Interested, Not " +
        "interested or Cast, so you can't tap them by accident. Nothing is hidden until you pick. Choose them in " +
        "Hushfacebook settings > News feed.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch, postMenuSheetHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        postMenuSheetMissing?.let { throw PatchException(it) }
        enableStatus("postMenuItems")
    }
}
