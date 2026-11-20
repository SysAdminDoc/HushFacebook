/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.actionrow

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.util.namedRegisters
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * The tag Facebook's own Save item carries, loaded in Save's case. Up to 584 the row was found by
 * "XFBUFIButtonWithFallbackOptions", which only its type list loaded (584 `LX/36P;->A06`). 585 (arm64
 * `LX/03E8;`, read 2026-10-29) dropped that literal from the type list, which now holds no string at
 * all, while the item builder (`A01`) still loaded "Save" in Save's case. 587 (arm64 `LX/03jE;->A04`,
 * read 2026-11-12) has no item builder: the row builder switches over the types itself, so "Save"
 * sits in the row builder's own Save case.
 */
internal const val SAVE_TAG = "Save"

internal const val LIST = "Ljava/util/List;"
internal const val STRING = "Ljava/lang/String;"
internal const val OBJECT = "Ljava/lang/Object;"
internal const val CONTEXT = "Landroid/content/Context;"
internal const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"

/**
 * Where Facebook builds the row of buttons under a post, and what an item of the row is made of.
 *
 * One class lists the row's button types ([types]: a static method answering a List) and builds
 * the row ([items]: static, holding [SAVE_TAG], calling the type list, and building each type's item
 * in a case of its own). The row builder is found first; the type list is the one static List
 * method of its class it calls. Save's case, from the item built before "Save" to Save's own item,
 * shows how an item is put together: an icon from Facebook's icon enum and a style enum, two texts
 * holding the icon, a label holding the text and the click (a Function1), a tag, and the item's
 * constructor taking those among nulls. The case reads the post from the row's state, which the row
 * builder holds in one register throughout.
 */
internal class ActionRow(
    val owner: String,
    val types: Method,
    val items: Method,
    /** The index in [items] where Save's case reads the post's holder from the row's state. */
    val stateRead: Int,
    /** The type enum's SAVE constant, as a field reference. */
    val save: String,
    /** The row state's Context field, and its field holding the post's holder. */
    val state: String,
    val stateContext: String,
    val statePost: String,
    /** The holder's field holding the post. */
    val postValue: String,
    val download: String,
    val outline: String,
    val iconInit: MethodReference,
    val textInit: MethodReference,
    /** Which of the text's parameters takes the icon; the String takes the words, the rest null. */
    val textIcon: Int,
    val labelInit: MethodReference,
    val tagInit: MethodReference,
    val itemInit: MethodReference,
)

