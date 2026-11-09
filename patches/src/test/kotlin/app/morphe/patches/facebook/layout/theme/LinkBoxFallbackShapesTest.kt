/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The link box's empty-config fallbacks on hand-built code. Up to 585 the reader falls through
 * `if-nez` on the config string's length into the fallback. 586's attachment footer jumps on an empty
 * string instead, to a block of its own after a `return`, which unboxes the second config's grey.
 */
class LinkBoxFallbackShapesTest {
    private val head = """
        const-wide v0, 0x${java.lang.Long.toHexString(LINK_BOX_COLOUR_CONFIG)}L
        invoke-static { v0, v1 }, Lfixture/Config;->A00(J)Ljava/lang/String;
        move-result-object v2
        invoke-static { v2 }, $PARSE_LINK_BOX
        move-result v3
        invoke-virtual { v2 }, Ljava/lang/String;->length()I
        move-result v4
    """.trimIndent()

    private fun reader(body: String): Method = MutableMethod(
        ImmutableMethod(
            "Lfixture/Footer;", "A00", emptyList(), "I", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null, null, ImmutableMethodImplementation(8, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, head + "\n" + body.trimIndent()) }

    /** The `move-result`s straight after a call named [name]. */
    private fun Method.after(name: String): List<Int> {
        val code = implementation!!.instructions.toList()
        return code.indices.filter { index ->
            index > 0 && code[index].opcode == Opcode.MOVE_RESULT &&
                ((code[index - 1] as? ReferenceInstruction)?.reference as? MethodReference)?.name == name
        }
    }

    @Test
    fun `585's fall-through fallback is the fallback`() {
        val method = reader(
            """
                if-nez v4, :done
                invoke-static { v5 }, Lfixture/Parent;->A00(Ljava/lang/Object;)I
                move-result v3
                :done
                return v3
            """,
        )
        assertEquals(method.after("A00"), linkBoxFallbacks(method))
    }

    @Test
    fun `586's jump to a block of its own that unboxes the grey is the fallback`() {
        for (branch in listOf("if-lez", "if-eqz")) {
            val method = reader(
                """
                    $branch v4, :grey
                    return v3
                    :grey
                    invoke-virtual { v5 }, Ljava/lang/Integer;->intValue()I
                    move-result v3
                    return v3
                """,
            )
            assertEquals(branch, method.after("intValue"), linkBoxFallbacks(method))
        }
    }

    @Test
    fun `a jump to the token's route, or to a block something else reaches, isn't`() {
        val token = reader(
            """
                if-lez v4, :token
                return v3
                :token
                invoke-static { v3 }, Lfixture/Token;->A00(I)I
                move-result v3
                return v3
            """,
        )
        assertEquals("a token read isn't an unboxing", emptyList<Int>(), linkBoxFallbacks(token))
        val shared = reader(
            """
                if-lez v4, :grey
                if-eqz v5, :grey
                return v3
                :grey
                invoke-virtual { v5 }, Ljava/lang/Integer;->intValue()I
                move-result v3
                return v3
            """,
        )
        assertEquals("another branch reaches the block", emptyList<Int>(), linkBoxFallbacks(shared))
        val inline = reader(
            """
                if-lez v4, :grey
                move v6, v3
                :grey
                invoke-virtual { v5 }, Ljava/lang/Integer;->intValue()I
                move-result v3
                return v3
            """,
        )
        assertEquals("the block is also fallen into", emptyList<Int>(), linkBoxFallbacks(inline))
    }
}
