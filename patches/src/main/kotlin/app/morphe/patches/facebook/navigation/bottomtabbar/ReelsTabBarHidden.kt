/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.layout.theme.TAB_BAR_CONTAINER
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Hide the tab bar on the Reels tab only. Read from 586, 2026-11-10; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * - The bar. Each time a tab is selected, Facebook restyles the tab bar in one static (fragment,
 *   tab bar holder, TabTag, Boolean)V (586 `LX/02LT;->A04`): its last step stores a flag on the
 *   TabBarContainerLayout. Right before that store the extension is handed the container and the
 *   TabTag, and hides the bar on the Video tab.
 * - The page. Facebook keeps scroll-away off on the Reels tab with the bars fully shown, and each
 *   tab's FbChromeFragment then lifts its page above them in a static (content fragment,
 *   FbChromeFragment)V (586 `A0B`, run on the tab's setup and again by
 *   "trackBottomTabsHeightChanges"): the page container's bottom margin becomes the scroll-away
 *   controller's sum of the bottom entries' heights (586 `LX/03GR;->A0T()I`), the tab bar's. A hidden
 *   bar keeps its height there, so the reel stopped a bar's height short of the bottom (seen on the
 *   S22, 2026-11-10: the page 2041 of 2241). The sum goes through the extension with the fragment's
 *   TabTag, and the extension answers 0 for the Reels tab. The content manager's bottom padding
 *   (586 `LX/030T;->A09`) already leaves the bar out on that tab, so it isn't touched.
 * - The reel. Each reel row is sized by a static (Activity, View, config)I (586 `LX/0AFt;->A00`):
 *   the window's content height, give or take the system bars, minus the same sum of the bottom
 *   entries' heights, taken last right before the return. With the page full height the row still
 *   stopped at 2041 (S22, 2026-11-10), so that last sum goes through the extension too and is 0 with
 *   the switch on. Only a viewer whose config says it sits in a tab takes that branch (586 `LX/05WE;`,
 *   built for "reels_tab_root_video_early_fetch"), and Facebook sets the Reels tab up at start while
 *   Home shows, so the extension can't wait for the bar to be hidden.
 */

internal const val REELS_TAB_BAR_HIDDEN = "Lapp/morphe/extension/facebook/navigation/ReelsTabBarHidden;"

private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val MARGINS = "Landroid/view/ViewGroup\$MarginLayoutParams;"
private const val VIEW = "Landroid/view/View;"

/** The tab bar restyle and the store it hooks before, with the container's and the TabTag's registers. */
internal class TabRestyleSite(val method: Method, val at: Int, val bar: Int, val tab: Int)

/**
 * The page margin method and the move-result of the bottom bars' height, with the registers the
 * hook uses: the height's, the FbChromeFragment's and one the next instruction overwrites unread.
 */
internal class PageMarginSite(val method: Method, val read: Int, val height: Int, val chrome: Int, val scratch: Int)

/** The reel height method and the move-result of the bottom bars' height it takes off last. */
internal class ReelHeightSite(val method: Method, val read: Int, val register: Int)

/** What the hook needs: the restyle, the page margin, FbChromeFragment's TabTag field and the reel height. */
internal data class ReelsTabBarSites(
    val restyle: TabRestyleSite,
    val margin: PageMarginSite,
    val tabField: FieldReference,
    val reel: ReelHeightSite,
)

/**
 * [method] as the tab bar restyle: a static (fragment, its own class, TabTag, Boolean)V with one
 * boolean store on the TabBarContainerLayout. Null for anything else.
 */
internal fun tabRestyleSite(method: Method): TabRestyleSite? {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" || parameters.size != 4 ||
        parameters[1] != method.definingClass || parameters[2] != TAB_TAG || parameters[3] != BOOLEAN
    ) {
        return null
    }
    val code = method.implementation?.instructions?.toList() ?: return null
    val stores = code.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IPUT_BOOLEAN &&
            ((instruction as ReferenceInstruction).reference as FieldReference).definingClass == TAB_BAR_CONTAINER
    }
    val at = stores.singleOrNull()?.index ?: return null
    val bar = (code[at] as TwoRegisterInstruction).registerB
    val tab = method.implementation!!.registerCount - 2
    return TabRestyleSite(method, at, bar, tab)
}

