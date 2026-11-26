/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/*
 * Show feed reels uncropped (#581). Read from 588 (arm64), 2026-11-25; the obfuscated names here are
 * for reviewers, the code never writes one down.
 *
 * - A reel posted in News Feed is drawn by FbShortsFeedUnitVideoAttachmentComponent (LX/0B1Z), and
 *   its frame's shape, width over height, comes from one static helper in the class whose static
 *   initializer names that spec (LX/0B1d;->A00(FbUserSession, ?, ?, ?, F)F).
 * - Its last parameter is the video's own width over height, and the helper never writes it. The
 *   helper answers with the story's ratio under one test, and otherwise clamps the video's shape
 *   to a minimum and maximum Facebook's server sends, which turns a 9:16 reel into a wider frame and
 *   cuts its sides off. The frame's cover image and the portrait player both take the answer.
 * - The hook goes in front of each of the helper's returns: the extension gets Facebook's answer and
 *   the video's shape and hands back the shape to use.
 */

internal const val FEED_REEL_SHAPE = "$EXTENSION_PACKAGE/reels/FeedReelShape;"

/** Facebook's answer first, then the video's own shape; the answer to return comes back. */
internal const val FEED_REEL_SHAPE_HOOK = "$FEED_REEL_SHAPE->shape(FF)F"

/** Kept literal. The caller context name of the feed reel's video attachment component. */
internal const val FEED_REEL_SPEC = "FbShortsFeedUnitVideoAttachmentComponentSpec"

private const val PATCH = "Clean up Reels"

private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

private fun refuseShape(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.label() = "$definingClass->$name"

/** Whether [method] has the frame helper's shape: static `(FbUserSession, ?, ?, ?, F)F`. */
internal fun isFeedReelShaper(method: Method): Boolean {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    return AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "F" &&
        parameters.size == 5 && parameters.first() == USER_SESSION && parameters.last() == "F"
}

/** The frame helpers among [owners]: the classes that name [FEED_REEL_SPEC]. */
internal fun feedReelShapers(owners: Iterable<ClassDef>): List<Method> =
    owners.filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter(::isFeedReelShaper) }

/**
 * The register holding [helper]'s last parameter, the video's shape, which every return hands to
 * the extension. It has to stay the video's shape to the end and sit low enough for a plain invoke.
 * Throws, naming what differs, otherwise.
 */
internal fun feedReelVideoRegister(helper: Method): Int {
    val implementation = helper.implementation ?: refuseShape("${helper.label()} has no body")
    // Static, so the parameters fill the top registers, one each since none is wide.
    val video = implementation.registerCount - 1
    if (video > 15) refuseShape("${helper.label()} keeps the video's shape in v$video, past what a four-bit invoke reaches")
    val instructions = implementation.instructions.toList()
    if (instructions.any { writesRegister(it, video) }) refuseShape("${helper.label()} overwrites the video's shape in v$video")
    val returns = instructions.filter { it.opcode == Opcode.RETURN }
    if (returns.isEmpty()) refuseShape("${helper.label()} never returns")
    returns.forEach {
        val answer = (it as OneRegisterInstruction).registerA
        if (answer > 15) refuseShape("${helper.label()} returns v$answer, past what a four-bit invoke reaches")
    }
    return video
}

/**
 * Show feed reels uncropped, off by default: every return of the feed reel's frame helper hands
 * Facebook's shape and the video's own to the extension, which answers with the one to draw.
 * Refuses before anything changes.
 */
internal fun BytecodePatchContext.uncropFeedReels() {
    val helpers = feedReelShapers(classDefByStrings(FEED_REEL_SPEC, StringComparisonType.EQUALS))
    val helper = helpers.singleOrNull()
        ?: refuseShape("expected one static (FbUserSession, ?, ?, ?, F)F beside \"$FEED_REEL_SPEC\", found ${helpers.map { it.label() }}")
    val video = feedReelVideoRegister(helper)
    val method = mutableClassDefBy(helper.definingClass).findMutableMethodOf(helper)
    val returns = method.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
    for (index in returns.asReversed()) {
        val answer = method.getInstruction<OneRegisterInstruction>(index).registerA
        method.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$answer, v$video }, $FEED_REEL_SHAPE_HOOK
                move-result v$answer
            """,
        )
    }
}
