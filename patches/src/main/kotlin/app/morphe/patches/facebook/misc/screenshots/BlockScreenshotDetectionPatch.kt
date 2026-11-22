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
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.extendsClass

/**
 * Keeps Facebook from learning that you took a screenshot or are recording the screen. See
 * ScreenshotDetectionAnchors.kt for where each hook goes, and the extension's ScreenshotDetection
 * for when.
 *
 * The photo library observer is the one every screenshot detector shares, so without it the patch
 * stops. The Android 14 and 15 calls are each sent on their own, and a build without them is
 * noted in the patch log.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val blockScreenshotDetectionPatch = patch(
    // The README table check reads this literal; DETECTION_PATCH carries the same text for the messages.
    name = "Block screenshot detection",
    description = "Stops Facebook noticing when you take a screenshot or record the screen, in the feed, Reels, " +
        "chats and everywhere else, so it can't react to it. Starts off. Turn it on in Hushfacebook settings > " +
        "Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val observer = classDefByOrNull(SCREENSHOT_OBSERVER)?.methods?.filter(::isObserverChange).orEmpty()
        if (observer.size != 1) {
            throw PatchException("$DETECTION_PATCH: expected one onChange(boolean, Uri) in $SCREENSHOT_OBSERVER, found ${observer.size}")
        }
        mutableClassDefBy(SCREENSHOT_OBSERVER).methods.single(::isObserverChange).ignoreChangesWhenBlocked()

        val activities = HashMap<String, Boolean>()
        val isActivity = { type: String -> activities.getOrPut(type) { extendsClass(type, ACTIVITY) } }
        val owners = mutableSetOf<String>()
        // A detection call names its method.
        classDefForEachReferencing(names = DetectionCall.entries.map { it.method }) { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
            if (classDef.methods.any { method ->
                    method.implementation?.instructions?.any { detectionCall(it, isActivity) != null } == true
                }
            ) {
                owners += classDef.type
            }
        }
        val sent = owners.flatMap { type -> mutableClassDefBy(type).methods.flatMap { it.sendDetectionCalls(isActivity) } }
        for (kind in DetectionCall.entries) {
            if (kind !in sent) patchLog.warning("$DETECTION_PATCH: no ${kind.method} call to send in this build")
        }
        enableStatus("screenshotDetection")
    }
}
