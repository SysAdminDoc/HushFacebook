/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/disableaudiencenetwork/DisableAudienceNetworkPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the manifest edit sits behind a bytecode patch, so
 * the settings screen can say the patch is in.
 */
package app.morphe.patches.facebook.ads.audiencenetwork

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.handleTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.executeResources
import org.w3c.dom.Document
import org.w3c.dom.Element
import app.morphe.patches.facebook.misc.settings.settingsPatch

private const val PATCH = "Disable Audience Network"

/**
 * The bridge through which the Facebook app serves ads to *other* apps: an app embedding the
 * Audience Network SDK binds to the installed Facebook app to fetch and render its ads.
 */
internal val AUDIENCE_NETWORK_COMPONENTS = listOf(
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteService",
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity",
    "com.facebook.ads.internal.ipc.AudienceNetworkExportedActivity",
    "com.facebook.ads.AudienceNetworkActivity",
    "com.facebook.audiencenetwork.AudienceNetworkService",
)

private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

/**
 * Disabling is enough (nothing can bind to or start the component), and it keeps the manifest diff
 * readable and reversible, unlike removing the entries.
 */
private val disableAudienceNetworkResourcePatch = patch {
    executeResources {
        document("AndroidManifest.xml").use { document -> disableAudienceNetwork(document) }
    }
}

/**
 * Disables each Audience Network component the manifest declares. Each one is a way in on its
 * own, so a build that renamed some still gets the others shut, and the patch log names each one
 * left open. None found stops the patch.
 */
internal fun disableAudienceNetwork(manifest: Document) {
    val disabled = mutableSetOf<String>()
    COMPONENT_TAGS.forEach { tag ->
        val nodes = manifest.getElementsByTagName(tag)
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val name = element.getAttribute("android:name")
            if (name !in AUDIENCE_NETWORK_COMPONENTS) continue

            element.setAttribute("android:enabled", "false")
            // Or Facebook's AppComponentManager turns it straight back on (#168).
            keepOffByDefault(element)
            disabled += name
        }
    }

    handleTargets(PATCH, "Audience Network components", AUDIENCE_NETWORK_COMPONENTS) { name ->
        if (name in disabled) null else "$name isn't in the manifest"
    }
}

@Suppress("unused")
val disableAudienceNetworkPatch = patch(
    name = "Disable Audience Network",
    description = "Stops Facebook serving its ads inside other apps on your phone. Those apps show their own ads " +
        "or none, and their rewarded ads may fail. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(disableAudienceNetworkResourcePatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        // Facebook's startup task turns the ipc components on again, so its calls go through
        // the extension (see AudienceNetworkAnchors.kt).
        val setup = mutableClassDefByOrNull(REMOTE_APIS_SETUP)
            ?: throw PatchException("$PATCH: this Facebook has no $REMOTE_APIS_SETUP")
        val calls = setup.methods.flatMap { method -> componentStateCalls(method).map { method to it } }
        if (calls.isEmpty()) throw PatchException("$PATCH: $REMOTE_APIS_SETUP turns no component on")
        calls.forEach { (method, index) -> method.passComponentState(PATCH, index) }
        enableStatus("audienceNetwork")
    }
}
