/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.backgroundplay

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.taptoplay.DOUBLE_TAP_TOUCH
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.TOUCH
import app.morphe.patches.facebook.media.taptoplay.TRIGGER_NAMES
import app.morphe.patches.facebook.media.taptoplay.grootPauses
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.media.taptoplay.innerPause
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.media.taptoplay.hookTouches
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep playing in the background's anchors on every Facebook build the bundle declares: the player's
 * play, inner pause, pause helper and its one recording of the pause with the video id from one
 * accessor; Facebook's background manager and the one fullscreen handler it asks, with its check and
 * return; the record's one video id and one trigger; the check's ways to a no, each kind as many times
 * as the patch expects; and the notification starter's one server flag check. Then the patch itself on
 * those classes, beside Tap to play, with each call where it belongs. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class BackgroundPlayFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val ifs = setOf(Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LTZ, Opcode.IF_GEZ,
        Opcode.IF_GTZ, Opcode.IF_LEZ, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE)

    private fun Instruction.callsTo(descriptor: String): Boolean = call?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == descriptor
    } == true

    @Test
    fun `each declared build has every anchor once, and the patch goes in on its own classes`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name

                val holders = mutableMapOf<String, MutableList<ClassDef>>()
                val anchors = listOf(GROOT_PLAY, FULLSCREEN_HANDLER, MANAGER_ON_BACKGROUND, WINDOW_SKIP_BACK)
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

                // The player.
                val play = holders[GROOT_PLAY].orEmpty().flatMap(::grootPlays).single()
                val trigger = play.parameterTypes.single().toString()
                val groot = holders.getValue(GROOT_PLAY).single { it.type == play.definingClass }
                val pause = innerPause(grootPauses(groot, trigger))
                assertNotNull("$name: no inner pause", pause)
                val helperClasses = mutableListOf<ClassDef>()
                val helper = pauseHelper(groot, pause!!, trigger) { type ->
                    FixtureDex.classes(fixture, setOf(type))[type]?.also { helperClasses += it }
                }
                assertNotNull("$name: the inner pause's static helper", helper)
                val windowPause = triggerPause(grootPauses(groot, trigger), pause, trigger)
                assertNotNull("$name: the window button's pause, public and taking only the trigger", windowPause)
                val recorded = recorderCalls(helper!!, trigger)
                assertEquals("$name: the helper's recordings of the pause", 1, recorded.size)
                val accessor = videoIdAccessor(helper, recorded.single(), groot.type)
                assertNotNull("$name: the recorded video id's accessor", accessor)
                val getter = groot.methods.single { it.name == accessor && it.parameterTypes.isEmpty() }
                assertEquals("$name: the accessor answers a String", STRING, getter.returnType)
                assertTrue("$name: the player keeps isPlaying()Z",
                    groot.methods.any { it.name == "isPlaying" && it.parameterTypes.isEmpty() && it.returnType == "Z" })

                // Facebook's manager and its fullscreen handler.
                val managers = holders[MANAGER_ON_BACKGROUND].orEmpty()
                assertEquals("$name: classes holding \"$MANAGER_ON_BACKGROUND\"", listOf(MANAGER), managers.map { it.type })
                val manager = managers.single()
                assertTrue("$name: the manager isn't as the extension makes and fills it", isBackgroundManager(manager))
                val questions = handlerQuestions(managerStops(manager)!!)
                assertEquals("$name: the manager's questions to its handlers", 1, questions.size)
                val question = questions.single()
                val record = question.parameterTypes[1].toString()
                val handlers = holders[FULLSCREEN_HANDLER].orEmpty().filter { isFullscreenHandler(it, question.definingClass) }
                assertEquals("$name: fullscreen handlers", 1, handlers.size)
                val handler = handlers.single()
                val checks = handlerChecks(handler, record)
                assertEquals("$name: the handler's checks", 1, checks.size)
                assertEquals("$name: the handler's returns", 1, handlerReturns(handler, record).size)

                val classes = FixtureDex.classes(fixture, setOf(trigger, record, FRAGMENT_ACTIVITY))
                assertTrue("$name: the trigger enum doesn't name ${BACKGROUND_TRIGGERS.joinToString()}",
                    isEnumNaming(classes.getValue(trigger), TRIGGER_NAMES + BACKGROUND_TRIGGERS))
                val recordClass = classes.getValue(record)
                assertEquals("$name: the record's Strings", 1, recordFields(recordClass, STRING).size)
                assertEquals("$name: the record's triggers", 1, recordFields(recordClass, trigger).size)

                // The check: every way to its no is one the patch knows, as many of each as expected.
                val read = readHandlerCheck(checks.single(), record)
                assertEquals("$name: the notification's starter", 9, read.starter.parameterTypes.size)
                val starterType = read.starter.definingClass
                val starterClass = FixtureDex.classes(fixture, setOf(starterType)).getValue(starterType)
                val starter = starterClass.methods.single {
                    it.name == read.starter.name && it.parameterTypes.size == 9 && it.implementation != null
                }
                val gate = notificationGate(starterClass, starter)
                assertNotNull("$name: the starter's server flag check", gate)

                // The picture-in-picture window's buttons: one builder, answering a list at least once.
                val windowHolders = holders[WINDOW_SKIP_BACK].orEmpty()
                val builders = windowHolders.flatMap { it.methods.filter(::isWindowActionBuilder) }
                assertEquals("$name: builders of the window's buttons", 1, builders.size)
                val builder = builders.single()
                val listReturns = objectReturns(builder)
                assertTrue("$name: the builder answers no list", listReturns.isNotEmpty())

                // The patch, on this build's own classes.
                val base = listOf(groot, classes.getValue(trigger), manager, handler, recordClass, starterClass,
                    classes.getValue(FRAGMENT_ACTIVITY), ExtensionDex.classDef(SETTINGS_STATUS),
                    ExtensionDex.classDef(BACKGROUND_PLAY)) + holders.getValue(GROOT_PLAY).filter { it !== groot } +
                    helperClasses.filter { helperClass -> holders.getValue(GROOT_PLAY).none { it.type == helperClass.type } }
                val context = PatchContexts.of(base + windowHolders.filter { holder -> base.none { it.type == holder.type } })
                backgroundPlayPatch.execute(context)
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                // The stubs name the accessor and the handler.
                val extension = context.mutableClassDefBy(BACKGROUND_PLAY)
                fun stub(stubName: String) = extension.methods.single { it.name == stubName }
                    .implementation!!.instructions.first().let { ((it as ReferenceInstruction).reference as StringReference).string }
                assertEquals("$name: the accessor's name", accessor, stub(VIDEO_ID_METHOD))
                assertEquals("$name: the handler's class", binaryName(handler.type), stub(HANDLER_CLASS))

                // The play and the inner pause tell the extension first with p0 and p1.
                listOf(play to STARTED, pause to PAUSING).forEach { (original, tell) ->
                    val told = patched(original)
                    val first = told.implementation!!.instructions.first()
                    assertEquals("$name: ${original.name}", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                    assertTrue("$name: ${original.name} calls $tell", first.callsTo(tell))
                    assertEquals("$name: ${original.name} reads p0 and p1",
                        listOf(told.localRegisterCount(), told.localRegisterCount() + 1), first.namedRegisters())
                }

                // The check: the decision first, then Facebook's own code.
                val check = patched(checks.single())
                val code = check.implementation!!.instructions.toList()
                assertEquals("$name: the check's decision",
                    listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16,
                        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                    code.take(9).map { it.opcode })
                assertEquals("$name: the record's copy", check.parameterRegisterNumber(1),
                    (code[0] as TwoRegisterInstruction).registerB)
                val fields = listOf(code[1], code[2]).map { ((it as ReferenceInstruction).reference as FieldReference) }
                assertEquals("$name: the record's video id and trigger", listOf(STRING, trigger), fields.map { it.type })
                assertTrue("$name: the decision", code[4].callsTo(DECIDING))
                assertEquals(listOf(0, 1, 2), code[4].namedRegisters())
                assertEquals("$name: Facebook's first instruction after the decision",
                    checks.single().implementation!!.instructions.first().opcode, code[9].opcode)

                // Four checks skipped, two names filled in, and every answer told.
                val flow = ControlFlow.of(check)
                val skips = code.indices.filter { code[it].callsTo(SKIP_CHECK) }
                assertEquals("$name: checks the extension can skip", 4, skips.size)
                skips.forEach { at ->
                    val result = code[at + 1] as OneRegisterInstruction
                    assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
                    assertEquals(Opcode.IF_NEZ, code[at + 2].opcode)
                    assertEquals(result.registerA, (code[at + 2] as OneRegisterInstruction).registerA)
                    // A skip jumps past the branch that follows, which Facebook's no still guards.
                    val branch = at + 3
                    assertTrue("$name: a skip isn't in front of a branch", code[branch].opcode in ifs)
                    assertEquals("$name: a skip lands past its branch", branch + 1, flow.normal[at + 2].first())
                }
                listOf(TITLE, SUBTITLE).forEach { call ->
                    val at = code.indices.single { code[it].callsTo(call) }
                    val register = code[at].namedRegisters().single()
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                    assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
                    assertEquals("$name: $call is checked for null next", Opcode.IF_EQZ, code[at + 2].opcode)
                    assertEquals(register, (code[at + 2] as OneRegisterInstruction).registerA)
                }
                val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }
                // The decision's own no, then each of Facebook's returns with its answer told first.
                assertEquals("$name: returns", 1 + checks.single().implementation!!.instructions.count { it.opcode == Opcode.RETURN },
                    returns.size)
                returns.drop(1).forEach { at ->
                    assertTrue("$name: an answer not told", code[at - 1].callsTo(ANSWERED))
                    assertEquals(code[at].namedRegisters(), code[at - 1].namedRegisters())
                }
                // Every branch that went to Facebook's no now lands on the call that tells it.
                val told = code.indices.filter { code[it].callsTo(ANSWERED) }.toSet()
                val toNo = code.indices.filter { code[it].opcode in ifs && flow.normal[it].first() in told }
                assertEquals("$name: branches to the told no", read.branches.size, toNo.size)

                // The return tells the extension first and before each of its returns.
                val back = patched(handlerReturns(handler, record).single())
                val backCode = back.implementation!!.instructions.toList()
                assertTrue("$name: the return's first call", backCode.first().callsTo(RETURNING))
                assertEquals(listOf(back.localRegisterCount()), backCode.first().namedRegisters())
                backCode.indices.filter { backCode[it].opcode == Opcode.RETURN_VOID }.forEach { at ->
                    assertTrue("$name: a return not told", backCode[at - 1].callsTo(RETURNED))
                }

                // The notification's server flag reads the extension first.
                val flag = patched(gate!!).implementation!!.instructions.toList()
                assertEquals("$name: the flag's hook",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN),
                    flag.take(4).map { it.opcode })
                assertTrue(flag[0].callsTo(NOTIFICATION_ALLOWED))

                // Each list of window buttons goes through the extension on its way out.
                val built = patched(builder).implementation!!.instructions.toList()
                val windowReturns = built.indices.filter { built[it].opcode == Opcode.RETURN_OBJECT }
                assertEquals("$name: the builder's returns", listReturns.size, windowReturns.size)
                windowReturns.forEach { at ->
                    val register = (built[at] as OneRegisterInstruction).registerA
                    assertTrue("$name: a list of window buttons not handed over", built[at - 2].callsTo(WINDOW_ACTIONS))
                    assertEquals(listOf(register), built[at - 2].namedRegisters())
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, built[at - 1].opcode)
                    assertEquals(register, (built[at - 1] as OneRegisterInstruction).registerA)
                }

                // The tap clock goes in once, whether Tap to play runs too or not.
                val dispatch = touchDispatches(classes.getValue(FRAGMENT_ACTIVITY)).single()
                assertEquals("$name: tap clock calls", 1,
                    patched(dispatch).implementation!!.instructions.count { it.callsTo(TOUCH) })
                context.hookTouches("Tap to play", doubleTap = true)
                val both = patched(dispatch).implementation!!.instructions.toList()
                assertEquals("$name: tap clock calls beside Tap to play", 1, both.count { it.callsTo(TOUCH) })
                assertTrue("$name: the double tap goes first beside Tap to play", both[0].callsTo(DOUBLE_TAP_TOUCH))
                assertTrue("$name: then the tap clock", both[1].callsTo(TOUCH))
                checked += version
            }
        }
        assertEquals("builds checked", versions, checked)
    }
}
