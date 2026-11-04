/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Shorts viewer's pill hook (#375) hands the extension the kinds it reads from the shower's log
 * label switch, so only a switch on the kind parameter's intValue() names one. Small static
 * (View, controller, Integer)V showers with v0 and v1 locals and the kind in p2 (v4).
 */
class PillKindsTest {
    private val intValue = ImmutableMethodReference("Ljava/lang/Integer;", "intValue", emptyList<String>(), "I")

    // Code units: invoke-virtual at 0, move-result at 3, packed-switch at 4, return-void at 7, the
    // case's const-string at 8, return-void at 10, a nop to even the payload out at 11, the payload at 12.
    private fun shower(unboxed: Int, switched: Int): Method = ImmutableMethod(
        "LX/0AqK;", "A06",
        listOf("Landroid/view/View;", "LX/0AqK;", "Ljava/lang/Integer;").map { ImmutableMethodParameter(it, null, null) },
        "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(5, listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, unboxed, 0, 0, 0, 0, intValue),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, switched, 8),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(NEXT_PILL)),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction10x(Opcode.NOP),
            ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(2, 4))),
        ), null, null),
    )

    @Test
    fun `a switch on the kind names its pill`() {
        assertEquals(setOf(2), pillKinds(shower(unboxed = 4, switched = 0), NEXT_PILL))
    }

    @Test
    fun `a switch on anything else names none`() {
        assertEquals("the controller's intValue passed for the kind", emptySet<Int>(), pillKinds(shower(unboxed = 3, switched = 0), NEXT_PILL))
        assertEquals("a register never written passed for the kind", emptySet<Int>(), pillKinds(shower(unboxed = 4, switched = 1), NEXT_PILL))
    }
}
