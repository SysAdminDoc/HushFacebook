/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The bar along the bottom of a live video (#537). Read from 588, 2026-11-21, arm64; the obfuscated
 * names here are for reviewers, the code never writes one down.
 *
 * A live video's player (Facebook's facecast live event views, which also play a live video opened
 * like a reel) lays a horizontal bar under the live comments: the comment box, which tags itself
 * "LiveEventCommentComposer" in its constructor (LX/0RbN;), the row of reactions a tap sends to the
 * stream (LX/0RbM;, filled by the streaming reaction input, LX/0QUw;), and a few Litho slots and
 * stubs for buttons such as Stars. The bar is LX/0RbO;, a LinearLayout whose (Context, AttributeSet,
 * int) constructor finds each of those children by id and casts the comment box to its class, the
 * only constructor doing so. Its controllers (LX/0Qgl;, LX/0QYe;) show and hide it later, so the bar
 * is handed to the extension at the end of that constructor, and the extension holds it gone before
 * each frame while the switch is on.
 */

internal const val LIVE_COMMENT_BAR = "$EXTENSION_PACKAGE/comments/LiveCommentBar;"

/** What the live video's comment box tags itself in its constructor. */
internal const val LIVE_COMPOSER_TAG = "LiveEventCommentComposer"

internal const val LIVE_BAR_BUILT = "$LIVE_COMMENT_BAR->barBuilt(Landroid/view/View;)V"

/** The constructor a view inflated from a layout runs. */
internal fun inflatedConstructor(method: Method): Boolean = method.name == "<init>" &&
    method.parameterTypes == listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I") &&
    method.returnType == "V"

/** Whether [classDef] tags itself [LIVE_COMPOSER_TAG] in its inflated constructor. */
internal fun isLiveComposer(classDef: ClassDef): Boolean =
    classDef.methods.any { inflatedConstructor(it) && holdsString(it, LIVE_COMPOSER_TAG) }

/** [classDef]'s inflated constructor when it casts a view to [composer], else null. */
internal fun liveBarConstructor(classDef: ClassDef, composer: String): Method? =
    classDef.methods.singleOrNull { method ->
        inflatedConstructor(method) && method.implementation?.instructions?.any {
            it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == composer
        } == true
    }

/** The live video's comment box, and the bar that casts a child to it in its constructor. */
internal class LiveBar(val composer: ClassDef, val bar: ClassDef, val init: Method)

/** The [LiveBar], or null with why in [why]. */
internal fun BytecodePatchContext.liveBar(why: (String) -> Unit): LiveBar? {
    val composers = classDefByStrings(LIVE_COMPOSER_TAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }.filter(::isLiveComposer)
    val composer = composers.singleOrNull()
        ?: return null.also { why("expected one view tagging itself $LIVE_COMPOSER_TAG as it's built, found ${composers.size}") }
    val bars = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEachReferencing(types = listOf(composer.type)) { classDef ->
        if (classDef.type != composer.type) liveBarConstructor(classDef, composer.type)?.let { bars += classDef to it }
    }
    val (bar, init) = bars.distinctBy { it.first.type }.singleOrNull()
        ?: return null.also { why("expected one view whose constructor casts a child to ${composer.type}, found ${bars.size}") }
    return LiveBar(composer, bar, init)
}

/** Every return of the bar's constructor first hands the bar to the extension. */
internal fun BytecodePatchContext.hideLiveCommentBar(patch: String) {
    val found = liveBar { why -> patchLog.warning("$patch: $why. Hide the live video comment bar does nothing in this build") }
        ?: return
    val method = mutableClassDefBy(found.bar.type).findMutableMethodOf(found.init)
    // From the end, so the indexes still to come stay where they were read.
    val returns = method.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }.sortedDescending()
    for (index in returns) {
        method.addInstructionsAtControlFlowLabel(index, "invoke-static { p0 }, $LIVE_BAR_BUILT")
    }
}
