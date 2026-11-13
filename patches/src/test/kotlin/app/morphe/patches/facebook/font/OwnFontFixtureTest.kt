/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.font

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.emoji.isEmojiTypefaceProvider
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The anchors the font swap stands on, pinned on each declared Facebook build: one typeface
 * resolver, the family enum with Meta's interface families, the builder factory and the two
 * builders it makes, and React Native's typeface resolver, with the frames the injections need.
 */
class OwnFontFixtureTest {
    private val TEXT_PAINT = "Landroid/text/TextPaint;"
    private val OBJECT_TYPE = "Ljava/lang/Object;"

    /**
     * The families the switch swaps, per declared build (version and ABI, each its own Redex output).
     * The extension picks them by name (OPTIMISTIC_* and FACEBOOK_SANS_VARIABLE, never a face with a
     * MONO word), so 586 dropping 585's OPTIMISTIC_AI_1_BETA and OPTIMISTIC_AI_3_BETA left these ten,
     * and 587's new OPTIMISTIC_MONO, a monospace face, stays Meta's. OwnFontTest's twelve from 580
     * still cover every one.
     */
    private val metaFamilies = mapOf(
        "587.0.0.39.55-arm64-v8a" to sortedSetOf(
            "FACEBOOK_SANS_VARIABLE", "OPTIMISTIC_AI", "OPTIMISTIC_AI_2_BETA",
            "OPTIMISTIC_DISPLAY_APP", "OPTIMISTIC_DISPLAY_APP_MEDIUM",
            "OPTIMISTIC_TEXT_APP_BOLD", "OPTIMISTIC_TEXT_APP_MEDIUM", "OPTIMISTIC_TEXT_APP_REGULAR",
            "OPTIMISTIC_VARIABLE_APP_LITE", "OPTIMISTIC_VF_APP_LITE",
        ),
        "587.0.0.39.55-armeabi-v7a" to sortedSetOf(
            "FACEBOOK_SANS_VARIABLE", "OPTIMISTIC_AI", "OPTIMISTIC_AI_2_BETA",
            "OPTIMISTIC_DISPLAY_APP", "OPTIMISTIC_DISPLAY_APP_MEDIUM",
            "OPTIMISTIC_TEXT_APP_BOLD", "OPTIMISTIC_TEXT_APP_MEDIUM", "OPTIMISTIC_TEXT_APP_REGULAR",
            "OPTIMISTIC_VARIABLE_APP_LITE", "OPTIMISTIC_VF_APP_LITE",
        ),
    )

    /**
     * The families with variable axes, per declared build. Their builders come from the one maker
     * that takes the family, which the patch hooks, so OPTIMISTIC_MONO's keep Meta's face.
     */
    private val axisFamilies = mapOf(
        "587.0.0.39.55-arm64-v8a" to listOf("OPTIMISTIC_AI", "OPTIMISTIC_AI_2_BETA", "OPTIMISTIC_MONO", "OPTIMISTIC_VARIABLE_APP_LITE"),
        "587.0.0.39.55-armeabi-v7a" to listOf("OPTIMISTIC_AI", "OPTIMISTIC_AI_2_BETA", "OPTIMISTIC_MONO", "OPTIMISTIC_VARIABLE_APP_LITE"),
    )

    /** The builder makers the patch hooks, per declared build. */
    private val builderMakerCount = mapOf("587.0.0.39.55-arm64-v8a" to 1, "587.0.0.39.55-armeabi-v7a" to 1)

    /** [bundle]'s declared build: its version and ABI. */
    private fun build(bundle: File) = bundle.name.removePrefix("facebook-").removeSuffix(".apkm")

    private fun <T> Map<String, T>.pinFor(bundle: File, what: String): T =
        this[build(bundle)] ?: throw AssertionError("${bundle.name}: $what has no pin for ${build(bundle)}")

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

    @Test
    fun `each declared build has one typeface resolver, Meta's families and the builder route`() = bundles { bundle ->
        val owners = FixtureDex.classesHolding(bundle, NO_BACKING_SOURCE)
        assertEquals("${bundle.name}: classes holding the no-source refusal", 1, owners.size)
        checkResolver(bundle, owners.single())
        checkBuilders(bundle, owners.single())
    }

    /**
     * The Roboto Facebook's text engine builds for text that names none of Meta's fonts comes from one
     * builder in each declared build, found by its log, and it hands its answer back through a return.
     */
    @Test
    fun `each declared build has one Roboto builder`() = bundles { bundle ->
        val builders = FixtureDex.classesHolding(bundle, NO_ROBOTO).flatMap(::robotoBuilders)
        assertEquals("${bundle.name}: Roboto builders", 1, builders.size)
        assertTrue("${bundle.name}: the Roboto builder returns a Typeface", objectReturns(builders.single()).isNotEmpty())
    }