/** Finds the [ActionRow] of this build. Throws naming what moved. */
internal fun BytecodePatchContext.actionRow(): ActionRow {
    // "Save" is a common literal, so the row builder is the static holder of it that makes a tag of
    // it, then an item holding the tag, and calls a static List method of its own class.
    val rows = methodsNaming(SAVE_TAG) {
        AccessFlags.STATIC.isSet(it.accessFlags) && holdsString(it, SAVE_TAG)
    }.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }.mapNotNull { method ->
        val code = method.code()
        val saveAt = code.indexOfFirst { it.string() == SAVE_TAG }
        val tagInit = saveTag(code, saveAt) ?: return@mapNotNull null
        val itemAt = (saveAt + 1 until code.size).firstOrNull { at ->
            code[at].methodReference()?.let { it.name == "<init>" && tagInit.definingClass in it.parameters() } == true
        } ?: return@mapNotNull null
        val lists = classDefBy(method.definingClass).methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == LIST && it.implementation != null
        }
        val calls = code.mapNotNull { it.methodReference() }
        val types = lists.filter { list -> calls.any { it.matches(list) } }.singleOrNull() ?: return@mapNotNull null
        RowBuilder(method, types, saveAt, tagInit, itemAt)
    }
    val row = rows.singleOrNull()
        ?: refuse("no single static row builder loading \"$SAVE_TAG\" into a tag of an item and calling one static List method, found ${rows.size}")
    val items = row.method
    val owner = items.definingClass
    val code = items.code()
    val tagInit = row.tagInit
    val itemInit = code[row.itemAt].methodReference()!!

    // The type enum: what the builder turns a case number into, the one naming a SAVE button.
    val typeEnums = code.mapNotNull { it.methodReference() }.filter { it.parameters() == listOf("I") && it.returnType.startsWith("L") }
        .map { it.returnType }.distinct().filter { type -> classDefByOrNull(type)?.let { enumConstant(it, "SAVE") } != null }
    val typeEnum = typeEnums.singleOrNull() ?: refuse("${items.name} turns numbers into ${typeEnums.size} enums naming SAVE: $typeEnums")
    val save = enumConstant(classDefBy(typeEnum), "SAVE")!!

    // Save's case: from past the item built before "Save" to Save's own item.
    val caseStart = (row.saveAt - 1 downTo 0).firstOrNull { code[it].methodReference()?.toString() == itemInit.toString() }?.plus(1) ?: 0
    val case = caseStart..row.itemAt

    // ---- the row state's fields, read the way Save's case reads them --------------------------
    val cast = case.firstOrNull { code[it].opcode == Opcode.CHECK_CAST && code[it].typeReference() == GRAPHQL_STORY }
        ?: refuse("${items.name}'s Save case never reads the post")
    val valueAt = (cast - 1 downTo maxOf(caseStart, cast - 6)).firstOrNull {
        code[it].opcode == Opcode.IGET_OBJECT && code[it].fieldReference()?.type == OBJECT
    } ?: refuse("${items.name} reads the post from no Object field")
    val postValue = code[valueAt].fieldReference()!!
    val holderAt = (valueAt - 1 downTo maxOf(caseStart, valueAt - 6)).firstOrNull {
        code[it].opcode == Opcode.IGET_OBJECT && code[it].fieldReference()?.type == postValue.definingClass
    } ?: refuse("${items.name} reads the post's holder from no field")
    val state = code[holderAt].fieldReference()!!.definingClass
    val stateContext = classDefBy(state).fields.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == CONTEXT
    }.singleOrNull() ?: refuse("$state has no single Context field")

    // ---- how an item is made, from the calls Save's case makes --------------------------------
    val inits = case.map { code[it] }.filter { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }
        .mapNotNull { it.methodReference() }.filter { it.name == "<init>" }
    fun one(what: String, wanted: (MethodReference) -> Boolean): MethodReference {
        val found = inits.filter(wanted).distinctBy { it.toString() }
        return found.singleOrNull() ?: refuse("${items.name}'s Save case makes ${found.size} kinds of $what: ${found.joinToString()}")
    }

    var download: String? = null
    var outline: String? = null
    val iconInit = one("icon") { init ->
        val parameters = init.parameters()
        if (parameters.size != 3) return@one false
        val icons = classDefByOrNull(parameters[0])?.let { enumConstant(it, "DOWNLOAD") } ?: return@one false
        val styles = classDefByOrNull(parameters[1])?.let { enumConstant(it, "OUTLINE") } ?: return@one false
        download = icons
        outline = styles
        true
    }
    val iconClass = classDefBy(iconInit.definingClass)
    val iconTypes = setOf(iconClass.type) + iconClass.interfaces + listOfNotNull(iconClass.superclass)

    val textInit = one("text") { init ->
        val parameters = init.parameters()
        parameters.size == 4 && parameters.last() == STRING && parameters.count { it in iconTypes } == 1
    }
    val textIcon = textInit.parameters().indexOfFirst { it in iconTypes }
    val labelInit = one("label") { it.parameters() == listOf(STRING, FUNCTION1) }

    val itemParameters = itemInit.parameters()
    if (itemParameters.count { it == labelInit.definingClass } != 1 ||
        itemParameters.count { it == textInit.definingClass } != 2 ||
        itemParameters.count { it == tagInit.definingClass } != 1
    ) {
        refuse("the item ${itemInit.definingClass} doesn't take one label, two texts and one tag: $itemParameters")
    }

    return ActionRow(
        owner = owner,
        types = row.types,
        items = items,
        stateRead = holderAt,
        save = save,
        state = state,
        stateContext = "$state->${stateContext.name}:$CONTEXT",
        statePost = code[holderAt].fieldReference().toString(),
        postValue = postValue.toString(),
        download = download!!,
        outline = outline!!,
        iconInit = iconInit,
        textInit = textInit,
        textIcon = textIcon,
        labelInit = labelInit,
        tagInit = tagInit,
        itemInit = itemInit,
    )
}

