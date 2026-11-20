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
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.util.MethodUtil

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

/*
 * The Reels row's height under a top tab bar (#466). With the top padding answered 0 the reel ran
 * from the top of the page, but each row kept the height Facebook gave it, the room below the top
 * bars, so the next reel showed in a band at the bottom. Read from 587 (2026-11-17): the maker of
 * the Reels tab's list (arm64 LX/0B2M;->BPc) works the row height out from one of several sources
 * (a size handed down, the list's own height, the screen less the bars), logs it after
 * " computed=" in a line that also names " recyclerPadTop=", and stores it in one int field of the
 * list section it makes (LX/0B2U;->A02), which hands it to every row. The extension takes the
 * height right before that store, with the maker. Facebook makes the list ahead, while Home shows,
 * and keeps rows made then (the section diffs), so the first reel kept the short height when the
 * extension grew only rows made on the Reels tab, and a row grown to the screen's content ran past
 * the list's bottom inset (S22, 2026-11-17: 2075, then 2316 in a 2199 list). The same log line
 * names " tabPad=", the tab bar padding the maker holds in one public int field (LX/0B2M;->A05)
 * and takes off each row; the extension reads it through a stub the patch fills with that field.
 * On the S22 that field read -1 on every row, and the maker works the height out once and hands
 * every row that, the first time before the Reels page is laid out. One of its sources is the list
 * itself: a helper it calls with the Activity, answering an Integer (LX/0AIZ;->A02), finds the
 * list by id and reads its height. The patch fills a second stub with that id, so the extension
 * reads the list the way Facebook does and remembers the list height for rows made before it.
 * 588's Android 9 build moves the log line into a static of its own (LX/0AsJ;->A0A) that the maker
 * (LX/0Asx;->BVs) calls with each value, so the patch follows the " computed=" and " tabPad="
 * parameters back to what the maker hands them.
 */

internal const val ROW_HEIGHT_LOG = " recyclerPadTop="
private const val ROW_HEIGHT_LABEL = " computed="
private const val TAB_PAD_LABEL = " tabPad="
internal const val REEL_ROW = "$TAB_BAR_SCROLL_AWAY->reelRow(ILjava/lang/Object;)I"
internal const val TAB_PADDING_STUB = "tabPadding"
internal const val REELS_LIST_ID_STUB = "reelsListId"

/**
 * Where the Reels list's maker stores the row height: the index of the one int store into another
 * class of the register its log appends after " computed=", and that register.
 */
internal fun reelRowStore(maker: Method): Pair<Int, Int>? {
    val code = maker.implementation?.instructions?.toList() ?: return null
    val (_, register) = appendedAfter(code, ROW_HEIGHT_LABEL) ?: return null
    return intStore(maker, code, register)
}

/**
 * [reelRowStore] for a maker that hands the row height to [helper], the static that logs it (588's
 * Android 9 build): the one int store into another class of the register the maker hands the helper
 * at the parameter its log appends after " computed=".
 */
internal fun reelRowStore(maker: Method, helper: Method): Pair<Int, Int>? {
    val code = maker.implementation?.instructions?.toList() ?: return null
    val (_, register) = handedFrom(maker, helper, ROW_HEIGHT_LABEL) ?: return null
    return intStore(maker, code, register)
}

/** The one int store of [register] into a class other than the maker's: its index and the register. */
private fun intStore(maker: Method, code: List<Instruction>, register: Int): Pair<Int, Int>? {
    val stores = code.withIndex().filter { (_, instruction) ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.IPUT && field?.type == "I" && field.definingClass != maker.definingClass &&
            (instruction as TwoRegisterInstruction).registerA == register
    }
    val store = stores.singleOrNull() ?: return null
    return store.index to register
}

