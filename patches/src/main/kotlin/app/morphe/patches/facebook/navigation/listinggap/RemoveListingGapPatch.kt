/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.listinggap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val PATCH = "Remove the gap above Marketplace listings"

/**
 * Facebook's SafeArea module for React Native. JavaScript calls safeAreaInsets by name, so the class
 * and that method keep their names in every build.
 */
internal const val SAFE_AREA = "Lcom/facebook/fbreact/safearea/ReactSafeArea;"
internal const val SAFE_AREA_READ = "safeAreaInsets"
internal const val INSETS = "Landroidx/core/graphics/Insets;"
private const val WRITABLE_MAP = "Lcom/facebook/react/bridge/WritableNativeMap;"
private const val ROOT_WINDOW_INSETS = "Landroid/view/View;->getRootWindowInsets()Landroid/view/WindowInsets;"

/**
 * React Native's SafeAreaView manager. Its name is how JavaScript finds the view, so the class keeps
 * it in every build, though the view it makes and that view's insets listener are renamed.
 */
internal const val SAFE_AREA_VIEW_MANAGER = "Lcom/facebook/react/views/safeareaview/ReactSafeAreaViewManager;"
private const val VIEW = "Landroid/view/View;"
private const val WINDOW_INSETS = "Landroidx/core/view/WindowInsetsCompat;"

internal const val LISTING_GAP = "$EXTENSION_PACKAGE/navigation/ListingGap;"
internal const val TRIM = "$LISTING_GAP->insets(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val TRIM_VIEW = "$LISTING_GAP->viewInsets(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/** The module's static (Insets) method that turns insets into the map JavaScript reads. */
internal fun isInsetsMap(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == WRITABLE_MAP &&
        method.parameterTypes.map { it.toString() } == listOf(INSETS) && method.implementation != null

/** Whether [instruction] is an invoke-static of [map]. */
internal fun callsInsetsMap(instruction: Instruction, map: Method): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC) return false
    val called = (instruction as ReferenceInstruction).reference as MethodReference
    return called.definingClass == map.definingClass && called.name == map.name &&
        called.parameterTypes.map { it.toString() } == listOf(INSETS) && called.returnType == map.returnType
}

/**
 * The classes [method] makes with new-instance. The listener the module sets on the decor view is one
 * of those safeAreaInsets makes, a lambda that also turns insets into the map, for the change
 * JavaScript is told about. The SafeAreaView's listener is one of those its onAttachedToWindow makes.
 */
internal fun madeBy(method: Method): List<String> =
    method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()

/**
 * Whether any of [code] from [from] up to [until] puts another value in [register]. A check-cast
 * keeps the same object, and 585's listener casts its view that way in its other branches.
 */
private fun writes(code: List<Instruction>, from: Int, until: Int, register: Int) = (from until until).any {
    val written = (code[it] as? OneRegisterInstruction)?.registerA
    code[it].opcode != Opcode.CHECK_CAST && code[it].opcode.setsRegister() && written != null &&
        (written == register || (code[it].opcode.setsWideRegister() && written + 1 == register))
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Whether [register] can hold nothing but [method]'s [parameter] as the instruction at [index]
 * starts, on every way there: each write that can reach it copies the parameter or check-casts it,
 * which keeps the same object.
 */
private fun holdsParameter(method: Method, index: Int, register: Int, parameter: Int, seen: MutableSet<Pair<Int, Int>> = mutableSetOf()): Boolean {
    if (!seen.add(index to register)) return true
    val code = method.implementation!!.instructions.toList()
    return method.writersReaching(index, register).all { writer ->
        when {
            writer == -1 -> register == parameter
            code[writer].opcode == Opcode.CHECK_CAST -> holdsParameter(method, writer, register, parameter, seen)
            code[writer].opcode in OBJECT_MOVES ->
                holdsParameter(method, writer, (code[writer] as TwoRegisterInstruction).registerB, parameter, seen)
            else -> false
        }
    }
}

/**
 * The register holding the decor view at the call to the insets map at [index] in [method]. In the
 * listener, a lambda's invoke(Object, Object), that's its first parameter, the view the listener was
 * set on, or a copy of it in v0 to v15 when the parameter sits past v15, as long as nothing on the
 * way to the call writes it. 587's 32-bit listener is a merged lambda that copies its view to v5 first
 * and writes the parameter only in a branch of its own (`LX/0Cx1;->invoke`). Anywhere else it's the
 * view whose getRootWindowInsets the method read last, as long as nothing wrote it since.
 */
internal fun decorRegister(method: Method, index: Int): Int {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    if (method.name == "invoke" && method.parameterTypes.size == 2 && !AccessFlags.STATIC.isSet(method.accessFlags)) {
        // p0 is this, p1 the view, p2 the insets.
        val view = method.implementation!!.registerCount - 2
        val holding = (listOf(view) + (0..15)).distinct().filter { holdsParameter(method, index, it, view) }
        if (holding.isEmpty()) throw PatchException("$PATCH: $where writes over its view before the insets map")
        return holding.firstOrNull { it <= 15 } ?: view
    }
    val read = (index - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.INVOKE_VIRTUAL &&
            ((code[it] as ReferenceInstruction).reference as MethodReference).toString() == ROOT_WINDOW_INSETS
    } ?: throw PatchException("$PATCH: no decor view before the insets map in $where")
    val view = (code[read] as FiveRegisterInstruction).registerC
    if (writes(code, read + 1, index, view)) throw PatchException("$PATCH: $where writes over the decor view before the insets map")
    return view
}

/** Every call to [map] in [method], last first, with the registers of its insets and its decor view. */
internal fun insetsMapCalls(method: Method, map: Method): List<Triple<Int, Int, Int>> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { callsInsetsMap(code[it], map) }.reversed().map {
        Triple(it, (code[it] as FiveRegisterInstruction).registerC, decorRegister(method, it))
    }
}

