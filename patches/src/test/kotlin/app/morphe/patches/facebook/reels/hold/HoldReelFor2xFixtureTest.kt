/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.hold

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.reelspeed.speedSetters
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hold a reel for 2x's anchors on every Facebook build the bundle declares: a long-press handler
 * for each speed-up component (FbShortsVideoControlComponent's only on 583, which dropped
 * UddPlayerControlComponent's) holding "speed_up", which asks the immersive player config one flag
 * and loads the log name once, after it takes the flag's answer; as many release
 * listeners, which ask a second flag straight before that one; the overlay's one check of the
 * speed-up flag before it gives a reel its release listener; the one edge check of the build, which
 * a handler calls (583: inlined into a lambda the handler makes); and nothing else in the build
 * asking either flag but three places the patch
 * leaves alone, none of which makes a handler or a listener; and a speed-up in each speed-up
 * component, setting FbGrootPlayer's speed from one read of the config; and every release listener
 * reading the player's speed with one public getter. Then the patch itself, run on those
 * classes: every flag call's answer through the extension in the same register before its branch,
 * every answer of the edge check and return of the hold speed through the extension, the edge
 * check's press and view handed over once it has them, the hold's start
 * straight after each "speed_up" load, the touch dispatch handing the event over first, the speed
 * setter handing the player and the speed over first and going on with the speed it gets back, and
 * the extension's speed stub calling the getter. For Hold a feed video for 2x, the player's one attach
 * of its view and the one public ViewGroup field it writes, then the attach handing the container and
 * the player over first, the touch dispatch handing the activity and the event over ahead of the
 * guard, and the extension's stubs reading that field and calling the setter. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HoldReelFor2xFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun key(method: Method) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build has every anchor, and the patch goes in on its own classes`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                // One handler, one release listener and one speed-up for each speed-up component: 583 dropped
                // UddPlayerControlComponent (582 LX/XIg;) and its handler and listener (LX/Z4h; and LX/Z4d;).
                val handlers = FixtureDex.classesHolding(bundle, SPEED_UP_LOG).filter(::isLongPressHandler)
                assertEquals("$name: long-press handlers", SPEED_UP_COMPONENTS.size, handlers.size)
                val config = handlers.mapNotNull(::configType).toSet().single()
                val speedUp = handlers.flatMap { h -> h.methods.flatMap { flagCalls(it, config).values } }.toSet().single()

                val listenerTypes = mutableSetOf<String>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.classes.filter { isReleaseListener(it) && configType(it) == config }.forEach { listenerTypes += it.type }
                }
                assertEquals("$name: release listeners", SPEED_UP_COMPONENTS.size, listenerTypes.size)
                val listeners = FixtureDex.classes(bundle, listenerTypes).values.toList()
                val release = listeners.flatMap { l -> l.methods.flatMap { flagsBefore(it, config, speedUp) } }.toSet().single()
                assertTrue("$name: the release flag is the speed-up flag", release != speedUp)
                for (listener in listeners) {
                    val asks = listener.methods.flatMap { flagsBefore(it, config, speedUp) }
                    assertEquals("$name: ${listener.type} asks the release flag before the speed-up flag", listOf(release), asks)
                }

                val lambdas = (handlers + listeners).map { it.type }.toSet()
                val components = CONTROL_COMPONENTS.flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
                val builders = components.flatMap { c -> c.methods.filter { makesOneOf(it, lambdas) } }
                val flags = setOf(speedUp, release)
                val checks = builders.filter { flagCalls(it, config).values.any { flag -> flag in flags } }
                assertEquals("$name: component renders asking either flag", 1, checks.size)
                assertEquals("$name: the render's calls of either flag", listOf(speedUp),
                    flagCalls(checks.single(), config).values.filter { it in flags })

                // Everything else in the build that asks either flag, left alone: none of it makes a
                // handler or a listener, and there are three.
                val flagKeys = setOf("$config->$speedUp()Z", "$config->$release()Z")
                val askers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.definingClass == config && (it.name == speedUp || it.name == release) }
                }) { method -> method.implementation?.instructions?.any { it.call?.toString() in flagKeys } == true }
                val patched = (handlers + listeners).map { it.type }.toSet()
                val others = askers.filter { it.definingClass !in patched && checks.none { c -> key(c) == key(it) } }
                assertEquals("$name: other methods asking the flags: ${others.map(::key)}", 3, others.size)
                assertTrue("$name: another method makes a handler or a listener", others.none { makesOneOf(it, lambdas) })
                assertTrue("$name: the release flag is asked outside the release listeners",
                    others.none { m -> m.code().any { it.call?.toString() == "$config->$release()Z" } })

                // Up to 582 a handler calls the edge check, the one method of its shape. 583 and 584 have
                // none: they inline the check into one case of a merged lambda the handler makes
                // (583's LX/CnX;->invoke).
                val edgeCalls = handlers.flatMap { h -> h.methods.flatMap { edgeChecksCalled(it, config) } }.distinctBy { it.toString() }
                val shaped = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.typeSection.any { it == config } }) {
                    isEdgeCheckShape(it.parameterTypes.map(CharSequence::toString), it.returnType, config)
                }
                val edgeCheck: EdgeCheck
                val edgeClass: ClassDef
                if (edgeCalls.isEmpty()) {
                    assertEquals("$name: methods of the edge check's shape", 0, shaped.size)
                    val made = FixtureDex.classes(bundle, handlers.flatMap { h -> h.methods.flatMap(::typesMade) }.toSet()).values
                    val inlined = made.flatMap { c -> c.methods.mapNotNull { inlinedEdgeCheck(it, config) } }
                    assertEquals("$name: lambdas the handlers make holding the edge check", 1, inlined.size)
                    edgeCheck = inlined.single()
                    edgeClass = made.single { it.type == edgeCheck.method.definingClass }
                    assertTrue("$name: the press and view aren't neighbours", edgeCheck.view != edgeCheck.press + 1)
                } else {
                    assertEquals("$name: edge checks the handlers call", 1, edgeCalls.size)
                    assertEquals("$name: methods of the edge check's shape", 1, shaped.size)
                    edgeClass = FixtureDex.classes(bundle, setOf(edgeCalls.single().definingClass)).values.single()
                    edgeCheck = ownEdgeCheck(edgeClass.methods.single { isMethod(it, edgeCalls.single()) })!!
                }
                val edge = edgeCheck.method

                // The speed-ups set FbGrootPlayer's speed from one double read of the config's answer.
                val playerHolders = FixtureDex.classesHolding(bundle, GROOT_PLAY).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val play = playerHolders.flatMap(::grootPlays).single()
                val player = playerHolders.single { it.type == play.definingClass }
                val setter = speedSetters(player).single()
                val configClass = FixtureDex.classes(bundle, setOf(config)).values.single()
                val configAnswers = configClass.methods.filter { it.parameterTypes.isEmpty() }.map { it.returnType }.toSet()
                val speedUpClasses = SPEED_UP_COMPONENTS.flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
                val reads = speedUpClasses.flatMap { c ->
                    c.methods.flatMap { m -> holdSpeedReads(m, "${player.type}->${setter.name}(F)V", configAnswers).map { m to it } }
                }
                assertEquals("$name: speed-ups setting the speed from a read of the config: ${reads.map { key(it.first) }}",
                    SPEED_UP_COMPONENTS.size, reads.size)
                assertEquals("$name: the speed-ups' components", SPEED_UP_COMPONENTS.size, reads.map { it.first.definingClass }.toSet().size)
                val speedRead = reads.map { it.second.toString() }.toSet().single()
                val speedClass = FixtureDex.classes(bundle, setOf(reads.first().second.definingClass)).values.single()
                val holdSpeed = speedClass.methods.single { isMethod(it, reads.first().second) }

                // Each release listener reads the player's speed with the same one getter.
                for (listener in listeners) {
                    val getters = listener.methods.flatMap { speedGettersCalled(it, player.type) }.map { it.toString() }.toSet()
                    assertEquals("$name: ${listener.type}'s reads of ${player.type}'s speed", 1, getters.size)
                }
                val getterCall = listeners.flatMap { l -> l.methods.flatMap { speedGettersCalled(it, player.type) } }
                    .distinctBy { it.toString() }.single()
                val speedGetter = player.methods.single { isMethod(it, getterCall) }
                assertTrue("$name: ${key(speedGetter)} isn't public", AccessFlags.PUBLIC.isSet(speedGetter.accessFlags))

                // Hold a feed video for 2x: one static attach of the player's view, taking the container first
                // and the player once, writing one ViewGroup field of the player, public like the setter.
                val attachHolders = FixtureDex.classesHolding(bundle, ATTACH_VIEW_LOG).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val attaches = attachHolders.flatMap { c -> c.methods.mapNotNull { m -> attachedPlayerParameter(m, player.type)?.let { m to it } } }
                assertEquals("$name: attaches of ${player.type}'s view: ${attaches.map { key(it.first) }}", 1, attaches.size)
                val (attach, playerParameter) = attaches.single()
                val written = containersWritten(attach, player)
                assertEquals("$name: ViewGroup fields of ${player.type} the attach writes: $written", 1, written.size)
                val container = written.single()
                val containerField = player.fields.single { it.name == container.name && it.type == container.type }
                assertTrue("$name: ${player.type}'s ${container.name} isn't a public instance field",
                    AccessFlags.PUBLIC.isSet(containerField.accessFlags) && !AccessFlags.STATIC.isSet(containerField.accessFlags))
                assertTrue("$name: ${key(setter)} isn't public", AccessFlags.PUBLIC.isSet(setter.accessFlags))
                assertTrue("$name: the attach has fewer than two locals", attach.localRegisterCount() >= 2)

                // Hide the pull down to lock hint: one enum naming the 2x pill's seven states, and one
                // constructor taking it, a float and a boolean, the state first.
                val stateEnums = FixtureDex.classesHolding(bundle, "LOCK_HINT").filter(::isPillStates)
                assertEquals("$name: 2x pill state enums: ${stateEnums.map { it.type }}", 1, stateEnums.size)
                val pillStates = stateEnums.single()
                val pills = FixtureDex.methodsWhere(bundle, { true }) { isPillConstructor(it, pillStates.type) }
                assertEquals("$name: 2x pill constructors: ${pills.map(::key)}", 1, pills.size)
                val pill = pills.single()
                val pillClass = FixtureDex.classes(bundle, setOf(pill.definingClass)).values.single()
                // Reels draw their own pill: one enum naming its six states, and one constructor taking
                // the session, that enum, one more object and two booleans, the hints flag first.
                val reelStateEnums = FixtureDex.classesHolding(bundle, "INITIATING_LOCK").filter(::isReelPillStates)
                assertEquals("$name: Reels 2x pill state enums: ${reelStateEnums.map { it.type }}", 1, reelStateEnums.size)
                val reelPillStates = reelStateEnums.single()
                val reelPills = FixtureDex.methodsWhere(bundle, { true }) { isReelPillConstructor(it, reelPillStates.type) }
                assertEquals("$name: Reels 2x pill constructors: ${reelPills.map(::key)}", 1, reelPills.size)
                val reelPill = reelPills.single()
                val reelPillClass = FixtureDex.classes(bundle, setOf(reelPill.definingClass)).values.single()

                val activity = FixtureDex.classes(bundle, setOf(FRAGMENT_ACTIVITY)).values.single()
                val componentClasses = components.filter { c -> checks.any { it.definingClass == c.type } }
                val classes: List<ClassDef> = (handlers + listeners + componentClasses + edgeClass + activity + player + configClass +
                    speedUpClasses + speedClass + attachHolders + pillStates + pillClass + reelPillStates + reelPillClass + ExtensionDex.classDef(REEL_HOLD) +
                    ExtensionDex.classDef(FEED_VIDEO_HOLD) + ExtensionDex.classDef(SETTINGS_STATUS))
                    .distinctBy { it.type }
                // A guard-only build, as Keep the reel speed has without this patch: the listeners'
                // answers, the hold's start, the setter and the touch dispatch, and none of the rest.
                val guardOnly = PatchContexts.of(classes)
                reelLiftGuardPatch.execute(guardOnly)
                fun hooks(method: Method, hook: String) = guardOnly.mutableClassDefBy(method.definingClass).methods
                    .single { key(it) == key(method) }.code().count { it.call?.toString() == hook }
                assertTrue("$name: the guard leaves a release listener alone",
                    listeners.flatMap { it.methods }.filter { flagCalls(it, config).isNotEmpty() }.all { hooks(it, RELEASE) > 0 })
                assertEquals("$name: the guard hooks a long-press flag", 0,
                    handlers.flatMap { it.methods }.sumOf { hooks(it, LONG_PRESS) })
                assertEquals("$name: the guard hooks the overlay's flag", 0, hooks(checks.single(), SPEED_UP))
                assertEquals("$name: the guard hooks the edge check", 0, hooks(edge, ANYWHERE))
                assertEquals("$name: the guard measures the edge check's press", 0, hooks(edge, EDGE_TOUCH))
                assertEquals("$name: the guard hooks the hold speed", 0, hooks(holdSpeed, HOLD_SPEED))
                assertEquals("$name: the guard's setter hook", 1, hooks(setter, SPEED_SET))
                assertEquals("$name: the guard's touch hook", 1,
                    hooks(activity.methods.single { it.name == "dispatchTouchEvent" && it.implementation != null }, TOUCH))
                assertEquals("$name: the guard's hold starts", handlers.size,
                    handlers.flatMap { it.methods }.sumOf { hooks(it, HELD) })
                assertEquals("$name: the guard hooks the feed video touch", 0,
                    hooks(activity.methods.single { it.name == "dispatchTouchEvent" && it.implementation != null }, FEED_TOUCH))
                assertEquals("$name: the guard hooks the attach", 0, hooks(attach, FEED_ATTACHED))
                assertEquals("$name: the guard hooks the 2x pill", 0, hooks(pill, LOCK_HINT_PILL))
                assertEquals("$name: the guard hooks the Reels 2x pill", 0, hooks(reelPill, LOCK_HINT_REEL_PILL))

                // The patcher runs the guard first, as a dependency, then the patch.
                val context = PatchContexts.of(classes)
                reelLiftGuardPatch.execute(context)
                holdReelFor2xPatch.execute(context)

                fun patched(method: Method): List<Instruction> = context.mutableClassDefBy(method.definingClass).methods.single {
                    key(it) == key(method)
                }.code()

                // First in the pill's constructor, its state through LockHint and back into the same register.
                val pillCode = patched(pill)
                assertEquals("$name: the 2x pill's hook", LOCK_HINT_PILL, pillCode[0].call.toString())
                assertEquals("$name: the 2x pill hands over its state", listOf(pill.parameterRegisterNumber(0)), pillCode[0].registers())
                assertEquals("$name: the 2x pill takes its state back", pill.parameterRegisterNumber(0),
                    (pillCode[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the 2x pill casts its state back", Opcode.CHECK_CAST, pillCode[2].opcode)
                assertEquals("$name: the 2x pill's code after its hook", pill.code().map { it.opcode }, pillCode.drop(3).map { it.opcode })

                // First in the Reels pill's constructor, its state and hints flag through LockHint, the flag back.
                val reelPillCode = patched(reelPill)
                assertEquals("$name: the Reels 2x pill's hook", LOCK_HINT_REEL_PILL, reelPillCode[0].call.toString())
                assertEquals("$name: the Reels 2x pill hands over its state and hints flag",
                    listOf(reelPill.parameterRegisterNumber(1), reelPill.parameterRegisterNumber(3)), reelPillCode[0].registers())
                assertEquals("$name: the Reels 2x pill takes its flag back", reelPill.parameterRegisterNumber(3),
                    (reelPillCode[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the Reels 2x pill's code after its hook", reelPill.code().map { it.opcode },
                    reelPillCode.drop(2).map { it.opcode })

                // First in both pills' renders: a yes from the extension returns no component.
                listOf(pillClass, reelPillClass).forEach { component ->
                    val render = pillRender(component) ?: throw AssertionError("$name: ${component.type} has no render")
                    val before = render.code()
                    val after = patched(render)
                    assertEquals("$name: ${component.type} asks", HIDES_2X_PILL, after[0].call.toString())
                    assertEquals("$name: ${component.type} takes the answer", Opcode.MOVE_RESULT, after[1].opcode)
                    assertEquals("$name: ${component.type} branches", Opcode.IF_EQZ, after[2].opcode)
                    assertEquals("$name: ${component.type} answers nothing", Opcode.RETURN_OBJECT, after[4].opcode)
                    assertEquals("$name: ${component.type} draws as before past the hook",
                        before.map { it.opcode }, after.drop(5).map { it.opcode })
                }

                fun assertAnswers(method: Method, hook: String, flags: Set<String>) {
                    val before = method.code()
                    val after = patched(method)
                    val calls = flagCalls(method, config).filterValues { it in flags }.keys
                        .filter { answerTakenAt(method, it) != null }.sorted()
                    assertTrue("$name: ${key(method)} asks none of $flags", calls.isNotEmpty())
                    calls.forEachIndexed { hooked, call ->
                        val at = call + 2 * hooked
                        val register = (before[call + 1] as OneRegisterInstruction).registerA
                        assertEquals("$name: ${key(method)} lost its flag call", before[call].call.toString(), after[at].call.toString())
                        assertEquals("$name: ${key(method)} after the flag's answer", hook, after[at + 2].call.toString())
                        assertEquals("$name: ${key(method)} hands over another register", listOf(register), after[at + 2].registers())
                        assertEquals("$name: ${key(method)} takes the answer back", register,
                            (after[at + 3] as OneRegisterInstruction).registerA)
                        assertEquals("$name: ${key(method)} lost what came after the answer", before[call + 2].opcode, after[at + 4].opcode)
                    }
                    assertEquals("$name: ${key(method)} hooks", calls.size, after.count { it.call?.toString() == hook })
                }
                handlers.flatMap { it.methods }.filter { flagCalls(it, config).isNotEmpty() }
                    .forEach { assertAnswers(it, LONG_PRESS, setOf(speedUp)) }
                listeners.flatMap { it.methods }.filter { flagCalls(it, config).isNotEmpty() }
                    .forEach { assertAnswers(it, RELEASE, setOf(speedUp, release)) }
                assertAnswers(checks.single(), SPEED_UP, setOf(speedUp, release))

                // Each handler loads its log name once, only past the flag, and the hold starts right after.
                for (handler in handlers) {
                    val loading = handler.methods.filter { speedUpLoads(it).isNotEmpty() }
                    assertEquals("$name: ${handler.type}'s methods loading \"$SPEED_UP_LOG\"", 1, loading.size)
                    val method = loading.single()
                    val load = speedUpLoads(method).single()
                    val flag = flagCalls(method, config).filterValues { it == speedUp }.keys.filter { answerTakenAt(method, it) != null }
                    assertTrue("$name: ${key(method)} loads \"$SPEED_UP_LOG\" before it takes the speed-up flag's answer",
                        flag.isNotEmpty() && flag.all { it < load })
                    val before = method.code()
                    val after = patched(method)
                    val at = speedUpLoads(context.mutableClassDefBy(method.definingClass).methods.single { key(it) == key(method) }).single()
                    assertEquals("$name: ${key(method)} after its \"$SPEED_UP_LOG\" load", HELD, after[at + 1].call.toString())
                    assertEquals("$name: ${key(method)} hands the hold a register", emptyList<Int>(), after[at + 1].registers())
                    assertEquals("$name: ${key(method)} lost what came after the load", before[load + 1].opcode, after[at + 2].opcode)
                    assertEquals("$name: ${key(method)} holds", 1, after.count { it.call?.toString() == HELD })
                }

                val edgeBefore = edge.code()
                val edgeAfter = patched(edge)
                // Before each way the edge check's answer goes out (its returns, or 583's Boolean.valueOf of
                // it), all past the press call, the answer through the extension in its own register.
                assertEquals("$name: the edge check's answers", edgeCheck.answers.size, edgeAfter.count { it.call?.toString() == ANYWHERE })
                edgeCheck.answers.keys.sorted().forEachIndexed { hooked, index ->
                    val register = edgeCheck.answers.getValue(index)
                    val at = index + 1 + 2 * hooked
                    assertEquals("$name: before the edge check's answer at $index", ANYWHERE, edgeAfter[at].call.toString())
                    assertEquals("$name: the register the edge check hands over", listOf(register), edgeAfter[at].registers())
                    assertEquals("$name: the edge check takes the answer back", register, (edgeAfter[at + 1] as OneRegisterInstruction).registerA)
                    assertEquals("$name: the edge check's answer goes out as before", edgeBefore[index].opcode, edgeAfter[at + 2].opcode)
                }
                // Where the edge check has the press and the reel's view it measures in hand: first in a check
                // of its own, its first two parameters, named by the range form whatever their numbers.
                val entry = edgeCheck.entry
                assertTrue("$name: an answer goes out before the press call", edgeCheck.answers.keys.all { it > entry })
                assertEquals("$name: the edge check's press call", EDGE_TOUCH, edgeAfter[entry].call.toString())
                assertEquals("$name: the edge check's press call's form",
                    if (edgeCheck.view == edgeCheck.press + 1) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC, edgeAfter[entry].opcode)
                assertEquals("$name: the press and view the edge check hands over", listOf(edgeCheck.press, edgeCheck.view),
                    edgeAfter[entry].registers())
                if (entry == 0) {
                    assertEquals("$name: the edge check's parameters", listOf(edge.parameterRegisterNumber(0), edge.parameterRegisterNumber(1)),
                        listOf(edgeCheck.press, edgeCheck.view))
                }
                assertEquals("$name: the edge check's press calls", 1, edgeAfter.count { it.call?.toString() == EDGE_TOUCH })
                assertEquals("$name: the edge check lost what came after the press call", edgeBefore[entry].opcode, edgeAfter[entry + 1].opcode)
                assertEquals("$name: the edge check's code before the press call moved", edgeBefore.take(entry).map { it.opcode },
                    edgeAfter.take(entry).map { it.opcode })

                val speedBefore = holdSpeed.code()
                val speedAfter = patched(holdSpeed)
                val wideReturns = speedAfter.withIndex().filter { it.value.opcode == Opcode.RETURN_WIDE }
                assertEquals("$name: $speedRead's returns", speedBefore.count { it.opcode == Opcode.RETURN_WIDE }, wideReturns.size)
                for ((index, instruction) in wideReturns) {
                    val register = (instruction as OneRegisterInstruction).registerA
                    assertEquals("$name: before the hold speed's return at $index", HOLD_SPEED, speedAfter[index - 2].call.toString())
                    assertEquals("$name: the register pair the hold speed hands over", listOf(register, register + 1),
                        speedAfter[index - 2].registers())
                    assertEquals("$name: the hold speed takes the answer back", Opcode.MOVE_RESULT_WIDE, speedAfter[index - 1].opcode)
                    assertEquals("$name: the hold speed's answer register", register, (speedAfter[index - 1] as OneRegisterInstruction).registerA)
                }
                val setterAfter = patched(setter)
                val self = setter.localRegisterCount()
                assertEquals("$name: the speed setter's first call", SPEED_SET, setterAfter[0].call.toString())
                assertEquals("$name: the player and speed the setter hands over", listOf(self, self + 1), setterAfter[0].registers())
                assertEquals("$name: the setter takes the speed back", Opcode.MOVE_RESULT, setterAfter[1].opcode)
                assertEquals("$name: the register the setter's speed comes back to", self + 1,
                    (setterAfter[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the setter lost its own first instruction", setter.code()[0].opcode, setterAfter[2].opcode)

                val stubMethod = context.mutableClassDefBy(REEL_HOLD).methods.single { it.name == PLAYER_SPEED_STUB }
                val stub = stubMethod.code()
                assertEquals("$name: the speed stub's cast", player.type, ((stub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the speed stub's call", key(speedGetter), stub[1].call.toString())
                assertEquals("$name: the speed stub's register", listOf(stubMethod.localRegisterCount()), stub[1].registers())
                assertEquals("$name: the speed stub's answer", Opcode.RETURN, stub[3].opcode)

                // The feed video hold first, with the activity and the event, then the guard's, with the event.
                val dispatch = activity.methods.single { it.name == "dispatchTouchEvent" && it.implementation != null }
                val dispatchAfter = patched(dispatch)
                val dispatchSelf = dispatch.localRegisterCount()
                assertEquals("$name: the touch dispatch's first call", FEED_TOUCH, dispatchAfter[0].call.toString())
                assertEquals("$name: the activity and event the dispatch hands over", listOf(dispatchSelf, dispatchSelf + 1),
                    dispatchAfter[0].registers())
                assertEquals("$name: the touch dispatch's second call", TOUCH, dispatchAfter[1].call.toString())
                assertEquals("$name: the event the dispatch hands over", listOf(dispatchSelf + 1), dispatchAfter[1].registers())

                // The attach reads the container and the player into its first two locals and hands them over.
                val attachAfter = patched(attach)
                assertEquals("$name: the attach's first move", Opcode.MOVE_OBJECT_FROM16, attachAfter[0].opcode)
                assertEquals("$name: the container the attach reads", listOf(0, attach.parameterRegisterNumber(0)),
                    (attachAfter[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                assertEquals("$name: the attach's second move", Opcode.MOVE_OBJECT_FROM16, attachAfter[1].opcode)
                assertEquals("$name: the player the attach reads", listOf(1, attach.parameterRegisterNumber(playerParameter)),
                    (attachAfter[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                assertEquals("$name: the attach's call", FEED_ATTACHED, attachAfter[2].call.toString())
                assertEquals("$name: the registers the attach hands over", listOf(0, 1), attachAfter[2].registers())
                assertEquals("$name: the attach lost its own first instruction", attach.code()[0].opcode, attachAfter[3].opcode)
                assertEquals("$name: the attach's feed hooks", 1, attachAfter.count { it.call?.toString() == FEED_ATTACHED })

                val viewStub = context.mutableClassDefBy(FEED_VIDEO_HOLD).methods.single { it.name == PLAYER_VIEW_STUB }.code()
                assertEquals("$name: the view stub's cast", player.type, ((viewStub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the view stub's read", Opcode.IGET_OBJECT, viewStub[1].opcode)
                assertEquals("$name: the field the view stub reads", container.toString(),
                    ((viewStub[1] as ReferenceInstruction).reference as FieldReference).toString())
                assertEquals("$name: the view stub's answer", Opcode.RETURN_OBJECT, viewStub[2].opcode)
                val speedStubMethod = context.mutableClassDefBy(FEED_VIDEO_HOLD).methods.single { it.name == SET_PLAYER_SPEED_STUB }
                val speedStub = speedStubMethod.code()
                assertEquals("$name: the speed setter stub's cast", player.type,
                    ((speedStub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the speed setter stub's call", key(setter), speedStub[1].call.toString())
                assertEquals("$name: the speed setter stub's registers",
                    listOf(speedStubMethod.localRegisterCount(), speedStubMethod.localRegisterCount() + 1), speedStub[1].registers())
                assertEquals("$name: the speed setter stub's end", Opcode.RETURN_VOID, speedStub[2].opcode)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "reelHold" }
                assertEquals("$name: SettingsStatus.reelHold() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