    /**
     * Each declared build reads Android's default bold in a text span's draw, which is how the names
     * bolded in a post's header get the phone's bold, and the rewrite of those reads and of the
     * calls that answer the phone's typefaces runs over every class making them.
     */
    @Test
    fun `each declared build reads Android's default bold in a text span`() = bundles { bundle ->
        val readers = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any { defaultRead(it) != null } == true
        }
        val reads = readers.flatMap { method -> method.implementation!!.instructions.mapNotNull(::defaultRead) }
        assertTrue("${bundle.name}: Android's default typefaces read ${reads.size} times", reads.size > 100)
        assertTrue("${bundle.name}: no text span reads DEFAULT_BOLD", readers.any { method ->
            method.name == "updateDrawState" && method.implementation!!.instructions.any { defaultRead(it) == "$TYPEFACE->DEFAULT_BOLD:$TYPEFACE" }
        })
        val styleCalls = readers.flatMap { method ->
            method.implementation!!.instructions.filter { defaultRead(it) == DEFAULT_FROM_STYLE }
        }
        assertTrue("${bundle.name}: no defaultFromStyle call", styleCalls.isNotEmpty())

        // The rewrite itself, over every class that reads them. A field read only ever compared
        // with another typeface stays, Litho's text paint's compare with Typeface.DEFAULT among
        // them. Every other read goes to the extension in its own place, a field read's answer
        // comes back into the register the read wrote, and each call to the extension's own.
        // With the classes of the static checks they call, whose code says whether each is one.
        val owners = FixtureDex.classes(bundle, readers.map { it.definingClass }.toSet() + staticCheckTypes(readers))
        val isEquality = PatchContexts.of(owners.values).equalityChecks()
        fun keptIn(method: Method) = method.implementation!!.instructions.toList().withIndex()
            .filter { (at, instruction) -> defaultRead(instruction) in DEFAULT_TYPEFACES && method.onlyCompared(at, isEquality) }.map { it.index }
        val kept = readers.flatMap { method -> keptIn(method).map { method } }
        assertTrue("${bundle.name}: no read is only compared", kept.isNotEmpty())
        assertTrue("${bundle.name}: no text paint builder keeps its compare with Typeface.DEFAULT", kept.any { method ->
            method.returnType == TEXT_PAINT && method.implementation!!.instructions.any { defaultRead(it) == DEFAULT_FROM_STYLE }
        })
        val context = PatchContexts.of(owners.values)
        assertEquals("${bundle.name}: reads sent", reads.size - kept.size, context.hookDefaultTypefaces())
        val getters = DEFAULT_TYPEFACES.values.map { "$OWN_FONT->$it()$TYPEFACE" }.toSet()
        val sent = mutableMapOf<String, Int>()
        for ((type, original) in owners) {
            for (method in context.mutableClassDefBy(type).methods) {
                val body = method.implementation?.instructions?.toList() ?: continue
                val where = "${bundle.name}: $type->${method.name}"
                val before = original.methods.single { it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString) &&
                    it.returnType == method.returnType }
                val code = before.implementation!!.instructions.toList()
                val stays = keptIn(before).toSet()
                assertEquals("$where: the reads left are the ones only compared", stays.map { defaultRead(code[it]) },
                    body.mapNotNull(::defaultRead))
                val wanted = code.indices.filter { defaultRead(code[it]) in DEFAULT_TYPEFACES && it !in stays }
                    .map { (code[it] as OneRegisterInstruction).registerA }
                val given = body.indices.filter { at ->
                    (body[at] as? ReferenceInstruction)?.reference?.toString() in getters
                }.map { at ->
                    assertEquals("$where: the getter's answer is moved", Opcode.MOVE_RESULT_OBJECT, body[at + 1].opcode)
                    (body[at + 1] as OneRegisterInstruction).registerA
                }
                assertEquals("$where: the registers the reads wrote", wanted, given)
                for (call in DEFAULT_CALLS) {
                    sent.merge(call, body.count { (it as? ReferenceInstruction)?.reference?.toString() == ownCall(call) }, Int::plus)
                }
            }
        }
        for (call in DEFAULT_CALLS) {
            assertEquals("${bundle.name}: $call calls sent", reads.count { it == call }, sent[call])
        }
        assertEquals("${bundle.name}: defaultFromStyle calls sent", styleCalls.size, sent[DEFAULT_FROM_STYLE])
    }

    /** The classes of the static calls on two objects answering a boolean that [methods] make. */
    private fun staticCheckTypes(methods: List<Method>): Set<String> = methods.flatMap { method ->
        method.implementation!!.instructions.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { call ->
                (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_TYPE, OBJECT_TYPE)
            }?.definingClass
        }
    }.toSet()

    /**
     * Every check each declared build makes of whether a typeface is one of Android's defaults:
     * an if-eq or if-ne, an equals call, or Kotlin's areEqual, a static call on two objects
     * answering a boolean whose own code only compares them. A read of the default nothing but checks use is
     * still a read of Android's own field after the rewrite, so each of those checks still asks
     * about Android's typeface. Litho's text paint and the post text's check (580 `LX/3qU;->A00`,
     * `LX/302;->A0k`) are among them. A read that's also set on a paint goes to the extension, and
     * its check compares the very typeface it then sets, as AppCompat's switch does to see whether
     * its paint has that one already. Each build has that switch, and its check has to see the
     * picked file too, since it asks about the value it's about to set, not about Android's.
     *
     * The only static call either build's reads reach is Kotlin's areEqual, so that's pinned too: a
     * new one turning up is a call the rule hasn't been checked against on real code.
     */
    @Test
    fun `every check of whether a typeface is Android's default still asks about Android's`() = bundles { bundle ->
        val readers = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any { defaultRead(it) in DEFAULT_TYPEFACES } == true
        }
        val isEquality = PatchContexts.of(FixtureDex.classes(bundle, staticCheckTypes(readers)).values).equalityChecks()
        fun isCheck(instruction: Instruction): Boolean = when (instruction.opcode) {
            Opcode.IF_EQ, Opcode.IF_NE -> true
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE ->
                (instruction as ReferenceInstruction).reference.toString() == "Ljava/lang/Object;->equals(Ljava/lang/Object;)Z"
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE ->
                ((instruction as ReferenceInstruction).reference as MethodReference).let { call ->
                    call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_TYPE, OBJECT_TYPE) &&
                        isEquality(call)
                }
            else -> false
        }
        fun checked(method: Method, only: Boolean): List<Int> {
            val code = method.implementation!!.instructions.toList()
            return code.indices.filter { at ->
                defaultRead(code[at]) in DEFAULT_TYPEFACES && method.literalReads(at).let { uses ->
                    uses.any { isCheck(code[it]) } && (!only || uses.all { isCheck(code[it]) })
                }
            }
        }
        val checkers = readers.filter { checked(it, only = false).isNotEmpty() }
        var throughKotlin = 0
        var onlyChecked = 0
        var alsoSet = 0
        for (method in checkers) {
            val code = method.implementation!!.instructions.toList()
            for (at in checked(method, only = true)) {
                onlyChecked++
                if (method.literalReads(at).any { code[it].opcode == Opcode.INVOKE_STATIC || code[it].opcode == Opcode.INVOKE_STATIC_RANGE }) throughKotlin++
            }
            alsoSet += (checked(method, only = false) - checked(method, only = true).toSet()).count { at ->
                method.literalReads(at).any {
                    (code[it] as? ReferenceInstruction)?.reference?.toString() == "Landroid/graphics/Paint;->setTypeface($TYPEFACE)$TYPEFACE"
                }
            }
        }
        assertTrue("${bundle.name}: $onlyChecked reads only checked", onlyChecked >= 5)
        assertTrue("${bundle.name}: no check goes through Kotlin's areEqual", throughKotlin >= 1)
        assertTrue("${bundle.name}: no read is both checked and set on a paint", alsoSet >= 1)
        assertTrue("${bundle.name}: Litho's text paint isn't among them", checkers.any { it.returnType == TEXT_PAINT })
        val staticCalls = readers.flatMap { method ->
            val code = method.implementation!!.instructions.toList()
            code.indices.filter { defaultRead(code[it]) in DEFAULT_TYPEFACES }.flatMap { at ->
                method.literalReads(at).mapNotNull { use ->
                    ((code[use] as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { call ->
                        (code[use].opcode == Opcode.INVOKE_STATIC || code[use].opcode == Opcode.INVOKE_STATIC_RANGE) &&
                            call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_TYPE, OBJECT_TYPE)
                    }
                }
            }
        }.distinctBy { it.toString() }
        assertEquals("${bundle.name}: static two-object calls a default read reaches: $staticCalls", 1, staticCalls.size)
        assertTrue("${bundle.name}: ${staticCalls.single()} isn't taken for a compare", isEquality(staticCalls.single()))

        val owners = FixtureDex.classes(bundle, checkers.map { it.definingClass }.toSet() + staticCheckTypes(checkers))
        val context = PatchContexts.of(owners.values)
        context.hookDefaultTypefaces()
        for (method in checkers) {
            val after = context.mutableClassDefBy(method.definingClass).methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
            }
            val code = method.implementation!!.instructions.toList()
            val body = after.implementation!!.instructions.toList()
            val left = body.indices.filter { defaultRead(body[it]) in DEFAULT_TYPEFACES }
            val where = "${bundle.name}: ${method.definingClass}->${method.name}"
            assertEquals("$where: the reads left are the ones only checked", checked(method, only = true).map { defaultRead(code[it]) },
                left.map { defaultRead(body[it]) })
            for (at in left) assertTrue("$where: the read left at $at is used by nothing but checks", after.literalReads(at).all { isCheck(body[it]) })
        }
    }

    /**
     * Use the system emoji's typeface: each declared build's emoji typeface provider hands its
     * answer to Facebook's emoji spans and drawings, which set it on a paint themselves, and to the
     * quick emoji picker's holder. On the way it goes through nothing the font rewrite sends to the
     * extension, and the rewrite changes nothing in those spans and drawings, so the phone's emoji
     * draw as they did whatever font file is picked.
     */
    @Test
    fun `the emoji typeface reaches its paints with nothing of the font rewrite's in the way`() = bundles { bundle ->
        val provider = FixtureDex.methodsWhere(bundle, { true }, ::isEmojiTypefaceProvider).single()
        val asked = "${provider.definingClass}->${provider.name}()$TYPEFACE"
        fun holds(method: Method, reference: String) =
            method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == reference } == true
        val consumers = sortedSetOf<String>()
        val holders = sortedSetOf<String>()
        fun follow(method: Method, at: Int, what: String) {
            val code = method.implementation!!.instructions.toList()
            for (use in method.literalReads(at)) {
                val reference = (code[use] as? ReferenceInstruction)?.reference
                assertTrue("${bundle.name}: ${method.definingClass}->${method.name}: $what goes through $reference",
                    defaultRead(code[use]) == null && !reference.toString().startsWith("$TYPEFACE->"))
                if (reference is MethodReference && reference.name == "<init>") consumers += reference.definingClass
                if (code[use].opcode == Opcode.SPUT_OBJECT) holders += reference.toString()
            }
        }
        for (caller in FixtureDex.methodsWhere(bundle, { true }) { holds(it, asked) }) {
            val code = caller.implementation!!.instructions.toList()
            for (at in code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == asked }) {
                assertEquals("${bundle.name}: the answer is moved", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                follow(caller, at + 1, "the provider's answer")
            }
        }
        assertTrue("${bundle.name}: the answer reaches ${consumers.size} spans and drawings", consumers.size >= 3)
        assertTrue("${bundle.name}: the quick picker's holder", holders.isNotEmpty())
        for (holder in holders) {
            val get = FixtureDex.methodsWhere(bundle, { true }) { holds(it, holder) }
            for (reader in get) {
                val code = reader.implementation!!.instructions.toList()
                code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT && (code[it] as ReferenceInstruction).reference.toString() == holder }
                    .forEach { follow(reader, it, "the held emoji typeface") }
            }
        }

        val owners = FixtureDex.classes(bundle, consumers)
        val context = PatchContexts.of(owners.values)
        for ((type, consumer) in owners) {
            assertTrue("${bundle.name}: $type sets a typeface on a paint", consumer.methods.any {
                holds(it, "Landroid/graphics/Paint;->setTypeface($TYPEFACE)$TYPEFACE")
            })
            for (method in context.mutableClassDefBy(type).methods) {
                assertEquals("${bundle.name}: $type->${method.name}: default reads sent", 0, method.sendDefaultReads(context.equalityChecks()))
                assertEquals("${bundle.name}: $type->${method.name}: text views sent", 0, method.sendTextViews())
            }
        }
    }

    /**
     * Each declared build makes its text layouts through Android's layout builder, choosing the
     * font padding on it, and its one-line text through BoringLayout.make, and every one of those
     * calls goes to the extension's stand-in (#96), with none of Android's left behind.
     */
    @Test
    fun `each declared build's text layout calls go to the extension`() = bundles { bundle ->
        val makers = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any { layoutCall(it) != null } == true
        }
        val calls = makers.flatMap { method -> method.implementation!!.instructions.mapNotNull(::layoutCall) }
        assertTrue("${bundle.name}: ${calls.count { it == OBTAIN_LAYOUT }} layouts obtained", calls.count { it == OBTAIN_LAYOUT } >= 30)
        assertTrue("${bundle.name}: ${calls.count { it == SET_INCLUDE_PAD }} padding choices", calls.count { it == SET_INCLUDE_PAD } >= 15)
        assertTrue("${bundle.name}: ${calls.count { it == BUILD_LAYOUT }} layouts built", calls.count { it == BUILD_LAYOUT } >= 30)
        assertEquals("${bundle.name}: every layout call, both one-line forms among them", LAYOUT_CALLS.size, LAYOUT_CALLS.keys.count { it in calls })

        val owners = FixtureDex.classes(bundle, makers.map { it.definingClass }.toSet())
        val context = PatchContexts.of(owners.values)
        assertEquals("${bundle.name}: calls sent", calls.sorted(), context.hookTextLayouts().sorted())
        for (type in owners.keys) {
            for (method in context.mutableClassDefBy(type).methods) {
                assertEquals("${bundle.name}: $type->${method.name} keeps a call of Android's", emptyList<String>(),
                    method.implementation?.instructions?.mapNotNull(::layoutCall).orEmpty())
            }
        }
    }

    /**
     * Each declared build fits text to a set line height with spans whose chooseHeight sets the
     * first line's top or the last line's bottom, the even split among them, which takes the shortfall
     * off the ascent and the descent alike and moves both edges with them (#96). Every such write goes
     * to the extension's stand-in on the write's own two registers, one instruction for one, and
     * nothing else in the span changes.
     */
    @Test
    fun `each declared build's line-height spans send their line edges to the extension`() = bundles { bundle ->
        val spans = FixtureDex.methodsWhere(bundle, { true }) { method ->
            isChooseHeight(method) && method.implementation?.instructions?.any { lineEdgeWrite(it) != null } == true
        }
        assertTrue("${bundle.name}: ${spans.size} line-height spans setting a line's edges", spans.size >= 3)
        val evenSplit = spans.filter { method ->
            val code = method.implementation!!.instructions.toList()
            val written = code.filter { it.opcode == Opcode.IPUT }
                .mapNotNull { ((it as ReferenceInstruction).reference as? FieldReference)?.name }
            written.containsAll(listOf("ascent", "descent", "top", "bottom")) &&
                code.any { (it as? ReferenceInstruction)?.reference?.toString() == "Ljava/lang/Math;->ceil(D)D" }
        }
        assertTrue("${bundle.name}: no even-split line-height span", evenSplit.isNotEmpty())

        val owners = FixtureDex.classes(bundle, spans.map { it.definingClass }.toSet())
        val context = PatchContexts.of(owners.values)
        val writes = spans.sumOf { span -> span.implementation!!.instructions.count { lineEdgeWrite(it) != null } }
        assertEquals("${bundle.name}: writes sent", writes, context.hookLineHeights())
        for (span in spans) {
            val where = "${bundle.name}: ${span.definingClass}"
            val before = span.implementation!!.instructions.toList()
            val after = context.mutableClassDefBy(span.definingClass).methods.single {
                isChooseHeight(it) && it.parameterTypes.joinToString("") == span.parameterTypes.joinToString("")
            }.implementation!!.instructions.toList()
            assertEquals("$where: one instruction for one", before.size, after.size)
            for (index in before.indices) {
                val edge = lineEdgeWrite(before[index])
                if (edge == null) {
                    assertEquals("$where: instruction $index", before[index].opcode, after[index].opcode)
                    continue
                }
                val write = before[index] as TwoRegisterInstruction
                val call = after[index] as FiveRegisterInstruction
                assertEquals("$where: stand-in at $index", LINE_EDGES.getValue(edge), (call as ReferenceInstruction).reference.toString())
                assertEquals("$where: registers at $index", listOf(write.registerA, write.registerB),
                    listOf(call.registerC, call.registerD).take(call.registerCount))
            }
        }
    }

    /**
     * Each declared build builds Android's text views, by `new` and as the super call of views of
     * its own, and its layout inflaters make views from a layout's tag through createView. The hook
     * runs over every class doing either, and each site gets the extension's call right after it,
     * on the site's register: the one the constructor got first, or the one the inflater's answer
     * moves into. Nothing else in those methods changes.
     */
    @Test
    fun `each declared build's text views go to the extension right after they're built`() = bundles { bundle ->
        fun sitesIn(code: List<Instruction>) = code.indices.mapNotNull { at ->
            builtTextView(code[at])
                ?: code.getOrNull(at + 1)?.takeIf { makesView(code[at]) && it.opcode == Opcode.MOVE_RESULT_OBJECT }
                    ?.let { (it as OneRegisterInstruction).registerA }
        }
        val builders = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any { builtTextView(it) != null || makesView(it) } == true
        }
        val sites = builders.sumOf { method -> sitesIn(method.implementation!!.instructions.toList()).size }
        val inflated = builders.sumOf { method -> method.implementation!!.instructions.count(::makesView) }
        assertTrue("${bundle.name}: $sites text views built", sites > 200)
        // 577's own inflater makes views from a tag, and 580 adds a factory that does it too.
        assertTrue("${bundle.name}: layout inflaters make views $inflated times", inflated >= 1)

        val owners = FixtureDex.classes(bundle, builders.map { it.definingClass }.toSet())
        val context = PatchContexts.of(owners.values)
        assertEquals("${bundle.name}: sites hooked", sites, context.hookTextViews())
        val hooks = setOf(OWN_TEXT_VIEW, OWN_INFLATED)
        for ((type, original) in owners) {
            for (method in context.mutableClassDefBy(type).methods) {
                val body = method.implementation?.instructions?.toList() ?: continue
                val where = "${bundle.name}: $type->${method.name}"
                val before = original.methods.single { it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString) &&
                    it.returnType == method.returnType }
                val code = before.implementation!!.instructions.toList()
                val at = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() in hooks }
                assertEquals("$where: the registers handed over", sitesIn(code), at.map { (body[it] as RegisterRangeInstruction).startRegister })
                for (hook in at) {
                    val prior = body[hook - 1]
                    assertTrue("$where: the hook at $hook follows what it hands over",
                        builtTextView(prior) != null || prior.opcode == Opcode.MOVE_RESULT_OBJECT && makesView(body[hook - 2]))
                    assertEquals("$where: one register", 1, (body[hook] as RegisterRangeInstruction).registerCount)
                }
                // The assembler pads a payload that follows the hook to its alignment with a nop, and
                // widens a goto the hook pushes out of reach (583's FbShortsMidcardPreviewRenderActivity
                // onActivityCreate: its loop's goto back grows to goto/16), which is the same jump.
                fun Opcode.normal() = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this
                val was = code.map { it.opcode }.filter { it != Opcode.NOP }.map { it.normal() }
                val left = body.filterIndexed { index, _ -> index !in at }.map { it.opcode }.filter { it != Opcode.NOP }.map { it.normal() }
                val first = (0 until maxOf(was.size, left.size)).firstOrNull { was.getOrNull(it) != left.getOrNull(it) }
                assertEquals("$where: nothing else changed, first difference at", null, first)
            }
        }
    }

    private fun checkResolver(bundle: File, owner: ClassDef) {
        val resolvers = typefaceResolvers(owner)
        assertEquals("${bundle.name}: typeface resolvers", 1, resolvers.size)
        val resolver = resolvers.single()
        assertTrue("${bundle.name}: the resolver needs three free locals", localRegisters(resolver) >= 3)
        assertTrue("${bundle.name}: the resolver returns an object", objectReturns(resolver).isNotEmpty())
        // At every object return the family and the weight are still in their own registers, and
        // v0 to v2 hold nothing the resolver reads afterwards.
        resolver.requireResolverHookFits()

        val type = familyType(resolver)
        val family = FixtureDex.classes(bundle, setOf(type)).getValue(type)
        assertEquals("${bundle.name}: the family is an enum", ENUM, family.superclass)
        val names = familyNames(family)
        assertEquals("${bundle.name}: Meta's interface families", metaFamilies.pinFor(bundle, "the swapped families"),
            names.filter(::isInterfaceFamily).toSortedSet())
        assertTrue("${bundle.name}: the creative families stay",
            "MONTSERRAT_REGULAR" in names && "COURIER_PRIME_BOLD" in names && "FACEBOOK_NARROW" in names)
        // The monospace families are declared and left alone: the negative control for the swap.
        assertEquals("${bundle.name}: the monospace families kept", listOf("OPTIMISTIC_MONO", "ROBOTO_MONO_REGULAR"),
            names.filter(::isMonospaceFamily).sorted())
        assertEquals("${bundle.name}: a monospace family is swapped", emptyList<String>(),
            names.filter { isMonospaceFamily(it) && isInterfaceFamily(it) })
        val withAxes = familiesWithAxes(family)
        assertEquals("${bundle.name}: the families with axes", axisFamilies.pinFor(bundle, "the families with axes"),
            withAxes.sorted())
        assertEquals("${bundle.name}: the families with axes the switch leaves alone", listOf("OPTIMISTIC_MONO"),
            withAxes.filterNot(::isInterfaceFamily))
    }

    private fun checkBuilders(bundle: File, owner: ClassDef) {
        val apiUtils = FixtureDex.classes(bundle, setOf(TYPEFACE_BUILDERS)).getValue(TYPEFACE_BUILDERS)
        val builders = builderClasses(apiUtils)
        assertEquals("${bundle.name}: an asset builder and a font file builder", 2, builders.size)

        // Every maker of a builder takes the family enum (580 takes it in the repository, 577 looked
        // it up by name in a helper), so a builder is only ever made for a family. Only families with
        // variable axes reach it, and since 587 one of those is OPTIMISTIC_MONO, so the patch hooks
        // each maker to tell the extension which family a builder is for.
        val makers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.methodSection.any { it.definingClass == TYPEFACE_BUILDERS && it.name == BUILDER_FACTORY }
        }) { method ->
            method.definingClass != TYPEFACE_BUILDERS && method.implementation?.instructions?.any(::callsBuilderFactory) == true
        }
        assertEquals("${bundle.name}: builder makers", builderMakerCount.pinFor(bundle, "the builder makers"), makers.size)
        val familyType = familyType(typefaceResolvers(owner).single())
        val strangers = makers.filterNot { maker -> maker.parameterTypes.count { it.toString() == familyType } == 1 }
            .map { "${it.definingClass}->${it.name}" }
        assertEquals("${bundle.name}: makers that don't take the family enum", emptyList<String>(), strangers)

        // Through the patcher: each maker hands every builder it returns, and its family, to the
        // extension and casts the answer back to its own return type.
        val context = PatchContexts.of(FixtureDex.classes(bundle, makers.map { it.definingClass }.toSet()).values.toList())
        assertEquals("${bundle.name}: builder makers hooked", makers.size, context.hookBuilderMakers(familyType))
        for (maker in makers) {
            val where = "${bundle.name}: ${maker.definingClass}->${maker.name}"
            val body = context.mutableClassDefBy(maker.definingClass).methods
                .single { it.name == maker.name && it.parameterTypes.map(Any::toString) == maker.parameterTypes.map(Any::toString) }
                .implementation!!.instructions.toList()
            val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
            assertEquals("$where: returns", objectReturns(maker).size, returns.size)
            for (at in returns) {
                val returned = (body[at] as OneRegisterInstruction).registerA
                assertEquals("$where: cast before the return at $at", listOf(Opcode.CHECK_CAST, returned, maker.returnType),
                    listOf(body[at - 1].opcode, (body[at - 1] as OneRegisterInstruction).registerA, (body[at - 1] as ReferenceInstruction).reference.toString()))
                assertEquals("$where: answer before the return at $at", listOf(Opcode.MOVE_RESULT_OBJECT, returned),
                    listOf(body[at - 2].opcode, (body[at - 2] as OneRegisterInstruction).registerA))
                assertEquals("$where: the extension's call before the return at $at", REMEMBER_FAMILY,
                    (body[at - 3] as ReferenceInstruction).reference.toString())
            }
        }

        val chains = chains(bundle, builders)
        for (builder in builders) {
            val chain = chains.getValue(builder)
            val builds = chain.flatMap(::buildMethods)
            assertTrue("${bundle.name}: $builder builds nothing", builds.isNotEmpty())
            builds.forEach { assertTrue("${bundle.name}: a build needs two free locals", localRegisters(it) >= 2) }
            // And at each of its object returns `this` is still the builder, and v0 and v1 are free.
            builds.forEach { it.requireBuilderHookFits() }
            assertTrue("${bundle.name}: $builder takes no variation string", chain.flatMap(::variationSetters).isNotEmpty())
        }
    }

    /** Each builder with its superclasses, nearest first, as far as the app's own classes go. */
    private fun chains(bundle: File, builders: List<String>): Map<String, List<ClassDef>> {
        val loaded = FixtureDex.classes(bundle, builders.toSet()).toMutableMap()
        while (true) {
            val wanted = loaded.values.mapNotNull { it.superclass }.filter { !isObject(it) && it !in loaded }.toSet()
            if (wanted.isEmpty()) break
            val found = FixtureDex.classes(bundle, wanted)
            if (found.isEmpty()) break
            loaded += found
        }
        return builders.associateWith { builder ->
            generateSequence(loaded[builder]) { at -> at.superclass?.let { loaded[it] } }.toList()
        }
    }

    /**
     * React Native's resolver on each declared build: one class holds the variation refusal, and one
     * of its methods has the resolver's shape and loads assets. It reads React Native's font manager,
     * the singleton whose class names the asset suffixes, and React Native's text layout, its spans
     * and its text inputs all call it. Its family name is intact at each return and v0 and v1 are
     * free there, and the patch's hook goes in at every return.
     */
    @Test
    fun `each declared build has one React Native typeface resolver the hook fits`() = bundles { bundle ->
        val owners = FixtureDex.classesHolding(bundle, INVALID_FONT_VARIATION)
        assertEquals("${bundle.name}: classes holding the variation refusal", 1, owners.size)
        val owner = owners.single()
        val refusers = owner.methods.filter { holdsString(it, INVALID_FONT_VARIATION) }
        assertEquals("${bundle.name}: the parse and the apply log it", 2, refusers.size)
        assertTrue("${bundle.name}: both log it as ReactNative", refusers.all { holdsString(it, "ReactNative") })

        val resolvers = reactNativeResolvers(owner)
        assertEquals("${bundle.name}: React Native typeface resolvers", 1, resolvers.size)
        val resolver = resolvers.single()
        assertTrue("${bundle.name}: the resolver needs two free locals", localRegisters(resolver) >= 2)
        assertTrue("${bundle.name}: two returns, the cached or created answer and the asset's",
            objectReturns(resolver).size >= 2)
        resolver.requireReactNativeHookFits()
        checkFontManager(bundle, resolver)
        checkReactNativeCallers(bundle, resolver)

        val context = PatchContexts.of(listOf(owner))
        context.hookReactNativeFonts()
        val body = context.mutableClassDefBy(owner.type).methods.single { it.name == resolver.name &&
            it.parameterTypes.map { type -> type.toString() } == resolver.parameterTypes.map { type -> type.toString() } }
            .implementation!!.instructions.toList()
        val family = localRegisters(resolver) + REACT_FAMILY
        val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("${bundle.name}: a return lost its hook or gained one", objectReturns(resolver).size, returns.size)
        for (at in returns) {
            val answer = (body[at] as OneRegisterInstruction).registerA
            val where = "${bundle.name}: the return at $at"
            assertEquals("$where: move-result", Opcode.MOVE_RESULT_OBJECT, body[at - 1].opcode)
            assertEquals("$where: back into its own register", answer, (body[at - 1] as OneRegisterInstruction).registerA)
            val call = body[at - 2] as FiveRegisterInstruction
            assertEquals("$where: calls the extension", REPLACE_REACT_NATIVE, (call as ReferenceInstruction).reference.toString())
            assertEquals("$where: with v0 and v1", listOf(0, 1), listOf(call.registerC, call.registerD))
            val copy = body[at - 3] as TwoRegisterInstruction
            assertEquals("$where: the family copied down", listOf(Opcode.MOVE_OBJECT_FROM16, 1, family),
                listOf(copy.opcode, copy.registerA, copy.registerB))
        }
    }

    /**
     * The resolver reads React Native's font manager: a singleton, held in a static field of its own
     * class, whose static initializer lists the style suffixes and file extensions it tries on the
     * app's font assets. Facebook's font prefetcher registers Meta's families there.
     */
    private fun checkFontManager(bundle: File, resolver: Method) {
        // A singleton of the app's own, which Typeface.DEFAULT, read there too, is not.
        val managers = resolver.implementation!!.instructions.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                instruction.opcode == Opcode.SGET_OBJECT && it.type == it.definingClass &&
                    !it.definingClass.startsWith("Landroid/") && !it.definingClass.startsWith("Ljava/")
            }?.definingClass
        }.distinct()
        assertEquals("${bundle.name}: the font manager singletons the resolver reads", 1, managers.size)
        val manager = FixtureDex.classes(bundle, managers.toSet()).getValue(managers.single())
        val initializer = manager.methods.single { it.name == "<clinit>" }
        for (literal in listOf("_bold", "_italic", "_bold_italic", ".ttf", ".otf")) {
            assertTrue("${bundle.name}: the font manager doesn't list \"$literal\"", holdsString(initializer, literal))
        }
    }

    /** React Native's text layout, a text span and a text input ask the resolver; the layout is the one handed the assets and the paint. */
    private fun checkReactNativeCallers(bundle: File, resolver: Method) {
        val callers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.methodSection.any { it.definingClass == resolver.definingClass && it.name == resolver.name }
        }) { method ->
            method.implementation?.instructions?.any { instruction ->
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                call?.definingClass == resolver.definingClass && call.name == resolver.name &&
                    call.parameterTypes.map { it.toString() } == resolver.parameterTypes.map { it.toString() }
            } == true
        }
        assertTrue("${bundle.name}: ${callers.size} callers", callers.size >= 3)
        // 584 renamed the layout's updateTextPaint; it still takes the app's assets and the paint.
        assertTrue("${bundle.name}: React Native's text layout doesn't ask it: ${callers.map { it.name }}",
            callers.any {
                it.name.startsWith("updateTextPaint") || it.parameterTypes.map(CharSequence::toString)
                    .containsAll(listOf("Landroid/content/res/AssetManager;", "Landroid/text/TextPaint;"))
            })
    }
}