private fun isBottomMargin(instruction: Instruction): Boolean {
    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: return false
    return field.definingClass == MARGINS && field.name == "bottomMargin" && field.type == "I"
}

/**
 * [method] as the page margin: a static (fragment, its own class)V whose first bottom margin store
 * keeps the move-result of a no-argument int call, the bottom bars' height, and that reads the
 * margin it compares with right after the call. Null for anything else.
 */
internal fun pageMarginSite(method: Method): PageMarginSite? {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
        parameters != listOf(FRAGMENT, method.definingClass)
    ) {
        return null
    }
    val code = method.implementation?.instructions?.toList() ?: return null
    val store = code.indexOfFirst { it.opcode == Opcode.IPUT && isBottomMargin(it) }.takeIf { it >= 0 } ?: return null
    val height = (code[store] as TwoRegisterInstruction).registerA
    val read = (store - 1 downTo 0).firstOrNull { writesRegister(code[it], height) } ?: return null
    val call = code.getOrNull(read - 1)
    val ref = (call as? ReferenceInstruction)?.reference as? MethodReference
    val next = code.getOrNull(read + 1)
    if (code[read].opcode != Opcode.MOVE_RESULT || call?.opcode != Opcode.INVOKE_VIRTUAL || ref?.returnType != "I" ||
        ref.parameterTypes.isNotEmpty() || next?.opcode != Opcode.IGET || !isBottomMargin(next)
    ) {
        return null
    }
    val scratch = (next as TwoRegisterInstruction).registerA
    val chrome = method.implementation!!.registerCount - 1
    if (scratch == next.registerB || scratch == height || scratch == chrome) return null
    return PageMarginSite(method, read, height, chrome, scratch)
}

/**
 * [method] as the reel height: a static (Activity, View, anything)I whose [height] call's result is
 * subtracted right after it's read, and that difference returned. Null for anything else.
 */
internal fun reelHeightSite(method: Method, height: MethodReference): ReelHeightSite? {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "I" || parameters.size != 3 ||
        parameters[0] != ACTIVITY || parameters[1] != VIEW
    ) {
        return null
    }
    val code = method.implementation?.instructions?.toList() ?: return null
    val reads = code.indices.mapNotNull { i ->
        val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
        if (code[i].opcode != Opcode.INVOKE_VIRTUAL || ref?.toString() != height.toString()) return@mapNotNull null
        val read = code.getOrNull(i + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
        val take = code.getOrNull(i + 2)?.takeIf { it.opcode == Opcode.SUB_INT_2ADDR } as? TwoRegisterInstruction
        val back = code.getOrNull(i + 3)?.takeIf { it.opcode == Opcode.RETURN } as? OneRegisterInstruction
        if (read == null || take?.registerB != read.registerA || back?.registerA != take.registerA) return@mapNotNull null
        ReelHeightSite(method, i + 1, read.registerA)
    }
    return reads.singleOrNull()
}

/**
 * The three sites and the FbChromeFragment's TabTag field, read from the classes as the patches
 * before this one left them, or null after a warning when this build doesn't have exactly one of each.
 */
