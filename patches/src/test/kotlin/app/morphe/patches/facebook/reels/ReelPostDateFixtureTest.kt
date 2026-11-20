/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.namedRegisters
import app.morphe.util.writersReaching
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
     * #520: each declared build's reel footer slots its New-for-you item in at a server place twice,
     * through List.add(int, Object), and every other positioned add in it counts its
     * own place. The hook swaps those two adds, and only them, for the extension call with the same
     * registers, so nothing else in the builder moves.
     */
    @Test
    fun `each declared build hands New for you's two inserts to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        val config = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, REASON_ALONE_FLAG)
                val builder = holders.flatMap { owner -> owner.methods.filter { reasonAloneRead(it) >= 0 } }.single()
                assertThrows("$name: no footer must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).slotNewForYou()
                }
                val original = builder.code()
                val positioned = original.indices.filter {
                    val call = (original[it] as? ReferenceInstruction)?.reference as? MethodReference
                    call != null && call.name == "add" && call.returnType == "V" &&
                        call.parameterTypes.map(CharSequence::toString) == listOf("I", "Ljava/lang/Object;")
                }
                // 588's Android 9 build reads the place through a static (Object, long) int helper that asks the config.
                val outlined = original.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.filter {
                    it.returnType == "I" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "J")
                }.map { it.definingClass }.toSet()
                val helpers = FixtureDex.classes(bundle, outlined).values.toList()
                val asksConfig = helpers.flatMap { owner ->
                    owner.methods.filter { helper ->
                        helper.implementation?.instructions?.toList().orEmpty().any {
                            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                            call?.definingClass == config && call.returnType == "J"
                        }
                    }
                }.map { "${it.definingClass}->${it.name}" }.toSet()
                val serverPlaced = positioned.filter { at ->
                    (maxOf(0, at - 20) until at).any {
                        val call = (original[it] as? ReferenceInstruction)?.reference as? MethodReference
                        call?.definingClass == config && call.returnType == "J" ||
                            call?.returnType == "I" && "${call.definingClass}->${call.name}" in asksConfig
                    }
                }
                assertEquals("$name: adds at a place read from the server", 2, serverPlaced.size)
                assertTrue("$name: the builder has positioned adds of its own too", positioned.size > serverPlaced.size)
                assertEquals("$name: the finder", serverPlaced, newForYouInserts(builder) { type -> helpers.firstOrNull { it.type == type } })
                val context = PatchContexts.of((holders + helpers).distinctBy { it.type })
                context.slotNewForYou()
                val patched = context.mutableClassDefBy(builder.definingClass).methods.single {
                    it.name == builder.name &&
                        it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: nothing added or taken away", original.size, patched.size)
                val hooked = patched.indices.filter {
                    ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SLOT_NEW_FOR_YOU
                }
                assertEquals("$name: the hook sits where the server-placed adds were", serverPlaced, hooked)
                for (at in hooked) {
                    assertTrue("$name: a static call at $at",
                        patched[at].opcode == Opcode.INVOKE_STATIC || patched[at].opcode == Opcode.INVOKE_STATIC_RANGE)
                    assertEquals("$name: with the add's list, place and item at $at",
                        original[at].namedRegisters(), patched[at].namedRegisters())
                }
                for (at in original.indices - hooked.toSet()) {
                    assertEquals("$name: instruction $at moved", original[at].opcode, patched[at].opcode)
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    /**
     * #520: a group's video in each declared build's full-screen viewer has an author row of its own
     * that never asks the post date helper for its line. Its render names the poster in one call whose
     * String is the text of both of its name spans, and that String goes to the extension with the
     * story right after the call, so the extension's answer reaches both spans.
     */
    @Test
    fun `each declared build hands a group video's poster to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val helper = FixtureDex.methodsWhere(bundle, { true }, ::isReelPostDate).single()
                val storyType = helper.parameterTypes[1].toString()
                val holders = FixtureDex.classesHolding(bundle, GROUP_AUTHOR)
                val renders = holders.flatMap { owner -> owner.methods.filter { groupPosterName(it) >= 0 } }
                assertEquals("$name: group author renders", 1, renders.size)
                val render = renders.single()
                assertEquals("$name: the row's render", "render", render.name)
                val original = render.code()
                assertTrue("$name: the row never asks the post date helper", original.none {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == helper.definingClass && call.name == helper.name
                })
                val spans = original.indices.filter {
                    val call = (original[it] as? ReferenceInstruction)?.reference as? MethodReference
                    original[it].opcode == Opcode.INVOKE_DIRECT && call?.name == "<init>" &&
                        call.parameterTypes.map(CharSequence::toString).drop(1) == listOf("Ljava/lang/CharSequence;", "Ljava/lang/Integer;")
                }
                assertEquals("$name: a name span on each of two ways", 2, spans.size)
                val nameAt = groupPosterName(render)
                val nameRegister = (original[nameAt] as OneRegisterInstruction).registerA
                for (span in spans) {
                    assertEquals("$name: the span at $span reads the poster's name", listOf(nameAt),
                        render.writersReaching(span, original[span].namedRegisters()[2]))
                }
                val call = (original[nameAt - 1] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the name comes from a String call", "Ljava/lang/String;", call.returnType)
                val story = storyHeld(render, nameAt + 1, storyType)
                assertNotNull("$name: the render holds the story where it names the poster", story)
                assertThrows("$name: no row must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).dateGroupPoster(storyType)
                }
                val context = PatchContexts.of(holders)
                context.dateGroupPoster(storyType)
                val patched = context.mutableClassDefBy(render.definingClass).methods.single {
                    it.name == render.name &&
                        it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
                }
                val code = patched.code()
                val hooks = code.indices.filter {
                    ((code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == GROUP_POSTER_TEXT
                }
                assertEquals("$name: one poster hook", 1, hooks.size)
                val hook = hooks.single()
                val passed = code[hook] as FiveRegisterInstruction
                assertEquals("$name: the hook takes two registers", 2, passed.registerCount)
                assertTrue("$name: the hook goes in right after the name", hook in nameAt + 1..nameAt + 3)
                fun copiedFrom(register: Int) = (nameAt + 1 until hook).map { code[it] }
                    .lastOrNull { it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == register }
                    ?.let { (it as TwoRegisterInstruction).registerB } ?: register
                assertEquals("$name: the hook passes the poster's name", nameRegister, copiedFrom(passed.registerC))
                assertEquals("$name: and the video's story", story, copiedFrom(passed.registerD))
                assertEquals("$name: the extension's name comes back", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
                assertEquals("$name: into the register passed", passed.registerC, (code[hook + 1] as OneRegisterInstruction).registerA)
                val last = if (passed.registerC == nameRegister) hook + 1 else hook + 2
                val spanInit = (original[spans.first()] as ReferenceInstruction).reference.toString()
                val datedSpans = code.indices.filter {
                    code[it].opcode == Opcode.INVOKE_DIRECT && (code[it] as? ReferenceInstruction)?.reference?.toString() == spanInit
                }
                assertEquals("$name: both spans are still there", 2, datedSpans.size)
                for (span in datedSpans) {
                    assertEquals("$name: the span at $span reads the extension's name", listOf(last),
                        patched.writersReaching(span, code[span].namedRegisters()[2]))
                }
                assertEquals("$name: only the hook and its copies added",
                    original.count { it.opcode != Opcode.NOP } + (last - nameAt), code.count { it.opcode != Opcode.NOP })
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
        // Where the attribution's register is reused before the ticker, a copy made right after the wording stands in.
        val carried = original.writersReaching(created, attribution) != original.writersReaching(call, attribution)
        val added = if (carried) 1 else 0
        val carrier = if (!carried) attribution else {
            val copy = patched[call + shift + 2]
            assertEquals("$name: the attribution is copied right after the wording", Opcode.MOVE_OBJECT_FROM16, copy.opcode)
            assertEquals("$name: from its own register", attribution, (copy as TwoRegisterInstruction).registerB)
            val held = copy.registerA
            assertTrue("$name: into one nothing reads from there on", held !in RegisterLiveness.of(original).liveInto(call + 2))
            assertTrue("$name: and nothing writes before the ticker's built", (call + 2..init).none { at ->
                code[at].opcode.setsRegister() && code[at].namedRegisters().first().let { written ->
                    written == held || (code[at].opcode.setsWideRegister() && written + 1 == held)
                }
            })
            val flow = ControlFlow.of(original)
            assertTrue("$name: nothing jumps in past the copy", code.indices.none { from ->
                (flow.normal[from] + flow.exceptional[from]).any { it in call + 2..created && it != from + 1 }
            })
            held
        }
        val hooks = patched.indices.filter { ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == hook }
        assertEquals("$name: one sound label hook in ${original.definingClass}", 1, hooks.size)
        val at = hooks.single()
        val start = created + shift + added
        val keep = patched[start] as TwoRegisterInstruction
        assertEquals("$name: the label is kept first", Opcode.MOVE_OBJECT_FROM16, patched[start].opcode)
        assertEquals("$name: from its own register", label, keep.registerB)
        assertTrue("$name: in one nothing reads there", keep.registerA !in RegisterLiveness.of(original).liveInto(created))
        assertTrue("$name: and not the attribution's carrier", keep.registerA != carrier)
        assertTrue("$name: and nothing writes before the ticker's built",
            (created..init).none { code[it].opcode.setsRegister() && code[it].namedRegisters().first() == keep.registerA })
        fun copiedFrom(register: Int) = (start + 1 until at).map { patched[it] }
            .lastOrNull { it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == register }
            ?.let { (it as TwoRegisterInstruction).registerB } ?: register
        val passed = patched[at].namedRegisters()
        assertEquals("$name: the hook passes the label", label, copiedFrom(passed[0]))
        assertEquals("$name: and its attribution", carrier, copiedFrom(passed[1]))
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
            code.count { it.opcode != Opcode.NOP } + (end - start) + 1 + shift + added, patched.count { it.opcode != Opcode.NOP })
    }

    private val moves = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

    /** The instruction in [method] whose value [register] holds at [at], followed back through plain moves. */
    private fun source(method: Method, at: Int, register: Int): Instruction? {
        val writer = method.writersReaching(at, register).singleOrNull()?.takeIf { it >= 0 } ?: return null
        val code = method.code()
        val written = code[writer]
        return if (written.opcode in moves) source(method, writer, (written as TwoRegisterInstruction).registerB) else written
    }

    /** The patched copy of [method] in [context]. */
    private fun patchedMethodOf(context: BytecodePatchContext, method: Method) =
        context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }

    private fun patchedOf(context: BytecodePatchContext, method: Method) = patchedMethodOf(context, method).code()

    private fun List<Instruction>.calling(hook: String) =
        indices.filter { ((this[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == hook }

    /**
     * #503: each declared build's Reels tab song line is a chip whose last flag, one of the render's
     * own boolean fields, makes it scroll. In the patch's order, after the hide and the date, that
     * flag goes to the extension right before the chip's constructor and comes back in its register.
     */
    @Test
    fun `each declared build hands the song chip's scrolling flag to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val storyType = FixtureDex.methodsWhere(bundle, { true }, ::isReelPostDate).single().parameterTypes[1].toString()
                val chipHolders = FixtureDex.classesHolding(bundle, SOUNDTRACK_CHIP)
                val render = chipHolders.flatMap { owner -> owner.methods.filter(::isAudioLineRender) }.single()
                val original = render.code()
                val chip = audioLineChip(render)
                assertTrue("$name: the render builds its chip past the join", chip > audioLineJoin(render))
                val chipInit = (original[chip] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: a constructor", "<init>", chipInit.name)
                assertEquals("$name: whose last two parameters are flags", listOf("Z", "Z"),
                    chipInit.parameterTypes.takeLast(2).map(CharSequence::toString))
                val flag = original[chip].namedRegisters().last()
                val read = source(render, chip, flag)
                assertNotNull("$name: the last flag comes from one place", read)
                assertEquals("$name: a boolean field", Opcode.IGET_BOOLEAN, read!!.opcode)
                assertEquals("$name: of the render's own class", render.definingClass,
                    ((read as ReferenceInstruction).reference as FieldReference).definingClass)
                val audio = PatchContexts.of(chipHolders)
                val audioLine = audio.reelsTabAudioLine()
                val other = audio.mutableClassDefBy(render.definingClass).methods.first { audioLineChip(it) < 0 && it.implementation != null }
                assertThrows("$name: a method with no chip must refuse the patch", PatchException::class.java) {
                    stillAudioLine(other)
                }
                audioLine.returnNullWhen(HIDE_AUDIO_LINE)
                dateReelsTabAudioLine(audioLine, storyType)
                val dated = audioLine.implementation!!.instructions.count { it.opcode != Opcode.NOP }
                stillAudioLine(audioLine)
                val patched = patchedOf(audio, render)
                val hooks = patched.calling(AUDIO_LINE_SCROLLS)
                assertEquals("$name: one scrolling hook", 1, hooks.size)
                val at = hooks.single()
                assertEquals("$name: the hook passes the flag", flag, (patched[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: and only the flag", 1, (patched[at] as RegisterRangeInstruction).registerCount)
                assertEquals("$name: the extension's answer comes back", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertEquals("$name: into the flag's register", flag, (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: right before the chip's constructor", chipInit.toString(),
                    ((patched[at + 2] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: which still takes the flag last", flag, patched[at + 2].namedRegisters().last())
                assertEquals("$name: the song line hook is still there", 1, patched.calling(AUDIO_LINE_TEXT).size)
                assertEquals("$name: two executable instructions added", dated + 2, patched.count { it.opcode != Opcode.NOP })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    /**
     * #503: each declared build's reel footer asks the post date helper for its row and hands the
     * rows under the author, a List, to one view of (FbUserSession, something, List, Function0,
     * boolean, boolean). Each row the helper answers goes to the extension right after it comes back,
     * and the rows go to it right before that view's constructor, its answer taking their register.
     * In the patch's order the footer's other hooks all still land.
     */
    @Test
    fun `each declared build hands the footer's date row and rows to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val helper = FixtureDex.methodsWhere(bundle, { true }, ::isReelPostDate).single()
                val helperParams = helper.parameterTypes.map(CharSequence::toString)
                val dateClasses = FixtureDex.classes(bundle, setOf(helper.definingClass)).values.toList()
                val holders = FixtureDex.classesHolding(bundle, REASON_ALONE_FLAG)
                val footer = holders.flatMap { owner -> owner.methods.filter { reasonAloneRead(it) >= 0 } }.single()
                val original = footer.code()
                val asked = original.indices.filter {
                    val call = (original[it] as? ReferenceInstruction)?.reference as? MethodReference
                    call != null && call.definingClass == helper.definingClass && call.name == helper.name &&
                        call.parameterTypes.map(CharSequence::toString) == helperParams
                }
                assertTrue("$name: the footer asks the post date helper for its row", asked.isNotEmpty())
                asked.forEach { assertEquals("$name: and takes its row at $it", Opcode.MOVE_RESULT_OBJECT, original[it + 1].opcode) }
                val flipper = footerFlipper(footer)
                assertTrue("$name: the footer builds one view that flips through its rows", flipper >= 0)
                val flipperInit = (original[flipper] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: whose third parameter is the rows", "Ljava/util/List;", flipperInit.parameterTypes[2].toString())
                val list = original[flipper].namedRegisters()[3]
                assertThrows("$name: no footer must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).holdFooterOnDate()
                }
                assertThrows("$name: a footer without the date helper must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(holders).holdFooterOnDate()
                }
                val alone = PatchContexts.of(holders + dateClasses)
                alone.holdFooterOnDate()
                val patched = patchedOf(alone, footer)
                val kept = patched.calling(FOOTER_DATE_ROW)
                assertEquals("$name: one date row hook for each time the helper is asked", asked.size, kept.size)
                // A branch onto the instruction after a row lands past its hook (588 has three).
                val flow = ControlFlow.of(patchedMethodOf(alone, footer))
                for (at in kept) {
                    assertTrue("$name: only the row's move-result reaches the hook at $at",
                        flow.normal.indices.none { from -> from != at - 1 && at in flow.normal[from] } &&
                            flow.exceptional.none { at in it })
                }
                for (at in kept) {
                    val row = (patched[at - 1] as OneRegisterInstruction).registerA
                    assertEquals("$name: the hook follows the row's move-result", Opcode.MOVE_RESULT_OBJECT, patched[at - 1].opcode)
                    assertEquals("$name: of the helper's call", helper.name,
                        ((patched[at - 2] as ReferenceInstruction).reference as MethodReference).name)
                    assertEquals("$name: and passes that row alone", listOf(row), patched[at].namedRegisters())
                }
                val handed = patched.calling(FOOTER_ROWS)
                assertEquals("$name: one rows hook", 1, handed.size)
                val at = handed.single()
                assertEquals("$name: the hook passes the rows", listOf(list), patched[at].namedRegisters())
                assertEquals("$name: the extension's rows come back", Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
                assertEquals("$name: into the rows' register", list, (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: right before the view's constructor", flipperInit.toString(),
                    ((patched[at + 2] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: which still takes them", list, patched[at + 2].namedRegisters()[3])
                assertEquals("$name: one instruction per row hook and two for the rows",
                    original.count { it.opcode != Opcode.NOP } + asked.size + 2, patched.count { it.opcode != Opcode.NOP })
                // In the patch's order, beside the footer's other hooks.
                val storyType = helperParams[1]
                val call = subtitleLabelCall(footer, storyType)
                val soundHelper = (original[call] as ReferenceInstruction).reference as MethodReference
                val soundClasses = FixtureDex.classes(bundle, setOf(soundHelper.definingClass)).values.toList()
                // 588's Android 9 build reads New for you's place through a static (Object, long) int helper elsewhere.
                val outlined = original.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.filter {
                    it.returnType == "I" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "J")
                }.map { it.definingClass }.toSet()
                val configHelpers = FixtureDex.classes(bundle, outlined).values.toList()
                val all = PatchContexts.of((holders + dateClasses + soundClasses + configHelpers).distinctBy { it.type })
                all.keepPostDateBesideReason()
                all.holdFooterOnDate()
                all.dateSoundLabels(storyType)
                all.slotNewForYou()
                val together = patchedOf(all, footer)
                assertEquals("$name: the reason-alone hook", 1, together.calling(REASON_ALONE).size)
                assertEquals("$name: the date row hooks", asked.size, together.calling(FOOTER_DATE_ROW).size)
                assertEquals("$name: the rows hook", 1, together.calling(FOOTER_ROWS).size)
                assertEquals("$name: the sound label hook", 1, together.calling(SOUND_LABEL_TEXT).size)
                assertEquals("$name: the New for you hooks", 2, together.calling(SLOT_NEW_FOR_YOU).size)
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    /**
     * #503: each declared build's reel caption render builds two date rows of the reel's
     * creation_time after the caption, an object of (FbUserSession, long) made from the time it just
     * read, each behind a server switch a few instructions up. Each switch's answer goes to the
     * extension right after its move-result and comes back in that register, before the branch that
     * tests it, and nothing else in the render goes on to the hook.
     */
    @Test
    fun `each declared build hands the caption date's server switches to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        val captionRender = "FbShortsViewerDescriptionComponentSpec_Text_Component_"
        val creationTime = "creation_time".hashCode()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, captionRender)
                // Read off the dex itself: each (FbUserSession, long) constructor handed a creation_time read.
                fun rowsOf(method: Method): List<Int> {
                    val code = method.code()
                    return code.indices.filter { at ->
                        val made = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
                        code[at].opcode == Opcode.INVOKE_DIRECT && made != null && made.name == "<init>" &&
                            made.parameterTypes.map(CharSequence::toString) == listOf("Lcom/facebook/auth/usersession/FbUserSession;", "J") &&
                            (maxOf(0, at - 6) until at).any { read ->
                                val call = (code[read] as? ReferenceInstruction)?.reference as? MethodReference
                                call?.name == "getTimeValue" && code.subList(maxOf(0, read - 3), read).any {
                                    it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction)
                                        .narrowLiteral == creationTime
                                }
                            }
                    }
                }
                // 588's Android 9 build asks a string table for the name, from a render in another class.
                val classes = if (holders.any { owner -> owner.methods.any { rowsOf(it).isNotEmpty() } }) holders else {
                    val tables = holders.filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, captionRender) } }
                        .associateBy { it.type }
                    val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                    val asking = if (tables.isEmpty()) emptySet() else FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in tables } }) {
                        !isStringTable(it) && !holdsString(it, captionRender) && namesString(it, captionRender, resolve)
                    }.map { it.definingClass }.toSet()
                    (holders + FixtureDex.classes(bundle, asking).values).distinctBy { it.type }
                }
                val renders = classes.flatMap { owner -> owner.methods.filter { rowsOf(it).isNotEmpty() } }
                assertEquals("$name: one caption render dates its caption", 1, renders.size)
                val render = renders.single()
                assertEquals("$name: two caption date rows", 2, rowsOf(render).size)
                assertThrows("$name: no caption render must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).dropCaptionDate()
                }
                val context = PatchContexts.of(classes)
                context.dropCaptionDate()
                val patched = patchedOf(context, render)
                val hooks = patched.calling(CAPTION_DATE_SHOWS)
                assertEquals("$name: one hook for each caption date row", 2, hooks.size)
                val flow = ControlFlow.of(patchedMethodOf(context, render))
                val rows = rowsOf(patchedMethodOf(context, render))
                for ((at, row) in hooks.zip(rows)) {
                    assertTrue("$name: the hook at $at sits above its row at $row", at < row && row - at <= 24)
                    assertTrue("$name: only the switch's move-result reaches the hook at $at",
                        flow.normal.indices.none { from -> from != at - 1 && at in flow.normal[from] } &&
                            flow.exceptional.none { at in it })
                    assertEquals("$name: the hook follows a move-result", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                    val switch = (patched[at - 2] as ReferenceInstruction).reference as MethodReference
                    assertEquals("$name: of a switch answering a boolean", "Z", switch.returnType)
                    assertEquals("$name: handed a Function0", "Lkotlin/jvm/functions/Function0;", switch.parameterTypes[1].toString())
                    val result = (patched[at - 1] as OneRegisterInstruction).registerA
                    assertEquals("$name: the hook passes that answer alone", listOf(result), patched[at].namedRegisters())
                    assertEquals("$name: the extension's answer comes back", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                    assertEquals("$name: into the same register", result, (patched[at + 1] as OneRegisterInstruction).registerA)
                    val test = patched[at + 2]
                    assertTrue("$name: and the branch right after tests it", test is OffsetInstruction &&
                        (test as OneRegisterInstruction).registerA == result)
                }
                val original = render.code()
                assertEquals("$name: two instructions per hook", original.count { it.opcode != Opcode.NOP } + 4,
                    patched.count { it.opcode != Opcode.NOP })
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
        val reason = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "reasonAlone" }
        assertNotNull("the extension has no ReelDeclutter.reasonAlone", reason)
        assertEquals("reasonAlone's shape", REASON_ALONE,
            "${reason!!.definingClass}->${reason.name}(${reason.parameterTypes.joinToString("")})${reason.returnType}")
        assertTrue("reasonAlone isn't static", AccessFlags.STATIC.isSet(reason.accessFlags))
        for ((method, shape) in listOf(
            "soundLabelText" to SOUND_LABEL_TEXT, "soundStory" to SOUND_STORY, "soundTickerText" to SOUND_TICKER_TEXT,
            "slotNewForYou" to SLOT_NEW_FOR_YOU, "groupPosterText" to GROUP_POSTER_TEXT,
            "audioLineScrolls" to AUDIO_LINE_SCROLLS, "footerDateRow" to FOOTER_DATE_ROW, "footerRows" to FOOTER_ROWS,
            "captionDateShows" to CAPTION_DATE_SHOWS,
        )) {
            val sound = ExtensionDex.classDef(type).methods.singleOrNull { it.name == method }
            assertNotNull("the extension has no ReelDeclutter.$method", sound)
            assertEquals("$method's shape", shape,
                "${sound!!.definingClass}->${sound.name}(${sound.parameterTypes.joinToString("")})${sound.returnType}")
            assertTrue("$method isn't static", AccessFlags.STATIC.isSet(sound.accessFlags))
        }
    }
}
