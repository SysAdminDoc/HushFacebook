/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Post text doesn't open comments (#594). Read from 588, 2026-11-26, arm64; the obfuscated names
 * here are for reviewers, the code never writes one down.
 *
 * A feed post's comments open in a [POPOVER_FRAGMENT], a kept class, and every tap that opens one
 * goes through the same launcher (588 `LX/09YS;->A00`): handed a Context, the sheet's host and the
 * kept [POPOVER_PARAMS], it finds the activity's fragment manager and calls the sheet's static show
 * (588 `SimpleUFIPopoverFragment;->A05`). The launcher already gives up with null on its own, when
 * a MobileConfig check says so or there's no Context, and its callers take that as no sheet. The
 * only other caller of the show is the feed putting back a sheet that was open before the feed was
 * rebuilt (it logs "reshowPopover"), which isn't a tap and is left alone.
 *
 * The params carry one Integer, the sheet's flyout context: what was tapped. Facebook logs it with
 * each sheet it opens, and on the S22 (2026-11-26) a tap on the post's text opened comments with 5
 * and the Comment button with 3. So first thing in the launcher, the extension is handed that
 * Integer, and on a yes the launcher returns null before anything else runs.
 */

internal const val POPOVER_PARAMS = "Lcom/facebook/ufiservices/flyout/params/PopoverParams;"

internal const val POPOVER_FRAGMENT = "Lcom/facebook/ufiservices/flyout/fragment/SimpleUFIPopoverFragment;"

internal const val KEEPS_COMMENTS_CLOSED =
    "$EXTENSION_PACKAGE/comments/CommentSheetOptions;->keepsCommentsClosed(Ljava/lang/Integer;)Z"

private const val CONTEXT = "Landroid/content/Context;"

/** The params' flyout context: their one instance field of type Integer, or null when there isn't exactly one. */
internal fun flyoutContextField(params: ClassDef): Field? =
    params.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/lang/Integer;" }.singleOrNull()

/**
 * The launcher: an instance method taking a Context, the sheet's host and [POPOVER_PARAMS],
 * returning a [POPOVER_FRAGMENT] it gets from one of the fragment's own static methods.
 */
internal fun isPopoverLauncher(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != POPOVER_FRAGMENT) return false
    val types = parameterTypeNames(method)
    if (types.size != 3 || types[0] != CONTEXT || types[2] != POPOVER_PARAMS) return false
    return method.implementation?.instructions?.any { instruction ->
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            ((instruction as ReferenceInstruction).reference as MethodReference).let {
                it.definingClass == POPOVER_FRAGMENT && it.returnType == POPOVER_FRAGMENT
            }
    } == true
}

/**
 * First thing in the launcher, the extension gets the params' flyout context, and on a yes the
 * launcher returns null, the way it does when Facebook keeps a sheet from opening itself. A build
 * where the launcher or the field can't be found keeps the rest of the patch, with a warning.
 */
internal fun BytecodePatchContext.keepCommentsClosedOnTextTap(patch: String) {
    fun skip(why: String) = patchLog.warning("$patch: $why. Post text doesn't open comments does nothing in this build")
    val params = classDefByOrNull(POPOVER_PARAMS) ?: return skip("this build has no $POPOVER_PARAMS")
    val field = flyoutContextField(params) ?: return skip("$POPOVER_PARAMS doesn't hold exactly one Integer")
    val launchers = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(POPOVER_FRAGMENT)) { classDef ->
        classDef.methods.filterTo(launchers, ::isPopoverLauncher)
    }
    val launcher = launchers.singleOrNull()
        ?: return skip("expected one method opening $POPOVER_FRAGMENT from a Context and $POPOVER_PARAMS, found ${launchers.size}")
    if (launcher.localRegisterCount() < 1) return skip("${launcher.definingClass}->${launcher.name} has no local register to borrow")
    val method = mutableClassDefBy(launcher.definingClass).findMutableMethodOf(launcher)
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${launcher.parameterRegister(2)}
            if-eqz v0, :launch
            iget-object v0, v0, ${field.definingClass}->${field.name}:${field.type}
            invoke-static { v0 }, $KEEPS_COMMENTS_CLOSED
            move-result v0
            if-eqz v0, :launch
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("launch", method.getInstruction(0)),
    )
}
