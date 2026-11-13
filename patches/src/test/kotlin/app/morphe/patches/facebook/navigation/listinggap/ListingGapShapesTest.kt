/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.listinggap

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which register holds the decor listener's view at its call to the insets map. 587's 32-bit
 * listener is a merged lambda with 21 registers: its view arrives in v19, it copies that to v5
 * first, and it writes v19 only in another branch.
 */
class ListingGapShapesTest {
    private val insetsMap = ImmutableMethodReference(SAFE_AREA, "A00", listOf(INSETS), "Lcom/facebook/react/bridge/WritableNativeMap;")

    /** A lambda's invoke(Object, Object) with 21 registers: p0 is v18, the view v19, the insets v20. */
    private fun listener(vararg instructions: Instruction): Method = ImmutableMethod(
        "LX/Lambda;", "invoke", listOf("Ljava/lang/Object;", "Ljava/lang/Object;").map { ImmutableMethodParameter(it, null, null) },
        "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(21, instructions.toList(), null, null),
    )

    private fun copy(to: Int, from: Int) = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, to, from)

    private fun mapCall() = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, insetsMap)

    private fun returns() = ImmutableInstruction11x(Opcode.RETURN_OBJECT, 20)

    @Test
    fun `the copy made first holds the view when the parameter is written only in another branch`() {
        // 0: the copy, 1: if-eqz v20 jumps 5 code units to the call, 2 and 3: the other branch.
        val method = listener(copy(5, 19), ImmutableInstruction21t(Opcode.IF_EQZ, 20, 5), copy(19, 0), returns(),
            mapCall(), returns())

        assertEquals(5, decorRegister(method, 4))
    }

    @Test
    fun `a check-cast of the copy keeps the view`() {
        val method = listener(copy(5, 19), ImmutableInstruction21c(Opcode.CHECK_CAST, 5, ImmutableTypeReference("Landroid/view/View;")),
            mapCall(), returns())

        assertEquals(5, decorRegister(method, 2))
    }

    @Test
    fun `a copy written on one way in is not the view`() {
        // The if-eqz falls into the write of v5 or jumps past it; both reach the call.
        val method = listener(copy(5, 19), ImmutableInstruction21t(Opcode.IF_EQZ, 20, 4), copy(5, 0), mapCall(), returns())

        assertEquals("the parameter, which the hook then refuses as past v15", 19, decorRegister(method, 3))
    }

    @Test
    fun `a view written on the way with no copy stops the patch`() {
        val method = listener(copy(19, 0), mapCall(), returns())

        val refusal = assertThrows(PatchException::class.java) { decorRegister(method, 1) }
        assertTrue(refusal.message.orEmpty().contains("writes over its view before the insets map"))
    }
}
