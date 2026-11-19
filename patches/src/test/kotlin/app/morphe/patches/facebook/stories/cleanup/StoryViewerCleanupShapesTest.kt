/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val immutableList = "Lcom/google/common/collect/ImmutableList;"

    /**
     * A list runner shaped like 588's processBucketData: the list in p2 goes to each source in p1 in
     * turn, [answer] keeps what it hands back and [step] goes on to the next one. 8 registers, so
     * p0 to p2 are v5 to v7.
     */
    private fun runner(answer: String = "move-result-object p2", step: String = "goto :loop") = MutableMethod(
        ImmutableMethod(
            "Lfixture/Controller;", "run",
            listOf(session, "Ljava/util/List;", immutableList).map { ImmutableMethodParameter(it, null, null) },
            immutableList, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(8, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-interface { p1 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v0
                :loop
                invoke-interface { v0 }, Ljava/util/Iterator;->hasNext()Z
                move-result v1
                if-eqz v1, :done
                invoke-interface { v0 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Lfixture/Source;
                new-instance v2, Lfixture/Flags;
                invoke-direct { v2 }, Lfixture/Flags;-><init>()V
                invoke-interface { v1, p0, v2, p2 }, Lfixture/Source;->answer(${session}Lfixture/Flags;$immutableList)$immutableList
                $answer
                $step
                :done
                return-object p2
            """,
        )
    }

    /** #521: the loop's way out is right after the step back, with the finished list in p2. */
    @Test
    fun `the runner is done with its sources right after the step back`() {
        assertEquals(SourcesDone(at = 13, register = 7), sourcesDone(runner()))
    }

    /** An answer kept in another register isn't the runner's list going round. */
    @Test
    fun `an answer kept elsewhere isn't the list going round`() {
        assertNull(sourcesDone(runner(answer = "move-result-object v3")))
    }

    /** A source asked once, with no step back up, isn't the runner's loop. */
    @Test
    fun `a source asked once isn't the loop`() {
        assertNull(sourcesDone(runner(step = "return-object p2")))
    }

    /** The finished list goes through the extension and back into an ImmutableList, and the loop's test lands on that. */
    @Test
    fun `the finished list goes through the extension where the loop comes out`() {
        val method = runner()
        method.dropCreateCardsAt(SourcesDone(at = 13, register = 7))
        val code = method.implementation!!.instructions.toList()
        assertEquals(
            listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT,
                Opcode.RETURN_OBJECT),
            code.drop(13).map { it.opcode },
        )
        assertEquals(WITHOUT_CREATE_CARDS, (code[13] as ReferenceInstruction).reference.toString())
        assertEquals("$immutableList->copyOf(Ljava/util/Collection;)$immutableList", (code[15] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(7, 7, 7, 7), listOf(
            (code[13] as RegisterRangeInstruction).startRegister, (code[14] as OneRegisterInstruction).registerA,
            (code[15] as RegisterRangeInstruction).startRegister, (code[16] as OneRegisterInstruction).registerA,
        ))
        val at = code.runningFold(0) { offset, it -> offset + it.codeUnits }
        assertEquals("the loop's test doesn't land on the hook", at[13], at[4] + (code[4] as OffsetInstruction).codeOffset)
    }

    private val view = "Landroid/view/View;"
    private val placerParameters = listOf(view, "Lfixture/Pager;", "F", "I")

    /**
     * A placer on [owner] shaped like 588's cube (#521): [distance] sets the page's camera distance,
     * then the page in p1 turns by p3, once through a plain call and once through a range one, and
     * tilts on its X axis. 8 registers, so p0 to p4 are v3 to v7.
     */
    private fun placer(owner: String = "Lfixture/Cube;", distance: Boolean = true) = MutableMethod(
        ImmutableMethod(
            owner, "place", placerParameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(8, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                ${if (distance) "invoke-virtual { p1, p3 }, $view->setCameraDistance(F)V" else "nop"}
                invoke-virtual { p1, p3 }, $view->setRotationY(F)V
                move-object v0, p1
                move v1, p3
                invoke-virtual/range { v0 .. v1 }, $view->setRotationY(F)V
                invoke-virtual { p1, p3 }, $view->setRotationX(F)V
                return-void
            """,
        )
    }

    private fun positioner(type: String, method: MutableMethod) = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Lfixture/Positioner;", null, null, null, emptyList(), listOf(ImmutableMethod.of(method)),
    )

    /** #521: the cube's turns are its setRotationY calls, the plain and the range one, never its other calls. */
    @Test
    fun `the cube's turns are its setRotationY calls`() {
        assertEquals(listOf(1, 4), cubeTurns(placer()))
    }

    /** The cube is the positioner whose placer sets a camera distance; one that only turns isn't it, nor one under another base. */
    @Test
    fun `only a positioner setting the camera distance under the base placer is the cube`() {
        val base = placer(owner = "Lfixture/Positioner;", distance = false)
        assertTrue(isCubePositioner(positioner("Lfixture/Cube;", placer()), base))
        assertFalse(isCubePositioner(positioner("Lfixture/Tilt;", placer(owner = "Lfixture/Tilt;", distance = false)), base))
        assertFalse(isCubePositioner(positioner("Lfixture/Cube;", placer()), placer(owner = "Lfixture/Other;")))
    }

    /** Each turn goes to the extension in the same registers, a range call as a range call, and the rest stays. */
    @Test
    fun `each turn goes to the extension with the same page and angle`() {
        val method = placer()
        val stock = method.implementation!!.instructions.map { it.opcode }
        cubeTurns(method).forEach { method.turnThroughExtension(it) }
        val code = method.implementation!!.instructions.toList()
        assertEquals(stock.mapIndexed { at, it -> if (at == 1) Opcode.INVOKE_STATIC else if (at == 4) Opcode.INVOKE_STATIC_RANGE else it },
            code.map { it.opcode })
        assertEquals(listOf(CUBE_TURN, CUBE_TURN), listOf(code[1], code[4]).map { (it as ReferenceInstruction).reference.toString() })
        assertEquals(listOf(2, 4, 6), (code[1] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC, it.registerD) })
        assertEquals(listOf(2, 0), (code[4] as RegisterRangeInstruction).let { listOf(it.registerCount, it.startRegister) })
        assertEquals(emptyList<Int>(), cubeTurns(method))
        assertEquals("$view->setRotationX(F)V", (code[5] as ReferenceInstruction).reference.toString())
    }
}
