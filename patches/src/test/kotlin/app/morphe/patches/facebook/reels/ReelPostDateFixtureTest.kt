/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post date under a reel's author (#296): each declared build has one helper that reads the
 * video's creation_time behind a server switch, and the switch's answer goes to the extension
 * before the helper compares it with yes. The Reels tab's song line, which takes the date's spot on
 * most reels, hands its joined line and the reel's story to the extension too (#357).
 */
class ReelPostDateFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    @Test
    fun `each declared build hands the post date's server switch to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val helpers = FixtureDex.methodsWhere(bundle, { true }, ::isReelPostDate)
                assertEquals("$name: post date helpers", 1, helpers.size)
                val helper = helpers.single()
                val classes = FixtureDex.classes(bundle, setOf(helper.definingClass)).values.toList()
                assertThrows("$name: no helper must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).showReelPostDate()
                }
                val context = PatchContexts.of(classes)
                val storyType = context.showReelPostDate()
                assertEquals("$name: the story type is the helper's second parameter", helper.parameterTypes[1].toString(), storyType)
                val patched = context.mutableClassDefBy(helper.definingClass).methods.single {
                    it.name == helper.name &&
                        it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
                }.code()
                val calls = patched.indices.filter {
                    ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SHOW_POST_DATE
                }
                assertEquals("$name: one hook", 1, calls.size)
                val at = calls.single()
                assertEquals("$name: the hook follows a move-result", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                val result = (patched[at - 1] as OneRegisterInstruction).registerA
                val gate = (patched[at - 2] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the switch hands back a boolean", "Z", gate.returnType)
                assertEquals("$name: the hook passes the answer", result, (patched[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook passes one register", 1, (patched[at] as RegisterRangeInstruction).registerCount)
                assertEquals("$name: the extension's answer replaces Facebook's", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertEquals("$name: into the same register", result, (patched[at + 1] as OneRegisterInstruction).registerA)
                // Facebook compares the answer with yes right after, and draws nothing for anything else.
                assertEquals("$name: Facebook branches on the answer next", Opcode.IF_NE, patched[at + 2].opcode)
                assertEquals("$name: on the same register", result, (patched[at + 2] as TwoRegisterInstruction).registerA)
                assertTrue("$name: the date is read after the switch", creationTimeRead(helper) > at - 2)
                // #357: the formatter's words, null past what Facebook words, go to the extension with the time.
                val texts = patched.indices.filter {
                    ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == POST_DATE_TEXT
                }
                assertEquals("$name: one wording hook", 1, texts.size)
                val worded = texts.single()
                val format = (patched[worded - 2] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the wording hook follows the formatter", "Ljava/lang/String;", format.returnType)
                assertEquals("$name: a formatter of the time", "J", format.parameterTypes.last().toString())
                assertEquals("$name: and its move-result-object", Opcode.MOVE_RESULT_OBJECT, patched[worded - 1].opcode)
                val words = (patched[worded - 1] as OneRegisterInstruction).registerA
                val formatCall = patched[worded - 2] as FiveRegisterInstruction
                val hook = patched[worded] as FiveRegisterInstruction
                assertEquals("$name: the hook takes three registers", 3, hook.registerCount)
                assertEquals("$name: Facebook's words first", words, hook.registerC)
                assertEquals("$name: then the time's low half", formatCall.registerF, hook.registerD)
                assertEquals("$name: and its high half", formatCall.registerG, hook.registerE)
                assertEquals("$name: the extension's words replace Facebook's", Opcode.MOVE_RESULT_OBJECT, patched[worded + 1].opcode)
                assertEquals("$name: into the same register", words, (patched[worded + 1] as OneRegisterInstruction).registerA)
                // Facebook draws nothing for null right after, so a filled-in date reaches the line.
                assertEquals("$name: Facebook checks the words for null next", Opcode.IF_EQZ, patched[worded + 2].opcode)
                assertEquals("$name: the same words", words, (patched[worded + 2] as OneRegisterInstruction).registerA)
                assertTrue("$name: the wording hook comes after the switch's", worded > at)
                assertEquals("$name: four executable instructions added",
                    helper.code().count { it.opcode != Opcode.NOP } + 4, patched.count { it.opcode != Opcode.NOP })
                // #357: on most reels the Reels tab's song line takes that spot, so the date goes in front of it.
                val chipHolders = FixtureDex.classesHolding(bundle, SOUNDTRACK_CHIP)
                val renders = chipHolders.flatMap { owner -> owner.methods.filter(::isAudioLineRender) }
                assertEquals("$name: audio line renders", 1, renders.size)
                val render = renders.single()
                val join = audioLineJoin(render)
                assertTrue("$name: the render joins its song in one call", join >= 0)
                val line = (render.code()[join + 1] as OneRegisterInstruction).registerA
                val story = audioLineStory(render, join + 2, storyType)
                assertNotNull("$name: the render holds the story where it joins the song", story)
                assertThrows("$name: no render must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).reelsTabAudioLine()
                }
                // In the patch's order: the hide's early return goes in first, and the date still finds its join.
                val audio = PatchContexts.of(chipHolders)
                val audioLine = audio.reelsTabAudioLine()
                audioLine.returnNullWhen(HIDE_AUDIO_LINE)
                dateReelsTabAudioLine(audioLine, storyType)
                val dated = audio.mutableClassDefBy(render.definingClass).methods.single {
                    it.name == render.name &&
                        it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: the hide's hook still comes first", HIDE_AUDIO_LINE,
                    ((dated[0] as ReferenceInstruction).reference as MethodReference).toString())
                val songHooks = dated.indices.filter {
                    ((dated[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == AUDIO_LINE_TEXT
                }
                assertEquals("$name: one song line hook", 1, songHooks.size)
                val songHook = songHooks.single()
                val passed = dated[songHook] as FiveRegisterInstruction
                assertEquals("$name: the song line hook takes two registers", 2, passed.registerCount)
                // A register the hook reads is the original, or a copy made of it between the join and the hook.
                val datedJoin = audioLineJoin(audioLine)
                assertTrue("$name: the hook goes in right after the join", songHook > datedJoin + 1)
                fun copiedFrom(register: Int) = (datedJoin + 2 until songHook).map { dated[it] }
                    .lastOrNull { it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == register }
                    ?.let { (it as TwoRegisterInstruction).registerB } ?: register
                assertEquals("$name: the hook passes the joined line", line, copiedFrom(passed.registerC))
                assertEquals("$name: and the reel's story", story, copiedFrom(passed.registerD))
                assertEquals("$name: the extension's line comes back", Opcode.MOVE_RESULT_OBJECT, dated[songHook + 1].opcode)
                assertEquals("$name: into the register passed", passed.registerC, (dated[songHook + 1] as OneRegisterInstruction).registerA)
                if (passed.registerC != line) {
                    val back = dated[songHook + 2] as TwoRegisterInstruction
                    assertEquals("$name: and is copied back", Opcode.MOVE_OBJECT_FROM16, dated[songHook + 2].opcode)
                    assertEquals("$name: into the line's own register", line, back.registerA)
                    assertEquals("$name: from the extension's answer", passed.registerC, back.registerB)
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = SHOW_POST_DATE.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "showsPostDate" }
        assertNotNull("the extension has no ReelDeclutter.showsPostDate", hook)
        assertEquals("showsPostDate's shape", SHOW_POST_DATE,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("showsPostDate isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        val wording = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "postDateText" }
        assertNotNull("the extension has no ReelDeclutter.postDateText", wording)
        assertEquals("postDateText's shape", POST_DATE_TEXT,
            "${wording!!.definingClass}->${wording.name}(${wording.parameterTypes.joinToString("")})${wording.returnType}")
        assertTrue("postDateText isn't static", AccessFlags.STATIC.isSet(wording.accessFlags))
        val song = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "audioLineText" }
        assertNotNull("the extension has no ReelDeclutter.audioLineText", song)
        assertEquals("audioLineText's shape", AUDIO_LINE_TEXT,
            "${song!!.definingClass}->${song.name}(${song.parameterTypes.joinToString("")})${song.returnType}")
        assertTrue("audioLineText isn't static", AccessFlags.STATIC.isSet(song.accessFlags))
    }
}
