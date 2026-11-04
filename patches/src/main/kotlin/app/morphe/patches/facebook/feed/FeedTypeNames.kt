/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * The feed unit model that answers `getTypeName()` with exactly [typeName].
 *
 * Facebook's generated GraphQL models keep that method's name while Redex renames their classes
 * every release, and each returns its GraphQL type as a literal. The extension's feed filter
 * compares the name a unit answers, so the literal being there is the evidence the rule needs.
 * `PaginatedPeopleYouMayKnowFeedUnit` is `LX/3zk;` in 580, and is in 577 under another name.
 */
internal fun typeNameFingerprint(typeName: String) = Fingerprint(
    name = "getTypeName",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == typeName
        } == true
    },
)

/** Stops the patch when no model answers [typeName], so a rename fails here, not silently. */
internal fun BytecodePatchContext.requireFeedTypeName(typeName: String) {
    if (typeNameFingerprint(typeName).methodOrNull == null) {
        throw PatchException("No feed unit answers getTypeName() with \"$typeName\" in this APK")
    }
}

/** Kept name and member. The type tag every tree model's `getTypeName()` switches on. */
private const val TYPE_TAG_FIELD = "Lcom/facebook/graphservice/tree/TreeJNI;->mTypeTag:I"

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.methodCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.readsTypeTag(): Boolean = opcode == Opcode.IGET &&
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.let {
        "${it.definingClass}->${it.name}:${it.type}" == TYPE_TAG_FIELD
    } == true

/** A method's instructions, each with its code-unit address, so switch and branch targets resolve. */
internal fun addressed(method: Method): Map<Int, Instruction> {
    val byAddress = LinkedHashMap<Int, Instruction>()
    var address = 0
    for (instruction in method.implementation?.instructions ?: return byAddress) {
        byAddress[address] = instruction
        address += instruction.codeUnits
    }
    return byAddress
}

/** Where the method's first switch sends [key], or null when it has no switch or no case for it. */
internal fun switchTarget(byAddress: Map<Int, Instruction>, key: Int): Int? {
    val (switchAddress, switch) = byAddress.entries.firstOrNull {
        it.value.opcode == Opcode.SPARSE_SWITCH || it.value.opcode == Opcode.PACKED_SWITCH
    } ?: return null
    val payload = byAddress[switchAddress + (switch as OffsetInstruction).codeOffset] as? SwitchPayload ?: return null
    val element = payload.switchElements.singleOrNull { it.key == key } ?: return null
    return switchAddress + element.offset
}

/** The literal a string table method, a static `(I)Ljava/lang/String;`, returns for [index]. */
private fun tableString(table: Method, index: Int): String? {
    val byAddress = addressed(table)
    val target = switchTarget(byAddress, index) ?: return null
    return byAddress[target]?.string()
}

/**
 * Every index a string table method answers [literal] for, read from its first switch in one pass.
 * A table holds thousands of entries, so a search over every caller reads it once this way rather
 * than once per call.
 */
internal fun tableIndices(table: Method, literal: String): Set<Int> {
    val byAddress = addressed(table)
    val (switchAddress, switch) = byAddress.entries.firstOrNull {
        it.value.opcode == Opcode.SPARSE_SWITCH || it.value.opcode == Opcode.PACKED_SWITCH
    } ?: return emptySet()
    val payload = byAddress[switchAddress + (switch as OffsetInstruction).codeOffset] as? SwitchPayload ?: return emptySet()
    return payload.switchElements.filter { byAddress[switchAddress + it.offset]?.string() == literal }.mapTo(HashSet()) { it.key }
}

private val INT_CONSTANTS = setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)

