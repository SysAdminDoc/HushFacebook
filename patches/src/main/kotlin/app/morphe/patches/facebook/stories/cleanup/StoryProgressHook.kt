/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

/*
 * The playing story's progress, for the bar Clean up the story viewer draws along the bottom of a
 * story (#380). Read from 585, 2026-11-04, arm64; the obfuscated names here are for reviewers, the
 * code never writes one down.
 *
 * - Facebook moves the segments at the top of a story from a progress listener (LX/0DdD;) it hands
 *   the bucket, the card and the progress, 0 to 1000. Two listeners move segments, and each writes
 *   "StoryViewerProgressBar.onProgressUpdated" into its trace when it's handed a card past its
 *   segments. LX/0C7w; moves a view of segments through an interface (LX/0DhC;->JYY(II)V, the card's
 *   index, then the progress), and LX/0DSP; a holder (LX/0nDd;->A00(F)V, the progress over 1000)
 *   whose one field is the segments drawable (LX/0mKV;).
 * - Under a server flag both hand their segments 1000, a full segment, in place of the progress, so
 *   the bar takes the listener's own progress argument rather than what the segments get. Neither
 *   listener writes over that argument on the way to its segments (586's LX/0efg; reuses it on the
 *   trace's branch only).
 * - Each listener's call to its segments is followed by its return-void, which only that call
 *   reaches, so the hook goes in between: it sees the progress only for the card the segments show,
 *   after Facebook's own checks, and no register but the segments holds anything wanted there.
 */

internal const val PROGRESS_TRACE = "StoryViewerProgressBar.onProgressUpdated"
internal const val STORY_PROGRESS_BAR = "$EXTENSION_PACKAGE/stories/StoryProgressBar;"
internal const val SEGMENTS_MOVED = "$STORY_PROGRESS_BAR->segmentsMoved(Ljava/lang/Object;I)V"
internal const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"

/**
 * Where a progress listener moves its segments: the call at [index], the register holding the
 * segments or their holder, and for a holder the field that keeps the segments drawable.
 */
internal class SegmentsMove(val index: Int, val register: Int, val drawable: FieldReference?)

/** Whether [type] is a Drawable, going up its superclasses through [classDef]. */
internal fun isDrawable(type: String, classDef: (String) -> ClassDef?): Boolean {
    var at = type
    repeat(20) {
        if (at == DRAWABLE) return true
        at = classDef(at)?.superclass ?: return false
    }
    return false
}

/**
 * [method]'s one move of its segments: a call through an interface taking two ints, or a call on
 * a holder taking the progress as a float, followed by the method's return-void, neither inside a
 * try (its handler could read the registers the hook borrows). A holder has to keep the segments in
 * the one Drawable among its instance fields, one [method]'s class may read. Null when that isn't so.
 */
internal fun segmentsMove(method: Method, classDef: (String) -> ClassDef?): SegmentsMove? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val moves = code.withIndex().filter { (_, instruction) ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        val parameters = reference?.parameterTypes?.map(CharSequence::toString)
        reference != null && reference.returnType == "V" &&
            (instruction.opcode == Opcode.INVOKE_INTERFACE && parameters == listOf("I", "I") ||
                instruction.opcode == Opcode.INVOKE_VIRTUAL && parameters == listOf("F"))
    }
    val (index, call) = moves.singleOrNull() ?: return null
    if (code.getOrNull(index + 1)?.opcode != Opcode.RETURN_VOID) return null
    val moveAt = code.take(index).sumOf { it.codeUnits }
    val returnAt = moveAt + call.codeUnits
    if (method.implementation!!.tryBlocks.any { moveAt < it.startCodeAddress + it.codeUnitCount && returnAt >= it.startCodeAddress }) {
        return null
    }
    val register = (call as FiveRegisterInstruction).registerC
    if (call.opcode == Opcode.INVOKE_INTERFACE) return SegmentsMove(index, register, null)
    val holder = classDef(((call as ReferenceInstruction).reference as MethodReference).definingClass) ?: return null
    val field = holder.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && isDrawable(it.type, classDef) }
        .singleOrNull() ?: return null
    val samePackage = holder.type.substringBeforeLast('/') == method.definingClass.substringBeforeLast('/')
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags) && (AccessFlags.PRIVATE.isSet(field.accessFlags) || !samePackage)) return null
    return SegmentsMove(index, register, ImmutableFieldReference(holder.type, field.name, field.type))
}

/** The register of [method]'s last argument, an int: the top one, since no wide argument follows it. */
internal fun lastArgument(method: Method): Int = method.implementation!!.registerCount - 1

/**
 * Whether [register] still holds what [method] was handed when the instruction at [at] starts, on
 * every way there. 586's LX/0efg; reuses its progress register for the trace's arguments, but only
 * on the branch that logs and returns, never on the one that moves the segments.
 */
internal fun keeps(method: Method, register: Int, at: Int): Boolean = method.writersReaching(at, register) == listOf(-1)

/**
 * The progress listeners: each method writing [PROGRESS_TRACE] ([methodsNaming]), with the class
 * it's in. 585's Android 9 build moves the trace into a string table (LX/0Akj;) that one of its two
 * listeners (LX/0bWy;) asks, so a listener is a holder or a caller of a table holding it, never the
 * table itself.
 */
internal fun BytecodePatchContext.progressListeners(): List<Pair<ClassDef, Method>> =
    methodsNaming(PROGRESS_TRACE) { !isStringTable(it) }.distinct()
        .map { method -> classDefBy(method.definingClass) to method }

/** Hands the extension each listener's segments and progress, right after the listener moves them. */
internal fun BytecodePatchContext.followStoryProgress() {
    val listeners = progressListeners()
    if (listeners.isEmpty()) refuse("no progress listener writes $PROGRESS_TRACE")
    for ((listener, method) in listeners) {
        val where = "${listener.type}->${method.name}"
        if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags) ||
            method.parameterTypes.lastOrNull()?.toString() != "I"
        ) {
            refuse("$where isn't an instance method taking the progress last and answering nothing")
        }
        val move = segmentsMove(method) { classDefByOrNull(it) }
            ?: refuse("$where moves its segments other than once right before it returns, inside a try, or from a field it can't read")
        val progress = lastArgument(method)
        if (!keeps(method, progress, move.index + 1)) refuse("$where writes over its progress argument before moving its segments")
        val read = move.drawable?.let { "iget-object v0, v0, ${it.definingClass}->${it.name}:${it.type}" } ?: ""
        val mutable = mutableClassDefBy(listener.type).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }
        mutable.requireLocals(STORY_VIEWER_CLEANUP_PATCH, 2)
        mutable.addInstructions(
            move.index + 1,
            """
                move-object/from16 v0, v${move.register}
                $read
                move/from16 v1, v$progress
                invoke-static { v0, v1 }, $SEGMENTS_MOVED
            """,
        )
    }
}
