/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The search screen's pre-search probe (#80) on every Facebook build the bundle declares: one list
 * reader names the entry class of "null_state_results_paginating", one class names itself
 * "SearchTypeaheadPaginableSection", and one place in its own methods reads an entry's node and the
 * module tree off it. That class is the section Facebook logs "rendered_modules" for and compares
 * entries by "unique_id" in, and the module goes on to be drawn as a tree. The patch adds one call
 * after the module is kept, handing over both, keeps its answer as the module cast back to the
 * reader's type, and changes nothing else. Where the node is tested for null before its accessor
 * reads the module (584), the call comes right after the node is kept, hands over the node alone and
 * keeps its answer as the node. 585 asks for the node through an interface the entry's class declares
 * and for the module through the node's interface, reads "rendered_modules" inside the node's own
 * accessor, and builds the module's template into the row instead of reading a tree statically.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class PreSearchSectionsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun List<Instruction>.opcodes() = map { it.opcode }.filter { it != Opcode.NOP }

    private fun FiveRegisterInstruction.registers() =
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)

    /**
     * Whether one of [section]'s no-argument String calls is answered, in a class declaring the call's
     * owner (itself or as an interface), by a method of that name loading [key]: 585's node accessor.
     */
    private fun readsThroughAccessor(bundle: File, section: ClassDef, key: Int): Boolean {
        val asked = section.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { it.call() }
            .filter { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            .map { "${it.definingClass}->${it.name}" }.toSet()
        val owners = asked.map { it.substringBefore("->") }.toSet()
        if (owners.isEmpty()) return false
        // An implementation names its interface, so only the dex files naming an owner are read.
        val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in owners } }) { method ->
            method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/String;" &&
                method.implementation?.instructions?.any { it is NarrowLiteralInstruction && it.narrowLiteral == key } == true
        }
        val readerClasses = FixtureDex.classes(bundle, readers.map { it.definingClass }.toSet())
        return readers.any { reader ->
            val owner = readerClasses[reader.definingClass] ?: return@any false
            (owner.interfaces + owner.type).any { "$it->${reader.name}" in asked }
        }
    }

    @Test
    fun `each declared build hands each pre-search entry to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
                val classes = (FixtureDex.classesHolding(bundle, PRE_SEARCH_SECTION) +
                    FixtureDex.classesHolding(bundle, PRE_SEARCH_LIST))
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val entry = PatchContexts.of(classes).preSearchEntryType()
                assertNotNull("$name: no entry class for \"$PRE_SEARCH_LIST\"", entry)
                // The entry's own class says which interfaces stand for it, as the patch reads it.
                val entryClass = FixtureDex.classes(bundle, setOf(entry!!))[entry]
                    ?: throw AssertionError("$name has no $entry")
                val context = PatchContexts.of((classes + entryClass).distinctBy { it.type })
                val sections = context.preSearchSections()
                assertEquals("$name: sections naming themselves \"$PRE_SEARCH_SECTION\"", 1, sections.size)
                val section = sections.single()
                // Positive control: the section reads its entries' ids by their key, and their module
                // names by theirs, itself or (585) through an accessor it asks whose implementation does.
                val literals = section.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
                    .filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral }.toSet()
                assertTrue("$name: the section doesn't read rendered_modules",
                    "rendered_modules".hashCode() in literals || readsThroughAccessor(bundle, section, "rendered_modules".hashCode()))
                assertTrue("$name: the section doesn't read unique_id", "unique_id".hashCode() in literals)

                val sites = preSearchSites(section, entry, entryClass.interfaces.toSet())
                assertEquals("$name: places reading an entry's module", 1, sites.size)
                val site = sites.single()
                val code = site.method.code()
                // What the hook replaces is tested for null right after it's kept.
                assertEquals("$name: the null test of what the hook replaces",
                    listOf(Opcode.IF_EQZ, site.kept),
                    listOf(code[site.read + 1].opcode, (code[site.read + 1] as OneRegisterInstruction).registerA))
                // 584 then reads the module off the node and tests it for null too.
                val moduleKept = if (site.byNode) site.read + 3 else site.read
                if (site.byNode) {
                    val accessor = code[site.read + 2] as FiveRegisterInstruction
                    // 585's node is an interface, so the accessor is asked with invoke-interface.
                    val asked = if (accessor.opcode == Opcode.INVOKE_INTERFACE) Opcode.INVOKE_INTERFACE else Opcode.INVOKE_VIRTUAL
                    assertEquals("$name: the module is read off the node",
                        listOf(asked, site.node, site.type),
                        listOf(accessor.opcode, accessor.registerC, accessor.call()?.definingClass))
                    assertEquals("$name: the module's null test",
                        listOf(Opcode.IF_EQZ, site.module),
                        listOf(code[site.read + 4].opcode, (code[site.read + 4] as OneRegisterInstruction).registerA))
                }
                // The module is then drawn as a tree, or (585) its template is read off it through its
                // own accessor and built into the row.
                val drawn = code.subList(moduleKept + 2, minOf(code.size, moduleKept + 6)).firstOrNull {
                    it.opcode == Opcode.INVOKE_STATIC && (it as FiveRegisterInstruction).registerC == site.module
                }
                if (drawn != null || !site.byNode) {
                    assertEquals("$name: the module isn't read as a tree to draw",
                        "Lcom/facebook/graphservice/tree/TreeJNI;", drawn?.call()?.returnType)
                } else {
                    val template = code[moduleKept + 2]
                    assertEquals("$name: the module's template isn't read off it",
                        listOf(Opcode.INVOKE_INTERFACE, 1, site.module, true, Opcode.MOVE_RESULT_OBJECT),
                        listOf(template.opcode, (template as? FiveRegisterInstruction)?.registerCount,
                            (template as? FiveRegisterInstruction)?.registerC, template.call()?.parameterTypes?.isEmpty(),
                            code[moduleKept + 3].opcode))
                    val kept = (code[moduleKept + 3] as OneRegisterInstruction).registerA
                    val built = code.subList(moduleKept + 4, minOf(code.size, moduleKept + 8)).firstOrNull {
                        it.opcode == Opcode.INVOKE_DIRECT && it.call()?.name == "<init>" &&
                            kept in (it as FiveRegisterInstruction).registers()
                    }
                    assertNotNull("$name: the module's template isn't built into the row", built)
                }
                assertTrue("$name: a jump lands on the null test", site.read + 1 !in branchTargets(code))

                context.hookPreSearchSections()
                val patched = context.mutableClassDefBy(section.type).methods
                    .single { patchedMethod ->
                        patchedMethod.name == site.method.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) ==
                            site.method.parameterTypes.map(CharSequence::toString)
                    }.code()
                // The added code can move a switch payload, which gains or loses an alignment nop.
                assertEquals("$name: three instructions added", code.opcodes().size + 3, patched.opcodes().size)
                val hand = patched[site.read + 1] as FiveRegisterInstruction
                if (site.byNode) {
                    assertEquals("$name: the extension handed the node",
                        listOf(Opcode.INVOKE_STATIC, PRE_SEARCH_NODE, 1, site.node),
                        listOf(hand.opcode, hand.call().toString(), hand.registerCount, hand.registerC))
                } else {
                    assertEquals("$name: the extension handed node and module",
                        listOf(Opcode.INVOKE_STATIC, PRE_SEARCH_ENTRY, 2, site.node, site.module),
                        listOf(hand.opcode, hand.call().toString(), hand.registerCount, hand.registerC, hand.registerD))
                }
                val kept = patched[site.read + 2] as OneRegisterInstruction
                assertEquals("$name: the extension's answer is kept where the read's was",
                    listOf(Opcode.MOVE_RESULT_OBJECT, site.kept), listOf(kept.opcode, kept.registerA))
                val cast = patched[site.read + 3]
                assertEquals("$name: the answer is cast back to the read's type",
                    listOf(Opcode.CHECK_CAST, site.kept, site.type),
                    listOf(cast.opcode, (cast as OneRegisterInstruction).registerA,
                        ((cast as ReferenceInstruction).reference as TypeReference).type))
                assertEquals("$name: the read's type is what it returns",
                    code[site.read - 1].call()?.returnType, site.type)
                assertEquals("$name: the rest is Facebook's", code.opcodes(),
                    patched.filterIndexed { index, _ -> index !in site.read + 1..site.read + 3 }.opcodes())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    /** Runs [check] on every declared build's bundles, then asserts each build and ABI was seen. */
    private fun forEachBundle(check: (name: String, bundle: File, entry: String, classes: List<ClassDef>) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                abis += bundle.name.substringAfter("-$version-").substringBefore(".apkm")
                val list = FixtureDex.classesHolding(bundle, PRE_SEARCH_LIST).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }
                val entry = PatchContexts.of(list).preSearchEntryType()
                assertNotNull("${bundle.name}: no entry class for \"$PRE_SEARCH_LIST\"", entry)
                val entryClass = FixtureDex.classes(bundle, setOf(entry!!))[entry]
                    ?: throw AssertionError("${bundle.name} has no $entry")
                check(bundle.name, bundle, entry, list + entryClass)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    /** [method] of [type] once [context] patched it. */
    private fun patched(context: BytecodePatchContext, type: String, method: Method) =
        context.mutableClassDefBy(type).methods.single {
            it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }.code()

    /** Asserts [patched] is [code] with [hook]'s three instructions at [at], keeping [kept] cast back to [type]. */
    private fun assertHanded(name: String, code: List<Instruction>, patched: List<Instruction>, at: Int, hook: String, kept: Int, type: String) {
        assertEquals("$name: three instructions added", code.opcodes().size + 3, patched.opcodes().size)
        val hand = patched[at] as FiveRegisterInstruction
        assertEquals("$name: the extension handed the node",
            listOf(Opcode.INVOKE_STATIC, hook, 1, kept), listOf(hand.opcode, hand.call().toString(), hand.registerCount, hand.registerC))
        assertEquals("$name: the answer is kept where the node was",
            listOf(Opcode.MOVE_RESULT_OBJECT, kept), listOf(patched[at + 1].opcode, (patched[at + 1] as OneRegisterInstruction).registerA))
        val cast = patched[at + 2]
        assertEquals("$name: the answer is cast back",
            listOf(Opcode.CHECK_CAST, kept, type),
            listOf(cast.opcode, (cast as OneRegisterInstruction).registerA, ((cast as ReferenceInstruction).reference as TypeReference).type))
        assertEquals("$name: the rest is Facebook's", code.opcodes(), patched.filterIndexed { index, _ -> index !in at..at + 2 }.opcodes())
    }

    @Test
    fun `each declared build's first page builder hands each node to the extension`() = forEachBundle { name, bundle, entry, classes ->
        val heads = FixtureDex.classesHolding(bundle, PRE_SEARCH_HEAD)
        val context = PatchContexts.of((classes + heads).distinctBy { it.type })
        assertEquals("$name: builders naming themselves \"$PRE_SEARCH_HEAD\"", 1, context.preSearchHeads().size)
        val head = context.preSearchHeads().single()
        val entryInterfaces = classes.first { it.type == entry }.interfaces.toSet()
        val sites = preSearchSites(head, entry, entryInterfaces).filter { it.byNode }
        assertEquals("$name: places the builder reads an entry's node", 1, sites.size)
        val site = sites.single()
        val code = site.method.code()
        // Positive control: the builder answers a list, and each module it reads goes into one.
        assertEquals("$name: the builder answers a list", "Lcom/google/common/collect/ImmutableList;", site.method.returnType)
        val added = code[site.read + 5]
        assertEquals("$name: the module goes into a list",
            listOf("add", site.module), listOf(added.call()?.name, (added as? FiveRegisterInstruction)?.registers()?.getOrNull(1)))
        context.hookPreSearchHead()
        assertHanded(name, code, patched(context, head.type, site.method), site.read + 1, PRE_SEARCH_HEAD_NODE, site.node, site.type)
    }

    @Test
    fun `each declared build's null-state query hands each node to the extension`() = forEachBundle { name, bundle, entry, classes ->
        // The classes holding the node type's key, then those calling an accessor among them.
        val key = NULL_STATE_NODE_KEY
        val holders = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == CONCRETE_READER } }) { method ->
            method.implementation?.instructions?.any { it is NarrowLiteralInstruction && it.narrowLiteral == key } == true
        }
        val holderClasses = FixtureDex.classes(bundle, holders.map { it.definingClass }.toSet()).values.toList()
        val owners = holderClasses.filter { it.type != entry }.flatMap { it.interfaces + it.type }.toSet()
        val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in owners } }) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.implementation?.instructions?.any { it.call()?.definingClass in owners } == true
        }
        val callerClasses = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet()).values.toList()
        val context = PatchContexts.of((classes + holderClasses + callerClasses).distinctBy { it.type })
        val sites = context.preSearchQuerySites(entry)
        assertEquals("$name: places the query reads an entry's node", 1, sites.size)
        val site = sites.single()
        val code = site.method.code()
        // Positive control: the loop body adds what it reads off the node to the list it's handed.
        assertTrue("$name: the query adds nothing to its list", code.subList(site.test, code.size).any {
            it.call()?.let { call -> call.definingClass == "Lcom/google/common/collect/ImmutableList\$Builder;" && call.name == "add" } == true
        })
        assertTrue("$name: a jump lands on the null test", site.test !in branchTargets(code))
        context.hookPreSearchQuery()
        assertHanded(name, code, patched(context, site.method.definingClass, site.method), site.test, PRE_SEARCH_QUERY_NODE, site.node, site.type)
    }

    @Test
    fun `each declared build hands the section's paginated list to the extension`() = forEachBundle { name, bundle, entry, classes ->
        val sections = FixtureDex.classesHolding(bundle, PRE_SEARCH_SECTION).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }
        val context = PatchContexts.of((classes + sections).distinctBy { it.type })
        val section = context.preSearchSections().single()
        val sites = preSearchListSites(section)
        assertEquals("$name: places the section keeps its paginated list", 1, sites.size)
        val site = sites.single()
        val code = site.method.code()
        // Positive control: the list is then handed to the section builder.
        assertEquals("$name: the list read", PRE_SEARCH_PAGINABLE, code[site.read - 1].call()?.returnType)
        assertTrue("$name: the list isn't handed on", code.subList(site.read + 1, minOf(code.size, site.read + 30)).any {
            it.call()?.parameterTypes?.map(CharSequence::toString)?.contains(PRE_SEARCH_PAGINABLE) == true
        })
        // The register the hook borrows is written again right after, on every path.
        assertTrue("$name: the borrowed register is written next", code[site.read + 1] is OneRegisterInstruction &&
            (code[site.read + 1] as OneRegisterInstruction).registerA == site.scratch)
        context.hookPreSearchList()
        val patched = patched(context, section.type, site.method)
        assertEquals("$name: four instructions added", code.opcodes().size + 4, patched.opcodes().size)
        val hand = patched[site.read + 2] as FiveRegisterInstruction
        assertEquals("$name: the extension handed the list and the accessor",
            listOf(Opcode.CONST_STRING, Opcode.INVOKE_STATIC, PRE_SEARCH_LIST_HOOK, 2, site.list, site.scratch),
            listOf(patched[site.read + 1].opcode, hand.opcode, hand.call().toString(), hand.registerCount, hand.registerC, hand.registerD))
        assertEquals("$name: the answer is kept as the list and cast back",
            listOf(Opcode.MOVE_RESULT_OBJECT, site.list, Opcode.CHECK_CAST, site.list, PRE_SEARCH_PAGINABLE),
            listOf(patched[site.read + 3].opcode, (patched[site.read + 3] as OneRegisterInstruction).registerA,
                patched[site.read + 4].opcode, (patched[site.read + 4] as OneRegisterInstruction).registerA,
                ((patched[site.read + 4] as ReferenceInstruction).reference as TypeReference).type))
        assertEquals("$name: the rest is Facebook's", code.opcodes(),
            patched.filterIndexed { index, _ -> index !in site.read + 1..site.read + 4 }.opcodes())
    }

    @Test
    fun `the extension has the hooks`() {
        for (descriptor in listOf(PRE_SEARCH_LIST_HOOK, PRE_SEARCH_ENTRY, PRE_SEARCH_NODE, PRE_SEARCH_HEAD_NODE, PRE_SEARCH_QUERY_NODE)) {
            val type = descriptor.substringBefore("->")
            val name = descriptor.substringAfter("->").substringBefore("(")
            val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no PreSearchSections.$name", hook)
            assertEquals("$name's shape", descriptor,
                "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        }
    }
}