/** A static holder of [SAVE_TAG] shaped like the row builder, with where it makes Save's tag and item. */
private class RowBuilder(val method: Method, val types: Method, val saveAt: Int, val tagInit: MethodReference, val itemAt: Int)

/** The tag made of "Save" within three instructions of loading it: a constructor taking only a String. */
private fun saveTag(code: List<Instruction>, saveAt: Int): MethodReference? {
    if (saveAt < 0) return null
    return (saveAt + 1 until minOf(code.size, saveAt + 4)).mapNotNull { code[it].methodReference() }
        .firstOrNull { it.name == "<init>" && it.parameters() == listOf(STRING) }
}

/**
 * Where [ActionRow.items] has its whole row. 588 walks the row's types, adds a (type, item) pair for
 * each to one list and only after the walk builds its result from that list, whole or split around
 * Share, with the state's register already reused. So the walk's exit is the last place holding both
 * the list and the state: [index] is the instruction the exit lands on, [head] the walk's `hasNext`,
 * [list] the pairs' register and [pair] their constructor. The state is the register Save's case
 * reads the post's holder from, which the same writes reach at the exit.
 *
 * 588's store-less build (`LX/03AA;->A01`) outlines the pair and its add into a static helper
 * (`LX/01CB;->A1P(Object, Object, AbstractCollection)V`), which [resolve] finds, so a walk may add its
 * pair through one call to such a helper ([outlinedPair]).
 */
internal class RowEnd(val head: Int, val index: Int, val list: Int, val state: Int, val pair: MethodReference)

internal fun ActionRow.rowEnd(resolve: (MethodReference) -> Method? = { null }): RowEnd {
    val code = items.code()
    val state = (code[stateRead] as TwoRegisterInstruction).registerB
    val walks = code.indices.mapNotNull { collectedPair(code, it) ?: outlinedPair(code, it, resolve) }
        .filter { it.head < stateRead && stateRead < it.init }
    val walk = walks.singleOrNull() ?: refuse("${items.name} doesn't collect its items in one walk, found ${walks.size}")
    if (items.writersReaching(walk.exit, state).toSet() != items.writersReaching(stateRead, state).toSet()) {
        refuse("${items.name} ends its walk at ${walk.exit} with something else than the row's state in v$state")
    }
    return RowEnd(walk.head, walk.exit, walk.list, state, walk.pair)
}

private class CollectedPair(val head: Int, val init: Int, val exit: Int, val list: Int, val pair: MethodReference)

/** The walk that adds the pair built at [init] to a list and goes round again, when [init] builds one. */
private fun collectedPair(code: List<Instruction>, init: Int): CollectedPair? {
    val pair = code[init].methodReference() ?: return null
    if (code[init].opcode != Opcode.INVOKE_DIRECT || pair.name != "<init>" || pair.parameters() != listOf(OBJECT, OBJECT)) return null
    val copy = code.getOrNull(init + 1)?.takeIf { it.opcode == Opcode.MOVE_OBJECT || it.opcode == Opcode.MOVE_OBJECT_FROM16 }
        as TwoRegisterInstruction?
    val addAt = init + if (copy == null) 1 else 2
    val add = code.getOrNull(addAt) ?: return null
    val added = add.methodReference() ?: return null
    if ((add.opcode != Opcode.INVOKE_VIRTUAL && add.opcode != Opcode.INVOKE_INTERFACE) || added.name != "add" ||
        added.returnType != "Z" || added.parameters() != listOf(OBJECT)
    ) {
        return null
    }
    val registers = add.namedRegisters()
    if (registers.size != 2 || registers[1] != code[init].namedRegisters().first()) return null
    val list = copy?.takeIf { it.registerA == registers[0] }?.registerB ?: registers[0]

    val (head, exit) = walkBack(code, addAt + 1, init) ?: return null
    return CollectedPair(head, init, exit, list, pair)
}

/**
 * The walk's `hasNext` and the exit it lands on, when [at] goes back to it: a goto onto a `hasNext`
 * before [before] whose answer an `if-eqz` tests straight after.
 */
