/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.backgroundplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes Keep playing in the background finds its hooks by, each beside the near miss it must
 * not take, and the handler check's hook run on a small check made in the same shape as Facebook's.
 */
class BackgroundPlayShapesTest {
    private val trigger = "Lfixture/Trigger;"
    private val type = "Lfixture/Type;"
    private val player = "Lfixture/Player;"
    private val registry = "Lfixture/Registry;"
    private val record = "Lfixture/Record;"
    private val starter = "Lfixture/Starter;"
    private val handlerInterface = "Lfixture/Handler;"
    private val handlerType = "Lfixture/Fullscreen;"
    private val recorder = "$registry->record($type$trigger$PLAYER_ORIGIN${"Ljava/lang/Object;"}${STRING}ZZ)V"
    private val start = "$starter->start($CONTEXT$type$PLAYER_ORIGIN$STRING$STRING${STRING}ZZZ)V"

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        body: String,
        registers: Int = 4,
        flags: Int = AccessFlags.PUBLIC.value,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun classDef(
        classType: String,
        vararg methods: Method,
        interfaces: List<String> = emptyList(),
        fields: List<ImmutableField> = emptyList(),
    ): ClassDef = ImmutableClassDef(classType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", interfaces, null, null,
        fields, methods.map(ImmutableMethod::of))

