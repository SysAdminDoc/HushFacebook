/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Where Hide read receipts finds the mark-read's future in hand, on each shape Facebook has shipped, beside the near misses it refuses. */
class ReadReceiptShapesTest {
    private val future = "Lfixture/Future;"
    private val post = "invoke-static {v0}, Lfixture/Poster;->post($future)V"

    private fun markRead(body: String) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Feature;", "markRead", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), future,
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /** Up to 585 the future is the answer of a call, moved into the register the method returns. */
    @Test
    fun `585's future comes out of a call`() {
        val method = markRead("""
            invoke-static {p1}, Lfixture/Futures;->make(Ljava/lang/Object;)$future
            move-result-object v0
            $post
            return-object v0
        """)
        assertEquals(0, method.futureRegister())
        assertEquals(2, method.futureReadyIndex())
    }

    /** 586 (`LX/0FDK;->A0A`) builds it: new-instance of the return type, then its constructor. The hook goes after the constructor. */
    @Test
    fun `586's future is built with new-instance and its constructor`() {
        val method = markRead("""
            new-instance v0, $future
            invoke-direct {v0, p1}, $future-><init>(Ljava/lang/Object;)V
            $post
            return-object v0
        """)
        assertEquals(0, method.futureRegister())
        assertEquals(2, method.futureReadyIndex())

        method.holdBackRead()
        val after = method.implementation!!.instructions.toList()
        assertEquals("asks after the constructor", HOLDS_CHAT_READ, ((after[2] as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals("tests the answer", Opcode.IF_EQZ, after[4].opcode)
        assertEquals("hands back the future", Opcode.RETURN_OBJECT to 0, after[5].opcode to (after[5] as OneRegisterInstruction).registerA)
        assertEquals("then posts as before", Opcode.INVOKE_STATIC, after[6].opcode)
    }

    /** A new instance of another type isn't the future the method answers. */
    @Test
    fun `a new instance of another type isn't the future`() {
        val method = markRead("""
            new-instance v0, Lfixture/Other;
            invoke-direct {v0, p1}, Lfixture/Other;-><init>(Ljava/lang/Object;)V
            check-cast v0, $future
            $post
            return-object v0
        """)
        assertThrows(PatchException::class.java) { method.futureReadyIndex() }
    }

    /** A new instance whose constructor runs on another register isn't in hand yet. */
    @Test
    fun `a future whose constructor never runs on it isn't in hand`() {
        val method = markRead("""
            new-instance v0, $future
            new-instance v1, $future
            invoke-direct {v1, p1}, $future-><init>(Ljava/lang/Object;)V
            $post
            return-object v0
        """)
        assertThrows(PatchException::class.java) { method.futureReadyIndex() }
    }
}
