/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.media.reelspeed.PLAYER_ORIGIN
import app.morphe.patches.facebook.media.resume.GROOT_RELEASE
import app.morphe.patches.facebook.media.resume.GROOT_SAFE_SEEK
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.reels.hold.ATTACH_VIEW_LOG
import app.morphe.patches.facebook.reels.hold.VIEW_GROUP
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes Tap to play finds its hooks by, each beside the near miss it must not take, and the
 * patch run on a small build made of them: what it puts first in each method, in which registers,
 * and what stops it.
 */
class TapToPlayShapesTest {
    private val trigger = "Lfixture/Trigger;"
    private val setting = "Lfixture/Autoplay;"
    private val player = "Lfixture/Player;"
    private val playingFace = "Lfixture/Playing;"
    private val controller = "Lfixture/Controller;"
    private val checker = "Lfixture/Checker;"
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val controls = "Lfixture/ReelControls;"
    private val component = "Lfixture/ControlComponent;"
    private val config = "Lfixture/Config;"
    private val playbackListener = "Lfixture/ReelListener;"
    private val playbackState = "Lfixture/PlaybackState;"
    private val reelControl = "Lfixture/ReelControl;"
    private val reelController = "Lfixture/ReelController;"

    private fun playbackClasses(compare: String = "if-ne", resetValue: Int = 0): List<ClassDef> {
        val playing = method(playbackState, "<clinit>", emptyList(), "V", """
            new-instance v0, $playbackState
            const-string v1, "PLAYING"
            const/4 v2, 0x0
            invoke-direct {v0, v1, v2}, $playbackState-><init>(Ljava/lang/String;I)V
            sput-object v0, $playbackState->playing:$playbackState
            ${(PLAYBACK_STATE_NAMES - "PLAYING").joinToString("\n") { "const-string v0, \"$it\"" }}
            return-void
        """, static = true)
        val stateField = ImmutableField(reelController, "state", reelControl, AccessFlags.PUBLIC.value, null, null, null)
        val reset = method(reelController, "clear", listOf(trigger, "Z"), "V", """
            iget-object v0, p0, $reelController->state:$reelControl
            const/4 v1, $resetValue
            iput-object v1, p0, $reelController->state:$reelControl
            return-void
        """, registers = 6)
        val controllerClass = ImmutableClassDef(reelController, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            null, null, null, listOf(stateField), listOf(ImmutableMethod.of(reset)))
        val listener = method(playbackListener, "onState", listOf("Lfixture/PlaybackEvent;"), "V", """
            iget-object v5, p1, Lfixture/PlaybackEvent;->state:$playbackState
            sget-object v0, $playbackState->playing:$playbackState
            $compare v5, v0, :done
            const/4 v3, 0x0
            const/4 v4, 0x0
            const/4 v6, 0x0
            const/4 v0, 0x0
            if-nez v6, :clear
            if-eqz v0, :done
            :clear
            iget-object v0, p0, $playbackListener->controller:$reelController
            invoke-virtual {v0, v4, v3}, $reelController->clear(${trigger}Z)V
            :done
            const-string v0, "$REELS_PLAYBACK_STARTED"
            return-void
        """, registers = 9)
        return listOf(classDef(playbackState, "Ljava/lang/Enum;", playing), enumNaming(reelControl, REEL_CONTROL_NAMES),
            controllerClass, classDef(playbackListener, "Ljava/lang/Object;", listener))
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        body: String,
        registers: Int = 4,
        static: Boolean = false,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun classDef(type: String, superclass: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.map(ImmutableMethod::of))

    private fun holding(string: String) = """
        const-string v0, "$string"
        return-void
    """

    private fun enumNaming(type: String, names: List<String>, seekable: Boolean = true): ClassDef {
        val user = type == trigger && "BY_USER" in names
        val seeker = user && seekable
        val body = names.joinToString("\n") { "const-string v0, \"$it\"" } + if (user) """

            new-instance v0, $type
            const-string v1, "BY_USER"
            const/4 v2, 0x0
            invoke-direct {v0, v1, v2}, $type-><init>(Ljava/lang/String;I)V
            sput-object v0, $type->user:$type
            sput-object v0, $type->alias:$type
            ${if (seeker) """
                new-instance v0, $type
                const-string v1, "BY_INITIAL_FRAME_PERFECT_SEEK"
                const/4 v2, 0x1
                invoke-direct {v0, v1, v2}, $type-><init>(Ljava/lang/String;I)V
                sput-object v0, $type->seeker:$type
            """ else ""}
            return-void
        """ else "\nreturn-void"
        // Like 584, the constant is also stored in a plain static alias that isn't an enum entry.
        val fields = if (user) listOf(
            ImmutableField(type, "user", type,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.ENUM.value, null, null, null),
            ImmutableField(type, "alias", type,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null, null),
        ) + (if (seeker) listOf(ImmutableField(type, "seeker", type,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.ENUM.value, null, null, null)) else emptyList())
        else emptyList()
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Enum;", null, null, null, fields,
            listOf(ImmutableMethod.of(method(type, "<clinit>", emptyList(), "V", body, registers = 3, static = true))))
    }

