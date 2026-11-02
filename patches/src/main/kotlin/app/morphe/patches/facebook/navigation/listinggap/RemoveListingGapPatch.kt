/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.listinggap

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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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

internal const val LISTING_GAP = "$EXTENSION_PACKAGE/navigation/ListingGap;"
internal const val TRIM = "$LISTING_GAP->insets(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

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
 * The classes safeAreaInsets makes with new-instance. The listener the module sets on the decor view
 * is one of them, a lambda that also turns insets into the map, for the change JavaScript is told about.
 */
internal fun madeBySafeAreaRead(read: Method): List<String> =
    read.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
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

/**
 * The register holding the decor view at the call to the insets map at [index] in [method]. In the
 * listener, a lambda's invoke(Object, Object), that's its first parameter, the view the listener was
 * set on, as long as nothing in the method writes it. Anywhere else it's the view whose
 * getRootWindowInsets the method read last, as long as nothing wrote it since.
 */
internal fun decorRegister(method: Method, index: Int): Int {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    if (method.name == "invoke" && method.parameterTypes.size == 2 && !AccessFlags.STATIC.isSet(method.accessFlags)) {
        // p0 is this, p1 the view, p2 the insets.
        val view = method.implementation!!.registerCount - 2
        if (writes(code, 0, index, view)) throw PatchException("$PATCH: $where writes over its view before the insets map")
        return view
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
    val listeners = madeBySafeAreaRead(read).mapNotNull { mutableClassDefByOrNull(it) }
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

/**
 * Takes the empty band out from above a Marketplace listing. A listing is a React Native page whose
 * header pads its top by the safe area Facebook's SafeArea module reports, the whole window's insets.
 * On Android 17 the page itself already sits below the status bar, so the top is padded twice
 * (#333). Both places the module turns insets into the map JavaScript reads, the value a page asks
 * for and the decor listener that tells it about changes, hand the insets and the decor view to the
 * extension's ListingGap first, which keeps only the part of each side that overlaps the page.
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
        // Every call is checked before any is changed, so a build missing a register leaves all as they were.
        callers.forEach { insetsMapCalls(it, map) }
        callers.forEach { it.trimBeforeInsetsMap(map) }
        enableStatus("listingGap")
    }
}
