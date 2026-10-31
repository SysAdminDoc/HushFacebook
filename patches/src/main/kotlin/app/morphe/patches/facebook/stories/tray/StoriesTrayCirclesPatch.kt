/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.tray

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val STORIES_CIRCLES_PATCH = "Round stories tray"

/** The string Home's stories row component writes into its trace, which names the method that builds the row. */
internal const val STORIES_ROW_RENDER = "StoriesHScrollComponent.render"

internal const val TRAY_ID = "$EXTENSION_PACKAGE/stories/StoriesTrayCircles;->trayId(I)V"
internal const val TRAY_CONTAINER = "$EXTENSION_PACKAGE/stories/StoriesTrayCircles;->container(Landroid/view/View;)V"

private const val SWIPE_REFRESH = "Landroidx/swiperefreshlayout/widget/SwipeRefreshLayout;"
private const val RECYCLER_CONSTRUCTOR =
    "Landroidx/recyclerview/widget/RecyclerView;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;I)V"

/** Resource ids of an app's own layout files and views sit in this range. */
private const val FIRST_APP_ID = 0x7f000000L
private const val LAST_APP_ID = 0x7fffffffL

/**
 * Draws Home's stories row as round pictures in one row, the way Instagram does, at a size you pick.
 *
 * Facebook builds the row as a horizontal list of Litho tiles and has no round tile to switch to, so
 * the look is applied to the views. The row's component names its list's view id when it renders,
 * and that id is read from the same method, so the extension knows which list is the stories row.
 * Every factory that builds a pull-to-refresh wrapper around a new list hands the wrapper to the
 * extension, which styles the tiles of the list that carries that id as they appear.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val storiesTrayCirclesPatch = bytecodePatch(
    name = "Round stories tray",
    description = "Draws the stories row on Home as round pictures at a size you pick, like Instagram. " +
        "Starts off. Turn it on in Hushfacebook settings > Stories.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("storiesTrayCircles")
        val render = storiesRowRender()
        val idIndex = trayIdIndex(render)
        val factories = trayContainerFactories()
        mutableClassDefBy(render.definingClass).methods.single { it.name == render.name && it.parameterTypes == render.parameterTypes }
            .reportTrayId(idIndex)
        factories.forEach { factory ->
            mutableClassDefBy(factory.definingClass).methods.single { it.name == factory.name && it.parameterTypes == factory.parameterTypes }
                .passContainer()
        }
        enableStatus("storiesTrayCircles")
    }
}

/** Whether [method] holds the string [text]. */
internal fun mentions(method: Method, text: String): Boolean =
    method.implementation?.instructions?.any {
        it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as? StringReference)?.string == text
    } == true

/** The method that renders Home's stories row. */
internal fun BytecodePatchContext.storiesRowRender(): Method {
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
        classDef.methods.filterTo(found) { mentions(it, STORIES_ROW_RENDER) }
    }
    return found.singleOrNull()
        ?: throw PatchException("$STORIES_CIRCLES_PATCH: expected one method that renders the stories row, found ${found.size}")
}

/**
 * Where the row's list view id is set: the one constant in an app id's range that is stored into a
 * field a couple of instructions later. Returns the constant's index.
 */
internal fun trayIdIndex(render: Method): Int {
    val instructions = render.implementation!!.instructions.toList()
    val found = instructions.indices.filter { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.CONST && (instruction as NarrowLiteralInstruction).wideLiteral in FIRST_APP_ID..LAST_APP_ID &&
            instructions.drop(index + 1).take(3).any {
                it.opcode == Opcode.IPUT && (it as OneRegisterInstruction).registerA == (instruction as OneRegisterInstruction).registerA
            }
    }
    return found.singleOrNull()
        ?: throw PatchException("$STORIES_CIRCLES_PATCH: expected one view id stored by the stories row, found ${found.size}")
}

/**
 * Whether [method] builds a pull-to-refresh wrapper around a new list: a static method taking a
 * Context, answering a class that extends SwipeRefreshLayout, and calling the list's own constructor.
 * [superOf] answers a class's superclass, or null when the class isn't known.
 */
internal fun isTrayContainerFactory(method: Method, superOf: (String) -> String?): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;") &&
        superOf(method.returnType) == SWIPE_REFRESH &&
        method.implementation!!.instructions.any {
            it.opcode == Opcode.INVOKE_DIRECT && ((it as ReferenceInstruction).reference as? MethodReference)?.let { ref ->
                "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}" == RECYCLER_CONSTRUCTOR
            } == true
        }

/** Every container factory in the app. */
internal fun BytecodePatchContext.trayContainerFactories(): List<Method> {
    val found = mutableListOf<Method>()
    classDefForEach { classDef: ClassDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
        classDef.methods.filterTo(found) { isTrayContainerFactory(it) { type -> classDefByOrNull(type)?.superclass } }
    }
    if (found.isEmpty()) throw PatchException("$STORIES_CIRCLES_PATCH: found no factory that wraps a new list for pull to refresh")
    return found
}

/** Tells the extension the list view id the row sets, right after the constant that holds it. */
internal fun MutableMethod.reportTrayId(constIndex: Int) {
    val id = getInstruction<OneRegisterInstruction>(constIndex).registerA
    addInstructions(
        constIndex + 1,
        "invoke-static/range { v$id .. v$id }, $TRAY_ID",
    )
}

/**
 * Hands each wrapper the factory returns to the extension. The call goes in at the return's own
 * label, so a branch that jumps straight to the return goes through it too.
 */
internal fun MutableMethod.passContainer() {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) throw PatchException("$STORIES_CIRCLES_PATCH: $definingClass->$name never returns")
    for (index in returns.asReversed()) {
        val view = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            "invoke-static/range { v$view .. v$view }, $TRAY_CONTAINER",
        )
    }
}
