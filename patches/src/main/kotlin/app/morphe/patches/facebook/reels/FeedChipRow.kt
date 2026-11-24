/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The chip row under a video in the feed (#314: "Made with Edits" beside the poster's name on 588,
 * 0.24.0). Read from 588, 2026-11-24, arm64; the obfuscated names here are for reviewers, the code
 * never writes one down.
 *
 * - A feed post's footer (LX/01mZ;->A0X, handed the post's GraphQLFeedback) builds a component
 *   (LX/0aWa;) that makes the row (LX/0X3P;). The row's render keeps the video's attribution models
 *   in a state of its own, reads them back as an AbstractCollection and walks them, asking a label
 *   helper (LX/0Xtx;->A01) for each chip's text: "Made with Edits", the sound, Remix and the rest.
 * - The reel chip list builder the chips switch filters (see ReelAnchors.kt) never sees that list:
 *   the reel rows (LX/0X59;, LX/0X9W;) read theirs from a field it fills, and this one doesn't.
 * - Facebook's own check of whether a video gets the Edits pill (hooked since 0.24.0, see
 *   ReelEditsLabel.kt) doesn't touch this row either.
 * - The row names every hidden chip type and the sound's, like the reel rows, and it's the only one
 *   of them that casts its list out of a state value (a static call answering Object) and walks it
 *   right after. The list goes through the same filter as the reel chips there, so the switches
 *   hide the same chips on a feed video as on a reel.
 */

private const val PATCH = "Clean up Reels"
private const val ABSTRACT_COLLECTION = "Ljava/util/AbstractCollection;"
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/** How far past the cast the row may start walking the list. 588 walks it nine instructions on. */
private const val WALK_WITHIN = 24

private val Instruction.methodReference
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> emptyList()
}

/** Whether [instruction] puts something new in [register]. */
private fun overwrites(instruction: Instruction, register: Int): Boolean =
    instruction is OneRegisterInstruction && instruction.registerA == register && instruction.opcode.setsRegister()

/**
 * The index of the `check-cast` that turns [method]'s state value into the chip list it walks: a cast
 * to AbstractCollection of what a static call answering Object just returned, whose register then
 * gets `AbstractCollection.iterator()` before anything else goes in it. Null unless exactly one.
 */
internal fun feedChipListCast(method: Method): Int? {
    val body = method.implementation?.instructions?.toList() ?: return null
    return body.indices.filter { at ->
        val cast = body[at]
        if (cast.opcode != Opcode.CHECK_CAST) return@filter false
        if (((cast as ReferenceInstruction).reference as TypeReference).type != ABSTRACT_COLLECTION) return@filter false
        val list = (cast as OneRegisterInstruction).registerA
        val read = body.getOrNull(at - 1)
        val call = body.getOrNull(at - 2)
        if (read?.opcode != Opcode.MOVE_RESULT_OBJECT || (read as OneRegisterInstruction).registerA != list) return@filter false
        if (call == null || call.opcode !in STATIC_CALLS) return@filter false
        if (call.methodReference?.returnType != "Ljava/lang/Object;") return@filter false
        walksAfter(body, at, list)
    }.singleOrNull()
}

private fun walksAfter(body: List<Instruction>, cast: Int, list: Int): Boolean {
    for (at in cast + 1 until minOf(body.size, cast + 1 + WALK_WITHIN)) {
        val instruction = body[at]
        val call = instruction.methodReference
        if (call != null && call.name == "iterator" && call.definingClass == ABSTRACT_COLLECTION &&
            registersOf(instruction).firstOrNull() == list
        ) return true
        if (overwrites(instruction, list)) return false
    }
    return false
}

/**
 * Whether [method] is the feed video's chip row: a Litho `render` of one argument that names every
 * hidden chip type and the sound's (loaded, or asked of a string table [resolve] finds) and walks a
 * list it casts out of a state value ([feedChipListCast]).
 */
internal fun isFeedChipRow(method: Method, resolve: (MethodReference) -> Method?): Boolean {
    if (method.name != RENDER || AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.size != 1 ||
        !method.returnType.startsWith("L")
    ) return false
    if (!namesEvery(method, HIDDEN_CHIPS + SOUNDTRACK_CHIP, resolve)) return false
    return feedChipListCast(method) != null
}

/**
 * Right before the row casts its list, the list goes through the extension's chip filter. A null
 * answer keeps Facebook's list, and an array is rebuilt into an ImmutableList with Facebook's own
 * copyOf, which the cast takes as it took Facebook's. The answer goes in a register nothing reads
 * before writing it.
 */
internal fun MutableMethod.filterFeedChipList() {
    val at = feedChipListCast(this) ?: throw PatchException("$PATCH: $definingClass->$name casts no chip list to walk")
    val list = (getInstruction(at) as OneRegisterInstruction).registerA
    val answer = try {
        findFreeRegister(at, list)
    } catch (failure: IllegalArgumentException) {
        throw PatchException("$PATCH: $definingClass->$name has no free register beside v$list at $at", failure)
    }
    addInstructionsWithLabels(
        at,
        """
            invoke-static/range { v$list .. v$list }, $FILTER_CHIPS
            move-result-object v$answer
            if-eqz v$answer, :facebooks_chips
            invoke-static/range { v$answer .. v$answer }, $COPY_OF
            move-result-object v$list
            :facebooks_chips
            nop
        """,
    )
}

/** The feed video's chip row sends its list through the chip filter. Refuses unless exactly one row fits. */
internal fun BytecodePatchContext.filterFeedChipRow() {
    val tables = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val rows = classDefByStrings(CHIP_ANCHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter { isFeedChipRow(it, tables) }.map { owner.type to it } }
    val (owner, row) = rows.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one feed video chip row walking a list it casts from its state, found ${rows.size}",
    )
    mutableClassDefBy(owner).methods.single {
        it.name == row.name && it.returnType == row.returnType &&
            it.parameterTypes.map(CharSequence::toString) == row.parameterTypes.map(CharSequence::toString)
    }.filterFeedChipList()
}
