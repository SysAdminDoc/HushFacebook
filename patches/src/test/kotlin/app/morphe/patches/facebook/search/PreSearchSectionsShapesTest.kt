/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-search item case in both shapes Facebook has built (#80): 583 reads an entry's module off
 * its node with a static reader, 584 tests the node for null first and reads the module with the
 * node's own accessor. Each gets its own hook, and a near miss of 584's shape gets none. 585 asks for
 * the node through an interface the entry's class declares, and gets 584's hook.
 */
class PreSearchSectionsShapesTest {
    private val section = "Lfixture/Section;"
    private val entry = "Lfixture/Entry;"
    private val node = "Lfixture/Node;"
    private val module = "Lfixture/Module;"
    private val tree = "Lcom/facebook/graphservice/tree/TreeJNI;"

    private fun ref(owner: String, name: String, parameters: List<String>, returns: String) =
        ImmutableMethodReference(owner, name, parameters, returns)

    private fun call(opcode: Opcode, ref: MethodReference, vararg registers: Int) = ImmutableInstruction35c(
        opcode, registers.size, registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 }, registers.getOrElse(2) { 0 },
        registers.getOrElse(3) { 0 }, registers.getOrElse(4) { 0 }, ref,
    )

    private fun method(owner: String, name: String, body: List<Instruction>, static: Boolean = false, returns: String = "V"): Method =
        ImmutableMethod(
            owner, name, emptyList<ImmutableMethodParameter>(), returns,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(8, body, null, null),
        )

    private val entryRows = "Lfixture/EntryRows;"

    private fun nodeRead(opcode: Opcode = Opcode.INVOKE_VIRTUAL, owner: String = entry) =
        call(opcode, ref(owner, "A00", emptyList(), node), 1)

    /** 583: node, its null test, the static reader, the module and its null test, then a tree to draw. */
    private fun staticItemCase() = method(section, "A2c", listOf(
        nodeRead(), // 0, 3 units
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), // 1, address 3
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 15), // 2, address 4, to 19
        ImmutableInstruction21c(Opcode.CONST_CLASS, 3, ImmutableTypeReference(module)), // 3, address 6
        call(Opcode.INVOKE_STATIC, ref("Lfixture/Reader;", "A09", listOf(tree, "Ljava/lang/Class;", "I", "I"), module), 0, 3, 4, 5), // 4, address 8
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2), // 5, address 11
        ImmutableInstruction21t(Opcode.IF_EQZ, 2, 8), // 6, address 12, to 20
        call(Opcode.INVOKE_STATIC, ref("Lfixture/Trees;", "A0M", listOf(module), tree), 2), // 7, address 14
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2), // 8, address 17
        ImmutableInstruction10x(Opcode.RETURN_VOID), // 9, address 18
        ImmutableInstruction10x(Opcode.RETURN_VOID), // 10, address 19
        ImmutableInstruction10x(Opcode.RETURN_VOID), // 11, address 20
    ))

    /**
     * 584: node, its null test, the node's accessor, the module and its null test to the same empty
     * row ([moduleJump] moves the second), then a tree to draw. 585 asks both with invoke-interface:
     * [nodeCall] on [nodeOwner], and [accessorCall].
     */
    private fun accessorItemCase(
        accessorOwner: String = node,
        receiver: Int = 0,
        moduleJump: Int = 10,
        nodeCall: Opcode = Opcode.INVOKE_VIRTUAL,
        nodeOwner: String = entry,
        accessorCall: Opcode = Opcode.INVOKE_VIRTUAL,
    ) =
        method(section, "A2S", listOf(
            nodeRead(nodeCall, nodeOwner), // 0, 3 units
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), // 1, address 3
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 16), // 2, address 4, to 20
            call(accessorCall, ref(accessorOwner, "A00", emptyList(), module), receiver), // 3, address 6
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), // 4, address 9
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, moduleJump), // 5, address 10, to 20
            call(Opcode.INVOKE_STATIC, ref("Lfixture/Trees;", "A0M", listOf(module), tree), 0), // 6, address 12
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), // 7, address 15
            ImmutableInstruction10x(Opcode.RETURN_VOID), // 8, address 16
            ImmutableInstruction10x(Opcode.RETURN_VOID), // 9, address 17
            ImmutableInstruction10x(Opcode.RETURN_VOID), // 10, address 18
            ImmutableInstruction10x(Opcode.RETURN_VOID), // 11, address 19
            ImmutableInstruction10x(Opcode.RETURN_VOID), // 12, address 20
        ))

    private fun sectionClass(itemCase: Method): ClassDef = ImmutableClassDef(
        section, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            method(section, "<init>", listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(PRE_SEARCH_SECTION)),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )),
            itemCase,
        ),
    )

    /** The list reader naming the entry class right before the list's name. */
    private fun listClass(): ClassDef = ImmutableClassDef(
        "Lfixture/List;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(method("Lfixture/List;", "A00", listOf(
            ImmutableInstruction21c(Opcode.CONST_CLASS, 1, ImmutableTypeReference(entry)),
            ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference(PRE_SEARCH_LIST)),
            call(Opcode.INVOKE_VIRTUAL, ref(tree, "getCachedPaginableTreeList", listOf("Ljava/lang/String;", "Ljava/lang/Class;"),
                "Lcom/google/common/collect/ImmutableList;"), 0, 2, 1),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ), static = true)),
    )

    /** The entry's tree class, declaring the interface 585's section asks for the node. */
    private fun entryClass(): ClassDef = ImmutableClassDef(
        entry, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf(entryRows), null, null, null, emptyList<Method>(),
    )

    private fun hooked(itemCase: Method, extra: List<ClassDef> = emptyList()): List<Instruction> {
        val context = PatchContexts.of(listOf(sectionClass(itemCase), listClass()) + extra)
        context.hookPreSearchSections()
        return context.mutableClassDefBy(section).methods.single { it.name == itemCase.name }.implementation!!.instructions.toList()
    }

    private fun Instruction.call() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    @Test
    fun `583 hands over the node and the module once the module is kept`() {
        val site = preSearchSites(sectionClass(staticItemCase()), entry).single()
        assertEquals(listOf(5, 0, 2, module, false), listOf(site.read, site.node, site.module, site.type, site.byNode))
        val code = hooked(staticItemCase())
        val hand = code[6] as FiveRegisterInstruction
        assertEquals(listOf(PRE_SEARCH_ENTRY, 2, 0, 2), listOf(hand.call(), hand.registerCount, hand.registerC, hand.registerD))
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, 2), listOf(code[7].opcode, (code[7] as OneRegisterInstruction).registerA))
        assertEquals(module, ((code[8] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("the module's null test follows", Opcode.IF_EQZ, code[9].opcode)
    }

    @Test
    fun `584 hands over the node alone before its null test`() {
        val site = preSearchSites(sectionClass(accessorItemCase()), entry).single()
        assertEquals(listOf(1, 0, 0, node, true), listOf(site.read, site.node, site.module, site.type, site.byNode))
        val code = hooked(accessorItemCase())
        val hand = code[2] as FiveRegisterInstruction
        assertEquals(listOf(PRE_SEARCH_NODE, 1, 0), listOf(hand.call(), hand.registerCount, hand.registerC))
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, 0), listOf(code[3].opcode, (code[3] as OneRegisterInstruction).registerA))
        assertEquals(listOf(Opcode.CHECK_CAST, 0, node),
            listOf(code[4].opcode, (code[4] as OneRegisterInstruction).registerA, ((code[4] as ReferenceInstruction).reference as TypeReference).type))
        assertEquals("the node's null test follows", Opcode.IF_EQZ, code[5].opcode)
    }

    @Test
    fun `a 584 module test jumping elsewhere, or an accessor of something else, is no site`() {
        // Controls: the module's null test lands one row early, the accessor is another class's,
        // and the accessor is asked of another register.
        assertEquals(0, preSearchSites(sectionClass(accessorItemCase(moduleJump = 9)), entry).size)
        assertEquals(0, preSearchSites(sectionClass(accessorItemCase(accessorOwner = "Lfixture/Other;")), entry).size)
        assertEquals(0, preSearchSites(sectionClass(accessorItemCase(receiver = 1)), entry).size)
        val refusal = assertThrows(PatchException::class.java) { hooked(accessorItemCase(moduleJump = 9)) }
        assertTrue(refusal.message, refusal.message!!.contains("0 places"))
    }

    private fun interfaceItemCase(nodeOwner: String = entryRows) = accessorItemCase(
        nodeCall = Opcode.INVOKE_INTERFACE, nodeOwner = nodeOwner, accessorCall = Opcode.INVOKE_INTERFACE,
    )

    @Test
    fun `585 asks the entry's interface for the node and hands that over`() {
        val site = preSearchSites(sectionClass(interfaceItemCase()), entry, setOf(entryRows)).single()
        assertEquals(listOf(1, 0, 0, node, true), listOf(site.read, site.node, site.module, site.type, site.byNode))
        val code = hooked(interfaceItemCase(), listOf(entryClass()))
        val hand = code[2] as FiveRegisterInstruction
        assertEquals(listOf(PRE_SEARCH_NODE, 1, 0), listOf(hand.call(), hand.registerCount, hand.registerC))
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, 0), listOf(code[3].opcode, (code[3] as OneRegisterInstruction).registerA))
        assertEquals(listOf(Opcode.CHECK_CAST, 0, node),
            listOf(code[4].opcode, (code[4] as OneRegisterInstruction).registerA, ((code[4] as ReferenceInstruction).reference as TypeReference).type))
        assertEquals("the node's null test follows", Opcode.IF_EQZ, code[5].opcode)
    }

    private val head = "Lfixture/Head;"

    /** The first page's builder: its name, and a loop reading each entry's node and module in 584's shape. */
    private fun headClass(itemCase: Method, name: String = PRE_SEARCH_HEAD): ClassDef = ImmutableClassDef(
        head, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            method(head, "A0D", listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(name)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ), returns = "Ljava/lang/String;"),
            ImmutableMethod(head, "A0T", emptyList<ImmutableMethodParameter>(), itemCase.returnType, itemCase.accessFlags, null, null, itemCase.implementation),
        ),
    )

    private fun hookedHead(itemCase: Method, extra: List<ClassDef> = emptyList(), name: String = PRE_SEARCH_HEAD): List<Instruction> {
        val context = PatchContexts.of(listOf(headClass(itemCase, name), listClass()) + extra)
        context.hookPreSearchHead()
        return context.mutableClassDefBy(head).methods.single { it.name == "A0T" }.implementation!!.instructions.toList()
    }

    @Test
    fun `the first page's builder hands over each node before its null test`() {
        for ((itemCase, extra) in listOf(accessorItemCase() to emptyList(), interfaceItemCase() to listOf(entryClass()))) {
            val code = hookedHead(itemCase, extra)
            val hand = code[2] as FiveRegisterInstruction
            assertEquals(listOf(PRE_SEARCH_HEAD_NODE, 1, 0), listOf(hand.call(), hand.registerCount, hand.registerC))
            assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, 0), listOf(code[3].opcode, (code[3] as OneRegisterInstruction).registerA))
            assertEquals(listOf(Opcode.CHECK_CAST, 0, node),
                listOf(code[4].opcode, (code[4] as OneRegisterInstruction).registerA, ((code[4] as ReferenceInstruction).reference as TypeReference).type))
            assertEquals("the node's null test follows", Opcode.IF_EQZ, code[5].opcode)
        }
    }

    @Test
    fun `a builder with another name, or 583's static reader, gets no hook`() {
        // Controls: the tail's name in place of the head's, and the head reading modules statically.
        val unnamed = assertThrows(PatchException::class.java) { hookedHead(accessorItemCase(), name = "nullstate_nt_tail_network") }
        assertTrue(unnamed.message, unnamed.message!!.contains("found 0"))
        val static = assertThrows(PatchException::class.java) { hookedHead(staticItemCase()) }
        assertTrue(static.message, static.message!!.contains("0 places"))
    }

    private val query = "Lfixture/Query;"
    private val queryNode = "Lfixture/QueryNode;"
    private val queryRows = "Lfixture/QueryRows;"
    private val builder = "Lcom/google/common/collect/ImmutableList\$Builder;"

    private fun queryBody(
        owner: String,
        read: List<Instruction>,
        parameters: List<String> = listOf(builder, "Ljava/util/Iterator;"),
        list: String = builder,
        returns: String = "V",
    ): Method {
        // Each read keeps the node in v3, and its null test jumps to the closing return.
        val body = read + listOf(
            ImmutableInstruction21t(Opcode.IF_EQZ, 3, 5),
            call(Opcode.INVOKE_VIRTUAL, ref(list, "add", listOf("Ljava/lang/Object;"), list), 5, 3),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableMethod(
            owner, "A1C", parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(8, body, null, null),
        )
    }

    /**
     * 587's result callback: an instance method taking the result, with the loop inlined twice, each
     * asking the node's interface, testing it (jumping back to the loop head) and adding to a builder.
     */
    private fun queryCallback(): Method {
        fun loop() = accessorRead() + listOf(
            // Back four code units, to this loop's interface call.
            ImmutableInstruction21t(Opcode.IF_EQZ, 3, -4),
            call(Opcode.INVOKE_VIRTUAL, ref(builder, "add", listOf("Ljava/lang/Object;"), builder), 5, 3),
        )
        val body = loop() + loop() + listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
        return ImmutableMethod(
            query, "HRJ", listOf(ImmutableMethodParameter("Lfixture/Result;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, ImmutableMethodImplementation(8, body, null, null),
        )
    }

    /** 584: the concrete reader with the node type's key ([key]), the answer kept, cast and tested. */
    private fun inlineRead(key: Int = NULL_STATE_NODE_KEY, cast: Boolean = true) = listOfNotNull(
        ImmutableInstruction31i(Opcode.CONST, 0, key),
        call(Opcode.INVOKE_VIRTUAL, ref(tree, CONCRETE_READER, listOf("I", "I", "Ljava/lang/Class;", "I"), tree), 4, 1, 0, 2, 6),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
        if (cast) ImmutableInstruction21c(Opcode.CHECK_CAST, 3, ImmutableTypeReference(tree)) else null,
    )

    /** 585: the node tree's accessor, asked through its interface. */
    private fun accessorRead(owner: String = queryRows, name: String = "ALc") = listOf(
        call(Opcode.INVOKE_INTERFACE, ref(owner, name, emptyList(), queryNode), 4),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
    )

    /** The node tree class answering [accessorRead], declaring [queryRows]: its accessor loads the key. */
    private fun accessorClass(owner: String = "Lfixture/QueryTree;", key: Int = NULL_STATE_NODE_KEY): ClassDef = ImmutableClassDef(
        owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf(queryRows), null, null, null,
        listOf(method(owner, "ALc", listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, key),
            call(Opcode.INVOKE_VIRTUAL, ref(tree, CONCRETE_READER, listOf("I", "I", "Ljava/lang/Class;", "I"), tree), 4, 1, 0, 2, 3),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ), returns = queryNode)),
    )

    private fun queryClass(body: Method): ClassDef = ImmutableClassDef(
        query, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(body),
    )

    private fun hookedQuery(classes: List<ClassDef>): List<Instruction> {
        val context = PatchContexts.of(classes + listClass())
        context.hookPreSearchQuery()
        return context.mutableClassDefBy(query).methods.single().implementation!!.instructions.toList()
    }

    private fun assertQueryHook(code: List<Instruction>, test: Int, type: String) {
        val hand = code[test] as FiveRegisterInstruction
        assertEquals(listOf(PRE_SEARCH_QUERY_NODE, 1, 3), listOf(hand.call(), hand.registerCount, hand.registerC))
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, 3), listOf(code[test + 1].opcode, (code[test + 1] as OneRegisterInstruction).registerA))
        assertEquals(listOf(Opcode.CHECK_CAST, 3, type), listOf(code[test + 2].opcode,
            (code[test + 2] as OneRegisterInstruction).registerA, ((code[test + 2] as ReferenceInstruction).reference as TypeReference).type))
        assertEquals("the node's null test follows", Opcode.IF_EQZ, code[test + 3].opcode)
    }

    @Test
    fun `584's query hands over the node it reads inline`() {
        val site = PatchContexts.of(listOf(queryClass(queryBody(query, inlineRead())))).preSearchQuerySites(entry).single()
        assertEquals(listOf(4, 3, tree), listOf(site.test, site.node, site.type))
        assertQueryHook(hookedQuery(listOf(queryClass(queryBody(query, inlineRead())))), 4, tree)
        // Without a cast the call's own answer type is what goes back.
        assertQueryHook(hookedQuery(listOf(queryClass(queryBody(query, inlineRead(cast = false))))), 3, tree)
    }

    @Test
    fun `585's query hands over the node its accessor answers`() {
        val code = hookedQuery(listOf(queryClass(queryBody(query, accessorRead())), accessorClass()))
        assertQueryHook(code, 2, queryNode)
    }

    @Test
    fun `587's callback hands over the node at each inlined loop`() {
        val context = PatchContexts.of(listOf(queryClass(queryCallback()), accessorClass()))
        assertEquals("both loops are sites", listOf(2, 6), context.preSearchQuerySites(entry).map { it.test }.sorted())
        val code = hookedQuery(listOf(queryClass(queryCallback()), accessorClass()))
        // The second loop's hook went in first, so the first keeps its index and the second moved by three.
        assertQueryHook(code, 2, queryNode)
        assertQueryHook(code, 9, queryNode)
        assertEquals("six instructions added", 15, code.size)
    }

    @Test
    fun `another key, another body, the entry's own accessor or another method is no query site`() {
        // Controls: another field's key, a loop body adding the node to something other than a list
        // builder, a body that answers something (a loop body and a result callback answer nothing),
        // the accessor being the entry class's (the section and the head read that one), and another
        // method of its interface.
        fun sites(vararg classes: ClassDef) = PatchContexts.of(classes.toList()).preSearchQuerySites(entry).size
        assertEquals(0, sites(queryClass(queryBody(query, inlineRead(key = "node".hashCode())))))
        assertEquals(0, sites(queryClass(queryBody(query, inlineRead(), list = "Ljava/util/ArrayList;"))))
        assertEquals(0, sites(queryClass(queryBody(query, inlineRead(), returns = "Ljava/lang/Object;"))))
        assertEquals(0, sites(queryClass(queryBody(query, accessorRead(), returns = "Ljava/lang/Object;")), accessorClass()))
        assertEquals(0, sites(queryClass(queryBody(query, accessorRead(), list = "Ljava/util/ArrayList;")), accessorClass()))
        assertEquals(0, sites(queryClass(queryBody(query, accessorRead())), accessorClass(owner = entry)))
        assertEquals(0, sites(queryClass(queryBody(query, accessorRead(name = "ALd"))), accessorClass()))
        assertEquals(0, sites(queryClass(queryBody(query, accessorRead())), accessorClass(key = "node".hashCode())))
        val refusal = assertThrows(PatchException::class.java) {
            hookedQuery(listOf(queryClass(queryBody(query, accessorRead(name = "ALd"))), accessorClass()))
        }
        assertTrue(refusal.message, refusal.message!!.contains("0 places"))
    }

    @Test
    fun `an interface the entry doesn't declare, or no entry class to ask, is no site`() {
        // Controls: the node asked of another interface, the entry's interfaces left out, and a patch
        // with no entry class to read them from.
        assertEquals(0, preSearchSites(sectionClass(interfaceItemCase("Lfixture/OtherRows;")), entry, setOf(entryRows)).size)
        assertEquals(0, preSearchSites(sectionClass(interfaceItemCase()), entry).size)
        val refusal = assertThrows(PatchException::class.java) { hooked(interfaceItemCase()) }
        assertTrue(refusal.message, refusal.message!!.contains("0 places"))
    }
}
