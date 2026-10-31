/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The reel under a top tab bar (#264). With the tab bar at the top, the Reels tab's reel started
 * below the bar and its letterbox read as a black strip above the video. Read from 585 (2026-10-31):
 * the scroll-away controller (arm64 LX/02Ca) pads each page's scrollable content by the height of
 * the top entries through the content manager (LX/02zl). Its trace
 * "ScrollAwayNavigationContentFragmentManager.maybeSetupScrollableContentPadding" (A0C) posts a
 * runnable (LX/02zx) that reads two no-argument int methods of the manager: A09, the content's top
 * padding (from LX/02zz;->A01(controller, Z)), and A0A, the top inset of a recycler's wrapper (from
 * LX/02zz;->A02). Both sum the top entries' heights. On the Reels tab, Facebook deactivates scroll-away
 * with the top entries fully shown, so they stay and the pages are padded below them. A08, the
 * bottom padding, calls a one-argument static and the controller's own accessor, so it isn't one.
 * The extension answers 0 for both while the top bar is on and the main screen is off the feed.
 */

internal const val CONTENT_PADDING_TRACE =
    "ScrollAwayNavigationContentFragmentManager.maybeSetupScrollableContentPadding"

internal const val PAGES_TOP = "$TAB_BAR_SCROLL_AWAY->pagesTop(I)I"

/**
 * The content manager's no-argument int methods that end by answering a static (controller, boolean)
 * int call: [holder]'s top padding methods. Each is returned with the register its `return` reads.
 */
internal fun topPaddingMethods(holder: ClassDef): List<Pair<Method, Int>> = holder.methods.mapNotNull { method ->
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "I" || method.parameterTypes.isNotEmpty()) {
        return@mapNotNull null
    }
    val code = method.implementation?.instructions?.toList() ?: return@mapNotNull null
    val last = code.lastOrNull()?.takeIf { it.opcode == Opcode.RETURN } as? OneRegisterInstruction ?: return@mapNotNull null
    val call = code.getOrNull(code.size - 2)?.takeIf { it.opcode == Opcode.MOVE_RESULT }
    val ask = code.getOrNull(code.size - 3)?.takeIf { it.opcode == Opcode.INVOKE_STATIC }
    val ref = (ask as? ReferenceInstruction)?.reference as? MethodReference
    if (call == null || ref == null || ref.returnType != "I" || ref.parameterTypes.size != 2 ||
        ref.parameterTypes[1].toString() != "Z" || (call as OneRegisterInstruction).registerA != last.registerA
    ) {
        return@mapNotNull null
    }
    method to last.registerA
}

/**
 * Hands each top padding answer to the extension right before the method returns it. Warns and does
 * nothing unless exactly the two methods are found and nothing jumps to their return.
 */
internal fun BytecodePatchContext.hookReelUnderTopBar(warn: (String) -> Unit) {
    val holders = classDefByStrings(CONTENT_PADDING_TRACE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
    val holder = holders.singleOrNull() ?: return warn("expected one class tracing \"$CONTENT_PADDING_TRACE\", found ${holders.size}")
    val methods = topPaddingMethods(holder)
    if (methods.size != 2) return warn("${holder.type} has ${methods.size} top padding methods, expected 2")
    for ((method, register) in methods) {
        val code = method.implementation!!.instructions.toList()
        val at = code.size - 1
        if (register > 15 || at in branchTargets(code)) return warn("${holder.type}->${method.name} can't take the hook")
    }
    for ((method, register) in methods) {
        val at = method.implementation!!.instructions.count() - 1
        mutableClassDefBy(holder.type).findMutableMethodOf(method).addInstructions(
            at,
            """
                invoke-static { v$register }, $PAGES_TOP
                move-result v$register
            """,
        )
    }
}
