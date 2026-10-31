/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The search screen before anything is typed (#80), where Facebook can serve People you may know and
 * For you. Read from 583, 2026-10-20; the obfuscated names are for reviewers, the code never writes
 * one down.
 *
 * Its rows come from one paginated list, "null_state_results_paginating", read with its entry class
 * (arm64 LX/87d;->A00, entries LX/87e). The section naming itself "SearchTypeaheadPaginableSection"
 * (LX/Bne) draws each entry in its event method's item case (A2c): it reads the entry's node (A00, a
 * tree LX/7RO), then the node's module tree through a static (tree, Class, key, tag) reader
 * (LX/3gt;->A09 to LX/7RP), and draws that as a native template, or an empty row when there's none.
 * (The empty row is a Litho column with no children and no style, LX/2aC.) No field is named for a
 * section, but the node's "rendered_modules" string, which Facebook hands its own performance logger,
 * says what it holds: on the S22 (583, 2026-10-20) the For you section is the one entry whose node
 * lists TRENDING_QUERY_SUGGESTION there. The extension logs, with Debug logging on, each entry's type
 * names and module names, never its id or text, and answers the module to draw: null, Facebook's
 * empty row, while Hide For you in search is on and the entry is For you, or Hide "People you may
 * know" in search is on and its node lists PEOPLE_YOU_MAY_KNOW (a reporter's Debug log, 583,
 * 2026-10-22).
 *
 * 584 (2026-10-22) reads it the other way round. The item case of LX/CEc;->A2S reads the node
 * (LX/7L1;->A00 to LX/7L2), tests it for null, then reads the module with the node's own no-argument
 * accessor (LX/7L2;->A00 to LX/7L3), tests that, and only then turns it into a tree (LX/A86;->A0M).
 * Both null tests jump to the same empty row, so there the extension is handed the node alone, right
 * after it's read, and its answer is the node: null draws that empty row.
 *
 * 585 (2026-10-30) keeps 584's order but reads through interfaces. The list still names the entry's
 * tree class (arm64 LX/08Hk, armv7 its own), and the section (arm64 LX/0DvG, armv7 LX/0DvD) casts each
 * entry to that class's interface (LX/0lzT) and asks it for the node with invoke-interface (ALd to
 * LX/0m9k), then the node for the module the same way (ENJ to LX/0lzS), both null tests jumping to
 * one empty row. The "rendered_modules" key moved into the node's own accessor (LX/0m9k;->FH8, whose
 * tree class LX/08Hl loads it), which the section logs; the extension reads it off the node as before.
 *
 * The section only draws the entries after the first page (#285, read from 584 and 585 on 2026-10-31).
 * The first page can go to a builder naming itself "nullstate_nt_head_network" (584 LX/9O3;->A0T, 585
 * arm64 LX/09mr;->A0X), which walks the same list and reads each entry's node and module in 584's
 * shape, both null tests jumping back to the loop, then builds a row straight from each module. A
 * reporter's search screen came wholly from there, so their Debug log had no line from the section.
 * That builder gets the same hook with its own entry point, and a null node leaves the entry out.
 *
 * Facebook's older null-state query ("FetchNTSearchNullStateQuery") reads nodes of the same type in a
 * static loop body (Builder, Iterator) that adds each module to a list: 584 inline (LX/hb3;->A1C, the
 * entry's "node" tree, then the concrete reader keyed by the hashCode of the node's type name, then
 * the module), 585 through the node tree's own accessor holding that key (LX/07oD;->ALc, asked through
 * its interface LX/0lzW from LX/0mDW;->A00). Each tests the node for null and returns, so the hook
 * goes right after the node is kept and a null leaves the entry out. That query may not ask for
 * "rendered_modules", in which case its Debug lines say none and its rows stay.
 */

/** The name the pre-search section gives itself. */
internal const val PRE_SEARCH_SECTION = "SearchTypeaheadPaginableSection"

