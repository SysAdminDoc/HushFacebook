/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Translate posts on every Facebook build the bundle declares: the post's text goes through the
 * extension before anything in the builder reads it, every return goes through it, a post opened to
 * its whole text goes through it outside the builder, and Show full post text's hook lands on its
 * own spot whichever of the two patches runs first. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TranslatePostsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    /**
     * Per build, how many places besides the builder show a post's whole text, and what the ones that
     * only measure it answer. 585 had the two that show it: the renderer a tap on a cut post takes, and
     * the whole text the feed builds beside the cut one. 587 also runs the text through the step to ask
     * whether it runs past its lines (a yes/no) and to pick how the post opens (a number).
     */
    private val wholeTextPlaces = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to (2 to listOf("Ljava/lang/Integer;", "Z")),
            "armeabi-v7a" to (2 to listOf("Ljava/lang/Integer;", "Z")),
        ),
    )

    private fun callsOf(method: Method): List<MethodReference> = method.implementation?.instructions
        ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()

    /**
     * Every method [start] reaches by calls that name it, [depth] calls down. A call through an
     * interface names the interface's method, so whatever answers it isn't followed.
     */
    private fun reachedFrom(bundle: File, start: Method, depth: Int): Set<String> {
        val reached = mutableSetOf<String>()
        // By class, the methods to read next.
        var next: Map<String, Set<String>> = callsOf(start).filter { reached.add(it.toString()) }
            .groupBy({ it.definingClass }, { it.toString() }).mapValues { it.value.toSet() }
        repeat(depth - 1) {
            val found = mutableMapOf<String, MutableSet<String>>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val wanted = next[classDef.type] ?: continue
                    for (method in classDef.methods.filter { it.toString() in wanted }) {
                        for (call in callsOf(method)) {
                            val named = call.toString()
                            if (reached.add(named)) found.getOrPut(call.definingClass) { mutableSetOf() } += named
                        }
                    }
                }
            }
            next = found
        }
        return reached
    }

    /** The builder after [patches], with the feed post component and its state ([foldTypes]) there for Show full post text. */
    private fun builderAfter(bundle: File, patches: List<BytecodePatch>, builder: Method, foldTypes: Set<String>): List<Instruction> {
        val classes = FixtureDex.classes(bundle, setOf(builder.definingClass, FRAGMENT_ACTIVITY) + foldTypes)
        val owner = classes.getValue(builder.definingClass)
        val context = PatchContexts.of((classes.values + ExtensionDex.classDef(SETTINGS_STATUS)).toList())
        patches.forEach { it.execute(context) }
        return context.mutableClassDefBy(owner.type).methods.single {
            it.name == builder.name && it.parameterTypes == builder.parameterTypes
        }.code()
    }

    @Test
    fun `each declared build runs a post's text and every return through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val found = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && (cutShaped(it) || foldMarked(it) || captionMarked(it))
                }
                val builder = found.filter(::cutShaped).single()
                // Show full post text's fold and reel caption classes, for running it after or before.
                val folds = foldTypes(found) + captionTypes(bundle, found)
                val original = builder.code()
                val returns = original.count { it.opcode == Opcode.RETURN_OBJECT }
                assertTrue("$name: the builder has no return", returns > 0)
                val textRegister = builder.implementation!!.registerCount - builder.parameterTypes.size

                val classes = FixtureDex.classes(bundle, setOf(builder.definingClass, FRAGMENT_ACTIVITY))
                val owner = classes.getValue(builder.definingClass)
                val activity = classes.getValue(FRAGMENT_ACTIVITY)
                val dispatch = touchDispatches(activity).single()
                val context = PatchContexts.of(listOf(owner, activity, ExtensionDex.classDef(SETTINGS_STATUS)))
                translatePostsPatch.execute(context)
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == builder.name && it.parameterTypes == builder.parameterTypes
                }.code()

                // Every touch reaches the line's clock first, read through the range form, and goes on unchanged.
                val touch = context.mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single {
                    it.name == dispatch.name && it.parameterTypes == dispatch.parameterTypes
                }.code()
                assertTrue("$name: the touch clock comes first", touch[0].names(TRANSLATION_TOUCH))
                val event = touch[0] as RegisterRangeInstruction
                assertEquals("$name: the event is handed over", (dispatch.implementation!!.registerCount - 1) to 1,
                    event.startRegister to event.registerCount)
                assertEquals("$name: one instruction in dispatchTouchEvent", dispatch.code().size + 1, touch.size)

                assertEquals("$name: two instructions per hook", original.size + 2 * (returns + 1), patched.size)
                assertTrue("$name: the text's hook comes first", patched[0].names(TRANSLATION_TEXT))
                val range = patched[0] as RegisterRangeInstruction
                assertEquals("$name: the post's text is handed over", textRegister to 1, range.startRegister to range.registerCount)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, patched[1].opcode)
                assertEquals("$name: the answer replaces the text", textRegister, (patched[1] as OneRegisterInstruction).registerA)

                val answers = patched.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
                assertEquals("$name: returns", returns, answers.size)
                for ((at, instruction) in answers) {
                    val register = (instruction as OneRegisterInstruction).registerA
                    assertTrue("$name: the return at $at isn't hooked", patched[at - 2].names(TRANSLATION_SHOWN))
                    val handed = patched[at - 2] as RegisterRangeInstruction
                    assertEquals("$name: the return's value is handed over", register to 1, handed.startRegister to handed.registerCount)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, patched[at - 1].opcode)
                    assertEquals(register, (patched[at - 1] as OneRegisterInstruction).registerA)
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "translatePosts" }
                assertEquals("$name: SettingsStatus.translatePosts() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)

                // Either order, Show full post text's hook stays right before its null check, on the cut's answer.
                for (order in listOf(listOf(translatePostsPatch, showFullPostTextPatch), listOf(showFullPostTextPatch, translatePostsPatch))) {
                    val both = builderAfter(bundle, order, builder, folds)
                    val at = both.indexOfFirst { it.names(CUT) }
                    assertTrue("$name: no cut hook after ${order.map { it.name }}", at > 0)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, both[at - 1].opcode)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, both[at + 1].opcode)
                    assertEquals(Opcode.IF_EQZ, both[at + 2].opcode)
                    assertEquals("$name: the text's hook after ${order.map { it.name }}", true, both[0].names(TRANSLATION_TEXT))
                    assertEquals("$name: hooks after ${order.map { it.name }}", original.size + 2 * (returns + 2), both.size)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build translates a post opened to its whole text`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val builder = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && cutShaped(it)
                }.single()
                val step = wholeTextStep(builder)
                assertTrue("$name: the builder runs its text through no step of its class", step != null)
                val callsStep = { instruction: Instruction -> instruction.names(step.toString()) }
                // The builder is an ImmutableMethod, its parameters not Strings, so signatures compare and lists don't.
                val callers = FixtureDex.methodsWhere(bundle, { true }) { method ->
                    !method.definingClass.startsWith(EXTENSION_CLASSES) && method.implementation != null &&
                        !(method.definingClass == builder.definingClass && MethodUtil.methodSignaturesMatch(method, builder)) &&
                        method.implementation!!.instructions.any(callsStep)
                }
                val classes = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet() + builder.definingClass + FRAGMENT_ACTIVITY)
                val context = PatchContexts.of((classes.values + ExtensionDex.classDef(SETTINGS_STATUS)).toList())
                translatePostsPatch.execute(context)
                // The builder keeps its own hooks only, so a text it runs through the step goes through the extension once.
                val builderCode = builder.code()
                val builderReturns = builderCode.count { it.opcode == Opcode.RETURN_OBJECT }
                assertEquals("$name: the builder's hooks", builderCode.size + 2 * (builderReturns + 1),
                    context.mutableClassDefBy(builder.definingClass).methods.single {
                        it.name == builder.name && MethodUtil.methodSignaturesMatch(it, builder)
                    }.code().size)
                val after = callers.associateWith { caller ->
                    context.mutableClassDefBy(caller.definingClass).methods.single {
                        it.name == caller.name && MethodUtil.methodSignaturesMatch(it, caller)
                    }.code()
                }
                // The places that show the whole text take the hooks. The ones that only measure it for the cut
                // answer a yes/no or a number and stay as they were, down to the instruction.
                val (measuring, showing) = callers.partition { after.getValue(it).size == it.code().size }
                val (shown, measured) = wholeTextPlaces.getValue(version)[name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("$name has no pin")
                assertEquals(
                    "$name: the places showing a post's whole text ${showing.map { it.toString() }}, and what the ones " +
                        "measuring it answer ${measuring.map { it.toString() }}",
                    shown to measured, showing.size to measuring.map { it.returnType }.sorted(),
                )
                // The extension keeps one note per thread from the text's hook to the return's. A hooked place the
                // builder reaches while it builds would take the builder's note, and its post would lose its line.
                val reached = reachedFrom(bundle, builder, 3)
                for (caller in showing) {
                    assertTrue("$name: $caller is hooked and runs inside the builder's own build", caller.toString() !in reached)
                }
                for (caller in showing) {
                    val original = caller.code()
                    val calls = original.count(callsStep)
                    val patched = after.getValue(caller)
                    val where = "$name: ${caller.definingClass}->${caller.name}"
                    assertEquals("$where: four instructions per call", original.size + 4 * calls, patched.size)
                    for ((at, call) in patched.withIndex().filter { callsStep(it.value) }) {
                        // 587's step is a virtual method of the builder's class, so its text comes after `this`.
                        val skip = if (call.opcode == Opcode.INVOKE_VIRTUAL || call.opcode == Opcode.INVOKE_VIRTUAL_RANGE) 1 else 0
                        val text = (call as? RegisterRangeInstruction)?.let { it.startRegister + skip }
                            ?: (call as FiveRegisterInstruction).let { if (skip == 1) it.registerD else it.registerC }
                        assertTrue("$where: the text going in at $at isn't hooked", patched[at - 2].names(TRANSLATION_TEXT))
                        assertEquals("$where: the text is handed over", text, (patched[at - 2] as RegisterRangeInstruction).startRegister)
                        assertEquals("$where: the answer replaces the text", text, (patched[at - 1] as OneRegisterInstruction).registerA)
                        val result = (patched[at + 1] as OneRegisterInstruction).registerA
                        assertTrue("$where: what comes back at $at isn't hooked", patched[at + 2].names(TRANSLATION_SHOWN))
                        assertEquals("$where: the step's answer is handed over", result, (patched[at + 2] as RegisterRangeInstruction).startRegister)
                        assertEquals(Opcode.MOVE_RESULT_OBJECT, patched[at + 3].opcode)
                        assertEquals(result, (patched[at + 3] as OneRegisterInstruction).registerA)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
