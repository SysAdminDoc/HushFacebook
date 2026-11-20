/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Keep the reel details lifted while Litho animates them (#466). Read from 588, 2026-11-19; the
 * obfuscated names are for reviewers, the code never writes one down.
 *
 * Litho animates a host's position by setting its Y to where it should be in the LithoView, less
 * where its parent is, and it finds where the parent is with one static walk, (View, boolean) to
 * float (588 LX/03lx;->A00), adding up getX() or getY() of each view up to the LithoView. getY()
 * counts a view's translation, so a reel's details lifted above a bar had the lift taken back off
 * every host inside them Litho moved: a few seconds into a reel its comment preview comes in and
 * the name and caption slide up 98 px with it, landing the lift lower, over the preview (S22, 588).
 * The walk's getY() asks the extension instead, which answers the view's Y without the lift.
 */

internal const val VIEW_LIFT = "Lapp/morphe/extension/facebook/navigation/ViewLift;"
internal const val LITHO_Y = "$VIEW_LIFT->lithoY(Landroid/view/View;)F"

private const val VIEW_GET_X = "Landroid/view/View;->getX()F"
private const val VIEW_GET_Y = "Landroid/view/View;->getY()F"
private const val VIEW_GET_PARENT = "Landroid/view/View;->getParent()Landroid/view/ViewParent;"

/** Whether [method] has the walk's shape: static, (View, boolean) to float, reading each parent's X or Y. */
internal fun walksLithoPositions(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "F") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf("Landroid/view/View;", "Z")) return false
    val calls = method.implementation?.instructions?.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
    }.orEmpty()
    return VIEW_GET_PARENT in calls && VIEW_GET_X in calls && (VIEW_GET_Y in calls || LITHO_Y in calls)
}

/**
 * Has Litho's position walk ask [LITHO_Y] in place of each `View.getY()` it calls. A build without
 * exactly one walk says so in the patch log and lifts the details without it. Once however often
 * this runs.
 */
internal fun BytecodePatchContext.hookLithoPositions() {
    val walks = mutableListOf<Method>()
    classDefForEachReferencing(names = listOf("getY")) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(walks, ::walksLithoPositions)
    }
    val walk = walks.singleOrNull() ?: return patchLog.warning(
        "$PATCH: expected one Litho position walk, found ${walks.size}, so a reel's caption can slide back over its comments preview on this build",
    )
    val method = mutableClassDefBy(walk.definingClass).findMutableMethodOf(walk)
    val code = method.implementation!!.instructions
    val sites = code.indices.filter { at ->
        val call = code[at]
        call.opcode == Opcode.INVOKE_VIRTUAL && ((call as ReferenceInstruction).reference as MethodReference).toString() == VIEW_GET_Y
    }
    for (at in sites) {
        val view = (code[at] as FiveRegisterInstruction).registerC
        method.replaceInstruction(at, "invoke-static/range { v$view .. v$view }, $LITHO_Y")
    }
}
