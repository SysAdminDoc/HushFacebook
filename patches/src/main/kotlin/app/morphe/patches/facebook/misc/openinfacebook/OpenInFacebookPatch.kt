/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.openinfacebook

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PATCH = "Open in Facebook from the share menu"

internal const val OPEN_IN_FACEBOOK_CLASS = "$EXTENSION_PACKAGE/misc/OpenInFacebook;"

/** The extension's share target as the manifest names it. */
internal val OPEN_IN_FACEBOOK_ACTIVITY = OPEN_IN_FACEBOOK_CLASS.removePrefix("L").removeSuffix(";").replace('/', '.')

/** Facebook's handler for the links it opens, which the share target passes the link to. */
internal const val URI_HANDLER = "com.facebook.katana.IntentUriHandler"

/** What the share menu shows under Facebook's icon. */
internal const val OPEN_IN_FACEBOOK_LABEL = "Open in Facebook"

private fun Document.all(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).mapNotNull { list.item(it) as? Element } }

private fun Element.child(tag: String, vararg attributes: Pair<String, String>): Element =
    ownerDocument.createElement(tag).also { child ->
        attributes.forEach { (name, value) -> child.setAttribute(name, value) }
        appendChild(child)
    }

/**
 * Declares the share target: an exported screen with no window of its own that takes shared text
 * and hands the first Facebook address in it to [URI_HANDLER]. A manifest without that handler, or
 * without an application, is refused, since the screen would have nowhere to send the link. A
 * second run leaves the one declaration it made.
 */
internal fun Document.declareOpenInFacebook() {
    val application = all("application").singleOrNull() ?: throw PatchException("$PATCH: the manifest has no single <application>")
    val activities = all("activity")
    if (activities.none { it.getAttribute("android:name") == URI_HANDLER }) {
        throw PatchException("$PATCH: the manifest declares no $URI_HANDLER to open links with")
    }
    if (activities.any { it.getAttribute("android:name") == OPEN_IN_FACEBOOK_ACTIVITY }) return

    val activity = application.child(
        "activity",
        "android:name" to OPEN_IN_FACEBOOK_ACTIVITY,
        "android:exported" to "true",
        "android:label" to OPEN_IN_FACEBOOK_LABEL,
        "android:theme" to "@android:style/Theme.Translucent.NoTitleBar",
        "android:excludeFromRecents" to "true",
        "android:noHistory" to "true",
    )
    val filter = activity.child("intent-filter")
    filter.child("action", "android:name" to "android.intent.action.SEND")
    filter.child("category", "android:name" to "android.intent.category.DEFAULT")
    filter.child("data", "android:mimeType" to "text/plain")
}

private val openInFacebookResourcePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { it.declareOpenInFacebook() }
    }
}

/**
 * Messenger keeps a facebook.com link it's sent in its own browser, and Android won't verify a
 * re-signed Facebook for those addresses, so nothing sends them here by itself (#111). Sharing the
 * page or the link to Open in Facebook does.
 */
@Suppress("unused")
val openInFacebookPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Open in Facebook from the share menu",
    description = "Adds Open in Facebook to Android's share menu. Share a Facebook link or page to it from " +
        "Messenger's browser or any other app, and it opens here instead of in that app. Works as soon as you " +
        "patch it in, with no switch.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch)
    dependsOn(openInFacebookResourcePatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        classDefByOrNull(OPEN_IN_FACEBOOK_CLASS) ?: throw PatchException("$PATCH: the extension has no $OPEN_IN_FACEBOOK_CLASS")
        enableStatus("openInFacebook")
    }
}
