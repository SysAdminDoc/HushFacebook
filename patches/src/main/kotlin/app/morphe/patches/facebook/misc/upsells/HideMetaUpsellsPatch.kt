/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.upsells

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.misc.sharesheet.shareSheetHookPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

/**
 * Hide Menu promotions takes Meta's other products out of the Menu; this takes the pushes for them
 * out of everywhere else, each behind its own switch, all off until they're turned on: the Edits
 * button and badge in the Reels composer's header and the Edits pill under feed videos, the Threads
 * cross-posting onboarding in the composer, the Threads and Muse buttons in the share sheet, the Meta
 * Verified offer sheet after you post and the label under some posts' headers, the avatar sticker
 * promotions in comments and Facebook's own promotion slots, and Meta AI's Imagine: the Imagine me
 * button under posts, the post composer's Imagine and Create story's Imagine tile, plus the other
 * Meta AI buttons under posts. Posting, the other ways to share and ordinary stickers are untouched.
 *
 * Every anchor is a kept class name, an enum constant's name or a literal (see
 * MetaUpsellAnchors.kt, ImagineAnchors.kt and ShareSheetAnchors.kt), and every one is required.
 * The share sheet's hook is shareSheetHookPatch, shared with Share sheet items.
 * The hooks ask the extension, which answers Facebook's own way until the settings are ready, while
 * paused, and whenever it fails.
 *
 * In the default selection, but it changes nothing until it's turned on in Hushfacebook settings:
 * nobody has seen it on a signed-in account yet.
 */
@Suppress("unused")
val hideMetaUpsellsPatch = patch(
    name = "Hide Meta upsells",
    description = "Hides Meta's pushes for its other products, such as Edits, Threads cross-posting, Meta " +
        "Verified, avatar stickers and Meta AI's Imagine, so you see fewer sales pitches. Starts off. Turn on " +
        "the parts you want in Hushfacebook settings > Meta's other products.",
) {
    category("Interface")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    dependsOn(shareSheetHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        hideMetaUpsells()
        enableStatus("metaUpsells")
    }
}
