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
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Fill the screen with reels (#453). Read from 587 (arm64 and armv7), 2026-11-13; the obfuscated
 * names here are for reviewers, the code never writes one down.
 *
 * - Every player view Facebook sizes goes through one static method (arm64 LX/08QI;->A00, armv7
 *   LX/08Q4;->A00), traced as UPDATE_SIZE and taking the view, its layout params, an alignment, an
 *   animation pair, the video's aspect ratio, the container's width and height, a scale mode and a
 *   flag. It asks the size helper (arm64 LX/09Jf;->A02, armv7 LX/09JS;->A02, traced as DERIVE_SIZE)
 *   for the view's size with one invoke-static/range of six registers: the aspect, the width, the
 *   height, the mode and the flag, the last two moved there from its own parameters. It then sets
 *   that size on the view's layout params and centers or places the view by the alignment.
 * - The helper's mode 2 fits the video inside the container, leaving bars above and below a 9:16
 *   reel on a taller screen. Mode 1 fills it, cropping the sides, unless the flag is set. Mode 3 keeps
 *   the container's size. The Reels tab and the full-screen viewers ask for mode 2.
 * - The hook goes right before that call: the extension is asked with the call's own six registers
 *   and, on a yes, the mode becomes 1 and the flag 0. Nothing reads either register after the call,
 *   so the change reaches the helper and nothing else.
 */

internal const val REEL_FILL = "$EXTENSION_PACKAGE/reels/ReelFill;"
/** The flag goes in as an int, read non-zero as set, so the hook takes whatever type its register holds. */
internal const val REEL_FILL_FILLS = "$REEL_FILL->fills(DIIII)Z"

/** Kept literal. Facebook's trace section for sizing a player view. */
internal const val UPDATE_SIZE = "VideoResizeUtils.updateSizeAndTranslation"

/** Kept literal. Facebook's trace section for working out a video's size in its container. */
internal const val DERIVE_SIZE = "VideoResizeUtils.deriveSizeWithAspectRatio"

private const val PATCH = "Clean up Reels"

private val SIZE_PARAMETERS = listOf("D", "I", "I", "I", "Z")

private fun refuseFill(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.label() = "$definingClass->$name"

/** Whether [method] has the size helper's shape: static `(DIIIZ)[I`. */
internal fun isSizeHelper(method: Method) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "[I" &&
        method.parameterTypes.map(CharSequence::toString) == SIZE_PARAMETERS

/** Whether [method] could be the sizing method: static, returning nothing, with a body. */
internal fun isSizing(method: Method) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.implementation != null

private fun MethodReference.isMethod(method: Method) = definingClass == method.definingClass &&
    name == method.name && returnType == method.returnType &&
    parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)

/** Where [fillReels] goes in, as [reelFillSite] found it. */
internal class ReelFillSite(
    /** The helper's call, where the hook goes in front. */
    val call: Int,
    /** The call's first register, the aspect's; the mode is four past it and the flag five. */
    val first: Int,
    /** A register nothing reads at the call, which takes the extension's answer. */
    val answer: Int,
)

/**
 * The one call in [method] to [helper]: an invoke-static/range of six registers that only the
 * instruction before it falls into, whose mode and flag registers nothing reads after it, with a
 * register below 256 free at the call for the extension's answer. Throws, naming what differs,
 * otherwise.
 */
internal fun reelFillSite(method: Method, helper: Method): ReelFillSite {
    val flow = try {
        ControlFlow.of(method)
    } catch (broken: IllegalArgumentException) {
        refuseFill("${method.label()} can't be read: ${broken.message}")
    }
    val code = flow.instructions
    val calls = code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_STATIC_RANGE && call?.isMethod(helper) == true
    }
    val call = calls.singleOrNull()
        ?: refuseFill("expected one invoke-static/range of ${helper.label()} in ${method.label()}, found ${calls.size}")
    val range = code[call] as RegisterRangeInstruction
    if (range.registerCount != 6) refuseFill("${method.label()} hands ${helper.label()} ${range.registerCount} registers, not 6")
    val first = range.startRegister
    if (first + 5 > 255) refuseFill("${method.label()}'s call at $call starts at v$first, too high to set its mode")

    val into = code.indices.filter { call in flow.normal[it] }
    if (into != listOf(call - 1)) refuseFill("${method.label()}'s call at $call is reached from $into, not only from ${call - 1}")
    if (code.indices.any { call in flow.exceptional[it] }) refuseFill("${method.label()}'s call at $call starts a handler")

    for (register in listOf(first + 4, first + 5)) {
        val reads = method.readsAfter(call, register)
        if (reads.isNotEmpty()) refuseFill("${method.label()} reads v$register at $reads after its call at $call")
    }

    val live = RegisterLiveness.of(method).liveInto(call)
    val registers = method.implementation!!.registerCount
    val answer = (0 until minOf(registers, 256)).firstOrNull { it !in live && it !in first..first + 5 }
        ?: refuseFill("${method.label()} has no register free at its call at $call")
    return ReelFillSite(call, first, answer)
}

/**
 * Fill the screen with reels, the twenty-eighth switch: the sizing method asks the extension before
 * it asks the size helper, and a yes asks the helper to fill the container instead of fitting the
 * video inside it. Refuses before anything changes.
 */
internal fun BytecodePatchContext.fillReels() {
    fun owners(literal: String) = classDefByStrings(literal, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val helpers = owners(DERIVE_SIZE).flatMap { owner -> owner.methods.filter { isSizeHelper(it) && holdsString(it, DERIVE_SIZE) } }
    val helper = helpers.singleOrNull()
        ?: refuseFill("expected one static (DIIIZ)[I holding \"$DERIVE_SIZE\", found ${helpers.map { it.label() }}")
    val sizings = owners(UPDATE_SIZE).flatMap { owner -> owner.methods.filter { isSizing(it) && holdsString(it, UPDATE_SIZE) } }
    val sizing = sizings.singleOrNull()
        ?: refuseFill("expected one static method holding \"$UPDATE_SIZE\", found ${sizings.map { it.label() }}")
    val method = mutableClassDefBy(sizing.definingClass).findMutableMethodOf(sizing)
    val site = reelFillSite(method, helper)
    val first = site.first
    method.addInstructionsWithLabels(
        site.call,
        """
            invoke-static/range { v$first .. v${first + 5} }, $REEL_FILL_FILLS
            move-result v${site.answer}
            if-eqz v${site.answer}, :size
            const/16 v${first + 4}, 0x1
            const/16 v${first + 5}, 0x0
        """,
        ExternalLabel("size", method.getInstruction(site.call)),
    )
}
