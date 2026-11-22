/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.media.progressbar.extendsType
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The share sheet's row of people to send to, read from 583 on 2026-10-17 (#169). The obfuscated
 * names are for reviewers; the code finds the row by a kept name and a method shape.
 *
 * The sheet's body render (583 arm64 LX/Zya;->render, armv7 LX/ZkL;->render, the one loading
 * "messenger_suggested_recipients_hscroll") adds the row of round profile pictures to the list of
 * children it is collecting in three places, and each add is a hook: with the switch on, the row
 * never joins the children.
 *
 * Two go through a helper of its own class, `A0E(?, ?, List, int, boolean)`, which returns the
 * row's component: the row built from a cache and the shimmer placeholder drawn while people load
 * ("..._grid_glimmer"). Both are `invoke-static` of that helper, `move-result-object`, then
 * `AbstractCollection.add` in the next two instructions.
 *
 * The third is the one a share sheet takes when it opens (seen in a method trace of 583 on the
 * emulator, 2026-10-17): the render builds the row's model from the list of people
 * (arm64 `new LX/ZHc;(..., List, float)`, armv7 `LX/YwX;`), hands it to a one-argument builder
 * (`LX/b8v;->A01(LX/ZHc;)LX/ZHI;`, armv7 `LX/ad2;->A01`), and adds what that returns.
 *
 * 585 (2026-10-29, arm64 `LX/0YkF;->render`, armv7 `LX/0Yji;->render`) keeps the three rows but
 * adds two of them through one instruction. The helper (`A0B`) now returns `LX/02cL;`, a subclass
 * (through `LX/03I7;`) of the `LX/01JC;` render returns. The placeholder's result still goes
 * straight into `AbstractCollection.add`. The cached row's result jumps (`goto`) to the instruction
 * the list-built row (`new LX/0Y0M;(..., List, F, F)` into `LX/0Zfz;->A01`, armv7 `LX/0Xzp;` into
 * `LX/0Zfe;->A01`) runs into: a
 * static `(Object, Object, AbstractCollection)V` Redex outlined (arm64 `LX/0IoG;->A1Z`, armv7
 * `LX/0Inu;->A1Y`) that adds the row and then, when it isn't null, the part after it. That shared
 * add becomes the extension's `addRowThen`, which hands the row to `addRow` and always adds the
 * part after it. It's hooked once, and only when nothing but those two rows reaches it.
 */

/** The extension class the hook asks. */
internal const val SHARE_SHEET_PEOPLE = "$EXTENSION_PACKAGE/misc/ShareSheetPeople;"
internal const val ADD_PEOPLE_ROW =
    "$SHARE_SHEET_PEOPLE->addRow(Ljava/util/AbstractCollection;Ljava/lang/Object;)Z"
internal const val ADD_PEOPLE_ROW_THEN =
    "$SHARE_SHEET_PEOPLE->addRowThen(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/AbstractCollection;)V"

private const val OBJECT = "Ljava/lang/Object;"
private const val ABSTRACT_COLLECTION = "Ljava/util/AbstractCollection;"
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/** A Litho component's `render`, a name the framework keeps. */
private const val RENDER = "render"

private const val PATCH = "Share sheet items (people row)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The share sheet's row of people goes through the extension's ShareSheetPeople. Nameless, so Share
 * sheet items carries it and its fixture tests run it on its own. Everything is found before
 * anything changes, and it refuses unless the render has exactly the two helper adds and the one
 * built from the list of people, and unless an add they share is reached by nothing else.
 */
internal val shareSheetPeoplePatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        hookShareSheetPeople()
    }
}

/**
 * Where a people row joins its parent's children: the call whose result is the row, the add
 * instruction's index and its registers. [next] is the part a shared add puts in after the row
 * (585), and null for a plain `AbstractCollection.add`.
 */
internal data class PeopleRowAdd(val call: Int, val index: Int, val children: Int, val row: Int, val next: Int? = null)