/** The paginated list the section's entries come from. */
internal const val PRE_SEARCH_LIST = "null_state_results_paginating"

internal const val PRE_SEARCH_ENTRY =
    "$EXTENSION_PACKAGE/search/PreSearchSections;->entry(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

internal const val PRE_SEARCH_NODE =
    "$EXTENSION_PACKAGE/search/PreSearchSections;->node(Ljava/lang/Object;)Ljava/lang/Object;"

/** The name the builder of the list's first page gives itself (#285). */
internal const val PRE_SEARCH_HEAD = "nullstate_nt_head_network"

internal const val PRE_SEARCH_HEAD_NODE =
    "$EXTENSION_PACKAGE/search/PreSearchSections;->headNode(Ljava/lang/Object;)Ljava/lang/Object;"

internal const val PRE_SEARCH_QUERY_NODE =
    "$EXTENSION_PACKAGE/search/PreSearchSections;->queryNode(Ljava/lang/Object;)Ljava/lang/Object;"

/** The tree reader that reads a field as a concrete type, which it names by its name's hashCode. */
internal const val CONCRETE_READER = "getCachedNullableConcreteReinterpret"

/** The pre-search entries' node type, as a tree names a concrete type: its name's hashCode. */
internal val NULL_STATE_NODE_KEY = "SearchNullStateNativeTemplatesResult".hashCode()

private const val LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"

private const val PAGINABLE_LIST_READER = "getCachedPaginableTreeList"

/**
 * Where an entry is drawn. Read by a static reader (583), [read] is the `move-result-object` keeping
 * the [module] tree read off the [node], and the hook replaces the module. Read [byNode] (584), [read]
 * keeps the node, which Facebook tests for null before its accessor answers the [module], and the
 * hook replaces the node. [type] is what the replaced register holds: the reader's answer or the node.
 */
internal data class PreSearchSite(
    val method: Method,
    val read: Int,
    val node: Int,
    val module: Int,
    val type: String,
    val byNode: Boolean = false,
    /** The entry class's no-argument node accessor the site read. */
    val accessor: String = "",
) {
    /** The register the hook's answer goes back into. */
    val kept get() = if (byNode) node else module
}

/**
 * The entry class of the pre-search list: the `const-class` loaded right before the
 * [PRE_SEARCH_LIST] name in a method handing both to [PAGINABLE_LIST_READER]. Null unless there's one.
 */
internal fun BytecodePatchContext.preSearchEntryType(): String? =
    classDefByStrings(PRE_SEARCH_LIST, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, PRE_SEARCH_LIST) }
        .mapNotNull(::listEntryType)
        .distinct()
        .singleOrNull()

private fun listEntryType(method: Method): String? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val types = code.indices.mapNotNull { index ->
        if (code[index].string() != PRE_SEARCH_LIST) return@mapNotNull null
        val type = (index - 1 downTo maxOf(0, index - 3)).firstOrNull { code[it].opcode == Opcode.CONST_CLASS }
            ?.let { ((code[it] as ReferenceInstruction).reference as TypeReference).type } ?: return@mapNotNull null
        val reads = code.subList(index + 1, minOf(code.size, index + 3)).any {
            (it as? ReferenceInstruction)?.reference.let { ref -> ref is MethodReference && ref.name == PAGINABLE_LIST_READER }
        }
        if (reads) type else null
    }
    return types.distinct().singleOrNull()
}

/** The classes outside the extension whose constructor names itself [PRE_SEARCH_SECTION]. */
internal fun BytecodePatchContext.preSearchSections(): List<ClassDef> =
    classDefByStrings(PRE_SEARCH_SECTION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .filter { section -> section.methods.any { it.name == "<init>" && holds(it, PRE_SEARCH_SECTION) } }

/**
 * Each place in [section]'s own (non-static) methods that reads an [entry]'s node with a no-argument
 * call, tests it for null, and then either hands it first to a static (tree, Class, int, int) reader
 * and keeps that reader's answer, the module tree the entry is drawn from, or (584) asks the node's
 * own no-argument accessor for the module and tests that for null too, jumping where the node's test
 * does. 585 asks both through interfaces: the node through one of [entryInterfaces], the interfaces
 * the entry class declares.
 */
internal fun preSearchSites(section: ClassDef, entry: String, entryInterfaces: Set<String> = emptySet()): List<PreSearchSite> =
    section.methods.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.mapNotNull { index -> siteAt(method, code, index, entry, entryInterfaces) }
    }

