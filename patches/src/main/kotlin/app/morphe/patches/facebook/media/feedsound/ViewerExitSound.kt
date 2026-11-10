/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.feedsound

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.util.ControlFlow
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val VIEWER_PATCH = "Keep feed videos muted (opened videos)"
internal const val OWN_VIEWER_SOUND_TIME = "$FEED_SOUND->viewerSoundTime(J)J"

/** The name Facebook logs for the step that runs as a video opened from the feed closes. */
internal const val RESTORE_STEP = "WarionSoundStateManager.restoreSoundToggleState"

/** The marker point that step logs when the video played with its sound past Facebook's threshold. */
internal const val SOUND_ON_WHEN_EXIT = "sound_on_when_exit"

/** The call that takes the video's time with sound out of its counter, leaving the counter empty. */
private const val TAKE_TIME = "Ljava/util/concurrent/atomic/AtomicLong;->getAndSet(J)J"

/**
 * The close step [method] in [owner]: it takes how long the opened video played with its sound on
 * into the register pair starting at [time], with the move-result-wide right before [askAt], then
 * weighs that against a threshold Facebook's servers set.
 */
internal class ViewerExitSound(val owner: String, val method: Method, val askAt: Int, val time: Int)

/**
 * The close step in [owner]: its one method holding [RESTORE_STEP] and naming [SOUND_ON_WHEN_EXIT],
 * with one [TAKE_TIME] call whose result is the first number its next cmp-long weighs, right before
 * it logs [SOUND_ON_WHEN_EXIT]. Throws for any other shape. 586's Android 9 build asks a name table
 * for the marker point's name, which [resolve] finds ([namesString]).
 */
internal fun viewerExitSound(owner: ClassDef, resolve: (MethodReference) -> Method? = { null }): ViewerExitSound {
    val steps = owner.methods.filter { holdsString(it, RESTORE_STEP) && namesString(it, SOUND_ON_WHEN_EXIT, resolve) }
    val method = steps.singleOrNull()
        ?: throw PatchException("$VIEWER_PATCH: ${owner.type} has ${steps.size} methods holding $RESTORE_STEP and $SOUND_ON_WHEN_EXIT")
    val where = "${owner.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val takes = code.indices.filter {
        code[it].opcode == Opcode.INVOKE_VIRTUAL && ((code[it] as ReferenceInstruction).reference as? MethodReference)?.toString() == TAKE_TIME
    }
    val take = takes.singleOrNull()
        ?: throw PatchException("$VIEWER_PATCH: $where takes ${takes.size} times out of a counter, expected one")
    val result = code.getOrNull(take + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_WIDE) throw PatchException("$VIEWER_PATCH: $where doesn't keep the time it takes")
    val time = (result as OneRegisterInstruction).registerA

    val compare = (take + 2 until code.size).firstOrNull { code[it].opcode == Opcode.CMP_LONG }
        ?: throw PatchException("$VIEWER_PATCH: $where never weighs the time it takes")
    if ((code[compare] as ThreeRegisterInstruction).registerB != time) {
        throw PatchException("$VIEWER_PATCH: $where weighs something other than the time it takes")
    }
    val logs = (compare + 1..minOf(compare + 3, code.lastIndex)).any {
        ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == SOUND_ON_WHEN_EXIT ||
            tableStringAt(code, it, resolve) == SOUND_ON_WHEN_EXIT
    }
    if (!logs) throw PatchException("$VIEWER_PATCH: $where doesn't log $SOUND_ON_WHEN_EXIT after weighing the time")

    // The ask goes right after the move-result-wide, so only that may lead there.
    val askAt = take + 2
    val flow = ControlFlow.of(method)
    for (index in code.indices) {
        if (index != take + 1 && askAt in flow.normal[index] + flow.exceptional[index]) {
            throw PatchException("$VIEWER_PATCH: $where has a jump past the time it takes")
        }
    }
    return ViewerExitSound(owner.type, method, askAt, time)
}

/**
 * The close step, from the one class outside the extension with a method holding [RESTORE_STEP]
 * and naming [SOUND_ON_WHEN_EXIT]. 585 has a second class holding the marker point's name, a table
 * of names with no [RESTORE_STEP], and 586's Android 9 build's step asks such a table for it.
 */
internal fun BytecodePatchContext.viewerExitSound(): ViewerExitSound {
    val tables = classDefByStrings(SOUND_ON_WHEN_EXIT, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
    val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
    val owners = classDefByStrings(RESTORE_STEP, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { owner -> owner.methods.any { holdsString(it, RESTORE_STEP) && namesString(it, SOUND_ON_WHEN_EXIT, resolve) } }
    val owner = owners.singleOrNull()
        ?: throw PatchException("$VIEWER_PATCH: found ${owners.size} classes logging $RESTORE_STEP's $SOUND_ON_WHEN_EXIT, expected one")
    return viewerExitSound(owner, resolve)
}

/**
 * Asks FeedSound for the opened video's time with sound right after the close step takes it, and
 * puts the answer back in the same register pair, so the threshold check and the rest of the step
 * run as Facebook wrote them.
 */
internal fun BytecodePatchContext.askViewerSoundTime(close: ViewerExitSound) {
    mutableClassDefBy(close.owner).findMutableMethodOf(close.method).addInstructions(
        close.askAt,
        """
            invoke-static/range { v${close.time} .. v${close.time + 1} }, $OWN_VIEWER_SOUND_TIME
            move-result-wide v${close.time}
        """,
    )
}
