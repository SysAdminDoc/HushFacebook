/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.util.ControlFlow
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Where a row in Facebook's Menu comes from, for the Hushfacebook row, on the 577 to 584 builds.
 *
 * The Menu's Settings and privacy group (see MenuSectionAnchors.kt for the groups) is drawn by the
 * native group section from a list of plain row items: a title, the address a tap opens, the
 * address of an icon, one or two icon resources, and the row's id. The native section gets that
 * list from one builder, the only call it makes that takes the user session, a list and one or
 * two flags and hands back an ImmutableList (583's is `LX/92y;->A08`, which also keeps the list it
 * built in a field and returns that on the next call). 582 had moved the Settings rows into the
 * section itself, so its one builder only made two other groups. 584 inlined 583's builder into the
 * section, which reads and writes the kept list itself (MenuRowAnchors.kt's [keptRowLists]), so the
 * hooks go where it does. Help group items are built the
 * same way by Facebook's own code (Scam protection center passes its address first and its icon
 * resource after), which is how a row with no server behind it is made.
 *
 * A tap on a row goes to one static handler taking the view, the user session, the row's helpers
 * and the row item, which loads the `bookmarks_menu` trace (582 and 584 ask a string table for it) and
 * picks what to open by the row's id. Two more static methods of the same class take the row item:
 * they log the row being seen, by its id (582's 32-bit build had dropped both). 583's 32-bit build
 * has no such handler: its body is inlined into a lambda ([inlinedRowTap]). The item
 * class and every member are Redex names (`Lmp` on 584, `9iZ` on 583, `cTt` on 582, `9KG` on 580, `8oK` on 577,
 * which has one icon resource where 580 has two), so they're found by these shapes and by the trace.
 */
internal const val ROW_PATCH = "Hushfacebook in the Menu"

/** The trace the Menu row tap handler loads. */
internal const val ROW_TAP_TRACE = "bookmarks_menu"

/** The static helpers the patch adds to the row item class, named for the extension to find. */
internal const val ROW_FACTORY = "hushfacebookRow"
internal const val ROW_ID_READER = "hushfacebookRowId"

internal const val VIEW = "Landroid/view/View;"
internal const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val STRING = "Ljava/lang/String;"

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

/**
 * The call in the native group section that builds the Settings and privacy row list from the
 * server's: a virtual one taking the user session, the list and a flag (581), or two (583, which
 * added the second). 582 hid it, building the rows in the section and handing the builder only two
 * other groups. The patch checks the builder makes row items.
 */
internal fun isRowListBuild(call: MethodReference): Boolean {
    val parameters = call.parameterTypes.map { it.toString() }
    return call.returnType == IMMUTABLE_LIST && parameters.size in 3..4 &&
        parameters.take(2) == listOf(USER_SESSION, "Ljava/util/List;") && parameters.drop(2).all { it == "Z" }
}

private val VIRTUAL_INVOKES = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/** The row list builds [method] calls. The patch wants exactly one. */
internal fun rowListBuilds(method: Method): List<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode in VIRTUAL_INVOKES }
        .mapNotNull { instruction ->
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.takeIf(::isRowListBuild)
        }
        .distinctBy { "${it.definingClass}->${it.name}" }

/**
 * The method that makes the [native] section's row list builds: the section itself, or on 586 the
 * one static method of its own class it calls for an ImmutableList (`LX/0D4P;->A01`), which picks
 * the Settings and privacy or the Help and support build by the group and hands back what the build
 * answers. With builds in neither it's the section, which may keep the lists itself (584).
 * [classDef] finds a class of the app by type.
 */
internal fun rowListSite(native: Method, classDef: (String) -> ClassDef?): Method {
    if (rowListBuilds(native).isNotEmpty()) return native
    val called = native.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { it.definingClass == native.definingClass && it.returnType == IMMUTABLE_LIST }
        .map { "${it.name}(${it.parameterTypes.joinToString("")})" }
        .toSet()
    val helpers = classDef(native.definingClass)?.methods?.toList().orEmpty().filter { method ->
        method.isStatic() && "${method.name}(${method.parameterTypes.joinToString("")})" in called &&
            rowListBuilds(method).isNotEmpty()
    }
    return helpers.singleOrNull() ?: native
}