/** The int a log line appends right after [label]: the index of that append and the register it appends. */
private fun appendedAfter(code: List<Instruction>, label: String): Pair<Int, Int>? {
    val at = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == label }
    if (at < 0) return null
    // The label's own append, then the append of the value.
    val appends = code.withIndex().drop(at + 1).take(4).filter { (_, it) ->
        it.opcode == Opcode.INVOKE_VIRTUAL && ((it as ReferenceInstruction).reference as MethodReference).let { ref ->
            ref.definingClass == "Ljava/lang/StringBuilder;" && ref.name == "append"
        }
    }
    val value = appends.getOrNull(1)?.takeIf { (_, it) ->
        ((it as ReferenceInstruction).reference as MethodReference).parameterTypes.singleOrNull()?.toString() == "I"
    } ?: return null
    val append = value.value as? FiveRegisterInstruction ?: return null
    return value.index to append.registerD
}

/** Whether [instruction] calls [method]. */
private fun calls(instruction: Instruction, method: Method): Boolean {
    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return ref.definingClass == method.definingClass && MethodUtil.methodSignaturesMatch(ref, method)
}

/** The index among [method]'s parameters of the one held in [register] as it starts, for a static method. */
private fun parameterAt(method: Method, register: Int): Int? {
    var next = method.implementation!!.registerCount - MethodUtil.getParameterRegisterCount(method)
    method.parameterTypes.map(CharSequence::toString).forEachIndexed { index, type ->
        if (register == next) return index
        next += if (type == "J" || type == "D") 2 else 1
    }
    return null
}

/** The register [call], a call of a static taking [parameterTypes], hands it at parameter [index]. */
private fun argumentRegister(call: Instruction, parameterTypes: List<String>, index: Int): Int? {
    val offset = parameterTypes.take(index).sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
    return when (call) {
        is RegisterRangeInstruction -> call.startRegister + offset
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
            .take(call.registerCount).getOrNull(offset)
        else -> null
    }
}

/**
 * Where the value [helper]'s log appends after [label] comes from in [maker]: the parameter the
 * append reads (through plain int moves, with nothing on the way writing it), the register the
 * maker's one call of [helper] hands at that parameter, and that register followed back through
 * plain int moves, each the only write reaching it. Answers the writes reaching the register it ends
 * on, and that register. Null unless [helper] is static and called once.
 */
