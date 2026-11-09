/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.video

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a post menu is filled, on hand-built code: 585 calls the menu builders' base, 586 calls an
 * interface the base implements, and only a builder taking the player origin opens a post's menu.
 */
class PostMenuShapesTest {
    private val base = "Lfixture/Base;"
    private val fill = "Lfixture/Fill;"
    private val signature = listOf(MENU, VIEW, "Lfixture/Props;", PLAYER_ORIGIN)
    private val baseClass = ImmutableClassDef(base, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf(fill), null, null,
        emptyList<ImmutableField>(), emptyList<ImmutableMethod>())

    private fun call(opcode: Opcode, owner: String, name: String = "B7q", parameters: List<String> = signature) =
        ImmutableInstruction3rc(opcode, 10, parameters.size + 1, ImmutableMethodReference(owner, name, parameters, "V"))

    private fun builder(vararg parameters: String) = ImmutableMethod("Lfixture/Builder;", "A04",
        parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/lang/Object;",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null)

    @Test
    fun `a virtual call on the base fills the menu, as 585 does`() {
        assertTrue(fillsPostMenu(call(Opcode.INVOKE_VIRTUAL_RANGE, base), baseClass, "B7q", signature))
    }

    @Test
    fun `an interface call on an interface of the base fills the menu, as 586 does`() {
        assertTrue(fillsPostMenu(call(Opcode.INVOKE_INTERFACE_RANGE, fill), baseClass, "B7q", signature))
    }

    @Test
    fun `other calls of the same name don't`() {
        assertFalse("an interface the base doesn't implement",
            fillsPostMenu(call(Opcode.INVOKE_INTERFACE_RANGE, "Lfixture/Other;"), baseClass, "B7q", signature))
        assertFalse("a virtual call on the interface",
            fillsPostMenu(call(Opcode.INVOKE_VIRTUAL_RANGE, fill), baseClass, "B7q", signature))
        assertFalse("an override calling its base",
            fillsPostMenu(call(Opcode.INVOKE_SUPER_RANGE, base), baseClass, "B7q", signature))
        assertFalse("another name", fillsPostMenu(call(Opcode.INVOKE_INTERFACE_RANGE, fill, "B7r"), baseClass, "B7q", signature))
        assertFalse("another signature", fillsPostMenu(call(Opcode.INVOKE_INTERFACE_RANGE, fill, parameters = signature.dropLast(1)),
            baseClass, "B7q", signature))
    }

    @Test
    fun `only a builder taking the player origin opens a post's menu`() {
        assertTrue(opensPostMenu(builder(VIEW, "Lfixture/Session;", PLAYER_ORIGIN, "Ljava/lang/Float;"), signature))
        assertFalse("586's builder for promotion and carousel units",
            opensPostMenu(builder(VIEW, "Ljava/lang/Integer;", "Ljava/lang/Runnable;"), signature))
    }
}
