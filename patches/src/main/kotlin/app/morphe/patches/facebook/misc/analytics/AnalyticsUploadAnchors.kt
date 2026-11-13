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
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.reels.watchhistory.callRegisters
import app.morphe.patches.facebook.shared.readsMobileConfig
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Where Facebook uploads its app analytics, on the 577 to 582 builds.
 *
 * XAnalytics is the native event logger much of the app logs through. Its Java side keeps its
 * names: XAnalyticsNative's kickOffUpload() and resumeUploading(String) are native methods, and each
 * has one caller. NativeXAnalyticsAppJobHandler schedules a runnable every three minutes while the
 * app is in the foreground, and its run() flushes the buffer and then calls kickOffUpload().
 * NativeXAnalyticsLowPriorityInit sets the uploader's network stack during start-up and then calls
 * resumeUploading(). The hook goes just before each call and skips it on a yes.
 *
 * Papaya is Meta's on-device learning. FBPapayaJobService keeps its name; onStartJob is declared on
 * it (577, 580) or on its renamed superclass (581, 582's `LX/PFS;`). It asks Facebook's config whether Papaya is on
 * and, when it isn't, returns false straight away with nothing started. The hook passes that answer
 * through the extension, so a held job takes Facebook's own off path.
 */
internal const val PATCH = "Hold back analytics uploads"

internal const val PAPAYA_SERVICE = "Lcom/facebook/papaya/fb/client/services/FBPapayaJobService;"

/** The config the Papaya job's start reads before it asks whether Papaya is on. */
internal const val PAPAYA_CONFIG = "papayaConfig"

internal const val JOB_PARAMETERS = "Landroid/app/job/JobParameters;"

internal const val XANALYTICS = "Lcom/facebook/xanalytics/XAnalyticsNative;"
internal const val APP_JOB_HANDLER = "Lcom/facebook/xanalytics/provider/NativeXAnalyticsAppJobHandler;"
internal const val LOW_PRIORITY_INIT = "Lcom/facebook/xanalytics/provider/NativeXAnalyticsLowPriorityInit;"
internal const val KICK_OFF_UPLOAD = "kickOffUpload"
internal const val RESUME_UPLOADING = "resumeUploading"
internal const val RUNNABLE = "Ljava/lang/Runnable;"

private const val ANALYTICS_UPLOADS = "$EXTENSION_PACKAGE/misc/AnalyticsUploads;"
internal const val HOLD_XANALYTICS = "$ANALYTICS_UPLOADS->holdXAnalyticsUpload()Z"
internal const val PAPAYA_ON = "$ANALYTICS_UPLOADS->papayaOn(Z)Z"
internal const val ANALYTICS2_JOB_RAN = "com.facebook.analytics2.action.UPLOAD_JOB_RAN"
internal const val OBSERVE_ANALYTICS2_RETRY = "$ANALYTICS_UPLOADS->observeAnalytics2Retry(Z)V"

/**
 * The unique framework store of Analytics2's retry flag, after the state machine finishes. Its key
 * is "will_retry", loaded straight before the store or read from a string table into the key
 * register: 587's arm64 `LX/064q;->A00` asks `LX/0fSL;->A00` for index 0x1ea, which [resolve] finds.
 */
internal fun analytics2RetryValue(method: Method, resolve: (MethodReference) -> Method? = { null }): Int? {
    if (method.returnType != "V" || method.parameterTypes.isNotEmpty() ||
        !holdsString(method, ANALYTICS2_JOB_RAN) || !holdsString(method, "exitStateMachine")) return null
    val instructions = method.implementation?.instructions?.toList() ?: return null
    return (1 until instructions.lastIndex).filter { index ->
        val call = instructions[index]
        val ref = (call as? ReferenceInstruction)?.reference as? MethodReference
        val registers = call.callRegisters()
        call.opcode in listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
            ref?.definingClass == "Landroid/os/BaseBundle;" && ref.name == "putBoolean" &&
            ref.returnType == "V" && ref.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "Z") &&
            registers.size == 3 && registers.toSet().size == 3 &&
            loadsRetryKey(instructions, index - 1, registers[1], resolve)
    }.singleOrNull()
}

/** Whether the instruction at [index] puts "will_retry" in [register], as a literal or a string table's answer. */
private fun loadsRetryKey(code: List<Instruction>, index: Int, register: Int, resolve: (MethodReference) -> Method?): Boolean {
    val key = code[index]
    if ((key as? OneRegisterInstruction)?.registerA != register) return false
    return when (key.opcode) {
        Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
            ((key as ReferenceInstruction).reference as? StringReference)?.string == "will_retry"
        Opcode.MOVE_RESULT_OBJECT -> tableStringAt(code, index - 1, resolve) == "will_retry"
        else -> false
    }
}

/** One void call after the existing store, borrowing no registers and changing no host values. */
internal fun MutableMethod.observeAnalytics2Retry(index: Int, resolve: (MethodReference) -> Method? = { null }) {
    if (analytics2RetryValue(this, resolve) != index) throw PatchException("$PATCH: Analytics2's retry store moved in $definingClass->$name")
    val value = getInstruction(index).callRegisters()[2]
    addInstructions(index + 1, "invoke-static/range { v$value .. v$value }, $OBSERVE_ANALYTICS2_RETRY")
}

