/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.treeFieldKey
import app.morphe.patches.facebook.misc.extension.filterBooleanReturns
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

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

/** Sends every answer of the X check through the extension. */
internal fun BytecodePatchContext.hidePostX() {
    val check = findPostXCheck()
    mutableClassDefBy(check.definingClass).findMutableMethodOf(check).filterBooleanReturns(PATCH, SHOW_POST_X)
}