/** Whether [instruction] calls something shaped like a string table: a static `(I)Ljava/lang/String;`. */
internal fun isStringTableCall(instruction: Instruction): Boolean {
    val call = instruction.methodCall() ?: return false
    return (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
        call.returnType == "Ljava/lang/String;" && call.parameterTypes.map { it.toString() } == listOf("I")
}

/**
 * The literals [method] asks string tables for: each an int constant loaded straight before a call
 * to a static `(I)Ljava/lang/String;` that [resolve] finds, read from that table's switch case for
 * the index. Redex outlines a literal that many methods share into such a table and moves literals
 * in and out of the tables between releases (581 moved the save-story event, the typeahead source
 * name and Messenger's /l.php in), so a method's literals are its own and these together.
 */
internal fun tableStringsAsked(method: Method, resolve: (MethodReference) -> Method?): List<String> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { index -> tableStringAt(code, index, resolve) }
}

/**
 * The literal the instruction at [index] of [code] asks a string table for, the way
 * [tableStringsAsked] reads one: a call to a static `(I)Ljava/lang/String;` that [resolve] finds,
 * straight after an int constant loaded into the register it takes. Null for anything else.
 */
internal fun tableStringAt(code: List<Instruction>, index: Int, resolve: (MethodReference) -> Method?): String? {
    val key = tableIndexAt(code, index) ?: return null
    return resolve(code[index].methodCall()!!)?.let { tableString(it, key) }
}

/**
 * The index the instruction at [index] of [code] asks a string table for: the int constant loaded
 * straight before a call to a static `(I)Ljava/lang/String;`, into the register it takes. Null
 * for anything else.
 */
internal fun tableIndexAt(code: List<Instruction>, index: Int): Int? {
    val instruction = code.getOrNull(index) ?: return null
    if (!isStringTableCall(instruction)) return null
    val register = when (instruction) {
        is RegisterRangeInstruction -> instruction.startRegister
        is FiveRegisterInstruction -> instruction.registerC
        else -> return null
    }
    val load = code.getOrNull(index - 1)
    if (load == null || load.opcode !in INT_CONSTANTS || (load as OneRegisterInstruction).registerA != register) {
        return null
    }
    return (load as NarrowLiteralInstruction).narrowLiteral
}

/** Whether [method] holds [literal] or asks a string table for it ([tableStringsAsked]). */
internal fun namesString(method: Method, literal: String, resolve: (MethodReference) -> Method?): Boolean =
    method.implementation?.instructions?.any { it.string() == literal } == true ||
        literal in tableStringsAsked(method, resolve)

