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
                assertTrue("$name: the builder runs its text through no static step of its class", step != null)
                val callsStep = { instruction: Instruction -> instruction.names(step.toString()) }
                // The builder is an ImmutableMethod, its parameters not Strings, so signatures compare and lists don't.
                val callers = FixtureDex.methodsWhere(bundle, { true }) { method ->
                    !method.definingClass.startsWith(EXTENSION_CLASSES) && method.implementation != null &&
                        !(method.definingClass == builder.definingClass && MethodUtil.methodSignaturesMatch(method, builder)) &&
                        method.implementation!!.instructions.any(callsStep)
                }
                // 585: the renderer a tap on a cut post takes, and the whole text the feed builds beside the cut one.
                // The Android 9 build folds that renderer's cut into the builder, which runs both texts through the
                // step itself, and the two outside it are left.
                assertTrue("$name: expected two places building a post's whole text, found ${callers.size}", callers.size >= 2)

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
                for (caller in callers) {
                    val original = caller.code()
                    val calls = original.count(callsStep)
                    val patched = context.mutableClassDefBy(caller.definingClass).methods.single {
                        it.name == caller.name && it.parameterTypes == caller.parameterTypes
                    }.code()
                    val where = "$name: ${caller.definingClass}->${caller.name}"
                    assertEquals("$where: four instructions per call", original.size + 4 * calls, patched.size)
                    for ((at, call) in patched.withIndex().filter { callsStep(it.value) }) {
                        val text = (call as? RegisterRangeInstruction)?.startRegister
                            ?: (call as FiveRegisterInstruction).registerC
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
