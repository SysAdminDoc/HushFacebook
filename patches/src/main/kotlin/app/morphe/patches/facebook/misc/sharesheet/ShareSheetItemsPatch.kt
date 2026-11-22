/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharesheet

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

/**
 * Lets people pick which items Facebook's share sheet shows. The hook is shareSheetHookPatch's;
 * this turns on the extension's rule and the list under Links in Hushfacebook settings, where the
 * item types Facebook has offered on the phone are listed beside the common ones.
 *
 * The idea is icysymmetra/tiktok-patches-for-morphe's Share sheet modification, which does the same
 * for TikTok's share sheet.
 *
 * Its group switch, beside the list, takes away the share sheet's Send to group button and its
 * new-group entries (#104), the way HushGram's Hide group buttons on the share sheet does for
 * Instagram. Those hooks are shareSheetGroupsPatch's; see ShareSheetGroupsPatch.kt. Its people switch takes away
 * the row of suggested people to send to (#169), shareSheetPeoplePatch's; see ShareSheetPeoplePatch.kt.
 *
 * In the default selection, but it changes nothing until it's turned on in Hushfacebook settings:
 * nobody has seen it on a signed-in account yet.
 */
@Suppress("unused")
val shareSheetItemsPatch = patch(
    name = "Share sheet items",
    description = "Lets you hide items you never use from Facebook's share sheet, such as WhatsApp, Meta AI or " +
        "Copy link, so the ones you want are easier to reach. Nothing is hidden until you pick. Choose them in " +
        "Hushfacebook settings > Links and sharing, where two switches that start off also take away the buttons that send to " +
        "the people you picked as a new group chat and the row of people Facebook suggests sending to.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch, shareSheetHookPatch, shareSheetGroupsPatch, shareSheetPeoplePatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        enableStatus("shareSheetItems")
    }
}
