/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Why Home could keep loading posts and show none of them (read from 582, 2026-10-15).
 *
 * Litho's RecyclerBinder adds rows that arrive while its list is on screen in batches, and its
 * applyReadyBatches puts the batch at the head of the queue in only once every inserted row's
 * holder says it has a layout (the holder's hasCompletedLatestLayout). That asks the row's
 * ComponentTree whether it holds a layout for the holder's size specs, and the tree's answer also
 * wants the layout made in the accessibility state the phone has now.
 *
 * The tree's setRootAndSizeSpec skips making a layout when its last layout has the same root and
 * fits the size, and that test leaves the accessibility state out, as does the layout step's reuse
 * of an unchanged layout. So once the state changes between a row's layout and the batch's check,
 * the row asks for a layout, the tree says it already has one, and the holder never says it has
 * one: the binder counts the ask as still running, never asks again, and every later batch queues
 * behind it. A probe at a stalled Home on the emulator showed the head row's tree holding both
 * layouts for the right specs, made with accessibility on, while the phone had it off; the queue
 * grew from 1 to 55 batches while the feed collection and the adapter's list both kept growing.
 * UiAutomation turns accessibility on for every screen read, which is how emulator scans that read
 * the screen after each swipe kept hitting it; on a phone it takes a screen reader or another
 * service turning on or off while Home adds rows.
 *
 * So where applyReadyBatches finds a holder without its layout, it asks a helper added to the
 * holder class whether the tree holds a layout that fits the holder's specs in either state, and
 * the extension (HeldInserts) lets the row in when it does. No layout made in the other state
 * reaches the screen that way: the tree's measure step does compare the accessibility state, so
 * when the list measures the row it lays it out again on the main thread, as it does for any row
 * on screen after such a change.
 *
 * A layout the tree started before such a change and finished after it never gets that far. Litho's
 * commit step runs the same layout test, so it drops the layout (it logs Litho.AsyncLayout.Dropped)
 * and asks for no other: the holder counts its ask as done and the tree holds no layout at all. An
 * emulator scan reading the screen back to back left Home's head row that way, its resolve result
 * in place and neither layout set, with the queue at 60 batches. So the commit step's call of the
 * layout test goes through a helper added to ComponentTree, which also takes a layout made in the
 * other state when it fits the tree's specs and the extension agrees. The tree then holds a layout
 * the first hook finds the next time the binder checks the row.
 */

/** Kept literal: the trace section applyReadyBatches opens with, which no other method loads. */
internal const val APPLY_READY_BATCHES = "collections.applyReadyBatches"

/** Kept class: Litho's ComponentTree. */
internal const val COMPONENT_TREE = "Lcom/facebook/litho/ComponentTree;"

/** Kept class: what the tree's layout test reads the accessibility state from. */
private const val ACCESSIBILITY_MANAGER = "Landroid/view/accessibility/AccessibilityManager;"

/** The helper this adds to the holder class. */
internal const val LAID_OUT_HELPER = "hushfacebookLaidOut"

internal const val LET_IN = "$EXTENSION_PACKAGE/feed/HeldInserts;->letIn(Z)Z"

/** How a refusal of the held rows hook names it. */
private const val HELD_ROWS = "Feed filter (held rows)"

/** Kept literal: what Litho logs when it drops a layout it finished, which only its commit step loads. */
internal const val LAYOUT_DROPPED = "Litho.AsyncLayout.Dropped"

/** The helper this adds to ComponentTree. */
internal const val COMMITTABLE_HELPER = "hushfacebookCommittable"

internal const val KEEP_LAYOUT = "$EXTENSION_PACKAGE/feed/HeldInserts;->keepLayout(Z)Z"

/** How a refusal of the held layouts hook names it. */
private const val HELD_LAYOUTS = "Feed filter (held layouts)"

/**
 * Where Litho's commit step tests a layout it finished: [method]: [index], its call of the tree's
 * layout test [test], and [fits], the layout's own size test that [test] calls.
 */
internal data class DroppedLayoutSite(
    val method: Method,
    val index: Int,
    val test: MethodReference,
    val fits: MethodReference,
)

/**
 * Where the held rows hook goes in applyReadyBatches, [method]: [index], the if-nez on the holder's
 * answer; [op] and [holderField], the register holding the insert operation and its holder field;
 * [layoutCheck], the holder's hasCompletedLatestLayout.
 */
internal data class HeldInsertSite(
    val method: Method,
    val index: Int,
    val op: Int,
    val holderField: FieldReference,
    val layoutCheck: MethodReference,
)

/**
 * What the helper reads, all found in the holder's [HeldInsertSite.layoutCheck]: the holder's
 * [tree] and the [width] and [height] specs it last asked a layout for, the tree's two layouts
 * ([layouts], background then main thread) and the layout's own size test, [fits], which the
 * tree's layout test calls before it compares the accessibility state.
 */
internal data class LaidOutParts(
    val holder: String,
    val tree: FieldReference,
    val width: FieldReference,
    val height: FieldReference,
    val layouts: List<FieldReference>,
    val fits: MethodReference,
)

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/**
 * The held rows site in [owner], or null when it isn't the RecyclerBinder: one static method
 * holding [APPLY_READY_BATCHES], and in it one no-argument boolean call on an app class's holder
 * read off an operation (another class) in the line before, whose answer goes to an if-nez. 586's
 * Android 9 build makes that check in a static boolean method of the binder that the first calls.
 */
internal fun heldInsertSite(owner: ClassDef): HeldInsertSite? {
    val apply = owner.methods.singleOrNull {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && holdsString(it, APPLY_READY_BATCHES)
    } ?: return null
    heldInsertSiteIn(apply, owner)?.let { return it }
    val called = apply.code().mapNotNull { it.reference() as? MethodReference }
        .filter { it.definingClass == owner.type && it.returnType == "Z" }
        .map { "${it.name}(${it.parameterTypes.joinToString("")})" }.toSet()
    return owner.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && "${it.name}(${it.parameterTypes.joinToString("")})" in called
    }.mapNotNull { heldInsertSiteIn(it, owner) }.singleOrNull()
}