private fun siteAt(method: Method, code: List<Instruction>, index: Int, entry: String, entryInterfaces: Set<String>): PreSearchSite? {
    val call = code[index]
    // Why: 585 casts each entry to an interface its tree class declares and asks that for the node.
    val owners = when (call.opcode) {
        Opcode.INVOKE_VIRTUAL -> setOf(entry)
        Opcode.INVOKE_INTERFACE -> entryInterfaces
        else -> return null
    }
    val nodeRead = (call as ReferenceInstruction).reference as? MethodReference ?: return null
    if (nodeRead.definingClass !in owners || nodeRead.parameterTypes.isNotEmpty() || !nodeRead.returnType.startsWith("L")) {
        return null
    }
    if (code.getOrNull(index + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val node = (code[index + 1] as OneRegisterInstruction).registerA
    if (code.getOrNull(index + 2)?.opcode != Opcode.IF_EQZ || (code[index + 2] as OneRegisterInstruction).registerA != node) {
        return null
    }
    nodeSiteAt(method, code, index, nodeRead.returnType, node)?.let { return it.copy(accessor = nodeRead.name) }
    val reader = (index + 3 until minOf(code.size, index + 9)).firstOrNull { at ->
        val instruction = code[at]
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode == Opcode.INVOKE_STATIC && ref != null &&
            ref.parameterTypes.map(CharSequence::toString).drop(1) == listOf("Ljava/lang/Class;", "I", "I") &&
            (instruction as FiveRegisterInstruction).registerC == node
    } ?: return null
    // Nothing between the null test and the reader may write the node's register.
    if (code.subList(index + 3, reader).any { (it as? OneRegisterInstruction)?.registerA == node }) return null
    if (code.getOrNull(reader + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val module = (code[reader + 1] as OneRegisterInstruction).registerA
    if (module == node) return null
    val moduleType = ((code[reader] as ReferenceInstruction).reference as MethodReference).returnType
    if (!moduleType.startsWith("L")) return null
    return PreSearchSite(method, reader + 1, node, module, moduleType, accessor = nodeRead.name)
}

/**
 * 584's shape after the node's null test at [index] + 2: the node's accessor, its answer kept and
 * tested. 585's node is an interface, so its accessor is asked with invoke-interface.
 */
private fun nodeSiteAt(method: Method, code: List<Instruction>, index: Int, nodeType: String, node: Int): PreSearchSite? {
    val call = code.getOrNull(index + 3)?.takeIf { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_INTERFACE }
        as? FiveRegisterInstruction ?: return null
    val ref = (call as ReferenceInstruction).reference as? MethodReference ?: return null
    if (call.registerCount != 1 || call.registerC != node ||
        ref.definingClass != nodeType || ref.parameterTypes.isNotEmpty() || !ref.returnType.startsWith("L")
    ) {
        return null
    }
    if (code.getOrNull(index + 4)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val module = (code[index + 4] as OneRegisterInstruction).registerA
    if (code.getOrNull(index + 5)?.opcode != Opcode.IF_EQZ || (code[index + 5] as OneRegisterInstruction).registerA != module) {
        return null
    }
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    fun target(at: Int) = addresses[at] + (code[at] as OffsetInstruction).codeOffset
    if (target(index + 2) != target(index + 5)) return null
    return PreSearchSite(method, index + 1, node, module, nodeType, byNode = true)
}

/**
 * Right after the module tree is kept, hand the node and the module to the extension and keep its
 * answer as the module, cast back to the reader's type, so Facebook's own null test draws the empty
 * row for a null. Where the node is tested first (584), hand over the node right after it's kept and
 * keep the answer as the node instead. Refuses unless the section, its entry class and one site are
 * found, and nothing jumps past the call.
 */
internal fun BytecodePatchContext.hookPreSearchSections() {
    val entry = preSearchEntryType() ?: throw PatchException("no single entry class for \"$PRE_SEARCH_LIST\"")
    val sections = preSearchSections()
    val section = sections.singleOrNull()
        ?: throw PatchException("expected one section naming itself \"$PRE_SEARCH_SECTION\", found ${sections.size}")
    // Why: 585 reads the node through an interface the entry's tree class declares.
    val entryInterfaces = classDefByOrNull(entry)?.interfaces?.toSet() ?: emptySet()
    val sites = preSearchSites(section, entry, entryInterfaces)
    val site = sites.singleOrNull()
        ?: throw PatchException("${section.type} reads an entry's module in ${sites.size} places, expected one")
    val hand = if (site.byNode) "invoke-static { v${site.node} }, $PRE_SEARCH_NODE"
    else "invoke-static { v${site.node}, v${site.module} }, $PRE_SEARCH_ENTRY"
    handOver(site.method, site.read + 1, listOf(site.node, site.module), site.kept, site.type, hand)
}

/** The classes outside the extension with a method naming them [PRE_SEARCH_HEAD]. */
internal fun BytecodePatchContext.preSearchHeads(): List<ClassDef> =
    classDefByStrings(PRE_SEARCH_HEAD, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .filter { head -> head.methods.any { holds(it, PRE_SEARCH_HEAD) } }

/**
 * The first page's builder reads each entry the way 584's section does, node and module each tested
 * for null, so it's found by the section's own sites and hands over the node the same way. Refuses
 * unless the builder, the entry class and one such site are found.
 */
internal fun BytecodePatchContext.hookPreSearchHead() {
    val entry = preSearchEntryType() ?: throw PatchException("no single entry class for \"$PRE_SEARCH_LIST\"")
    val heads = preSearchHeads()
    val head = heads.singleOrNull()
        ?: throw PatchException("expected one class naming itself \"$PRE_SEARCH_HEAD\", found ${heads.size}")
    val entryInterfaces = classDefByOrNull(entry)?.interfaces?.toSet() ?: emptySet()
    val sites = preSearchSites(head, entry, entryInterfaces).filter { it.byNode }
    val site = sites.singleOrNull()
        ?: throw PatchException("${head.type} reads an entry's node in ${sites.size} places, expected one")
    handOver(site.method, site.read + 1, listOf(site.node), site.node, site.type, "invoke-static { v${site.node} }, $PRE_SEARCH_HEAD_NODE")
}

/** Where the null-state query keeps an entry's [node] of [type], tested for null at [test]. */
internal data class PreSearchQuerySite(val method: Method, val test: Int, val node: Int, val type: String)

/**
 * Each place in a static (Builder, Iterator) loop body that reads a node of the entries' type, keeps
 * it, maybe casts it, and tests it for null: read inline with [CONCRETE_READER] and the type's key
 * (584), or through an accessor holding both (585), asked of its class or an interface it declares.
 * The entry class's own accessor isn't one, since the section and the head read that.
 */
internal fun BytecodePatchContext.preSearchQuerySites(entry: String): List<PreSearchQuerySite> {
    val sites = mutableListOf<PreSearchQuerySite>()
    val accessors = mutableListOf<Method>()
    classDefForEachReferencing(names = listOf(CONCRETE_READER)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods) {
            if (isQueryBody(method)) {
                sites += querySites(method) { code, at -> readsNodeInline(code, at) }
            } else if (classDef.type != entry && isNodeAccessor(method)) {
                accessors += method
            }
        }
    }
    if (accessors.isEmpty()) return sites
    val owners = accessors.flatMap { listOf(it.definingClass) + (classDefByOrNull(it.definingClass)?.interfaces.orEmpty()) }.toSet()
    val names = accessors.map { it.name }.toSet()
    classDefForEachReferencing(types = owners) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods.filter(::isQueryBody)) {
            sites += querySites(method) { code, at ->
                val ref = (code[at] as ReferenceInstruction).reference as MethodReference
                ref.definingClass in owners && ref.name in names && ref.parameterTypes.isEmpty()
            }
        }
    }
    return sites
}

private fun isQueryBody(method: Method) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(LIST_BUILDER, "Ljava/util/Iterator;")

/** An instance method with no parameters answering an object read with [CONCRETE_READER] and the node type's key. */
private fun isNodeAccessor(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.isNotEmpty() || !method.returnType.startsWith("L")) {
        return false
    }
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.indices.any { readsNodeInline(code, it) }
}

/** [CONCRETE_READER] called at [at] with a register loaded with the node type's key just before. */
private fun readsNodeInline(code: List<Instruction>, at: Int): Boolean {
    val call = code[at] as? FiveRegisterInstruction ?: return false
    if (((call as ReferenceInstruction).reference as? MethodReference)?.name != CONCRETE_READER) return false
    val registers = listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    return (maxOf(0, at - 6) until at).any { index ->
        val key = code[index] as? NarrowLiteralInstruction
        key != null && key.narrowLiteral == NULL_STATE_NODE_KEY && (key as OneRegisterInstruction).registerA in registers
    }
}

/** The calls in [method] that [reads] says read the node, kept, maybe cast, then tested for null. */
private fun querySites(method: Method, reads: (List<Instruction>, Int) -> Boolean): List<PreSearchQuerySite> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        if (code[at].opcode !in QUERY_CALLS || !reads(code, at)) return@mapNotNull null
        if (code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        val node = (code[at + 1] as OneRegisterInstruction).registerA
        val cast = code.getOrNull(at + 2)?.takeIf { it.opcode == Opcode.CHECK_CAST && (it as OneRegisterInstruction).registerA == node }
        val test = if (cast != null) at + 3 else at + 2
        if (code.getOrNull(test)?.opcode != Opcode.IF_EQZ || (code[test] as OneRegisterInstruction).registerA != node) {
            return@mapNotNull null
        }
        val type = cast?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
            ?: ((code[at] as ReferenceInstruction).reference as MethodReference).returnType
        PreSearchQuerySite(method, test, node, type)
    }
}

private val QUERY_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)

