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
import app.morphe.patches.facebook.misc.upsells.isEnumNaming
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * The post menu sheet, read from 587 on 2026-11-14. The obfuscated names here are for reviewers;
 * the code finds each by a kept name or by its shape.
 *
 * Facebook tags every row of a post's three-dot menu with a kind, a constant of one enum whose
 * names include SEE_MORE (Interested), SEE_LESS (Not interested), SAVE, REPORT and CAST (587
 * LX/0Hq4). The kind sits on the row's metadata (LX/0Hy0, field A01). One static method sorts a
 * filled menu into the sheet's parts (587 LX/0I1X;->A02, handed a CallerContext, the menu and
 * seven flags). It makes a holder (LX/0Hwt), puts the Interested, Not interested and Manage your
 * Feed rows in fields of their own, the first page's rows in one list (A08) and the rest in the
 * More options list (A07), and answers the holder from its one return. The sheet draws from the
 * holder, and Facebook's own presenter moves rows between its lists after the method returns, so
 * the extension does the same there.
 *
 * In front of each return the holder goes to the extension through a helper the patch adds to the
 * method's class, which hands over the metadata class, the kind field and the first page list by
 * name: the kind field is the one the method reads of the kind enum's type, and the first page list
 * is the one a row goes to when its kind is in Facebook's static set of first page kinds. Don't
 * tag a row with a kind to move it: a REPORT row gets a legal_report row added under it.
 */

internal const val SEE_MORE = "SEE_MORE"
internal val POST_MENU_KINDS = listOf(SEE_MORE, "SEE_LESS", "TUNE_YOUR_ALGORITHM", "SAVE", "REPORT")

/** The extension class the holder goes to. */
internal const val POST_MENU_ITEMS = "$EXTENSION_PACKAGE/misc/PostMenuItems;"
internal const val POST_MENU_ARRANGE =
    "$POST_MENU_ITEMS->arrange(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The helper the patch adds to the sorting method's class. */
internal const val ARRANGE_HELPER = "hushfacebookArrangePostMenu"

private const val CALLER_CONTEXT = "Lcom/facebook/common/callercontext/CallerContext;"
private const val LIST = "Ljava/util/List;"
private const val SET = "Ljava/util/Set;"

/** How far after loading the set of first page kinds the first page list is read. */
private const val FIRST_PAGE_REACH = 6

/** How the hook names itself when it refuses: it goes in for more than one patch. */
private const val POST_MENU_SHEET = "Post menu sheet"

private fun refuseSheet(detail: String): Nothing = throw PatchException("$POST_MENU_SHEET: $detail")

/** What [findPostMenuSheet] found: the sorting method and the names the extension reads the holder by. */
internal class PostMenuSheet(
    val sorter: Method,
    /** The metadata class each row's kind sits on, as a type descriptor. */
    val metadata: String,
    val kindField: String,
    val firstPage: String,
)

/**
 * Finds the method that sorts a post menu into the sheet's parts and the names the extension needs.
 * Refuses unless the kind enum, exactly one such method, its kind field and its first page list
 * are there. Changes nothing.
 */
internal fun BytecodePatchContext.findPostMenuSheet(): PostMenuSheet {
    val enums = classDefByStrings(SEE_MORE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { isEnumNaming(it, POST_MENU_KINDS) }
    val kind = enums.singleOrNull()?.type
        ?: refuseSheet("expected one enum naming ${POST_MENU_KINDS.joinToString()}, found ${enums.size}")
    val sorters = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(kind)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(sorters) { isPostMenuSorter(it, kind) }
    }
    val sorter = sorters.singleOrNull()
        ?: refuseSheet("expected one method sorting a post menu by $kind, found ${sorters.map { "${it.definingClass}->${it.name}" }}")
    val holder = classDefByOrNull(sorter.returnType) ?: refuseSheet("this Facebook build has no ${sorter.returnType}")
    val lists = holder.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LIST }
    if (lists < 2) refuseSheet("${holder.type} holds $lists lists, not a first page and More options")
    val read = kindRead(sorter, kind) ?: refuseSheet("${sorter.definingClass}->${sorter.name} reads no row's kind")
    val firstPage = firstPageList(sorter)
        ?: refuseSheet("${sorter.definingClass}->${sorter.name} has no one list for the first page's kinds")
    return PostMenuSheet(sorter, read.definingClass, read.name, firstPage)
}

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Whether [method] sorts a post menu's rows into a sheet: static, handed a CallerContext first, it
 * makes the holder it answers, reads each row's [kind] and fills the holder's lists.
 */
