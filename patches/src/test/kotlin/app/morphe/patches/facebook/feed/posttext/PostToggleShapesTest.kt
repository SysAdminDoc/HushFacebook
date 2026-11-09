/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Where a post's "see more" toggle names its component, on hand-built code: 585 loads the update's
 * name and names the class right after it, 586 also asks a Redex string table for the name, and the
 * class follows the table's answer.
 */
class PostToggleShapesTest {
    private val tableType = "Lfixture/Strings;"
    private val component = "Lfixture/PostComponent;"

    private fun method(owner: String, body: String, parameters: List<String> = emptyList(), returnType: String = "V") = MutableMethod(
        ImmutableMethod(
            owner, "A00", parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun classOf(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods.toList())

    /** A string table like 586's `LX/01AE;->A00`: index 0x1e9c answers the toggle. */
    private fun stringTable() = classOf(
        tableType,
        method(
            tableType,
            """
                packed-switch p0, :cases
                const-string v0, "unused"
                return-object v0
                :toggle
                const-string v0, "$TOGGLE"
                return-object v0
                :cases
                .packed-switch 0x1e9c
                    :toggle
                .end packed-switch
            """,
            parameters = listOf("I"), returnType = "Ljava/lang/String;",
        ),
    )

    private fun direct(after: String = "const-class v0, $component") =
        method("Lfixture/Direct;", "const-string v1, \"$TOGGLE\"\n$after\nreturn-void")

    private fun asking(index: String = "0x1e9c", after: String = "const-class v0, $component") =
        method("Lfixture/Asker;", "const/16 v0, $index\ninvoke-static { v0 }, $tableType->A00(I)Ljava/lang/String;\nmove-result-object v1\n$after\nreturn-void")

    @Test
    fun `585's toggle names the class straight after its name`() {
        val sender = direct()
        assertEquals(listOf(component), PatchContexts.of(listOf(classOf("Lfixture/Direct;", sender))).toggled(sender))
    }

    @Test
    fun `586's toggle asked of a string table names the class after the table's answer`() {
        val sender = asking()
        val context = PatchContexts.of(listOf(classOf("Lfixture/Asker;", sender), stringTable()))
        assertEquals(listOf(component), context.toggled(sender))
        // Another entry of the table isn't the toggle.
        val other = asking(index = "0x1e9d")
        assertEquals(emptyList<String>(), PatchContexts.of(listOf(classOf("Lfixture/Asker;", other), stringTable())).toggled(other))
    }

    @Test
    fun `a toggle naming no class after it is refused`() {
        val sender = asking(after = "const/4 v0, 0x0")
        val context = PatchContexts.of(listOf(classOf("Lfixture/Asker;", sender), stringTable()))
        assertThrows(PatchException::class.java) { context.toggled(sender) }
        val loaded = direct(after = "const/4 v0, 0x0")
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(classOf("Lfixture/Direct;", loaded))).toggled(loaded) }
    }

    @Test
    fun `the toggle's senders are its loaders and the table's askers, never the table`() {
        val context = PatchContexts.of(listOf(classOf("Lfixture/Direct;", direct()), classOf("Lfixture/Asker;", asking()), stringTable()))
        assertEquals(setOf("Lfixture/Direct;", "Lfixture/Asker;"), context.sendersOf(TOGGLE).map { it.definingClass }.toSet())
        assertEquals(setOf(component), context.sendersOf(TOGGLE).flatMap { context.toggled(it) }.toSet())
    }
}
