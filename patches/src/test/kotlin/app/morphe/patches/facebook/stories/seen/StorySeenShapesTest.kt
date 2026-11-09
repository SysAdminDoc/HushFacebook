/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of View stories anonymously that need no Facebook build: which classes and methods the
 * anchors take as the seen mutation, its request builder and its sender, which they turn down, and
 * the code the hook puts first in the sender.
 */
class StorySeenShapesTest {
    private val owner = "Lfixture/SeenSender;"
    private val mutationType = "Lfixture/SeenMutation;"
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val request = "Lfixture/Request;"
    private val builderParameters = listOf(session, "Ljava/util/Set;")
    private val senderParameters = listOf("Lfixture/Callback;", session, "Ljava/util/Map;", "Ljava/util/Set;", "Z")

    private fun string(register: Int, value: String) =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)

    private fun method(
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        body: List<Instruction>,
        static: Boolean = false,
        definingClass: String = owner,
    ): Method = ImmutableMethod(
        definingClass,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
        null,
        null,
        ImmutableMethodImplementation(registers, body, null, null),
    )

    /** The query class: its constructor loads [literals], the mutation's name and root field by default. */
    private fun mutationClass(vararg literals: String = arrayOf(SEEN_MUTATION, SEEN_ROOT_FIELD), constructor: String = "<init>") =
        ImmutableClassDef(
            mutationType, AccessFlags.PUBLIC.value, "Lfixture/Query;", null, null, null, null,
            listOf(method(constructor, emptyList(), "V", 2, literals.map { string(0, it) } + returnVoid, definingClass = mutationType)),
        )

    /** The request builder: loads [literal], creates [creates] and hands it back. */
    private fun builder(
        literal: String = STORY_IDS,
        creates: String = mutationType,
        returnType: String = request,
        static: Boolean = false,
        name: String = "getRequest",
    ) = method(
        name, builderParameters, returnType, 5,
        listOf(
            string(1, literal),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(creates)),
            ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(creates, "<init>", emptyList(), "V")),
            if (returnType == "V") returnVoid else ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ),
        static = static,
    )

    private val callBuilder = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL, 3, 3, 5, 7, 0, 0, ImmutableMethodReference(owner, "getRequest", builderParameters, request),
    )

    /** The sender: calls the builder [calls] times and returns, the way both builds' sender does once it has cards. */
    private fun sender(
        parameters: List<String> = senderParameters,
        returnType: String = "V",
        static: Boolean = false,
        calls: Int = 1,
        registers: Int = 9,
        name: String = "A00",
    ) = method(
        name, parameters, returnType, registers,
        List(calls) { listOf(callBuilder, ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)) }.flatten() +
            if (returnType == "V") returnVoid else ImmutableInstruction11x(Opcode.RETURN, 0),
        static = static,
    )

    private fun senderClass(vararg methods: Method): ClassDef =
        ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    @Test
    fun `the mutation is the class whose constructor names DirectSeenMutation and its root field`() {
        assertTrue(isSeenMutation(mutationClass()))
        assertFalse(isSeenMutation(mutationClass(SEEN_MUTATION)))
        assertFalse(isSeenMutation(mutationClass(SEEN_ROOT_FIELD)))
        // Facebook's bucket loaders compare a response's name with "DirectSeenMutation" too, in a
        // method of their own: the name alone, or outside a constructor, isn't the query.
        assertFalse(isSeenMutation(mutationClass(SEEN_MUTATION, SEEN_ROOT_FIELD, constructor = "A01")))
    }

    @Test
    fun `the builder fills story_ids_list and creates the mutation`() {
        assertTrue(isRequestBuilder(builder(), mutationType))
        assertFalse(isRequestBuilder(builder(), "Lfixture/OtherMutation;"))
        assertFalse(isRequestBuilder(builder(literal = "story_id"), mutationType))
        assertFalse(isRequestBuilder(builder(creates = "Lfixture/ReplyMutation;"), mutationType))
        assertFalse(isRequestBuilder(builder(static = true), mutationType))
        assertFalse(isRequestBuilder(builder(returnType = "V"), mutationType))
    }

    @Test
    fun `the sender is the one other method of the builder's class that calls it`() {
        val sender = sender()
        assertSame(sender, seenSender(senderClass(builder(), sender), mutationType))
        // The builder's name doesn't matter: Redex could rename it too.
        val renamed = senderClass(builder(name = "A01"), method("A00", senderParameters, "V", 9, listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 3, 5, 7, 0, 0,
                ImmutableMethodReference(owner, "A01", builderParameters, request)),
            returnVoid,
        )))
        assertEquals("A00", seenSender(renamed, mutationType)?.name)
    }

    @Test
    fun `a class that doesn't have exactly that shape gives no sender`() {
        // Negative controls: no caller, two callers, two builders, and callers of the wrong shape.
        assertNull(seenSender(senderClass(builder()), mutationType))
        assertNull(seenSender(senderClass(builder(), sender(), sender(name = "A01")), mutationType))
        assertNull(seenSender(senderClass(builder(), builder(name = "A02"), sender()), mutationType))
        assertNull(seenSender(senderClass(builder(), sender(static = true)), mutationType))
        assertNull(seenSender(senderClass(builder(), sender(returnType = "Z")), mutationType))
        assertNull(seenSender(senderClass(builder(), sender(parameters = senderParameters - "Ljava/util/Set;", registers = 8)),
            mutationType))
        assertNull(seenSender(senderClass(builder(), sender(calls = 2)), mutationType))
        // The same class building something else: the reply sender's class, for one.
        assertNull(seenSender(senderClass(builder(creates = "Lfixture/ReplyMutation;"), sender()), mutationType))
    }

    private val query = "Lfixture/Query;"
    private val queryInit = ImmutableMethodReference(query, "<init>", listOf("Ljava/lang/Class;", "Ljava/lang/String;"), "V")

    /**
     * 586's builder (`LX/0CRf;->getRequest`): no query class, it creates Facebook's generic query and
     * hands its constructor the result class and then the name, loaded into [nameRegister].
     * [overwrite] writes that register between the load and the call, [created] is what it creates.
     */
    private fun queryMaker(
        name: String = SEEN_MUTATION,
        nameRegister: Int = 3,
        overwrite: Boolean = false,
        created: String = query,
        swapped: Boolean = false,
    ) = method(
        "getRequest", builderParameters, request, 7,
        listOfNotNull(
            string(1, STORY_IDS),
            ImmutableInstruction21c(Opcode.CONST_CLASS, 2, ImmutableTypeReference("Lfixture/Result;")),
            string(nameRegister, name),
            if (overwrite) string(nameRegister, "fbandroid") else null,
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(created)),
            if (swapped) {
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 0, nameRegister, 2, 0, 0, queryInit)
            } else {
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 0, 2, nameRegister, 0, 0, queryInit)
            },
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ),
    )

    @Test
    fun `586's builder makes the query named DirectSeenMutation itself`() {
        assertTrue(makesSeenQuery(queryMaker()))
        assertTrue(isRequestBuilder(queryMaker(), null))
        val sender = sender()
        assertSame(sender, seenSender(senderClass(queryMaker(), sender), null))
        // The query class form and the made form don't stand in for each other.
        assertFalse(isRequestBuilder(queryMaker(), mutationType))
        assertFalse(isRequestBuilder(builder(), null))
        assertNull(seenSender(senderClass(builder(), sender()), null))
    }

    @Test
    fun `a query not named DirectSeenMutation in its constructor isn't the seen query`() {
        // Negative controls: another name, the name written over before the call, the name handed
        // first, and a constructor called on something the method didn't create.
        assertFalse(makesSeenQuery(queryMaker(name = "DirectReplyMutation")))
        assertFalse(makesSeenQuery(queryMaker(overwrite = true)))
        assertFalse(makesSeenQuery(queryMaker(swapped = true)))
        assertFalse(makesSeenQuery(queryMaker(created = "Lfixture/Other;")))
        assertFalse(isRequestBuilder(queryMaker(name = "DirectReplyMutation"), null))
    }

    private fun MutableMethod.at(index: Int) = implementation!!.instructions.elementAt(index)

    /** The sender as Facebook has it: a callback, then what the hook hands over. */
    private val sendShape = listOf("Lfixture/Callback;") + SENDER_SHAPE

    @Test
    fun `the sender sends the set the extension answers and returns first thing on null`() {
        val sender = MutableMethod(sender(parameters = sendShape, registers = 12))
        val own = sender.implementation!!.instructions.count()
        sender.filterViews()
        val hook = sender.at(0)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        val call = (hook as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/facebook/stories/StorySeen;", call.definingClass)
        assertEquals("toSend", call.name)
        assertEquals("Ljava/util/Set;", call.returnType)
        assertEquals("every argument goes, the sender first", 3, (hook as RegisterRangeInstruction).startRegister)
        assertEquals(9, hook.registerCount)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, sender.at(1).opcode)
        assertEquals("the answer goes in a local first", 0, (sender.at(1) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, sender.at(2).opcode)
        assertEquals(0, (sender.at(2) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_VOID, sender.at(3).opcode)
        // An answer of its own drops the card filters (p6, v9 here) before it becomes the set (p7, v10).
        assertEquals(4, offsetTarget(sender, 2))
        assertEquals(Opcode.IF_EQ, sender.at(5).opcode)
        assertEquals(8, offsetTarget(sender, 5))
        assertEquals(9, (sender.at(7) as TwoRegisterInstruction).registerA)
        assertEquals(10, (sender.at(8) as TwoRegisterInstruction).registerA)
        assertEquals(0, (sender.at(8) as TwoRegisterInstruction).registerB)
        // Then the sender's own code, none of it skipped.
        assertEquals(own + 9, sender.implementation!!.instructions.count())
        assertEquals(Opcode.INVOKE_VIRTUAL, sender.at(9).opcode)
    }

    /** A sender that doesn't take what the hook hands over is refused by name, before anything goes in. */
    @Test
    fun `a sender of another shape stops the patch`() {
        val other = MutableMethod(sender())
        val refusal = assertThrows(PatchException::class.java) { other.filterViews() }
        assertTrue(refusal.message, refusal.message!!.contains(PATCH))
        assertEquals(Opcode.INVOKE_VIRTUAL, other.at(0).opcode)
        assertEquals(3, other.implementation!!.instructions.count())
    }

    /** The instruction index a branch at [index] lands on. */
    private fun offsetTarget(method: MutableMethod, index: Int): Int {
        val instructions = method.implementation!!.instructions.toList()
        val branch = instructions[index] as OffsetInstruction
        var address = 0
        val addresses = instructions.map { val at = address; address += it.codeUnits; at }
        return addresses.indexOf(addresses[index] + branch.codeOffset)
    }
}
