/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code Download any reel puts in front of the sidebar assembly, and its field lookups. */
class DownloadReelShapesTest {
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"

    private val HELPER = "Lfixture/Sidebar;->hushfacebookDownloadButton(${session}Lfixture/Scope;Lfixture/Player;" +
        "Ljava/lang/Object;Ljava/util/List;Ljava/util/List;)V"

    private val Instruction.call get() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Where the block stood in the tests below: ahead of the nop at 4, writing v8 to v13. */
    private val place = SidebarBlockPlace(index = 4, session = 30, scoped = 31, player = 32, story = 33, buttons = 20, markers = 21, scratch = 8)

    /**
     * A sidebar builder whose button list comes from a helper declared to return a plain List, as
     * a build could hand it, and whose markers are a new ArrayList. The block goes in front of the
     * nop standing in for the assembly's argument moves.
     */
    private fun sidebar(): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sidebar;",
            "build",
            listOf(ImmutableMethodParameter("Lfixture/Scope;", null, null)),
            "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(40, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;
                move-result-object v20
                new-instance v21, Ljava/util/ArrayList;
                invoke-direct/range { v21 .. v21 }, Ljava/util/ArrayList;-><init>()V
                nop
                const/4 v0, 0x0
                return-object v0
            """,
        )
        addInstructionsAtControlFlowLabel(4, sidebarButtonBlock(place, HELPER))
    }

    /**
     * The block asks the switch, copies the session, scoped context, player, story and both lists
     * into six consecutive locals and calls the helper over the range. It writes nothing else, takes
     * nothing back and every one of its eleven instructions survives the inline compiler, which
     * leaves out a 4-bit form whose register doesn't fit without a word.
     */
    @Test
    fun `the helper gets six values in six consecutive locals`() {
        val body = sidebar().implementation!!.instructions.toList()
        assertEquals("the builder's seven instructions and the block's eleven", 18, body.size)
        assertTrue(body[4].call!!.endsWith("->showsButton()Z"))
        assertEquals(Opcode.MOVE_RESULT, body[5].opcode)
        assertEquals(8, (body[5] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, body[6].opcode)
        assertEquals(8, (body[6] as OneRegisterInstruction).registerA)
        val moves = (7..12).map { body[it] as TwoRegisterInstruction }
        moves.forEach { assertEquals(Opcode.MOVE_OBJECT_FROM16, it.opcode) }
        assertEquals((8..13).toList(), moves.map { it.registerA })
        assertEquals("session, scoped context, player, story, buttons, markers", listOf(30, 31, 32, 33, 20, 21), moves.map { it.registerB })
        val call = body[13] as RegisterRangeInstruction
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(HELPER, body[13].call)
        assertEquals(8, call.startRegister)
        assertEquals(6, call.registerCount)
        // The switch's no lands on the block's last nop, which falls through to Facebook's own nop.
        val branch = body[6] as BuilderOffsetInstruction
        assertSame(body[14], branch.target.location.instruction)
        assertEquals(Opcode.NOP, body[14].opcode)
        assertEquals(Opcode.NOP, body[15].opcode)
        assertEquals(Opcode.CONST_4, body[16].opcode)
        assertTrue("the block adds nothing to the lists itself, the helper does", body.none { it.call?.contains("->add(") == true })
    }

    /**
     * A builder shaped like the sidebar's end: the two lists fetched into v20 and v21, three
     * instructions standing in for the argument moves before the assembly call at index 8, then
     * [after]. Each names a high register only where its operand reaches it: the patcher's smali
     * compiler leaves out, without a word, an instruction whose register doesn't fit.
     */
    private fun builderEnd(after: String = "", window: String = "nop\nnop\nnop"): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sidebar;", "build", listOf(ImmutableMethodParameter("Lfixture/Scope;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(40, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                const/4 v3, 0x0
                invoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;
                move-result-object v20
                invoke-static { }, Lfixture/Lists;->markers()Ljava/util/ArrayList;
                move-result-object v21
                $window
                invoke-static/range { v20 .. v21 }, Lfixture/Assembly;->build(Ljava/util/List;Ljava/util/ArrayList;)V
                $after
                return-void
            """,
        )
    }

    /** The injection point three instructions before the assembly call, which is at index 8. */
    private val injectAt = 5
    private val assemblyAt = 8

    /**
     * What the block reads is what the call gets: a register nothing writes before the call is the
     * call's own, and one an object move fills in between is followed back to the register it copies.
     */
    @Test
    fun `an argument is followed back through the moves after the block, and nothing else`() {
        assertEquals(20, builderEnd().argumentAt(injectAt, assemblyAt, 20))
        // The list the call gets is swapped in from v21 after the block, so the block adds to v21.
        val swapped = builderEnd(window = "nop\nmove-object/from16 v20, v21\nnop")
        assertEquals(21, swapped.argumentAt(injectAt, assemblyAt, 20))
        // Two moves in a row are followed to the first.
        val twice = builderEnd(window = "move-object/from16 v22, v21\nmove-object/from16 v20, v22\nnop")
        assertEquals(21, twice.argumentAt(injectAt, assemblyAt, 20))

        // Made fresh between the block and the call, it doesn't exist yet where the block reads it.
        val made = builderEnd(window = "nop\ninvoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;\nmove-result-object v20")
        val refused = assertThrows(PatchException::class.java) { made.argumentAt(injectAt, assemblyAt, 20) }
        assertTrue(refused.message, refused.message.orEmpty().contains("makes v20 at instruction 7"))
    }

    /**
     * A render shaped like 585's end (LX/0B7e;->A17): the player parked in v29 at instruction 2, the
     * lists fetched, a button loop whose exit jumps to instruction 11, then the call's straight run,
     * which parks something else in v29 first and makes three lambdas in v0 to v2 before the call at
     * 18. The call reads v20 to v29, so v26 to v28 are live all through the run. [start] goes in
     * front of the player's read, [loop] at the end of the loop's body, [run] at the start of the
     * run, and [after] after the call. [registers] is the method's register count.
     */
    private fun loopEnd(
        start: String = "",
        loop: String = "",
        run: String = "",
        after: String = "",
        registers: Int = 40,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sidebar;", "render", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                $start
                invoke-static { }, Lfixture/Lists;->player()Lfixture/Player;
                move-result-object v3
                move-object/from16 v29, v3
                :parked
                invoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;
                move-result-object v20
                invoke-static { }, Lfixture/Lists;->markers()Ljava/util/ArrayList;
                move-result-object v21
                :loop
                invoke-static { }, Lfixture/Lists;->more()Z
                move-result v3
                if-eqz v3, :done
                $loop
                goto :loop
                :done
                $run
                move-object/from16 v29, v22
                const/4 v0, 0x0
                const/4 v1, 0x0
                const/4 v2, 0x0
                move-object/from16 v23, v0
                move-object/from16 v24, v1
                move-object/from16 v25, v2
                invoke-static/range { v20 .. v29 }, Lfixture/Assembly;->build(Ljava/util/List;Ljava/util/ArrayList;$OBJECTS)V
                $after
                return-void
            """,
        )
    }

    private val OBJECTS = "Ljava/lang/Object;".repeat(8)
    private val loopCallAt = 18
    private val runStart = 11

    /** The index of the assembly call in [method], the one range call. */
    private fun callOf(method: MutableMethod) =
        method.implementation!!.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC_RANGE }

    /** The place for the block in [method]: the session is v22, the scoped context v26, the story v27, the lists v20 and v21, the player parked in v29 at 2. */
    private fun placeIn(method: MutableMethod, parkedAt: Int? = 2) = method.sidebarBlockPlace(
        assemblyIndex = callOf(method),
        session = 22,
        scoped = 26,
        story = 27,
        buttons = 20,
        markers = 21,
        player = 29,
        parkedAt = parkedAt,
        playerParameter = null,
    )

    /**
     * 585 keeps the scoped context in v2 and a constant in v0 to the end of its render, so the
     * block can't borrow v0 to v2 as it did up to 584. It goes at the run's start, where the loop
     * ends, on six consecutive locals nothing reads again, and reads each value where the call gets it.
     */
    @Test
    fun `the block goes at the start of the call's run, on six dead locals`() {
        val method = loopEnd()
        assertEquals(runStart, method.straightRunStart(loopCallAt))
        val found = placeIn(method)
        assertEquals(runStart, found.index)
        assertEquals("v0 to v5 are dead there and none is read by the block", 0, found.scratch)
        assertEquals(
            listOf(22, 26, 27, 20, 21, 29),
            listOf(found.session, found.scoped, found.story, found.buttons, found.markers, found.player),
        )
    }

    /**
     * The first instruction of the run that works is taken. Here the run's first instruction makes
     * the scoped context (v26) fresh, so the block can't read it in front of that instruction, and
     * goes in front of the next one.
     */
    @Test
    fun `an instruction the block can't read the arguments in front of is passed over`() {
        val method = loopEnd(run = "const/16 v26, 0x0")
        assertEquals(runStart, method.straightRunStart(callOf(method)))
        val found = placeIn(method)
        assertEquals(runStart + 1, found.index)
        assertTrue("never between a call and the instruction taking its answer", method.implementation!!.instructions.toList()[found.index].opcode !in
            setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_EXCEPTION))
    }

    /** A locals the rest of the method reads can't be the block's. Here only v0 to v2 and v23 to v25 are free at the run: three apiece. */
    @Test
    fun `a run with no six consecutive dead locals is refused`() {
        val method = loopEnd(after = "invoke-static/range { v3 .. v19 }, Lfixture/Log;->all()V", registers = 31)
        val refused = assertThrows(PatchException::class.java) { placeIn(method) }
        assertTrue(refused.message, refused.message.orEmpty().contains("has no place for the button before the assembly call"))
        assertTrue(refused.message, refused.message.orEmpty().contains("no 6 consecutive locals"))
    }

    /**
     * The block's writes are 8-bit destinations, so six dead locals above v255 don't count. Here
     * v3 to v257 are read after the call, so only v0 to v2, v23 to v25 and v258 up are free.
     */
    @Test
    fun `dead locals above v255 are refused`() {
        val method = loopEnd(after = "invoke-static/range { v3 .. v257 }, Lfixture/Log;->all()V", registers = 300)
        val refused = assertThrows(PatchException::class.java) { placeIn(method) }
        assertTrue(refused.message, refused.message.orEmpty().contains("no 6 consecutive locals"))
    }

    /**
     * The player is read from the register the builder parked it in, so every path to the block
     * has to come through the parking. One that skips it, or one coming round the loop after the
     * loop parks something else, would hand the helper a wrong player that still verifies, so the
     * block is refused everywhere in the run.
     */
    @Test
    fun `the parked player has to reach the block on every path`() {
        loopEnd().requireParkedAt("the player", 29, parkedAt = 2, index = runStart)

        // The loop's body parks something else in v29, and its back edge reaches the run.
        val loopWrites = loopEnd(loop = "move-object/from16 v29, v22")
        val looped = assertThrows(PatchException::class.java) {
            loopWrites.requireParkedAt("the player", 29, parkedAt = 2, index = runStart + 1)
        }
        assertTrue(looped.message, looped.message.orEmpty().contains("reaches instruction 12"))
        assertTrue(looped.message, looped.message.orEmpty().contains("writes v29 at 10, 12"))
        val noPlace = assertThrows(PatchException::class.java) { placeIn(loopWrites) }
        assertTrue(noPlace.message, noPlace.message.orEmpty().contains("has no place for the button"))
        assertTrue(noPlace.message, noPlace.message.orEmpty().contains("first refusal, instruction 12: "))
        assertTrue(noPlace.message, noPlace.message.orEmpty().contains("the player: Lfixture/Sidebar;->render reaches instruction 12"))

        // A branch at the start jumps past the parking.
        val skips = loopEnd(start = "if-eqz v5, :parked")
        val skipped = assertThrows(PatchException::class.java) {
            skips.requireParkedAt("the player", 29, parkedAt = 3, index = runStart + 1)
        }
        assertTrue(skipped.message, skipped.message.orEmpty().contains("reaches instruction 12"))
        assertThrows(PatchException::class.java) { placeIn(skips, parkedAt = 3) }
    }

    /**
     * The arguments are followed back from the call to the run's start, where the block reads them,
     * and an index the loop jumps past is refused.
     */
    @Test
    fun `the block's arguments are read at the run's start, and not across a jump`() {
        val method = loopEnd()
        assertEquals("the call's v29 is v22 before the run parks it", 22, method.argumentAt(runStart, loopCallAt, 29))
        assertEquals(20, method.argumentAt(runStart, loopCallAt, 20))
        val made = assertThrows(PatchException::class.java) { method.argumentAt(runStart, loopCallAt, 23) }
        assertTrue(made.message, made.message.orEmpty().contains("makes v0 at instruction 12"))
        val jumped = assertThrows(PatchException::class.java) { method.argumentAt(runStart - 4, loopCallAt, 20) }
        assertTrue(jumped.message, jumped.message.orEmpty().contains("jumps between instruction 7 and the call at 18"))
    }

    /**
     * The block takes over the label of the instruction it goes in front of, so 585's loop exit runs
     * it, and its switch's no lands on its own last nop, which falls through to that instruction.
     */
    @Test
    fun `a jump to the block's place runs the block, and the switch's no skips to the original`() {
        val method = loopEnd()
        method.addInstructionsAtControlFlowLabel(
            runStart,
            sidebarButtonBlock(SidebarBlockPlace(runStart, session = 22, scoped = 26, player = 29, story = 27, buttons = 20, markers = 21, scratch = 0), HELPER),
        )
        val body = method.implementation!!.instructions.toList()
        val exit = body[9] as BuilderOffsetInstruction
        assertEquals(Opcode.IF_EQZ, exit.opcode)
        assertSame("the loop's exit lands on the block", body[runStart], exit.target.location.instruction)
        assertTrue(body[runStart].call!!.endsWith("->showsButton()Z"))

        val switch = body[runStart + 2] as BuilderOffsetInstruction
        val nop = body.indexOfLast { it.opcode == Opcode.NOP }
        assertSame("the switch's no goes to the block's last nop", body[nop], switch.target.location.instruction)
        val original = body[nop + 1] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[nop + 1].opcode)
        assertEquals(listOf(29, 22), listOf(original.registerA, original.registerB))
        assertEquals("the original stays once, after the block", 1,
            body.count { it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == 29 && it.registerB == 22 })
    }

    /** A one-method stand-in for a class's method, with [code] as its body. */
    private fun method(type: String, name: String, parameters: List<String>, returns: String, code: String, static: Boolean = false) =
        MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(8, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, code) }

    /**
     * 583's sidebar builder is the spec's method, handed the scoped context first and then the
     * component's fields, and the component's render only calls it. The render is told by its one
     * parameter, the builder's first, and the call.
     */
    @Test
    fun `a render handing the scoped context to the spec's builder is the sidebar's render`() {
        val builder = method(
            "Lfixture/Spec;", "build", listOf("Lfixture/Scope;", session, "Lfixture/Player;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nreturn-object v0",
        )
        val call = "invoke-virtual/range { v0 .. v3 }, Lfixture/Spec;->build(Lfixture/Scope;${session}Lfixture/Player;)Lfixture/Component;"
        val render = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v2, 0x0\nconst/4 v3, 0x0\n$call\nmove-result-object v0\nreturn-object v0")
        assertTrue(rendersThrough(render, builder))

        val otherScope = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Other;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v2, 0x0\nconst/4 v3, 0x0\n$call\nmove-result-object v0\nreturn-object v0")
        assertTrue("another scoped type", !rendersThrough(otherScope, builder))
        val twoParameters = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;", "I"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v2, 0x0\nconst/4 v3, 0x0\n$call\nmove-result-object v0\nreturn-object v0")
        assertTrue("a second parameter", !rendersThrough(twoParameters, builder))
        val noCall = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nreturn-object v0")
        assertTrue("no call to the builder", !rendersThrough(noCall, builder))
    }

    /** 585's Android 9 build hands its builder the tree props first and the scoped context later on. */
    @Test
    fun `the scoped context may come later in the builder's parameters, once`() {
        val builder = method(
            "Lfixture/Spec;", "build", listOf("Lfixture/Props;", session, "Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nreturn-object v0",
        )
        val call = "invoke-virtual/range { v0 .. v3 }, Lfixture/Spec;->build(Lfixture/Props;${session}Lfixture/Scope;)Lfixture/Component;"
        val render = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v1, 0x0\nconst/4 v2, 0x0\n$call\nmove-result-object v0\nreturn-object v0")
        assertTrue(rendersThrough(render, builder))

        val twice = method(
            "Lfixture/Spec;", "build", listOf("Lfixture/Scope;", session, "Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nreturn-object v0",
        )
        val twiceCall = "invoke-virtual/range { v0 .. v3 }, Lfixture/Spec;->build(Lfixture/Scope;${session}Lfixture/Scope;)Lfixture/Component;"
        val rendersTwice = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v1, 0x0\nconst/4 v2, 0x0\n$twiceCall\nmove-result-object v0\nreturn-object v0")
        assertTrue("a builder taking the scoped type twice", !rendersThrough(rendersTwice, twice))
    }

    private val handler = "Lkotlin/jvm/functions/Function1;"
    private val factory = "Lfixture/Factory;->make($session$handler$handler$handler$handler)Lfixture/Button;"

    /** A static of the sidebar's class taking the button's kind, with [code] as its body. */
    private fun buttonMaker(code: String, type: String = "Lfixture/Sidebar;", static: Boolean = true) =
        method(type, "button", listOf("I"), "Lfixture/Button;", code, static)

    /** A render naming the sidebar, when [named], and calling [call]. */
    private fun sidebarRender(call: String, named: Boolean = true) = method(
        "Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
        (if (named) "const-string v0, \"UDDSideBarComponent\"\n" else "") +
            "const/4 v1, 0x0\ninvoke-static { v1 }, $call\nmove-result-object v0\nreturn-object v0",
    )

    /**
     * Up to 583 the sidebar's builder calls the button factory, the call taking the session and
     * four handlers, itself. 584's render (LX/AF0;->A0l) calls a static of its own class instead
     * (LX/AF0;->A01), which calls the factory (LX/2Uj;->A00), so that static is read for it.
     */
    @Test
    fun `the sidebar's builder calls the button factory itself or through a static of its class`() {
        val makesButton = "invoke-static { v0, v1, v2, v3, v4 }, $factory\nmove-result-object v0\nreturn-object v0"
        val inline = method("Lfixture/Spec;", "build", listOf("Lfixture/Scope;"), "Lfixture/Button;",
            "const-string v0, \"UDDSideBarComponent\"\n$makesButton")
        assertTrue(isSidebarBuilder(inline, listOf(inline)))
        assertEquals(listOf(inline), buttonFactoryCallers(inline, listOf(inline)))

        val maker = buttonMaker(makesButton)
        val render = sidebarRender("Lfixture/Sidebar;->button(I)Lfixture/Button;")
        assertTrue(isSidebarBuilder(render, listOf(render, maker)))
        assertEquals(listOf(maker), buttonFactoryCallers(render, listOf(render, maker)))

        // Without the sidebar's name it's some other component's render.
        val unnamed = sidebarRender("Lfixture/Sidebar;->button(I)Lfixture/Button;", named = false)
        assertTrue("no sidebar name", !isSidebarBuilder(unnamed, listOf(unnamed, maker)))
        // A static of another class with the same name and signature isn't one of the siblings.
        val elsewhere = sidebarRender("Lfixture/Other;->button(I)Lfixture/Button;")
        assertTrue("another class's static", !isSidebarBuilder(elsewhere, listOf(elsewhere, maker)))
        // The static has to call the factory: four handlers without the session first is 582's
        // FDSBottomSheet, not a button.
        val sheet = buttonMaker("invoke-static { v0, v1, v2, v3 }, Lfixture/Sheet;->make($handler$handler$handler$handler)Lfixture/Button;\n" +
            "move-result-object v0\nreturn-object v0")
        assertTrue("a sibling making a sheet", !isSidebarBuilder(render, listOf(render, sheet)))
        // And it has to be the static the render calls, not an instance method of the same shape.
        val instance = buttonMaker(makesButton, static = false)
        assertTrue("an instance method", !isSidebarBuilder(render, listOf(render, instance)))
    }

    /**
     * 585's render calls no factory and no static that does. It names the sidebar and hands its
     * buttons, markers and names to the assembly call, which takes exactly two ArrayLists.
     */
    @Test
    fun `a render naming the sidebar and assembling it is the builder without any factory in reach`() {
        val assembling = "const/4 v1, 0x0\nconst/4 v2, 0x0\n" +
            "invoke-static/range { v1 .. v3 }, Lfixture/Assembly;->build(Ljava/util/List;Ljava/util/ArrayList;Ljava/util/ArrayList;)V\n" +
            "return-object v0"
        val render = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const-string v0, \"UDDSideBarComponent\"\n$assembling")
        assertTrue(isSidebarBuilder(render, listOf(render)))
        assertEquals(3, sidebarAssemblyIndex(render.implementation!!.instructions.toList()))

        // The name alone is in a dozen places.
        val unnamed = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\n$assembling")
        assertTrue("no sidebar name", !isSidebarBuilder(unnamed, listOf(unnamed)))
        assertEquals("the assembly is found by its two lists, whatever names the sidebar", 3,
            sidebarAssemblyIndex(unnamed.implementation!!.instructions.toList()))
        // One list, or the lists on a virtual call, isn't the assembly.
        val oneList = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const-string v0, \"UDDSideBarComponent\"\nconst/4 v1, 0x0\n" +
                "invoke-static { v1, v1 }, Lfixture/Assembly;->build(Ljava/util/List;Ljava/util/ArrayList;)V\nreturn-object v0")
        assertTrue(!isSidebarBuilder(oneList, listOf(oneList)))
        assertEquals(-1, sidebarAssemblyIndex(oneList.implementation!!.instructions.toList()))
        val virtual = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const-string v0, \"UDDSideBarComponent\"\nconst/4 v1, 0x0\n" +
                "invoke-virtual { v1, v1, v1 }, Lfixture/Assembly;->build(Ljava/util/ArrayList;Ljava/util/ArrayList;)V\nreturn-object v0")
        assertTrue(!isSidebarBuilder(virtual, listOf(virtual)))
    }

    /**
     * 585's render hands the button's kind to a static of its class (LX/0B7e;->A0D, LX/0B7h;->A0D),
     * which news up one small class per kind, and each of those calls the factory from a method of
     * its own. The factory is found from the render, through that static, to those methods.
     */
    @Test
    fun `the factory is found through the static that makes the small button classes`() {
        val makesButton = "invoke-static { v0, v1, v2, v3, v4 }, $factory\nmove-result-object v0\nreturn-object v0"
        val pizza = method("Lfixture/Pizza;", "A01", emptyList(), "Lfixture/Button;", makesButton)
        val other = method("Lfixture/Other;", "A00", emptyList(), "Lfixture/Button;", "const/4 v0, 0x0\nreturn-object v0")
        val kinds = method(
            "Lfixture/Sidebar;", "button", listOf("I"), "Lfixture/Button;",
            "new-instance v0, Lfixture/Pizza;\ninvoke-direct { v0 }, Lfixture/Pizza;-><init>()V\n" +
                "invoke-virtual { v0 }, Lfixture/Pizza;->A01()Lfixture/Button;\nmove-result-object v0\n" +
                "new-instance v1, Lfixture/Other;\ninvoke-direct { v1 }, Lfixture/Other;-><init>()V\n" +
                "invoke-virtual { v1 }, Lfixture/Other;->A00()Lfixture/Button;\nmove-result-object v1\nreturn-object v0",
            static = true,
        )
        val render = sidebarRender("Lfixture/Sidebar;->button(I)Lfixture/Button;")
        val resolve: (MethodReference) -> Method? = { call ->
            listOf(pizza, other).firstOrNull { it.definingClass == call.definingClass && it.name == call.name }
        }

        assertEquals("the static doesn't call the factory itself", emptyList<Method>(), buttonFactoryCallers(render, listOf(render, kinds)))
        assertEquals(listOf(pizza), reachedFactoryCallers(render, listOf(render, kinds), resolve))
        assertEquals("a class that can't be read names nothing", emptyList<Method>(), reachedFactoryCallers(render, listOf(render, kinds)) { null })

        // A render that calls the factory through a static of its own is answered as before.
        val maker = buttonMaker(makesButton)
        assertEquals(listOf(maker), reachedFactoryCallers(render, listOf(render, maker), resolve))
    }

    @Test
    fun `a call is resolved to the method of the class that matches its name, return type and parameters`() {
        val pizza = method("Lfixture/Pizza;", "A01", listOf("I"), "Lfixture/Button;", "const/4 v0, 0x0\nreturn-object v0")
        val owner = ImmutableClassDef("Lfixture/Pizza;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(pizza))
        assertEquals(
            "Lfixture/Pizza;->A01(I)Lfixture/Button;",
            resolveMethod(owner, ImmutableMethodReference("Lfixture/Pizza;", "A01", listOf("I"), "Lfixture/Button;")).toString(),
        )
        assertEquals(null, resolveMethod(owner, ImmutableMethodReference("Lfixture/Pizza;", "A01", listOf("J"), "Lfixture/Button;")))
        assertEquals(null, resolveMethod(owner, ImmutableMethodReference("Lfixture/Pizza;", "A01", listOf("I"), "V")))
        assertEquals(null, resolveMethod(owner, ImmutableMethodReference("Lfixture/Pizza;", "A02", listOf("I"), "Lfixture/Button;")))
    }

    /**
     * 583's arm64 builder asks for each button's marker itself. Its armeabi-v7a one hands the icon,
     * the button and the three lists to a static Redex outlined from the loop body, which asks for
     * the marker there, so that helper is read when the builder names no marker.
     */
    @Test
    fun `the marker is read off the builder, or off the outlined loop body it hands the icon to`() {
        val icon = "Lfixture/Icon;"
        val lists = "Ljava/util/AbstractCollection;"
        val marker = "Lfixture/Markers;->of(${icon})Lfixture/Marker;"
        val loopBody = method(
            "Lfixture/Loop;", "add", listOf(icon, "Ljava/lang/Object;", lists, lists, lists), "V",
            "invoke-static { p0 }, $marker\nmove-result-object v0\ninvoke-virtual { p3, v0 }, $lists->add(Ljava/lang/Object;)Z\nreturn-void",
            static = true,
        )
        val resolve = { call: MethodReference -> loopBody.takeIf { call.definingClass == "Lfixture/Loop;" && call.name == "add" } }
        val outlined = method("Lfixture/Spec;", "build", listOf("Lfixture/Scope;"), "V",
            "const/4 v0, 0x0\ninvoke-static { v0, v0, v0, v0, v0 }, Lfixture/Loop;->add(${icon}Ljava/lang/Object;$lists$lists$lists)V\nreturn-void")
        assertEquals(listOf(marker), iconMarkers(outlined, icon, resolve).map { it.toString() })

        // Asked in the builder, that's the one, and a helper taking the icon isn't read.
        val inline = method("Lfixture/Spec;", "build", listOf("Lfixture/Scope;"), "V",
            "const/4 v0, 0x0\ninvoke-static { v0 }, Lfixture/Own;->of(${icon})Lfixture/Marker;\n" +
                "invoke-static { v0, v0, v0, v0, v0 }, Lfixture/Loop;->add(${icon}Ljava/lang/Object;$lists$lists$lists)V\nreturn-void")
        assertEquals(listOf("Lfixture/Own;->of(${icon})Lfixture/Marker;"), iconMarkers(inline, icon, resolve).map { it.toString() })

        // A helper that can't be read names nothing.
        assertEquals(emptyList<String>(), iconMarkers(outlined, icon) { null }.map { it.toString() })
    }

    /**
     * 585's More sheet builder takes the scoped context twice. The one it tests against null isn't
     * the one the row helper reads the Context off; the one it never tests is.
     */
    @Test
    fun `a parameter tested against null is told from one used as is`() {
        val builder = method(
            "Lfixture/Sheet;", "add", listOf("Lfixture/Scope;", "Lfixture/Scope;", "Ljava/util/List;"), "V",
            "if-eqz p1, :done\nnop\n:done\nreturn-void", static = true,
        )
        assertTrue("the second is tested", builder.testsForNull(1))
        assertTrue("the first is not", !builder.testsForNull(0))
        assertTrue("nor the list", !builder.testsForNull(2))
        val tested = method("Lfixture/Sheet;", "add", listOf("Lfixture/Scope;"), "V", "if-nez p0, :done\nnop\n:done\nreturn-void", static = true)
        assertTrue("if-nez counts too", tested.testsForNull(0))
    }

    private fun field(name: String, type: String) = ImmutableField("Lfixture/Sidebar;", name, type, AccessFlags.PUBLIC.value, null, null, null)

    @Test
    fun `a field is picked by its type, and a missing or doubled one is named`() {
        val sessionField = field("session", session)
        val others = listOf(field("context", "Landroid/content/Context;"), field("count", "I"))
        assertSame(sessionField, fieldOfType(others + sessionField, session, "the session field"))

        val none = assertThrows(PatchException::class.java) { fieldOfType(others, session, "the session field") }
        assertTrue(none.message, none.message.orEmpty().contains("the session field"))
        assertTrue(none.message, none.message.orEmpty().contains("found 0"))

        val two = assertThrows(PatchException::class.java) {
            fieldOfType(others + sessionField + field("session2", session), session, "the session field")
        }
        assertTrue(two.message, two.message.orEmpty().contains("found 2"))
    }
}
