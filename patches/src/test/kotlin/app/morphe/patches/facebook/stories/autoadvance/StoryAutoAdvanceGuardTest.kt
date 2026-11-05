/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.autoadvance

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The register the Story guard borrows in front of the progress callback's navigation call. The
 * call sits in the middle of the callback, so a local can still be wanted there.
 */
class StoryAutoAdvanceGuardTest {
    private val navigate = "Lfixture/Controller;->next(Lcom/facebook/stories/model/StoryCard;Lfixture/Controller;)V"

    /** A progress callback, (bucket, card, progress)V on an instance, with [locals] locals. */
    private fun callback(locals: Int, smali: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Controller;", "onProgress",
            listOf("Lcom/facebook/stories/model/StoryBucket;", "Lcom/facebook/stories/model/StoryCard;", "I")
                .map { ImmutableMethodParameter(it, null, null) },
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(locals + 4, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    /** The shape both builds have: v0 is done with by the time the call comes. */
    private val facebooksShape = """
        const/4 v0, 0x1
        invoke-static {p2, v0}, Lfixture/Checks;->notNull(Ljava/lang/Object;I)V
        iput p3, p0, Lfixture/Controller;->progress:I
        const/16 v0, 0x3e8
        if-ne p3, v0, :done
        invoke-static {p2, p0}, $navigate
        :done
        return-void
    """

    @Test
    fun `the guard borrows v0 where the callback is done with it`() {
        val method = callback(1, facebooksShape)
        assertEquals(0, method.waitForTapRegister(5))

        method.addInstructionsWithLabels(
            5,
            waitForTapBlock(0, "Lfixture/Controller;"),
            ExternalLabel("navigate", method.getInstruction(5)),
        )
        val body = method.implementation!!.instructions.toList()
        assertEquals(Opcode.MOVE_RESULT, body[6].opcode)
        assertEquals(0, (body[6] as OneRegisterInstruction).registerA)
        // Held, the extension keeps the callback for a replay, and the callback's class gets the
        // story to start again when Loop stories is on.
        assertEquals(HELD, ((body[8] as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals(Opcode.INVOKE_STATIC_RANGE, body[9].opcode)
        assertEquals(Opcode.RETURN_VOID, body[10].opcode)
    }

    /** A touch release that moves a finished story on: the guard skips the call, and only the call. */
    @Test
    fun `a touch release skips its navigation while the story is held`() {
        val method = callback(
            2,
            """
                iget v1, p0, Lfixture/Controller;->progress:I
                const/16 v0, 0x3e8
                if-ne v1, v0, :done
                const/4 v0, 0x0
                invoke-static {v0, p0}, $navigate
                :done
                return-void
            """,
        )
        val register = method.waitForTapRegister(4)
        assertEquals("the call still reads v0", 1, register)
        method.addInstructionsWithLabels(4, releaseBlock(register), ExternalLabel("held", method.getInstruction(5)))
        val body = method.implementation!!.instructions.toList()
        assertEquals(Opcode.IF_NEZ, body[6].opcode)
        assertEquals(Opcode.INVOKE_STATIC, body[7].opcode)
        assertEquals("lands on the return after the call", 8, (body[6] as BuilderOffsetInstruction).target.location.index)
        assertEquals(Opcode.RETURN_VOID, body[8].opcode)
    }

    /**
     * A callback that still reads v0 after the call. The guard's answer used to go in v0 regardless,
     * so the code after the call would have read the guard's boolean in place of its own value.
     */
    @Test
    fun `a callback still reading v0 after the call gets the guard in another local`() {
        val method = callback(
            2,
            """
                const/4 v0, 0x1
                invoke-static {p2, p0}, $navigate
                invoke-static {v0}, Lfixture/Log;->note(I)V
                return-void
            """,
        )
        assertEquals(1, method.waitForTapRegister(1))
    }

    @Test
    fun `a callback with every local still wanted stops the patch`() {
        val method = callback(
            1,
            """
                const/4 v0, 0x1
                invoke-static {p2, p0}, $navigate
                invoke-static {v0}, Lfixture/Log;->note(I)V
                return-void
            """,
        )
        val refused = assertThrows(PatchException::class.java) { method.waitForTapRegister(1) }
        assertTrue(refused.message, refused.message.orEmpty().startsWith("Stop Story auto-advance:"))
    }
}
