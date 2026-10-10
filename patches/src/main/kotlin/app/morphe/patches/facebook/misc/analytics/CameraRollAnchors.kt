/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.analytics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.reels.watchhistory.callRegisters
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.patches.facebook.shared.readsMobileConfig
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Facebook decides to run its camera roll cloud processing, on 582.
 *
 * The processing behind camera roll sharing suggestions looks through the phone's photos and
 * videos, runs models on them and uploads photos, video details and the model output. No local
 * preference gates it: the opt-in on the Camera roll sharing suggestions page is applied on
 * Facebook's server. One static config check, `(FbUserSession)Z` on a renamed class, decides
 * whether it runs: the scheduling app job cancels all its workers when the check says no, and the
 * pipeline's run, the media insights and the photo upload each stop on a no. The pipeline's run
 * keeps its coroutine name and loads the kept reason `adv_pro_app_job_disabled` next to its one call
 * to the check, which is how the check is found. The hook goes first in the check and answers no.
 *
 * MediaCountUploadAppJob (a kept name) schedules the worker that reports how many photos, videos and
 * motion photos the phone holds. Its first config read is a kill switch whose yes branch cancels
 * that worker's unique work, so the hook passes the read's answer through the extension and a held
 * job takes that branch.
 */
internal const val RUN_PIPELINE = "Lcom/facebook/camerarollprocessor/advancedpro/PipelineManager\$runPipeline\$2;"
internal const val MEDIA_COUNT_JOB = "Lcom/facebook/camerarollprocessor/appjob/MediaCountUploadAppJob;"
internal const val SCHEDULING_JOB = "Lcom/facebook/camerarollprocessor/advancedpro/appjob/AdvancedProAppJob;"

/** The reason the pipeline's run gives when the processing check says no. */
internal const val PROCESSING_OFF = "adv_pro_app_job_disabled"

/** The unique work name of the media count worker, which the kill switch's branch cancels. */
internal const val MEDIA_COUNT_WORK = "MediaCountUploadWorker"

private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

private const val CAMERA_ROLL = "$EXTENSION_PACKAGE/misc/CameraRollProcessing;"
internal const val HOLD_PROCESSING = "$CAMERA_ROLL->holdProcessing()Z"
internal const val STOP_MEDIA_COUNT = "$CAMERA_ROLL->stopMediaCount(Z)Z"

/** The method [instruction] calls when it has the processing check's shape, a static `(FbUserSession)Z`. */
private fun sessionCheck(instruction: Instruction): MethodReference? {
    if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return call.takeIf { it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) }
}

/** Every static `(FbUserSession)Z` call in [method], as written. */
internal fun sessionChecks(method: Method): List<MethodReference> =
    method.implementation?.instructions?.mapNotNull(::sessionCheck).orEmpty()

/**
 * The processing check: the one static `(FbUserSession)Z` the pipeline's run calls, in the
 * invokeSuspend of [runPipeline] that loads [PROCESSING_OFF]. Null when that isn't exactly one.
 */
internal fun processingCheck(runPipeline: ClassDef): MethodReference? {
    val runs = runPipeline.methods.filter { it.name == "invokeSuspend" && holdsString(it, PROCESSING_OFF) }
    val checks = runs.singleOrNull()?.let(::sessionChecks)?.distinctBy { "${it.definingClass}->${it.name}" }
    return checks?.singleOrNull()
}

/** Whether [method] is the check [call] names, with a body to hook. */
internal fun isProcessingCheck(method: Method, call: MethodReference): Boolean =
    method.definingClass == call.definingClass && method.name == call.name && method.returnType == "Z" &&
        method.parameterTypes.map(Any::toString) == listOf(USER_SESSION) &&
        AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null

/**
 * The index of the move-result that takes the media count job's kill switch in [method], or null
 * when [method] isn't the job's void run. The switch is the method's first boolean config read.
 * After its move-result come only constant loads into other registers, then an if-nez on the
 * answer whose target cancels the unique work named [MEDIA_COUNT_WORK]: a call taking one String
 * that reads a register loaded with that name, before the method returns.
 */
internal fun mediaCountSwitch(method: Method, classOf: (String) -> ClassDef?): Int? {
    if (method.returnType != "V" || method.parameterTypes.isNotEmpty() || !holdsString(method, MEDIA_COUNT_WORK)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val read = code.indexOfFirst { readsMobileConfig(it, "Z", classOf) }
    if (read < 0 || code.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT) return null
    val answer = (code[read + 1] as OneRegisterInstruction).registerA
    val test = (read + 2 until code.size).firstOrNull { index ->
        val instruction = code[index]
        !instruction.opcode.name.startsWith("const") || (instruction as OneRegisterInstruction).registerA == answer
    } ?: return null
    if (code[test].opcode != Opcode.IF_NEZ || (code[test] as OneRegisterInstruction).registerA != answer) return null
    val cancel = branchTarget(code, test) ?: return null
    val work = workRegisters(code.subList(0, test))
    val block = code.drop(cancel).takeWhile { !it.opcode.name.startsWith("return") }
    val cancels = block.any { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call != null && call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") &&
            instruction.callRegisters().any { it in work }
    }
    return if (cancels) read + 1 else null
}

/** The registers [code] leaves holding the string [MEDIA_COUNT_WORK]. */
private fun workRegisters(code: List<Instruction>): Set<Int> {
    val holding = mutableSetOf<Int>()
    for (instruction in code) {
        val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        if (!instruction.opcode.setsRegister()) continue
        val string = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        if (string == MEDIA_COUNT_WORK) holding += register else holding -= register
    }
    return holding
}

/** First in the processing check: [HOLD_PROCESSING] answers yes, and the check answers false at once. */
internal fun MutableMethod.holdProcessingFirst() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_PROCESSING
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}

/** Passes the media count kill switch's answer, taken by the move-result at [index], through [STOP_MEDIA_COUNT]. */
internal fun MutableMethod.passMediaCountSwitch(index: Int) {
    val answer = (getInstruction(index) as? OneRegisterInstruction)?.takeIf { getInstruction(index).opcode == Opcode.MOVE_RESULT }
        ?.registerA ?: throw PatchException("$PATCH: instruction $index of $definingClass->$name isn't the kill switch's move-result")
    addInstructions(
        index + 1,
        """
            invoke-static/range { v$answer .. v$answer }, $STOP_MEDIA_COUNT
            move-result v$answer
        """,
    )
}
