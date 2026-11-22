/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.haptics

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
 * Holds back the haptics Facebook plays on its own taps and gestures. See HapticsAnchors.kt for
 * what it changes, and the extension's Haptics for when.
 *
 * In the default selection with its switch off: Facebook's haptics are a matter of taste, not
 * something it does to you.
 */
@Suppress("unused")
val turnOffHapticsPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Turn off haptics",
    description = "Stops the short vibrations Facebook plays when you tap and swipe in it, if you find them " +
        "distracting. Your keyboard and your phone's own vibrations stay. Starts off. Turn it on in Hushfacebook " +
        "settings > Appearance.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val owners = mutableSetOf<String>()
        // A haptic call names its method.
        classDefForEachReferencing(names = listOf("performHapticFeedback", "vibrate")) { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
            if (classDef.methods.any(::playsHaptic)) owners += classDef.type
        }
        var views = 0
        var vibrations = 0
        for (type in owners) {
            val classDef = mutableClassDefByOrNull(type) ?: throw PatchException("$PATCH: $type went missing")
            classDef.methods.forEach { method ->
                val (view, vibration) = method.turnOffHaptics()
                views += view
                vibrations += vibration
            }
        }
        if (views == 0) throw PatchException("$PATCH: found no View haptic call outside the extension")
        if (vibrations == 0) throw PatchException("$PATCH: found no vibrator effect call outside the extension")
        enableStatus("turnOffHaptics")
    }
}