internal fun isPostMenuSorter(method: Method, kind: String): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return false
    if (method.parameterTypes.firstOrNull()?.toString() != CALLER_CONTEXT) return false
    val holder = method.returnType
    if (!holder.startsWith("L") || holder.startsWith("Ljava/") || holder.startsWith("Landroid/")) return false
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.any { it.opcode == Opcode.RETURN_OBJECT } &&
        code.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as? TypeReference)?.type == holder } &&
        code.any { it.opcode == Opcode.IGET_OBJECT && it.field()?.type == kind } &&
        code.any { it.opcode == Opcode.IGET_OBJECT && it.field()?.let { f -> f.definingClass == holder && f.type == LIST } == true }
}

/** The field [sorter] reads a row's [kind] from, on the row's metadata. */
internal fun kindRead(sorter: Method, kind: String): FieldReference? =
    sorter.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
        instruction.field()?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.type == kind }
    }

/**
 * The name of the holder's list that [sorter] puts a row in when Facebook's static set of first
 * page kinds holds the row's kind: the first list it reads after loading that set. Null unless
 * every such read names the same one.
 */
internal fun firstPageList(sorter: Method): String? {
    val code = sorter.implementation?.instructions?.toList() ?: return null
    val names = mutableSetOf<String>()
    code.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.SGET_OBJECT || instruction.field()?.type != SET) return@forEachIndexed
        code.subList(index + 1, minOf(code.size, index + 1 + FIRST_PAGE_REACH)).firstNotNullOfOrNull { next ->
            next.field()?.takeIf {
                next.opcode == Opcode.IGET_OBJECT && it.definingClass == sorter.returnType && it.type == LIST
            }
        }?.let { names += it.name }
    }
    return names.singleOrNull()
}

/** [type] as Java names a class, LX/0Hy0; as X.0Hy0. */
internal fun javaName(type: String): String = type.removePrefix("L").removeSuffix(";").replace('/', '.')

/**
 * Adds the helper to the sorting method's class and calls it in front of each of the method's
 * returns, handed the returned holder. The call goes in at each return's control flow label, so
 * every branch to a return runs it.
 */
internal fun BytecodePatchContext.hookPostMenuSheet(sheet: PostMenuSheet) {
    val owner = mutableClassDefBy(sheet.sorter.definingClass)
    val sorter = owner.findMutableMethodOf(sheet.sorter)
    val helper = ImmutableMethod(
        owner.type,
        ARRANGE_HELPER,
        listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(4),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                const-string v0, "${javaName(sheet.metadata)}"
                const-string v1, "${sheet.kindField}"
                const-string v2, "${sheet.firstPage}"
                invoke-static { p0, v0, v1, v2 }, $POST_MENU_ARRANGE
                return-void
            """,
        )
    }
    owner.methods.add(helper)
    val returns = sorter.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    for (index in returns.asReversed()) {
        val holder = sorter.getInstruction<OneRegisterInstruction>(index).registerA
        sorter.addInstructionsAtControlFlowLabel(
            index,
            "invoke-static/range { v$holder .. v$holder }, ${owner.type}->$ARRANGE_HELPER(Ljava/lang/Object;)V",
        )
    }
}
