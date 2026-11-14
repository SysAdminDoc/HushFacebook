/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.treeFieldKey
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/*
 * The X at the top of a feed post, next to its three-dot menu (#389). Read from 585, 2026-11-05,
 * arm64; the obfuscated names are for reviewers only.
 *
 * - One check decides whether a story's header gets the X: an instance method, (props, env) -> Z,
 *   on the companion of the story's easy-hide helper (585 `LX/02zX;->A02`, the companion held in
 *   `LX/02zW;->A0A`). The helper names itself in its logging method with the literal
 *   "biz_disco_feed_story_easy_hide". The check reads the story's should_show_easy_hide flag (its
 *   tree field key) and then a few feed conditions.
 * - The header plugin's needed check (`LX/02zP;->H71`) and the header builder (`LX/02mf;->A0F`)
 *   both ask it, so a no leaves the header as Facebook draws it for a post with no X, with the
 *   three-dot menu in the corner. Its answers go through the extension like the follow text check's.
 */

internal const val EASY_HIDE_TAG = "biz_disco_feed_story_easy_hide"
internal const val EASY_HIDE_FIELD = "should_show_easy_hide"
internal const val SHOW_POST_X = "$POST_PROMPTS->showPostX(I)Z"

/** Whether [method] is the X check: an instance method taking two arguments, answering Z, reading the easy-hide flag. */
internal fun isPostXCheck(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" || method.parameterTypes.size != 2) {
        return false
    }
    val key = treeFieldKey(EASY_HIDE_FIELD)
    return method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } == true
}

/** The X check, on the companion a static field of the easy-hide helper holds. Changes nothing. */
internal fun BytecodePatchContext.findPostXCheck(): Method {
    val helpers = classDefByStrings(EASY_HIDE_TAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
    val companions = helpers.flatMap { helper -> helper.staticFields.mapNotNull { classDefByOrNull(it.type) } }
        .distinctBy { it.type }
    val checks = companions.flatMap { it.methods.filter(::isPostXCheck) }
    return checks.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one (props, env) -> Z check reading \"$EASY_HIDE_FIELD\" beside \"$EASY_HIDE_TAG\", " +
            "found ${checks.size} in ${helpers.size} helper(s)",
    )
}

/*
 * The X in the header of the Reels row in the feed, next to its three-dot menu (#389). Read from 587,
 * 2026-11-14, arm64; the obfuscated names are for reviewers only.
 *
 * - The header names itself "ShowcaseFbShortsHeaderComponent" in its constructor (587 `LX/08L8`).
 *   It doesn't go through the easy-hide check above, which is why the posts' switch missed it.
 * - Its render builds the menu, then adds the X (`LX/05oQ`, whose tap runs Facebook's hide flow,
 *   `LX/03C5;->A03`) only when the row's hide handler is the kind that can hide it from the feed: the
 *   method's one instance-of. A no there leaves the slot empty, so the header keeps its title and menu.
 * - That instance-of's answer goes through the extension on its own register, under the same switch.
 *   A row Facebook can't hide answers no there, and that stays a no.
 */

internal const val REELS_ROW_HEADER = "ShowcaseFbShortsHeaderComponent"
internal const val SHOW_REELS_ROW_X = "$POST_PROMPTS->showReelsRowX(I)Z"

/** Where the Reels row's header decides on its X: its [render] method and the index of the instance-of there. */
internal class ReelsRowX(val render: Method, val check: Int)

/** The Reels row header's X check. Changes nothing. */
internal fun BytecodePatchContext.findReelsRowX(): ReelsRowX {
    val headers = classDefByStrings(REELS_ROW_HEADER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { header -> header.methods.any { it.name == "<init>" && holdsString(it, REELS_ROW_HEADER) } }
    val header = headers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one class whose constructor loads \"$REELS_ROW_HEADER\", found ${headers.size}")
    val checks = header.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().withIndex()
            .filter { it.value.opcode == Opcode.INSTANCE_OF }
            .map { ReelsRowX(method, it.index) }
    }
    val check = checks.singleOrNull()
        ?: throw PatchException("$PATCH: expected one instance-of in ${header.type}, found ${checks.size}")
    val next = check.render.implementation!!.instructions.elementAtOrNull(check.check + 1)
    val register = (check.render.implementation!!.instructions.elementAt(check.check) as TwoRegisterInstruction).registerA
    if (next?.opcode != Opcode.IF_EQZ || (next as OneRegisterInstruction).registerA != register) {
        throw PatchException("$PATCH: ${header.type}'s instance-of isn't followed by an if-eqz on its answer")
    }
    return check
}

/** Sends every answer of the X check, and the Reels row header's, through the extension. */
internal fun BytecodePatchContext.hidePostX() {
    val check = findPostXCheck()
    mutableClassDefBy(check.definingClass).findMutableMethodOf(check).filterBooleanReturns(PATCH, SHOW_POST_X)
    val row = findReelsRowX()
    val render = mutableClassDefBy(row.render.definingClass).findMutableMethodOf(row.render)
    val answer = render.getInstruction<TwoRegisterInstruction>(row.check).registerA
    render.addInstructions(
        row.check + 1,
        """
            invoke-static/range { v$answer .. v$answer }, $SHOW_REELS_ROW_X
            move-result v$answer
        """,
    )
}
