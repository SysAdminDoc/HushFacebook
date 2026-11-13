/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Shorts viewer's pick hook (#375) names the config register right after the pick, so the
 * patch takes the config only when every path from the start to the pick loads it first and no other
 * write of its register gets back to the pick, and where the root's picks meet when it picks from
 * more than one branch (587). Small roots with v0 the
 * config, v1 the bar, v2 a flag and p0 (v3) the root.
 */
class VideoConfigLoadTest {
    private val config = "Lcom/example/Config;"
    private val field = ImmutableFieldReference("Lcom/example/Root;", "config", config)
    private val picker = ImmutableMethodReference("Lcom/example/Root;", "pick", emptyList<String>(), "Ljava/lang/Object;")

    private val load = ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3, field)
    private val pick = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, picker)
    private val keep = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1)
    private val done = ImmutableInstruction10x(Opcode.RETURN_VOID)

    private fun root(instructions: List<Instruction>, tries: List<ImmutableTryBlock> = emptyList()): Method = ImmutableMethod(
        "Lcom/example/Root;", "render", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(4, instructions, tries, null),
    )

    @Test
    fun `a config loaded on the way to the pick is taken`() {
        assertEquals(0, videoConfigLoad(root(listOf(load, pick, keep, done)), 0, config, 1))
    }

    @Test
    fun `a branch around the load leaves the config behind`() {
        // Code units: const/4 at 0, if-eqz at 1, iget-object at 3, the pick at 5.
        fun branching(offset: Int) = root(listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, offset),
            load, pick, keep, done,
        ))
        assertNull("a jump straight to the pick passed", videoConfigLoad(branching(4), 0, config, 3))
        // Positive control: the same jump landing on the load still loads it first.
        assertEquals(2, videoConfigLoad(branching(2), 0, config, 3))
    }

    @Test
    fun `a load that throws into a handler running the pick leaves the config behind`() {
        // Code units: iget-object at 0 (the try covers it), the pick at 2, move-result at 5,
        // return-void at 6, the handler's move-exception at 7 and its goto at 8.
        fun throwing(handler: Instruction) = root(
            listOf(load, pick, keep, done, ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 2), handler),
            listOf(ImmutableTryBlock(0, 2, listOf(ImmutableExceptionHandler("Ljava/lang/NullPointerException;", 7)))),
        )
        assertNull("the load's own handler was skipped", videoConfigLoad(throwing(ImmutableInstruction10t(Opcode.GOTO, -6)), 0, config, 1))
        // Positive control: a handler that just returns never reaches the pick.
        assertEquals(0, videoConfigLoad(throwing(done), 0, config, 1))
    }

    @Test
    fun `a write of the config's register after the pick counts only when it gets back to the pick`() {
        // Code units: iget-object at 0, the pick at 2, move-result at 5, if-eqz at 6 to the return,
        // a const/4 over the config at 8, then a goto at 9 that either ends the root or loops back to the pick.
        fun later(loop: Boolean) = root(listOf(
            load, pick, keep,
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction10t(Opcode.GOTO, if (loop) -7 else 1),
            done,
        ))
        // 587's armv7 root: the register is reused on the way to a return.
        assertEquals(0, videoConfigLoad(later(loop = false), 0, config, 1))
        assertNull("a write looping back to the pick passed", videoConfigLoad(later(loop = true), 0, config, 1))
        assertNull("a pick right after the write passed", videoConfigLoad(later(loop = false), 0, config, 5))
    }

    /**
     * 587's two picks from two branches: if-eqz at 0 to the second pick, the first pick at 2 kept at 5
     * and its goto at 6, the second pick at 7 kept at 10 and its goto at 11, both to the merge at 12, a
     * const/4 (index 7), then return-void at 13.
     */
    private fun twoPicks(
        firstGoto: Int = 6,
        secondKeep: Instruction = keep,
        secondGoto: Instruction = ImmutableInstruction10t(Opcode.GOTO, 1),
    ) = root(listOf(
        ImmutableInstruction21t(Opcode.IF_EQZ, 2, 7),
        pick, keep, ImmutableInstruction10t(Opcode.GOTO, firstGoto),
        pick, secondKeep, secondGoto,
        ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
        done,
    ))

    @Test
    fun `picks that keep the bar in one register and jump to one spot meet there`() {
        assertEquals(7 to 1, videoBarPickJumps(twoPicks(), listOf(1, 4)))
    }

    @Test
    fun `picks that don't meet in one spot with one register are refused`() {
        assertNull("picks jumping to two spots passed", videoBarPickJumps(twoPicks(firstGoto = 7), listOf(1, 4)))
        assertNull("picks keeping the bar in two registers passed",
            videoBarPickJumps(twoPicks(secondKeep = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2)), listOf(1, 4)))
        assertNull("a pick falling through to the merge passed",
            videoBarPickJumps(twoPicks(secondGoto = ImmutableInstruction11n(Opcode.CONST_4, 0, 0)), listOf(1, 4)))
        assertNull("no picks passed", videoBarPickJumps(twoPicks(), emptyList()))
    }
}
