/*
 * Modified for Hushfacebook (Facebook), 2026: Facebook only, with the extension class in
 * Hushfacebook's own extension and SettingsStatus told the patch is in. The manifest is read and
 * written through small helpers the fixture test also calls. Every class but the extension class
 * itself has its reads swapped, so Hushfacebook's own diagnostic report names the real version
 * too. The register fallback for an iget destination past v15 is gone, since iget only addresses
 * v0 to v15, and the reads are walked from the end of each method instead of matched by a
 * fingerprint.
 *
 * Forked from:
 * https://github.com/MorpheApp/morphe-patches/blob/1bcd0bcedf1238e71b0bd8815da2c10df7ef5bd2/patches/src/main/kotlin/app/morphe/patches/all/misc/updates/DisablePlayStoreUpdatesPatch.kt
 *
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2470
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/patches/all/misc/versioncode/ChangeVersionCodePatch.kt
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.patches.facebook.updates.playstore

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.executeResources
import app.morphe.util.finalizeBytecode
import app.morphe.util.finalizeResources
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val EXTENSION_CLASS = "$EXTENSION_PACKAGE/updates/DisablePlayStoreUpdatesPatch;"
internal const val VERSION_CODE_FIELD = "Landroid/content/pm/PackageInfo;->versionCode:I"
internal const val LONG_VERSION_CODE = "Landroid/content/pm/PackageInfo;->getLongVersionCode()J"
internal const val GET_VERSION_CODE = "$EXTENSION_CLASS->getVersionCode(Landroid/content/pm/PackageInfo;)I"
internal const val GET_VERSION_CODE_LONG = "$EXTENSION_CLASS->getVersionCodeLong(Landroid/content/pm/PackageInfo;)J"

private var originalVersionCode: Int = 0

/** The manifest's root element. */
private fun Document.manifest(): Element = getElementsByTagName("manifest").item(0) as Element

/** The version code the manifest declares. */
internal fun Document.versionCode(): Int = manifest().getAttribute("android:versionCode").toInt()

/** Gives the manifest the highest version code Android allows. */
internal fun Document.raiseVersionCode() {
    //  Max allowed by Play Store is 2100000000, but Android allows max int value.
    manifest().setAttribute("android:versionCode", Int.MAX_VALUE.toString())
}

@Suppress("unused")
private val disablePlayStoreUpdatesResourcePatch = patch {
    executeResources {
        document("AndroidManifest.xml").use { document ->
            originalVersionCode = document.versionCode()
        }
    }

    finalizeResources {
        document("AndroidManifest.xml").use { document ->
            document.raiseVersionCode()
        }
    }
}

/** Whether [instruction] reads PackageInfo's int version code. */
private fun readsVersionCode(instruction: Instruction) =
    instruction.opcode == Opcode.IGET && (instruction as ReferenceInstruction).reference.toString() == VERSION_CODE_FIELD

/** Whether [instruction] asks PackageInfo for its long version code. */
private fun asksLongVersionCode(instruction: Instruction) =
    instruction.opcode == Opcode.INVOKE_VIRTUAL && (instruction as ReferenceInstruction).reference.toString() == LONG_VERSION_CODE

/** Whether any method of [classDef] reads a version code the patch swaps. */
internal fun readsVersionCode(classDef: ClassDef): Boolean = classDef.methods.any { method ->
    method.implementation?.instructions?.any { readsVersionCode(it) || asksLongVersionCode(it) } == true
}

/**
 * Sends every version code read in this method through the extension, which answers the real one
 * where Android would give the raised one. Returns how many reads it swapped.
 */
internal fun MutableMethod.readRealVersionCode(): Int {
    val instructions = implementation?.instructions?.toList() ?: return 0
    var swapped = 0
    // From the end, so a move-result put in doesn't shift an index still to come.
    for (index in instructions.indices.reversed()) {
        val instruction = instructions[index]
        if (readsVersionCode(instruction)) {
            val read = instruction as TwoRegisterInstruction
            val packageInfoRegister = read.registerB
            addInstruction(index + 1, "move-result v${read.registerA}")
            replaceInstruction(
                index,
                "invoke-static/range { v$packageInfoRegister .. v$packageInfoRegister }, $GET_VERSION_CODE",
            )
            swapped++
        } else if (asksLongVersionCode(instruction)) {
            // Replace long version code, which is a combination of
            // regular version code and versionCodeMajor.
            if (instructions.getOrNull(index + 1)?.opcode != Opcode.MOVE_RESULT_WIDE) continue
            val register = (instruction as FiveRegisterInstruction).registerC
            replaceInstruction(index, "invoke-static/range { v$register .. v$register }, $GET_VERSION_CODE_LONG")
            swapped++
        }
    }
    return swapped
}

@Suppress("unused")
val disablePlayStoreUpdatesPatch = patch(
    name = "Disable Play Store updates",
    description = "Stops Google Play offering Facebook updates, which a patched app can't take anyway. It isn't " +
        "selected by default because once it's installed, a build without it can't install over it. It doesn't " +
        "work on a Root Mount install. Turn on Expert mode in Manager to pick it.",
    default = false,
) {
    category("Fixes")
    dependsOn(settingsPatch, facebookExtensionPatch, disablePlayStoreUpdatesResourcePatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("playStoreUpdates")
        val stub = classDefByOrNull(EXTENSION_CLASS)?.methods?.singleOrNull(::isOriginalVersionCodeStub)
        if (stub == null) throw PatchException("Disable Play Store updates: $EXTENSION_CLASS has no originalVersionCode()I")
        enableStatus("playStoreUpdates")
    }

    finalizeBytecode {
        mutableClassDefBy(EXTENSION_CLASS).methods.single(::isOriginalVersionCodeStub).returnEarly(originalVersionCode)

        // Every class but the extension's own, Hushfacebook's other extension classes included.
        val readers = mutableListOf<ClassDef>()
        classDefForEach { if (it.type != EXTENSION_CLASS && readsVersionCode(it)) readers += it }
        for (reader in readers) {
            mutableClassDefBy(reader.type).methods.forEach { it.readRealVersionCode() }
        }
    }
}

private fun isOriginalVersionCodeStub(method: Method) =
    method.name == "originalVersionCode" && method.returnType == "I" && method.parameterTypes.isEmpty()