/**
 * Every method that turns insets into the safe area map, safeAreaInsets and the decor listener, as
 * found in [context]: throws unless there is the read and at least one listener.
 */
internal fun BytecodePatchContext.insetsMapCallers(): Pair<Method, List<MutableMethod>> {
    val module = mutableClassDefByOrNull(SAFE_AREA) ?: throw PatchException("$PATCH: this build has no $SAFE_AREA")
    val map = module.methods.singleOrNull(::isInsetsMap)
        ?: throw PatchException("$PATCH: expected one static ($INSETS)$WRITABLE_MAP on $SAFE_AREA")
    val read = module.methods.singleOrNull { it.name == SAFE_AREA_READ && it.implementation != null }
        ?: throw PatchException("$PATCH: $SAFE_AREA has no $SAFE_AREA_READ")
    if (read.implementation!!.instructions.none { callsInsetsMap(it, map) }) {
        throw PatchException("$PATCH: $SAFE_AREA_READ doesn't turn insets into the map")
    }
    val listeners = madeBy(read).mapNotNull { mutableClassDefByOrNull(it) }
        .flatMap { it.methods }.filter { method -> method.implementation?.instructions?.any { callsInsetsMap(it, map) } == true }
    if (listeners.isEmpty()) throw PatchException("$PATCH: no listener made in $SAFE_AREA_READ turns insets into the map")
    return map to listOf(read) + listeners
}

/**
 * Hands the insets to [TRIM] with the decor view before each call to the map in [method], and
 * puts what it answers, cast back to Insets, where the map reads them.
 */
