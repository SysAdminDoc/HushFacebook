/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Where the scrubber's progress update checks for a drag before its time writer, on each shape Facebook has shipped, beside the near misses refused. */
class KeepProgressBarShapesTest {
    private val base = "Lfixture/Base;"
    private val state = "Lfixture/State;"
    private val write = "invoke-static {p0}, $base->write($base)V"

    private fun method(name: String, body: String) = MutableMethod(
        ImmutableMethod(
            base, name, listOf(ImmutableMethodParameter(base, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(3, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun progress(body: String): Pair<MutableMethod, ProgressUpdate> {
        val writer = method("write", "return-void")
        val update = method("update", body)
        val classDef = ImmutableClassDef(base, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod.of(writer), ImmutableMethod.of(update)))
        return update to ProgressUpdate(classDef, update, writer)
    }

    /** Up to 585 the writer runs right after an if-ne on the drag state. */
    @Test
    fun `585's drag check falls through to the writer`() {
        val (update, found) = progress("""
            const/4 v1, 0x0
            sget-object v0, $state->DRAG:Ljava/lang/Integer;
            if-ne v1, v0, :done
            $write
            :done
            return-void
        """)
        assertEquals(1, dragCheck(update, found))
    }

    /**
     * 586 (`LX/0A4r;->A06`) jumps to the writer from an if-eq on the drag state and falls through to an
     * idle state check and more gates that also end there, the last an if-eqz.
     */
    @Test
    fun `586's drag check jumps to the writer`() {
        val (update, found) = progress("""
            const/4 v1, 0x0
            sget-object v0, $state->DRAG:Ljava/lang/Integer;
            if-eq v1, v0, :write
            sget-object v0, $state->IDLE:Ljava/lang/Integer;
            if-ne v1, v0, :done
            invoke-static {}, Lfixture/Gate;->open()Z
            move-result v0
            if-eqz v0, :done
            :write
            $write
            :done
            return-void
        """)
        assertEquals(1, dragCheck(update, found))
    }

    /** An if-eq onto the writer that compares something other than a static state isn't the drag check. */
    @Test
    fun `a jump to the writer after another read is refused`() {
        val (update, found) = progress("""
            const/4 v1, 0x0
            const/4 v0, 0x1
            if-eq v1, v0, :write
            invoke-static {}, Lfixture/Gate;->open()Z
            move-result v0
            if-eqz v0, :done
            :write
            $write
            :done
            return-void
        """)
        assertThrows(PatchException::class.java) { dragCheck(update, found) }
    }

    /** Two state checks jumping to the writer leave no one place for the hook. */
    @Test
    fun `two state jumps to the writer are refused`() {
        val (update, found) = progress("""
            const/4 v1, 0x0
            sget-object v0, $state->DRAG:Ljava/lang/Integer;
            if-eq v1, v0, :write
            sget-object v0, $state->SEEK:Ljava/lang/Integer;
            if-eq v1, v0, :write
            invoke-static {}, Lfixture/Gate;->open()Z
            move-result v0
            if-eqz v0, :done
            :write
            $write
            :done
            return-void
        """)
        assertThrows(PatchException::class.java) { dragCheck(update, found) }
    }

    @Test
    fun `a writer run twice is refused`() {
        val (update, found) = progress("""
            const/4 v1, 0x0
            sget-object v0, $state->DRAG:Ljava/lang/Integer;
            if-ne v1, v0, :done
            $write
            $write
            :done
            return-void
        """)
        assertThrows(PatchException::class.java) { dragCheck(update, found) }
    }
}
