/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postmenu

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
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
 */

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
 * both preference ids that calls one method of Facebook's taking nothing and answering a List more
 * than once (the section rows), and one taking nothing and answering a String (the row id).
 */
internal fun reelSheetOf(logger: Method): ReelMenuSheet? {
    val code = logger.implementation?.instructions?.toList() ?: return null
    val strings = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
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
    val found = classDefByStrings(REEL_INTERESTED, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.mapNotNull(::reelSheetOf) }
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