private fun walkBack(code: List<Instruction>, at: Int, before: Int): Pair<Int, Int>? {
    val back = code.getOrNull(at) ?: return null
    if (back.opcode != Opcode.GOTO && back.opcode != Opcode.GOTO_16 && back.opcode != Opcode.GOTO_32) return null
    val head = branchTarget(code, at) ?: return null
    if (head >= before || code[head].methodReference()?.name != "hasNext") return null
    val answer = code.getOrNull(head + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as OneRegisterInstruction? ?: return null
    val test = code.getOrNull(head + 2)?.takeIf { it.opcode == Opcode.IF_EQZ } as OneRegisterInstruction? ?: return null
    if (test.registerA != answer.registerA) return null
    val exit = branchTarget(code, head + 2) ?: return null
    return head to exit
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * The walk that hands (type, item) to a static helper building the pair and adding it to the list,
 * then goes round again, when [at] calls one (588 store-less `LX/01CB;->A1P`). The helper, which
 * [resolve] finds, makes a pair of its first two parameters in order and adds it to its third. The
 * list's register is the one copied into the call's third argument just before it, or that argument.
 */
private fun outlinedPair(code: List<Instruction>, at: Int, resolve: (MethodReference) -> Method?): CollectedPair? {
    val call = code[at].methodReference() ?: return null
    if (code[at].opcode != Opcode.INVOKE_STATIC || call.returnType != "V" || call.parameters().size != 3 ||
        call.parameters().take(2) != listOf(OBJECT, OBJECT)
    ) {
        return null
    }
    val helper = resolve(call) ?: return null
    val pair = helperPair(helper) ?: return null
    var list = code[at].namedRegisters()[2]
    var copyAt = at - 1
    while (copyAt >= 0 && code[copyAt].opcode in OBJECT_MOVES) {
        val copy = code[copyAt] as TwoRegisterInstruction
        if (copy.registerA == list) {
            list = copy.registerB
            break
        }
        copyAt--
    }
    val (head, exit) = walkBack(code, at + 1, at) ?: return null
    return CollectedPair(head, at, exit, list, pair)
}

/**
 * The pair constructor [helper] builds from its first two parameters, in order, and adds to its third
 * (a `Collection`), with nothing else: new-instance, the constructor, add, return-void.
 */
private fun helperPair(helper: Method): MethodReference? {
    val code = helper.code()
    if (!AccessFlags.STATIC.isSet(helper.accessFlags) || code.size != 4) return null
    val locals = helper.implementation!!.registerCount - 3
    val made = code[0].takeIf { it.opcode == Opcode.NEW_INSTANCE } as OneRegisterInstruction? ?: return null
    val pair = code[1].methodReference() ?: return null
    if (code[1].opcode != Opcode.INVOKE_DIRECT || pair.name != "<init>" || pair.parameters() != listOf(OBJECT, OBJECT) ||
        code[1].namedRegisters() != listOf(made.registerA, locals, locals + 1)
    ) {
        return null
    }
    val added = code[2].methodReference() ?: return null
    if ((code[2].opcode != Opcode.INVOKE_VIRTUAL && code[2].opcode != Opcode.INVOKE_INTERFACE) || added.name != "add" ||
        added.returnType != "Z" || added.parameters() != listOf(OBJECT) || code[2].namedRegisters() != listOf(locals + 2, made.registerA)
    ) {
        return null
    }
    return pair.takeIf { code[3].opcode == Opcode.RETURN_VOID }
}

/** The indices of [ActionRow.types]'s returns, with the register each returns. */
internal fun ActionRow.typeReturns(): List<Pair<Int, Int>> {
    val code = types.code()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        .map { it to (code[it] as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) refuse("${types.name} never returns its list")
    return returns
}

internal fun refuse(why: String): Nothing = throw PatchException("Post action buttons: $why")

internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

internal fun MethodReference.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun MethodReference.matches(method: Method) =
    definingClass == method.definingClass && name == method.name && returnType == method.returnType &&
        parameters() == method.parameterTypes.map(CharSequence::toString)

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.typeReference() =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
