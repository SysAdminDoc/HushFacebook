/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postmenu

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Lets people pick which rows a post's or a reel's three-dot menu shows (#475), such as Interested,
 * Not interested, Cast or Clear mode, so a stray tap can't land on one. The post menu's hook is
 * postMenuSheetHookPatch's, and this adds the reel More sheet's (ReelMenuSheetAnchors.kt). It turns
 * on the extension's rule and the list under News feed in Hushfacebook settings, where the kinds of
 * row Facebook has shown on the phone are listed beside the common ones. It works by each row's
 * kind or id, never its words, so it holds in every language.
 *
 * A build where the reel sheet moved goes on with the post menu alone. In the default selection,
 * but it changes nothing until rows are picked.
 */
@Suppress("unused")
val postMenuItemsPatch = bytecodePatch(
    name = "Post menu items",
    description = "Lets you hide rows you never use from the three-dot menu of posts and reels, such as " +
        "Interested, Not interested or Cast, so you can't tap them by accident. Nothing is hidden until you pick. " +
        "Choose them in Hushfacebook settings > News feed.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch, postMenuSheetHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        postMenuSheetMissing?.let { throw PatchException(it) }
        try {
            hookReelMenuSheet(findReelMenuSheet())
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. A reel's More sheet keeps all its rows.")
        }
        enableStatus("postMenuItems")
    }
}
