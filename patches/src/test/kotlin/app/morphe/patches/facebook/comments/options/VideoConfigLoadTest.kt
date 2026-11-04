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
 * patch takes the config only when every path from the start to the pick loads it first. Small
 * roots with v0 the config, v1 the bar, v2 a flag and p0 (v3) the root.
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
}
