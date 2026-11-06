/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.watchhistory

import app.morphe.RepoFiles
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parts of Don't send reel watch history that need no Facebook build: what the fingerprint
 * takes as the batcher's flush and what it refuses, the call the hook leaves in its place, and the
 * contract rule that holds a patched build to it.
 */
class ReelWatchHistoryShapesTest {
    private val owner = "Lfixture/Batcher;"
    private val sendType = "Lfixture/Send;"
    private val request = "Lfixture/Request;"
    private val execute = ImmutableMethodReference(EXECUTOR, "execute", listOf(RUNNABLE), "V")

    /** A class holding [redexName] where Redex writes it, a Runnable unless [runnable] is false. */
    private fun sendClass(type: String = sendType, redexName: String? = SEEN_STATE_SEND, runnable: Boolean = true): ClassDef {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val fields = redexName?.let {
            listOf(ImmutableField(type, "__redex_internal_original_name", "Ljava/lang/String;", flags, ImmutableStringEncodedValue(it), null, null))
        }
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            if (runnable) listOf(RUNNABLE) else null, null, null, fields, null,
        )
    }

    private fun lookup(vararg classes: ClassDef): (String) -> ClassDef? {
        val byType = classes.associateBy { it.type }
        return { byType[it] }
    }

    private fun string(register: Int, value: String) = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    private fun newInstance(register: Int, type: String = sendType) =
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, register, ImmutableTypeReference(type))

    private fun construct(register: Int, argument: Int, type: String = sendType) = ImmutableInstruction35c(
        Opcode.INVOKE_DIRECT, 2, register, argument, 0, 0, 0, ImmutableMethodReference(type, "<init>", listOf(request), "V"),
    )

    private fun handOver(executor: Int = 1, runnable: Int = 0) =
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, executor, runnable, 0, 0, 0, execute)

    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)

    /** The shape both builds have, cut down: name the input, name the mutation, build the send, hand it over. */
    private fun standardBody(): List<Instruction> = listOf(
        string(2, VIDEO_IDS),
        string(3, SEEN_STATE_MUTATION),
        newInstance(0),
        construct(0, 4),
        handOver(),
        returnVoid,
    )

    private fun flush(
        body: List<Instruction> = standardBody(),
        registers: Int = 8,
        parameters: List<String> = emptyList(),
        returnType: String = "V",
        static: Boolean = false,
    ): Method = ImmutableMethod(
        owner, "A00", parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
        null, null, ImmutableMethodImplementation(registers, body, null, null),
    )

    @Test
    fun `the flush is found at its one hand-over to the executor`() {
        assertEquals(4, sendPoint(flush(), lookup(sendClass())))
    }

    /** 585's Android 9 build keeps the helper's send apart: the session, both id lists and the callback. */
    @Test
    fun `a helper send taking the batch as arguments is found the same way`() {
        val arguments = listOf(
            "Lcom/facebook/auth/usersession/FbUserSession;", "Ljava/util/List;", "Ljava/util/List;",
            "Lkotlin/jvm/functions/Function0;",
        )
        assertEquals(4, sendPoint(flush(parameters = arguments, registers = 12), lookup(sendClass())))
        assertNull("another helper's runnable", sendPoint(flush(parameters = arguments, registers = 12),
            lookup(sendClass(redexName = "FbShortsViewerFooterHotCommentHelper\$createHotCommentQueryRunnable\$1"))))
    }

    private val input = "Lfixture/Input;"

    /** A GraphQL input whose `(List)` constructor loads [field], the way 584's merged `LX/7nD` loads `video_ids`. */
    private fun inputClass(field: String = VIDEO_IDS): ClassDef {
        val constructor = ImmutableMethod(
            input, "<init>", listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
            ImmutableMethodImplementation(3, listOf(string(0, field), returnVoid), null, null),
        )
        return ImmutableClassDef(input, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(constructor))
    }

    /** 584's flush: the input is built (and `video_ids` filled) by its constructor, then the mutation is named. */
    private fun mergedInputBody(constructFirst: Boolean = true): List<Instruction> {
        val build = listOf(
            newInstance(5, input),
            ImmutableInstruction35c(
                Opcode.INVOKE_DIRECT, 2, 5, 2, 0, 0, 0, ImmutableMethodReference(input, "<init>", listOf("Ljava/util/List;"), "V"),
            ),
        )
        val name = listOf(string(3, SEEN_STATE_MUTATION))
        return (if (constructFirst) build + name else name + build) + listOf(newInstance(0), construct(0, 4), handOver(), returnVoid)
    }

    @Test
    fun `a flush whose input constructor fills the ids is found`() {
        assertEquals(5, sendPoint(flush(mergedInputBody()), lookup(sendClass(), inputClass())))
        assertNull("the constructor fills something else", sendPoint(flush(mergedInputBody()), lookup(sendClass(), inputClass("post_ids"))))
        assertNull("the input is built after the mutation is named", sendPoint(flush(mergedInputBody(constructFirst = false)), lookup(sendClass(), inputClass())))
        assertNull("an input class the build doesn't have", sendPoint(flush(mergedInputBody()), lookup(sendClass())))
    }

    @Test
    fun `both literals are required`() {
        assertNull(sendPoint(flush(standardBody().filterIndexed { i, _ -> i != 0 }), lookup(sendClass())))
        assertNull(sendPoint(flush(standardBody().filterIndexed { i, _ -> i != 1 }), lookup(sendClass())))
    }

    @Test
    fun `only a void instance method is the flush`() {
        val send = lookup(sendClass())
        assertNull(sendPoint(flush(static = true), send))
        assertNull(sendPoint(flush(returnType = "Z"), send))
    }

    @Test
    fun `the hand-over has to come after the mutation is named, and be the only one`() {
        val send = lookup(sendClass())
        // Named after the hand-over: whatever went to the executor, it wasn't this mutation.
        val late = listOf(string(2, VIDEO_IDS), newInstance(0), construct(0, 4), handOver(), string(3, SEEN_STATE_MUTATION), returnVoid)
        assertNull(sendPoint(flush(late), send))
        // Two hand-overs: the fingerprint no longer says which one sends the batch.
        val twice = standardBody().dropLast(1) + handOver() + returnVoid
        assertNull(sendPoint(flush(twice), send))
    }

    @Test
    fun `the runnable handed over has to be a new instance of the send Redex named`() {
        assertNull("another helper's runnable", sendPoint(flush(), lookup(sendClass(redexName = "FbShortsViewerFooterHotCommentHelper\$createHotCommentQueryRunnable\$1"))))
        assertNull("no Redex name", sendPoint(flush(), lookup(sendClass(redexName = null))))
        assertNull("not a Runnable", sendPoint(flush(), lookup(sendClass(runnable = false))))
        assertNull("a class the build doesn't have", sendPoint(flush(), lookup()))

        // A runnable read out of a field, with the send built beside it but never handed over.
        val field = ImmutableFieldReference(owner, "A01", RUNNABLE)
        val fromField = listOf(
            string(2, VIDEO_IDS), string(3, SEEN_STATE_MUTATION), newInstance(5), construct(5, 4),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 7, field), handOver(), returnVoid,
        )
        assertNull(sendPoint(flush(fromField), lookup(sendClass())))
    }

    /** One branch builds the send and the other something else: either could reach the executor. */
    @Test
    fun `a hand-over two new instances can reach is refused`() {
        val other = "Lfixture/Other;"
        // Code units: const-string 2 each, if-eqz 2, new-instance 2, goto 1, invoke 3.
        val body = listOf(
            string(2, VIDEO_IDS), // 0, address 0
            string(3, SEEN_STATE_MUTATION), // 1, address 2
            ImmutableInstruction21t(Opcode.IF_EQZ, 6, 5), // 2, address 4, to index 5 at 9
            newInstance(0), // 3, address 6
            ImmutableInstruction10t(Opcode.GOTO, 3), // 4, address 8, to index 6 at 11
            newInstance(0, other), // 5, address 9
            handOver(), // 6, address 11
            returnVoid, // 7, address 14
        )
        assertNull(sendPoint(flush(body), lookup(sendClass(), sendClass(type = other))))
    }

    private fun Instruction.call(): MethodReference = (this as ReferenceInstruction).reference as MethodReference

    private fun MethodReference.descriptor() =
        definingClass + "->" + name + parameterTypes.joinToString("", "(", ")") + returnType

    @Test
    fun `the hook takes the hand-over's place with the same registers`() {
        val method = MutableMethod(flush())
        val before = method.implementation!!.instructions.toList()
        method.withholdSendAt(4)
        val after = method.implementation!!.instructions.toList()
        assertEquals("nothing is added around the call, so no branch moves", before.size, after.size)
        assertEquals(Opcode.INVOKE_STATIC, after[4].opcode)
        assertEquals(SEND, after[4].call().descriptor())
        assertEquals("the executor first, then the runnable", listOf(1, 0), after[4].callRegisters())
        assertEquals(before[4].codeUnits, after[4].codeUnits)
        assertTrue("an executor call is left", after.none { it.isExecute() })
        for (index in before.indices) {
            if (index != 4) assertEquals("instruction $index moved", before[index].opcode, after[index].opcode)
        }
    }

    /** A call written as a range stays one, because its registers can sit above v15. */
    @Test
    fun `a range hand-over becomes a range call`() {
        val body = listOf(
            string(2, VIDEO_IDS), string(3, SEEN_STATE_MUTATION), newInstance(18),
            ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 18, 1, ImmutableMethodReference(sendType, "<init>", emptyList(), "V")),
            ImmutableInstruction3rc(Opcode.INVOKE_INTERFACE_RANGE, 17, 2, execute), returnVoid,
        )
        val flush = flush(body, registers = 20)
        assertEquals(4, sendPoint(flush, lookup(sendClass())))
        val method = MutableMethod(flush)
        method.withholdSendAt(4)
        val hook = method.implementation!!.instructions.elementAt(4)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals(SEND, hook.call().descriptor())
        assertEquals(listOf(17, 18), hook.callRegisters())
    }

    @Test
    fun `an index that isn't the hand-over stops the patch and changes nothing`() {
        val method = MutableMethod(flush())
        val refusal = assertThrows(PatchException::class.java) { method.withholdSendAt(3) }
        assertTrue(refusal.message, refusal.message!!.contains(PATCH))
        assertTrue(method.implementation!!.instructions.elementAt(4).isExecute())
    }

    /**
     * The receipt refuses a patched build whose flush still hands its batch to the executor, or
     * whose stand-in went anywhere else or onto other registers, by a sole-call rule in
     * scripts/injected-mutation-contracts.txt. The rule names what the patch finds the flush by and
     * the call it writes, so a rename on one side can't leave it looking for something no build has.
     */
    @Test
    fun `the contract file holds the hook`() {
        val rules = File(RepoFiles.root, "scripts/injected-mutation-contracts.txt").readLines()
            .map { it.trim() }
            .filter { it.startsWith("sole-call ") && it.contains("/ReelWatchHistory;->") }
        assertEquals(
            listOf("sole-call $SEND replacing ${execute.descriptor()} in instance ()V holding $SEEN_STATE_MUTATION $VIDEO_IDS"),
            rules,
        )
    }
}
