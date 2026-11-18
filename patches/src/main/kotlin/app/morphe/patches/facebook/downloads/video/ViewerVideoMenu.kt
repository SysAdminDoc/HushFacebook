/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.downloads.photo.MENU_LISTENER
import app.morphe.patches.facebook.downloads.photo.SAVE_PHOTO_ACTION
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.ControlFlow
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil

/** The extension call that adds the viewer's row. */
internal const val ADD_VIEWER_ITEM = "$EXTENSION_PACKAGE/download/ViewerVideoItem;->add(" +
    "Landroid/view/Menu;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"

/** The helper this patch adds to the class that builds the viewer's menu. */
internal const val VIEWER_VIDEO_HELPER = "hushfacebookViewerVideoItem"

/** The builder's leading parameters the helper takes: menu, session, screen, post and item. */
private const val VIEWER_PARAMETERS = 5

/**
 * The method that builds the three-dot menu of the photo and video viewer, and what a row added
 * there needs: where the method ends, the register of its first parameter, and the icon of
 * Facebook's own Save photo row.
 */
internal class ViewerMenu(
    val owner: MutableClass,
    val method: MutableMethod,
    val returnIndex: Int,
    val firstRegister: Int,
    val icon: Int,
)

private fun viewerCallOf(instruction: Instruction) =
    (instruction as? ReferenceInstruction)?.reference as? MethodReference

private fun makesSavePhotoAction(method: Method) =
    method.implementation?.instructions?.any { viewerCallOf(it)?.name == SAVE_PHOTO_ACTION } == true

/**
 * Finds the [ViewerMenu] of this build. Save photo's action has a kept name and one caller, the
 * builder, which takes the menu first, the post fourth and the viewer's item fifth. It's read from
 * the mutable method, since Download any photo may already have put its hooks in there.
 */
internal fun BytecodePatchContext.viewerMenu(): ViewerMenu {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(names = listOf(SAVE_PHOTO_ACTION)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filter(::makesSavePhotoAction).forEach { found += classDef.type to it }
    }
    if (found.size != 1) {
        throw PatchException(
            "Expected 1 method making $SAVE_PHOTO_ACTION, the viewer's menu, found ${found.size}: " +
                found.joinToString { "${it.first}->${it.second.name}" },
        )
    }
    val (type, stock) = found.single()
    val where = "$type->${stock.name}"
    val signature = stock.parameterTypes.map(CharSequence::toString)

    val owner = mutableClassDefBy(type)
    val method = owner.methods.single {
        it.name == stock.name && it.returnType == stock.returnType &&
            it.parameterTypes.map(CharSequence::toString) == signature
    }
    val instructions = method.implementation!!.instructions.toList()

    val action = instructions.indexOfFirst { viewerCallOf(it)?.name == SAVE_PHOTO_ACTION }
    val item = viewerCallOf(instructions[action])!!.parameterTypes.singleOrNull()?.toString()
    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags) ||
        signature.size < VIEWER_PARAMETERS || signature[0] != MENU || signature[3] != GRAPHQL_STORY ||
        signature[4] != item
    ) {
        throw PatchException("$where no longer takes the menu, the post and the viewer's item: $signature")
    }

    // ---- the end every filled menu reaches ------------------------------------------------------
    val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    val flow = ControlFlow.of(method)
    val entries = instructions.indices.filter { from ->
        returnIndex in flow.normal[from] || returnIndex in flow.exceptional[from]
    }
    if (entries != listOf(returnIndex - 1)) {
        throw PatchException("$where reaches its last return from $entries, so a call can't go right before it")
    }

    // p0 is the builder. The row reads the first five parameters, so none may have been reused.
    val firstRegister = method.implementation!!.registerCount - MethodUtil.getParameterRegisterCount(method) + 1
    for (register in firstRegister until firstRegister + VIEWER_PARAMETERS) {
        if (method.writersReaching(returnIndex, register) != listOf(-1)) {
            throw PatchException("$where writes over its parameter in v$register before it ends")
        }
    }

    return ViewerMenu(owner, method, returnIndex, firstRegister, savePhotoIcon(instructions, action, where))
}

/**
 * The icon Save photo's row is added with: the last argument of the first add after its action.
 * Other rows jump into the end of that add with icons of their own, so it's the value loaded on
 * the way down from the action that counts, not every value that can reach the call.
 */
private fun savePhotoIcon(instructions: List<Instruction>, action: Int, where: String): Int {
    val shape = listOf(MENU, MENU_LISTENER, "I", "I", "I")
    val row = (action + 1 until instructions.size).firstOrNull { index ->
        val call = viewerCallOf(instructions[index])
        instructions[index].opcode == Opcode.INVOKE_STATIC && call?.returnType == "V" &&
            call.parameterTypes.map(CharSequence::toString) == shape
    } ?: throw PatchException("$where adds no row after $SAVE_PHOTO_ACTION")

    val register = (instructions[row] as FiveRegisterInstruction).registerG
    val load = (row - 1 downTo action + 1).map(instructions::get).firstOrNull {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
    }
    val icon = (load as? NarrowLiteralInstruction)?.narrowLiteral
    if (icon == null || icon ushr 24 != 0x7f) {
        throw PatchException("$where gives Save photo's row no resource id for its icon: $icon")
    }
    return icon
}

/**
 * Adds the helper to the builder's class and calls it right before the builder's last return, when
 * the menu holds all of Facebook's rows. A builder that returns early has no item to offer. The
 * helper has registers of its own, since the builder's parameters sit too high for a plain call
 * and it has none to spare for the icon.
 */
internal fun addViewerVideoItem(menu: ViewerMenu) {
    val parameters = menu.method.parameterTypes.take(VIEWER_PARAMETERS).map(CharSequence::toString)
    val helper = ImmutableMethod(
        menu.owner.type,
        VIEWER_VIDEO_HELPER,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(VIEWER_PARAMETERS + 1),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                const v0, ${menu.icon}
                invoke-static { p0, p2, p3, p4, v0 }, $ADD_VIEWER_ITEM
                return-void
            """,
        )
    }
    menu.owner.methods.add(helper)
    menu.method.addInstructions(
        menu.returnIndex,
        "invoke-static/range { v${menu.firstRegister} .. v${menu.firstRegister + VIEWER_PARAMETERS - 1} }, " +
            "${menu.owner.type}->$VIEWER_VIDEO_HELPER(${parameters.joinToString("")})V",
    )
}
