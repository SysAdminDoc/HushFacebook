/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * Which player the Reels auto-advance controller holds, read from 583 on 2026-10-20. The obfuscated
 * names here are for reviewers; the code finds each by kept strings and field types.
 *
 * The end-of-video step (583 LX/5Qy;->EMO(J)V, the one method holding "Unable to
 * grootPlayer.onVideoComplete", whose loop check reelLoopPatch hooks) belongs to a listener with one
 * field, the player it listens to (LX/5B3;), and reads it first thing. The Reels auto-advance
 * controller (583 LX/8eg;) is the one class holding both "AutoAdvanceIdleTimer" and
 * "did_auto_advance". Its one field of the player's type (A05) is the player it advances from, and
 * two methods set it: the Reels binder (583 LX/8bn;->A04()V) and LX/97F;->A2c, which also needs a
 * nop to keep its switch payload aligned once hooked.
 *
 * An Auto-next switch would move to the next reel when a video ends on that player. Whether the
 * controller's player is still the one playing after a swipe back, a replay or Facebook's own
 * advance hasn't been seen, so this only watches: the end-of-video step hands its player to the
 * extension right after reading it, and each write of the controller's field hands over the
 * controller and the player just set. The extension writes fixed labels to the debug log.
 */

internal const val REEL_BINDING = "$EXTENSION_PACKAGE/reels/ReelBinding;"
internal const val BINDING_BOUND = "$REEL_BINDING->bound(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val BINDING_ENDED = "$REEL_BINDING->ended(Ljava/lang/Object;)V"

/** Two log strings only the Reels auto-advance controller holds both of. */
internal const val AUTO_ADVANCE_IDLE = "AutoAdvanceIdleTimer"
internal const val DID_AUTO_ADVANCE = "did_auto_advance"

private const val BINDING = "Reel binding evidence"

/** The end-of-video step's read of its player: the `iget-object`'s index, the register it lands in and the field. */
internal data class PlayerRead(val at: Int, val register: Int, val field: FieldReference)

/** One write of the controller's player: the `iput-object`'s index, the player's register and the controller's. */
internal data class PlayerWrite(val at: Int, val player: Int, val controller: Int)

/** The register `this` arrives in, or null for a static method or one without a body. */
private fun thisRegister(method: Method): Int? {
    val implementation = method.implementation ?: return null
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val parameters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return implementation.registerCount - parameters - 1
}

/**
 * [complete]'s first read of its owner's player from `this`, or null. The owner has to declare one
 * instance field, of an object type: a listener that keeps anything else isn't the one read here.
 */
internal fun playerRead(owner: ClassDef, complete: Method): PlayerRead? {
    val field = owner.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.singleOrNull() ?: return null
    if (!field.type.startsWith("L")) return null
    val self = thisRegister(complete) ?: return null
    val code = complete.implementation?.instructions?.toList() ?: return null
    return code.withIndex().firstNotNullOfOrNull { (at, instruction) ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@firstNotNullOfOrNull null
        val read = (instruction as ReferenceInstruction).reference as FieldReference
        val registers = instruction as TwoRegisterInstruction
        if (read.definingClass != owner.type || read.name != field.name || read.type != field.type || registers.registerB != self) {
            null
        } else {
            PlayerRead(at, registers.registerA, read)
        }
    }
}

/** The classes among [candidates] with a method holding [AUTO_ADVANCE_IDLE] and one holding [DID_AUTO_ADVANCE]. */
internal fun autoAdvanceControllers(candidates: List<ClassDef>): List<ClassDef> = candidates.filter { candidate ->
    candidate.methods.any { holdsString(it, AUTO_ADVANCE_IDLE) } && candidate.methods.any { holdsString(it, DID_AUTO_ADVANCE) }
}

/** [controller]'s one instance field of [playerType], or null when it has none or several. */
internal fun boundPlayerField(controller: ClassDef, playerType: String): FieldReference? =
    controller.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == playerType }.singleOrNull()

/** Whether [instruction] is an `iput-object` of [field]. */
private fun writes(instruction: Instruction, field: FieldReference): Boolean {
    if (instruction.opcode != Opcode.IPUT_OBJECT) return false
    val write = (instruction as ReferenceInstruction).reference as FieldReference
    return write.definingClass == field.definingClass && write.name == field.name && write.type == field.type
}

/** Each `iput-object` of [field] in [method]. */
internal fun playerWrites(method: Method, field: FieldReference): List<PlayerWrite> {
    val code = method.implementation?.instructions ?: return emptyList()
    return code.withIndex().filter { writes(it.value, field) }.map { (at, instruction) ->
        val registers = instruction as TwoRegisterInstruction
        PlayerWrite(at, registers.registerA, registers.registerB)
    }
}

