/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.options.GRAPHQL_COMMENT
import app.morphe.patches.facebook.downloads.video.ATTACHMENTS_FIELD
import app.morphe.patches.facebook.downloads.video.IMMUTABLE_LIST
import app.morphe.patches.facebook.downloads.video.graphQlGetter
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/*
 * A comment's long-press sheet, which offers Save photo on a photo comment and nothing on a GIF
 * (#226). Read from 584, 2026-10-24, arm64; the obfuscated names are for reviewers, the code never
 * writes one down.
 *
 * - The sheet is a component (LX/Nld;) made by CommentBottomSheetFragment, and its render builds
 *   the rows into one list. It holds the name "CommentBottomSheetMenuSocket".
 * - Save photo comes last: render copies the comment's attachments (GraphQLComment's getter of
 *   `attachments`), keeps the ones styled as a photo (a GIF's attachment isn't), and when any are
 *   left builds the row. That's a click action wrapped in a row-click object (LX/3yR;, a 3tD, a
 *   Function0 and the Function1 tap, the first two null), handed with the component context, the
 *   title, a null subtitle and an icon to one static row builder (LX/Nld;->A03), whose answer goes
 *   into the list when it isn't null.
 * - Every way past Save photo, rows or none, meets at the list's isEmpty, and render draws the sheet
 *   only when that says no.
 *
 * The hook goes right after that test: the extension is asked for a tap action for the comment, and
 * when it answers one, a row of Facebook's own kind is built the way Save photo's is, with Save
 * photo's icon, and added to the list. Download any video puts Save video (#318) in the same way at
 * the same point, so a video comment's sheet gets it too.
 *
 * The rows close the sheet after their tap the way Facebook's own rows do, with the sheet dialog's
 * dismiss (586: LX/0UUz;->run). CommentBottomSheetFragment's onCreateView news up the sheet's render
 * component and sets the sheet as a new Dialog's content once, and right after that call the dialog
 * goes to the extension's CommentSheet, which the rows' tap then closes.
 */

internal const val COMMENT_SHEET_SOCKET = "CommentBottomSheetMenuSocket"
internal const val GIF_ROW_CLICK = "$EXTENSION_PACKAGE/download/CommentGif;->clickFor(Ljava/lang/Object;)Lkotlin/jvm/functions/Function1;"
internal const val GIF_ROW_TITLE = "$EXTENSION_PACKAGE/download/CommentGif;->title()Ljava/lang/String;"
internal const val PHOTO_ROW_CLICK = "$EXTENSION_PACKAGE/download/CommentPhoto;->clickFor(Ljava/lang/Object;)Lkotlin/jvm/functions/Function1;"
internal const val PHOTO_ROW_TITLE = "$EXTENSION_PACKAGE/download/CommentPhoto;->title()Ljava/lang/String;"
internal const val VIDEO_PATCH = "Download any video"
internal const val VIDEO_ROW_CLICK = "$EXTENSION_PACKAGE/download/CommentVideo;->clickFor(Ljava/lang/Object;)Lkotlin/jvm/functions/Function1;"
internal const val VIDEO_ROW_TITLE = "$EXTENSION_PACKAGE/download/CommentVideo;->title()Ljava/lang/String;"
internal const val SHEET_OPENED = "$EXTENSION_PACKAGE/download/CommentSheet;->opened(Landroid/app/Dialog;)V"
internal const val SET_CONTENT_VIEW = "Landroid/app/Dialog;->setContentView(Landroid/view/View;)V"
private const val SHEET_PATCHES = "Download any photo and Download any video"

private const val STRING = "Ljava/lang/String;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"

/**
 * Where the sheet's render takes the Save GIF row, and what the row is built from: the hook goes
 * at [insert], [comment], [context] and [list] are the registers holding the comment, the component
 * context and the rows, and [builder], [wrapper], [wrapperInit], [icon] and [add] are Save photo's
 * own row builder, its row-click type and constructor, its icon and the list's add. When render
 * reuses the comment's register before the hook point (586's Android 9 build), [comment] holds
 * `this` instead and [commentFields] are the fields render first read the comment through.
 */
internal data class CommentGifPoint(
    val insert: Int,
    val comment: Int,
    val context: Int,
    val list: Int,
    val builder: String,
    val wrapper: String,
    val wrapperInit: String,
    val icon: Int,
    val add: String,
    val commentFields: List<String> = emptyList(),
)

private fun Instruction.methodCalled() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

private fun Instruction.firstRegister() = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> -1
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * How [method] read what [before]'s [move] copies: the register holding `this` and the fields
 * read from it, when the copy's source is the end of a run of iget-objects that starts at a
 * register holding `this` up to the end of [before]. Null otherwise.
 */
