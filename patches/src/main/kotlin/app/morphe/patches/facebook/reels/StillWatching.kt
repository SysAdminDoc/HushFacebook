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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
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
 * - 587 (read 2026-11-13, both ABIs) moved the idle state onto the AutoAdvanceIdleTimer the
 *   controller holds (arm64 LX/09m9; holds LX/09mJ; in A0i, armv7 LX/09lw; holds LX/09m6;). The
 *   runnable (LX/09mK;, armv7 LX/09m7;) holds only the timer, sets its A02, reads its A03 and shows
 *   the prompt through the timer's Function0 A0D; the check (A0N) reads the timer from A0i and then
 *   its A02. [idleStateOwners] finds the timer from the check's reads, so the runnable is looked for
 *   on whichever class keeps the flags.
 */

internal const val STILL_WATCHING = "$EXTENSION_PACKAGE/reels/StillWatching;"
internal const val STILL_WATCHING_IDLE = "$STILL_WATCHING->idle(I)Z"

private const val PATCH = "Clean up Reels"

/**
 * The idle runnable's work: the flag it sets, the held-back advance it reads and the prompt it shows.
 * The prompt is the flag owner's own method, or (587) Function0's invoke on the owner's [callback].
 */
internal data class IdleRunnable(
    val flag: FieldReference,
    val pending: FieldReference,
    val prompt: MethodReference,
    val callback: FieldReference? = null,
)

private const val PROMPT_CALLBACK = "Lkotlin/jvm/functions/Function0;"

private fun ClassDef.instanceFields() = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun field(instruction: Instruction) =
    (instruction as? ReferenceInstruction)?.reference as? FieldReference

private fun called(instruction: Instruction) =
    (instruction as? ReferenceInstruction)?.reference as? MethodReference

/** The shape of the controller's idle check: static, taking the controller, answering a boolean. */
private fun isIdleCheck(method: Method, controller: ClassDef) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(controller.type)

/**
 * The classes that may keep [controller]'s idle flags: the controller, and the type of each of its
 * own instance fields that one of its idle-check-shaped methods reads and then reads a boolean of off
 * the same register (587's AutoAdvanceIdleTimer, read as `iget-object C.A0i` then `iget-boolean`).
 */
internal fun idleStateOwners(controller: ClassDef): Set<String> {
    val held = controller.instanceFields().map { it.name to it.type }.toSet()
    val owners = linkedSetOf(controller.type)
    controller.methods.filter { isIdleCheck(it, controller) }.forEach { method ->
        val code = method.implementation?.instructions?.toList() ?: return@forEach
        code.zipWithNext { load, read ->
            val via = field(load) ?: return@zipWithNext
            val flag = field(read) ?: return@zipWithNext
            if (load.opcode == Opcode.IGET_OBJECT && read.opcode == Opcode.IGET_BOOLEAN &&
                via.definingClass == controller.type && (via.name to via.type) in held &&
                flag.definingClass == via.type && flag.type == "Z" &&
                (read as TwoRegisterInstruction).registerB == (load as TwoRegisterInstruction).registerA
            ) {
                owners += via.type
            }
        }
    }
    return owners
}

/**
 * The idle runnable among the classes that name one of [owners] ([idleStateOwners]): a Runnable
 * holding nothing but one owner, whose run() sets one of that owner's booleans to true, reads another,
 * and shows the prompt, either by calling one of the owner's own no-argument methods or (587) by
 * invoking the Function0 it loaded off one of the owner's fields right before. Null for anything else.
 */
internal fun idleRunnable(candidate: ClassDef, owners: Set<String>): IdleRunnable? {
    if ("Ljava/lang/Runnable;" !in candidate.interfaces) return null
    val owner = candidate.instanceFields().singleOrNull()?.type?.takeIf { it in owners } ?: return null
    val run = candidate.methods.singleOrNull { it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        ?: return null
    val code = run.implementation?.instructions?.toList() ?: return null
    fun owns(field: FieldReference?) = field != null && field.definingClass == owner && field.type == "Z"

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
    // The prompt: a call to one of the owner's own no-argument methods (585, 586), or Function0's
    // invoke on the callback loaded off the owner into the register it invokes (587).
    val prompts = mutableListOf<Pair<MethodReference, FieldReference?>>()
    code.forEachIndexed { at, instruction ->
        val call = called(instruction) ?: return@forEachIndexed
        if (instruction.opcode == Opcode.INVOKE_VIRTUAL && call.definingClass == owner &&
            call.parameterTypes.isEmpty() && call.returnType == "V"
        ) {
            prompts += call to null
        }
        if (instruction.opcode == Opcode.INVOKE_INTERFACE && call.definingClass == PROMPT_CALLBACK &&
            call.name == "invoke" && call.parameterTypes.isEmpty() && call.returnType == "Ljava/lang/Object;"
        ) {
            val load = code.getOrNull(at - 1) ?: return null
            val callback = field(load) ?: return null
            val invoked = instruction as? FiveRegisterInstruction ?: return null
            if (load.opcode != Opcode.IGET_OBJECT || callback.definingClass != owner || callback.type != PROMPT_CALLBACK ||
                invoked.registerCount != 1 || (load as TwoRegisterInstruction).registerA != invoked.registerC
            ) {
                return null
            }
            prompts += call to callback
        }
    }
    val (prompt, callback) = prompts.singleOrNull() ?: return null
    return IdleRunnable(flag, pending, prompt, callback)
}

/** [controller]'s static boolean checks that take the controller and read [flag]. */
internal fun idleChecks(controller: ClassDef, flag: FieldReference): List<Method> = controller.methods.filter { method ->
    isIdleCheck(method, controller) &&
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
    val owners = idleStateOwners(controller)
    val runnables = mutableListOf<IdleRunnable>()
    classDefForEachReferencing(types = owners) { candidate ->
        if (candidate.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        idleRunnable(candidate, owners)?.let(runnables::add)
    }
    val runnable = runnables.distinct().singleOrNull()
        ?: refuse("expected one Runnable that sets a flag on one of ${owners.joinToString()} and shows the prompt, found ${runnables.size}")
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
