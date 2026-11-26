/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.feedsound

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.layout.theme.TAB_BAR_CONTAINER
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.navigation.bottomtabbar.tabRestyleSite
import app.morphe.patches.facebook.reels.SOUND_TOGGLE_STRING
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Keep feed videos muted on each declared Facebook build: the feed sound state's session setter
 * asks the extension for its flag before anything reads it, and its reasoned setter asks right
 * after reading the flag the state holds, each answer going back in the flag's own register, with
 * the rest of both methods as Facebook wrote it, and then telling soundSet the state, the reason
 * and that answer (#465). The story viewer's volume setter asks for each
 * story's volume right before it builds the request that carries it (#261). The opened video's
 * close step asks for the time it played with sound right after taking it (#350).
 */
class KeepFeedVideosMutedFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun same(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
        a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build's feed sound setters ask before the flag is used`() = bundles { bundle ->
        val starts = FixtureDex.methodsWhere(bundle, { true }, ::startsSession)
        // Probed on 583 (2026-10-21): one sound state, whose session start hands a flag to EkI.
        assertEquals("${bundle.name}: classes starting a sound session", 1, starts.size)
        val type = starts.single().definingClass
        val owners = FixtureDex.classes(bundle, setOf(type))
        val context = PatchContexts.of(owners.values)
        val state = context.feedSoundState()
        assertEquals("${bundle.name}: the sound state", type, state.owner)
        assertEquals("${bundle.name}: the sound flag's type", "Z", state.field.type)

        val change = owners.getValue(type).methods.single { same(it, state.change) }
        val changeCode = change.implementation!!.instructions.toList()
        val session = owners.getValue(type).methods.single { same(it, state.session) }
        val sessionCode = session.implementation!!.instructions.toList()
        context.askFeedSound(state)
        val mutable = context.mutableClassDefBy(type)

        val where = "${bundle.name}: $type->${change.name}"
        val body = mutable.methods.single { same(it, change) }.implementation!!.instructions.toList()
        assertEquals("$where: three instructions added", changeCode.size + 3, body.size)
        val at = state.askAt
        // 585 bumps a counter before the read, so the read is not always first; the ask sits right after it.
        assertEquals("$where: the read stays right before the ask", Opcode.IGET_BOOLEAN, body[at - 1].opcode)
        assertEquals("$where: the read is of the sound flag", state.field.toString(), (body[at - 1] as ReferenceInstruction).reference.toString())
        assertEquals("$where: the read lands in the current flag's register", state.current, (body[at - 1] as OneRegisterInstruction).registerA)
        val ask = body[at]
        assertEquals("$where: the ask's form", Opcode.INVOKE_STATIC, ask.opcode)
        assertEquals("$where: the ask", OWN_SOUND_CHANGE, (ask as ReferenceInstruction).reference.toString())
        val asked = ask as FiveRegisterInstruction
        assertEquals("$where: the ask's registers", listOf(state.reason, state.changeFlag, state.current),
            listOf(asked.registerC, asked.registerD, asked.registerE))
        val answer = body[at + 1]
        assertEquals("$where: the answer's form", Opcode.MOVE_RESULT, answer.opcode)
        assertEquals("$where: the answer's register", state.changeFlag, (answer as OneRegisterInstruction).registerA)
        // #465: the state, the reason and the answer go to soundSet, the state being what the read read from.
        val told = body[at + 2]
        assertEquals("$where: the state's form", Opcode.INVOKE_STATIC, told.opcode)
        assertEquals("$where: the state", OWN_SOUND_SET, (told as ReferenceInstruction).reference.toString())
        val toldRegisters = told as FiveRegisterInstruction
        assertEquals("$where: soundSet's registers", listOf((body[at - 1] as TwoRegisterInstruction).registerB, state.reason, state.changeFlag),
            listOf(toldRegisters.registerC, toldRegisters.registerD, toldRegisters.registerE))
        for (index in changeCode.indices) {
            val patchedIndex = if (index < at) index else index + 3
            assertEquals("$where: instruction $index", changeCode[index].opcode, body[patchedIndex].opcode)
        }

        val sessionWhere = "${bundle.name}: $type->${session.name}"
        val sessionBody = mutable.methods.single { same(it, session) }.implementation!!.instructions.toList()
        assertEquals("$sessionWhere: three instructions added", sessionCode.size + 3, sessionBody.size)
        val sessionAsk = sessionBody[0]
        assertEquals("$sessionWhere: the ask's form", Opcode.INVOKE_STATIC_RANGE, sessionAsk.opcode)
        assertEquals("$sessionWhere: the ask", OWN_SESSION_SOUND, (sessionAsk as ReferenceInstruction).reference.toString())
        assertEquals("$sessionWhere: the ask reads the flag", state.sessionFlag, (sessionAsk as RegisterRangeInstruction).startRegister)
        assertEquals("$sessionWhere: the ask reads one register", 1, sessionAsk.registerCount)
        val sessionAnswer = sessionBody[1]
        assertEquals("$sessionWhere: the answer's form", Opcode.MOVE_RESULT, sessionAnswer.opcode)
        assertEquals("$sessionWhere: the answer's register", state.sessionFlag, (sessionAnswer as OneRegisterInstruction).registerA)
        val sessionTold = sessionBody[2]
        assertEquals("$sessionWhere: the state's form", Opcode.INVOKE_STATIC_RANGE, sessionTold.opcode)
        assertEquals("$sessionWhere: the state", OWN_SOUND_SET, (sessionTold as ReferenceInstruction).reference.toString())
        // The setter's own, its reason and its flag: its three parameters, the last registers it has.
        assertEquals("$sessionWhere: soundSet starts at the setter's own", session.implementation!!.registerCount - 3,
            (sessionTold as RegisterRangeInstruction).startRegister)
        assertEquals("$sessionWhere: soundSet reads three registers", 3, sessionTold.registerCount)
        for (at in sessionCode.indices) assertEquals("$sessionWhere: instruction $at", sessionCode[at].opcode, sessionBody[at + 3].opcode)
    }

    @Test
    fun `each declared build's story volume setter asks before building its request`() = bundles { bundle ->
        val owners = FixtureDex.classesHolding(bundle, STORY_AUDIBLE)
        // Probed on 585 (2026-10-31): StoryViewerVideoComponentSpec's static setter, 0Cb4 on arm64 and 0Cb7 on v7.
        assertEquals("${bundle.name}: classes holding $STORY_AUDIBLE", 1, owners.size)
        val context = PatchContexts.of(owners)
        val volume = context.storyVolume()
        val type = owners.single().type
        assertEquals("${bundle.name}: the story viewer", type, volume.owner)
        val code = volume.method.implementation!!.instructions.toList()
        assertEquals("${bundle.name}: the request's build", Opcode.NEW_INSTANCE, code[volume.askAt].opcode)
        assertEquals("${bundle.name}: the player call", Opcode.INVOKE_VIRTUAL, code[volume.askAt + 2].opcode)
        assertEquals("${bundle.name}: the volume rides in the call", volume.volume,
            (code[volume.askAt + 2] as FiveRegisterInstruction).registerE)

        context.askStorySound(volume)
        val where = "${bundle.name}: $type->${volume.method.name}"
        val body = context.mutableClassDefBy(type).methods.single { same(it, volume.method) }.implementation!!.instructions.toList()
        assertEquals("$where: two instructions added", code.size + 2, body.size)
        val ask = body[volume.askAt]
        assertEquals("$where: the ask's form", Opcode.INVOKE_STATIC, ask.opcode)
        assertEquals("$where: the ask", OWN_STORY_VOLUME, (ask as ReferenceInstruction).reference.toString())
        assertEquals("$where: the ask reads one register", 1, (ask as FiveRegisterInstruction).registerCount)
        assertEquals("$where: the ask reads the volume", volume.volume, ask.registerC)
        val answer = body[volume.askAt + 1]
        assertEquals("$where: the answer's form", Opcode.MOVE_RESULT, answer.opcode)
        assertEquals("$where: the answer's register", volume.volume, (answer as OneRegisterInstruction).registerA)
        for (index in code.indices) {
            val patchedIndex = if (index < volume.askAt) index else index + 2
            assertEquals("$where: instruction $index", code[index].opcode, body[patchedIndex].opcode)
        }
    }

    @Test
    fun `each declared build's tab bar restyle tells FeedSound the tab picked first thing`() = bundles { bundle ->
        // #593: the Reels tab's own sound goes through, and the next tab picked mutes it again.
        val restyles = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == TAB_BAR_CONTAINER } }) {
            tabRestyleSite(it) != null
        }
        assertEquals("${bundle.name}: tab bar restyles", 1, restyles.size)
        val type = restyles.single().definingClass
        val context = PatchContexts.of(FixtureDex.classes(bundle, setOf(type)).values)
        val site = context.feedSoundTabSite()
        assertEquals("${bundle.name}: the restyle's class", type, site.method.definingClass)
        assertEquals("${bundle.name}: the tab is the TabTag parameter", site.method.implementation!!.registerCount - 2, site.tab)
        val code = site.method.implementation!!.instructions.toList()

        context.tellTabShown(site)
        val where = "${bundle.name}: $type->${site.method.name}"
        val body = context.mutableClassDefBy(type).methods.single { same(it, site.method) }.implementation!!.instructions.toList()
        assertEquals("$where: one instruction added", code.size + 1, body.size)
        val tell = body[0]
        assertEquals("$where: the call's form", Opcode.INVOKE_STATIC_RANGE, tell.opcode)
        assertEquals("$where: the call", OWN_TAB_SHOWN, (tell as ReferenceInstruction).reference.toString())
        assertEquals("$where: the call reads the tab", site.tab, (tell as RegisterRangeInstruction).startRegister)
        assertEquals("$where: the call reads one register", 1, tell.registerCount)
        val jumps = setOf(Opcode.GOTO_16, Opcode.GOTO_32)
        for (index in code.indices) {
            val before = code[index].opcode.let { if (it in jumps) Opcode.GOTO else it }
            val after = body[index + 1].opcode.let { if (it in jumps) Opcode.GOTO else it }
            assertEquals("$where: instruction $index", before, after)
        }
    }

    @Test
    fun `each declared build's speaker button listener reads the flag as it's made`() = bundles { bundle ->
        // #593, probed on 588 (2026-11-26): the speaker button's listener is LX/062B;, whose K1m
        // reads the flag and never its reason. LX/0e8K; reads the flag too, but checks its reason, and
        // LX/0SJl;, LX/0SJm; (the feed's inline toggle) and LX/12St; read it the same way but aren't
        // handed the unified sound toggle, LX/061s;, as they're made.
        val owner = FixtureDex.methodsWhere(bundle, { true }, ::startsSession).single().definingClass
        val ownerClass = FixtureDex.classes(bundle, setOf(owner)).getValue(owner)
        val listeners = ownerClass.methods.filter { it.parameterTypes.size == 1 }
            .map { it.parameterTypes.single().toString() }.filter { it.startsWith("L") }.toSet()
        val types = (listeners + owner).toMutableSet()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) if (classDef.interfaces.any(listeners::contains)) types += classDef.type
        }
        val toggles = FixtureDex.classesHolding(bundle, SOUND_TOGGLE_STRING)
        val context = PatchContexts.of(FixtureDex.classes(bundle, types).values + toggles)
        val state = context.feedSoundState()
        val speaker = context.speakerListener(state)
        val where = "${bundle.name}: ${speaker.type}"
        val reason = state.change.parameterTypes[0].toString()
        assertEquals("$where: the callback takes the reason", listOf(reason), speaker.callback.parameterTypes.map(CharSequence::toString))
        val before = speaker.inits.associateWith { it.implementation!!.instructions.toList() }

        context.speakerCatchesUp(speaker)
        val mutable = context.mutableClassDefBy(speaker.type)
        for ((init, code) in before) {
            val body = mutable.methods.single { same(it, init) }.implementation!!.instructions.toList()
            val returns = code.count { it.opcode == Opcode.RETURN_VOID }
            assertEquals("$where: five instructions before each return", code.size + 5 * returns, body.size)
            val at = body.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC && (it as ReferenceInstruction).reference.toString() == OWN_SPEAKER_CATCHES_UP
            }
            assertEquals("$where: the code before the ask", code.take(at).map { it.opcode }, body.take(at).map { it.opcode })
            val answer = (body[at + 1] as OneRegisterInstruction).registerA
            assertEquals("$where: the answer", Opcode.MOVE_RESULT, body[at + 1].opcode)
            val skip = body[at + 2]
            assertEquals("$where: a no skips the callback", Opcode.IF_EQZ, skip.opcode)
            assertEquals("$where: the skip reads the answer", answer, (skip as OneRegisterInstruction).registerA)
            assertEquals("$where: the skip lands on the return", 6, (skip as OffsetInstruction).codeOffset)
            val none = body[at + 3]
            assertEquals("$where: no reason", Opcode.CONST_4, none.opcode)
            assertEquals("$where: no reason's register", answer, (none as OneRegisterInstruction).registerA)
            assertEquals("$where: no reason is null", 0, (none as NarrowLiteralInstruction).narrowLiteral)
            val call = body[at + 4] as FiveRegisterInstruction
            assertEquals("$where: the callback's form", Opcode.INVOKE_VIRTUAL, body[at + 4].opcode)
            assertEquals("$where: the callback", "${speaker.type}->${speaker.callback.name}($reason)V",
                (call as ReferenceInstruction).reference.toString())
            assertEquals("$where: the callback's arguments", listOf(init.localRegisterCount(), answer),
                listOf(call.registerC, call.registerD))
            assertEquals("$where: the callback's argument count", 2, call.registerCount)
            assertEquals("$where: then the return", Opcode.RETURN_VOID, body[at + 5].opcode)
        }
    }

    @Test
    fun `each declared build's opened video close asks right after taking the time with sound`() = bundles { bundle ->
        val owners = FixtureDex.classesHolding(bundle, SOUND_ON_WHEN_EXIT)
        // Probed on 585 (2026-11-02): WarionSoundStateManager's restore step, 0BWo.A0W on arm64 and
        // 0BWr.A0W on v7, and a table of marker point names, 0D5Y.A0W and 0D5b.A0W, that the finder skips.
        // 586's Android 9 build (2026-11-10) has the table, 07hI.A00, and a lambda, 0A5C.A00, and its
        // restore step, 0BT9.A0k, asks the table for the name.
        assertEquals("${bundle.name}: classes holding $SOUND_ON_WHEN_EXIT", 2, owners.size)
        val tables = owners.associateBy { it.type }
        val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        val restoring = FixtureDex.classesHolding(bundle, RESTORE_STEP)
        val steps = restoring.filter { owner ->
            owner.methods.any { holdsString(it, RESTORE_STEP) && namesString(it, SOUND_ON_WHEN_EXIT, resolve) }
        }
        assertEquals("${bundle.name}: classes holding $RESTORE_STEP and naming $SOUND_ON_WHEN_EXIT", 1, steps.size)
        val context = PatchContexts.of((owners + restoring).distinctBy { it.type })
        val close = context.viewerExitSound()
        val type = steps.single().type
        assertEquals("${bundle.name}: the close step's class", type, close.owner)
        val code = close.method.implementation!!.instructions.toList()
        assertEquals("${bundle.name}: the time is kept right before the ask", Opcode.MOVE_RESULT_WIDE, code[close.askAt - 1].opcode)
        assertEquals("${bundle.name}: the time's register", close.time, (code[close.askAt - 1] as OneRegisterInstruction).registerA)

        context.askViewerSoundTime(close)
        val where = "${bundle.name}: $type->${close.method.name}"
        val body = context.mutableClassDefBy(type).methods.single { same(it, close.method) }.implementation!!.instructions.toList()
        assertEquals("$where: two instructions added", code.size + 2, body.size)
        val ask = body[close.askAt]
        assertEquals("$where: the ask's form", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
        assertEquals("$where: the ask", OWN_VIEWER_SOUND_TIME, (ask as ReferenceInstruction).reference.toString())
        assertEquals("$where: the ask reads the time", close.time, (ask as RegisterRangeInstruction).startRegister)
        assertEquals("$where: the ask reads one wide value", 2, ask.registerCount)
        val answer = body[close.askAt + 1]
        assertEquals("$where: the answer's form", Opcode.MOVE_RESULT_WIDE, answer.opcode)
        assertEquals("$where: the answer's register", close.time, (answer as OneRegisterInstruction).registerA)
        for (index in code.indices) {
            val patchedIndex = if (index < close.askAt) index else index + 2
            assertEquals("$where: instruction $index", code[index].opcode, body[patchedIndex].opcode)
        }
    }
}