private fun fieldsFromThis(method: Method, before: List<Instruction>, move: Int): Pair<Int, List<String>>? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || before[move].opcode !in OBJECT_MOVES) return null
    var held = (before[move] as TwoRegisterInstruction).registerB
    val fields = ArrayDeque<String>()
    for (at in move - 1 downTo 0) {
        val read = before[at]
        if (read.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerA != held) break
        fields.addFirst((read as ReferenceInstruction).reference.toString())
        held = read.registerB
    }
    if (fields.isEmpty()) return null
    val self = method.implementation!!.registerCount - MethodUtil.getParameterRegisterCount(method)
    if (before.any { it.writes(self) }) return null
    val writes = before.filter { it.writes(held) }
    val isThis = held == self ||
        (writes.size == 1 && writes[0].opcode in OBJECT_MOVES && (writes[0] as TwoRegisterInstruction).registerB == self)
    return if (isThis) held to fields.toList() else null
}

/**
 * The hook point in [method] when it's the comment sheet's render, read through Save photo's row:
 * the one call of the comment's [attachmentsGetter], then the first static (row-click, context,
 * String, String, int) builder after it, the row-click newed up for it from a Function1, the icon
 * constant, the add of its answer to the list, and the list's isEmpty test right after. Null when
 * any of those is missing, when the hook point is a branch target, or when the comment or context
 * register could hold something else there.
 */
