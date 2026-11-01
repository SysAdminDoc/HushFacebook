/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.ads.sponsoredsearch.enumConstantFields
import app.morphe.patches.facebook.media.resume.POSITION_STUB
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.media.resume.paramsGetters
import app.morphe.patches.facebook.media.resume.playingInterfaces
import app.morphe.patches.facebook.media.resume.positionReaders
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.util.ControlFlow
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
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
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap to play's hooks on every Facebook build the bundle declares: FbGrootPlayer's play, its
 * inner pause and its bind, each there once; the older player's play and pause, once each; the
 * trigger enum naming every trigger the extension's rule reads; the one Autoplay settings checker
 * and its one reader of the ON, OFF, WIFI_ONLY and DEFAULT enum; the one autoplay-off check the Reels
 * controls call, which asks that checker; and FbFragmentActivity's touch dispatch. Then the patch itself, run on those classes, with each call first where it belongs and
 * reading the registers the method keeps its arguments in. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TapToPlayFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    @Test
    fun `each declared build has every hook once, and the patch goes in on its own classes`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name

                // One pass over the build for the classes holding the anchors' strings.
                val holders = mutableMapOf<String, MutableList<ClassDef>>()
                val anchors = listOf(GROOT_PLAY, LEGACY_PLAY, AUTOPLAY_SETTINGS_CHECKER, REELS_CONTROLS, REELS_PLAYBACK_STARTED)
                FixtureDex.forEach(fixture) { dex ->
                    val strings = anchors.filter { anchor -> dex.stringSection.any { it == anchor } }
                    if (strings.isEmpty()) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.type.startsWith(EXTENSION_CLASSES)) continue
                        for (anchor in strings) {
                            if (classDef.methods.any { holdsString(it, anchor) }) {
                                holders.getOrPut(anchor) { mutableListOf() } += ImmutableClassDef.of(classDef)
                            }
                        }
                    }
                }

                val plays = holders[GROOT_PLAY].orEmpty().flatMap(::grootPlays)
                assertEquals("$name: players' plays holding \"$GROOT_PLAY\"", 1, plays.size)
                val play = plays.single()
                val trigger = play.parameterTypes.single().toString()
                val groot = holders.getValue(GROOT_PLAY).single { it.type == play.definingClass }

                val pauses = grootPauses(groot, trigger)
                assertEquals("$name: the player's pauses", 2, pauses.size)
                val pause = innerPause(pauses)
                assertNotNull("$name: no pause the other hands on to", pause)
                assertEquals("$name: the inner pause's arguments", 2, pause!!.parameterTypes.size)
                val binds = grootBinds(groot)
                assertEquals("$name: the player's binds", 1, binds.size)

                val legacyPlays = holders[LEGACY_PLAY].orEmpty().flatMap { legacyPlays(it, trigger) }
                assertEquals("$name: older player plays", 1, legacyPlays.size)
                val legacyPlay = legacyPlays.single()
                val legacy = holders.getValue(LEGACY_PLAY).single { it.type == legacyPlay.definingClass }
                assertEquals("$name: older player pauses", 1, legacyPauses(legacy, trigger).size)

                val checkers = holders[AUTOPLAY_SETTINGS_CHECKER].orEmpty().filter(::isAutoplaySettingsChecker)
                assertEquals("$name: Autoplay settings checkers", 1, checkers.size)
                val checker = checkers.single()
                val enumCandidates = checker.methods.filter { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
                    .map { it.returnType }.toSet()

                // The Reels controls call one autoplay-off check, of one class.
                val components = holders[REELS_CONTROLS].orEmpty().filter { autoplayOffChecksCalled(it).isNotEmpty() }
                val reelChecks = components.flatMap(::autoplayOffChecksCalled).toSet()
                assertEquals("$name: Reels autoplay-off checks", 1, reelChecks.size)
                val reelCheckSignature = reelChecks.single()
                val controlsType = reelCheckSignature.substringBefore("->")

                val kept = setOf(trigger, FRAGMENT_ACTIVITY, controlsType, VIDEO_PLAYER_PARAMS) + enumCandidates
                val classes = FixtureDex.classes(fixture, kept)
                assertEquals("$name: classes missing", emptySet<String>(),
                    setOf(trigger, FRAGMENT_ACTIVITY, VIDEO_PLAYER_PARAMS) - classes.keys)
                assertTrue("$name: the trigger enum doesn't name ${TRIGGER_NAMES.joinToString()}",
                    isEnumNaming(classes.getValue(trigger), TRIGGER_NAMES))
                val readers = settingReaders(checker) { type -> classes[type]?.let { isEnumNaming(it, SETTING_NAMES) } == true }
                assertEquals("$name: readers of the Autoplay setting", 1, readers.size)
                val reader = readers.single()
                val setting = classes.getValue(reader.returnType)
                assertEquals("$name: touch dispatches", 1, touchDispatches(classes.getValue(FRAGMENT_ACTIVITY)).size)
                val reelCheck = methodNamed(classes.getValue(controlsType), reelCheckSignature)
                assertNotNull("$name: $reelCheckSignature has no body", reelCheck)
                assertTrue("$name: the Reels check doesn't ask the Autoplay settings checker",
                    asksWithSession(reelCheck!!, checker.type))

                val playbackOwner = holders.getValue(REELS_PLAYBACK_STARTED).single { owner ->
                    owner.methods.any { it.returnType == "V" && it.parameterTypes.size == 1 && holdsString(it, REELS_PLAYBACK_STARTED) }
                }
                val playback = playbackOwner.methods.single { holdsString(it, REELS_PLAYBACK_STARTED) }
                val playbackClasses = mutableMapOf(playbackOwner.type to playbackOwner)
                repeat(2) {
                    val references = playbackClasses.values.flatMap { owner ->
                        owner.fields.map { it.type } + owner.methods.flatMap { method ->
                            method.implementation?.instructions?.mapNotNull { instruction ->
                                (instruction as? ReferenceInstruction)?.reference as? FieldReference
                            }?.flatMap { listOf(it.definingClass, it.type) }.orEmpty()
                        }
                    }.filter { it.startsWith("L") }.toSet() - playbackClasses.keys
                    playbackClasses.putAll(FixtureDex.classes(fixture, references))
                }

                // The player's interfaces, where the position reader is declared (#90).
                val grootInterfaces = FixtureDex.classes(fixture, groot.interfaces.toSet())

                // The patch, on this build's own classes.
                val context = PatchContexts.of(
                    listOf(groot, legacy, checker, classes.getValue(trigger), setting, classes.getValue(FRAGMENT_ACTIVITY),
                        classes.getValue(controlsType), classes.getValue(VIDEO_PLAYER_PARAMS), ExtensionDex.classDef(TAP_TO_PLAY),
                        ExtensionDex.classDef(FEED_CONTROLS),
                        ExtensionDex.classDef(SETTINGS_STATUS)) + components + playbackClasses.values +
                        grootInterfaces.values.filter { it.type != groot.type },
                )
                tapToPlayPatch.execute(context)
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                // The separate inline button calls the same public native commands, with BY_USER.
                val command = context.mutableClassDefBy(FEED_CONTROLS).methods.single { it.name == "commandPlayer" }
                val commandCode = command.implementation!!.instructions.toList()
                val nativeCommands = commandCode.mapNotNull { it.call }.filter { it.definingClass == groot.type }
                val outerPause = pauses.single { it !== pause }
                assertEquals("$name: inline native commands", listOf(play.name, outerPause.name), nativeCommands.map { it.name })
                nativeCommands.forEach { call ->
                    assertEquals(listOf(trigger), call.parameterTypes.map(CharSequence::toString))
                }
                val userNames = enumConstantFields(classes.getValue(trigger)).filterValues { it == "BY_USER" }.keys
                val user = classes.getValue(trigger).fields.single {
                    it.name in userNames && AccessFlags.ENUM.isSet(it.accessFlags)
                }.name
                assertEquals("$name: inline commands use the native user trigger twice", 2, commandCode.count {
                    val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                    it.opcode == Opcode.SGET_OBJECT && field?.definingClass == trigger && field.name == user
                })
                // The seek bar seeks with the exact trigger, through the player's public seek.
                val seekNames = enumConstantFields(classes.getValue(trigger)).filterValues { it == "BY_INITIAL_FRAME_PERFECT_SEEK" }.keys
                val seekBy = classes.getValue(trigger).fields.single {
                    it.name in seekNames && AccessFlags.ENUM.isSet(it.accessFlags)
                }.name
                val bridge = context.mutableClassDefBy(FEED_CONTROLS)
                val seekCode = bridge.methods.single { it.name == "seekPlayer" }.implementation!!.instructions.toList()
                assertEquals("$name: the seek's trigger", 1, seekCode.count {
                    val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                    it.opcode == Opcode.SGET_OBJECT && field?.definingClass == trigger && field.name == seekBy
                })
                val seekCall = seekCode.mapNotNull { it.call }.single()
                assertEquals("$name: the seek's owner", groot.type, seekCall.definingClass)
                assertEquals("$name: the seek's arguments", listOf(trigger, "I"), seekCall.parameterTypes.map(CharSequence::toString))
                listOf("playerPosition", "playerLength").forEach { stub ->
                    val call = bridge.methods.single { it.name == stub }.implementation!!.instructions.mapNotNull { it.call }.single()
                    assertEquals("$name: $stub's owner", groot.type, call.definingClass)
                    assertEquals("$name: $stub's answer", "I", call.returnType)
                }
                assertTrue("$name: position and length are two readers", bridge.methods.single { it.name == "playerPosition" }
                    .implementation!!.instructions.mapNotNull { it.call }.single().name != bridge.methods.single { it.name == "playerLength" }
                    .implementation!!.instructions.mapNotNull { it.call }.single().name)
                assertEquals("$name: the params' length", 1, bridge.methods.single { it.name == "paramsLength" }
                    .implementation!!.instructions.count {
                        val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                        it.opcode == Opcode.IGET && field?.definingClass == VIDEO_PLAYER_PARAMS
                    })
                assertEquals("$name: the attach knows the current player", 1,
                    context.mutableClassDefBy(groot.type).methods.sumOf { method ->
                        method.implementation?.instructions?.count { it.call?.toString() == INLINE_ATTACHED } ?: 0
                    })
                assertEquals("$name: the release retires its control", 1,
                    context.mutableClassDefBy(groot.type).methods.sumOf { method ->
                        method.implementation?.instructions?.count { it.call?.toString() == INLINE_RELEASED } ?: 0
                    })

                // Both plays: player and trigger copied into v0 and v1, the answer, and a no that
                // returns before the player's own first instruction.
                listOf(play to ALLOW_START, legacyPlay to ALLOW_LEGACY_START).forEach { (original, allow) ->
                    val gated = patched(original)
                    val code = gated.implementation!!.instructions.toList()
                    val first = original.implementation!!.instructions.first()
                    assertEquals("$name: ${original.name}'s gate",
                        listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                            Opcode.IF_NEZ, Opcode.RETURN_VOID),
                        code.take(6).map { it.opcode })
                    val triggerIndex = original.parameterTypes.indexOfFirst { it.toString() == trigger }
                    assertEquals("$name: the player's copy", listOf(0, gated.localRegisterCount()),
                        (code[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                    assertEquals("$name: the trigger's copy", listOf(1, gated.parameterRegisterNumber(triggerIndex)),
                        (code[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                    assertEquals("$name: ${original.name}'s call", allow, code[2].call.toString())
                    assertEquals(listOf(0, 1), code[2].registers())
                    assertEquals(0, (code[3] as OneRegisterInstruction).registerA)
                    assertEquals("$name: the player's own first instruction after the gate", first.opcode, code[6].opcode)
                    assertSame("$name: a yes goes on to it", code[6], (code[4] as BuilderOffsetInstruction).target.location.instruction)
                    assertEquals("$name: ${original.name} grew by the gate",
                        original.implementation!!.instructions.count() + 6, code.size)
                }

                // Pauses and the bind tell the extension first, with p0 alone.
                listOf(pause to PAUSED, binds.single() to REBOUND, legacyPauses(legacy, trigger).single() to PAUSED)
                    .forEach { (original, tell) ->
                        val told = patched(original)
                        val first = told.implementation!!.instructions.first()
                        assertEquals("$name: ${original.name}", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                        assertEquals("$name: ${original.name}", tell, first.call.toString())
                        assertEquals("$name: ${original.name} reads p0", listOf(told.localRegisterCount()), first.registers())
                    }
                val outer = pauses.single { it !== pause }
                assertTrue("$name: the outer pause was hooked too",
                    patched(outer).implementation!!.instructions.none { it.call?.toString() == PAUSED })

                // Each answer of the reader goes through the extension and back as the setting's type.
                val answers = patched(reader).implementation!!.instructions.toList()
                val returns = answers.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
                assertEquals("$name: the reader's returns",
                    reader.implementation!!.instructions.count { it.opcode == Opcode.RETURN_OBJECT }, returns.size)
                returns.forEach { (index, answer) ->
                    val register = (answer as OneRegisterInstruction).registerA
                    assertEquals("$name: reader call", AUTOPLAY_SETTING, answers[index - 3].call.toString())
                    assertEquals(listOf(register), answers[index - 3].registers())
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, answers[index - 2].opcode)
                    assertEquals(register, (answers[index - 2] as OneRegisterInstruction).registerA)
                    assertEquals(Opcode.CHECK_CAST, answers[index - 1].opcode)
                    assertEquals(setting.type, (answers[index - 1] as ReferenceInstruction).reference.toString())
                }

                // The Reels check asks the extension first with its first boolean, and a no runs its own code.
                val reels = patched(reelCheck).implementation!!.instructions.toList()
                assertEquals("$name: the Reels check's hook",
                    listOf(Opcode.MOVE_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN),
                    reels.take(5).map { it.opcode })
                assertEquals("$name: the first boolean's copy", listOf(0, patched(reelCheck).parameterRegisterNumber(2)),
                    (reels[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                assertEquals(SHOW_REEL_PLAY_BUTTON, reels[1].call.toString())
                assertEquals(listOf(0), reels[1].registers())
                assertSame("$name: a no goes on to the check's own first instruction", reels[5],
                    (reels[3] as BuilderOffsetInstruction).target.location.instruction)
                assertEquals(reelCheck.implementation!!.instructions.first().opcode, reels[5].opcode)

                // Only the actual PLAYING branch gains cleanup. PAUSED, preparing, seeking and
                // error events still take their original route, with no early method return.
                val events = patched(playback)
                val eventCode = events.implementation!!.instructions.toList()
                val cleanup = eventCode.indices.single { eventCode[it].call?.toString() == CLEAR_REEL_PLAY_BUTTON }
                val playingRead = eventCode.indices.single { index ->
                    val field = (eventCode[index] as? ReferenceInstruction)?.reference as? FieldReference
                    eventCode[index].opcode == Opcode.SGET_OBJECT && field != null &&
                        playbackClasses[field.type]?.let { enumConstantFields(it)[field.name] == "PLAYING" } == true
                }
                assertEquals(Opcode.IF_NE, eventCode[playingRead + 1].opcode)
                val eventFlow = ControlFlow.of(events)
                fun reaches(start: Int): Boolean {
                    val pending = java.util.ArrayDeque<Int>().apply { add(start) }
                    val seen = mutableSetOf<Int>()
                    while (!pending.isEmpty()) {
                        val index = pending.removeFirst()
                        if (index == cleanup) return true
                        if (seen.add(index)) eventFlow.normal[index].forEach(pending::add)
                    }
                    return false
                }
                assertTrue("$name: PLAYING reaches the cleanup decision", reaches(playingRead + 2))
                assertFalse("$name: other playback states must bypass cleanup", reaches(eventFlow.normal[playingRead + 1].first()))
                assertEquals("$name: original listener remains intact", playback.implementation!!.instructions.count() + 4, eventCode.size)
                assertEquals("$name: cleanup keeps the original decision register",
                    (eventCode[cleanup + 1] as OneRegisterInstruction).registerA,
                    (eventCode[cleanup + 2] as OneRegisterInstruction).registerA)

                // Every touch goes to the tap clock with the screen and the event, and on unchanged.
                val dispatch = patched(touchDispatches(classes.getValue(FRAGMENT_ACTIVITY)).single())
                val touch = dispatch.implementation!!.instructions.toList()
                assertEquals("$name: the tap clock", TOUCH, touch[0].call.toString())
                assertEquals(listOf(dispatch.localRegisterCount(), dispatch.localRegisterCount() + 1), touch[0].registers())
                assertTrue("$name: dispatchTouchEvent still hands the event on",
                    touch.drop(1).any { it.call?.name == "dispatchTouchEvent" })

                // The reel check's stubs: the player's params getter, and the field the params report as isFbShorts.
                val getter = paramsGetters(groot).single()
                val shorts = classes.getValue(VIDEO_PLAYER_PARAMS).methods.filter { holdsString(it, FB_SHORTS) }
                    .mapNotNull { reportedValues(it)[FB_SHORTS] }.distinctBy { it.toString() }
                assertEquals("$name: fields the params report as $FB_SHORTS", 1, shorts.size)
                val stubs = context.mutableClassDefBy(TAP_TO_PLAY)
                fun stub(stubName: String) = stubs.methods.single { it.name == stubName }.implementation!!.instructions.toList()
                val paramsStub = stub(PLAYER_PARAMS_STUB)
                assertEquals("$name: the params stub's cast", groot.type, ((paramsStub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the params stub's call", "${groot.type}->${getter.name}()$VIDEO_PLAYER_PARAMS",
                    paramsStub[1].call.toString())
                assertEquals("$name: the params stub's answer", Opcode.RETURN_OBJECT, paramsStub[3].opcode)
                val flagStub = stub(FB_SHORTS_STUB)
                assertEquals("$name: the $FB_SHORTS stub's read", Opcode.IGET_BOOLEAN, flagStub[1].opcode)
                assertEquals("$name: the $FB_SHORTS stub's field", "$VIDEO_PLAYER_PARAMS->${shorts.single().name}:Z",
                    ((flagStub[1] as ReferenceInstruction).reference as FieldReference).toString())
                assertEquals("$name: the $FB_SHORTS stub reads its argument", 0, (flagStub[1] as TwoRegisterInstruction).registerB)

                // The position stub: the player's one int method of its isPlaying() interface (#90).
                val face = playingInterfaces(grootInterfaces.values.toList()).single()
                val positionReader = positionReaders(groot, face).single()
                val positionStub = stub(POSITION_STUB)
                assertEquals("$name: the position stub's cast", groot.type, ((positionStub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the position stub's call", "${groot.type}->${positionReader.name}()I", positionStub[1].call.toString())
                assertEquals("$name: the position stub's answer", listOf(Opcode.MOVE_RESULT, Opcode.RETURN),
                    positionStub.subList(2, 4).map { it.opcode })
                checked += version
            }
        }
        assertEquals("builds checked", versions, checked)
    }
}