/**
 * Right before the query's null test of the node, hand the node to the extension and keep its answer
 * cast back to what was there, so a null leaves the entry out. Refuses unless the entry class and one
 * query site are found.
 */
internal fun BytecodePatchContext.hookPreSearchQuery() {
    val entry = preSearchEntryType() ?: throw PatchException("no single entry class for \"$PRE_SEARCH_LIST\"")
    val sites = preSearchQuerySites(entry)
    val site = sites.singleOrNull()
        ?: throw PatchException("the null-state query reads an entry's node in ${sites.size} places, expected one")
    handOver(site.method, site.test, listOf(site.node), site.node, site.type, "invoke-static { v${site.node} }, $PRE_SEARCH_QUERY_NODE")
}

/**
 * Adds [hand] at [at] in [method], its answer kept in [kept] and cast back to [type]. Refuses a
 * register past v15 among [registers], or a jump onto [at], which would skip the call.
 */
private fun BytecodePatchContext.handOver(method: Method, at: Int, registers: List<Int>, kept: Int, type: String, hand: String) {
    if (registers.any { it > 15 }) {
        throw PatchException("${method.definingClass}->${method.name} keeps an entry past v15")
    }
    // A label on the null test stays there, so a jump to it would skip the call.
    if (at in branchTargets(method.implementation!!.instructions.toList())) {
        throw PatchException("${method.definingClass}->${method.name} jumps to the null test of what it keeps")
    }
    mutableClassDefBy(method.definingClass).findMutableMethodOf(method).addInstructions(
        at,
        """
            $hand
            move-result-object v$kept
            check-cast v$kept, $type
        """,
    )
}

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun holds(method: Method, string: String) =
    method.implementation?.instructions?.any { it.string() == string } == true