internal fun commentGifPoint(method: Method, attachmentsGetter: String): CommentGifPoint? {
    if (!holdsString(method, COMMENT_SHEET_SOCKET)) return null
    val code = method.implementation?.instructions?.toList() ?: return null

    val getter = code.indices.singleOrNull { at ->
        val call = code[at].methodCalled() ?: return@singleOrNull false
        (code[at].opcode == Opcode.INVOKE_VIRTUAL || code[at].opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            call.definingClass == GRAPHQL_COMMENT && call.name == attachmentsGetter &&
            call.parameterTypes.isEmpty() && call.returnType == IMMUTABLE_LIST
    } ?: return null
    val comment = code[getter].firstRegister()

    val builderAt = (getter + 1 until minOf(code.size, getter + 40)).firstOrNull { at ->
        val call = code[at].methodCalled() ?: return@firstOrNull false
        val types = call.parameterTypes.map(CharSequence::toString)
        code[at].opcode == Opcode.INVOKE_STATIC && types.size == 5 && types[0].startsWith("L") &&
            types[1].startsWith("L") && types[2] == STRING && types[3] == STRING && types[4] == "I" &&
            call.returnType.startsWith("L")
    } ?: return null
    val build = code[builderAt] as FiveRegisterInstruction
    val builder = code[builderAt].methodCalled()!!

    val wrapperAt = (builderAt - 1 downTo getter + 1).firstOrNull { at ->
        val call = code[at].methodCalled() ?: return@firstOrNull false
        val types = call.parameterTypes.map(CharSequence::toString)
        code[at].opcode == Opcode.INVOKE_DIRECT && call.name == "<init>" && types.size == 3 &&
            types[1] == FUNCTION0 && types[2] == FUNCTION1 && (code[at] as FiveRegisterInstruction).registerC == build.registerC
    } ?: return null
    val wrapperInit = code[wrapperAt].methodCalled()!!
    val newed = code[wrapperAt - 1]
    if (newed.opcode != Opcode.NEW_INSTANCE || (newed as OneRegisterInstruction).registerA != build.registerC ||
        ((newed as ReferenceInstruction).reference as TypeReference).type != wrapperInit.definingClass
    ) return null

    val iconWrite = code[builderAt - 1]
    if (!iconWrite.writes(build.registerG) || iconWrite !is NarrowLiteralInstruction) return null
    val icon = iconWrite.narrowLiteral

    val result = code.getOrNull(builderAt + 1) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val row = (result as OneRegisterInstruction).registerA
    val nullTest = code.getOrNull(builderAt + 2) ?: return null
    if (nullTest.opcode != Opcode.IF_EQZ || (nullTest as OneRegisterInstruction).registerA != row) return null
    val addAt = code.getOrNull(builderAt + 3) ?: return null
    val add = addAt.methodCalled() ?: return null
    if (addAt.opcode != Opcode.INVOKE_VIRTUAL || add.name != "add" || (addAt as FiveRegisterInstruction).registerCount != 2 ||
        addAt.registerD != row
    ) return null
    val list = addAt.registerC

    val isEmpty = code.getOrNull(builderAt + 4) ?: return null
    val isEmptyCall = isEmpty.methodCalled() ?: return null
    if (isEmptyCall.name != "isEmpty" || isEmptyCall.returnType != "Z" || isEmpty.firstRegister() != list) return null
    val answer = code.getOrNull(builderAt + 5) ?: return null
    if (answer.opcode != Opcode.MOVE_RESULT) return null
    val empty = code.getOrNull(builderAt + 6) ?: return null
    if (empty.opcode != Opcode.IF_NEZ || (empty as OneRegisterInstruction).registerA != (answer as OneRegisterInstruction).registerA) return null
    val insert = builderAt + 7
    if (insert >= code.size || insert in branchTargets(code)) return null

    // The short call forms the row is built with take v0 to v15 only.
    val context = build.registerD
    if (context > 15 || list > 15) return null
    // Every way to the hook point must find the context and the comment where Save photo's row
    // found them: each register is written once, by the copy render starts with, or for the
    // comment by one move and its cast to GraphQLComment, with no write after. When the comment's
    // register is reused after the getter (586's Android 9 build), the hook reads the comment again
    // the way that move's source was read, from `this`.
    val before = code.subList(0, insert)
    if (before.count { it.writes(context) } != 1) return null
    val commentWrites = before.indices.filter { before[it].writes(comment) }
    if (commentWrites.size < 2 || commentWrites[1] != commentWrites[0] + 1 || commentWrites[1] > getter) return null
    val cast = before[commentWrites[1]]
    if (cast.opcode != Opcode.CHECK_CAST || ((cast as ReferenceInstruction).reference as TypeReference).type != GRAPHQL_COMMENT) return null
    val (holder, commentFields) = when {
        commentWrites.size == 2 -> comment to emptyList()
        commentWrites[2] > getter -> fieldsFromThis(method, before, commentWrites[0]) ?: return null
        else -> return null
    }

    return CommentGifPoint(
        insert = insert,
        comment = holder,
        context = context,
        list = list,
        builder = builder.toString(),
        wrapper = wrapperInit.definingClass,
        wrapperInit = wrapperInit.toString(),
        icon = icon,
        add = add.toString(),
        commentFields = commentFields,
    )
}

/** Finds the comment sheet's render and returns the step that adds Save GIF to it. */
internal fun BytecodePatchContext.commentSheetGifRow(): () -> Unit = commentSheetRow(PHOTO_PATCH, GIF_ROW_CLICK, GIF_ROW_TITLE)

/**
 * Finds the comment sheet's render and returns the step that adds Save photo to it for a photo
 * comment (#440). Facebook's own row there shows only while a screen reader is on, and the
 * extension answers no row when it does.
 */
internal fun BytecodePatchContext.commentSheetPhotoRow(): () -> Unit = commentSheetRow(PHOTO_PATCH, PHOTO_ROW_CLICK, PHOTO_ROW_TITLE)

/**
 * Finds the comment sheet's render and returns the step that adds Download any video's Save video
 * to it (#318). It goes at the same point as Save GIF, so with both patches in, whichever runs
 * second puts its row in front of the other's, and each row asks its own switch.
 */
internal fun BytecodePatchContext.commentSheetVideoRow(): () -> Unit = commentSheetRow(VIDEO_PATCH, VIDEO_ROW_CLICK, VIDEO_ROW_TITLE)

/**
 * The comment sheet's render, the class and the method, and the attachments getter it was read
 * through. Refuses, naming [patch], unless exactly one method outside the extension has the shape
 * [commentGifPoint] looks for.
 */
private fun BytecodePatchContext.commentSheetRender(patch: String): Triple<String, Method, String> {
    val attachmentsGetter = graphQlGetter(GRAPHQL_COMMENT, IMMUTABLE_LIST, ATTACHMENTS_FIELD, "a comment's attachments")
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(types = listOf(GRAPHQL_COMMENT), strings = listOf(COMMENT_SHEET_SOCKET)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filter { commentGifPoint(it, attachmentsGetter) != null }.mapTo(found) { classDef.type to it }
    }
    val (owner, holder) = found.singleOrNull() ?: throw PatchException(
        "$patch: expected one comment sheet render ($COMMENT_SHEET_SOCKET) that builds Save photo's row from a " +
            "comment's attachments, found ${found.size}",
    )
    return Triple(owner, holder, attachmentsGetter)
}

/**
 * Finds the comment sheet's render and returns the step that adds the row the extension's [click]
 * and [title] describe. Refuses, naming [patch], unless exactly one method outside the extension has
 * the shape [commentGifPoint] looks for. Nothing changes until the step runs.
 */
private fun BytecodePatchContext.commentSheetRow(patch: String, click: String, title: String): () -> Unit {
    val (owner, holder, attachmentsGetter) = commentSheetRender(patch)
    return {
        val method = mutableClassDefBy(owner).methods.single {
            it.name == holder.name && it.parameterTypes.map(CharSequence::toString) == holder.parameterTypes.map(CharSequence::toString)
        }
        val point = commentGifPoint(method, attachmentsGetter)
            ?: throw PatchException("$patch: $owner->${holder.name} lost its Save photo row before it was hooked")
        val registers = method.getFreeRegisterProvider(point.insert, 4, point.comment, point.context, point.list)
        val action = registers.getFreeRegister4Bit()
        val wrapper = registers.getFreeRegister4Bit()
        val none = registers.getFreeRegister4Bit()
        val icon = registers.getFreeRegister4Bit()
        method.addInstructionsWithLabels(
            point.insert,
            """
                move-object/from16 v$action, v${point.comment}
                ${point.commentFields.joinToString("\n") { "iget-object v$action, v$action, $it" }}
                invoke-static { v$action }, $click
                move-result-object v$action
                if-eqz v$action, :hush_no_row
                new-instance v$wrapper, ${point.wrapper}
                const/4 v$none, 0x0
                invoke-direct { v$wrapper, v$none, v$none, v$action }, ${point.wrapperInit}
                invoke-static { }, $title
                move-result-object v$action
                const v$icon, ${point.icon}
                invoke-static { v$wrapper, v${point.context}, v$action, v$none, v$icon }, ${point.builder}
                move-result-object v$action
                if-eqz v$action, :hush_no_row
                invoke-virtual { v${point.list}, v$action }, ${point.add}
            """,
            ExternalLabel("hush_no_row", method.getInstruction(point.insert)),
        )
    }
}

/**
 * Where [method] gives a comment sheet's dialog its content, when it's the fragment's method that
 * makes the sheet whose render is [render]: it news up [render] and calls a Dialog's
 * setContentView(View) once. Answers the index right after that call and the register holding the
 * dialog, or null, also when that index is a branch target.
 */
internal fun commentSheetDialogPoint(method: Method, render: String): Pair<Int, Int>? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val makesSheet = code.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == render
    }
    if (!makesSheet) return null
    val setAt = code.indices.singleOrNull { at ->
        (code[at].opcode == Opcode.INVOKE_VIRTUAL || code[at].opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            code[at].methodCalled()?.toString() == SET_CONTENT_VIEW
    } ?: return null
    val insert = setAt + 1
    if (insert >= code.size || insert in branchTargets(code)) return null
    return insert to code[setAt].firstRegister()
}