/**
 * The Menu row tap handler: shaped like one ([isRowTapShape]) and naming [ROW_TAP_TRACE], loading
 * it or, as on 582 and 584, asking a string table [resolve] finds for it ([namesString]).
 */
internal fun isRowTap(method: Method, resolve: (MethodReference) -> Method? = { null }): Boolean =
    isRowTapShape(method) && namesString(method, ROW_TAP_TRACE, resolve)

/** Static, returning nothing, taking the view, the user session and four more. Its fourth argument is the row item. */
internal fun isRowTapShape(method: Method): Boolean {
    val parameters = method.parameters()
    return method.implementation != null && method.isStatic() && method.returnType == "V" &&
        parameters.size == 6 && parameters[0] == VIEW && parameters[1] == USER_SESSION &&
        parameters[3].startsWith("L")
}

/** The row item type [tap] takes. */
internal fun rowItemType(tap: Method): String = tap.parameterTypes[3].toString()

/** The other static methods of the tap handler's class that take the row item: the row's loggers. */
internal fun rowLoggers(owner: ClassDef, tap: Method, item: String): List<Method> = owner.methods.filter {
    it !== tap && it.name != tap.name && it.implementation != null && it.isStatic() && it.returnType == "V" &&
        item in it.parameters()
}

/**
 * Where 583's 32-bit build taps a row. It has no tap handler: the handler's body is inlined into
 * one case of a merged Kotlin lambda's `invoke` (`LX/YUe;`, cases 4 to 7, which share it). That
 * code reads the row item out of the row component's field, then the row's view from a call
 * answering a View, then names [ROW_TAP_TRACE] further down. [hook] is the instruction after the
 * view's `move-result-object`, reached only by falling through from the item read, and [view] and
 * [item] hold the view and the row item of type [itemType] there.
 */
internal class InlinedRowTap(val method: Method, val hook: Int, val view: Int, val item: Int, val itemType: String)

/** Whether [method] is a Kotlin lambda's `invoke(Object)Object`, where 583's 32-bit build inlines the tap. */
internal fun isLambdaInvoke(method: Method): Boolean =
    method.implementation != null && !method.isStatic() && method.name == "invoke" &&
        method.returnType == "Ljava/lang/Object;" && method.parameters() == listOf("Ljava/lang/Object;")

/**
 * The inlined tap in [method], a lambda's invoke naming [ROW_TAP_TRACE] (directly or through a string
 * table [resolve] finds): the last call answering a View before the trace, and before that the last
 * field read of a type [isRowItem] takes, with no way into the code between them but falling through
 * and no write to the item's register. Another way in before the view call is allowed when every
 * write of the item reaching the hook reads that same field. Null for any other method.
 */
internal fun inlinedRowTap(method: Method, resolve: (MethodReference) -> Method?, isRowItem: (String) -> Boolean): InlinedRowTap? {
    if (!isLambdaInvoke(method)) return null
    val code = method.implementation!!.instructions.toList()
    val trace = code.indices.firstOrNull { at ->
        ((code[at] as? ReferenceInstruction)?.reference as? StringReference)?.string == ROW_TAP_TRACE ||
            tableStringAt(code, at, resolve) == ROW_TAP_TRACE
    } ?: return null
    val viewCall = (trace - 1 downTo 0).firstOrNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode in VIRTUAL_INVOKES && call != null && call.returnType == VIEW && call.parameterTypes.isEmpty() &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    } ?: return null
    val view = (code[viewCall + 1] as OneRegisterInstruction).registerA
    val itemRead = (viewCall - 1 downTo 0).firstOrNull { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IGET_OBJECT && field != null && isRowItem(field.type)
    } ?: return null
    val item = (code[itemRead] as OneRegisterInstruction).registerA
    val hook = viewCall + 2
    if (view == item || hook >= code.size) return null
    val flow = ControlFlow.of(method)
    val into = Array(code.size) { mutableListOf<Int>() }
    for (from in code.indices) {
        flow.normal[from].forEach { into[it] += from }
        flow.exceptional[from].forEach { into[it] += from }
    }
    if ((viewCall + 1..hook).any { at -> into[at] != listOf(at - 1) }) return null
    if ((itemRead + 1..viewCall).all { at -> into[at] == listOf(at - 1) }) {
        if ((itemRead + 1 until hook).any { at -> writes(code[at], item) }) return null
    } else {
        // 585's Android 9 build joins a second case of `LX/0V2Y;->invoke` (4, sharing the tap of 3, 5
        // and 6) in after the item read, once it has read the item from the same field. So with another
        // way in, every write of the item reaching the hook has to be a read of that one field.
        val field = (code[itemRead] as ReferenceInstruction).reference as FieldReference
        val writers = method.writersReaching(hook, item)
        if (writers.any { at -> at < 0 || code[at].opcode != Opcode.IGET_OBJECT || (code[at] as ReferenceInstruction).reference != field }) {
            return null
        }
    }
    return InlinedRowTap(method, hook, view, item, ((code[itemRead] as ReferenceInstruction).reference as FieldReference).type)
}