internal const val PRE_SEARCH_PAGINABLE = "Lcom/facebook/graphservice/interfaces/PaginableList;"

internal const val PRE_SEARCH_LIST_HOOK =
    "$EXTENSION_PACKAGE/search/PreSearchSections;->list(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"

/** Where the section keeps the paginated list it was just handed: the [list] register, set at [read]. */
internal data class PreSearchListSite(val method: Method, val read: Int, val list: Int, val scratch: Int)

/**
 * Each place in [section]'s own methods that asks something with no arguments for the paginated
 * list (an invoke answering [PRE_SEARCH_PAGINABLE]) and keeps the answer, with the asked object's register
 * written again by the very next instruction, so it can carry the hook's string meanwhile (#235).
 */
internal fun preSearchListSites(section: ClassDef): List<PreSearchListSite> =
    section.methods.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.mapNotNull { at ->
            val call = code[at].takeIf { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_VIRTUAL }
                as? FiveRegisterInstruction ?: return@mapNotNull null
            val ref = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
            if (ref.returnType != PRE_SEARCH_PAGINABLE || ref.parameterTypes.isNotEmpty() || call.registerCount != 1) return@mapNotNull null
            if (code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
            val list = (code[at + 1] as OneRegisterInstruction).registerA
            val scratch = call.registerC
            val next = code.getOrNull(at + 2) as? OneRegisterInstruction ?: return@mapNotNull null
            val writes = code[at + 2].opcode in setOf(Opcode.CONST, Opcode.CONST_WIDE, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_WIDE_16,
                Opcode.CONST_WIDE_32, Opcode.CONST_HIGH16, Opcode.CONST_WIDE_HIGH16)
            if (!writes || next.registerA != scratch || scratch == list || scratch > 15 || list > 15) return@mapNotNull null
            PreSearchListSite(method, at + 1, list, scratch)
        }
    }