internal fun MutableMethod.trimBeforeInsetsMap(map: Method) {
    for ((index, insets, decor) in insetsMapCalls(this, map)) {
        if (insets > 15 || decor > 15) {
            throw PatchException("$PATCH: $definingClass->$name keeps the insets in v$insets and the view in v$decor, past v15")
        }
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$insets, v$decor }, $TRIM
                move-result-object v$insets
                check-cast v$insets, $INSETS
            """,
        )
    }
}

/** The view the SafeAreaView [manager] makes: the one type its View-returning methods make. */
internal fun safeAreaViewType(manager: ClassDef): String =
    manager.methods.filter { it.returnType == VIEW }.flatMap(::madeBy).distinct().singleOrNull()
        ?: throw PatchException("$PATCH: expected $SAFE_AREA_VIEW_MANAGER to make one view")

/** Whether [method] is an androidx insets listener: onApplyWindowInsets(View, WindowInsetsCompat). */
internal fun isInsetsListener(method: Method): Boolean =
    method.name == "onApplyWindowInsets" && method.returnType == WINDOW_INSETS &&
        method.parameterTypes.map { it.toString() } == listOf(VIEW, WINDOW_INSETS) && method.implementation != null

private val INVOKES = setOf(
    Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_DIRECT, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_SUPER,
    Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_DIRECT_RANGE,
    Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_SUPER_RANGE,
)

/** Whether [instruction] is an invoke of a method that answers androidx Insets. */
private fun readsInsets(instruction: Instruction): Boolean =
    instruction.opcode in INVOKES && ((instruction as ReferenceInstruction).reference as MethodReference).returnType == INSETS

/**
 * Where [method] casts to the SafeAreaView [viewType], last first, each with the registers of the
 * insets it read before that cast and of the view. The insets are the result of the last call before
 * the cast that answers Insets, as long as nothing writes that register in between.
 */
internal fun safeAreaViewReads(method: Method, viewType: String): List<Triple<Int, Int, Int>> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val where = "${method.definingClass}->${method.name}"
    return code.indices.filter {
        code[it].opcode == Opcode.CHECK_CAST && ((code[it] as ReferenceInstruction).reference as TypeReference).type == viewType
    }.reversed().map { cast ->
        val read = (cast - 1 downTo 1).firstOrNull {
            code[it].opcode == Opcode.MOVE_RESULT_OBJECT && readsInsets(code[it - 1])
        } ?: throw PatchException("$PATCH: no insets read before $where casts to its view")
        val insets = (code[read] as OneRegisterInstruction).registerA
        if (writes(code, read + 1, cast, insets)) throw PatchException("$PATCH: $where writes over its insets before the cast")
        Triple(cast, insets, (code[cast] as OneRegisterInstruction).registerA)
    }
}

/**
 * React Native's SafeAreaView, as found in [context]: its type and every insets listener its
 * onAttachedToWindow makes that pads it. Throws unless there's at least one.
 */
internal fun BytecodePatchContext.safeAreaViewListeners(): Pair<String, List<MutableMethod>> {
    val manager = mutableClassDefByOrNull(SAFE_AREA_VIEW_MANAGER)
        ?: throw PatchException("$PATCH: this build has no $SAFE_AREA_VIEW_MANAGER")
    val viewType = safeAreaViewType(manager)
    val attached = mutableClassDefByOrNull(viewType)?.methods?.singleOrNull {
        it.name == "onAttachedToWindow" && it.parameterTypes.isEmpty() && it.implementation != null
    } ?: throw PatchException("$PATCH: $viewType has no onAttachedToWindow")
    val listeners = madeBy(attached).mapNotNull { mutableClassDefByOrNull(it) }.flatMap { it.methods }
        .filter { isInsetsListener(it) && safeAreaViewReads(it, viewType).isNotEmpty() }
    if (listeners.isEmpty()) throw PatchException("$PATCH: no insets listener made when $viewType is attached pads it")
    return viewType to listeners
}

/**
 * Right after each cast to the SafeAreaView [viewType], hands the insets just read to [TRIM_VIEW]
 * with the view, and puts what it answers, cast back to Insets, where the view's padding reads them.
 */
internal fun MutableMethod.trimSafeAreaView(viewType: String) {
    for ((cast, insets, view) in safeAreaViewReads(this, viewType)) {
        if (insets > 15 || view > 15) {
            throw PatchException("$PATCH: $definingClass->$name keeps the insets in v$insets and the view in v$view, past v15")
        }
        addInstructions(
            cast + 1,
            """
                invoke-static { v$insets, v$view }, $TRIM_VIEW
                move-result-object v$insets
                check-cast v$insets, $INSETS
            """,
        )
    }
}

/**
 * Takes the empty band out from above a Marketplace listing. A listing is a React Native page whose
 * header pads its top by the safe area Facebook's SafeArea module reports, the whole window's insets.
 * On Android 17 the page itself already sits below the status bar, so the top is padded twice
 * (#333). Both places the module turns insets into the map JavaScript reads, the value a page asks
 * for and the decor listener that tells it about changes, hand the insets and the decor view to the
 * extension's ListingGap first, which keeps only the part of each side that overlaps the page.
 * React Native's SafeAreaView pads by the insets Android hands it without asking the module, so its
 * insets listener hands those to ListingGap too, with the view, right after it casts to it.
 *
 * Included in the default selection: it only takes out space Facebook added twice, and a page drawn
 * edge to edge gets the same insets as before.
 */
@Suppress("unused")
val removeListingGapPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Remove the gap above Marketplace listings",
    description = "Takes out the empty band some phones show between the status bar and a Marketplace listing's " +
        "close and share buttons, like Pixels on Android 17. Phones without the gap look the same as before. " +
        "On by default. Turn it off in Hushfacebook settings > Marketplace.",
    default = true,
) {
    // Interface is full; this one fixes a layout Facebook gets wrong on newer Android.
    category("Fixes")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val (map, callers) = insetsMapCallers()
        val (viewType, viewListeners) = safeAreaViewListeners()
        // Every call is checked before any is changed, so a build missing a register leaves all as they were.
        callers.forEach { insetsMapCalls(it, map) }
        viewListeners.forEach { safeAreaViewReads(it, viewType) }
        callers.forEach { it.trimBeforeInsetsMap(map) }
        viewListeners.forEach { it.trimSafeAreaView(viewType) }
        enableStatus("listingGap")
    }
}
