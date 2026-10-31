/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.reelspeed

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.findMutableMethodOf
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Speed on finished live videos (#238). The gear menu's rows are built by one static method of
 * the player's settings plugin (0dSr.A00 on 585 arm64, 0dSD.A00 on armeabi-v7a), the one method
 * holding ENGAGEMENT_STATE that casts to the speed row's click handler, the class holding
 * GEAR_SPEED_TAP. Its Playback speed row is always there for the inline and Watch players. For the
 * full-screen player, the one a live video and its replay open in, a video that is live gets its
 * own test, and one that isn't gets the row only when a MobileConfig flag is on or the unified
 * player's engagement state is set:
 *
 *     if-nez vFlag, :speedRow       # the MobileConfig flag, read or cached
 *     if-eqz vEngaged, :noSpeedRow  # the engagement state
 *     goto/16 :speedRow
 *
 * Facebook's live player passes neither, so a live video that has ended shows no speed row. The
 * hook goes in front of the if-eqz, which only that if-nez falls into: Facebook's own yes still
 * jumps first, and otherwise the extension is asked with the video's isSponsored and a yes jumps to
 * the row, which opens Facebook's own speed sheet.
 */

/** Kept literal. The speed row's click handler logs a tap under it. */
internal const val GEAR_SPEED_TAP = "video_player_control_gear_playback_speed_tap"

/** Kept literal. The gear menu's builder compares the unified player's state with it. */
internal const val ENGAGEMENT_STATE = "UNIFIED_PLAYER_VDD_ENGAGEMENT_STATE"

internal const val OFFER_SPEED = "$EXTENSION_PACKAGE/media/ReplaySpeed;->offerSpeed(I)Z"

private fun refuseReplay(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Where [offerReplaySpeed] goes in, as [replaySpeedSite] found it. All are register numbers but the two indexes. */
internal class ReplaySpeedSite(
    /** The if-eqz on the engagement state, where the hook goes in front. */
    val test: Int,
    /** The speed row's first instruction, where Facebook's yes and the hook's yes jump. */
    val speedRow: Int,
    /** The video's isSponsored, read by the speed row too. */
    val sponsored: Int,
    /** The engagement state the if-eqz tests. */
    val engaged: Int,
    /** The MobileConfig flag's register, which nothing reads after the test: the hook takes the extension's answer in it. */
    val scratch: Int,
)

private val ROW_GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
private val SPONSORED_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

private fun Method.label() = "$definingClass->$name"

private fun sameField(read: FieldReference, field: FieldReference) =
    read.definingClass == field.definingClass && read.name == field.name && read.type == field.type

/**
 * The engagement test in [method], the gear menu's builder: an if-nez to the speed row, then the
 * if-eqz on the engagement state, then a goto to the same row, where the row opens with a call
 * whose result is cast to [rowType] and then tests the video's isSponsored ([sponsoredField]).
 * Only the if-nez may come into the if-eqz, the if-nez's register must be free there, and every
 * value the isSponsored register can hold there must be read from [sponsoredField], straight or
 * through one move. Throws, naming what differs, otherwise.
 */
internal fun replaySpeedSite(method: Method, rowType: String, sponsoredField: FieldReference): ReplaySpeedSite {
    val flow = try {
        ControlFlow.of(method)
    } catch (broken: IllegalArgumentException) {
        refuseReplay("${method.label()} can't be read: ${broken.message}")
    }
    val code = flow.instructions
    fun target(index: Int) = flow.normal[index].first()
    fun register(index: Int) = (code[index] as OneRegisterInstruction).registerA

    val tests = (0 until code.size - 2).filter { at ->
        code[at].opcode == Opcode.IF_NEZ && code[at + 1].opcode == Opcode.IF_EQZ && code[at + 2].opcode in ROW_GOTOS &&
            target(at) == target(at + 2) && target(at + 1) != target(at)
    }
    val rows = tests.filter { at ->
        val row = target(at)
        row + 3 < code.size &&
            code[row + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[row + 2].opcode == Opcode.CHECK_CAST && register(row + 2) == register(row + 1) &&
            ((code[row + 2] as ReferenceInstruction).reference as TypeReference).type == rowType &&
            code[row + 3].opcode == Opcode.IF_NEZ
    }
    val at = rows.singleOrNull() ?: refuseReplay(
        "expected one if-nez, if-eqz, goto to a row cast to $rowType in ${method.label()}, found ${rows.size} " +
            "(${tests.size} without the row)",
    )
    val test = at + 1
    val speedRow = target(at)

    val into = code.indices.filter { test in flow.normal[it] }
    if (into != listOf(at)) refuseReplay("${method.label()}'s engagement test at $test is reached from $into, not only from ${at}")
    if (code.indices.any { test in flow.exceptional[it] }) refuseReplay("${method.label()}'s engagement test at $test starts a handler")

    val engaged = register(test)
    val scratch = register(at)
    val sponsored = register(speedRow + 3)
    val live = RegisterLiveness.of(method).liveInto(test)
    if (scratch == engaged || scratch == sponsored || scratch in live) {
        refuseReplay("${method.label()}'s v$scratch is still read after the engagement test at $test")
    }

    fun readsSponsored(writer: Int): Boolean {
        if (writer < 0) return false
        val instruction = code[writer]
        if (instruction.opcode == Opcode.IGET_BOOLEAN) {
            return sameField((instruction as ReferenceInstruction).reference as FieldReference, sponsoredField)
        }
        return false
    }
    val writers = method.writersReaching(test, sponsored)
    val fed = writers.isNotEmpty() && writers.all { writer ->
        if (readsSponsored(writer)) return@all true
        if (writer < 0 || code[writer].opcode !in SPONSORED_MOVES) return@all false
        val sources = method.writersReaching(writer, (code[writer] as TwoRegisterInstruction).registerB)
        sources.isNotEmpty() && sources.all(::readsSponsored)
    }
    if (!fed) refuseReplay("${method.label()}'s v$sponsored at $test isn't always $sponsoredField, written at $writers")

    return ReplaySpeedSite(test, speedRow, sponsored, engaged, scratch)
}

/** The speed row's click handler types the gear menu's builder casts to in [method]. */
private fun rowCasts(method: Method, rowTypes: Set<String>): List<String> =
    method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.CHECK_CAST }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .filter { it in rowTypes }
        .distinct()

/**
 * Speed on finished live videos, the fourth switch: the gear menu of a full-screen video that isn't
 * live asks the extension before it leaves Playback speed out. [sponsoredField] is
 * VideoPlayerParams' isSponsored, which the anchors found in the params' debug dump. Refuses before
 * anything changes.
 */
internal fun BytecodePatchContext.offerReplaySpeed(sponsoredField: FieldReference) {
    val rowTypes = classDefByStrings(GEAR_SPEED_TAP, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { classDef -> classDef.methods.any { holdsString(it, GEAR_SPEED_TAP) } }
        .map { it.type }
        .toSet()
    val builders = classDefByStrings(ENGAGEMENT_STATE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, ENGAGEMENT_STATE) } }
        .flatMap { method -> rowCasts(method, rowTypes).map { method to it } }
    val (builder, rowType) = builders.singleOrNull() ?: refuseReplay(
        "expected one method holding \"$ENGAGEMENT_STATE\" that casts to a class holding \"$GEAR_SPEED_TAP\", " +
            "found ${builders.map { (method, row) -> "${method.label()} to $row" }}",
    )
    val method = mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder)
    val site = replaySpeedSite(method, rowType, sponsoredField)
    method.addInstructionsWithLabels(
        site.test,
        """
            if-nez v${site.engaged}, :speed
            invoke-static/range { v${site.sponsored} .. v${site.sponsored} }, $OFFER_SPEED
            move-result v${site.scratch}
            if-nez v${site.scratch}, :speed
        """,
        ExternalLabel("speed", method.getInstruction(site.speedRow)),
    )
}
