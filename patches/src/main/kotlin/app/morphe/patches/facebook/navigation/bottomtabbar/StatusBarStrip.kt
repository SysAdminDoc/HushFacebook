/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.findMutableMethodOf
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Hide the status bar's strip (#124). Read from 583, 2026-10-18; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * On Android 15 and up Facebook draws a strip of its own behind the status bar: a view it adds to
 * the window's content (583 LX/2Ns;->A03 and A04) under an id the kept StatusBarUtil looks it up by
 * on the window to color it (A09). An insets listener on the content hands the window's system
 * insets to one static method, (Context, View, Boolean, Boolean, int, int, int, int) answering
 * androidx Insets (LX/2Ns;->A00), which sizes the strip to the top inset and pads the content by
 * it. Those insets keep the bar's height after Hide the status bar asks the bar away, so a strip
 * of background stayed above the top bar (S22, 2026-10-18: 75 px). First thing in that method, the
 * extension is handed the content view and the top inset, and answers 0 while the bar it hid is
 * gone.
 *
 * The same method pads the content up off the gesture bar by the bottom inset and sizes a second
 * strip to it under the screen (588 LX/02V3;->A00, the strip at a margin of minus its height). On
 * the full-screen player a reel opened from a post stopped above that band (#466, S22 588: 42 px).
 * The extension is handed the content view and the bottom inset too, and answers 0 under a video
 * viewer that runs under the gesture bar, which sends the strip off the screen with the padding.
 */

internal const val STATUS_BAR_UTIL = "Lcom/facebook/navigation/statusbar/StatusBarUtil;"
internal const val HIDDEN_STATUS_BAR = "Lapp/morphe/extension/facebook/navigation/HiddenStatusBar;"
internal const val TOP_INSET = "$HIDDEN_STATUS_BAR->topInset(Landroid/view/View;I)I"
internal const val IMMERSIVE_EDGE = "Lapp/morphe/extension/facebook/navigation/ImmersiveEdge;"
internal const val BOTTOM_INSET = "$IMMERSIVE_EDGE->bottomInset(Landroid/view/View;I)I"

private const val WINDOW_FIND_VIEW = "Landroid/view/Window;->findViewById(I)Landroid/view/View;"
internal const val ANDROIDX_INSETS = "Landroidx/core/graphics/Insets;"

/** The system bar padding's parameters: the context, the content view, two switches and the four insets. */
private val PADDING_PARAMETERS = listOf(
    "Landroid/content/Context;", "Landroid/view/View;", "Ljava/lang/Boolean;", "Ljava/lang/Boolean;", "I", "I", "I", "I",
)

/** Where the content view and the top and bottom insets sit among [PADDING_PARAMETERS]. */
private const val CONTENT_PARAMETER = 1
private const val TOP_PARAMETER = 5
private const val BOTTOM_PARAMETER = 7

/**
 * The ids [util]'s methods look a view up by on a window: each literal a `const` loads right before
 * a `Window.findViewById` that takes it.
 */
internal fun stripIds(util: ClassDef): Set<Int> = util.methods.flatMap { method ->
    val code = method.implementation?.instructions?.toList().orEmpty()
    code.indices.mapNotNull { at ->
        val load = code[at]
        val call = code.getOrNull(at + 1) ?: return@mapNotNull null
        val called = (call as? ReferenceInstruction)?.reference as? MethodReference
        if (load.opcode != Opcode.CONST || called?.toString() != WINDOW_FIND_VIEW) return@mapNotNull null
        (load as NarrowLiteralInstruction).narrowLiteral.takeIf { (load as OneRegisterInstruction).registerA in call.namedRegisters() }
    }
}.toSet()

/** Whether [method] takes and answers what Facebook's system bar padding does. */
internal fun takesSystemBarPadding(method: Method) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == ANDROIDX_INSETS &&
        method.parameterTypes.map(CharSequence::toString) == PADDING_PARAMETERS

/** Whether [method] loads [id]. */
internal fun loadsId(method: Method, id: Int) = method.implementation?.instructions?.any {
    it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == id
} == true

/**
 * Puts the extension first in the system bar padding: the one of [candidates], the methods
 * [takesSystemBarPadding] picked out, that loads the strip's id, the one id the kept
 * [STATUS_BAR_UTIL] looks a view up by on a window. A build where either isn't found keeps the
 * strip, says so in the patch log, and Tab bar at the bottom goes on without it. The top inset's
 * answer comes first, then the bottom inset's, each put there once however often this runs.
 */
internal fun BytecodePatchContext.hookStatusBarStrip(candidates: List<Method>) {
    val util = classDefByOrNull(STATUS_BAR_UTIL) ?: return skipStrip("$STATUS_BAR_UTIL isn't in this build")
    val id = stripIds(util).singleOrNull() ?: return skipStrip("expected one view id $STATUS_BAR_UTIL finds on a window")
    val paddings = candidates.filter { loadsId(it, id) }.distinctBy { "${it.definingClass}->${it.name}" }
    val padding = paddings.singleOrNull()
        ?: return skipStrip("expected one system bar padding loading 0x${Integer.toHexString(id)}, found ${paddings.size}")
    val method = mutableClassDefBy(padding.definingClass).findMutableMethodOf(padding)
    val first = method.implementation!!.registerCount - PADDING_PARAMETERS.size
    val where = "${padding.definingClass}->${padding.name}"
    if (!method.calls(BOTTOM_INSET)) {
        if (first + BOTTOM_PARAMETER > 15) {
            patchLog.warning("$PATCH: $where keeps the bottom inset past v15, so videos opened from a post keep the gesture bar's band on this build")
        } else {
            method.opensBottomEdge(first + CONTENT_PARAMETER, first + BOTTOM_PARAMETER)
        }
    }
    if (method.calls(TOP_INSET)) return
    if (first + TOP_PARAMETER > 15) return skipStrip("$where keeps the top inset past v15")
    method.closesStrip(first + CONTENT_PARAMETER, first + TOP_PARAMETER)
}

private fun Method.calls(reference: String) =
    implementation!!.instructions.any { (it as? ReferenceInstruction)?.reference?.toString() == reference }

private fun skipStrip(why: String) {
    patchLog.warning("$PATCH: $why, so Facebook's strip behind a hidden status bar stays on this build")
}

/** [hookStatusBarStrip] for the bottom inset: the extension's answer replaces it in [bottom]. */
internal fun MutableMethod.opensBottomEdge(content: Int, bottom: Int) {
    addInstructions(
        0,
        """
            invoke-static { v$content, v$bottom }, $BOTTOM_INSET
            move-result v$bottom
        """,
    )
}

/** [hookStatusBarStrip] for the padding itself: the extension's answer replaces the top inset in [top]. */
internal fun MutableMethod.closesStrip(content: Int, top: Int) {
    addInstructions(
        0,
        """
            invoke-static { v$content, v$top }, $TOP_INSET
            move-result v$top
        """,
    )
}
