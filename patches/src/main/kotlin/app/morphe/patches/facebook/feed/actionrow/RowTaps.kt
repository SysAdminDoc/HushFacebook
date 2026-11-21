/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.actionrow

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Taps beside the buttons under a post (#540). Read from 588, 2026-11-21, arm64; the obfuscated
 * names here are for reviewers, the code never writes one down.
 *
 * A device showed the row under a feed post as one clickable host the width of the post, with Like,
 * Comment, Share, Save and the reaction faces each clickable inside it. A tap between the buttons
 * and the faces reaches the host, whose tap opens the comments, or who reacted when nobody has
 * commented yet.
 *
 * That host is the wrapper Facebook names UFIFeedbackFlyoutLauncherComponent (LX/03AK;, named in
 * its no-argument constructor). Its layout (A1B) wraps the row it's given and, when the post has
 * feedback to show, checks whether the row is the older bling bar (an instance-of, the method's only
 * one). When it isn't, the wrapper gets a click event, then a label and the Button role, and every
 * path ends at the key "ufi_feedback_wrapper". The event's case in the shared dispatcher (LX/01Mz;
 * ->A1F) opens the feedback sheet (LX/04nU;->A00). While the switch is on, the layout goes from the
 * instance-of straight to the key, so the wrapper is drawn with no tap and no label, and a tap beside
 * the buttons lands on nothing. The buttons' own taps are their own and stay.
 */

internal const val POST_ROW_TAPS = "$EXTENSION_PACKAGE/feed/PostRowTaps;"

/** What the wrapper that opens a post's feedback from its row names itself. */
internal const val FLYOUT_LAUNCHER = "UFIFeedbackFlyoutLauncherComponent"

/** The key every path of the wrapper's layout gives its host last. */
internal const val FEEDBACK_WRAPPER_KEY = "ufi_feedback_wrapper"

internal const val IGNORE_ROW_TAPS = "$POST_ROW_TAPS->ignoreRowTaps()Z"

/**
 * Where a layout checks its row's type ([check], writing [register] from another register) and
 * where it keys its host ([key], later): the stretch the switch skips.
 */
internal class RowTapStretch(val check: Int, val key: Int, val register: Int)

/** [layout]'s [RowTapStretch], or null when it has no one instance-of before one load of the key. */
internal fun rowTapStretch(layout: Method): RowTapStretch? {
    val code = layout.implementation?.instructions?.toList() ?: return null
    val check = code.withIndex().singleOrNull { it.value.opcode == Opcode.INSTANCE_OF } ?: return null
    val key = code.withIndex().singleOrNull {
        ((it.value as? ReferenceInstruction)?.reference as? StringReference)?.string == FEEDBACK_WRAPPER_KEY
    } ?: return null
    val written = check.value as TwoRegisterInstruction
    // The check's own result register is free before it, unless the check reads that register too.
    if (key.index <= check.index || written.registerA == written.registerB) return null
    return RowTapStretch(check.index, key.index, written.registerA)
}

/** Whether [classDef] names itself [FLYOUT_LAUNCHER] in its no-argument constructor. */
internal fun isFlyoutLauncher(classDef: ClassDef): Boolean = classDef.methods.any {
    it.name == "<init>" && it.parameterTypes.isEmpty() && holdsString(it, FLYOUT_LAUNCHER)
}

/**
 * The wrapper's layout asks the extension first, at its check of the row's type, and on a yes goes
 * to the key, past the tap and the label.
 */
internal fun BytecodePatchContext.ignoreRowTaps() {
    val launchers = classDefByStrings(FLYOUT_LAUNCHER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }.filter(::isFlyoutLauncher)
    val launcher = launchers.singleOrNull() ?: throw PatchException(
        "Post action buttons: expected one component naming itself $FLYOUT_LAUNCHER, found ${launchers.size}",
    )
    val layouts = launcher.methods.filter { rowTapStretch(it) != null }
    val layout = layouts.singleOrNull() ?: throw PatchException(
        "Post action buttons: expected one method of ${launcher.type} checking its row's type before keying it " +
            "$FEEDBACK_WRAPPER_KEY, found ${layouts.size}",
    )
    val stretch = rowTapStretch(layout)!!
    val method = mutableClassDefBy(launcher.type).findMutableMethodOf(layout)
    val register = stretch.register
    method.addInstructionsAtControlFlowLabel(
        stretch.check,
        """
            invoke-static { }, $IGNORE_ROW_TAPS
            move-result v$register
            if-nez v$register, :facebooks_key
        """,
        ExternalLabel("facebooks_key", method.getInstruction(stretch.key)),
    )
}
