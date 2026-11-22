/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * Where a video starts over at its end, read from 581 on 2026-10-09. The obfuscated names here are
 * for reviewers; the code finds each by a kept string, a kept class and the params' debug dump.
 *
 * FbGrootPlayer's listener (581 LX/5FE;->EFu(J)V, the one method holding "Unable to
 * grootPlayer.onVideoComplete") runs when a video reaches its end. A live video completes. Any
 * other one, while the player is playing, has its VideoPlayerParams read: first
 * shouldPreventExcessiveLooping, then shouldLoopVideo (581 A2L). With shouldLoopVideo set and the
 * loop count under the params' limit, the player seeks back to the start and plays again. Without
 * it, the player takes its completion path: it tells its listeners the video completed and the
 * video stays on its last frame. Facebook takes that same path when it stops a reel that has looped
 * too often.
 *
 * The hook goes right after the shouldLoopVideo read, on the same register, with the params'
 * register beside it. The extension answers no for a reel, which the params' isFbShorts (581 A1e)
 * marks, while the switch is on, and Facebook's own answer for everything else. The params' one
 * public isFbShorts field is read through a stub the patch fills, as Tap to play's reel check is.
 */

/** The extension class the hook asks. */
internal const val REEL_LOOP = "$EXTENSION_PACKAGE/reels/ReelLoop;"
internal const val LOOPS = "$REEL_LOOP->loops(ZLjava/lang/Object;)Z"

/** The extension's stub the patch fills with the params' isFbShorts read. */
internal const val REEL_LOOP_FB_SHORTS = "fbShorts"

/** A log string only FbGrootPlayer's end-of-video step holds. */
internal const val VIDEO_COMPLETE_ANCHOR = "Unable to grootPlayer.onVideoComplete"

/** The names VideoPlayerParams' debug dump reports the two flags under. */
internal const val SHOULD_LOOP_VIDEO = "shouldLoopVideo"
internal const val IS_FB_SHORTS = "isFbShorts"

private const val OBJECT = "Ljava/lang/Object;"

private const val PATCH = "Clean up Reels (play reels once)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The end-of-video step's loop check answers through the extension's ReelLoop. Nameless, so Clean
 * up Reels carries it and its fixture test runs it on its own. Refuses unless the params report
 * each flag from one public boolean, and the one method holding the completion string reads
 * shouldLoopVideo once.
 */
internal val reelLoopPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        hookReelLoops()
    }
}

/** One read of the loop flag: the index of its `iget-boolean`, the register it lands in and the params' register. */
internal data class LoopRead(val at: Int, val answer: Int, val params: Int)

/** Each `iget-boolean` of [field] in [method]. */
internal fun loopReads(method: Method, field: FieldReference): List<LoopRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.withIndex().mapNotNull { (at, instruction) ->
        if (instruction.opcode != Opcode.IGET_BOOLEAN) return@mapNotNull null
        val read = (instruction as ReferenceInstruction).reference as FieldReference
        if (read.definingClass != field.definingClass || read.name != field.name || read.type != field.type) {
            return@mapNotNull null
        }
        val registers = instruction as TwoRegisterInstruction
        LoopRead(at, registers.registerA, registers.registerB)
    }
}

/**
 * The field [params]' debug dumps report [name] from, when it's one public instance boolean of
 * [params] itself, or null.
 */
internal fun reportedFlag(params: ClassDef, name: String): FieldReference? {
    val fields = params.methods.filter { holdsString(it, name) }
        .mapNotNull { reportedValues(it)[name] }.distinctBy(FieldReference::toString)
    val field = fields.singleOrNull() ?: return null
    val declared = params.fields.singleOrNull {
        it.name == field.name && it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return null
    return if (field.definingClass == params.type && AccessFlags.PUBLIC.isSet(declared.accessFlags)) field else null
}

/**
 * Why the hook can't go after [read], or null when it can. It hands the extension the flag and the
 * params from the registers they're in straight after the read, so both have to be below v16, and
 * the read can't have landed the flag on the register the params were in: `iget-boolean v5, v5`
 * leaves no params to hand over, and passing the flag as the params' object is a VerifyError that
 * stops Facebook's player class from loading at all.
 */
internal fun loopHookRefusal(read: LoopRead): String? = when {
    read.answer > 15 || read.params > 15 ->
        "the loop flag is in v${read.answer} and the params in v${read.params}; the hook needs both below v16"
    read.answer == read.params ->
        "the loop flag is read into v${read.answer}, the register that held the params, so the hook can't hand them over"
    else -> null
}

/** The methods of [holders] holding the completion string. */
internal fun videoCompletes(holders: List<ClassDef>): List<Method> =
    holders.flatMap { it.methods }.filter { holdsString(it, VIDEO_COMPLETE_ANCHOR) }

internal fun BytecodePatchContext.hookReelLoops() {
    val params = classDefByOrNull(VIDEO_PLAYER_PARAMS) ?: refuse("this build has no $VIDEO_PLAYER_PARAMS")
    // The stub reads the params from the extension's package.
    if (!AccessFlags.PUBLIC.isSet(params.accessFlags)) refuse("$VIDEO_PLAYER_PARAMS isn't public")
    val loop = reportedFlag(params, SHOULD_LOOP_VIDEO)
        ?: refuse("expected $VIDEO_PLAYER_PARAMS to report $SHOULD_LOOP_VIDEO from one public boolean field")
    val shorts = reportedFlag(params, IS_FB_SHORTS)
        ?: refuse("expected $VIDEO_PLAYER_PARAMS to report $IS_FB_SHORTS from one public boolean field")

    val holders = classDefByStrings(VIDEO_COMPLETE_ANCHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val completes = videoCompletes(holders)
    val complete = completes.singleOrNull()
        ?: refuse("expected one method holding \"$VIDEO_COMPLETE_ANCHOR\", found ${completes.size}")
    val reads = loopReads(complete, loop)
    val read = reads.singleOrNull()
        ?: refuse("expected one read of $SHOULD_LOOP_VIDEO in ${complete.definingClass}->${complete.name}, found ${reads.size}")
    loopHookRefusal(read)?.let(::refuse)

    if (classDefByOrNull(REEL_LOOP) == null) refuse("the extension has no $REEL_LOOP")
    val stub = mutableClassDefBy(REEL_LOOP).methods.singleOrNull {
        it.name == REEL_LOOP_FB_SHORTS && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
    } ?: refuse("$REEL_LOOP has no static Z $REEL_LOOP_FB_SHORTS($OBJECT)")
    stub.addInstructions(
        0,
        """
            check-cast p0, $VIDEO_PLAYER_PARAMS
            iget-boolean p0, p0, $VIDEO_PLAYER_PARAMS->${shorts.name}:Z
            return p0
        """,
    )

    mutableClassDefBy(complete.definingClass).findMutableMethodOf(complete).addInstructions(
        read.at + 1,
        """
            invoke-static { v${read.answer}, v${read.params} }, $LOOPS
            move-result v${read.answer}
        """,
    )
}
