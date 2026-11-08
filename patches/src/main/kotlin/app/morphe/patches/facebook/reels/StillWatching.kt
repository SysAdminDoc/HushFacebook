/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * "Are you still watching?" (#427). Read from 585 (arm64 and its Android 9 build), 2026-11-08; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The Reels auto-advance controller ([autoAdvanceControllers], 585 LX/0AqK;, Redex's
 *   WatchMetadataPeekPillController) arms an idle timer whenever you touch the player. When it fires,
 *   its runnable (LX/0AqL;, showIdleTimerRunnable) sets the controller's idle flag (A0J) and, when the
 *   countdown already held back an advance (A0N), shows the prompt (A0W), which pauses the video.
 * - One static check on the controller (A0O) reads that flag, and also says idle once enough videos
 *   went by since the last touch, both behind server switches. The countdown to the next video asks
 *   it right before advancing and, on a yes, holds the advance back and marks it for the runnable;
 *   the end of a video asks it before posting the prompt itself.
 * - The hook hands each of the check's answers to the extension, which says not idle while the
 *   switch is on. The countdown then advances as it would for anyone touching the screen, nothing is
 *   held back for the runnable to find, and the end of a video posts nothing.
 */

internal const val STILL_WATCHING = "$EXTENSION_PACKAGE/reels/StillWatching;"
internal const val STILL_WATCHING_IDLE = "$STILL_WATCHING->idle(I)Z"

private const val PATCH = "Clean up Reels"

/** The idle runnable's work: the flag it sets, the held-back advance it reads and the prompt it shows. */
internal data class IdleRunnable(val flag: FieldReference, val pending: FieldReference, val prompt: MethodReference)

private fun ClassDef.instanceFields() = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun field(instruction: Instruction) =
    (instruction as? ReferenceInstruction)?.reference as? FieldReference

/**
 * The idle runnable among the classes that name [controller]: a Runnable holding nothing but the
 * controller, whose run() sets one of the controller's booleans to true, reads another, and calls one
 * of the controller's own no-argument methods. Null for anything else.
 */
internal fun idleRunnable(candidate: ClassDef, controller: String): IdleRunnable? {
    if ("Ljava/lang/Runnable;" !in candidate.interfaces) return null
    if (candidate.instanceFields().singleOrNull()?.type != controller) return null
    val run = candidate.methods.singleOrNull { it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        ?: return null
    val code = run.implementation?.instructions?.toList() ?: return null
    fun owns(field: FieldReference?) = field != null && field.definingClass == controller && field.type == "Z"

    val sets = code.indices.filter { code[it].opcode == Opcode.IPUT_BOOLEAN && owns(field(code[it])) }
    val set = sets.singleOrNull() ?: return null
    val before = code.getOrNull(set - 1) ?: return null
    val value = (code[set] as TwoRegisterInstruction).registerA
    if (before.opcode != Opcode.CONST_4 || (before as OneRegisterInstruction).registerA != value ||
        (before as NarrowLiteralInstruction).narrowLiteral != 1
    ) {
        return null
    }
    val flag = field(code[set])!!
    val pending = code.filter { it.opcode == Opcode.IGET_BOOLEAN && owns(field(it)) }.mapNotNull(::field)
        .singleOrNull { it.name != flag.name } ?: return null
    val prompt = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .singleOrNull { it.definingClass == controller && it.parameterTypes.isEmpty() && it.returnType == "V" } ?: return null
    return IdleRunnable(flag, pending, prompt)
}

/** [controller]'s static boolean checks that take the controller and read [flag]. */
internal fun idleChecks(controller: ClassDef, flag: FieldReference): List<Method> = controller.methods.filter { method ->
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(controller.type) &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.IGET_BOOLEAN && field(it)?.let { read -> read.definingClass == flag.definingClass && read.name == flag.name } == true
        } == true
}

/** The controller, its idle runnable and its idle check, as found. Throws, naming what's missing. */
internal class StillWatchingAnchors(val controller: ClassDef, val runnable: IdleRunnable, val check: Method)

internal fun BytecodePatchContext.findStillWatchingAnchors(): StillWatchingAnchors {
    fun refuse(message: String): Nothing = throw PatchException("$PATCH: $message")
    val controllers = autoAdvanceControllers(
        classDefByStrings(AUTO_ADVANCE_IDLE, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type },
    )
    val controller = controllers.singleOrNull()
        ?: refuse("expected one class holding \"$AUTO_ADVANCE_IDLE\" and \"$DID_AUTO_ADVANCE\", found ${controllers.size}")
    val runnables = mutableListOf<IdleRunnable>()
    classDefForEachReferencing(types = listOf(controller.type)) { candidate ->
        if (candidate.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        idleRunnable(candidate, controller.type)?.let(runnables::add)
    }
    val runnable = runnables.distinct().singleOrNull()
        ?: refuse("expected one Runnable that sets one of ${controller.type}'s flags and shows its prompt, found ${runnables.size}")
    val checks = idleChecks(controller, runnable.flag)
    val check = checks.singleOrNull()
        ?: refuse("expected one static check on ${controller.type} reading ${runnable.flag.name}, found ${checks.size}")
    return StillWatchingAnchors(controller, runnable, check)
}

/**
 * The controller's idle check answers through the extension, which says not idle while Skip "Are you
 * still watching?" is on (#427). Every return goes through it, so each branch of the check does.
 */
internal fun BytecodePatchContext.skipStillWatching() {
    val anchors = findStillWatchingAnchors()
    mutableClassDefBy(anchors.controller.type).findMutableMethodOf(anchors.check)
        .filterBooleanReturns(PATCH, STILL_WATCHING_IDLE)
}
