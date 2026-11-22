/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.screenshots

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
 * Lets screenshots and screen recordings show the screens Facebook marks secure. See
 * AllowScreenshotsAnchors.kt for what it changes, and the extension's Screenshots for when.
 *
 * In the default selection with its switch off: a secure screen is one Facebook means to keep out
 * of screenshots, so showing it is a choice to make.
 */
@Suppress("unused")
val allowScreenshotsPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Allow screenshots",
    description = "Lets you take screenshots and screen recordings on the pages where Facebook blocks them, so " +
        "you can keep a copy of what's on screen. Starts off. Turn it on in Hushfacebook settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val owners = mutableSetOf<String>()
        // A flags call names Window, a flags write LayoutParams.
        classDefForEachReferencing(types = listOf(WINDOW, LAYOUT_PARAMS)) { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
            if (classDef.methods.any(::setsWindowFlags)) owners += classDef.type
        }
        val sent = owners.sumOf { type -> mutableClassDefByOrNull(type)?.methods?.sumOf { it.allowScreenshots() } ?: 0 }
        if (sent == 0) throw PatchException("$PATCH: found no window flag call or layout flags write outside the extension")
        enableStatus("allowScreenshots")
    }
}
