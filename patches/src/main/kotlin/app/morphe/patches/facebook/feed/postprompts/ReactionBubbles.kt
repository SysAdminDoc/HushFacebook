/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.reels.returnNullWhen
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/*
 * The friend bubbles over a post's photo or video (#483). Read from 587, 2026-11-17, arm64 and
 * armv7; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * Facebook lays a row of bubbles over feed media: each friend who reacted, as their profile
 * picture with their reaction on it, plus your own repost bubble. One Litho component draws the row
 * (arm64 LX/04eh;, armv7 LX/04eO;). ReactionsBelowCaptionPlugin is one of the five places that
 * build it. Its render lays the bubbles out (one component each, arm64 LX/08Tb;, which names
 * "fb_reactions_overlay") and is the only method that loads "_collapse_repost_bubble_row". It
 * already answers null for a post with nothing to show, so an answer of null at its start leaves the
 * media as Facebook draws it without bubbles. The Reels tab's own bubbles are another component,
 * which Clean up Reels' comment and reaction previews switch keeps from loading.
 */

/** The key the bubble row's render builds for its collapsing repost row. No other method loads it. */
internal const val BUBBLE_ROW = "_collapse_repost_bubble_row"

internal const val HIDE_REACTION_BUBBLES = "$POST_PROMPTS->hideReactionBubbles()Z"

/** The loads of a narrow literal into one register a render can use for its null. */
private val NULL_LOADS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/**
 * Whether [method] is a render that already answers null on some path: it loads 0 into a register
 * and returns that same register somewhere. The hook's null is then an answer its caller already
 * handles. The load is a const/4, or a const/16 when the register is past v15 (588's store-less
 * build keeps its null in v16).
 */
internal fun answersNullItself(method: Method): Boolean {
    val code = method.implementation?.instructions?.toList() ?: return false
    val zeroed = code.filter { it.opcode in NULL_LOADS && (it as NarrowLiteralInstruction).narrowLiteral == 0 }
        .map { (it as OneRegisterInstruction).registerA }.toSet()
    return code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in zeroed }
}

/** The bubble row's render: the one method loading [BUBBLE_ROW]. Changes nothing. */
internal fun BytecodePatchContext.findBubbleRow(): Method {
    val holders = classDefByStrings(BUBBLE_ROW, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val renders = holders.flatMap { methodsHolding(it, BUBBLE_ROW) }
    val render = renders.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method naming $BUBBLE_ROW, found ${renders.size}")
    if (render.name != "render" || !render.returnType.startsWith("L") || !answersNullItself(render)) {
        throw PatchException(
            "$PATCH: ${render.definingClass}->${render.name} isn't a render that already answers null",
        )
    }
    return render
}

/** Has the bubble row's render answer null first thing while the switch is on. */
internal fun BytecodePatchContext.hideReactionBubbles() {
    val render = findBubbleRow()
    mutableClassDefBy(render.definingClass).findMutableMethodOf(render).returnNullWhen(HIDE_REACTION_BUBBLES, PATCH)
}
