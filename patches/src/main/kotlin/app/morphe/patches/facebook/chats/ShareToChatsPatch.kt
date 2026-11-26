/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.navigation.bottomtabbar.topBarButtonsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.executeResources
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val SHARE_TO_CHATS_PATCH = "Share to Facebook chats"

internal const val SHARE_TO_CHATS_CLASS = "$EXTENSION_PACKAGE/chats/ShareToChats;"

/** The extension's share target as the manifest names it. */
internal val SHARE_TO_CHATS_ACTIVITY = SHARE_TO_CHATS_CLASS.removePrefix("L").removeSuffix(";").replace('/', '.')

/** What the share menu shows under Facebook's icon. */
internal const val SHARE_TO_CHATS_LABEL = "Facebook chats"

/** The types each of the share target's actions takes. */
internal val SHARE_TO_CHATS_TYPES = linkedMapOf(
    "android.intent.action.SEND" to listOf("image/*", "video/*", "text/plain"),
    "android.intent.action.SEND_MULTIPLE" to listOf("image/*", "video/*"),
)

private fun Document.all(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).mapNotNull { list.item(it) as? Element } }

private fun Element.child(tag: String, vararg attributes: Pair<String, String>): Element =
    ownerDocument.createElement(tag).also { child ->
        attributes.forEach { (name, value) -> child.setAttribute(name, value) }
        appendChild(child)
    }

/**
 * Declares the share target: an exported screen with no window of its own that takes shared photos,
 * videos and text, turned off until the switch turns it on, so it stays out of the share menu for
 * everyone who chats in Messenger. A manifest without a single application is refused. A second run
 * leaves the one declaration it made.
 */
internal fun Document.declareShareToChats() {
    val application = all("application").singleOrNull() ?: throw PatchException("$SHARE_TO_CHATS_PATCH: the manifest has no single <application>")
    if (all("activity").any { it.getAttribute("android:name") == SHARE_TO_CHATS_ACTIVITY }) return

    val activity = application.child(
        "activity",
        "android:name" to SHARE_TO_CHATS_ACTIVITY,
        "android:exported" to "true",
        "android:enabled" to "false",
        "android:label" to SHARE_TO_CHATS_LABEL,
        "android:theme" to "@android:style/Theme.Translucent.NoTitleBar",
        "android:excludeFromRecents" to "true",
    )
    for ((action, types) in SHARE_TO_CHATS_TYPES) {
        val filter = activity.child("intent-filter")
        filter.child("action", "android:name" to action)
        filter.child("category", "android:name" to "android.intent.category.DEFAULT")
        types.forEach { filter.child("data", "android:mimeType" to it) }
    }
}

private val shareToChatsResourcePatch = patch {
    executeResources {
        document("AndroidManifest.xml").use { it.declareShareToChats() }
    }
}

/**
 * With chats inside Facebook, Android's share menu has no way into them: Facebook declares
 * Messenger's share screens, but their code never ships in the app (#568). The share target takes
 * what's shared into the gallery or onto the clipboard and opens Chats through the Messaging button,
 * which the top bar's hooks keep.
 */
@Suppress("unused")
val shareToChatsPatch = patch(
    // The README table check reads this literal; SHARE_TO_CHATS_PATCH carries the same text for the messages.
    name = "Share to Facebook chats",
    description = "Puts Facebook chats in Android's share menu, for when your chats are inside Facebook instead of " +
        "the Messenger app. Share a photo, video or link there and it's saved to your gallery or copied, then " +
        "Facebook opens on your chats so you can pick one and send it. Starts off. Turn it on in Hushfacebook " +
        "settings > Chats and Messenger.",
    default = true,
) {
    category("Chats")
    dependsOn(settingsPatch)
    dependsOn(shareToChatsResourcePatch, facebookExtensionPatch, topBarButtonsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        classDefByOrNull(SHARE_TO_CHATS_CLASS) ?: throw PatchException("$SHARE_TO_CHATS_PATCH: the extension has no $SHARE_TO_CHATS_CLASS")
        enableStatus("shareToChats")
    }
}