    private fun inlinePlayer(base: ClassDef): ClassDef {
        val field = ImmutableField(player, "container", VIEW_GROUP, AccessFlags.PUBLIC.value, null, null, null)
        val extra = listOf(
            method(player, "isPlaying", emptyList(), "Z", "const/4 v0, 0x0\nreturn v0"),
            method(player, "position", emptyList(), "I", "const/4 v0, 0x0\nreturn v0"),
            method(player, "length", emptyList(), "I", "const/4 v0, 0x0\nreturn v0"),
            method(player, "remaining", emptyList(), "J", """
                invoke-virtual {p0}, $player->length()I
                move-result v0
                invoke-virtual {p0}, $player->params()$VIDEO_PLAYER_PARAMS
                move-result-object v1
                iget v2, v1, $VIDEO_PLAYER_PARAMS->videoDurationMs:I
                invoke-virtual {p0}, $player->position()I
                move-result v1
                sub-int/2addr v0, v1
                int-to-long v0, v0
                return-wide v0
            """),
            method(player, "safeSeek", listOf(trigger, player, "I", "Z"), "V", holding(GROOT_SAFE_SEEK), static = true),
            method(player, "seekWith", listOf(trigger, "I", "Z"), "V", """
                invoke-static {p1, p0, p2, p3}, $player->safeSeek($trigger${player}IZ)V
                return-void
            """),
            method(player, "seekTo", listOf(trigger, "I"), "V", """
                const/4 v0, 0x0
                invoke-virtual {p0, p1, p2, v0}, $player->seekWith(${trigger}IZ)V
                return-void
            """),
            method(player, "params", emptyList(), VIDEO_PLAYER_PARAMS, "const/4 v0, 0x0\nreturn-object v0"),
            method(player, "origin", emptyList(), PLAYER_ORIGIN, "const/4 v0, 0x0\nreturn-object v0"),
            method(player, "release", listOf(trigger, "Ljava/lang/String;"), "V", holding(GROOT_RELEASE)),
            method(player, "attach", listOf(VIEW_GROUP, "Ljava/lang/String;", player), "V", """
                const-string v0, "$ATTACH_VIEW_LOG"
                iput-object p0, p2, $player->container:$VIEW_GROUP
                return-void
            """, registers = 6, static = true),
        )
        return ImmutableClassDef(player, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf(playingFace), null, null,
            listOf(field), (base.methods.toList() + extra).map(ImmutableMethod::of))
    }