/** The end-of-video step and the controller's writers, each as the patch will change it. */
internal class ReelBindingAnchors(
    val complete: MutableMethod,
    val read: PlayerRead,
    val field: FieldReference,
    val writes: List<Pair<MutableMethod, List<PlayerWrite>>>,
)

/**
 * Both anchors, read from the methods as they are now, after reelLoopPatch's hook. A constructor's
 * write is left out: there the controller may not be built yet, and a just-built controller holds
 * no player worth reporting. Throws, naming what's missing, before anything changes.
 */
internal fun BytecodePatchContext.findReelBindingAnchors(): ReelBindingAnchors {
    fun refuse(message: String): Nothing = throw PatchException("$BINDING: $message")
    if (classDefByOrNull(REEL_BINDING) == null) refuse("the extension has no $REEL_BINDING")

    val holders = classDefByStrings(VIDEO_COMPLETE_ANCHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val completes = videoCompletes(holders)
    val found = completes.singleOrNull()
        ?: refuse("expected one method holding \"$VIDEO_COMPLETE_ANCHOR\", found ${completes.size}")
    val listener = mutableClassDefBy(found.definingClass)
    val complete = listener.findMutableMethodOf(found)
    val read = playerRead(listener, complete)
        ?: refuse("${found.definingClass} keeps no single player that ${found.name} reads from this")

    val controllers = autoAdvanceControllers(
        classDefByStrings(AUTO_ADVANCE_IDLE, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type },
    )
    val controller = controllers.singleOrNull()
        ?: refuse("expected one class holding \"$AUTO_ADVANCE_IDLE\" and \"$DID_AUTO_ADVANCE\", found ${controllers.size}")
    val field = boundPlayerField(controller, read.field.type)
        ?: refuse("${controller.type} keeps no single ${read.field.type}")

    val writers = mutableListOf<Pair<ClassDef, Method>>()
    // A writer puts the field, which names its class.
    classDefForEachReferencing(types = listOf(field.definingClass)) { candidate ->
        if (candidate.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        candidate.methods.filter { method ->
            method.name != "<init>" && method.implementation?.instructions?.any { writes(it, field) } == true
        }.mapTo(writers) { candidate to it }
    }
    if (writers.isEmpty()) refuse("nothing outside a constructor sets ${field.definingClass}->${field.name}")
    val writes = writers.map { (owner, method) ->
        val mutable = mutableClassDefBy(owner.type).findMutableMethodOf(method)
        mutable to playerWrites(mutable, field)
    }
    writes.flatMap { it.second }.firstOrNull { it.player > 15 || it.controller > 15 }?.let {
        refuse("a write of ${field.definingClass}->${field.name} has the player in v${it.player} and the controller in v${it.controller}; the hook needs both below v16")
    }
    return ReelBindingAnchors(complete, read, field, writes)
}

/**
 * Hands the extension the end-of-video step's player right after it's read, by range so any
 * register fits, and each write's controller and player right after the write. Both read only:
 * nothing moves, nothing else changes and no register is added. A label on the instruction after
 * either stays there, so a jump to it passes the hook by, and only a path that didn't run the read
 * or the write jumps there.
 */
internal fun applyReelBindingAnchors(anchors: ReelBindingAnchors) {
    val player = anchors.read.register
    anchors.complete.addInstruction(anchors.read.at + 1, "invoke-static/range { v$player .. v$player }, $BINDING_ENDED")
    for ((method, writes) in anchors.writes) {
        writes.asReversed().forEach { write ->
            method.addInstruction(write.at + 1, "invoke-static { v${write.controller}, v${write.player} }, $BINDING_BOUND")
        }
    }
}

/**
 * With Debug logging on, the diagnostic report says whether a reel that ends is on the player the
 * Reels auto-advance controller holds (see the extension's ReelBinding), the evidence an Auto-next
 * switch is waiting on. Nameless, so Clean up Reels carries it, after its loop hook in the same
 * end-of-video step. It only watches. A build missing one of its anchors goes on without it, with
 * a warning, so Clean up Reels' own switches still apply.
 */
internal val reelBindingPatch = patch {
    dependsOn(facebookExtensionPatch, reelLoopPatch)

    executeBytecode {
        val anchors = try {
            findReelBindingAnchors()
        } catch (missing: Exception) {
            // A missing anchor is a PatchException with its reason; anything else is a bug, named as one.
            val why = if (missing is PatchException) missing.message else "$BINDING: ${missing::class.java.name}: ${missing.message}"
            patchLog.warning("$why. Debug reports go on without the reel binding lines.")
            return@executeBytecode
        }
        applyReelBindingAnchors(anchors)
    }
}
