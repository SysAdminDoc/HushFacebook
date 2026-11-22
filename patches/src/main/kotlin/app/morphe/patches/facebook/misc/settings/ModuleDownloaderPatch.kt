/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val DOWNLOADER = "Module downloader fix"
internal const val MODULE_DOWNLOADS = "$EXTENSION_PACKAGE/settings/ModuleDownloads;"
internal const val FROM_PLAY = "$MODULE_DOWNLOADS->fromPlay(Z)Z"

/** The download manager's error when it has no downloader left, which only its choosing method writes. */
internal const val NO_DOWNLOADER = "VoltronDownloadManagerImpl: No downloader available. "

/** The choice of Google Play in [method]: the test at [index] reads [register], true for Play. */
internal class PlayChoiceSite(val method: Method, val index: Int, val register: Int)

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Where the download manager's choosing method (the one that writes [NO_DOWNLOADER]) settles on
 * Google Play, when [method] is it. Facebook 583 reads two flags off one settings object, a server
 * flag and whether Play installed the app, and tests each straight away. Both tests jump to the
 * same read of an enum constant, Facebook's own downloader, and when both flags are true the code
 * runs on into a read of another constant of that enum, Google Play. The site is the second test:
 * it only runs once the first flag was true, so its register is exactly "use Google Play". Null for
 * any other method.
 */
internal fun playChoiceSite(method: Method): PlayChoiceSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    if (code.none { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == NO_DOWNLOADER }) return null
    val address = IntArray(code.size + 1)
    for (at in code.indices) address[at + 1] = address[at] + code[at].codeUnits
    fun target(at: Int) = address.indexOf(address[at] + (code[at] as OffsetInstruction).codeOffset)
    fun flag(at: Int): FieldReference? = code.getOrNull(at)?.takeIf { it.opcode == Opcode.IGET_BOOLEAN }?.field()
    fun tests(at: Int, read: Int) = code.getOrNull(at)?.opcode == Opcode.IF_EQZ &&
        (code[at] as OneRegisterInstruction).registerA == (code[read] as TwoRegisterInstruction).registerA
    fun constant(at: Int): FieldReference? = code.getOrNull(at)
        ?.takeIf { it.opcode == Opcode.SGET_OBJECT }?.field()?.takeIf { it.definingClass == it.type }
    val sites = (0 until code.size - 4).filter { at ->
        val first = flag(at) ?: return@filter false
        val second = flag(at + 2) ?: return@filter false
        if (first.definingClass == method.definingClass || first.definingClass != second.definingClass || first.name == second.name) {
            return@filter false
        }
        if ((code[at] as TwoRegisterInstruction).registerB != (code[at + 2] as TwoRegisterInstruction).registerB) return@filter false
        if (!tests(at + 1, at) || !tests(at + 3, at + 2)) return@filter false
        val own = target(at + 1)
        if (own < 0 || own != target(at + 3)) return@filter false
        val sideload = constant(own) ?: return@filter false
        val play = constant(at + 4) ?: return@filter false
        play.type == sideload.type && play.name != sideload.name
    }
    val at = sites.singleOrNull() ?: return null
    return PlayChoiceSite(method, at + 3, (code[at + 2] as TwoRegisterInstruction).registerA)
}

/** The one choice of Google Play. Throws, naming what's missing, before anything changes. */
internal fun BytecodePatchContext.findPlayChoiceSite(): PlayChoiceSite {
    val sites = classDefByStrings(NO_DOWNLOADER, StringComparisonType.EQUALS).flatMap { classDef ->
        classDef.methods.mapNotNull(::playChoiceSite)
    }
    return sites.singleOrNull() ?: throw PatchException("$DOWNLOADER: expected one choice of Google Play in the module download manager, found ${sites.size}")
}

/**
 * Hands the choice to the extension, which answers false, so the test jumps to Facebook's own
 * downloader. By range, so any register fits. A jump straight to the test would skip the call, so
 * a test with one is refused.
 */
internal fun BytecodePatchContext.applyPlayChoice(site: PlayChoiceSite) {
    val method = mutableClassDefBy(site.method.definingClass).findMutableMethodOf(site.method)
    if ((method.implementation!!.instructions[site.index] as BuilderInstruction).location.labels.isNotEmpty()) {
        throw PatchException("$DOWNLOADER: ${method.definingClass}->${method.name} jumps straight to its Google Play test")
    }
    method.addInstructions(
        site.index,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $FROM_PLAY
            move-result v${site.register}
        """,
    )
}

/**
 * Fetches app modules, such as instant games, with Facebook's own downloader instead of Google
 * Play's split install (#168). Facebook uses Play when Play installed the app and a server flag
 * allows it, but Play only serves the splits of the app it published, signed with Meta's key, so a
 * patched build gets "module unavailable" back and a game's download screen closes at once.
 * Facebook's own downloader works for any build. No switch and no name of its own: Hushfacebook
 * settings brings it into every build. A build missing the anchor goes on without it, with a
 * warning: a throw here would stop Hushfacebook settings, and every patch with it.
 */
internal val moduleDownloaderPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        // Dependencies run before Settings, and its refusal has to be what explains an opaque
        // Android 9 build, so nothing is looked up in one.
        if (compressedCodeRefusal(classDefByOrNull(FACEBOOK_APPLICATION) != null, classDefByOrNull(MAIN_TAB_ACTIVITY)) != null) {
            return@executeBytecode
        }
        try {
            applyPlayChoice(findPlayChoiceSite())
        } catch (missing: Exception) {
            val why = if (missing is PatchException) missing.message else "$DOWNLOADER: ${missing::class.java.name}: ${missing.message}"
            patchLog.warning("$why. Games and other app modules may not download when Google Play installed Facebook.")
        }
    }
}