internal fun BytecodePatchContext.hookShareSheetPeople() {
    val bodies = methodsNaming(SHARE_BODY_ANCHOR) { it.name == RENDER }
    val body = bodies.singleOrNull() ?: refuse("expected one render loading \"$SHARE_BODY_ANCHOR\", found ${bodies.size}")
    // 585's helper returns a subclass of the component render returns.
    val helperAdds = helperRowAdds(body) { type -> classDefByOrNull(type)?.superclass }
    if (helperAdds.size != 2) refuse("expected the cached people row and its placeholder in the share sheet body, found ${helperAdds.size}")
    val listAdds = listRowAdds(body)
    if (listAdds.size != 1) refuse("expected one people row built from its list in the share sheet body, found ${listAdds.size}")
    val code = body.code()
    sharedAddRefusal(code, helperAdds + listAdds)?.let(::refuse)
    val adds = (helperAdds + listAdds).distinctBy { it.index }
    adds.filter { it.next != null }.forEach { add ->
        val shared = code[add.index].reference() as MethodReference
        val adder = classDefByOrNull(shared.definingClass)?.methods?.singleOrNull {
            it.name == shared.name && it.returnType == shared.returnType &&
                it.parameterTypes.map(CharSequence::toString) == shared.parameterTypes.map(CharSequence::toString)
        }
        if (adder == null || !isPairAdd(adder)) {
            refuse("$shared, where people rows join the share sheet body, no longer adds the row and then the part after it")
        }
    }

    val method = mutableClassDefBy(body.definingClass).findMutableMethodOf(body)
    adds.forEach { add ->
        val hook = add.next?.let { next -> "invoke-static { v${add.row}, v$next, v${add.children} }, $ADD_PEOPLE_ROW_THEN" }
            ?: "invoke-static { v${add.children}, v${add.row} }, $ADD_PEOPLE_ROW"
        method.replaceInstruction(add.index, hook)
    }
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/** Every place [render] adds a people row to its children, once each, in code order. */
internal fun peopleRowAdds(render: Method, superOf: (String) -> String? = { null }): List<PeopleRowAdd> =
    (helperRowAdds(render, superOf) + listRowAdds(render)).distinctBy { it.index }.sortedBy { it.index }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/**
 * The two adds of a row from the render's own helper: a call to a static helper of its class that
 * takes `(?, ?, List, int, boolean)` and returns what `render` returns or, through [superOf], a
 * subclass of it, whose result goes into an add (see [resultAdded]).
 */
internal fun helperRowAdds(render: Method, superOf: (String) -> String? = { null }): List<PeopleRowAdd> {
    val code = render.code()
    return (0 until code.size - 2).mapNotNull { index ->
        val call = code[index].reference() as? MethodReference ?: return@mapNotNull null
        if (code[index].opcode != Opcode.INVOKE_STATIC && code[index].opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        if (call.definingClass != render.definingClass || !extendsType(call.returnType, render.returnType, superOf)) return@mapNotNull null
        val parameters = call.parameterTypes.map(CharSequence::toString)
        if (parameters.size != 5 || parameters.subList(2, 5) != listOf("Ljava/util/List;", "I", "Z")) return@mapNotNull null
        resultAdded(code, index)
    }
}

/** How far before the builder call the row model's constructor may sit (583 has one move between). */
private const val MODEL_REACH = 3

/**
 * The add of a row built from the list of people: the render constructs a model whose constructor
 * takes a `List` among its arguments, a one-argument virtual method that takes that model returns
 * the row, and the row goes straight into an `AbstractCollection.add`.
 */
internal fun listRowAdds(render: Method): List<PeopleRowAdd> {
    val code = render.code()
    return (0 until code.size - 2).mapNotNull { index ->
        val opcode = code[index].opcode
        if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
        val call = code[index].reference() as? MethodReference ?: return@mapNotNull null
        val model = call.parameterTypes.singleOrNull()?.toString() ?: return@mapNotNull null
        if (!model.startsWith("L") || model.startsWith("Ljava/") || !call.returnType.startsWith("L")) return@mapNotNull null
        val built = (maxOf(0, index - MODEL_REACH) until index).any { before ->
            val init = code[before].reference() as? MethodReference
            (code[before].opcode == Opcode.INVOKE_DIRECT || code[before].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                init != null && init.definingClass == model && init.name == "<init>" &&
                init.parameterTypes.any { it.toString() == "Ljava/util/List;" }
        }
        if (!built) return@mapNotNull null
        resultAdded(code, index)
    }
}

/**
 * The add of the call at [index]'s result: right after its `move-result-object`, or where the
 * `goto` there lands (585's cached row), either an `AbstractCollection.add` of the row or a static
 * `(Object, Object, AbstractCollection)V` handed the row first (585's shared add, see [isPairAdd]).
 */
private fun resultAdded(code: List<Instruction>, index: Int): PeopleRowAdd? {
    val moved = code[index + 1]
    if (moved.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val row = (moved as OneRegisterInstruction).registerA
    // 585 sends the cached row through a goto to the add it shares with the row built from its list.
    val at = if (code[index + 2].opcode in GOTOS) branchTarget(code, index + 2) ?: return null else index + 2
    val add = code[at]
    val target = add.reference() as? MethodReference ?: return null
    val registers = add as? FiveRegisterInstruction ?: return null
    if (add.opcode == Opcode.INVOKE_VIRTUAL && isCollectionAdd(target)) {
        if (registers.registerCount != 2 || registers.registerD != row) return null
        return PeopleRowAdd(index, at, registers.registerC, row)
    }
    // 585 outlines "add the row, then the part after it if any" into one static helper.
    if (add.opcode == Opcode.INVOKE_STATIC && target.returnType == "V" &&
        target.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, OBJECT, ABSTRACT_COLLECTION)
    ) {
        if (registers.registerCount != 3 || registers.registerC != row) return null
        return PeopleRowAdd(index, at, registers.registerE, row, registers.registerD)
    }
    return null
}

private fun isCollectionAdd(target: MethodReference) =
    target.definingClass == ABSTRACT_COLLECTION && target.name == "add" &&
        target.parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && target.returnType == "Z"

/**
 * Whether [method] is the shared add 585 outlined: static `(Object, Object, AbstractCollection)V`
 * that adds its first argument to the collection, then its second when that isn't null, and does
 * nothing else (arm64 `LX/0IoG;->A1Z`, armv7 `LX/0Inu;->A1Y`).
 */
internal fun isPairAdd(method: Method): Boolean {
    val implementation = method.implementation ?: return false
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
        method.parameterTypes.map(CharSequence::toString) != listOf(OBJECT, OBJECT, ABSTRACT_COLLECTION)
    ) return false
    val code = implementation.instructions.toList()
    val first = implementation.registerCount - 3
    fun adds(at: Int, item: Int): Boolean {
        val add = code.getOrNull(at) as? FiveRegisterInstruction ?: return false
        val target = add.reference() as? MethodReference ?: return false
        return add.opcode == Opcode.INVOKE_VIRTUAL && isCollectionAdd(target) &&
            add.registerCount == 2 && add.registerC == first + 2 && add.registerD == item
    }
    return code.size == 4 && adds(0, first) && code[1].opcode == Opcode.IF_EQZ &&
        (code[1] as OneRegisterInstruction).registerA == first + 1 && branchTarget(code, 1) == 3 &&
        adds(2, first + 1) && code[3].opcode == Opcode.RETURN_VOID
}

/** Instructions after which the next one doesn't run. */
private val ENDS = GOTOS + setOf(
    Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW,
)

/**
 * Why hooking [adds] could catch something other than a people row, or null when it can't: every
 * jump onto one of their adds is a people row's own `goto`, no switch case lands there, and the
 * instruction above an add either doesn't run into it or is a people row's `move-result-object`.
 */
internal fun sharedAddRefusal(code: List<Instruction>, adds: List<PeopleRowAdd>): String? {
    val rowJumps = adds.map { it.call + 2 }.filter { code[it].opcode in GOTOS }.toSet()
    val rowMoves = adds.map { it.call + 1 }.toSet()
    val jumpTargets = code.indices
        .filter { code[it] is OffsetInstruction && code[it].opcode != Opcode.PACKED_SWITCH && code[it].opcode != Opcode.SPARSE_SWITCH }
        .groupBy { branchTarget(code, it) }
    val caseTargets = switchCaseTargets(code)
    for (index in adds.map { it.index }.distinct()) {
        val strangers = jumpTargets[index].orEmpty().filter { it !in rowJumps }
        if (strangers.isNotEmpty()) return "the people row add at $index is also reached from $strangers"
        if (index in caseTargets) return "a switch case lands on the people row add at $index"
        val above = index - 1
        if (above >= 0 && code[above].opcode !in ENDS && above !in rowMoves) {
            return "the instruction above the people row add at $index runs into it"
        }
    }
    return null
}

/** The indices every packed or sparse switch case in [code] lands on. */
private fun switchCaseTargets(code: List<Instruction>): Set<Int> {
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val indexOfAddress = addresses.withIndex().associate { (index, address) -> address to index }
    return code.indices.filter { code[it].opcode == Opcode.PACKED_SWITCH || code[it].opcode == Opcode.SPARSE_SWITCH }
        .flatMap { at ->
            val payload = indexOfAddress[addresses[at] + (code[at] as OffsetInstruction).codeOffset]?.let { code[it] } as? SwitchPayload
            payload?.switchElements.orEmpty().mapNotNull { indexOfAddress[addresses[at] + it.offset] }
        }.toSet()
}