/** Whether [instruction] writes [register], half of a wide value included. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
}

/**
 * The row item's full constructor: the title, the address a tap opens, the icon's address, one or
 * more icon resources and the id, in that order.
 */
internal fun fullConstructor(item: ClassDef): Method? = item.methods.filter { method ->
    val parameters = method.parameters()
    method.name == "<init>" && method.implementation != null && parameters.size >= 5 &&
        parameters[0] == CHAR_SEQUENCE && parameters[1] == STRING && parameters[2] == STRING &&
        parameters.last() == "J" && parameters.subList(3, parameters.size - 1).all { it == "I" }
}.singleOrNull()

/** The row item's id: its one instance field of type long. */
internal fun idField(item: ClassDef): FieldReference? =
    item.fields.filter { it.type == "J" && !AccessFlags.STATIC.isSet(it.accessFlags) }.singleOrNull()

private fun CharSequence.width() = if (toString() == "J" || toString() == "D") 2 else 1

/**
 * The field each argument of [constructor] is stored in, by argument index, or null unless every
 * argument goes straight into a field of its own class.
 */
internal fun constructorFields(constructor: Method): List<FieldReference>? {
    val implementation = constructor.implementation ?: return null
    val types = constructor.parameterTypes
    val self = implementation.registerCount - 1 - types.sumOf { it.width() }
    val registers = types.indices.map { index -> self + 1 + types.take(index).sumOf { it.width() } }
    val stores = implementation.instructions
        .filter { it.opcode.name.startsWith("iput") }
        .mapNotNull { instruction ->
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            val store = instruction as TwoRegisterInstruction
            if (store.registerB == self && field.definingClass == constructor.definingClass) {
                (instruction as OneRegisterInstruction).registerA to field
            } else {
                null
            }
        }
    val fields = registers.map { register -> stores.filter { it.first == register }.map { it.second }.singleOrNull() }
    if (fields.any { it == null }) return null
    return fields.map { it!! }.takeIf { mapped -> mapped.map { it.name }.toSet().size == mapped.size }
}

/**
 * The body of the row factory added to the row item class: `(template, title, id)` makes a new row
 * item with [title] and [id], no address to open, and the template's icon. The new item is built
 * in v0 and its arguments in v1 and up, so the constructor call takes one range; the template is
 * the first parameter, just past them.
 */
internal fun rowFactorySmali(item: String, constructor: Method, fields: List<FieldReference>): String {
    val types = constructor.parameterTypes.map { it.toString() }
    val lines = mutableListOf("check-cast p0, $item", "new-instance v0, $item")
    var register = 1
    types.forEachIndexed { index, type ->
        lines += when {
            index == 0 -> "move-object/from16 v$register, p1"
            index == 1 -> "const/4 v$register, 0x0"
            index == 2 -> "iget-object v$register, p0, ${reference(fields[index])}"
            type == "I" -> "iget v$register, p0, ${reference(fields[index])}"
            else -> "move-wide/from16 v$register, p2"
        }
        register += if (type == "J") 2 else 1
    }
    lines += "invoke-direct/range { v0 .. v${register - 1} }, $item-><init>(${types.joinToString("")})V"
    lines += "return-object v0"
    return lines.joinToString("\n")
}

/** How many locals [rowFactorySmali] uses: the new item and every argument register. */
internal fun rowFactoryLocals(constructor: Method): Int = 1 + constructor.parameterTypes.sumOf { it.width() }

internal fun reference(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"