    private fun field(owner: String, name: String, fieldType: String, static: Boolean = false) = ImmutableField(
        owner, name, fieldType, AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null, null,
    )

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.calls(descriptor: String) = call?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == descriptor
    } == true

    // The pause helper and its recording of the pause.

    private fun helper(extraWrite: Boolean = false, getterOwner: String = player, owner: String = player) = method(
        owner, "helper", listOf(trigger, player, "Ljava/lang/Object;"), "V",
        """
            invoke-virtual {p1}, $getterOwner->video()Ljava/lang/String;
            move-result-object v10
            ${if (extraWrite) "const/4 v10, 0x0" else "nop"}
            const/4 v5, 0x0
            const/4 v6, 0x0
            move-object v7, p0
            const/4 v8, 0x0
            const/4 v9, 0x0
            const/4 v11, 0x0
            const/4 v12, 0x0
            invoke-virtual/range {v5 .. v12}, $recorder
            return-void
        """,
        registers = 16,
        flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    )

    private fun innerPause(calls: List<String> = listOf("helper"), owner: String = player) = method(
        player, "pauseFor", listOf(trigger, "Ljava/lang/Object;"), "V",
        calls.joinToString("\n") { "invoke-static {p1, p0, p2}, $owner->$it($trigger$player${"Ljava/lang/Object;"})V" } +
            "\nreturn-void",
    )

    @Test
    fun `the pause helper, its recording and the video id accessor`() {
        val groot = classDef(player, innerPause(), helper())
        val pause = groot.methods.single { it.name == "pauseFor" }
        val found = pauseHelper(groot, pause, trigger) { null }
        assertEquals("helper", found?.name)
        assertNull("two helpers", pauseHelper(
            classDef(player, innerPause(listOf("helper", "other")), helper(),
                method(player, "other", listOf(trigger, player, "Ljava/lang/Object;"), "V", "return-void",
                    flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)),
            innerPause(listOf("helper", "other")), trigger) { null })

        // 587 keeps the helper in a class of static helpers beside the player.
        val helpers = classDef(registry, helper(owner = registry))
        val outlined = classDef(player, innerPause(owner = registry))
        val away = pauseHelper(outlined, outlined.methods.single(), trigger) { if (it == registry) helpers else null }
        assertEquals(registry, away?.definingClass)
        assertEquals("video", videoIdAccessor(away!!, recorderCalls(away, trigger).single(), player))
        assertNull("a helper class that isn't there", pauseHelper(outlined, outlined.methods.single(), trigger) { null })
        val unrelated = classDef(registry, method(registry, "unrelated", listOf(trigger, player, "Ljava/lang/Object;"), "V",
            "return-void", flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value))
        assertNull("a helper class without the helper", pauseHelper(outlined, outlined.methods.single(), trigger) { unrelated })

        val calls = recorderCalls(found!!, trigger)
        assertEquals(1, calls.size)
        assertEquals("video", videoIdAccessor(found, calls.single(), player))
        val twice = helper(extraWrite = true)
        assertNull("the id register written twice", videoIdAccessor(twice, recorderCalls(twice, trigger).single(), player))
        val elsewhere = helper(getterOwner = registry)
        assertNull("an accessor of another class", videoIdAccessor(elsewhere, recorderCalls(elsewhere, trigger).single(), player))

        val recorderReference = ImmutableMethod(registry, "record",
            listOf(type, trigger, PLAYER_ORIGIN, "Ljava/lang/Object;", STRING, "Z", "Z").map { ImmutableMethodParameter(it, null, null) },
            "V", 0, null, null, null)
        assertTrue(isRecorder(recorderReference, trigger))
        assertFalse("another trigger", isRecorder(recorderReference, type))
        val noId = ImmutableMethod(registry, "record",
            listOf(type, trigger, PLAYER_ORIGIN, "Ljava/lang/Object;", "I", "Z", "Z").map { ImmutableMethodParameter(it, null, null) },
            "V", 0, null, null, null)
        assertFalse("no String video id", isRecorder(noId, trigger))
    }

    private fun outerPause(name: String = "pause", flags: Int = AccessFlags.PUBLIC.value, handsOn: Boolean = true) = method(
        player, name, listOf(trigger), "V",
        (if (handsOn) "const/4 v0, 0x0\ninvoke-virtual {p0, p1, v0}, $player->pauseFor($trigger${"Ljava/lang/Object;"})V\n" else "") +
            "return-void",
    )

    @Test
    fun `the window button's pause is the public one taking only the trigger that hands on to the inner one`() {
        val inner = innerPause()
        val found = triggerPause(listOf(inner, outerPause()), inner, trigger)
        assertEquals("pause", found?.name)
        assertNull("only the inner pause", triggerPause(listOf(inner), inner, trigger))
        assertNull("a private one", triggerPause(listOf(inner, outerPause(flags = AccessFlags.PRIVATE.value)), inner, trigger))
        assertNull("one that doesn't hand on", triggerPause(listOf(inner, outerPause(handsOn = false)), inner, trigger))
        assertNull("two of them", triggerPause(listOf(inner, outerPause(), outerPause("pauseToo")), inner, trigger))
        val wider = method(player, "pauseWith", listOf(trigger, "Z"), "V",
            "const/4 v0, 0x0\ninvoke-virtual {p0, p1, v0}, $player->pauseFor($trigger${"Ljava/lang/Object;"})V\nreturn-void")
        assertEquals("one that takes more than the trigger is passed over", "pause",
            triggerPause(listOf(inner, wider, outerPause()), inner, trigger)?.name)
    }

    // The manager and its handler.

    private fun stops(trace: String = MANAGER_ON_BACKGROUND, asks: Int = 1) = method(
        MANAGER, "onActivityStopped", listOf(ACTIVITY), "V",
        listOf("const-string v0, \"$trace\"", "const/4 v1, 0x0", "const/4 v2, 0x0").plus(
            (1..asks).map { "invoke-interface {v1, v0, v2}, $handlerInterface->ask$it($SESSION$record)Z" },
        ).plus("return-void").joinToString("\n"),
    )

    private fun manager(vararg methods: Method = arrayOf(stops()), lists: Int = 1) = classDef(
        MANAGER,
        method(MANAGER, "<init>", emptyList(), "V", "return-void"),
        *methods,
        fields = (1..lists).map { field(MANAGER, "handlers$it", LIST) } + field(MANAGER, "started", "Z"),
    )

    private fun handler(tag: String = FULLSCREEN_HANDLER, implements: Boolean = true) = classDef(
        handlerType,
        method(handlerType, "<init>", emptyList(), "V", "return-void"),
        method(handlerType, "getTag", emptyList(), STRING, "const-string v0, \"$tag\"\nreturn-object v0"),
        interfaces = if (implements) listOf(handlerInterface) else emptyList(),
    )

    @Test
    fun `the manager, its question and the fullscreen handler`() {
        assertTrue(isBackgroundManager(manager()))
        assertFalse("two lists", isBackgroundManager(manager(lists = 2)))
        assertFalse("no trace", isBackgroundManager(manager(stops(trace = "Something.else"))))
        assertFalse("another class", isBackgroundManager(classDef("Lfixture/Manager;", stops())))
        assertEquals(1, handlerQuestions(managerStops(manager())!!).size)
        assertEquals(2, handlerQuestions(managerStops(manager(stops(asks = 2)))!!).size)

        assertTrue(isFullscreenHandler(handler(), handlerInterface))
        assertFalse("another tag", isFullscreenHandler(handler(tag = "WorkBackgroundPlaybackHandler"), handlerInterface))
        assertFalse("not a handler", isFullscreenHandler(handler(implements = false), handlerInterface))
        assertEquals("fixture.Fullscreen", binaryName(handlerType))
    }

    // The notification's starter and its server flag check.

    private fun starterClass(withProduct: Boolean = true, gates: Int = 1, asks: Boolean = true) = classDef(
        starter,
        method(
            starter, "start", listOf(CONTEXT, type, PLAYER_ORIGIN, STRING, STRING, STRING, "Z", "Z", "Z"), "V",
            listOfNotNull(
                if (asks) "invoke-direct {p0, p9}, $starter->gate0(Z)Z" else null,
                "const-class v0, $NOTIFICATION_SERVICE",
                if (withProduct) "const-string v0, \"$AUDIO_PRODUCT\"" else null,
                "return-void",
            ).joinToString("\n"),
            registers = 12,
        ),
        *(0 until gates).map {
            method(starter, "gate$it", listOf("Z"), "Z", "return p1", registers = 2, flags = AccessFlags.PRIVATE.value)
        }.toTypedArray(),
    )

    @Test
    fun `the notification's server flag check`() {
        val good = starterClass()
        val start = good.methods.single { it.name == "start" }
        assertEquals("gate0", notificationGate(good, start)?.name)
        listOf(starterClass(withProduct = false), starterClass(gates = 2), starterClass(asks = false)).forEach { miss ->
            assertNull(notificationGate(miss, miss.methods.single { it.name == "start" }))
        }
    }

    // The handler's check.

    private val checkBody = """
        const/4 v13, 0x0
        const/4 v3, 0x1
        invoke-static {}, Lfixture/Sound;->on()Z
        move-result v0
        if-eqz v0, :no
        move-object/from16 v6, p2
        iget-boolean v0, v6, $record->paused:Z
        if-nez v0, :no
        iget-object v4, v6, $record->params:Ljava/lang/Object;
        invoke-static {v4}, Lfixture/Eligible;->check(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :no
        iget-wide v7, v6, $record->at:J
        const-wide/16 v9, 0x3e8
        cmp-long v0, v7, v9
        if-gtz v0, :no
        iget-object v1, v6, $record->trigger:$trigger
        sget-object v0, $trigger->BY_USER:$trigger
        if-eq v1, v0, :no
        iget-object v1, v6, $record->type:$type
        sget-object v0, $type->FULL_SCREEN_PLAYER:$type
        if-ne v1, v0, :no
        invoke-static {v4}, Lfixture/Story;->of(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        if-eqz v0, :no
        invoke-static {v0}, Lfixture/Story;->media(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v1
        if-eqz v1, :no
        invoke-static {v1}, Lfixture/Story;->id(Ljava/lang/Object;)Ljava/lang/String;
        move-result-object v0
        if-eqz v0, :no
        invoke-static {v1}, Lfixture/Story;->title(Ljava/lang/Object;)Ljava/lang/String;
        move-result-object v9
        const/4 v10, 0x0
        invoke-static {v1}, Lfixture/Story;->owner(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        if-eqz v0, :named
        invoke-static {v0}, Lfixture/Story;->name(Ljava/lang/Object;)Ljava/lang/String;
        move-result-object v10
        :named
        if-eqz v9, :no
        if-eqz v10, :no
        EXTRA
        const/4 v5, 0x0
        const/4 v6, 0x0
        const/4 v7, 0x0
        const/4 v8, 0x0
        const/4 v11, 0x0
        const/4 v12, 0x0
        invoke-virtual/range {v5 .. v14}, STARTER
        return v3
        :no
        return v13
    """

    private val staticSound = """
        invoke-static {}, Lfixture/Sound;->on()Z
        move-result v0
        if-eqz v0, :no
    """.trimIndent()

    /** 587's sound check: a boolean field of the settings object a lazy getter answers. */
    private val fieldSound = """
        invoke-static {}, Lfixture/Lazy;->get()Ljava/lang/Object;
        move-result-object v0
        check-cast v0, Lfixture/Settings;
        iget-boolean v0, v0, Lfixture/Settings;->sound:Z
        if-eqz v0, :no
    """.trimIndent()

    private fun check(extra: String = "nop", starterCall: String = start, dropTitle: Boolean = false, sound: String? = null) = method(
        handlerType, "check", listOf(SESSION, record), "Z",
        checkBody.replace("EXTRA", extra).replace("STARTER", starterCall)
            .let { if (dropTitle) it.replace("        if-eqz v9, :no\n", "") else it }
            .let { body -> sound?.let { body.replace(staticSound.prependIndent("        "), it) } ?: body },
        registers = 17,
    )

    @Test
    fun `the check's ways to its no are read by kind, and one it doesn't know stops the patch`() {
        val read = readHandlerCheck(check(), record)
        assertEquals(EXPECTED_CHECKS, CheckKind.values().associateWith { kind -> read.branches.values.count { it == kind } })
        assertEquals(9, read.titleRegister)
        assertEquals(10, read.subtitleRegister)
        assertEquals("start", read.starter.name)

        val fromField = check(sound = fieldSound)
        assertTrue("the 587 form replaced the static call",
            fromField.implementation!!.instructions.any { it.calls("Lfixture/Lazy;->get()Ljava/lang/Object;") })
        val field = readHandlerCheck(fromField, record)
        assertEquals("587 reads the sound from a field", EXPECTED_CHECKS,
            CheckKind.values().associateWith { kind -> field.branches.values.count { it == kind } })
        assertEquals(CheckKind.ASKS, field.branches.values.first())
        assertThrows("the field's no when the sound is on", IllegalStateException::class.java) {
            readHandlerCheck(check(sound = fieldSound.replace("if-eqz v0, :no", "if-nez v0, :no")), record)
        }

        assertThrows("an unknown way to its no", IllegalStateException::class.java) {
            readHandlerCheck(check(extra = "if-nez v3, :no"), record)
        }
        assertThrows("a title check gone", IllegalStateException::class.java) {
            readHandlerCheck(check(dropTitle = true), record)
        }
        assertThrows("no starter", IllegalStateException::class.java) {
            readHandlerCheck(check(starterCall = "$starter->other($CONTEXT$type$PLAYER_ORIGIN$STRING${STRING}ZZZ)V"), record)
        }
    }

    @Test
    fun `the check's hook decides first, skips four checks, names the notification and tells each answer`() {
        val original = check()
        val read = readHandlerCheck(original, record)
        val handler = Handler(handler(), original, original, read, "video", "trigger", record, trigger, original)
        val hooked = check()
        hookCheck(hooked, handler)
        val code = hooked.implementation!!.instructions.toList()

        assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16,
            Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN), code.take(9).map { it.opcode })
        assertTrue(code[4].calls(DECIDING))
        assertEquals(Opcode.CONST_4, code[9].opcode)

        val flow = ControlFlow.of(hooked)
        val liveness = RegisterLiveness.of(hooked)
        val skips = code.indices.filter { code[it].calls(SKIP_CHECK) }
        assertEquals(4, skips.size)
        skips.forEach { at ->
            val free = (code[at + 1] as OneRegisterInstruction).registerA
            val branch = at + 3
            assertFalse("the skip writes a register its branch reads", free in code[branch].namedRegisters())
            assertFalse("the skip writes a register read after the branch", free in liveness.liveInto(branch + 1))
            assertEquals("a skip lands past its branch", branch + 1, flow.normal[at + 2].first())
        }
        assertEquals(9, code.single { it.calls(TITLE) }.namedRegisters().single())
        assertEquals(10, code.single { it.calls(SUBTITLE) }.namedRegisters().single())
        // The owner's own null check, which lands on the title's check, now lands on the title's call.
        val ownerSkip = code.indices.single { code[it].opcode == Opcode.IF_EQZ && flow.normal[it].first().let { to -> code[to].calls(TITLE) } }
        assertTrue(ownerSkip < code.indices.single { code[it].calls(TITLE) })

        val told = code.indices.filter { code[it].calls(ANSWERED) }
        assertEquals(2, told.size)
        told.forEach { assertEquals(Opcode.RETURN, code[it + 1].opcode) }
        val ifs = setOf(Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_GTZ)
        val ways = code.indices.filter { at -> code[at].opcode in ifs && flow.normal[at].first() in told }
        assertEquals("every way to the no lands on the told answer", read.branches.size, ways.size)

        // 587's sound field gets its skip the same way, between the read and its branch.
        val fieldOriginal = check(sound = fieldSound)
        val fieldHooked = check(sound = fieldSound)
        hookCheck(fieldHooked, Handler(handler(), fieldOriginal, fieldOriginal, readHandlerCheck(fieldOriginal, record), "video",
            "trigger", record, trigger, fieldOriginal))
        val fieldCode = fieldHooked.implementation!!.instructions.toList()
        assertEquals(4, fieldCode.count { it.calls(SKIP_CHECK) })
        val fieldSkip = fieldCode.indexOfFirst { it.calls(SKIP_CHECK) }
        assertEquals(Opcode.IGET_BOOLEAN, fieldCode[fieldSkip - 1].opcode)
        assertEquals(Opcode.IF_EQZ, fieldCode[fieldSkip + 3].opcode)
        assertFalse("the skip writes the field's register",
            (fieldCode[fieldSkip + 1] as OneRegisterInstruction).registerA in fieldCode[fieldSkip + 3].namedRegisters())
    }

    @Test
    fun `the return tells the extension first and before each return`() {
        val back = method(handlerType, "back", listOf(ACTIVITY, SESSION, record), "V",
            """
                if-eqz p1, :done
                return-void
                :done
                return-void
            """, registers = 5)
        hookReturn(back)
        val code = back.implementation!!.instructions.toList()
        assertTrue(code.first().calls(RETURNING))
        assertEquals(listOf(1), code.first().namedRegisters())
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertEquals(2, returns.size)
        returns.forEach { assertTrue(code[it - 1].calls(RETURNED)) }
        val flow = ControlFlow.of(back)
        assertTrue("the branch lands on the told return", code[flow.normal[1].first()].calls(RETURNED))
    }

    private fun windowBuilder(
        parameters: List<String> = listOf("Z"),
        returns: String = LIST,
        action: String = WINDOW_SKIP_BACK,
        builds: String = REMOTE_ACTION,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ) = method("Lfixture/Window;", "actions", parameters, returns,
        """
            const-string v0, "$action"
            new-instance v1, $builds
            const/4 v0, 0x0
            return-object v0
        """, flags = flags)

    @Test
    fun `the window's buttons are found by their rewind action and RemoteAction, beside near misses`() {
        assertTrue(isWindowActionBuilder(windowBuilder()))
        listOf(
            windowBuilder(parameters = emptyList()),
            windowBuilder(returns = "Ljava/lang/Object;"),
            windowBuilder(action = "com.facebook.video.reels.pip.ACTION_SKIP_FORWARD"),
            windowBuilder(builds = "Landroid/app/PictureInPictureParams;"),
            windowBuilder(flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value),
        ).forEach { assertFalse(isWindowActionBuilder(it)) }
    }
}