/**
 * Right after the section keeps its paginated list, hand the list and the entry's node accessor name
 * to the extension and keep its answer as the list, so a list of only hidden rows says it has no
 * next page (#235). A jump onto the next instruction (the null-list path) keeps its target there and
 * skips the call, which is right: it has no list. Refuses unless the section and one site are found.
 */
internal fun BytecodePatchContext.hookPreSearchList() {
    val entry = preSearchEntryType() ?: throw PatchException("no single entry class for \"$PRE_SEARCH_LIST\"")
    val sections = preSearchSections()
    val section = sections.singleOrNull()
        ?: throw PatchException("expected one section naming itself \"$PRE_SEARCH_SECTION\", found ${sections.size}")
    val entryInterfaces = classDefByOrNull(entry)?.interfaces?.toSet() ?: emptySet()
    val accessors = preSearchSites(section, entry, entryInterfaces).map { it.accessor }.distinct()
    val accessor = accessors.singleOrNull()?.takeIf { it.isNotEmpty() }
        ?: throw PatchException("${section.type} reads an entry's node through ${accessors.size} accessors, expected one")
    val sites = preSearchListSites(section)
    val site = sites.singleOrNull()
        ?: throw PatchException("${section.type} reads its paginated list in ${sites.size} places, expected one")
    mutableClassDefBy(site.method.definingClass).findMutableMethodOf(site.method).addInstructions(
        site.read + 1,
        """
            const-string v${site.scratch}, "$accessor"
            invoke-static { v${site.list}, v${site.scratch} }, $PRE_SEARCH_LIST_HOOK
            move-result-object v${site.list}
            check-cast v${site.list}, $PRE_SEARCH_PAGINABLE
        """,
    )
}
