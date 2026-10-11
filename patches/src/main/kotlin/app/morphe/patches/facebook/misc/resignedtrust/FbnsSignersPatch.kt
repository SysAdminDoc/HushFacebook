/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Ported back from SysAdminDoc/HushThreads (FbnsSignersPatch.kt, HushThreads #6), where the push
 * process's signer read was found by measuring a re-signed Threads.
 */
package app.morphe.patches.facebook.misc.resignedtrust

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.MAIN_TAB_ACTIVITY
import app.morphe.patches.facebook.misc.settings.compressedCodeRefusal
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"

internal const val FBNS_SIGNERS = "Lapp/morphe/extension/facebook/misc/FacebookSignature;->" +
    "fbnsSigners(Landroid/content/pm/PackageInfo;[Landroid/content/pm/Signature;)[Landroid/content/pm/Signature;"

private const val FBNS_FIX = "Push service signer fix"

/**
 * Part of every build, with no name to deselect it by: Hushfacebook settings, which every other
 * patch depends on, depends on this one. Re-signing alone is what makes Facebook's push service
 * reject Facebook, whatever else is patched in, so no selection may leave it out (#112).
 *
 * A build without the check goes on without the fix and says so in the patch log, the way the other
 * hidden patches of the settings patch do: a throw here would stop every patch with it.
 */
internal val fbnsSignersPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        // The settings patch's refusal has to be what explains an Android 9 build, so nothing is
        // looked up in one.
        if (compressedCodeRefusal(classDefByOrNull(FACEBOOK_APPLICATION) != null, classDefByOrNull(MAIN_TAB_ACTIVITY)) != null) {
            return@execute
        }
        val method = try {
            FbnsPackageCheckFingerprint.method
        } catch (missing: Exception) {
            patchLog.warning("$FBNS_FIX: Facebook's push service package check wasn't found. A re-signed build may keep restarting its push service.")
            return@execute
        }
        try {
            method.routeFbnsSigners()
        } catch (refused: PatchException) {
            patchLog.warning("${refused.message} A re-signed build may keep restarting its push service.")
        }
    }
}

/**
 * Facebook's push service (FBNS, in the `:notification` process) checks the package it hands pushes
 * to against Meta's certificates, reading `PackageInfo.signatures` itself rather than through the
 * signers method [restoreTrustPatch] answers for. `FbnsServiceDelegateV2`'s start runs it on
 * Facebook's own package with no shortcut, so a re-signed build fails it: the delegate drops the
 * registration, stops the service and says so, the main process starts it again, and each round
 * leaves threads running (HushThreads #6 counted about 8,000 in 46 seconds on Threads, which shares
 * this code). The array that read gives goes through the extension, which answers Facebook's
 * original certificate for this app and the system's answer for any other package.
 *
 * The call goes straight after the read and names its two registers. `invoke-static` takes 4-bit
 * registers, so both must be v15 or lower, and the read must not overwrite the package it read
 * from, which the call still needs.
 */
internal fun MutableMethod.routeFbnsSigners() {
    val (index, info, signatures) = fbnsSignersRead()
    addInstructions(
        index + 1,
        """
            invoke-static { v$info, v$signatures }, $FBNS_SIGNERS
            move-result-object v$signatures
        """,
    )
}

/** The check's one read of `PackageInfo.signatures`: its index, the package register and the signatures register. */
internal fun MutableMethod.fbnsSignersRead(): Triple<Int, Int, Int> {
    val reads = implementation!!.instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == PACKAGE_INFO && it.name == "signatures"
            }
    }
    val (index, read) = reads.singleOrNull()
        ?: throw PatchException("$FBNS_FIX: expected one read of PackageInfo.signatures in the push service's package check, found ${reads.size}.")
    val signatures = (read as TwoRegisterInstruction).registerA
    val info = read.registerB
    if (signatures == info || signatures > 15 || info > 15) {
        throw PatchException("$FBNS_FIX: the package check reads signatures into v$signatures from v$info, which the call can't name.")
    }
    return Triple(index, info, signatures)
}
