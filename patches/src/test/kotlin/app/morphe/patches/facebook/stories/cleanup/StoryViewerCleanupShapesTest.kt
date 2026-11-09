/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the links column's render sorts a pill by its type, on each shape Facebook has shipped. */
class StoryViewerCleanupShapesTest {
    private val type = "Lfixture/PillType;"

    /** A render walking the links in p1, with [gate] between the type's null check and its ordinal. */
    private fun render(gate: String) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Column;", "render", listOf(ImmutableMethodParameter("Ljava/util/Iterator;", null, null)), "V",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(8, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                :loop
                invoke-interface { p1 }, Ljava/util/Iterator;->hasNext()Z
                move-result v0
                if-eqz v0, :done
                invoke-interface { p1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Lfixture/Link;
                invoke-interface { v1 }, Lfixture/Link;->type()$type
                move-result-object v2
                if-eqz v2, :loop
                $gate
                invoke-virtual { v2 }, Ljava/lang/Enum;->ordinal()I
                move-result v0
                sparse-switch v0, :cases
                const/4 v3, 0x1
                goto :loop
                :music
                const/4 v3, 0x2
                goto :loop
                :done
                return-void
                :cases
                .sparse-switch
                    0x0 -> :music
                .end sparse-switch
            """,
        )
    }

    /** Up to 585 (LX/0cVh;) the ordinal comes right after the type's null check. */
    @Test
    fun `585's column reads the ordinal right after the null check`() {
        val site = pillSwitch(render(""))
        assertEquals(PillSwitch(read = 6, type = 2, ordinal = 9, scratch = 0, next = 12, typeClass = type), site)
    }

    /**
     * 586 (LX/0dVe;) reads a boolean of the column after the null check and, when it's off, jumps
     * to the loop's step. The read is still the call five instructions up, and the question still
     * goes right before the ordinal.
     */
    @Test
    fun `586's column tests a flag between the null check and the ordinal`() {
        val site = pillSwitch(render("iget-boolean v0, p0, Lfixture/Column;->shown:Z\nif-eqz v0, :loop"))
        assertEquals(PillSwitch(read = 6, type = 2, ordinal = 11, scratch = 0, next = 14, typeClass = type), site)
    }

    /** A flag that skips forward isn't the loop's step, so the shape isn't taken for the column. */
    @Test
    fun `a flag jumping forward isn't the column's`() {
        assertNull(pillSwitch(render("iget-boolean v0, p0, Lfixture/Column;->shown:Z\nif-eqz v0, :done")))
    }

    /** A flag tested in a register other than the one just read isn't the gate. */
    @Test
    fun `a flag tested in another register isn't the gate`() {
        assertNull(pillSwitch(render("iget-boolean v0, p0, Lfixture/Column;->shown:Z\nif-eqz v4, :loop")))
    }
}
