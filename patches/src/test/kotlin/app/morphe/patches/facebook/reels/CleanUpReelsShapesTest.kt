/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Clean up Reels that need no Facebook build: how the Follow getter is traced out of a
 * dump shaped like each build's, what the reader rule refuses, and the code each hook puts in.
 */
class CleanUpReelsShapesTest {
    private fun method(
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        static: Boolean,
        smali: String,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Host;",
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0),
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    /** A static dump method: v0 to v4 are locals, p0 the writer, p1 the config, p2 a prefix. */
    private fun dump(smali: String) = method(
        "dump", listOf("Lfixture/Writer;", "Lfixture/Config;", "Ljava/lang/String;"), "V", 8, static = true, smali,
    )

    private fun put(name: String, getter: String) = """
        invoke-virtual { p1 }, Lfixture/Config;->$getter()Z
        move-result v0
        invoke-static { v0 }, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;
        move-result-object v1
        const-string v0, "$name"
        invoke-virtual { p0, v0, v1 }, Lfixture/Writer;->put(Ljava/lang/String;Ljava/lang/String;)V
    """

    /**
     * 580: the getter, its answer as text, then the name, then the write. The next name's getter
     * sits two instructions after this name, closer than its own four before, and isn't picked.
     */
    @Test
    fun `the value written beside the name is traced to its own getter`() {
        val dump = dump(
            put("getUDDConfig.resumePlayerOnBottomSheetDismiss", "before") +
                put("getUDDConfig.removeFollowingButton", "follow") +
                put("getUDDConfig.smallUiFootprintHideMore", "after") +
                put("getPlayerTabGrowthConfig.removeFollowingButton", "follow") +
                "return-void",
        )
        val found = followButtonGetter(dump)
        assertNull(found.problem)
        assertEquals("follow", found.getter!!.name)
        assertEquals(2, found.literals)
    }

    /** 577: a prefix joined to ".removeFollowingButton" first, then the getter, then the write. */
    @Test
    fun `a name built from the literal is followed to the write`() {
        val dump = dump(
            """
                move-object v4, p2
                const-string v2, ".removeFollowingButton"
                move-object v3, v2
                invoke-static { v4, v3 }, Lfixture/Text;->join(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v3
                invoke-virtual { p1 }, Lfixture/Config;->follow()Z
                move-result v2
                invoke-static { p0, v3, v2 }, Lfixture/Writer;->putBoolean(Lfixture/Writer;Ljava/lang/String;Z)V
                invoke-virtual { p1 }, Lfixture/Config;->after()Z
                move-result v2
                return-void
            """,
        )
        val found = followButtonGetter(dump)
        assertNull(found.problem)
        assertEquals("follow", found.getter!!.name)
        assertEquals(1, found.literals)
    }

    @Test
    fun `a dump the trace can't follow stops the patch with why`() {
        // No name at all.
        assertNotNull(followButtonGetter(dump(put("getUDDConfig.somethingElse", "other") + "return-void")).problem)
        // The value written is a constant, not a getter's answer.
        assertNotNull(followButtonGetter(dump(
            """
                const-string v1, "true"
                const-string v0, "getUDDConfig.removeFollowingButton"
                invoke-virtual { p0, v0, v1 }, Lfixture/Writer;->put(Ljava/lang/String;Ljava/lang/String;)V
                return-void
            """,
        )).problem)
        // Two names, two different getters: which one is the button's is a guess.
        val two = followButtonGetter(dump(
            put("getUDDConfig.removeFollowingButton", "follow") +
                put("getPlayerTabGrowthConfig.removeFollowingButton", "other") + "return-void",
        ))
        assertNull(two.getter)
        assertTrue(two.problem!!, two.problem!!.contains("2 getters"))
    }

    private fun reader(definingClass: String, name: String, vararg strings: String): Method = ImmutableMethod(
        definingClass, name, emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(
            1,
            strings.map {
                com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(
                    Opcode.CONST_STRING, 0, com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference(it),
                )
            } + com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
            null,
            null,
        ),
    )

    @Test
    fun `the Follow getter's readers have to be the three known ones`() {
        val dumper = reader("Lfixture/Dump;", "getExtraFileFromWorkerThread", WATCH_FEED_DUMP)
        val author = reader("Lfixture/Author;", "A1F", AUTHOR_COMPONENT)
        val render = reader("Lfixture/Row;", RENDER)
        assertNull(followReaderProblem(listOf(render, dumper, author), dumper))

        val fourth = reader("Lfixture/Other;", "somewhere")
        assertNotNull("a fourth reader", followReaderProblem(listOf(render, dumper, author, fourth), dumper))
        assertNotNull("no dump", followReaderProblem(listOf(render, author, fourth), dumper))
        assertNotNull("no author literal", followReaderProblem(listOf(render, dumper, fourth), dumper))
        assertNotNull("not a render", followReaderProblem(listOf(fourth, dumper, author), dumper))
        assertNotNull("two author rows", followReaderProblem(
            listOf(reader("Lfixture/Row;", RENDER, AUTHOR_COMPONENT), dumper, author), dumper))
    }

    /** The author row the Follow check is read from: the reader holding its literal, never the dump. */
    @Test
    fun `the author row is the one reader besides the dump holding its literal`() {
        val dumper = reader("Lfixture/Dump;", "getExtraFileFromWorkerThread", WATCH_FEED_DUMP, AUTHOR_COMPONENT)
        val author = reader("Lfixture/Author;", "A1F", AUTHOR_COMPONENT)
        val render = reader("Lfixture/Row;", RENDER)
        assertEquals(author, authorRow(listOf(render, dumper, author), dumper))
        assertNull("no author row", authorRow(listOf(render, dumper), dumper))
        assertNull("two author rows", authorRow(listOf(reader("Lfixture/Row;", RENDER, AUTHOR_COMPONENT), dumper, author), dumper))
    }