    /** The interface declaring isPlaying(), whose one int method names the player's position reader. */
    private fun playingInterface(): ClassDef = ImmutableClassDef(
        playingFace, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
        "Ljava/lang/Object;", null, null, null, null,
        listOf("isPlaying" to "Z", "position" to "I").map { (name, returns) ->
            ImmutableMethod(playingFace, name, emptyList<ImmutableMethodParameter>(), returns,
                AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
        },
    )

    private fun inlineParams(): ClassDef {
        val fields = (INLINE_FLAGS.keys.associateWith { "Z" } + ("videoId" to "Ljava/lang/String;") + ("videoDurationMs" to "I"))
        val dump = fields.entries.joinToString("\n") { (name, type) -> """
            const-string v0, "$name"
            ${when (type) { "Z" -> "iget-boolean"; "I" -> "iget"; else -> "iget-object" }} v1, p0, $VIDEO_PLAYER_PARAMS->$name:$type
            invoke-static {v0, v1}, Lfixture/Reporter;->report(Ljava/lang/String;$type)V
        """ } + "\nreturn-void"
        return ImmutableClassDef(VIDEO_PLAYER_PARAMS, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, type) -> ImmutableField(VIDEO_PLAYER_PARAMS, name, type, AccessFlags.PUBLIC.value, null, null, null) },
            listOf(ImmutableMethod.of(method(VIDEO_PLAYER_PARAMS, "dump", emptyList(), "V", dump))))
    }

    private fun play(owner: String = player, registers: Int = 20) =
        method(owner, "play", listOf(trigger), "V", holding(GROOT_PLAY), registers)

    private fun innerPause() = method(player, "pauseFor", listOf(trigger, "Ljava/lang/Object;"), "V", holding(GROOT_PAUSE))

    private fun outerPause() = method(
        player, "pause", listOf(trigger), "V",
        """
            const-string v0, "$GROOT_PAUSE"
            const/4 v1, 0x0
            invoke-virtual {p0, p1, v1}, $player->pauseFor(${trigger}Ljava/lang/Object;)V
            return-void
        """,
    )

    private fun bind() = method(player, "bind", listOf("Ljava/lang/Object;"), "V", holding(GROOT_BIND))

    private fun groot(vararg methods: Method = arrayOf(play(), outerPause(), innerPause(), bind())) =
        classDef(player, "Ljava/lang/Object;", *methods)

    private fun legacy() = classDef(
        controller, "Ljava/lang/Object;",
        method(controller, "play", listOf(session, trigger), "V", holding(LEGACY_PLAY), registers = 17),
        method(controller, "pause", listOf(trigger), "V", holding(LEGACY_PAUSE)),
    )

    private fun settingsChecker(readerReturns: String = setting) = classDef(
        checker, "Ljava/lang/Object;",
        method(checker, "<init>", emptyList(), "V", holding(AUTOPLAY_SETTINGS_CHECKER)),
        method(
            checker, "read", emptyList(), readerReturns,
            """
                sget-object v0, $readerReturns->chosen:$readerReturns
                if-nez v0, :answer
                sget-object v2, $readerReturns->fallback:$readerReturns
                return-object v2
                :answer
                return-object v0
            """,
        ),
    )

    private fun activity(vararg methods: Method = arrayOf(dispatch())) =
        classDef(FRAGMENT_ACTIVITY, "Landroid/app/Activity;", *methods)

    private fun dispatch() = method(
        FRAGMENT_ACTIVITY, "dispatchTouchEvent", listOf(MOTION_EVENT), "Z",
        """
            const/4 v0, 0x0
            return v0
        """,
        registers = 3,
    )

    /** The Reels controls' autoplay-off check: (session, config, excluded, other)Z, asking the checker. */
    private fun reelCheck(name: String = "offAtStart", asksChecker: Boolean = true) = method(
        controls, name, listOf(session, config, "Z", "Z"), "Z",
        if (asksChecker) {
            """
                const/4 v0, 0x0
                invoke-virtual {v0, p1}, $checker->autoplayOff(${session})Z
                move-result v0
                return v0
            """
        } else {
            """
                const/4 v0, 0x0
                return v0
            """
        },
        registers = 6,
    )

    /** A class holding the component's name that calls [checks] of the check's shape. */
    private fun controlComponent(vararg checks: String = arrayOf("offAtStart")) = classDef(
        component, "Ljava/lang/Object;",
        method(
            component, "onCreateInitialState", emptyList(), "V",
            listOf("const-string v0, \"$REELS_CONTROLS\"", "const/4 v1, 0x0", "const/4 v2, 0x0", "const/4 v3, 0x0",
                "const/4 v4, 0x1").plus(checks.map { "invoke-virtual {v1, v2, v3, v4, v4}, $controls->$it(${session}${config}ZZ)Z" })
                .plus("return-void").joinToString("\n"),
            registers = 6,
        ),
    )

    private fun build(
        grootPlayer: ClassDef = groot(),
        triggerEnum: ClassDef = enumNaming(trigger, TRIGGER_NAMES),
        checkerClass: ClassDef = settingsChecker(),
        activityClass: ClassDef = activity(),
        componentClass: ClassDef = controlComponent(),
        controlsClass: ClassDef = classDef(controls, "Ljava/lang/Object;", reelCheck()),
    ) = PatchContexts.of(
        listOf(inlinePlayer(grootPlayer), playingInterface(), inlineParams(), triggerEnum, legacy(), checkerClass,
            enumNaming(setting, SETTING_NAMES), activityClass, componentClass, controlsClass,
            ExtensionDex.classDef(FEED_CONTROLS), ExtensionDex.classDef(SETTINGS_STATUS)) + playbackClasses(),
    )

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun patched(context: app.morphe.patcher.patch.BytecodePatchContext, owner: String, name: String) =
        context.mutableClassDefBy(owner).methods.single { it.name == name }

    @Test
    fun `reel cleanup refuses an inverted state branch and a reset that keeps an overlay`() {
        assertThrows(PatchException::class.java) { PatchContexts.of(playbackClasses(compare = "if-eq")).hookReelPlayback() }
        assertThrows(PatchException::class.java) { PatchContexts.of(playbackClasses(resetValue = 1)).hookReelPlayback() }
        assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).hookReelPlayback() }
    }

    @Test
    fun `inline controls call public native play and outer pause and read each media exclusion`() {
        val context = build()
        tapToPlayPatch.execute(context)
        val bridge = context.mutableClassDefBy(FEED_CONTROLS)
        val command = bridge.methods.single { it.name == "commandPlayer" }.implementation!!.instructions.toList()
        assertEquals(listOf("play", "pause"), command.mapNotNull { it.call }.filter { it.definingClass == player }.map { it.name })
        val triggerReads = command.filter { it.opcode == Opcode.SGET_OBJECT }
        assertEquals(2, triggerReads.size)
        assertTrue(triggerReads.all { (it as ReferenceInstruction).reference.toString() == "$trigger->user:$trigger" })
        INLINE_FLAGS.forEach { (name, stub) ->
            val code = bridge.methods.single { it.name == stub }.implementation!!.instructions.toList()
            val read = (code[1] as ReferenceInstruction).reference as FieldReference
            assertEquals(Opcode.IGET_BOOLEAN, code[1].opcode)
            assertEquals(name, read.name)
            assertEquals(VIDEO_PLAYER_PARAMS, read.definingClass)
        }
        assertEquals(INLINE_ATTACHED, patched(context, player, "attach").implementation!!.instructions.toList()[2].call.toString())
        assertEquals(INLINE_RELEASED, patched(context, player, "release").implementation!!.instructions.first().call.toString())
        // The seek bar seeks as Facebook's own bar does, and reads the position and length the resume patch reads.
        val seek = bridge.methods.single { it.name == "seekPlayer" }.implementation!!.instructions.toList()
        assertEquals("$player->seekTo(${trigger}I)V", seek.firstNotNullOf { it.call }.toString())
        assertEquals(listOf(0, 1, 2), seek.first { it.call != null }.registers())
        assertEquals("$trigger->seeker:$trigger", seek.single { it.opcode == Opcode.SGET_OBJECT }
            .let { (it as ReferenceInstruction).reference.toString() })
        listOf("playerPosition" to "position", "playerLength" to "length").forEach { (stub, reader) ->
            val code = bridge.methods.single { it.name == stub }.implementation!!.instructions
            assertEquals("$player->$reader()I", code.firstNotNullOf { it.call }.toString())
        }
        val length = bridge.methods.single { it.name == "paramsLength" }.implementation!!.instructions.toList()
        assertEquals(Opcode.IGET, length[1].opcode)
        assertEquals("$VIDEO_PLAYER_PARAMS->videoDurationMs:I", (length[1] as ReferenceInstruction).reference.toString())
    }

    @Test
    fun `inline controls refuse missing media facts and ambiguous attachments before editing stubs`() {
        val missing = build()
        missing.mutableClassDefBy(VIDEO_PLAYER_PARAMS).fields.removeAll { it.name == "isAudioOnly" }
        val play = missing.classDefBy(player).methods.single { it.name == "play" }
        assertTrue(assertThrows(PatchException::class.java) { missing.installFeedVideoControls(play) }
            .message.orEmpty().contains("isAudioOnly"))
        fun refusalWithout(context: app.morphe.patcher.patch.BytecodePatchContext) = assertThrows(PatchException::class.java) {
            context.installFeedVideoControls(context.classDefBy(player).methods.single { it.name == "play" })
        }.message.orEmpty()
        val noSeekTrigger = build(triggerEnum = enumNaming(trigger, TRIGGER_NAMES, seekable = false))
        assertTrue(refusalWithout(noSeekTrigger).contains("BY_INITIAL_FRAME_PERFECT_SEEK"))
        val noSeek = build()
        noSeek.mutableClassDefBy(player).methods.removeAll { it.name == "seekTo" }
        assertTrue(refusalWithout(noSeek).contains("native seek"))
        val noLength = build()
        noLength.mutableClassDefBy(player).methods.removeAll { it.name == "remaining" }
        assertTrue(refusalWithout(noLength).contains("remaining time reader"))
        assertTrue(noSeek.mutableClassDefBy(FEED_CONTROLS).methods.single { it.name == "seekPlayer" }
            .implementation!!.instructions.none { it.call?.definingClass == player })
        assertTrue(missing.mutableClassDefBy(FEED_CONTROLS).methods.single { it.name == "commandPlayer" }
            .implementation!!.instructions.none { it.call?.definingClass == player })
        val ambiguous = build()
        ambiguous.mutableClassDefBy(player).methods.add(method(player, "attachAgain", listOf(VIEW_GROUP, player), "V", """
            const-string v0, "$ATTACH_VIEW_LOG"
            iput-object p0, p1, $player->container:$VIEW_GROUP
            return-void
        """, registers = 4, static = true))
        assertTrue(assertThrows(PatchException::class.java) {
            ambiguous.installFeedVideoControls(ambiguous.classDefBy(player).methods.single { it.name == "play" })
        }.message.orEmpty().contains("found 2"))
    }

    @Test
    fun `the anchors take the shapes they're written for and not their near misses`() {
        // A play taking two arguments, or a static one, isn't the player's play.
        assertEquals(1, grootPlays(groot()).size)
        assertEquals(0, grootPlays(groot(method(player, "play", listOf(trigger, "I"), "V", holding(GROOT_PLAY)))).size)
        assertEquals(0, grootPlays(groot(method(player, "play", listOf(trigger), "V", holding(GROOT_PLAY), static = true))).size)
        assertEquals(0, grootPlays(groot(method(player, "play", listOf(trigger), "Z", holding(GROOT_PLAY)))).size)

        // The inner pause: the one every other pause hands on to.
        val pauses = grootPauses(groot(), trigger)
        assertEquals(2, pauses.size)
        assertEquals("pauseFor", innerPause(pauses)?.name)
        assertNull("two pauses that hand on to neither",
            innerPause(grootPauses(groot(method(player, "pause", listOf(trigger), "V", holding(GROOT_PAUSE)), innerPause()), trigger)))
        assertNull("a third pause that doesn't hand on",
            innerPause(grootPauses(groot(outerPause(), innerPause(),
                method(player, "stop", listOf(trigger, "I"), "V", holding(GROOT_PAUSE))), trigger)))
        assertEquals("a lone pause", "pauseFor", innerPause(grootPauses(groot(innerPause()), trigger))?.name)
        assertEquals(0, grootPauses(groot(method(player, "pause", listOf("I", trigger), "V", holding(GROOT_PAUSE))), trigger).size)

        assertEquals(1, grootBinds(groot()).size)
        assertEquals(0, grootBinds(groot(method(player, "bind", listOf("I", "I"), "V", holding(GROOT_BIND)))).size)

        // The older player's play takes the trigger once, among its arguments.
        assertEquals(1, legacyPlays(legacy(), trigger).size)
        assertEquals(0, legacyPlays(classDef(controller, "Ljava/lang/Object;",
            method(controller, "play", listOf(trigger, trigger), "V", holding(LEGACY_PLAY))), trigger).size)
        assertEquals(1, legacyPauses(legacy(), trigger).size)
        assertEquals(0, legacyPauses(classDef(controller, "Ljava/lang/Object;",
            method(controller, "pause", listOf(trigger, "I"), "V", holding(LEGACY_PAUSE))), trigger).size)

        // The trigger enum has to name every trigger the rule reads.
        assertTrue(isEnumNaming(enumNaming(trigger, TRIGGER_NAMES), TRIGGER_NAMES))
        assertTrue(!isEnumNaming(enumNaming(trigger, TRIGGER_NAMES - "BY_FLYOUT"), TRIGGER_NAMES))
        assertTrue(!isEnumNaming(classDef(trigger, "Ljava/lang/Object;"), TRIGGER_NAMES))

        // The checker, and its one reader answering the setting's enum.
        assertTrue(isAutoplaySettingsChecker(settingsChecker()))
        assertTrue(!isAutoplaySettingsChecker(legacy()))
        assertEquals(1, settingReaders(settingsChecker()) { it == setting }.size)
        assertEquals(0, settingReaders(settingsChecker()) { it == trigger }.size)
        assertEquals(0, settingReaders(classDef(checker, "Ljava/lang/Object;",
            method(checker, "read", listOf("I"), setting, "const/4 v0, 0x0\nreturn-object v0"))) { it == setting }.size)

        // The Reels check: (session, a config, two booleans) answering a boolean, and nothing like it.
        assertTrue(isAutoplayOffCheckShape(listOf(session, config, "Z", "Z"), "Z"))
        assertTrue(!isAutoplayOffCheckShape(listOf(session, config, "Z", "Z"), "V"))
        assertTrue(!isAutoplayOffCheckShape(listOf(session, config, "Z"), "Z"))
        assertTrue(!isAutoplayOffCheckShape(listOf(config, session, "Z", "Z"), "Z"))
        assertTrue(!isAutoplayOffCheckShape(listOf(session, "I", "Z", "Z"), "Z"))
        val signature = "$controls->offAtStart(${session}${config}ZZ)Z"
        assertEquals(setOf(signature), autoplayOffChecksCalled(controlComponent("offAtStart", "offAtStart")))
        assertEquals(2, autoplayOffChecksCalled(controlComponent("offAtStart", "offLater")).size)
        val controlsClass = classDef(controls, "Ljava/lang/Object;", reelCheck(), reelCheck("silent", asksChecker = false))
        assertEquals("offAtStart", methodNamed(controlsClass, signature)?.name)
        assertTrue(asksWithSession(methodNamed(controlsClass, signature)!!, checker))
        assertTrue(!asksWithSession(methodNamed(controlsClass, "$controls->silent(${session}${config}ZZ)Z")!!, checker))
        assertTrue(!asksWithSession(methodNamed(controlsClass, signature)!!, player))

        assertEquals(1, touchDispatches(activity()).size)
        assertEquals(0, touchDispatches(activity(method(FRAGMENT_ACTIVITY, "dispatchTouchEvent", listOf(MOTION_EVENT, "I"), "Z",
            "const/4 v0, 0x0\nreturn v0"))).size)
    }

    @Test
    fun `the patch puts each hook first where it belongs, in registers that fit`() {
        val context = build()
        tapToPlayPatch.execute(context)

        // Play: the player and the trigger copied into v0 and v1 through the 16-bit form, then the
        // answer, and a no returns before the player's own first instruction.
        val play = patched(context, player, "play")
        val code = play.implementation!!.instructions.toList()
        assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
            Opcode.IF_NEZ, Opcode.RETURN_VOID, Opcode.CONST_STRING), code.take(7).map { it.opcode })
        assertEquals(listOf(0, 18), (code[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(listOf(1, 19), (code[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(ALLOW_START, code[2].call.toString())
        assertEquals(listOf(0, 1), code[2].registers())
        assertSame("a yes goes on to the player's own first instruction", code[6],
            ((code[4] as BuilderOffsetInstruction).target.location.instruction))

        // The older player's play: the trigger is its second argument.
        val legacyPlay = patched(context, controller, "play").implementation!!.instructions.toList()
        assertEquals(ALLOW_LEGACY_START, legacyPlay[2].call.toString())
        assertEquals(listOf(1, 16), (legacyPlay[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(Opcode.RETURN_VOID, legacyPlay[5].opcode)

        // Pauses and the bind tell the extension first, with the range form reading only p0.
        listOf(Triple(player, "pauseFor", PAUSED), Triple(player, "bind", REBOUND), Triple(controller, "pause", PAUSED))
            .forEach { (owner, name, call) ->
                val first = patched(context, owner, name).implementation!!.instructions.first()
                assertEquals("$name", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                assertEquals("$name", call, first.call.toString())
                assertEquals("$name reads p0", listOf(patched(context, owner, name).localRegisterCount()), first.registers())
            }
        assertEquals("the outer pause hands on, so it isn't hooked twice", Opcode.CONST_STRING,
            patched(context, player, "pause").implementation!!.instructions.first().opcode)

        // Each answer of the reader goes out through the extension and back as the setting's type.
        val reader = patched(context, checker, "read").implementation!!.instructions.toList()
        val returns = reader.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        assertEquals(2, returns.size)
        returns.forEach { (index, answer) ->
            val register = (answer as OneRegisterInstruction).registerA
            assertEquals(AUTOPLAY_SETTING, reader[index - 3].call.toString())
            assertEquals(listOf(register), reader[index - 3].registers())
            assertEquals(Opcode.MOVE_RESULT_OBJECT, reader[index - 2].opcode)
            assertEquals(register, (reader[index - 2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.CHECK_CAST, reader[index - 1].opcode)
            assertEquals(setting, (reader[index - 1] as ReferenceInstruction).reference.toString())
        }
        // The branch to the second return lands on the extension's call, not past it.
        val branch = reader.first { it.opcode == Opcode.IF_NEZ } as BuilderOffsetInstruction
        assertSame(reader[returns[1].index - 3], branch.target.location.instruction)

        // The Reels check asks the extension first with its first boolean, copied into v0 through
        // the 16-bit form; a yes answers at once and a no runs Facebook's own check.
        val reels = patched(context, controls, "offAtStart").implementation!!.instructions.toList()
        assertEquals(listOf(Opcode.MOVE_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN,
            Opcode.CONST_4), reels.take(6).map { it.opcode })
        assertEquals(listOf(0, 4), (reels[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(SHOW_REEL_PLAY_BUTTON, reels[1].call.toString())
        assertEquals(listOf(0), reels[1].registers())
        assertEquals(0, (reels[4] as OneRegisterInstruction).registerA)
        assertSame(reels[5], (reels[3] as BuilderOffsetInstruction).target.location.instruction)

        // Every touch goes to the tap clock, the screen and the event in p0 and p1.
        val touch = patched(context, FRAGMENT_ACTIVITY, "dispatchTouchEvent").implementation!!.instructions.toList()
        assertEquals(TOUCH, touch[0].call.toString())
        assertEquals(listOf(1, 2), touch[0].registers())
        assertEquals(Opcode.CONST_4, touch[1].opcode)

        val events = patched(context, playbackListener, "onState").implementation!!.instructions.toList()
        val cleanup = events.indexOfFirst { it.call?.toString() == CLEAR_REEL_PLAY_BUTTON }
        assertTrue("PLAYING now reaches cleanup even when both viewer flags are false", cleanup > 2)
        assertEquals(Opcode.IF_EQZ, events[cleanup + 2].opcode)
        assertEquals(0, (events[cleanup + 1] as OneRegisterInstruction).registerA)
        assertSame("non-playing states bypass the cleanup", events[events.lastIndex - 1],
            (events[2] as BuilderOffsetInstruction).target.location.instruction)
    }

    @Test
    fun `a build the rule can't be sure of stops the patch`() {
        fun refusal(context: app.morphe.patcher.patch.BytecodePatchContext) =
            assertThrows(PatchException::class.java) { tapToPlayPatch.execute(context) }.message.orEmpty()

        assertTrue(refusal(build(triggerEnum = enumNaming(trigger, TRIGGER_NAMES - "BY_FLYOUT")))
            .contains("isn't an enum naming"))
        assertTrue(refusal(build(grootPlayer = groot(play(), method(player, "play2", listOf(trigger), "V", holding(GROOT_PLAY)),
            outerPause(), innerPause(), bind()))).contains("expected one player play"))
        assertTrue(refusal(build(grootPlayer = groot(play(), method(player, "pause", listOf(trigger), "V", holding(GROOT_PAUSE)),
            innerPause(), bind()))).contains("every other one hands on to"))
        assertTrue(refusal(build(grootPlayer = groot(play(), outerPause(), innerPause()))).contains("expected one bind"))
        assertTrue(refusal(build(checkerClass = settingsChecker(readerReturns = trigger))).contains("answering the enum"))
        assertTrue(refusal(build(activityClass = classDef(FRAGMENT_ACTIVITY, "Landroid/app/Activity;")))
            .contains("dispatchTouchEvent"))
        assertTrue(refusal(build(componentClass = controlComponent("offAtStart", "offLater")))
            .contains("to call one (session, config, Z, Z)Z check, found 2"))
        assertTrue(refusal(build(componentClass = classDef(component, "Ljava/lang/Object;")))
            .contains("found 0"))
        assertTrue(refusal(build(controlsClass = classDef(controls, "Ljava/lang/Object;", reelCheck(asksChecker = false))))
            .contains("doesn't ask the Autoplay settings checker"))
        // A play with one local has nowhere to put the player and the trigger.
        assertTrue(refusal(build(grootPlayer = groot(play(registers = 3), outerPause(), innerPause(), bind())))
            .contains("needs 2"))
    }
}
