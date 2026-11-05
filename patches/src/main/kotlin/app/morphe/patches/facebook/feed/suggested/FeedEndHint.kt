/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/*
 * The bottom of Home (#258, Discussion #371). Read from 585, 2026-11-05; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * Home's list ends with an empty component (arm64 LX/03Jv;) whose two visibility handlers call one
 * static method of its class (A0B) with the session, the component and true as it comes into view,
 * false as it leaves. That method opens a trace named "InterruptionStateManager.onEndOfFeedUnitVisible"
 * for Facebook's own feed timing, which is how the patch finds it. The patch hands the flag to the
 * extension first thing there, so the extension knows the moment someone reaches the bottom of Home.
 */

/** The trace the bottom of Home's visibility method opens. */
internal const val END_VISIBLE_TRACE = "InterruptionStateManager.onEndOfFeedUnitVisible"

internal const val FEED_END_VISIBLE = "$EXTENSION_PACKAGE/feed/FeedEndHint;->endVisible(Z)V"

/** A static method answering nothing whose last parameter is the visible flag. */
internal fun isEndVisibleMethod(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.returnType == "V" && method.parameterTypes.size >= 2 && method.parameterTypes.last() == "Z"

/** The methods outside the extension opening [END_VISIBLE_TRACE] that take the visible flag. */
internal fun BytecodePatchContext.endVisibleMethods(): List<Method> =
    classDefByStrings(END_VISIBLE_TRACE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, END_VISIBLE_TRACE) }
        .filter(::isEndVisibleMethod)

/**
 * Hands the bottom of Home's visible flag to the extension first thing in the method Facebook calls
 * as it comes into view and leaves. The method is static, so the flag, its last parameter, is its last
 * register. Refuses unless one method fits.
 */
internal fun BytecodePatchContext.hookFeedEndHint() {
    val methods = endVisibleMethods()
    val method = methods.singleOrNull()
        ?: throw PatchException("expected one method opening \"$END_VISIBLE_TRACE\" with a visible flag, found ${methods.size}")
    val visible = method.implementation!!.registerCount - 1
    mutableClassDefBy(method.definingClass).findMutableMethodOf(method).addInstructions(
        0,
        "invoke-static/range { v$visible .. v$visible }, $FEED_END_VISIBLE",
    )
}
