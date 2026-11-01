/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.pip

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.resume.safeSeeks
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPauses
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.media.taptoplay.innerPause
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.reels.VIDEO_COMPLETE_ANCHOR
import app.morphe.patches.facebook.reels.playerRead
import app.morphe.patches.facebook.reels.videoCompletes
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Picture-in-picture's anchors on every Facebook build the bundle declares: ReelsPipUtil, the one
 * class loading the Reels viewer's picture-in-picture surface, with its one (Activity) -> boolean
 * check asking for the phone's feature; and the Reels viewer's arming step, which calls that check,
 * with the surface gate it asks first. Then the patch on both: the extension first, its yes
 * returning true and anything else jumping to Facebook's own first instruction. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it. Also the hold's hooks: ReelsPipUtil's arming and
 * disarms, and FbGrootPlayer's pause and play; the helpers that hand the arming over and arm again,
 * the second in the shape the next reel's own aspect gives and not at all for a shape the window
 * doesn't take, and the stub answering what the second did; the runnable whose change is set aside; and the viewer's new view
 * id going through the extension. And the Watch viewer's flag: the one fragment holding the engagement-state
 * text, its onResume, and the MobileConfig read in front of its call of the check, answered through the
 * extension in the read's own register. And the Video tab's arming: the one method loading its
 * deep-dive surface's name that calls the check, its inlined copy of the gate (the gate's own
 * settings fetch and lambda cases) before the check, and the flag after the name whose no falls
 * into a disarm, both answered through the extension in their own registers. And the viewer
 * opening's hide of each view of the screen, heard by the extension first, and the one method that
 * shows them again, through the extension (#126).
 */
class PictureInPictureFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.shape() = parameterTypes.map(CharSequence::toString)

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    /** The index a branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        var address = 0
        val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
        return addresses.indexOf(addresses[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    @Test
    fun `each declared build has the check and the gate once, and the extension goes first in both`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val utils = FixtureDex.classesHolding(bundle, PIP_SURFACE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                assertEquals("$name: classes loading \"$PIP_SURFACE\"", 1, utils.size)
                val util = utils.single()
                val checks = util.methods.filter(::isPipCheck)
                assertEquals("$name: ${util.type}'s (Activity) -> boolean checks asking for the feature", 1, checks.size)
                val check = checks.single()

                val holders = FixtureDex.classesHolding(bundle, AI_STYLES_DRAFTS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val steps = holders.flatMap { holder -> holder.methods.filter { isArmingStep(it, check) } }
                assertEquals("$name: arming steps calling the check", 1, steps.size)
                val arming = steps.single()
                val call = surfaceGateCall(arming)
                assertNotNull("$name: ${arming.definingClass}->${arming.name} asks no gate first", call)
                val holder = holders.single { it.type == arming.definingClass }
                val gate = holder.methods.single {
                    it.name == call!!.name && it.shape() == call.parameterTypes.map(CharSequence::toString) && it.returnType == "Z"
                }
                assertTrue("$name: the gate isn't static", AccessFlags.STATIC.isSet(gate.accessFlags))
                val armingCode = arming.code()
                val gateAt = armingCode.indexOfFirst { it.call?.name == gate.name && it.call?.definingClass == gate.definingClass }
                val checkAt = armingCode.indexOfFirst { it.call?.name == check.name && it.call?.definingClass == check.definingClass }
                assertTrue("$name: the arming step asks the gate after the check", gateAt in 0 until checkAt)

                val owners = FixtureDex.classesHolding(bundle, GROOT_PLAY).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val plays = owners.flatMap(::grootPlays)
                assertEquals("$name: player plays", 1, plays.size)
                val play = plays.single()
                val owner = owners.single { it.type == play.definingClass }
                val pause = innerPause(grootPauses(owner, play.parameterTypes.single().toString()))
                assertNotNull("$name: the player's inner pause", pause)
                val armings = util.methods.filter { isArming(it, play.definingClass) }
                assertEquals("$name: ${util.type}'s armings for ${play.definingClass}", 1, armings.size)
                val disarms = util.methods.filter(::isDisarm)
                assertTrue("$name: ${util.type} has no disarm", disarms.isNotEmpty())
                val arm = armings.single()
                assertTrue("$name: ${arm.name} isn't static", AccessFlags.STATIC.isSet(arm.accessFlags))
                val videoParams = videoParamsCall(arming, arm, play.definingClass)
                assertNotNull("$name: ${arming.name} asks the player for none of ${arm.name}'s arguments", videoParams)
                val paramsClass = FixtureDex.classes(bundle, setOf(videoParams!!.call.returnType)).values.single()
                val aspect = aspectField(paramsClass)
                assertNotNull("$name: ${paramsClass.type} holds no one aspect double", aspect)
                val runnables = stateRunnables(util)
                assertEquals("$name: ${util.type}'s (Activity, boolean) runnables", 1, runnables.size)
                val runnable = FixtureDex.classes(bundle, runnables).values.single()
                val run = runnable.methods.single { isStateChange(it, util.type) }
                val viewers = FixtureDex.classesHolding(bundle, VIEWER_CONTAINER).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val openings = viewers.flatMap { viewer -> viewer.methods.filter(::isViewerOpening) }
                assertEquals("$name: viewer openings", 1, openings.size)
                val opening = openings.single()
                val topics = FixtureDex.classesHolding(bundle, TOPIC_FEED).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                assertEquals("$name: classes holding \"$TOPIC_FEED\"", 1, topics.size)
                val topic = topics.single()
                val resume = topic.methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" }
                val resumeAt = topicFlagAt(resume, check)
                assertTrue("$name: ${topic.type}->onResume has no flag in front of the check", resumeAt > 0)
                val originalResume = resume.code()
                val flagRegister = (originalResume[resumeAt] as OneRegisterInstruction).registerA
                assertTrue("$name: the flag's register v$flagRegister isn't a local of ${resume.localRegisterCount()}",
                    flagRegister < resume.localRegisterCount())
                assertTrue("$name: the flag read isn't a MobileConfig boolean read", isFlagRead(originalResume[resumeAt - 1]))
                val resumeCheckAt = originalResume.indexOfFirst {
                    it.call?.name == check.name && it.call?.definingClass == check.definingClass
                }
                assertTrue("$name: the flag read isn't the last one before the check",
                    (resumeAt until resumeCheckAt).none { isFlagRead(originalResume[it]) })

                val homeHolders = FixtureDex.classesHolding(bundle, VIDEO_TAB_SURFACE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val homes = homeHolders.flatMap { homeHolder -> homeHolder.methods.filter { isHome(it, check) } }
                assertEquals("$name: methods loading \"$VIDEO_TAB_SURFACE\" that call the check", 1, homes.size)
                val home = homes.single()
                val homeClass = homeHolders.single { it.type == home.definingClass }
                val originalHome = home.code()
                val copyAt = homeGateAt(home, gate, check)
                val homeFlag = homeFlagAt(home, disarms)
                val homeCheckAt = originalHome.indexOfFirst { it.call?.name == check.name && it.call?.definingClass == check.definingClass }
                assertTrue("$name: ${home.definingClass}->${home.name} has no copy of ${gate.name} before the check",
                    copyAt in 1 until homeCheckAt)
                assertTrue("$name: ${home.definingClass}->${home.name} has no flag after the check whose no disarms",
                    homeFlag > homeCheckAt)
                val gateFetch = settingsFetchAt(gate)
                assertTrue("$name: the gate fetches no settings", gateFetch > 0)
                val settings = gate.code()[gateFetch].call.toString()
                val gateCases = gateRead(gate.code(), gateFetch).second
                assertTrue("$name: the gate loads no lambda cases", gateCases.size >= 2)
                val copyFetch = (copyAt downTo 0).first { originalHome[it].call?.toString() == settings }
                assertEquals("$name: the copy loads the gate's lambda cases", copyAt - 1 to gateCases, gateRead(originalHome, copyFetch))
                assertTrue("$name: the flag read isn't a MobileConfig boolean read", isFlagRead(originalHome[homeFlag - 1]))
                val homeGateRegister = (originalHome[copyAt] as OneRegisterInstruction).registerA
                val homeFlagRegister = (originalHome[homeFlag] as OneRegisterInstruction).registerA
                assertTrue("$name: the copy's answer v$homeGateRegister isn't a local of ${home.localRegisterCount()}",
                    homeGateRegister < home.localRegisterCount())
                assertTrue("$name: the flag's answer v$homeFlagRegister isn't a local of ${home.localRegisterCount()}",
                    homeFlagRegister < home.localRegisterCount())
                val fallsInto = originalHome[homeFlag + 2].call
                assertTrue("$name: a no from the flag doesn't fall into a disarm",
                    disarms.any { it.definingClass == fallsInto?.definingClass && it.name == fallsInto.name })

                val originalCheck = check.code()
                val originalGate = gate.code()
                val originalPlay = play.code()
                val originalRun = run.code()
                val originalOpening = opening.code()

                // #126: the viewer hides each view of the screen as it opens, and one method shows them again.
                val hidden = hideAt(originalOpening)
                val newViewId = originalOpening.indexOfFirst { it.call?.name == "generateViewId" }
                assertTrue("$name: the viewer opening hides no view of the screen after its new view id", hidden > newViewId)
                val branchesTo = originalOpening.indices.filter { originalOpening[it] is OffsetInstruction }.map { originalOpening.target(it) }
                assertTrue("$name: a branch lands on the viewer's hide, past the extension", hidden !in branchesTo)
                val showers = viewers.flatMap { viewer -> viewer.methods.filter { !isViewerOpening(it) && showAt(it.code()) >= 0 } }
                assertEquals("$name: methods showing the screen again", 1, showers.size)
                val shower = showers.single()
                val originalShower = shower.code()
                val shownAt = showAt(originalShower)
                val shownView = originalShower[shownAt] as FiveRegisterInstruction
                assertTrue("$name: the show takes its view or visibility from past v15", shownView.registerC <= 15 && shownView.registerD <= 15)

                val endHolders = FixtureDex.classesHolding(bundle, VIDEO_COMPLETE_ANCHOR).filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
                val end = videoCompletes(endHolders).single()
                val listener = endHolders.single { it.type == end.definingClass }
                val endRead = playerRead(listener, end)
                assertNotNull("$name: ${listener.type} keeps no single player that its end-of-video step reads", endRead)

                val context = PatchContexts.of((listOf(util, holder, owner, runnable, topic, homeClass, paramsClass, listener) + viewers + listOf(
                    ExtensionDex.classDef(PICTURE_IN_PICTURE), ExtensionDex.classDef(SETTINGS_STATUS))).distinctBy { it.type })
                pictureInPicturePatch.execute(context)

                val patched = context.mutableClassDefBy(check.definingClass).methods.single {
                    it.name == check.name && isPipCheck(it)
                }.code()
                val first = patched[0]
                assertEquals("$name: the check's first instruction", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                assertEquals("$name: the check's first call", ALLOWED, first.call.toString())
                val activity = check.localRegisterCount() + if (AccessFlags.STATIC.isSet(check.accessFlags)) 0 else 1
                assertEquals("$name: the register handed over is the activity", activity, (first as RegisterRangeInstruction).startRegister)
                assertEquals("$name: one register handed over", 1, first.registerCount)
                assertEquals("$name: the yes branch", Opcode.IF_EQZ, patched[2].opcode)
                assertEquals("$name: the yes answers true", 1, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the yes returns", Opcode.RETURN, patched[4].opcode)
                // #252, #256: with the switch off, the check says no to Facebook's own window too.
                assertEquals("$name: a no asks whether the switch refuses", 5, patched.target(2))
                assertEquals("$name: the refusal's call", REFUSED, patched[5].call.toString())
                assertEquals("$name: the refusal's branch", Opcode.IF_EQZ, patched[7].opcode)
                assertEquals("$name: a refusal answers false", 0, (patched[8] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: a refusal returns", Opcode.RETURN, patched[9].opcode)
                assertEquals("$name: neither runs Facebook's first instruction", 10, patched.target(7))
                assertEquals("$name: Facebook's first instruction moved", originalCheck[0].opcode, patched[10].opcode)
                assertEquals("$name: the rest of Facebook's check", originalCheck.size + 10, patched.size)

                val gated = context.mutableClassDefBy(gate.definingClass).methods.single {
                    it.name == gate.name && it.shape() == gate.shape() && it.returnType == "Z"
                }.code()
                assertEquals("$name: the gate's first call", SURFACE_ALLOWED, gated[0].call.toString())
                assertEquals("$name: the gate's yes branch", Opcode.IF_EQZ, gated[2].opcode)
                assertEquals("$name: a no runs Facebook's gate", 4, gated.target(2))
                assertEquals("$name: the gate's yes returns", Opcode.RETURN, gated[3].opcode)
                assertEquals("$name: Facebook's first instruction of the gate moved", originalGate[0].opcode, gated[4].opcode)
                assertEquals("$name: the rest of Facebook's gate", originalGate.size + 4, gated.size)

                fun patchedOf(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.shape() == method.shape() && it.returnType == method.returnType
                }
                val armed = patchedOf(arm).code()
                val parameters = arm.shape().joinToString("")
                val handOver = armed[0] as RegisterRangeInstruction
                assertEquals("$name: the arming's first call", "${util.type}->$ARMING_HELPER($parameters)V", armed[0].call.toString())
                assertEquals("$name: the arming hands over all its arguments", listOf(arm.localRegisterCount(), arm.shape().size),
                    listOf(handOver.startRegister, handOver.registerCount))
                assertEquals("$name: the arming's own code follows", arm.code().size + 1, armed.size)
                val helpers = context.mutableClassDefBy(util.type).methods
                val capture = helpers.single { it.name == ARMING_HELPER }
                assertEquals("$name: $ARMING_HELPER takes the arming's arguments", arm.shape(), capture.shape())
                assertTrue("$name: $ARMING_HELPER isn't static", AccessFlags.STATIC.isSet(capture.accessFlags))
                assertTrue("$name: $ARMING_HELPER doesn't hand them to the extension", capture.code().any { it.call.toString() == ARMED })
                val replay = helpers.single { it.name == REARM_HELPER }
                assertEquals("$name: $REARM_HELPER answers whether it armed", "Z", replay.returnType)
                val replayCode = replay.code()
                val replayCalls = replayCode.mapNotNull { it.call?.toString() }
                assertEquals("$name: $REARM_HELPER's last calls: the video, its shape, the arming",
                    listOf(videoParams.call.toString(), SHAPE, "${util.type}->${arm.name}($parameters)V"), replayCalls.takeLast(3))
                // #126: the shape comes from the next reel's own aspect, and a shape the window doesn't take arms nothing.
                val aspectRead = replayCode.indexOfFirst { it.opcode == Opcode.IGET_WIDE }
                assertEquals("$name: the aspect read", aspect.toString(), (replayCode[aspectRead] as ReferenceInstruction).reference.toString())
                val shapeAt = replayCode.indexOfFirst { it.call?.toString() == SHAPE }
                val rationalAt = arm.shape().indexOf(RATIONAL)
                assertEquals("$name: the shape lands in the arming's shape argument", rationalAt,
                    (replayCode[shapeAt + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: a null shape skips the arming", Opcode.IF_EQZ, replayCode[shapeAt + 2].opcode)
                val armingAt = replayCode.indexOfLast { it.call?.toString() == "${util.type}->${arm.name}($parameters)V" }
                val skipTo = replayCode.target(shapeAt + 2)
                assertTrue("$name: the skip lands past the arming", skipTo > armingAt)
                assertEquals("$name: the skip answers false", 0, (replayCode[skipTo] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the arming answers true", 1, (replayCode[armingAt + 1] as NarrowLiteralInstruction).narrowLiteral)
                assertTrue("$name: the captured shape is never read back",
                    replayCode.none { it.opcode == Opcode.CHECK_CAST && (it as ReferenceInstruction).reference.toString() == RATIONAL })
                val stub = context.mutableClassDefBy(PICTURE_IN_PICTURE).methods.single { it.name == REARM_STUB }.code()
                assertEquals("$name: the stub's first call", "${util.type}->$REARM_HELPER([Ljava/lang/Object;Ljava/lang/Object;)Z",
                    stub[0].call.toString())
                assertEquals("$name: the stub takes the helper's answer", Opcode.MOVE_RESULT, stub[1].opcode)
                assertEquals("$name: the stub returns it", Opcode.RETURN, stub[2].opcode)
                assertEquals("$name: the stub returns what it took", (stub[1] as OneRegisterInstruction).registerA,
                    (stub[2] as OneRegisterInstruction).registerA)
                // #126: the view stub reads the one public ViewGroup the player's attach writes.
                val seek = safeSeeks(owner, play.parameterTypes.single().toString()).single()
                val viewField = context.playerViewField(Player(play, pause!!, seek))
                assertEquals("$name: the view field's owner", play.definingClass, viewField.definingClass)
                val viewStub = context.mutableClassDefBy(PICTURE_IN_PICTURE).methods.single { it.name == PLAYER_VIEW_STUB }.code()
                assertEquals("$name: the view stub's shape", listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT),
                    viewStub.take(3).map { it.opcode })
                assertEquals("$name: the view stub casts to the player", play.definingClass,
                    (viewStub[0] as ReferenceInstruction).reference.toString())
                assertEquals("$name: the view stub reads the attached view", viewField.toString(),
                    (viewStub[1] as ReferenceInstruction).reference.toString())
                for (disarm in disarms) {
                    assertEquals("$name: ${disarm.name}'s first call", DISARMED, patchedOf(disarm).code()[0].call.toString())
                }
                val paused = patchedOf(pause!!).code()[0]
                assertEquals("$name: the pause's first call", PLAYER_PAUSED, paused.call.toString())
                assertEquals("$name: the pause hands over this", pause.localRegisterCount(), (paused as RegisterRangeInstruction).startRegister)
                val played = patchedOf(play).code()
                val trace = originalPlay.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == GROOT_PLAY }
                assertEquals("$name: the play's call after its trace", PLAYER_PLAYING, played[trace + 1].call.toString())
                assertEquals("$name: the play hands over this", play.localRegisterCount(),
                    (played[trace + 1] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the play's own code around it", originalPlay.size + 1, played.size)
                val seeked = patchedOf(seek).code()
                assertEquals("$name: the seek's first call", KEEP_PLACE, seeked[0].call.toString())
                assertEquals("$name: the seek hands over its trigger, player and place", seek.localRegisterCount(),
                    (seeked[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the seek's yes branch", Opcode.IF_EQZ, seeked[2].opcode)
                assertEquals("$name: a no runs Facebook's seek", 4, seeked.target(2))
                assertEquals("$name: a yes drops the seek", Opcode.RETURN_VOID, seeked[3].opcode)
                // #44: a reel's loop is the same seek, and the end-of-video step says it's coming.
                assertEquals("$name: the end step reads the player", play.definingClass, endRead!!.field.type)
                val ending = patchedOf(end).code()
                assertEquals("$name: the end step's call after its read", PLAYER_ENDED, ending[endRead.at + 1].call.toString())
                assertEquals("$name: the end step hands over its player", endRead.register,
                    (ending[endRead.at + 1] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the end step's own code around it", end.code().size + 1, ending.size)

                val ran = patchedOf(run).code()
                assertEquals("$name: the runnable's first call", SET_ASIDE, ran[0].call.toString())
                assertEquals("$name: the runnable's yes branch", Opcode.IF_EQZ, ran[2].opcode)
                assertEquals("$name: a no runs Facebook's runnable", 4, ran.target(2))
                assertEquals("$name: a yes sets nothing", Opcode.RETURN_VOID, ran[3].opcode)
                assertEquals("$name: the rest of Facebook's runnable", originalRun.size + 4, ran.size)

                val opened = patchedOf(opening).code()
                val newId = newViewId
                val idRegister = (originalOpening[newId + 1] as OneRegisterInstruction).registerA
                val swap = opened[newId + 2] as FiveRegisterInstruction
                assertEquals("$name: the call after the new view id", VIEWER_ID, opened[newId + 2].call.toString())
                assertEquals("$name: the activity and the id handed over", listOf(opening.localRegisterCount(), idRegister),
                    listOf(swap.registerC, swap.registerD))
                assertEquals("$name: the answer is the id", idRegister, (opened[newId + 3] as OneRegisterInstruction).registerA)
                // The hide sits two instructions further down, after the view id's.
                val heard = opened[hidden + 2] as RegisterRangeInstruction
                assertEquals("$name: the call before the hide", HIDING, opened[hidden + 2].call.toString())
                assertEquals("$name: the view handed over is the one hidden",
                    listOf((originalOpening[hidden] as FiveRegisterInstruction).registerC, 1), listOf(heard.startRegister, heard.registerCount))
                assertEquals("$name: Facebook's hide follows", originalOpening[hidden].call.toString(), opened[hidden + 3].call.toString())
                assertEquals("$name: the rest of Facebook's opening", originalOpening.size + 3, opened.size)

                val shown = patchedOf(shower).code()
                val showing = shown[shownAt] as FiveRegisterInstruction
                assertEquals("$name: the show goes to the extension", SHOWN_AGAIN, shown[shownAt].call.toString())
                assertEquals("$name: as a static call", Opcode.INVOKE_STATIC, shown[shownAt].opcode)
                assertEquals("$name: the view and visibility handed over", listOf(shownView.registerC, shownView.registerD),
                    listOf(showing.registerC, showing.registerD))
                assertEquals("$name: the rest of Facebook's handler", originalShower.size, shown.size)

                val resumed = patchedOf(resume).code()
                val swapped = resumed[resumeAt + 1] as RegisterRangeInstruction
                assertEquals("$name: the call after the flag", IMMERSIVE_ALLOWED, resumed[resumeAt + 1].call.toString())
                assertEquals("$name: the flag's answer is handed over by range", Opcode.INVOKE_STATIC_RANGE, resumed[resumeAt + 1].opcode)
                assertEquals("$name: the register handed over is the flag's", listOf(flagRegister, 1),
                    listOf(swapped.startRegister, swapped.registerCount))
                assertEquals("$name: the answer goes back in the flag's register", Opcode.MOVE_RESULT, resumed[resumeAt + 2].opcode)
                assertEquals("$name: the answer is the flag's register", flagRegister, (resumed[resumeAt + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the branch follows the answer", Opcode.IF_EQZ, resumed[resumeAt + 3].opcode)
                assertEquals("$name: the rest of Facebook's onResume", originalResume.size + 2, resumed.size)

                // The copy of the gate first, then the flag two instructions further down than before.
                val homed = patchedOf(home).code()
                for ((at, hook, register) in listOf(
                    Triple(copyAt, HOME_GATE_ALLOWED, homeGateRegister),
                    Triple(homeFlag + 2, HOME_FLAG_ALLOWED, homeFlagRegister),
                )) {
                    val handOver = homed[at + 1] as RegisterRangeInstruction
                    assertEquals("$name: the call after the answer at $at", hook, homed[at + 1].call.toString())
                    assertEquals("$name: the answer at $at is handed over by range", Opcode.INVOKE_STATIC_RANGE, homed[at + 1].opcode)
                    assertEquals("$name: the register handed over is the answer's", listOf(register, 1),
                        listOf(handOver.startRegister, handOver.registerCount))
                    assertEquals("$name: the extension's answer goes back", Opcode.MOVE_RESULT, homed[at + 2].opcode)
                    assertEquals("$name: in the answer's own register", register, (homed[at + 2] as OneRegisterInstruction).registerA)
                }
                assertEquals("$name: Facebook's branch on the copy follows", originalHome[copyAt + 1].opcode, homed[copyAt + 3].opcode)
                assertEquals("$name: Facebook's branch on the flag follows", Opcode.IF_NEZ, homed[homeFlag + 5].opcode)
                assertEquals("$name: then its disarm", fallsInto.toString(), homed[homeFlag + 6].call.toString())
                assertEquals("$name: the rest of the Video tab's arming", originalHome.size + 4, homed.size)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "pictureInPicture" }
                assertEquals("$name: SettingsStatus.pictureInPicture() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                println("$name: check ${check.definingClass}->${check.name}, gate ${gate.definingClass}->${gate.name} " +
                    "asked by ${arming.name}, armed in ${arm.name}, disarmed in ${disarms.joinToString { it.name }}, " +
                    "player ${play.definingClass}->${play.name}/${pause.name}, video ${videoParams.call.name}, " +
                    "set aside in ${runnable.type}, viewer ${opening.definingClass}->${opening.name}, " +
                    "watch viewer ${topic.type}->onResume flag at $resumeAt in v$flagRegister of ${resume.localRegisterCount()} locals, " +
                    "video tab ${home.definingClass}->${home.name} gate copy (cases $gateCases) at $copyAt in v$homeGateRegister " +
                    "and flag at $homeFlag in v$homeFlagRegister of ${home.localRegisterCount()} locals, " +
                    "screen hidden at $hidden, shown again in ${shower.definingClass}->${shower.name} at $shownAt")
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
