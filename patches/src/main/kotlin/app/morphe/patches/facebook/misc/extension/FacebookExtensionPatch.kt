/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.patches.facebook.misc.extension

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch
import app.morphe.util.executeBytecode
import app.morphe.util.superclassChain

/** The application class the manifest names. A manifest name is never obfuscated. */
internal const val FACEBOOK_APPLICATION = "Lcom/facebook/katana/app/FacebookApplication;"

/** Hands the crash handler its switch at every start, signed out included (#528). */
internal const val CRASH_SWITCH_HAND_OVER = "Lapp/morphe/extension/facebook/settings/CrashFileSwitch;->handOver()V"

/**
 * Merges the shared and Facebook extensions and hands them the application context.
 *
 * <p>The context goes in at the start of the application's `onCreate()`, in whichever class of
 * its hierarchy declares one: every process Facebook starts builds this application, and the
 * settings, the pause switch and safe mode all read preferences through that context before any
 * hook asks for a switch.
 */
val facebookExtensionPatch = patch {
    dependsOn(sharedExtensionPatch(extensionName = "facebook", isYouTubeOrYouTubeMusic = false))

    executeBytecode {
        // The chain starts at the application class itself and stops at the first framework class
        // the APK does not carry.
        val declaring = superclassChain(FACEBOOK_APPLICATION)
            .mapNotNull { type -> mutableClassDefByOrNull(type) }
            .firstNotNullOfOrNull { classDef ->
                classDef.methods.singleOrNull { method ->
                    method.name == "onCreate" && method.parameterTypes.isEmpty() &&
                        method.returnType == "V" && method.implementation != null
                }
            } ?: throw PatchException(
                "Neither $FACEBOOK_APPLICATION nor a superclass in the APK declares onCreate()",
            )

        declaring.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V",
        )
        // The crash switch otherwise comes only with the first hook that reads a setting, and a
        // start on the login screen runs none, so the deaths it found were never filed (#528).
        declaring.addInstruction(1, "invoke-static {}, $CRASH_SWITCH_HAND_OVER")
    }
}
