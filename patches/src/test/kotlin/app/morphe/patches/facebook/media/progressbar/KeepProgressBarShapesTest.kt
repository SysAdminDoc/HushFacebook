/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Where the scrubber's progress update checks for a drag before its time writer, and where the newer
 * player's controls extension posts its hide, on each shape Facebook has shipped, beside the near
 * misses refused.
 */
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

    private val controls = "Lfixture/Controls;"
    private val other = "Lfixture/Other;"

    /** A video extension's event method, with room for the hide's handler, runnable and wide delay. */
    private fun event(body: String) = MutableMethod(
        ImmutableMethod(
            base, "event", listOf(ImmutableMethodParameter(base, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(6, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /**
     * The controls extension's branch as 586 (`LX/0YE3;->A0J`) and 587 (`LX/0Oru;->A0K`) have it: drop
     * the pending hide, then post it after 10 seconds with accessibility on, else 3, the short wait
     * jumping back to the post.
     */
    private val controlsHide = """
        sget-object v3, $controls->handler:Landroid/os/Handler;
        const/4 v0, 0x0
        invoke-virtual {v3, v0}, $REMOVE_CALLBACKS
        sget-object v2, $controls->hide:Ljava/lang/Runnable;
        invoke-static {}, Lfixture/Accessibility;->on()Z
        move-result v0
        if-eqz v0, :short
        const-wide/16 v0, 0x2710
        :post
        invoke-virtual {v3, v2, v0, v1}, $POST_DELAYED
        return-void
        :short
        const-wide/16 v0, 0xbb8
        goto :post
    """

    /**
     * 586 merged the controls extension's event method with fifteen other video extensions', picked
     * by class. Only the hide's post goes through the extension: the method's first instruction and
     * every other extension's branch stay Facebook's, and the short wait still jumps to the post (#447).
     */
    @Test
    fun `586's merged controls extension keeps running the other extensions`() {
        val method = event("""
            instance-of v0, p0, $controls
            if-eqz v0, :other
            $controlsHide
            :other
            instance-of v0, p0, $other
            if-eqz v0, :done
            invoke-static {p0}, $other->handle($base)V
            :done
            return-void
        """)
        val original = method.implementation!!.instructions.toList()
        val post = original.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == POST_DELAYED }
        method.noHidePostWhileKept()
        val patched = method.implementation!!.instructions.toList()
        assertEquals("the hook added or dropped instructions", original.size, patched.size)
        assertEquals("the method no longer starts with Facebook's pick", Opcode.INSTANCE_OF, patched[0].opcode)
        assertEquals(POSTS_CONTROLS_HIDE, (patched[post] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.INVOKE_STATIC, patched[post].opcode)
        val others = original.indices.filter { it != post }
        assertEquals(others.map { original[it].opcode }, others.map { patched[it].opcode })
        val jump = patched.indexOfFirst { it.opcode == Opcode.GOTO }
        assertEquals("the short wait no longer jumps to the post", post, (patched[jump] as BuilderOffsetInstruction).target.location.index)
    }

    /** A method posting two runnables with a delay can't say which is the hide, so it's refused. */
    @Test
    fun `a second post is refused`() {
        val method = event("""
            $controlsHide
            invoke-virtual {v3, v2, v0, v1}, $POST_DELAYED
            return-void
        """)
        assertThrows(PatchException::class.java) { method.noHidePostWhileKept() }
    }

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