internal fun BytecodePatchContext.reelsTabBarSites(): ReelsTabBarSites? {
    val owners = mutableSetOf<String>()
    classDefForEachReferencing(types = listOf(TAB_BAR_CONTAINER)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        if (classDef.methods.any { tabRestyleSite(it) != null }) owners += classDef.type
    }
    val restyles = owners.flatMap { owner -> mutableClassDefBy(owner).methods.mapNotNull(::tabRestyleSite) }
    val restyle = restyles.singleOrNull()
        ?: return skipHidden("expected one tab bar restyle storing a flag on $TAB_BAR_CONTAINER, found ${restyles.size}")
    val restyleCode = restyle.method.implementation!!.instructions.toList()
    if (restyle.bar > 15 || restyle.tab > 15 || restyle.at in branchTargets(restyleCode) ||
        restyleCode.any { writesRegister(it, restyle.tab) }
    ) {
        return skipHidden("${restyle.method.definingClass}->${restyle.method.name} can't take the hook")
    }

    val chrome = mutableClassDefByOrNull(FB_CHROME_FRAGMENT) ?: return skipHidden("$FB_CHROME_FRAGMENT isn't in this build")
    val tabFields = chrome.fields.filter { it.type == TAB_TAG && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val tabField = tabFields.singleOrNull()
        ?: return skipHidden("expected one $TAB_TAG field in $FB_CHROME_FRAGMENT, found ${tabFields.size}")
    val margins = chrome.methods.mapNotNull(::pageMarginSite)
    val margin = margins.singleOrNull()
        ?: return skipHidden("expected one page margin in $FB_CHROME_FRAGMENT set from the bottom bars' height, found ${margins.size}")
    val marginCode = margin.method.implementation!!.instructions.toList()
    if (margin.height > 15 || margin.chrome > 15 || margin.scratch > 15 || margin.read + 1 in branchTargets(marginCode) ||
        marginCode.any { writesRegister(it, margin.chrome) }
    ) {
        return skipHidden("$FB_CHROME_FRAGMENT->${margin.method.name} can't take the hook")
    }

    val height = (marginCode[margin.read - 1] as ReferenceInstruction).reference as MethodReference
    val sizers = mutableSetOf<String>()
    classDefForEachReferencing(types = listOf(height.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        if (classDef.methods.any { reelHeightSite(it, height) != null }) sizers += classDef.type
    }
    val reels = sizers.flatMap { sizer -> mutableClassDefBy(sizer).methods.mapNotNull { reelHeightSite(it, height) } }
    val reel = reels.singleOrNull()
        ?: return skipHidden("expected one reel height taking $height off last, found ${reels.size}")
    val reelCode = reel.method.implementation!!.instructions.toList()
    if (reel.register > 15 || reel.read + 1 in branchTargets(reelCode)) {
        return skipHidden("${reel.method.definingClass}->${reel.method.name} can't take the hook")
    }
    return ReelsTabBarSites(restyle, margin, tabField, reel)
}

private fun skipHidden(why: String): Nothing? {
    patchLog.warning("$PATCH: $why, so Hide the tab bar on the Reels tab does nothing on this build")
    return null
}

/**
 * Hands the restyle's bar and tab, the page margin's bottom bars' height and tab, and the reel
 * height's bottom bars' height to the extension.
 */
internal fun BytecodePatchContext.hookReelsTabBarHidden() {
    val (restyle, margin, tabField, reel) = reelsTabBarSites() ?: return
    mutableClassDefBy(restyle.method.definingClass).findMutableMethodOf(restyle.method).addInstructions(
        restyle.at,
        """
            invoke-static { v${restyle.bar}, v${restyle.tab} }, $REELS_TAB_BAR_HIDDEN->tabShown(Landroid/view/View;Ljava/lang/Object;)V
        """,
    )
    mutableClassDefBy(margin.method.definingClass).findMutableMethodOf(margin.method).addInstructions(
        margin.read + 1,
        """
            iget-object v${margin.scratch}, v${margin.chrome}, $FB_CHROME_FRAGMENT->${tabField.name}:$TAB_TAG
            invoke-static { v${margin.height}, v${margin.scratch} }, $REELS_TAB_BAR_HIDDEN->pageBottomMargin(ILjava/lang/Object;)I
            move-result v${margin.height}
        """,
    )
    mutableClassDefBy(reel.method.definingClass).findMutableMethodOf(reel.method).addInstructions(
        reel.read + 1,
        """
            invoke-static { v${reel.register} }, $REELS_TAB_BAR_HIDDEN->reelBottomBars(I)I
            move-result v${reel.register}
        """,
    )
}
