/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the page stack's methods count as its push and pop (#276). 585 asks for the page update
 * in two instance methods; 587 moved their shared body into one static taking the stack alone.
 */
class MenuSubpagesShapesTest {
    private val stack = "LX/Stack;"
    private val menu = ImmutableFieldReference(stack, "A02", "Ljava/lang/ref/WeakReference;")

    /** A method of the stack that reads the Menu into v0 from [self] and holds the page update. */
    private fun page(name: String, flags: Int, parameters: List<String>, self: Int): Method = ImmutableMethod(
        stack, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
        ImmutableMethodImplementation(4, listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, self, menu),
            ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(PAGE_UPDATE)),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ), null, null),
    )

    private fun stackOf(vararg methods: Method) = ImmutableClassDef(
        stack, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
        emptyList<ImmutableField>(), methods.toList(),
    )

    private val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

    @Test
    fun `585's push and pop are instance methods`() {
        val found = pageChanges(stackOf(page("A04", AccessFlags.PUBLIC.value, emptyList(), 3),
            page("A05", AccessFlags.PUBLIC.value, emptyList(), 3)), menu)

        assertEquals(listOf("A04" to 0, "A05" to 0), found.map { (method, read) -> method.name to read })
    }

    @Test
    fun `587's static taking the stack alone counts, a static taking more does not`() {
        val found = pageChanges(stackOf(page("A00", publicStatic, listOf(stack), 3),
            page("A01", publicStatic, listOf(stack, "I"), 2), page("A03", publicStatic, listOf("Ljava/lang/Object;"), 3)), menu)

        assertEquals(listOf("A00" to 0), found.map { (method, read) -> method.name to read })
    }
}