    /** A method a static call can reach: its owner, name and shape, and the strings it loads. */
    private fun callee(owner: String, name: String, returnType: String, first: String, vararg strings: String,
                       static: Boolean = true): Method = ImmutableMethod(
        owner, name, listOf(ImmutableMethodParameter(first, null, null)), returnType,
        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
        ImmutableMethodImplementation(
            2,
            strings.map {
                com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(
                    Opcode.CONST_STRING, 0, com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference(it),
                )
            } + com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
            null,
            null,
        ),
    )

    private fun reference(method: Method) = com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference(
        method.definingClass, method.name, method.parameterTypes, method.returnType,
    )

    /**
     * An author row whose body makes each of [calls], the first as a range call: invoke-static for a
     * static callee, invoke-virtual for an instance one (585's check lives on a singleton).
     */
    private fun authorCalling(vararg calls: Method): Method = ImmutableMethod(
        "Lfixture/Author;", "A1F", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(
            2,
            calls.mapIndexed { index, call ->
                val static = AccessFlags.STATIC.isSet(call.accessFlags)
                if (index == 0) {
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc(
                        if (static) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_VIRTUAL_RANGE, 1, 1, reference(call),
                    )
                } else {
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c(
                        if (static) Opcode.INVOKE_STATIC else Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, reference(call),
                    )
                }
            } + com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
            null,
            null,
        ),
    )

    private val surfaces = FOLLOW_CHECK_SURFACES.toTypedArray()
    private val builder = callee("Lfixture/Button;", "build", "Ljava/lang/Object;", FB_USER_SESSION, FOLLOW_BUTTON_KEY)
    private val check = callee("Lfixture/Check;", "offersFollow", "Z", FB_USER_SESSION, *surfaces)

    /** Every lookalike the rule has to pass over, each short of the check by one thing. */
    private val lookalikes = listOf(
        callee("Lfixture/Check;", "oneSurface", "Z", FB_USER_SESSION, surfaces.first()),
        callee("Lfixture/Check;", "sessionNotFirst", "Z", "Landroid/content/Context;", *surfaces),
        callee("Lfixture/Check;", "notABoolean", "Ljava/lang/String;", FB_USER_SESSION, *surfaces),
    )

    private fun resolver(vararg methods: Method): (MethodReference) -> Method? = { call ->
        methods.firstOrNull { it.definingClass == call.definingClass && it.name == call.name }
    }

    /**
     * The check the author row asks: static, a boolean, the session first and both surfaces named,
     * picked out of lookalikes that miss one of those each, wherever it sits among the row's calls.
     */
    @Test
    fun `the Follow check is the one the author row asks beside the button it builds`() {
        val resolve = resolver(builder, check, *lookalikes.toTypedArray())
        for (at in 0..lookalikes.size) {
            val calls = lookalikes.toMutableList<Method>().apply { add(at, check) } + builder
            val found = followCheck(authorCalling(*calls.toTypedArray()), resolve)
            assertNull(found.problem)
            assertEquals("the check at call $at", check, found.check)
        }
        // Asked twice, it's still one check.
        assertEquals(check, followCheck(authorCalling(check, builder, check), resolve).check)
    }

    /** 585 moved the check onto a singleton, called with invoke-virtual, and the row holds the button's key itself. */
    @Test
    fun `an instance Follow check called virtually is found, and the row may hold the button's key itself`() {
        val instance = callee("Lfixture/Singleton;", "offersFollow", "Z", FB_USER_SESSION, *surfaces, static = false)
        val resolve = resolver(instance, *lookalikes.toTypedArray())
        val buildsItself = ImmutableMethod(
            "Lfixture/Author;", "A17", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(
                        Opcode.CONST_STRING, 0,
                        com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference(FOLLOW_BUTTON_KEY),
                    ),
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc(
                        Opcode.INVOKE_VIRTUAL_RANGE, 1, 1, reference(instance),
                    ),
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )
        val found = followCheck(buildsItself, resolve)
        assertNull(found.problem)
        assertEquals(instance, found.check)
        // The key is still needed: without it (here or in a call) the row isn't the one that builds the button.
        val noKey = followCheck(authorCalling(instance), resolve)
        assertNull(noKey.check)
        assertTrue(noKey.problem!!, noKey.problem!!.contains(FOLLOW_BUTTON_KEY))
    }

    @Test
    fun `an author row that doesn't show one Follow check beside its button stops the patch with why`() {
        val second = callee("Lfixture/Check;", "alsoOffersFollow", "Z", FB_USER_SESSION, *surfaces)
        val resolve = resolver(builder, check, second, *lookalikes.toTypedArray())

        // The button built somewhere else: the check this row asks may not decide it any more.
        val noButton = followCheck(authorCalling(check), resolve)
        assertNull(noButton.check)
        assertTrue(noButton.problem!!, noButton.problem!!.contains(FOLLOW_BUTTON_KEY))
        // No check asked.
        val none = followCheck(authorCalling(builder, *lookalikes.toTypedArray()), resolve)
        assertNull(none.check)
        assertTrue(none.problem!!, none.problem!!.contains("0 Follow checks"))
        // Two: which one decides the button is a guess.
        val two = followCheck(authorCalling(check, builder, second), resolve)
        assertNull(two.check)
        assertTrue(two.problem!!, two.problem!!.contains("2 Follow checks"))
        // Calls the patch can't resolve count for nothing.
        assertNotNull(followCheck(authorCalling(builder, check)) { null }.problem)
    }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    /** The index a branch at [index] lands on. */
    private fun MutableMethod.target(index: Int): Int {
        val body = body()
        var address = 0
        val addresses = body.map { instruction -> address.also { address += instruction.codeUnits } }
        return addresses.indexOf(addresses[index] + (body[index] as OffsetInstruction).codeOffset)
    }