/** Whether [method] is the Papaya job's start: onStartJob(JobParameters)Z with a body. */
internal fun isJobStart(method: Method): Boolean =
    method.name == "onStartJob" && method.returnType == "Z" && method.implementation != null &&
        method.parameterTypes.map { it.toString() } == listOf(JOB_PARAMETERS)

/**
 * The index of the move-result that takes the Papaya gate's answer in [method], or null when it
 * isn't the job's start or the gate isn't exactly once in it. The gate is a MobileConfig boolean
 * read (MobileConfigUnsafeContext's own on 581, 582's `LX/3gg;->A1I` helper, which [classOf] looks
 * into), its result tested by if-nez, whose fall-through releases the lock and returns a register
 * last set to 0.
 */
internal fun papayaGate(method: Method, classOf: (String) -> ClassDef?): Int? {
    if (!isJobStart(method) || !holdsString(method, PAPAYA_CONFIG)) return null
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val gates = (0 until instructions.size - 4).filter { index ->
        val result = instructions[index + 1]
        val test = instructions[index + 2]
        readsMobileConfig(instructions[index], "Z", classOf) &&
            result.opcode == Opcode.MOVE_RESULT && test.opcode == Opcode.IF_NEZ &&
            (test as OneRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA &&
            instructions[index + 3].opcode == Opcode.MONITOR_EXIT && instructions[index + 4].opcode == Opcode.RETURN &&
            returnsZero(instructions, index, (instructions[index + 4] as OneRegisterInstruction).registerA)
    }
    return gates.singleOrNull()?.plus(1)
}

/** Whether the last write to [register] before [index] sets it to the literal 0. */
private fun returnsZero(instructions: List<Instruction>, index: Int, register: Int): Boolean {
    val write = (index - 1 downTo 0).map { instructions[it] }.firstOrNull {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
    } ?: return false
    return write.opcode.name.startsWith("const") && (write as? NarrowLiteralInstruction)?.narrowLiteral == 0
}

/** Whether [instruction] calls XAnalyticsNative's [name] with [parameters]. */
private fun calls(instruction: Instruction, name: String, parameters: List<String>): Boolean {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.definingClass == XANALYTICS && call.name == name && call.returnType == "V" &&
        call.parameterTypes.map { it.toString() } == parameters
}

/** Every index in [method] where it calls XAnalyticsNative's [name] with [parameters]. */
private fun callsIn(method: Method, name: String, parameters: List<String>): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.filter { calls(instructions[it], name, parameters) }
}

/**
 * The kickOffUpload() calls in the run() of each Runnable [handler] makes, as (run(), index). The
 * foreground task is the one runnable the handler builds that makes the call; [classOf] looks a
 * class up by its type.
 */
internal fun kickOffUploads(handler: ClassDef, classOf: (String) -> ClassDef?): List<Pair<Method, Int>> {
    val built = handler.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.toSet()
    return built.mapNotNull(classOf).filter { runnable -> runnable.interfaces.any { it.toString() == RUNNABLE } }
        .flatMap { runnable ->
            runnable.methods.filter { it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() }
                .flatMap { run -> callsIn(run, KICK_OFF_UPLOAD, emptyList()).map { run to it } }
        }
}

/** The resumeUploading(String) calls in [init]'s methods, as (method, index). */
internal fun resumeUploads(init: ClassDef): List<Pair<Method, Int>> =
    init.methods.flatMap { method -> callsIn(method, RESUME_UPLOADING, listOf("Ljava/lang/String;")).map { method to it } }

/**
 * Asks [HOLD_XANALYTICS] just before the XAnalytics call at [index] and jumps past the call on a
 * yes. The answer goes in a register the call doesn't read and nothing after it reads unwritten.
 */
internal fun MutableMethod.skipUploadWhenHeld(index: Int, name: String) {
    val call = getInstruction(index)
    val parameters = if (name == RESUME_UPLOADING) listOf("Ljava/lang/String;") else emptyList()
    if (!calls(call, name, parameters)) throw PatchException("$PATCH: instruction $index of $definingClass->${this.name} isn't $name")
    val answer = findFreeRegister(index, call.callRegisters())
    addInstructionsWithLabels(
        index,
        """
            invoke-static { }, $HOLD_XANALYTICS
            move-result v$answer
            if-nez v$answer, :held
        """,
        ExternalLabel("held", getInstruction(index + 1)),
    )
}

/** Passes the Papaya gate's answer, taken by the move-result at [index], through [PAPAYA_ON]. */
internal fun MutableMethod.passPapayaGate(index: Int) {
    val answer = (getInstruction(index) as? OneRegisterInstruction)?.takeIf { getInstruction(index).opcode == Opcode.MOVE_RESULT }
        ?.registerA ?: throw PatchException("$PATCH: instruction $index of $definingClass->$name isn't the gate's move-result")
    addInstructions(
        index + 1,
        """
            invoke-static/range { v$answer .. v$answer }, $PAPAYA_ON
            move-result v$answer
        """,
    )
}