/** Whether [method] is shaped like a string table: a static `(I)Ljava/lang/String;` with a body. */
internal fun isStringTable(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.returnType == "Ljava/lang/String;" && method.parameterTypes.map { it.toString() } == listOf("I")

/**
 * The methods outside the extension that [wanted] takes and that name [literal] ([namesString]):
 * the literal's holders' own, and, where one of those holders is a string table ([isStringTable]),
 * every method asking that table for it. Redex moves literals into tables between releases (582's
 * `bookmarks_menu` and share footer anchor are in `LX/6zX;` and `LX/mDc;`), and a caller of a
 * table holds no literal of its own, so only that case sweeps every class.
 */
internal fun BytecodePatchContext.methodsNaming(literal: String, wanted: (Method) -> Boolean): List<Method> {
    val holders = classDefByStrings(literal, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
    val found = holders.values.flatMapTo(mutableListOf()) { owner -> owner.methods.filter { wanted(it) && holdsString(it, literal) } }
    if (holders.values.none { owner -> owner.methods.any { isStringTable(it) && holdsString(it, literal) } }) return found
    val resolve: (MethodReference) -> Method? = { call -> holders[call.definingClass]?.let { resolveStatic(it, call) } }
    // Each table's indices for the literal, read once: every caller of a table asks it, and a table is
    // thousands of instructions (#216).
    val indices = HashMap<MethodReference, Set<Int>>()
    fun asksTable(method: Method): Boolean {
        val code = method.implementation?.instructions?.toList() ?: return false
        return code.indices.any { at ->
            val index = tableIndexAt(code, at) ?: return@any false
            val call = code[at].methodCall()!!
            index in indices.getOrPut(call) { resolve(call)?.let { tableIndices(it, literal) }.orEmpty() }
        }
    }
    // A method without the literal names it only by calling a table, which [resolve] finds among the
    // holders ([namesString], with each table read once).
    classDefForEachReferencing(types = holders.keys) { owner ->
        if (owner.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        owner.methods.filterTo(found) { wanted(it) && !holdsString(it, literal) && asksTable(it) }
    }
    return found
}

/**
 * The name [method], a tree model's `getTypeName()`, answers for a model tagged [tag], or null.
 *
 * The method switches on the tree's type tag. A case answers a literal of its own, or loads an
 * index and asks a static string table (`(I)Ljava/lang/String;`) for it, the way Redex outlines a
 * literal it finds in more than one place. [resolve] finds that table, and the name is the literal
 * its switch case for the index returns. Anything else (no case for the tag, the super call, a table
 * [resolve] can't find, a case that doesn't end in a literal) answers null.
 */
internal fun taggedTypeName(method: Method, tag: Int, resolve: (MethodReference) -> Method?): String? {
    val byAddress = addressed(method)
    if (byAddress.values.none { it.readsTypeTag() }) return null
    var address = switchTarget(byAddress, tag) ?: return null
    val constants = HashMap<Int, Int>()
    // A case is a handful of instructions; the bound only stops a goto loop.
    repeat(16) {
        val instruction = byAddress[address] ?: return null
        instruction.string()?.let { return it }
        val call = instruction.methodCall()
        when {
            instruction is NarrowLiteralInstruction && instruction is OneRegisterInstruction &&
                instruction.opcode.name.startsWith("const") ->
                constants[instruction.registerA] = instruction.narrowLiteral
            instruction is OffsetInstruction && instruction.opcode.name.startsWith("goto") -> {
                address += instruction.codeOffset
                return@repeat
            }
            call != null -> {
                if (instruction.opcode != Opcode.INVOKE_STATIC || call.returnType != "Ljava/lang/String;" ||
                    call.parameterTypes.map { it.toString() } != listOf("I")
                ) {
                    return null
                }
                val register = (instruction as? FiveRegisterInstruction)?.registerC ?: return null
                val index = constants[register] ?: return null
                return resolve(call)?.let { tableString(it, index) }
            }
            else -> return null
        }
        address += instruction.codeUnits
    }
    return null
}

/**
 * Whether [method] is a tree model's `getTypeName()` that answers [typeName] for the type tag of
 * that name (the MD5 prefix [treeTypeTag] computes), by a literal or through a string table.
 */
internal fun answersTaggedTypeName(method: Method, typeName: String, resolve: (MethodReference) -> Method?): Boolean {
    if (method.name != "getTypeName" || method.returnType != "Ljava/lang/String;" || method.parameterTypes.isNotEmpty()) {
        return false
    }
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    return taggedTypeName(method, treeTypeTag(typeName), resolve) == typeName
}

/**
 * Stops the patch when no model answers [typeName] for its type tag. For a type that shares a
 * model class with others, whose `getTypeName()` answers it through a string table rather than a
 * literal of its own, which [requireFeedTypeName] can't see.
 */
internal fun BytecodePatchContext.requireTaggedFeedTypeName(typeName: String) {
    val tag = treeTypeTag(typeName)
    val resolve = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val fingerprint = Fingerprint(
        name = "getTypeName",
        returnType = "Ljava/lang/String;",
        parameters = listOf(),
        custom = { method, _ ->
            // The tag as a switch key is cheap to look for, and rules out nearly every model
            // before a string table is read.
            method.implementation?.instructions?.any { payload ->
                payload is SwitchPayload && payload.switchElements.any { it.key == tag }
            } == true && answersTaggedTypeName(method, typeName, resolve)
        },
    )
    if (fingerprint.methodOrNull == null) {
        throw PatchException("No feed unit answers getTypeName() with \"$typeName\" for its type tag in this APK")
    }
}
