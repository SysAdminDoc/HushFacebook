/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/*
 * The Join button after a group's name in the header of a post from a group you haven't joined
 * (#356). Read from 585, 2026-11-02, arm64 and armv7; the obfuscated names are for reviewers only.
 *
 * - The group header helper (GroupsFeedStoryHeaderTitleMenuHelperImpl, 585 `LX/02tg;->A00` on arm64)
 *   has one entry, (Context, SpannableStringBuilder, FbUserSession, props, story) -> V, named by the
 *   trace literal "...appendActionableForumJoinButton". It appends the separator dot to the title
 *   and then the Join button (`LX/02t3;->A04`, "...appendActionableGroupJoinButton") or the forum's.
 *   Its callers are the feed title plugin and one title builder, and nothing reads a result.
 * - Returning first thing leaves the title as Facebook builds it for a post with no such button,
 *   dot included. At the first instruction v0 is free: the method's first instruction is a move
 *   from a parameter register.
 */

internal const val JOIN_BUTTON_ENTRY = "appendActionableForumJoinButton"
internal const val JOIN_BUTTON_TRACE = "GroupsFeedStoryHeaderTitleMenuHelperImpl.$JOIN_BUTTON_ENTRY"
internal const val HIDE_JOIN_BUTTON = "$POST_PROMPTS->hideJoinButton()Z"
private const val SPANNABLE_BUILDER = "Landroid/text/SpannableStringBuilder;"

/** Whether [method] is the header entry: an instance method taking a SpannableStringBuilder, answering nothing, with the trace literal. */
internal fun isJoinButtonEntry(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.size == 5 && method.parameterTypes.any { it.toString() == SPANNABLE_BUILDER } &&
        holdsString(method, JOIN_BUTTON_TRACE)

/** The header entry on whichever class holds it. Changes nothing. */
internal fun BytecodePatchContext.findJoinButtonEntry(): Method {
    val classes = classDefByStrings(JOIN_BUTTON_TRACE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
    val entries = classes.flatMap { it.methods.filter(::isJoinButtonEntry) }
    return entries.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one header method with \"$JOIN_BUTTON_TRACE\", found ${entries.size}",
    )
}

/** Asks the extension first thing and returns before the dot and the button are appended when it says so. */
internal fun MutableMethod.skipWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_header
            return-void
        """,
        ExternalLabel("facebooks_header", getInstruction(0)),
    )
}

internal fun BytecodePatchContext.hidePostJoinButton() {
    val entry = findJoinButtonEntry()
    mutableClassDefBy(entry.definingClass).methods.single {
        it.name == entry.name && it.parameterTypes.map(CharSequence::toString) == entry.parameterTypes.map(CharSequence::toString)
    }.skipWhen(HIDE_JOIN_BUTTON)
}
