/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postmenu

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableStringsAsked
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * A reel's More sheet, read from 587 on 2026-11-14 for #475. The obfuscated names here are for
 * reviewers; the code finds each by the sheet's row ids and by shape.
 *
 * The sheet is built from sections (587 LX/0HL3, LX/0HL2, LX/0HL5 and a list of LX/0HKY's
 * LX/0HL4), each a subclass of one abstract class (LX/0I06) whose one method A02() switches on the
 * section's type and answers a new list of its rows. A row is, or holds, a row model (LX/0Hyj, or a
 * LX/0Htm holding one in A00), and the model's A04() answers the row's id, such as
 * fds_control_type_save. Interested and Not interested sit in a header of their own, not a list.
 *
 * Facebook's impression logger for the sheet (587 LX/0I08;->A00, the one method holding both the
 * Interested and Not interested ids) walks every section's A02() list and reads each row's id with
 * A04(), so it names all three: the list method, the model class and the id method. In front of
 * each of A02's returns the list goes through a helper the patch adds to its class, which hands it
 * to the extension with the model's name and the id method's name and returns what it answers.
 *
 * 588's Android 9 build (2026-11-20) has the logger (LX/0jdE;->A02) ask a string table (LX/0vfR;->A00,
 * indexes 394 and 393) for both ids instead of holding them; the rows are LX/0is0;->A00 and the id
 * LX/0jQO;->A06.
 *
 * Interested and Not interested (588 arm64, 2026-11-21, #475) come from the sheet's header (LX/0ahd;->A04,
 * an LX/0avX), whose LX/0ar9 holds the two titles as CharSequences, A01 and A00; the logger reads
 * both. One Litho render (LX/0ZsZ;->render) draws them: two rows it lists with LX/0QQL;->A15 and hands
 * to a column (LX/0QQN;->A0q(LX/01SY;List;Z)), or, for some sheets, a two-way control under a title.
 * A missing header or title holder only swaps in Facebook's own strings, so the patch asks the
 * extension first thing in that render, which draws nothing when both are picked, and runs the two
 * rows through it before the column, which drops the one picked.
 *
 * Other sheets (588 arm64 on Matt's emulator account, 2026-11-21) skip that render: the sheet's
 * section builder (LX/0aYh;->A0l) makes a top card of three row models, Interested, Not interested
 * and Your algorithm, each with a line under it, and turns each into its row with one static method
 * (LX/0b6s;->A00(CallerContext, LX/0b1J, ...)), as the section lists' rows are. Every caller hands
 * that row to a column's add (LX/02gF;->A1w), which skips a null, so the patch asks the extension
 * first thing in that method and answers no row for a picked kind.
 */

internal const val POST_MENU_REEL_PREFERENCES_HIDDEN = "$POST_MENU_ITEMS->reelPreferencesHidden()Z"
internal const val POST_MENU_REEL_PREFERENCE_ROWS =
    "$POST_MENU_ITEMS->reelPreferenceRows(Ljava/util/List;)Ljava/util/List;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
internal const val POST_MENU_REEL_ROW_HIDDEN =
    "$POST_MENU_ITEMS->reelRowHidden(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z"
private const val CALLER_CONTEXT = "Lcom/facebook/common/callercontext/CallerContext;"

internal const val REEL_INTERESTED = "fds_control_type_preference_signal_positive"
internal const val REEL_NOT_INTERESTED = "fds_control_type_preference_signal_negative"

internal const val POST_MENU_REEL_ROWS =
    "$POST_MENU_ITEMS->reelRows(Ljava/util/List;Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;"

/** The helper the patch adds to the section class. */
internal const val REEL_ROWS_HELPER = "hushfacebookReelMenuRows"

private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"

private fun refuseReelSheet(detail: String): Nothing = throw PatchException("Reel menu sheet: $detail")

/** What [findReelMenuSheet] found: the method listing a section's rows, and how a row's id is read. */
internal class ReelMenuSheet(
    val rows: MethodReference,
    /** The row model class, as a type descriptor. */
    val model: String,
    val idMethod: String,
)

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun ownClass(type: String) = type.startsWith("L") && !type.startsWith("Ljava/") && !type.startsWith("Landroid/")

/**
 * The sheet [logger] names, when it is the reel More sheet's impression logger: a method holding
 * both preference ids, its own or asked of the string tables [resolve] finds, that calls one method
 * of Facebook's taking nothing and answering a List more than once (the section rows), and one
 * taking nothing and answering a String (the row id).
 */
internal fun reelSheetOf(logger: Method, resolve: (MethodReference) -> Method? = { null }): ReelMenuSheet? {
    val code = logger.implementation?.instructions?.toList() ?: return null
    val own = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
    val strings = if (REEL_INTERESTED in own && REEL_NOT_INTERESTED in own) own else own + tableStringsAsked(logger, resolve)
    if (REEL_INTERESTED !in strings || REEL_NOT_INTERESTED !in strings) return null
    val calls = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        .mapNotNull { it.called() }
        .filter { ownClass(it.definingClass) && it.parameterTypes.isEmpty() }
    val lists = calls.filter { it.returnType == LIST }.groupBy { it.toString() }.filterValues { it.size > 1 }
    val ids = calls.filter { it.returnType == STRING }.distinctBy { it.toString() }
    val rows = lists.values.singleOrNull()?.first() ?: return null
    val id = ids.singleOrNull() ?: return null
    return ReelMenuSheet(rows, id.definingClass, id.name)
}

/**
 * Finds the reel More sheet's section rows method and the names the extension reads a row's id by.
 * Refuses unless exactly one impression logger names them and the rows method has code to hook.
 */
internal fun BytecodePatchContext.findReelMenuSheet(): ReelMenuSheet {
    val resolve: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val found = methodsNaming(REEL_INTERESTED) { !isStringTable(it) }
        .mapNotNull { reelSheetOf(it, resolve) }
        .distinctBy { "${it.rows}|${it.model}->${it.idMethod}" }
    val sheet = found.singleOrNull()
        ?: refuseReelSheet("expected one sheet logger naming the rows and ids around \"$REEL_INTERESTED\", found ${found.size}")
    val owner = classDefByOrNull(sheet.rows.definingClass) ?: refuseReelSheet("this Facebook build has no ${sheet.rows.definingClass}")
    val method = owner.methods.singleOrNull { it.name == sheet.rows.name && it.parameterTypes.isEmpty() && it.returnType == LIST }
        ?: refuseReelSheet("${owner.type} declares no ${sheet.rows.name}()")
    if (method.implementation?.instructions?.none { it.opcode == Opcode.RETURN_OBJECT } != false) {
        refuseReelSheet("${owner.type}->${method.name} has no code answering its rows")
    }
    var type: String? = sheet.model
    while (type != null && ownClass(type)) {
        val classDef = classDefByOrNull(type) ?: break
        if (classDef.methods.any { it.name == sheet.idMethod && it.parameterTypes.isEmpty() && it.returnType == STRING }) {
            return sheet
        }
        type = classDef.superclass
    }
    refuseReelSheet("${sheet.model} has no ${sheet.idMethod}() answering a row's id")
}

/**
 * Adds the helper to the rows method's class and runs each list the method answers through it, at
 * each return's control flow label, so every branch to a return runs it.
 */
internal fun BytecodePatchContext.hookReelMenuSheet(sheet: ReelMenuSheet) {
    val owner = mutableClassDefBy(sheet.rows.definingClass)
    val rows = owner.methods.single { it.name == sheet.rows.name && it.parameterTypes.isEmpty() && it.returnType == LIST }
    val helper = ImmutableMethod(
        owner.type,
        REEL_ROWS_HELPER,
        listOf(ImmutableMethodParameter(LIST, null, null)),
        LIST,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                const-string v0, "${javaName(sheet.model)}"
                const-string v1, "${sheet.idMethod}"
                invoke-static { p0, v0, v1 }, $POST_MENU_REEL_ROWS
                move-result-object v0
                return-object v0
            """,
        )
    }
    owner.methods.add(helper)
    val returns = rows.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    for (index in returns.asReversed()) {
        val list = rows.getInstruction<OneRegisterInstruction>(index).registerA
        rows.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$list .. v$list }, ${owner.type}->$REEL_ROWS_HELPER($LIST)$LIST
                move-result-object v$list
            """,
        )
    }
}

