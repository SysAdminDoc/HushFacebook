/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloks' text span builder on hand-built code: 582 and 585 build the spans in a static
 * (Context, node, theme, List) method, 586 in a static (Spannable, node, theme, String, int, int)
 * helper. Either asks the themed colour reader and makes exactly one ForegroundColorSpan.
 */
class BloksTextBuilderShapesTest {
    private val node = "Lfixture/Node;"
    private val theme = "Lfixture/Theme;"
    private val reader = ImmutableMethodReference("Lfixture/Reader;", "A00", listOf(node, theme, "I"), "I")
    private val older = listOf("Landroid/content/Context;", node, theme, "Ljava/util/List;")
    private val newer = listOf("Landroid/text/Spannable;", node, theme, "Ljava/lang/String;", "I", "I")

    private val asksReader = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 2, 3, 0, 0, reader)
    private val makesSpan = ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 2, 4, 0, 0, 0, 0,
        ImmutableMethodReference("Landroid/text/style/ForegroundColorSpan;", "<init>", listOf("I"), "V"))

    private fun builder(parameters: List<String>, code: List<Instruction>, static: Boolean = true) = ImmutableMethod(
        "Lfixture/Builder;", "A02", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
        ImmutableMethodImplementation(8, code + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null))

    @Test
    fun `582 and 585's list builder is the builder`() {
        assertTrue(isBloksTextBuilder(builder(older, listOf(asksReader, makesSpan)), reader))
    }

    @Test
    fun `586's span helper is the builder`() {
        assertTrue(isBloksTextBuilder(builder(newer, listOf(asksReader, makesSpan)), reader))
    }

    @Test
    fun `other methods aren't`() {
        assertFalse("other parameters", isBloksTextBuilder(builder(newer.dropLast(1), listOf(asksReader, makesSpan)), reader))
        assertFalse("not static", isBloksTextBuilder(builder(newer, listOf(asksReader, makesSpan), static = false), reader))
        assertFalse("no reader call", isBloksTextBuilder(builder(newer, listOf(makesSpan)), reader))
        assertFalse("two spans", isBloksTextBuilder(builder(newer, listOf(asksReader, makesSpan, makesSpan)), reader))
        assertFalse("no span", isBloksTextBuilder(builder(older, listOf(asksReader)), reader))
    }
}
