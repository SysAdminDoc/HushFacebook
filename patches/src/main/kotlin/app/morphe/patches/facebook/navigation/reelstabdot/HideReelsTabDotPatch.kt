/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.reelstabdot

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val PATCH = "Hide the Reels tab dot"

/** The log name the tab bar's jewel controller loads in its count for one tab. */
internal const val JEWEL_COUNT = "FbMainTabActivityJewelController.getTrackedCountWithLogging"

internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val TAB_TAG = "Lcom/facebook/navigation/tabbar/state/model/TabTag;"

internal const val REELS_TAB_DOT = "Lapp/morphe/extension/facebook/navigation/ReelsTabDot;"
internal const val CLEAR = "$REELS_TAB_DOT->clear(Ljava/lang/Object;)Z"

/**
 * The tab bar's one count hook. Facebook's tab bar asks its jewel controller
 * (FbMainTabActivityJewelController, 581 `LX/1pM;`) for each tab's count through one static method,
 * (FbUserSession, controller, TabTag, int) -> int, which loads [JEWEL_COUNT] for its logging; the
 * tab bar's layout, its count updates and a runnable of its own all call it. The extension goes
 * first in it, handed the tab. Hide the Reels tab dot and Hide tab badges both depend on this, so
 * the method carries one Hushfacebook call whichever of them is in, and each only switches its own
 * rule on.
 */
internal val jewelCountHookPatch = patch {
    dependsOn(settingsPatch)

    executeBytecode {
        applyJewelCount(findJewelCount())
    }
}

/**
 * The dot and "new" count on the Reels tab (Video on some accounts) go: the shared count hook
 * answers 0 for the Reels tab, and every other tab gets Facebook's count unless Hide tab badges
 * takes it.
 */
@Suppress("unused")
val hideReelsTabDotPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide the Reels tab dot",
    description = "Takes the new-item dot and count off the Reels tab, called Video on some accounts, so it " +
        "stops calling for your attention. Other tabs keep theirs. On by default. Turn it off in Hushfacebook " +
        "settings > Reels and Watch.",
) {
    category("Navigation")
    dependsOn(settingsPatch)
    dependsOn(jewelCountHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        enableStatus("reelsTabDot")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Whether [method] is the jewel controller's count: static (FbUserSession, controller, TabTag, int) -> int. */
internal fun isJewelCount(method: Method): Boolean {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    return AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" && parameters.size == 4 &&
        parameters[0] == FB_USER_SESSION && parameters[1] == method.definingClass && parameters[2] == TAB_TAG &&
        parameters[3] == "I" && holdsString(method, JEWEL_COUNT)
}

/** The jewel controller's count for one tab. Changes nothing. */
internal fun BytecodePatchContext.findJewelCount(): Method {
    val counts = classDefByStrings(JEWEL_COUNT, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter(::isJewelCount) }
    return counts.singleOrNull()
        ?: refuse("expected one static (FbUserSession, controller, TabTag, int) count loading \"$JEWEL_COUNT\", found ${counts.size}")
}

/**
 * The extension goes first, handed the tab through the range form. Its yes returns 0 from v0, free
 * at the method's start; anything else runs Facebook's count as it was.
 */
internal fun BytecodePatchContext.applyJewelCount(count: Method) {
    val method = mutableClassDefBy(count.definingClass).findMutableMethodOf(count)
    method.requireLocals(PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p2 .. p2 }, $CLEAR
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", method.getInstruction(0)),
    )
}
