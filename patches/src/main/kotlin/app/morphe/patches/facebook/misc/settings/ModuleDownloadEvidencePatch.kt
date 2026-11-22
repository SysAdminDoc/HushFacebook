/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EVIDENCE = "Module download evidence"
internal const val MODULE_DOWNLOAD_EVIDENCE = "$EXTENSION_PACKAGE/settings/ModuleDownloadEvidence;"
internal const val MODULE_STEP = "$MODULE_DOWNLOAD_EVIDENCE->step(Ljava/lang/Object;)V"
internal const val MODULE_FAILED = "$MODULE_DOWNLOAD_EVIDENCE->failed(Ljava/lang/Object;)V"

/** The step runner's own log line when it's handed no steps, which only it writes. */
internal const val NO_STEPS = "executeAction is called when no actions exist %s"

/** What the module download screen keeps as its error when its task failed without one. */
internal const val FAILED_NO_EXCEPTION = "Failed, no exception"

/** Facebook's screen that downloads an app module, such as a game, before opening it. Kept by name. */
internal const val MODULE_DOWNLOAD_SCREEN = "Lcom/facebook/voltron/ui/impl/AppModuleDownloadActivity2;"

/** One place the evidence is read: [call] goes in at [index] of [method], handed [register]. */
internal class ModuleEvidenceSite(val method: Method, val index: Int, val register: Int, val call: String)

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** The first register a call reads, written as a range or not: 584 calls some of these by range. */
internal fun Instruction.firstRegister(): Int? = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> null
}

/**
 * The step runner's read of each step, when [method] is the runner: it asks its own iterator for a
 * step, writes [NO_STEPS] when there's none, and otherwise takes the next step from that iterator
 * and casts it straight away. The evidence goes in right after the cast, on the step's register.
 * Later iterators in the runner walk its listeners, not steps. Null for any other method.
 */
internal fun moduleStepSite(method: Method): ModuleEvidenceSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val written = code.indexOfFirst { it.string() == NO_STEPS }
    if (written < 0) return null
    fun Int.iteratorCall(name: String) =
        code[this].called()?.let { it.definingClass == "Ljava/util/Iterator;" && it.name == name } == true
    val asked = (0 until written).firstOrNull { it.iteratorCall("hasNext") } ?: return null
    val steps = code[asked].firstRegister() ?: return null
    val next = (written until code.size).firstOrNull { it.iteratorCall("next") } ?: return null
    if (code[next].firstRegister() != steps) return null
    val result = code.getOrNull(next + 1) ?: return null
    val cast = code.getOrNull(next + 2) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || cast.opcode != Opcode.CHECK_CAST) return null
    val register = (cast as OneRegisterInstruction).registerA
    if ((result as OneRegisterInstruction).registerA != register) return null
    return ModuleEvidenceSite(method, next + 3, register, MODULE_STEP)
}

/**
 * The module download screen's failure path, when [method] is the download callback that writes
 * [FAILED_NO_EXCEPTION]: the last read of the task's exception that starts a path of its own
 * (right after a return) before that string, in the screen's case (it casts to the screen before
 * the string). The evidence goes in right after the read, on the task's register, so every failure
 * passes it, with or without an exception. Null for any other method.
 */
internal fun moduleFailureSite(method: Method): ModuleEvidenceSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val written = code.indexOfFirst { it.string() == FAILED_NO_EXCEPTION }
    if (written < 0) return null
    val read = (1 until written - 1).lastOrNull { at ->
        val called = code[at].called()
        code[at - 1].opcode == Opcode.RETURN_VOID && code[at].opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
            called?.returnType == "Ljava/lang/Exception;" && called.parameterTypes.isEmpty() &&
            code[at + 1].opcode == Opcode.MOVE_RESULT_OBJECT
    } ?: return null
    val screens = (read until written).any { at ->
        code[at].opcode == Opcode.CHECK_CAST && ((code[at] as ReferenceInstruction).reference as TypeReference).type == MODULE_DOWNLOAD_SCREEN
    }
    if (!screens) return null
    val task = code[read].firstRegister() ?: return null
    if ((code[read + 1] as OneRegisterInstruction).registerA == task) return null
    return ModuleEvidenceSite(method, read + 2, task, MODULE_FAILED)
}

/**
 * Both sites, the step runner's first. Each anchor string has to lead to exactly one site. Throws,
 * naming what's missing, before anything changes.
 */
internal fun BytecodePatchContext.findModuleEvidenceSites(): List<ModuleEvidenceSite> {
    fun refuse(message: String): Nothing = throw PatchException("$EVIDENCE: $message")
    fun sites(anchor: String, site: (Method) -> ModuleEvidenceSite?) =
        classDefByStrings(anchor, StringComparisonType.EQUALS).flatMap { classDef -> classDef.methods.mapNotNull(site) }
    val step = sites(NO_STEPS, ::moduleStepSite).let { it.singleOrNull() ?: refuse("expected one module step runner, found ${it.size}") }
    val failure = sites(FAILED_NO_EXCEPTION, ::moduleFailureSite)
        .let { it.singleOrNull() ?: refuse("expected one module download failure path, found ${it.size}") }
    return listOf(step, failure)
}

/**
 * Hands each site's register to the extension, by range so any register fits. Read only: nothing
 * else changes and no register is added. A jump to the instruction after a site skips its call,
 * which is right for both: neither register holds a step or a task on another way in.
 */
internal fun BytecodePatchContext.applyModuleEvidence(sites: List<ModuleEvidenceSite>) {
    for (site in sites) {
        mutableClassDefBy(site.method.definingClass).findMutableMethodOf(site.method)
            .addInstruction(site.index, "invoke-static/range { v${site.register} .. v${site.register} }, ${site.call}")
    }
}

/**
 * With Debug logging on, the diagnostic report's module download line says which steps Facebook's
 * module downloader took and what the download screen's task held when it failed (#168, see the
 * extension's ModuleDownloadEvidence). No switch and no name of its own: Hushfacebook settings
 * brings it into every build. It reads and changes nothing. A build missing an anchor goes on
 * without it, with a warning: a throw here would stop Hushfacebook settings, and every patch with it.
 */
internal val moduleDownloadEvidencePatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        // Dependencies run before Settings, and its refusal has to be what explains an opaque
        // Android 9 build, so nothing is looked up in one.
        if (compressedCodeRefusal(classDefByOrNull(FACEBOOK_APPLICATION) != null, classDefByOrNull(MAIN_TAB_ACTIVITY)) != null) {
            return@executeBytecode
        }
        val sites = try {
            findModuleEvidenceSites()
        } catch (missing: Exception) {
            val why = if (missing is PatchException) missing.message else "$EVIDENCE: ${missing::class.java.name}: ${missing.message}"
            patchLog.warning("$why. Debug reports go on without the module downloader's steps.")
            return@executeBytecode
        }
        applyModuleEvidence(sites)
    }
}