    private val Instruction.call get() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    /**
     * Every return gets the filter, one reached by a goto included, and the filter's answer goes in
     * a register of its own: null keeps the list, an array becomes Facebook's own ImmutableList.
     */
    @Test
    fun `every return of the chip list goes through the filter`() {
        val builder = method(
            "build", listOf("Ljava/util/List;", "Z"), IMMUTABLE_LIST, 4, static = true,
            """
                if-eqz p1, :empty
                invoke-static { p0 }, $IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST
                move-result-object v0
                goto :done
                :empty
                invoke-static { }, $IMMUTABLE_LIST->of()$IMMUTABLE_LIST
                move-result-object v0
                return-object v0
                :done
                return-object v0
            """,
        )
        builder.filterChipsBeforeEveryReturn()
        val body = builder.body()
        val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals(2, returns.size)
        for (at in returns) {
            val hook = at - 6
            assertEquals(FILTER_CHIPS, body[hook].call)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, body[hook + 1].opcode)
            val answer = (body[hook + 1] as OneRegisterInstruction).registerA
            assertTrue("the answer overwrote the list", answer != 0)
            assertEquals(Opcode.IF_EQZ, body[hook + 2].opcode)
            assertEquals("null keeps the list", hook + 5, builder.target(hook + 2))
            assertEquals(Opcode.NOP, body[hook + 5].opcode)
            assertEquals(COPY_OF, body[hook + 3].call)
            assertEquals(0, (body[hook + 4] as OneRegisterInstruction).registerA)
        }
        val goto = body.indexOfFirst { it.opcode == Opcode.GOTO }
        assertEquals("the goto skipped the filter", returns.last() - 6, builder.target(goto))
    }

    @Test
    fun `a chip list with no spare local stops the patch`() {
        val builder = method("build", listOf("Ljava/util/List;"), IMMUTABLE_LIST, 2, static = true,
            """
                invoke-static { p0 }, $IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST
                move-result-object v0
                return-object v0
            """)
        assertThrows(PatchException::class.java) { builder.filterChipsBeforeEveryReturn() }
        val none = method("build", listOf("Ljava/util/List;"), IMMUTABLE_LIST, 2, static = true, "throw p0")
        assertThrows(PatchException::class.java) { none.filterChipsBeforeEveryReturn() }
    }

    @Test
    fun `the Follow getter answers true first when the extension says so`() {
        val getter = method("A1c", emptyList(), "Z", 2, static = false,
            """
                const/4 v0, 0x0
                return v0
            """)
        getter.returnTrueWhen(HIDE_FOLLOWING_BUTTON)
        val body = getter.body()
        assertEquals(HIDE_FOLLOWING_BUTTON, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals("off runs Facebook's own getter", 5, getter.target(2))
        assertEquals(Opcode.CONST_4, body[3].opcode)
        assertEquals("the hook answers true", 1, (body[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[4].opcode)
        assertEquals(7, body.size)

        val noLocal = method("A1c", emptyList(), "Z", 1, static = false, "const/4 p0, 0x0\nreturn p0")
        assertThrows(PatchException::class.java) { noLocal.returnTrueWhen(HIDE_FOLLOWING_BUTTON) }
    }

    /**
     * The Follow check asks first and answers no when told to, in a local of its own: the session,
     * the other arguments and the check's own answer stay where Facebook put them.
     */
    @Test
    fun `the Follow check answers false first when the extension says so`() {
        val parameters = listOf(FB_USER_SESSION, "Ljava/lang/String;", "Z")
        val check = method("A0B", parameters, "Z", 5, static = true,
            """
                const/4 v0, 0x1
                return v0
            """)
        check.returnFalseWhen(HIDE_FOLLOW_BUTTON)
        val body = check.body()
        assertEquals(HIDE_FOLLOW_BUTTON, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals(0, (body[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, body[2].opcode)
        assertEquals("off runs Facebook's own check", 5, check.target(2))
        assertEquals(Opcode.CONST_4, body[3].opcode)
        assertEquals("the hook answers no", 0, (body[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[4].opcode)
        assertEquals(0, (body[4] as OneRegisterInstruction).registerA)
        assertEquals(7, body.size)

        // Every register a parameter: nothing is free to hold the extension's answer.
        val noLocal = method("A0B", parameters, "Z", 3, static = true, "return p2")
        assertThrows(PatchException::class.java) { noLocal.returnFalseWhen(HIDE_FOLLOW_BUTTON) }
    }

    private fun render(smali: String, name: String = RENDER) =
        method(name, listOf("Lfixture/Context;"), "Lfixture/Component;", 6, static = false, smali)

    /** A model's type, compared with the soundtrack's, and on to `:line` when it's the soundtrack. */
    private val pickSoundtrack = """
        invoke-interface { p1 }, Lfixture/Model;->getTypeName()Ljava/lang/String;
        move-result-object v1
        const-string v0, "$SOUNDTRACK_CHIP"
        invoke-virtual { v0, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :line
    """

    /**
     * The Reels tab's audio line row (#200) is the render that picks the soundtrack before it can
     * return and answers null first. Renders that return a component first, return before they
     * name the type, or aren't a render are the lookalikes 583 has. The hook asks first and answers
     * null, in a local of its own.
     */
    @Test
    fun `the audio line row is the render that answers null before anything else`() {
        val row = render("""
            const/16 v3, 0x0
            $pickSoundtrack
            return-object v3
            :line
            new-instance v2, Lfixture/Row;
            return-object v2
        """)
        assertTrue(isAudioLineRender(row))
        val builtFirst = render("""
            new-instance v3, Lfixture/Row;
            $pickSoundtrack
            return-object v3
            :line
            const/4 v2, 0x0
            return-object v2
        """)
        assertTrue("a render answering a built component first was taken", !isAudioLineRender(builtFirst))
        val returnsFirst = render("""
            const/4 v3, 0x0
            if-nez p1, :pick
            return-object v3
            :pick
            $pickSoundtrack
            return-object v3
            :line
            return-object v3
        """)
        assertTrue("a render returning before it names the type was taken", !isAudioLineRender(returnsFirst))
        val notRender = render("""
            const/16 v3, 0x0
            $pickSoundtrack
            return-object v3
            :line
            return-object p1
        """, name = "A1C")
        assertTrue("a method that isn't a render was taken", !isAudioLineRender(notRender))

        row.returnNullWhen(HIDE_AUDIO_LINE)
        val body = row.body()
        assertEquals(HIDE_AUDIO_LINE, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals(0, (body[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, body[2].opcode)
        assertEquals("off draws Facebook's row", 5, row.target(2))
        assertEquals(Opcode.CONST_4, body[3].opcode)
        assertEquals("the hook answers null", 0, (body[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN_OBJECT, body[4].opcode)
        assertEquals(0, (body[4] as OneRegisterInstruction).registerA)
        assertEquals(15, body.size)

        val noLocal = method(RENDER, listOf("Lfixture/Context;"), "Lfixture/Component;", 2, static = false, "return-object p1")
        assertThrows(PatchException::class.java) { noLocal.returnNullWhen(HIDE_AUDIO_LINE) }
    }

    /** A tree read of [key] into v0, on to `:none` when it's false. */
    private fun readFlag(key: Int) = """
        const v1, $key
        invoke-virtual { p1, v1 }, Lcom/facebook/graphservice/tree/TreeJNI;->getBooleanValue(I)Z
        move-result v0
        if-eqz v0, :none
    """

    /**
     * "Made with Edits" under a caption (#314) is the render that reads the video's Edits flag and
     * nothing else from the tree. A render that reads another flag beside it, a static helper reading
     * it, and a method that isn't a render are the other readers 585 has. The hook asks first and
     * answers null.
     */
    @Test
    fun `the Edits line is the render reading only the Edits flag`() {
        val tail = """
            new-instance v2, Lfixture/Row;
            return-object v2
            :none
            const/4 v2, 0x0
            return-object v2
        """
        val line = render(readFlag(MADE_WITH_EDITS) + tail)
        assertTrue(isEditsLabelRender(line))
        val alsoReadsAnother = render(readFlag(MADE_WITH_EDITS) + readFlag("can_viewer_comment".hashCode()) + tail)
        assertTrue("a render reading a second flag was taken", !isEditsLabelRender(alsoReadsAnother))
        val readsNoFlag = render("""
            new-instance v2, Lfixture/Row;
            return-object v2
        """)
        assertTrue("a render reading no flag was taken", !isEditsLabelRender(readsNoFlag))
        val helper = method(RENDER, listOf("Lfixture/Context;"), "Lfixture/Component;", 6, static = true,
            readFlag(MADE_WITH_EDITS).replace("p1", "p0") + tail)
        assertTrue("a static helper was taken", !isEditsLabelRender(helper))
        val notRender = render(readFlag(MADE_WITH_EDITS) + tail, name = "A1C")
        assertTrue("a method that isn't a render was taken", !isEditsLabelRender(notRender))
        val answersFlag = method(RENDER, listOf("Lfixture/Context;"), "Z", 6, static = false, """
            const v1, $MADE_WITH_EDITS
            invoke-virtual { p1, v1 }, Lcom/facebook/graphservice/tree/TreeJNI;->getBooleanValue(I)Z
            move-result v0
            return v0
        """)
        assertTrue("a method answering the flag itself was taken", !isEditsLabelRender(answersFlag))

        line.returnNullWhen(HIDE_EDITS_LABEL)
        val body = line.body()
        assertEquals(HIDE_EDITS_LABEL, body[0].call)
        assertEquals(Opcode.IF_EQZ, body[2].opcode)
        assertEquals("off draws Facebook's line", 5, line.target(2))
        assertEquals("the hook answers null", 0, (body[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN_OBJECT, body[4].opcode)
    }

    /** A TreeJNI tree class behind [flags] whose [name]()Z reads [key]. */
    private fun flagTree(flags: String, name: String, key: Int): ClassDef = ImmutableClassDef(
        "Lfixture/FlagTree;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf(flags), null, null, emptyList(),
        listOf(
            method(name, emptyList(), "Z", 2, static = false, """
                const v0, $key
                invoke-virtual { p0, v0 }, Lcom/facebook/graphservice/tree/TreeJNI;->getBooleanValue(I)Z
                move-result v0
                return v0
            """),
        ),
    )

    /**
     * 587 asks a tree model's interface for the Edits flag (arm64 LX/0DXO;->HdZ) instead of reading the
     * tree in the render. The TreeJNI class behind the interface names the getter, and the render asking
     * it is the line. A render asking another getter, one reading a second flag beside it and a static
     * helper asking it are not.
     */
    @Test
    fun `587's Edits line asks the tree model's getter for the flag`() {
        val flags = "Lfixture/Flags;"
        val getters = editsGetterCalls(flagTree(flags, "HdZ", MADE_WITH_EDITS))
        assertEquals(setOf("$flags->HdZ", "Lfixture/FlagTree;->HdZ"), getters)
        assertTrue("another flag's getter was taken",
            editsGetterCalls(flagTree(flags, "HdZ", "can_viewer_comment".hashCode())).isEmpty())
        val tail = """
            new-instance v2, Lfixture/Row;
            return-object v2
            :none
            const/4 v2, 0x0
            return-object v2
        """
        val asks = """
            invoke-interface { p1 }, $flags->HdZ()Z
            move-result v0
            if-eqz v0, :none
        """
        val line = render(asks + tail)
        assertTrue(isEditsLabelRender(line, getters))
        assertTrue("a render asking a getter no tree class named was taken", !isEditsLabelRender(line))
        val asksAnother = render(asks.replace("HdZ", "HdY") + tail)
        assertTrue("a render asking another getter was taken", !isEditsLabelRender(asksAnother, getters))
        val alsoReads = render(asks + readFlag("can_viewer_comment".hashCode()) + tail)
        assertTrue("a render reading a second flag was taken", !isEditsLabelRender(alsoReads, getters))
        val helper = method(RENDER, listOf("Lfixture/Context;"), "Lfixture/Component;", 6, static = true,
            asks.replace("p1", "p0") + tail)
        assertTrue("a static helper was taken", !isEditsLabelRender(helper, getters))
    }

    /** A reel header: p0 the row, p1 the search bar, p2 its send button, each wrapped and tagged when there is one. */
    private fun header(add: String = "Lfixture/Row;->add(Lfixture/Component;)V", sendTag: String = SEARCH_BAR_SEND) = method(
        "A00", listOf("Lfixture/Row;", "Lfixture/Component;", "Lfixture/Component;"), "Lfixture/Component;", 6, static = true, """
            if-eqz p1, :add_bar
            const-string v0, "$SEARCH_BAR"
            invoke-static { p1, v0 }, Lfixture/Wrap;->tag(Lfixture/Component;Ljava/lang/String;)Lfixture/Component;
            move-result-object p1
            :add_bar
            invoke-virtual { p0, p1 }, $add
            if-eqz p2, :add_send
            const-string v0, "$sendTag"
            invoke-static { p2, v0 }, Lfixture/Wrap;->tag(Lfixture/Component;Ljava/lang/String;)Lfixture/Component;
            move-result-object p2
            :add_send
            invoke-virtual { p0, p2 }, $add
            invoke-virtual { p0 }, Lfixture/Row;->build()Lfixture/Component;
            move-result-object v0
            return-object v0
        """,
    )

    /**
     * The search bar over a reel opened from a profile and its send button (#201): the header is the
     * builder holding both tags, each part's add is the first call after its tag that takes one
     * component and returns nothing, and the hook takes that call's place, so the jump Facebook makes
     * for a part it didn't wrap lands on the hook too, then the add follows with the answer.
     */
    @Test
    fun `the search bar's parts go through the extension on their way into the header`() {
        val row = header()
        assertTrue(isSearchBarHeader(row))
        assertTrue("a header without the send button's tag was taken", !isSearchBarHeader(header(sendTag = "fb_video_ghost_bar_sent")))
        assertEquals(4, partAddAfter(row, SEARCH_BAR))
        assertEquals(9, partAddAfter(row, SEARCH_BAR_SEND))
        assertNull("an add answering something was taken", partAddAfter(header(add = "Lfixture/Row;->add(Lfixture/Component;)Lfixture/Row;"), SEARCH_BAR))
        assertNull("an add taking something else was taken", partAddAfter(header(add = "Lfixture/Row;->add(Ljava/lang/Object;)V"), SEARCH_BAR))
        assertNull("a literal the header doesn't hold found an add", partAddAfter(row, "fb_video_ghost_bar_sent"))

        for (literal in listOf(SEARCH_BAR, SEARCH_BAR_SEND)) row.handPartFirst(partAddAfter(row, literal)!!)
        val body = row.body()
        assertEquals(19, body.size)
        for ((hook, part) in listOf(4 to 4, 12 to 5)) {
            assertEquals(SEARCH_BAR_PART, body[hook].call)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, body[hook + 1].opcode)
            assertEquals("the answer goes back in the part's register", part, (body[hook + 1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.CHECK_CAST, body[hook + 2].opcode)
            assertEquals("Lfixture/Row;->add(Lfixture/Component;)V", body[hook + 3].call)
        }
        assertEquals("a bar Facebook didn't build skips to the hook", 4, row.target(0))
        assertEquals("a button Facebook didn't build skips to the hook", 12, row.target(8))
    }

    @Test
    fun `a footer runnable returns first when the extension says so`() {
        val run = method("run", emptyList(), "V", 2, static = false,
            """
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                return-void
            """)
        run.returnVoidWhen(SKIP_HOT_COMMENT)
        val body = run.body()
        assertEquals(SKIP_HOT_COMMENT, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals("off runs Facebook's query", 4, run.target(2))
        assertEquals(Opcode.RETURN_VOID, body[3].opcode)
        assertEquals(Opcode.SGET_OBJECT, body[4].opcode)
    }

    /**
     * 585's hot comment runnable was Kotlin's `$1`; 586 has `$1` for the MFC query and `$2` for the
     * plain one, so the name is matched up to its number. The social bubbles name stays exact.
     */
    @Test
    fun `the hot comment runnable is matched with any number, the bubbles one exactly`() {
        assertTrue(footerRunnableNamed("${HOT_COMMENT_RUNNABLE}1", HOT_COMMENT_RUNNABLE))
        assertTrue(footerRunnableNamed("${HOT_COMMENT_RUNNABLE}2", HOT_COMMENT_RUNNABLE))
        assertTrue(footerRunnableNamed("${HOT_COMMENT_RUNNABLE}12", HOT_COMMENT_RUNNABLE))
        assertTrue("no number", !footerRunnableNamed(HOT_COMMENT_RUNNABLE, HOT_COMMENT_RUNNABLE))
        assertTrue("a nested lambda", !footerRunnableNamed("${HOT_COMMENT_RUNNABLE}1\$1", HOT_COMMENT_RUNNABLE))
        assertTrue("another helper", !footerRunnableNamed("FbShortsViewerFooterHotCommentHelper\$other\$1", HOT_COMMENT_RUNNABLE))
        assertTrue("no name", !footerRunnableNamed(null, HOT_COMMENT_RUNNABLE))
        assertTrue(footerRunnableNamed(SOCIAL_BUBBLES_RUNNABLE, SOCIAL_BUBBLES_RUNNABLE))
        assertTrue("the bubbles name is exact", !footerRunnableNamed(SOCIAL_BUBBLES_RUNNABLE.dropLast(1) + "2", SOCIAL_BUBBLES_RUNNABLE))
    }

    /** A class Redex named [redexName], a Runnable unless [runnable] is false, whose run() loads [loads]. */
    private fun namedRunnableClass(redexName: String, loads: String?, runnable: Boolean = true): ClassDef {
        val type = "Lfixture/Row;"
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val run = method("run", emptyList(), "V", 2, static = false, (loads?.let { "const-string v0, \"$it\"\n" } ?: "") + "return-void")
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", if (runnable) listOf("Ljava/lang/Runnable;") else null, null, null,
            listOf(ImmutableField(type, "__redex_internal_original_name", "Ljava/lang/String;", flags, ImmutableStringEncodedValue(redexName), null, null)),
            listOf(run),
        )
    }

    /**
     * #464: the caption row's comment runnable is the one of its numbered runnables loading either
     * comment query. A hot comment runnable needs only its name, since 587's `$2` hands a lambda the
     * query and loads none itself.
     */
    @Test
    fun `the caption row's comment runnable loads a comment query, a hot comment one needs only its name`() {
        assertNotNull(captionCommentRunnable(namedRunnableClass("${CAPTION_ROW_RUNNABLE}1", XAR_INLINE_COMMENTS_QUERY)))
        assertNotNull(captionCommentRunnable(namedRunnableClass("${CAPTION_ROW_RUNNABLE}1", INLINE_COMMENTS_QUERY)))
        assertNull("another of the row's runnables", captionCommentRunnable(namedRunnableClass("${CAPTION_ROW_RUNNABLE}2", null)))
        assertNull("another row's", captionCommentRunnable(namedRunnableClass("${HOT_COMMENT_RUNNABLE}1", XAR_INLINE_COMMENTS_QUERY)))
        assertNull("not a Runnable", captionCommentRunnable(namedRunnableClass("${CAPTION_ROW_RUNNABLE}1", INLINE_COMMENTS_QUERY, runnable = false)))
        assertNotNull("a hot comment runnable loading no query", namedRunnable(namedRunnableClass("${HOT_COMMENT_RUNNABLE}2", null), HOT_COMMENT_RUNNABLE))
        assertNull("a nested lambda", namedRunnable(namedRunnableClass("${HOT_COMMENT_RUNNABLE}2\$1", null), HOT_COMMENT_RUNNABLE))
    }

    private val lambdaType = "Lfixture/Lambda;"

    /** A static of the merged lambda that takes the lambda and its key and answers an Object. */
    private fun handOn(name: String, smali: String): Method = MutableMethod(
        ImmutableMethod(
            lambdaType, name, listOf(ImmutableMethodParameter(lambdaType, null, null), ImmutableMethodParameter("I", null, null)),
            "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(3, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    /**
     * A merged lambda shaped like 584's: `invoke()` and its first hand-on switch have no case for
     * the Follow flags' keys (0x29 and 0x2a), so each falls through two defaults before the last
     * switch names its method.
     */
    private fun lambda(): ClassDef {
        val invoke = MutableMethod(
            ImmutableMethod(
                lambdaType, "invoke", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    iget v0, p0, $lambdaType->key:I
                    packed-switch v0, :cases
                    invoke-static { p0, v0 }, $lambdaType->A1D(${lambdaType}I)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                    :one
                    invoke-static { p0 }, $lambdaType->A01($lambdaType)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                    :cases
                    .packed-switch 0x1
                        :one
                    .end packed-switch
                """,
            )
        }
        val first = handOn(
            "A1D",
            """
                sparse-switch p1, :cases
                invoke-static { p0, p1 }, $lambdaType->A1B(${lambdaType}I)Ljava/lang/Object;
                move-result-object v0
                return-object v0
                :five
                invoke-static { p0 }, $lambdaType->A05($lambdaType)Ljava/lang/Object;
                move-result-object v0
                return-object v0
                :cases
                .sparse-switch
                    0x5 -> :five
                .end sparse-switch
            """,
        )
        val second = handOn(
            "A1B",
            """
                packed-switch p1, :cases
                const/4 v0, 0x0
                return-object v0
                :following
                invoke-static { p0 }, $lambdaType->A0A($lambdaType)Ljava/lang/Object;
                move-result-object v0
                return-object v0
                :shorts
                invoke-static { p0 }, $lambdaType->A0h($lambdaType)Ljava/lang/Object;
                move-result-object v0
                return-object v0
                :cases
                .packed-switch 0x29
                    :following
                    :shorts
                .end packed-switch
            """,
        )
        return ImmutableClassDef(
            lambdaType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(invoke, first, second),
        )
    }

    @Test
    fun `a lambda key with no case is followed through each switch's default`() {
        val lambda = lambda()
        assertEquals("A0A", lambdaCase(lambda, 0x29)?.name)
        assertEquals("A0h", lambdaCase(lambda, 0x2a)?.name)
        assertEquals("a case of invoke() itself", "A01", lambdaCase(lambda, 1)?.name)
        assertEquals("a case of the first hand-on", "A05", lambdaCase(lambda, 5)?.name)
        assertNull("a key the last switch has no case for", lambdaCase(lambda, 0x30))
    }

    /** A static of the synthetic flag lambda with the lambda as its first parameter. */
    private fun lambdaStatic(name: String, parameters: List<String>, registers: Int, smali: String): Method = MutableMethod(
        ImmutableMethod(
            lambdaType, name, parameters.map { ImmutableMethodParameter(it, null, null) },
            "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    /**
     * A merged lambda shaped like 585's Follow flags: key 1 calls a static method that reads the flag
     * (the 584 form), key 3 has the read written into the second switch behind a holder lookup that
     * answers the holder, not an Object (585's first flag), key 4 is written the same way but reads
     * a flag of no config class, and key 5 has no case.
     */
    private fun flagLambda(): ClassDef {
        val invoke = MutableMethod(
            ImmutableMethod(
                lambdaType, "invoke", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    iget v0, p0, $lambdaType->key:I
                    packed-switch v0, :cases
                    invoke-static { p0, v0 }, $lambdaType->A1Z(${lambdaType}I)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                    :one
                    invoke-static { p0 }, $lambdaType->A01($lambdaType)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                    :cases
                    .packed-switch 0x1
                        :one
                    .end packed-switch
                """,
            )
        }
        val flagRead = lambdaStatic(
            "A01", listOf(lambdaType), 3,
            """
                invoke-static { p0 }, $lambdaType->holder($lambdaType)Lfixture/Holder;
                move-result-object v0
                invoke-static { v0 }, Lcom/facebook/mobileconfig/fixture/Flags;->read(Ljava/lang/Object;)Z
                move-result v0
                invoke-static { v0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object v0
                return-object v0
            """,
        )
        val written = lambdaStatic(
            "A1Z", listOf(lambdaType, "I"), 4,
            """
                packed-switch p1, :cases
                const/4 v0, 0x0
                return-object v0
                :flag
                invoke-static { p0 }, $lambdaType->holder($lambdaType)Lfixture/Holder;
                move-result-object v0
                invoke-static { v0 }, Lcom/facebook/mobileconfig/fixture/Flags;->read(Ljava/lang/Object;)Z
                move-result v0
                goto :box
                :other
                invoke-static { p0 }, $lambdaType->holder($lambdaType)Lfixture/Holder;
                move-result-object v0
                invoke-static { v0 }, Lfixture/Other;->read(Ljava/lang/Object;)Z
                move-result v0
                :box
                invoke-static { v0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object v0
                return-object v0
                :cases
                .packed-switch 0x3
                    :flag
                    :other
                .end packed-switch
            """,
        )
        return ImmutableClassDef(
            lambdaType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(invoke, flagRead, written),
        )
    }

    @Test
    fun `a flag read written into a lambda's switch is found, and a read of another kind is not`() {
        val lambda = flagLambda()
        assertEquals("a case that calls its own method", "A01", flagReader(lambda, 1)?.name)
        assertEquals("a case written into the second switch", "A1Z", flagReader(lambda, 3)?.name)
        assertNull("that case has no static method of its own", lambdaCase(lambda, 3))
        assertNull("a case that reads no config flag", flagReader(lambda, 4))
        assertNull("a key the switch has no case for", flagReader(lambda, 5))
        assertEquals("key", lambdaKeyField(lambda)?.name)
        assertNull("a lambda with no key read before its switch", lambdaKeyField(lambdaWithoutKey()))
    }

    private fun lambdaWithoutKey(): ClassDef {
        val invoke = MutableMethod(
            ImmutableMethod(
                lambdaType, "invoke", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, "const/4 v0, 0x0\nreturn-object v0") }
        return ImmutableClassDef(
            lambdaType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(invoke),
        )
    }

    /**
     * 585 has no method to hook for the first Follow flag, so the lambda's invoke() answers a boxed
     * true for the flag's key when the extension says so and runs Facebook's switch for any other
     * key. `this` is reached through a 16-bit move, so a lambda whose invoke() keeps it above v15
     * still gets a valid hook.
     */
    @Test
    fun `a lambda answers a boxed true for its key first when the extension says so`() {
        val key = requireNotNull(lambdaKeyField(flagLambda()))
        val invoke = method("invoke", emptyList(), "Ljava/lang/Object;", 20, static = false,
            """
                const/4 v0, 0x0
                return-object v0
            """)
        invoke.returnTrueObjectForKeyWhen(key, 155, HIDE_FOLLOWING_BUTTON)
        val body = invoke.body().filter { it.opcode != Opcode.NOP }
        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[0].opcode)
        assertEquals(Opcode.IGET, body[1].opcode)
        assertEquals("the key is compared as an int", 155, (body[2] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.IF_NE, body[3].opcode)
        assertEquals(HIDE_FOLLOWING_BUTTON, body[4].call)
        assertEquals(Opcode.MOVE_RESULT, body[5].opcode)
        assertEquals(Opcode.IF_EQZ, body[6].opcode)
        assertEquals(Opcode.SGET_OBJECT, body[7].opcode)
        assertEquals(Opcode.RETURN_OBJECT, body[8].opcode)
        assertEquals("another key runs Facebook's own switch", 9, invoke.target(3))
        assertEquals("off runs it too", 9, invoke.target(6))
        assertEquals(Opcode.CONST_4, invoke.body()[9].opcode)
        assertEquals("this is read from p0, v19 here", 19, (body[0] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerB)

        val noLocals = method("invoke", emptyList(), "Ljava/lang/Object;", 1, static = false, "const/4 p0, 0x0\nreturn-object p0")
        assertThrows(PatchException::class.java) { noLocals.returnTrueObjectForKeyWhen(key, 155, HIDE_FOLLOWING_BUTTON) }
    }

    private val loopFlag = com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference(
        "Lfixture/Params;", "shouldLoop", "Z",
    )

    private val loopFlagName = "Lfixture/Params;->shouldLoop:Z"

    /** An end-of-video step reading the loop flag with [read], then branching on it. */
    private fun videoComplete(read: String) = method(
        "complete", listOf("Lfixture/Params;"), "V", 8, static = true,
        """
            move-object v5, p0
            $read
            if-eqz v0, :done
            :done
            return-void
        """,
    )

    /**
     * Play reels once hands the extension the loop flag and the params from the registers they're in
     * right after the read. A read that lands the flag on the params' own register leaves no params
     * to hand over, and the hook would pass the flag as the params' object, which ART refuses when
     * it verifies Facebook's player class. That build is refused at patch time instead.
     */
    @Test
    fun `a loop flag read over the params' register stops Play reels once`() {
        val separate = loopReads(videoComplete("iget-boolean v0, v5, $loopFlagName"), loopFlag).single()
        assertEquals(listOf(0, 5), listOf(separate.answer, separate.params))
        assertNull("581's read, the flag in v0 and the params in v5", loopHookRefusal(separate))

        val over = loopReads(videoComplete("iget-boolean v5, v5, $loopFlagName\nmove v0, v5"), loopFlag).single()
        assertEquals(listOf(5, 5), listOf(over.answer, over.params))
        val refusal = loopHookRefusal(over)
        assertNotNull("the flag read over the params was hooked", refusal)
        assertTrue(refusal!!, refusal.contains("v5"))

        assertNotNull("the flag above v15", loopHookRefusal(LoopRead(1, 16, 5)))
        assertNotNull("the params above v15", loopHookRefusal(LoopRead(1, 0, 16)))
    }

    private val idleController = "Lfixture/Controller;"
    private val idleTimer = "Lfixture/Timer;"
    private val promptCallback = "Lkotlin/jvm/functions/Function0;"

    private fun instanceField(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

    /** A Runnable keeping one instance field per entry of [kept], with [run] as its run(). */
    private fun idleRunnableClass(run: String, kept: List<String> = listOf(idleTimer)): ClassDef = ImmutableClassDef(
        "Lfixture/IdleRunnable;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf("Ljava/lang/Runnable;"), null, null,
        kept.mapIndexed { at, type -> instanceField("Lfixture/IdleRunnable;", "kept$at", type) },
        listOf(method("run", emptyList(), "V", 3, static = false, run)),
    )

    /** 585 and 586: the runnable keeps the controller, sets its flag and calls its prompt method. */
    private fun controllerRun() = """
        iget-object v1, p0, Lfixture/IdleRunnable;->kept0:$idleController
        const/4 v0, 0x1
        iput-boolean v0, v1, $idleController->idle:Z
        iget-boolean v0, v1, $idleController->held:Z
        if-eqz v0, :done
        invoke-virtual { v1 }, $idleController->prompt()V
        :done
        return-void
    """

    /**
     * 587: the runnable keeps the controller's idle timer, sets the timer's flag and invokes the
     * Function0 the timer keeps for the prompt (LX/09mK;->run on arm64).
     */
    private fun timerRun(
        set: String = "0x1",
        load: String = "iget-object v0, v1, $idleTimer->show:$promptCallback",
        invoke: String = "invoke-interface { v0 }, $promptCallback->invoke()Ljava/lang/Object;",
        extra: String = "",
    ) = """
        iget-object v1, p0, Lfixture/IdleRunnable;->kept0:$idleTimer
        const/4 v0, $set
        iput-boolean v0, v1, $idleTimer->idle:Z
        iget-boolean v0, v1, $idleTimer->held:Z
        if-eqz v0, :done
        $load
        $invoke
        $extra
        :done
        return-void
    """

    @Test
    fun `the idle runnable is found on the controller and on the timer it keeps`() {
        val old = idleRunnable(idleRunnableClass(controllerRun(), listOf(idleController)), setOf(idleController))
        assertNotNull("586's runnable keeping the controller", old)
        assertEquals(listOf("$idleController->idle", "held", "$idleController->prompt()V"),
            listOf("${old!!.flag.definingClass}->${old.flag.name}", old.pending.name, described(old.prompt)))
        assertNull("586's prompt is the controller's own method", old.callback)

        val owners = setOf(idleController, idleTimer)
        val new = idleRunnable(idleRunnableClass(timerRun()), owners)
        assertNotNull("587's runnable keeping the timer", new)
        assertEquals(listOf("$idleTimer->idle", "held", "$promptCallback->invoke()Ljava/lang/Object;", "$idleTimer->show"),
            listOf("${new!!.flag.definingClass}->${new.flag.name}", new.pending.name, described(new.prompt),
                "${new.callback?.definingClass}->${new.callback?.name}"))
        // Positive control for the negatives below: the old shape still passes with both owners.
        assertNotNull(idleRunnable(idleRunnableClass(controllerRun(), listOf(idleController)), owners))
    }

    @Test
    fun `a runnable that isn't the idle runnable is refused`() {
        val owners = setOf(idleController, idleTimer)
        assertNull("a runnable keeping a class that keeps no idle flag",
            idleRunnable(idleRunnableClass(timerRun()), setOf(idleController)))
        assertNull("a runnable keeping two fields", idleRunnable(idleRunnableClass(timerRun(), listOf(idleTimer, idleTimer)), owners))
        assertNull("a runnable clearing the flag", idleRunnable(idleRunnableClass(timerRun(set = "0x0")), owners))
        assertNull("a prompt kept on another class",
            idleRunnable(idleRunnableClass(timerRun(load = "iget-object v0, v1, Lfixture/Other;->show:$promptCallback")), owners))
        assertNull("a prompt invoked from another register than the one loaded",
            idleRunnable(idleRunnableClass(timerRun(invoke = "invoke-interface { v1 }, $promptCallback->invoke()Ljava/lang/Object;")), owners))
        assertNull("a runnable with two prompts",
            idleRunnable(idleRunnableClass(timerRun(extra = "invoke-virtual { v1 }, $idleTimer->prompt()V")), owners))
    }

    /** A controller keeping the timer in `timer`, with one method taking the controller doing [check]. */
    private fun idleControllerClass(check: String, static: Boolean = true): ClassDef = ImmutableClassDef(
        idleController, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
        listOf(instanceField(idleController, "timer", idleTimer)),
        listOf(method("isIdle", listOf(idleController), "Z", 3, static, check)),
    )

    private val timerCheck = """
        iget-object v0, p0, $idleController->timer:$idleTimer
        iget-boolean v0, v0, $idleTimer->idle:Z
        return v0
    """

    @Test
    fun `the timer is an idle state owner only when the idle check reads its flag off it`() {
        assertEquals("587's check reading the timer, then its flag", setOf(idleController, idleTimer),
            idleStateOwners(idleControllerClass(timerCheck)))
        assertEquals("a check that isn't static", setOf(idleController), idleStateOwners(idleControllerClass(timerCheck, static = false)))
        assertEquals("a flag read off another register", setOf(idleController), idleStateOwners(idleControllerClass("""
            iget-object v0, p0, $idleController->timer:$idleTimer
            iget-boolean v0, v1, $idleTimer->idle:Z
            return v0
        """)))
        assertEquals("a field the controller doesn't keep", setOf(idleController), idleStateOwners(idleControllerClass("""
            iget-object v0, p0, $idleController->other:$idleTimer
            iget-boolean v0, v0, $idleTimer->idle:Z
            return v0
        """)))
        assertEquals("a flag of another class", setOf(idleController), idleStateOwners(idleControllerClass("""
            iget-object v0, p0, $idleController->timer:$idleTimer
            iget-boolean v0, v0, Lfixture/Other;->idle:Z
            return v0
        """)))
    }
}
