/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 * Ported from https://github.com/SysAdminDoc/HushMessenger (its original photo and original video controls)
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

/**
 * Photos and videos sent from a chat that opens inside Facebook go out as the originals.
 * See OriginalChatMediaAnchors.kt for the three places that change, and the extension's
 * OriginalChatMedia for when they do.
 *
 * In Morphe Manager's default selection with the switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val originalChatMediaPatch = patch(
    // The README table check reads this literal; ORIGINAL_MEDIA_PATCH carries the same text for the messages.
    name = "Send chat photos and videos at original quality",
    description = "Photos and videos you send from chats that open inside Facebook go out as the originals " +
        "instead of smaller copies, so they arrive sharp. Very large files are still shrunk. Starts off. Turn it " +
        "on in Hushfacebook settings > Chats and Messenger.",
) {
    category("Chats")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("originalChatMedia")
        val anchors = findOriginalMediaAnchors()
        hookOriginalMedia(anchors)
        enableStatus("originalChatMedia")
    }
}