/** The render of a reel More sheet's Interested and Not interested header, and where it hands its two rows on. */
internal class ReelPreferenceHeader(val render: Method, val rowsAt: Int)

/** The class whose two CharSequence fields [logger] reads: the header's title holder, or null. */
internal fun preferenceTitlesOf(logger: Method): String? {
    val reads = logger.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.type == CHAR_SEQUENCE }
    return reads.groupBy { it.definingClass }
        .filterValues { fields -> fields.map { it.name }.toSet().size == 2 }
        .keys.singleOrNull()
}

/** Whether [method] is a render reading both of [titles]' CharSequence fields. */
internal fun readsBothTitles(method: Method, titles: String): Boolean =
    method.name == "render" && method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass == titles && it.type == CHAR_SEQUENCE }
        .map { it.name }.toSet().size == 2

/** The register an invoke hands as its argument [index], counting a static call's first as 0. */
private fun Instruction.argument(index: Int): Int? = when (this) {
    is RegisterRangeInstruction -> startRegister + index
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).getOrNull(index)
    else -> null
}

/**
 * Where [render] hands the header's rows to their column: the one static call taking something, a
 * List and a boolean whose List the call just before it answered. Null unless there is exactly one.
 */
internal fun preferenceRowsAt(render: Method): Int? {
    val code = render.implementation?.instructions?.toList() ?: return null
    return code.indices.filter { at ->
        val call = code[at].called()
        at >= 2 && call != null &&
            (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call.parameterTypes.size == 3 && call.parameterTypes[1] == LIST && call.parameterTypes[2] == "Z" &&
            code[at - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (code[at - 1] as OneRegisterInstruction).registerA == code[at].argument(1) &&
            code[at - 2].called()?.returnType == LIST
    }.singleOrNull()
}

/**
 * Finds the render of the reel More sheet's Interested and Not interested header through the sheet
 * logger's title holder. Refuses unless exactly one render reads both titles and hands two rows on.
 */
internal fun BytecodePatchContext.findReelPreferenceHeader(): ReelPreferenceHeader {
    val resolve: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val titles = methodsNaming(REEL_INTERESTED) { !isStringTable(it) }
        .filter { reelSheetOf(it, resolve) != null }
        .mapNotNull { preferenceTitlesOf(it) }
        .distinct().singleOrNull()
        ?: refuseReelSheet("expected the sheet logger to read one header holding Interested and Not interested")
    val renders = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(titles)) { owner ->
        if (owner.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        owner.methods.filterTo(renders) { readsBothTitles(it, titles) }
    }
    val render = renders.singleOrNull()
        ?: refuseReelSheet("expected one render reading both of $titles' titles, found ${renders.size}")
    val rowsAt = preferenceRowsAt(render)
        ?: refuseReelSheet("${render.definingClass}->render hands its two rows on in no way the patch knows")
    if (render.returnType == "V" || render.implementation!!.registerCount - render.parameterTypes.size - 1 < 1) {
        refuseReelSheet("${render.definingClass}->render has no room for the patch's question")
    }
    return ReelPreferenceHeader(render, rowsAt)
}

/**
 * Runs the header's two rows through the extension before their column, then asks it first thing
 * in the render whether to draw nothing, as when both rows are picked.
 */
internal fun BytecodePatchContext.hookReelPreferenceHeader(header: ReelPreferenceHeader) {
    val render = mutableClassDefBy(header.render.definingClass).findMutableMethodOf(header.render)
    val rows = (render.getInstruction<Instruction>(header.rowsAt)).argument(1)!!
    render.addInstructions(
        header.rowsAt,
        """
            invoke-static/range { v$rows .. v$rows }, $POST_MENU_REEL_PREFERENCE_ROWS
            move-result-object v$rows
        """,
    )
    render.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $POST_MENU_REEL_PREFERENCES_HIDDEN
            move-result v0
            if-eqz v0, :draw
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("draw", render.getInstruction(0)),
    )
}

/** Whether [method] turns one row [model] into its row: static, handed a CallerContext and then the model, answering one of Facebook's classes. */
internal fun buildsReelRow(method: Method, model: String): Boolean {
    val params = method.parameterTypes.map { it.toString() }
    return AccessFlags.STATIC.isSet(method.accessFlags) && params.size >= 2 &&
        params[0] == CALLER_CONTEXT && params[1] == model && ownClass(method.returnType) &&
        method.implementation != null
}

/**
 * Finds the one method turning a reel More sheet row model into its row. Refuses unless exactly one
 * does and it has three registers of its own for the patch's question.
 */
internal fun BytecodePatchContext.findReelRowBuilder(sheet: ReelMenuSheet): Method {
    val builders = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(sheet.model)) { owner ->
        if (owner.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        owner.methods.filterTo(builders) { buildsReelRow(it, sheet.model) }
    }
    val builder = builders.singleOrNull()
        ?: refuseReelSheet("expected one method building a row from a ${sheet.model}, found ${builders.size}")
    val parameterRegisters = builder.parameterTypes.size + builder.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
    if (builder.implementation!!.registerCount - parameterRegisters < 3) {
        refuseReelSheet("${builder.definingClass}->${builder.name} has no room for the patch's question")
    }
    return builder
}

/** Asks the extension first thing in [builder] whether its row is of a picked kind, and answers no row on a yes. */
internal fun BytecodePatchContext.hookReelRowBuilder(builder: Method, sheet: ReelMenuSheet) {
    val method = mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder)
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v2, p1
            const-string v0, "${javaName(sheet.model)}"
            const-string v1, "${sheet.idMethod}"
            invoke-static { v2, v0, v1 }, $POST_MENU_REEL_ROW_HIDDEN
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", method.getInstruction(0)),
    )
}
