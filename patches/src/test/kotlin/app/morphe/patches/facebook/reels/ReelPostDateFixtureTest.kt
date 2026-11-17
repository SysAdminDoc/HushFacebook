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
import app.morphe.util.RegisterLiveness
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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

    /**
     * #496: each declared build's reel footer reads the flag that leaves a recommendation reason alone
     * on the author's line once, and the hook hands that boolean to the extension before Facebook
     * branches on it.
     */
    @Test
    fun `each declared build hands the reason-alone flag to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, REASON_ALONE_FLAG)
                val builders = holders.flatMap { owner -> owner.methods.filter { reasonAloneRead(it) >= 0 } }
                assertEquals("$name: footers reading the flag", 1, builders.size)
                val builder = builders.single()
                assertThrows("$name: no footer must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).keepPostDateBesideReason()
                }
                val context = PatchContexts.of(holders)
                context.keepPostDateBesideReason()
                val original = builder.code()
                val read = reasonAloneRead(builder)
                val flag = (original[read] as OneRegisterInstruction).registerA
                val unbox = (original[read - 1] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the flag is unboxed right before", "booleanValue", unbox.name)
                val patched = context.mutableClassDefBy(builder.definingClass).methods.single {
                    it.name == builder.name &&
                        it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }.code()
                val calls = patched.indices.filter {
                    ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == REASON_ALONE
                }
                assertEquals("$name: one hook", 1, calls.size)
                val at = calls.single()
                assertEquals("$name: the hook follows the flag's move-result", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                assertEquals("$name: of the same register", flag, (patched[at - 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the hook passes the flag", flag, (patched[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: and only the flag", 1, (patched[at] as RegisterRangeInstruction).registerCount)
                assertEquals("$name: the extension's answer comes back", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertEquals("$name: into the flag's register", flag, (patched[at + 1] as OneRegisterInstruction).registerA)
                // Facebook branches on the flag within the next few instructions, before anything writes it.
                val branch = (at + 2 until minOf(patched.size, at + 6)).firstOrNull {
                    patched[it].opcode in setOf(Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_EQZ, Opcode.IF_NEZ)
                }
                assertNotNull("$name: Facebook branches on the flag next", branch)
                assertEquals("$name: on the flag's register", flag, when (val branching = patched[branch!!]) {
                    is TwoRegisterInstruction -> branching.registerA
                    else -> (branching as OneRegisterInstruction).registerA
                })
                assertEquals("$name: two executable instructions added",
                    original.count { it.opcode != Opcode.NOP } + 2, patched.count { it.opcode != Opcode.NOP })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    /**
     * #496: each declared build's reel footer words every attribution's label with one helper and
     * builds a ticker label from it, and a second method in the helper's class builds the soundtrack's
     * ticker label alone. Both labels go to the extension right before the ticker's new-instance, the
     * footer's with the story it holds and the second one's with the story kept on the way in, and
     * Facebook's own label comes back into its register right after the ticker's constructor.
     */
    @Test
    fun `each declared build hands a silent reel's sound label to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val storyType = FixtureDex.methodsWhere(bundle, { true }, ::isReelPostDate).single().parameterTypes[1].toString()
                val holders = FixtureDex.classesHolding(bundle, REASON_ALONE_FLAG)
                val footer = holders.flatMap { owner -> owner.methods.filter { reasonAloneRead(it) >= 0 } }.single()
                val call = subtitleLabelCall(footer, storyType)
                assertTrue("$name: the footer words its labels in one call", call >= 0)
                val helper = (footer.code()[call] as ReferenceInstruction).reference as MethodReference
                val ticker = soundTicker(footer, call)
                assertNotNull("$name: the footer builds a ticker label from its words", ticker)
                val story = audioLineStory(footer, ticker!!.first, storyType)
                assertNotNull("$name: the footer holds the story where it builds the label", story)
                val helperClasses = FixtureDex.classes(bundle, setOf(helper.definingClass)).values.toList()
                val soundtracks = soundTickerHelpers(helperClasses.single().methods, helper, storyType)
                assertEquals("$name: soundtrack ticker labels", 1, soundtracks.size)
                val soundtrack = soundtracks.single()
                assertTrue("$name: the second helper is the soundtrack's", soundtrack.code().any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "XFBFBShortsSoundtrackTitle"
                })
                assertThrows("$name: no footer must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).dateSoundLabels(storyType)
                }
                val context = PatchContexts.of(holders + helperClasses)
                context.dateSoundLabels(storyType)

                val patchedFooter = context.mutableClassDefBy(footer.definingClass).methods.single {
                    it.name == footer.name &&
                        it.parameterTypes.map(CharSequence::toString) == footer.parameterTypes.map(CharSequence::toString)
                }.code()
                assertSoundHook(name, footer, call, patchedFooter, 0, SOUND_LABEL_TEXT, story)

                val patchedHelper = context.mutableClassDefBy(soundtrack.definingClass).methods.single {
                    it.name == soundtrack.name &&
                        it.parameterTypes.map(CharSequence::toString) == soundtrack.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: the story is kept on the way in", SOUND_STORY,
                    ((patchedHelper[0] as ReferenceInstruction).reference as MethodReference).toString())
                val params = soundtrack.parameterTypes.map(CharSequence::toString)
                val sizes = params.map { if (it == "J" || it == "D") 2 else 1 }
                val storyParameter = soundtrack.implementation!!.registerCount - sizes.sum() + sizes.take(params.indexOf(storyType)).sum()
                assertEquals("$name: from the story parameter's register", listOf(storyParameter), patchedHelper[0].namedRegisters())
                val worded = soundtrack.code().indices.single {
                    ((soundtrack.code()[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == helper.toString()
                }
                assertSoundHook(name, soundtrack, worded, patchedHelper, 1, SOUND_TICKER_TEXT, null)
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    /**
     * That [patched], [original] after the patch with [shift] instructions put in ahead of [call],
     * hands the label worded at [call] to [hook] with its attribution and [story] right before the
     * ticker's new-instance, and puts the label back right after the ticker's constructor.
     */
    private fun assertSoundHook(name: String, original: Method, call: Int, patched: List<Instruction>, shift: Int, hook: String, story: Int?) {
        val code = original.code()
        assertEquals("$name: the label is a move-result-object", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
        val label = (code[call + 1] as OneRegisterInstruction).registerA
        val attribution = code[call].namedRegisters()[1]
        val (created, init) = soundTicker(original, call)!!
        assertEquals("$name: the ticker starts with its new-instance", Opcode.NEW_INSTANCE, code[created].opcode)
        val hooks = patched.indices.filter { ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == hook }
        assertEquals("$name: one sound label hook in ${original.definingClass}", 1, hooks.size)
        val at = hooks.single()
        val start = created + shift
        val keep = patched[start] as TwoRegisterInstruction
        assertEquals("$name: the label is kept first", Opcode.MOVE_OBJECT_FROM16, patched[start].opcode)
        assertEquals("$name: from its own register", label, keep.registerB)
        assertTrue("$name: in one nothing reads there", keep.registerA !in RegisterLiveness.of(original).liveInto(created))
        assertTrue("$name: and nothing writes before the ticker's built",
            (created..init).none { code[it].opcode.setsRegister() && code[it].namedRegisters().first() == keep.registerA })
        fun copiedFrom(register: Int) = (start + 1 until at).map { patched[it] }
            .lastOrNull { it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == register }
            ?.let { (it as TwoRegisterInstruction).registerB } ?: register
        val passed = patched[at].namedRegisters()
        assertEquals("$name: the hook passes the label", label, copiedFrom(passed[0]))
        assertEquals("$name: and its attribution", attribution, copiedFrom(passed[1]))
        if (story == null) {
            assertEquals("$name: and nothing else", 2, passed.size)
        } else {
            assertEquals("$name: and the story", story, copiedFrom(passed[2]))
        }
        assertEquals("$name: the extension's label comes back", Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
        assertEquals("$name: into the register passed", passed[0], (patched[at + 1] as OneRegisterInstruction).registerA)
        var end = at + 2
        if (passed[0] != label) {
            assertEquals("$name: and is copied back", Opcode.MOVE_OBJECT_FROM16, patched[end].opcode)
            assertEquals("$name: into the label's register", label, (patched[end] as TwoRegisterInstruction).registerA)
            end++
        }
        assertEquals("$name: the ticker's new-instance comes next", Opcode.NEW_INSTANCE, patched[end].opcode)
        assertEquals("$name: into the same register", code[created].namedRegisters(), patched[end].namedRegisters())
        assertEquals("$name: of the same type",
            (code[created] as ReferenceInstruction).reference.toString(), (patched[end] as ReferenceInstruction).reference.toString())
        val patchedInit = end + (init - created)
        assertTrue("$name: its constructor takes the label", label in patched[patchedInit].namedRegisters().drop(1))
        val back = patched[patchedInit + 1] as TwoRegisterInstruction
        assertEquals("$name: Facebook's label comes back after", Opcode.MOVE_OBJECT_FROM16, patched[patchedInit + 1].opcode)
        assertEquals("$name: into the label's register", label, back.registerA)
        assertEquals("$name: from where it was kept", keep.registerA, back.registerB)
        assertEquals("$name: the hook's instructions and the stash added",
            code.count { it.opcode != Opcode.NOP } + (end - start) + 1 + shift, patched.count { it.opcode != Opcode.NOP })
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
        val reason = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "reasonAlone" }
        assertNotNull("the extension has no ReelDeclutter.reasonAlone", reason)
        assertEquals("reasonAlone's shape", REASON_ALONE,
            "${reason!!.definingClass}->${reason.name}(${reason.parameterTypes.joinToString("")})${reason.returnType}")
        assertTrue("reasonAlone isn't static", AccessFlags.STATIC.isSet(reason.accessFlags))
        for ((method, shape) in listOf("soundLabelText" to SOUND_LABEL_TEXT, "soundStory" to SOUND_STORY, "soundTickerText" to SOUND_TICKER_TEXT)) {
            val sound = ExtensionDex.classDef(type).methods.singleOrNull { it.name == method }
            assertNotNull("the extension has no ReelDeclutter.$method", sound)
            assertEquals("$method's shape", shape,
                "${sound!!.definingClass}->${sound.name}(${sound.parameterTypes.joinToString("")})${sound.returnType}")
            assertTrue("$method isn't static", AccessFlags.STATIC.isSet(sound.accessFlags))
        }
    }
}
