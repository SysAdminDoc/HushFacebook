/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode

/**
 * Keeps Facebook from telling the sender that you've read a chat that opens inside Facebook. See
 * ReadReceiptAnchors.kt for where the hook goes, and the extension's ReadReceipts for when.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val hideReadReceiptsPatch = patch(
    // The README table check reads this literal; READ_RECEIPTS_PATCH carries the same text for the messages.
    name = "Hide read receipts",
    description = "People you chat with in chats that open inside Facebook don't see that you've read their " +
        "messages, so you can read now and reply later. Replying may still show you've read it. Starts off. Turn " +
        "it on in Hushfacebook settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        hideReadReceipts()
        enableStatus("readReceipts")
    }
}

/** Puts the hook in Mailbox's one mark-read method. */
internal fun BytecodePatchContext.hideReadReceipts() {
    val found = mutableListOf<Pair<String, String>>()
    // The mark-read holds its name.
    classDefForEachReferencing(strings = listOf(MAILBOX_MARK_READ)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filter(::isMailboxMarkRead).forEach { found += classDef.type to it.name }
    }
    val (owner, name) = found.singleOrNull()
        ?: throw PatchException("$READ_RECEIPTS_PATCH: expected one Mailbox mark-read holding \"$MAILBOX_MARK_READ\", found ${found.size}")
    mutableClassDefBy(owner).methods.single { it.name == name && isMailboxMarkRead(it) }.holdBackRead()
}