private fun handedFrom(maker: Method, helper: Method, label: String): Pair<List<Int>, Int>? {
    if (!AccessFlags.STATIC.isSet(helper.accessFlags)) return null
    val code = helper.implementation?.instructions?.toList() ?: return null
    var (at, register) = appendedAfter(code, label) ?: return null
    while (true) {
        val writers = helper.writersReaching(at, register)
        if (writers == listOf(-1)) break
        val move = writers.singleOrNull()?.takeIf { it >= 0 }?.let(code::get)?.takeIf { it.opcode in INT_MOVES } ?: return null
        at = writers.single()
        register = (move as TwoRegisterInstruction).registerB
    }
    val parameter = parameterAt(helper, register) ?: return null
    val makerCode = maker.implementation?.instructions?.toList() ?: return null
    val call = makerCode.indices.filter { calls(makerCode[it], helper) }.singleOrNull() ?: return null
    var handed = argumentRegister(makerCode[call], helper.parameterTypes.map(CharSequence::toString), parameter) ?: return null
    at = call
    while (true) {
        val writers = maker.writersReaching(at, handed)
        val move = writers.singleOrNull()?.takeIf { it >= 0 }?.let(makerCode::get)?.takeIf { it.opcode in INT_MOVES }
            ?: return writers to handed
        at = writers.single()
        handed = (move as TwoRegisterInstruction).registerB
    }
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val INT_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/** Whether this instruction writes [register], as itself or as the upper half of a wide value. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val target = (this as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (opcode.setsWideRegister() && target + 1 == register)
}

/**
 * A register up to v15 that holds the maker itself wherever the method runs: `this` when it's that
 * low and never written, otherwise the one copy of `this` the method makes before anything branches,
 * into a register written nowhere else. Null when there's none.
 */
internal fun makerRegister(maker: Method): Int? {
    if (AccessFlags.STATIC.isSet(maker.accessFlags)) return null
    val implementation = maker.implementation ?: return null
    val code = implementation.instructions.toList()
    val self = implementation.registerCount - MethodUtil.getParameterRegisterCount(maker)
    fun writesTo(register: Int) = code.count { it.writes(register) }
    if (self <= 15 && writesTo(self) == 0) return self
    val jumpedTo = branchTargets(code)
    for ((index, instruction) in code.withIndex()) {
        if (index in jumpedTo || instruction is OffsetInstruction || !instruction.opcode.canContinue() || instruction.writes(self)) {
            return null
        }
        if (instruction.opcode in OBJECT_MOVES) {
            val move = instruction as TwoRegisterInstruction
            if (move.registerB == self && move.registerA <= 15 && writesTo(move.registerA) == 1) return move.registerA
        }
    }
    return null
}

/**
 * The maker's own int field its log appends after " tabPad=": the tab bar padding it takes off each
 * row. Followed back from the append through plain moves to the read of the field. Null when the
 * value comes from anything else.
 */
internal fun tabPadField(maker: Method): FieldReference? {
    val code = maker.implementation?.instructions?.toList() ?: return null
    var (at, register) = appendedAfter(code, TAB_PAD_LABEL) ?: return null
    while (--at >= 0) {
        val instruction = code[at]
        if (!instruction.writes(register)) continue
        if (instruction.opcode in INT_MOVES) {
            register = (instruction as TwoRegisterInstruction).registerB
            continue
        }
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        return field?.takeIf { instruction.opcode == Opcode.IGET && it.type == "I" && it.definingClass == maker.definingClass }
    }
    return null
}

/**
 * [tabPadField] for a maker that hands the padding to [helper], the static that logs it (588's
 * Android 9 build): the maker's own int field read into what it hands the helper at the parameter
 * its log appends after " tabPad=", that read the only write reaching the call.
 */
internal fun tabPadField(maker: Method, helper: Method): FieldReference? {
    val (writers, _) = handedFrom(maker, helper, TAB_PAD_LABEL) ?: return null
    val at = writers.singleOrNull()?.takeIf { it >= 0 } ?: return null
    val read = maker.implementation!!.instructions.elementAt(at)
    val field = (read as? ReferenceInstruction)?.reference as? FieldReference
    return field?.takeIf { read.opcode == Opcode.IGET && it.type == "I" && it.definingClass == maker.definingClass }
}

/**
 * The method that makes the Reels list, with its class: [logged] itself when it stores the row height,
 * or, when [logged] is a static that only logs it (588's Android 9 build: LX/0AsJ;->A0A), the one
 * method calling it. Null when that caller isn't one.
 */
internal fun BytecodePatchContext.reelListMaker(holder: ClassDef, logged: Method): Pair<ClassDef, Method>? {
    if (!AccessFlags.STATIC.isSet(logged.accessFlags) || reelRowStore(logged) != null) return holder to logged
    val callers = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEachReferencing(types = listOf(logged.definingClass)) { classDef ->
        classDef.methods.filter { method -> method.implementation?.instructions?.any { calls(it, logged) } == true }
            .forEach { callers += classDef to it }
    }
    return callers.singleOrNull()
}

/**
 * Fills the extension's tab padding stub with a read of [field] from a maker of [makerType]. False,
 * with nothing changed, when the stub isn't there.
 */
internal fun BytecodePatchContext.fillTabPadding(makerType: String, field: FieldReference): Boolean {
    val stub = mutableClassDefBy(TAB_BAR_SCROLL_AWAY).methods.singleOrNull {
        it.name == TAB_PADDING_STUB && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    } ?: return false
    stub.addInstructions(
        0,
        """
            check-cast p0, $makerType
            iget p0, p0, $field
            return p0
        """,
    )
    return true
}

/** The one helper the maker calls with the Activity that answers an Integer: the list's height. Null unless there's exactly one. */
internal fun listHeightHelper(maker: Method): MethodReference? {
    val asks = maker.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        ?.filter { it.returnType == "Ljava/lang/Integer;" && it.parameterTypes.firstOrNull()?.toString() == ACTIVITY }
        ?.distinctBy { it.toString() } ?: return null
    return asks.singleOrNull()
}

/** The view id [helper] finds first: the literal loaded right before its first Activity.findViewById, into the id register. */
internal fun firstViewId(helper: Method): Int? {
    val code = helper.implementation?.instructions?.toList() ?: return null
    for ((load, find) in code.zipWithNext()) {
        val ref = (find as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (ref.definingClass != ACTIVITY || ref.name != "findViewById") continue
        val literal = load as? NarrowLiteralInstruction ?: return null
        return literal.narrowLiteral.takeIf { (find as FiveRegisterInstruction).registerD == (load as OneRegisterInstruction).registerA }
    }
    return null
}

/** Fills the extension's list id stub with [id]. False, with nothing changed, when the stub isn't there. */
internal fun BytecodePatchContext.fillReelsListId(id: Int): Boolean {
    val stub = mutableClassDefBy(TAB_BAR_SCROLL_AWAY).methods.singleOrNull {
        it.name == REELS_LIST_ID_STUB && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty()
    } ?: return false
    stub.returnEarly(id)
    return true
}

/**
 * Hands the Reels row height, with the list's maker, to the extension right before the maker stores
 * it, and fills the extension's stubs with the maker's padding field and the list's id. Warns and
 * does nothing unless there's one maker, one such store, a low register holding the maker and
 * nothing jumping to the store. A padding field or list id that can't be found or read only costs
 * its stub, with a warning.
 */
internal fun BytecodePatchContext.hookReelRow(warn: (String) -> Unit) {
    val holders = classDefByStrings(ROW_HEIGHT_LOG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
    val holder = holders.singleOrNull() ?: return warn("expected one class logging \"$ROW_HEIGHT_LOG\", found ${holders.size}")
    val makers = holder.methods.filter { method ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ROW_HEIGHT_LOG
        } == true
    }
    val logged = makers.singleOrNull() ?: return warn("expected one method of ${holder.type} logging \"$ROW_HEIGHT_LOG\", found ${makers.size}")
    val (owner, found) = reelListMaker(holder, logged)
        ?: return warn("expected one method calling ${holder.type}->${logged.name}, the static logging the row height")
    // The static that logs what the maker hands it, when the maker doesn't log itself.
    val logger = logged.takeIf { found !== logged }
    // The mutable copy, so another patch's additions to the method can't shift the index.
    val maker = mutableClassDefBy(owner.type).findMutableMethodOf(found)
    val (store, register) = (if (logger == null) reelRowStore(maker) else reelRowStore(maker, logger))
        ?: return warn("expected one store of the row height logged in ${owner.type}->${maker.name}")
    val self = makerRegister(maker) ?: return warn("${owner.type}->${maker.name} keeps itself in no register up to v15")
    val code = maker.implementation!!.instructions.toList()
    if (register > 15 || store in branchTargets(code)) return warn("${owner.type}->${maker.name} can't take the row hook")
    val padding = (if (logger == null) tabPadField(maker) else tabPadField(maker, logger))?.takeIf { field ->
        AccessFlags.PUBLIC.isSet(owner.accessFlags) && owner.fields.any {
            it.name == field.name && it.type == "I" && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
    }
    when {
        padding == null -> warn("no public tab bar padding field logged in ${owner.type}->${maker.name}, Reels rows grow by the dropped padding alone")
        !fillTabPadding(owner.type, padding) -> warn("$TAB_BAR_SCROLL_AWAY has no $TAB_PADDING_STUB stub, Reels rows grow by the dropped padding alone")
    }
    val helper = listHeightHelper(maker)
    val listId = helper?.let { ask -> classDefByOrNull(ask.definingClass)?.methods?.singleOrNull { MethodUtil.methodSignaturesMatch(it, ask) } }
        ?.let(::firstViewId)
    when {
        listId == null -> warn("no Reels list id read through ${helper ?: "a list height helper"} in ${owner.type}->${maker.name}, Reels rows don't take the list's height")
        !fillReelsListId(listId) -> warn("$TAB_BAR_SCROLL_AWAY has no $REELS_LIST_ID_STUB stub, Reels rows don't take the list's height")
    }
    maker.addInstructions(
        store,
        """
            invoke-static { v$register, v$self }, $REEL_ROW
            move-result v$register
        """,
    )
}