private fun heldInsertSiteIn(apply: Method, owner: ClassDef): HeldInsertSite? {
    val code = apply.code()
    val sites = (1 until code.size - 2).mapNotNull { at ->
        val call = code[at]
        val read = code[at - 1]
        val check = call.reference() as? MethodReference ?: return@mapNotNull null
        val field = read.reference() as? FieldReference ?: return@mapNotNull null
        if (call.opcode != Opcode.INVOKE_VIRTUAL || check.returnType != "Z" || check.parameterTypes.isNotEmpty()) return@mapNotNull null
        if (read.opcode != Opcode.IGET_OBJECT || field.type != check.definingClass) return@mapNotNull null
        // Read off an operation, not off the binder (its released flag is an AtomicBoolean it reads the same way).
        if (field.definingClass == owner.type || check.definingClass.startsWith("Ljava/")) return@mapNotNull null
        val holder = (read as TwoRegisterInstruction).registerA
        if ((call as FiveRegisterInstruction).registerCount != 1 || call.registerC != holder) return@mapNotNull null
        val result = code[at + 1]
        if (result.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        val answer = (result as OneRegisterInstruction).registerA
        val branch = code[at + 2]
        if (branch.opcode != Opcode.IF_NEZ || (branch as OneRegisterInstruction).registerA != answer) return@mapNotNull null
        // The hook reads the holder off the operation again, so the answer can't overwrite it.
        if (read.registerB == answer) return@mapNotNull null
        HeldInsertSite(apply, at + 2, read.registerB, field, check)
    }
    val site = sites.singleOrNull() ?: return null
    // Code in front of the if-nez runs only if nothing but the answer's move-result reaches it.
    val flow = ControlFlow.of(apply)
    val into = code.indices.filter { site.index in flow.normal[it] }
    return site.takeIf { into == listOf(site.index - 1) }
}

/** The instruction before [at] in [code] that last writes [register], when it's an iget of a field. */
private fun fieldInto(code: List<Instruction>, at: Int, register: Int): FieldReference? {
    val write = (at - 1 downTo 0).map { code[it] }.firstOrNull {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
    } ?: return null
    if (write.opcode != Opcode.IGET && write.opcode != Opcode.IGET_OBJECT) return null
    return write.reference() as? FieldReference
}

/**
 * What the helper reads, from the holder's layout check [check] in [holder] and the tree's layout
 * test in [tree], or null when they aren't the shapes this knows: the check reads one tree and two
 * int specs off the holder and calls one static tree method (tree, layout, int, int) twice, each
 * time on a layout it read off the tree; that method calls one (int, int) boolean on the layout and
 * reads the accessibility manager.
 */
internal fun laidOutParts(holder: ClassDef, check: MethodReference, tree: ClassDef): LaidOutParts? {
    val method = holder.methods.singleOrNull {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return null
    val code = method.code()
    val treeField = code.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.reference() as? FieldReference }
        .singleOrNull { it.definingClass == holder.type && it.type == COMPONENT_TREE } ?: return null

    val tests = code.indices.filter { code[it].opcode == Opcode.INVOKE_STATIC && isTreeLayoutTest(code[it].reference()) }
    if (tests.size != 2) return null
    val test = code[tests[0]].reference() as MethodReference
    if (code[tests[1]].reference() != test) return null
    val layoutType = test.parameterTypes[1].toString()
    // Each call is (tree, layout, width, height): the layout comes off the tree, the specs off the holder.
    val reads = tests.map { at ->
        val call = code[at] as FiveRegisterInstruction
        listOf(call.registerD, call.registerE, call.registerF).map { fieldInto(code, at, it) ?: return null }
    }
    val layouts = reads.map { it[0] }
    if (layouts.any { it.definingClass != COMPONENT_TREE || it.type != layoutType } || layouts[0] == layouts[1]) return null
    val (width, height) = reads[0].drop(1)
    if (reads[1].drop(1) != listOf(width, height) || width == height) return null
    if (listOf(width, height).any { it.definingClass != holder.type || it.type != "I" }) return null

    val fits = layoutFits(tree, test) ?: return null
    return LaidOutParts(holder.type, treeField, width, height, layouts, fits)
}

/** Whether [ref] is a static ComponentTree method shaped (tree, layout, int, int) boolean. */
private fun isTreeLayoutTest(ref: Any?): Boolean = ref is MethodReference && ref.definingClass == COMPONENT_TREE &&
    ref.returnType == "Z" && ref.parameterTypes.size == 4 && ref.parameterTypes[0] == COMPONENT_TREE &&
    ref.parameterTypes[2] == "I" && ref.parameterTypes[3] == "I"

/**
 * The layout's own size test that the tree's layout test [test] in [tree] calls, or null when [test]
 * isn't the one that also compares the accessibility state: it has to read the accessibility manager
 * and call exactly one (int, int) boolean on its layout parameter's type.
 */
private fun layoutFits(tree: ClassDef, test: MethodReference): MethodReference? {
    val layoutType = test.parameterTypes[1].toString()
    val treeTest = tree.methods.singleOrNull {
        it.name == test.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == test.parameterTypes.map(CharSequence::toString)
    } ?: return null
    val treeCode = treeTest.code()
    if (treeCode.none { (it.reference() as? FieldReference)?.type == ACCESSIBILITY_MANAGER }) return null
    return treeCode.mapNotNull { it.reference() as? MethodReference }.filter {
        it.definingClass == layoutType && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I")
    }.distinct().singleOrNull()
}

/**
 * Where Litho's commit step tests a layout it finished, in [owner], or null when [owner] isn't that
 * step: one method holding [LAYOUT_DROPPED], and in it one static call of the tree's layout test,
 * the one that compares the accessibility state, whose answer a move-result takes.
 */
internal fun droppedLayoutSite(owner: ClassDef, tree: ClassDef): DroppedLayoutSite? {
    val commit = owner.methods.singleOrNull { holdsString(it, LAYOUT_DROPPED) } ?: return null
    val code = commit.code()
    val at = code.indices.singleOrNull {
        (code[it].opcode == Opcode.INVOKE_STATIC || code[it].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            isTreeLayoutTest(code[it].reference())
    } ?: return null
    if (code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT) return null
    val test = code[at].reference() as MethodReference
    val fits = layoutFits(tree, test) ?: return null
    return DroppedLayoutSite(commit, at, test, fits)
}

/**
 * The RecyclerBinder's held rows site and what its helper reads. Exactly one class holding
 * [APPLY_READY_BATCHES] has to answer [heldInsertSite], and its holder and the tree have to answer
 * [laidOutParts]; anything else stops the patch, as a hook in the wrong place could hold rows that
 * Facebook would have let in.
 */
internal fun BytecodePatchContext.heldInsertSite(): Pair<HeldInsertSite, LaidOutParts> {
    val holders = classDefByStrings(APPLY_READY_BATCHES, StringComparisonType.EQUALS)
    val sites = holders.mapNotNull(::heldInsertSite)
    val site = sites.singleOrNull() ?: throw PatchException(
        "$HELD_ROWS: expected one RecyclerBinder whose applyReadyBatches holds \"$APPLY_READY_BATCHES\" and " +
            "checks each inserted row's layout, found ${sites.size} among ${holders.count()} class(es) holding the string.",
    )
    val holder = classDefByOrNull(site.layoutCheck.definingClass)
        ?: throw PatchException("$HELD_ROWS: the row holder ${site.layoutCheck.definingClass} isn't in the app")
    val tree = classDefByOrNull(COMPONENT_TREE) ?: throw PatchException("$HELD_ROWS: $COMPONENT_TREE isn't in the app")
    val parts = laidOutParts(holder, site.layoutCheck, tree) ?: throw PatchException(
        "$HELD_ROWS: ${site.layoutCheck} no longer reads one tree, two size specs and the tree's two layouts the way this expects",
    )
    return site to parts
}

/**
 * Litho's commit step. Exactly one class holding [LAYOUT_DROPPED] has to answer [droppedLayoutSite];
 * anything else stops the patch, as a call swapped in the wrong place could keep layouts Litho is
 * right to drop.
 */
internal fun BytecodePatchContext.droppedLayoutSite(): DroppedLayoutSite {
    val tree = classDefByOrNull(COMPONENT_TREE) ?: throw PatchException("$HELD_LAYOUTS: $COMPONENT_TREE isn't in the app")
    val owners = classDefByStrings(LAYOUT_DROPPED, StringComparisonType.EQUALS)
    val sites = owners.mapNotNull { droppedLayoutSite(it, tree) }
    return sites.singleOrNull() ?: throw PatchException(
        "$HELD_LAYOUTS: expected one commit step holding \"$LAYOUT_DROPPED\" that calls the tree's layout test once, " +
            "found ${sites.size} among ${owners.count()} class(es) holding the string.",
    )
}

/**
 * The helper added to the holder class: whether the holder's tree holds a layout, background or main
 * thread, that fits the size specs the holder last asked for, whatever the accessibility state it
 * was made in. The reads take no locks, as a stale answer only means the next check decides.
 */
internal fun laidOutHelper(parts: LaidOutParts) = ImmutableMethod(
    parts.holder,
    LAID_OUT_HELPER,
    listOf(ImmutableMethodParameter(parts.holder, null, null)),
    "Z",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(5),
).toMutable().apply {
    addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${parts.tree}
            if-eqz v0, :no
            iget v1, p0, ${parts.width}
            iget v2, p0, ${parts.height}
            iget-object v3, v0, ${parts.layouts[0]}
            if-eqz v3, :main
            invoke-virtual { v3, v1, v2 }, ${parts.fits}
            move-result v3
            if-nez v3, :yes
            :main
            iget-object v3, v0, ${parts.layouts[1]}
            if-eqz v3, :no
            invoke-virtual { v3, v1, v2 }, ${parts.fits}
            move-result v3
            if-nez v3, :yes
            :no
            const/4 v0, 0x0
            return v0
            :yes
            const/4 v0, 0x1
            return v0
        """,
    )
}

/**
 * In front of applyReadyBatches' if-nez on the holder's answer: when the answer is no, reads the
 * holder off the operation again, asks the helper, and hands its answer to the extension, whose
 * answer the if-nez then reads. The answer's register held the holder before the call, so it's
 * free to reuse, and the operation's register still holds the operation.
 */
internal fun MutableMethod.askAboutHeldRows(site: HeldInsertSite, parts: LaidOutParts) {
    val answer = (getInstruction(site.index) as OneRegisterInstruction).registerA
    if (answer > 15 || site.op > 15) throw PatchException("$HELD_ROWS: v$answer or v${site.op} is above v15")
    val helper = "${parts.holder}->$LAID_OUT_HELPER(${parts.holder})Z"
    addInstructionsWithLabels(
        site.index,
        """
            if-nez v$answer, :checked
            iget-object v$answer, v${site.op}, ${site.holderField}
            invoke-static { v$answer }, $helper
            move-result v$answer
            invoke-static { v$answer }, $LET_IN
            move-result v$answer
        """,
        ExternalLabel("checked", getInstruction(site.index)),
    )
}

/** How the commit step calls the helper added to ComponentTree. */
internal fun committableCall(site: DroppedLayoutSite) =
    "$COMPONENT_TREE->$COMMITTABLE_HELPER(${site.test.parameterTypes.joinToString("")})Z"

/**
 * The helper added to ComponentTree, in place of the tree's layout test at the commit step: the
 * test's own answer when it's yes, and otherwise, when the layout fits the specs it was tested
 * against, the extension's answer on keeping it although it was made in the other accessibility
 * state.
 */
internal fun committableHelper(site: DroppedLayoutSite) = ImmutableMethod(
    COMPONENT_TREE,
    COMMITTABLE_HELPER,
    site.test.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) },
    "Z",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(5),
).toMutable().apply {
    addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1, p2, p3 }, ${site.test}
            move-result v0
            if-nez v0, :done
            if-eqz p1, :done
            invoke-virtual { p1, p2, p3 }, ${site.fits}
            move-result v0
            invoke-static { v0 }, $KEEP_LAYOUT
            move-result v0
            :done
            return v0
        """,
    )
}

/**
 * Swaps the commit step's call of the tree's layout test for the helper, on the same registers,
 * so its move-result and the branch after it read the helper's answer.
 */
internal fun MutableMethod.keepHeldLayouts(site: DroppedLayoutSite) {
    val call = getInstruction(site.index)
    val registers = when (call) {
        is FiveRegisterInstruction -> "invoke-static { " +
            listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
                .take(call.registerCount).joinToString { "v$it" } + " }"
        is RegisterRangeInstruction ->
            "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }"
        else -> throw PatchException("$HELD_LAYOUTS: ${call.opcode} at ${site.index} isn't a call")
    }
    replaceInstruction(site.index, "$registers, ${committableCall(site)}")
}

/**
 * Lets a list's new row in when only an accessibility change holds it back (HeldInserts in the
 * extension), whether its layout was made before the change or finished after it. The feed guard
 * brings it along with the tail fetch hook, as both keep Home showing what it loads, and the
 * extension leaves Litho's answers alone while paused.
 */
internal val heldInsertsPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val (site, parts) = heldInsertSite()
        val dropped = droppedLayoutSite()
        mutableClassDefBy(parts.holder).methods.add(laidOutHelper(parts))
        mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(CharSequence::toString) == site.method.parameterTypes.map(CharSequence::toString)
        }.askAboutHeldRows(site, parts)
        mutableClassDefBy(COMPONENT_TREE).methods.add(committableHelper(dropped))
        mutableClassDefBy(dropped.method.definingClass).methods.single {
            it.name == dropped.method.name && it.returnType == dropped.method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == dropped.method.parameterTypes.map(CharSequence::toString)
        }.keepHeldLayouts(dropped)
    }
}
