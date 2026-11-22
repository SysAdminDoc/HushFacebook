/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/resignedtrust/RestoreTrustPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.resignedtrust

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.util.executeBytecode

private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"

private const val ORIGINAL_SIGNERS = "Lapp/morphe/extension/facebook/misc/FacebookSignature;->" +
    "originalSigners(Landroid/content/pm/PackageInfo;)Ljava/util/List;"

@Suppress("unused")
val restoreTrustPatch = patch(
    name = "Restore screens on re-signed builds",
    description = "Fixes profiles, photos, posts and some Settings pages that won't open on a patched Facebook, " +
        "lets a Messenger or Facebook Lite patched in the same Manager sign in through it, and opens Instagram " +
        "and Threads links in a patched Instagram or Threads. Root Mount " +
        "installs don't need it. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(facebookExtensionPatch)

    executeBytecode {
        val method = PackageSignersFingerprint.method
        val owner = mutableClassDefBy(method.definingClass)

        // The package whose signers the method reads. The class holds exactly one.
        val packageInfoFields = owner.fields.filter { it.type.toString() == PACKAGE_INFO }
        check(packageInfoFields.size == 1) {
            "Expected 1 PackageInfo field on ${method.definingClass}, found ${packageInfoFields.size}"
        }
        val packageInfo = packageInfoFields.single().name

        // The result holds the signer list and two flags. Its constructor states that shape.
        val signers = method.returnType.toString()
        val constructor = mutableClassDefBy(signers).methods.singleOrNull {
            it.name == "<init>" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;", "Z", "Z")
        }
        check(constructor != null) { "$signers has no (List, boolean, boolean) constructor" }

        method.answerOriginalSigners(packageInfo, signers)

        // The Debug logging test of a same-key Messenger's link. A build without its anchors, or
        // where anything else about the test fails, still gets the fix, and the settings screen then
        // shows no test row: patched() is filled last, so a fill that stops part way runs nothing.
        try {
            fillMessengerLinkStubs(findMessengerLinkAnchors())
        } catch (missing: Exception) {
            // A missing anchor is a PatchException with its reason; anything else is a bug, named as one.
            val why = if (missing is PatchException) missing.message else "${missing::class.java.name}: ${missing.message}"
            patchLog.warning("Restore screens on re-signed builds: $why. The Messenger link test is left out.")
        }

        // Instagram and Threads links Facebook's family scope drops for a patched app's signer (#287).
        // A build whose scope filter has another shape keeps the rest of the fix.
        try {
            FamilyScopeFilterFingerprint.method.handOffDroppedFamilyLinks()
        } catch (missing: Exception) {
            val why = if (missing is PatchException) missing.message else "${missing::class.java.name}: ${missing.message}"
            patchLog.warning("Restore screens on re-signed builds: $why. Instagram and Threads links stay with Facebook's scope.")
        }

        enableStatus("restoreTrust")
    }
}

/**
 * For the running app, under Facebook's name or a clone's, answer with the original certificate and
 * skip the body. For any other package, the extension answers null and the body runs as before. The
 * two flags are false, as the body sets them for a single signer.
 *
 * The injection is at index 0, where no local is live yet, so v0 to v2 are free once the method is
 * known to have three locals. `iget-object` takes 4-bit registers, so `this` is copied down into v0
 * first: in a method with more than sixteen registers `p0` sits above v15, and the patcher's smali
 * compiler leaves out an instruction whose register doesn't fit, without a word.
 */
internal fun MutableMethod.answerOriginalSigners(packageInfo: String, signers: String) {
    requireLocals("Restore screens on re-signed builds", 3)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $definingClass->$packageInfo:$PACKAGE_INFO
            invoke-static { v0 }, $ORIGINAL_SIGNERS
            move-result-object v1
            if-eqz v1, :original
            new-instance v0, $signers
            const/4 v2, 0x0
            invoke-direct { v0, v1, v2, v2 }, $signers-><init>(Ljava/util/List;ZZ)V
            return-object v0
        """,
        ExternalLabel("original", getInstruction(0)),
    )
}