/**
 * Finds the fragment that makes the comment sheet and returns the step that hands the sheet's
 * dialog to the extension's CommentSheet as soon as the sheet is its content, so the rows the
 * download patches add close it after their tap the way Facebook's own rows do: with that dialog's
 * dismiss. Refuses, naming [patch], unless exactly one method outside the extension has the shape
 * [commentSheetDialogPoint] looks for. Nothing changes until the step runs.
 */
internal fun BytecodePatchContext.commentSheetDialog(patch: String): () -> Unit {
    val (render) = commentSheetRender(patch)
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(types = listOf(render)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filter { commentSheetDialogPoint(it, render) != null }.mapTo(found) { classDef.type to it }
    }
    val (owner, holder) = found.singleOrNull() ?: throw PatchException(
        "$patch: expected one method that makes the comment sheet ($render) and sets its dialog's content once, " +
            "found ${found.size}",
    )
    return {
        val method = mutableClassDefBy(owner).methods.single {
            it.name == holder.name && it.parameterTypes.map(CharSequence::toString) == holder.parameterTypes.map(CharSequence::toString)
        }
        val (insert, dialog) = commentSheetDialogPoint(method, render)
            ?: throw PatchException("$patch: $owner->${holder.name} lost its comment sheet dialog before it was hooked")
        method.addInstructions(
            insert,
            if (dialog <= 15) "invoke-static { v$dialog }, $SHEET_OPENED" else "invoke-static/range { v$dialog .. v$dialog }, $SHEET_OPENED",
        )
    }
}

/**
 * Hands the extension the comment sheet's dialog, so Save GIF, Save photo and Save video close the
 * sheet after their tap. Download any photo and Download any video both depend on it, so the hook
 * goes in once with either or both.
 */
internal val commentSheetDialogPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)
    execute { commentSheetDialog(SHEET_PATCHES)() }
}
