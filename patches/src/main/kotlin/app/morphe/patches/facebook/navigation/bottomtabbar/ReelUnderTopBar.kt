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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
 * The extension answers 0 for both while the top bar is on and the main screen shows the Reels tab.
 * Facebook deactivates scroll-away on Notifications, Groups and Feeds too, and those keep the padding
 * or the bar covers their first rows (#328).
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

/*
 * The Reels tab's header under a top tab bar (#390). The Reels tab's own header, its "Reels" title
 * with the search and profile buttons, sits at the top of the tab's page. Facebook places it below
 * the top bars by the same padding the reel gets, so with that padding answered 0 the header went up
 * under the tab bar with the reel and showed through a see-through bar. Read from 587 (2026-11-16):
 * VideoHomeRootFragment.onCreateView finds the header, a LithoView, on one of four paths and stores
 * each in one LithoView field of its own; the extension takes it right after that store and keeps it
 * below a tab bar at the top.
 */

internal const val REELS_HEADER_CLEAR = "Lapp/morphe/extension/facebook/navigation/ReelsHeaderClear;"
internal const val REELS_HEADER_MADE = "$REELS_HEADER_CLEAR->headerMade(Landroid/view/View;)V"
private const val LITHO_VIEW = "Lcom/facebook/litho/LithoView;"

/** Where onCreateView stores the Reels header: the index of the one store into its own LithoView field, and the register stored. */
internal fun reelsHeaderStore(onCreateView: Method): Pair<Int, Int>? {
    val code = onCreateView.implementation?.instructions?.toList() ?: return null
    val stores = code.withIndex().filter { (_, instruction) ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.IPUT_OBJECT && field?.definingClass == onCreateView.definingClass && field.type == LITHO_VIEW
    }
    val store = stores.singleOrNull() ?: return null
    return store.index to (store.value as TwoRegisterInstruction).registerA
}

/**
 * Hands the Reels header to the extension right after VideoHomeRootFragment stores it. Warns and
 * does nothing unless there's exactly one such store and nothing jumps to the instruction after it.
 */
internal fun BytecodePatchContext.hookReelsHeader(warn: (String) -> Unit) {
    val fragment = classDefByOrNull(VIDEO_HOME_ROOT_FRAGMENT) ?: return warn("$VIDEO_HOME_ROOT_FRAGMENT isn't in this build")
    val found = fragment.methods.singleOrNull { it.name == "onCreateView" && it.returnType == "Landroid/view/View;" }
        ?: return warn("expected one $VIDEO_HOME_ROOT_FRAGMENT->onCreateView")
    // The mutable copy, so another patch's additions to the method can't shift the index.
    val onCreateView = mutableClassDefBy(fragment.type).findMutableMethodOf(found)
    val (store, register) = reelsHeaderStore(onCreateView)
        ?: return warn("expected one store of a LithoView field in $VIDEO_HOME_ROOT_FRAGMENT->onCreateView")
    val code = onCreateView.implementation!!.instructions.toList()
    if (register > 15 || store + 1 >= code.size || (store + 1) in branchTargets(code)) {
        return warn("$VIDEO_HOME_ROOT_FRAGMENT->onCreateView can't take the header hook")
    }
    onCreateView.addInstructions(store + 1, "invoke-static { v$register }, $REELS_HEADER_MADE")
}
