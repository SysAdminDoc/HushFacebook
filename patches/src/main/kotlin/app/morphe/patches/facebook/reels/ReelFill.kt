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
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/*
 * Fill the screen with reels (#453). Read from 587 (arm64 and armv7), 2026-11-13; the obfuscated
 * names here are for reviewers, the code never writes one down.
 *
 * - One static helper (arm64 LX/09Jf;->A02, armv7 LX/09JS;->A02), traced as DERIVE_SIZE, works out
 *   how big a video should be in its container: it takes the video's aspect ratio, the container's
 *   width and height, a scale mode and a flag, and answers with the video's size.
 * - Its mode 2 fits the video inside the container, leaving bars above and below a 9:16 reel on a
 *   taller screen. Mode 1 fills it, cropping the sides, unless the flag is set. Mode 3 keeps the
 *   container's size. The Reels tab and the full-screen viewers ask for mode 2.
 * - Three places ask it on 587 (arm64): the method that sizes a player view (LX/08QI;->A00, traced as
 *   VideoResizeUtils.updateSizeAndTranslation), the reel video's binder (LX/04C0;->A0B), and the
 *   video surface's own resize of its SurfaceControl (LX/08jI;->A0E). The first hook went in front of
 *   the player view's call only, so closing a reel's comment sheet, which resizes the surface, sized
 *   the picture to fit again inside a view that still filled the screen, and the bars came back
 *   above and below it until the reel was swiped away (#456).
 * - The hook goes first thing in the helper: the extension is asked with the helper's own six
 *   parameter registers and, on a yes, the mode becomes 1 and the flag 0, so every caller gets the
 *   same answer.
 */

internal const val REEL_FILL = "$EXTENSION_PACKAGE/reels/ReelFill;"
/** The flag goes in as an int, read non-zero as set, so the hook takes the boolean register as it is. */
internal const val REEL_FILL_FILLS = "$REEL_FILL->fills(DIIII)Z"

/** Kept literal. Facebook's trace section for working out a video's size in its container. */
internal const val DERIVE_SIZE = "VideoResizeUtils.deriveSizeWithAspectRatio"

private const val PATCH = "Clean up Reels"

private val SIZE_PARAMETERS = listOf("D", "I", "I", "I", "Z")

/** The helper's parameters take six registers: two for the aspect, one each for the rest. */
internal const val SIZE_PARAMETER_REGISTERS = 6

private fun refuseFill(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.label() = "$definingClass->$name"

/** Whether [method] has the size helper's shape: static `(DIIIZ)[I`. */
internal fun isSizeHelper(method: Method) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "[I" &&
        method.parameterTypes.map(CharSequence::toString) == SIZE_PARAMETERS

/**
 * The first register of [helper]'s parameters, the aspect's; the mode is four past it and the flag
 * five. v0 takes the extension's answer, so the helper needs a register of its own below them, and
 * the flag has to sit low enough for a const to set it. Throws, naming what differs, otherwise.
 */
internal fun reelFillFirst(helper: Method): Int {
    val registers = helper.implementation?.registerCount ?: refuseFill("${helper.label()} has no body")
    val first = registers - SIZE_PARAMETER_REGISTERS
    if (first < 1) refuseFill("${helper.label()} has no register of its own for the answer")
    if (first + 5 > 255) refuseFill("${helper.label()}'s flag sits at v${first + 5}, too high to set")
    return first
}

/**
 * Fill the screen with reels, the twenty-eighth switch: the size helper asks the extension first
 * thing, and a yes has it fill the container instead of fitting the video inside it, whoever asked.
 * Refuses before anything changes.
 */
internal fun BytecodePatchContext.fillReels() {
    val owners = classDefByStrings(DERIVE_SIZE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val helpers = owners.flatMap { owner -> owner.methods.filter { isSizeHelper(it) && holdsString(it, DERIVE_SIZE) } }
    val helper = helpers.singleOrNull()
        ?: refuseFill("expected one static (DIIIZ)[I holding \"$DERIVE_SIZE\", found ${helpers.map { it.label() }}")
    val first = reelFillFirst(helper)
    val method = mutableClassDefBy(helper.definingClass).findMutableMethodOf(helper)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$first .. v${first + 5} }, $REEL_FILL_FILLS
            move-result v0
            if-eqz v0, :size
            const/16 v${first + 4}, 0x1
            const/16 v${first + 5}, 0x0
        """,
        ExternalLabel("size", method.getInstruction(0)),
    )
}
