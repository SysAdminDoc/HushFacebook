/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.tableIndexAt
import app.morphe.patches.facebook.feed.tableIndices
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Whether [method] holds one of the strings beside the feed post's state, where the fold hooks go. */
internal fun foldMarked(method: Method) = loadsString(method, TOGGLE) || loadsString(method, COMPONENT_SPEC)

/**
 * The classes the fold's finder reads out of [found]: those holding the strings beside it, and the
 * state classes whose booleans the component's first method sets (they hold no string of their own).
 */
internal fun foldTypes(found: List<Method>): Set<String> {
    val marked = found.filter(::foldMarked)
    val states = marked.filter { loadsString(it, COMPONENT_SPEC) }.flatMap { method ->
        method.implementation!!.instructions.filter { it.opcode == Opcode.IPUT_BOOLEAN }
            .map { ((it as ReferenceInstruction).reference as FieldReference).definingClass }
    }
    return (marked.map { it.definingClass } + states).toSet()
}

/** Whether [method] sends the reel caption's "more" update, where the caption's component is named. */
internal fun captionMarked(method: Method) = loadsString(method, CAPTION_TOGGLE)

/** The components named right after [CAPTION_TOGGLE] in [found]. */
internal fun captionComponents(found: List<Method>): Set<String> = found.filter(::captionMarked).flatMap { method ->
    val code = method.implementation!!.instructions.toList()
    code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == CAPTION_TOGGLE }
        .map { ((code[it + 1] as ReferenceInstruction).reference as TypeReference).type }
}.toSet()

/**
 * The methods asking a string table among [found] for one of [literals]. Redex moved [TOGGLE] into a
 * table on 586 (`LX/01AE;->A00` arm64), and a method asking it for the toggle (`LX/040m;->A19`) holds
 * no string of its own, so the finder reads it only when its class is there.
 */
internal fun tableAskers(bundle: File, found: List<Method>, literals: Set<String>): List<Method> {
    val tables = found.filter { method -> isStringTable(method) && literals.any { loadsString(method, it) } }
    if (tables.isEmpty()) return emptyList()
    val indices = tables.associate { table -> "${table.definingClass}->${table.name}" to literals.flatMap { tableIndices(table, it) }.toSet() }
    val types = tables.map { it.definingClass }.toSet()
    return FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in types } }) { method ->
        val code = method.implementation?.instructions?.toList() ?: return@methodsWhere false
        code.indices.any { at ->
            val index = tableIndexAt(code, at) ?: return@any false
            val call = (code[at] as ReferenceInstruction).reference as MethodReference
            indices["${call.definingClass}->${call.name}"]?.contains(index) == true
        }
    }
}

/** The classes whose booleans [components] set: their state classes. */
internal fun captionStates(components: Collection<ClassDef>): Set<String> = components.flatMap { it.methods }.flatMap { method ->
    method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.IPUT_BOOLEAN }
        .map { ((it as ReferenceInstruction).reference as FieldReference).definingClass }
}.toSet() - components.map { it.type }.toSet()

/** The classes the caption's finder reads out of [bundle]: the update's senders, the component and its state classes. */
internal fun captionTypes(bundle: File, found: List<Method>): Set<String> {
    val components = captionComponents(found)
    return found.filter(::captionMarked).map { it.definingClass }.toSet() + components +
        captionStates(FixtureDex.classes(bundle, components).values)
}

/**
 * Show full post text's anchors on every Facebook build the bundle declares: the one method shaped
 * like the feed's text cut that reads the line limit, with the cut's answer and the story in the
 * registers each build keeps them in, and the feed post component and its state, where a post's
 * "allow truncation" starts and flips. Then the patch on them: the cut's answer goes through the
 * extension with the story right after it lands and before the null check reads it, the first value
 * goes through it before it's stored, and each flip is handed over with the post's story right after
 * it's stored. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ShowFullPostTextFixtureTest {
    /** Where one build keeps the cut and the post's state. */
    private data class Place(val builder: String, val cut: Int, val story: Int, val component: String, val state: String)

    /** Each declared build's places, by ABI. */
    private val expected = mapOf(
        "585.0.0.47.54" to mapOf(
            "arm64-v8a" to Place("LX/03Bn;", 5, 2, "LX/02iG;", "LX/039x;"),
            "armeabi-v7a" to Place("LX/03Bn;", 5, 2, "LX/02iG;", "LX/039x;"),
            "arm64-v8a-minapi28-storeless" to Place("LX/03CW;", 5, 2, "LX/02iz;", "LX/03Ag;"),
        ),
        "586.0.0.52.53" to mapOf(
            "arm64-v8a" to Place("LX/041q;", 5, 2, "LX/02za;", "LX/040k;"),
            "armeabi-v7a" to Place("LX/041q;", 5, 2, "LX/02za;", "LX/040k;"),
        ),
    )

    /** Each declared build's reel caption component, by ABI. */
    private val captionComponentOf = mapOf(
        "585.0.0.47.54" to mapOf("arm64-v8a" to "LX/09as;", "armeabi-v7a" to "LX/09ax;",
            "arm64-v8a-minapi28-storeless" to "LX/09bQ;"),
        "586.0.0.52.53" to mapOf("arm64-v8a" to "LX/09Dk;", "armeabi-v7a" to "LX/09Dk;"),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build cuts post text once, and the cut, the first state and each tap go through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val found = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && (cutShaped(it) || foldMarked(it) || captionMarked(it))
                }
                val methods = found.filter(::cutShaped)
                assertEquals("$name: methods shaped like the text cut", 1, methods.size)
                val cut = textCut(methods.single())
                val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                val captionComponent = captionComponentOf[version]?.get(abi)
                val components = captionComponents(found)
                assertEquals("$name: the reel caption's component", setOf(captionComponent), components)
                val states = captionStates(FixtureDex.classes(bundle, components).values)
                // A table holding the toggle is asked for it, and the finder reads the asker beside the toggle's own loaders.
                val askers = tableAskers(bundle, found, setOf(TOGGLE, CAPTION_TOGGLE))
                if (found.any { isStringTable(it) && loadsString(it, TOGGLE) }) {
                    assertTrue("$name: a string table holds the toggle, but nothing asks it for it", askers.isNotEmpty())
                }
                val classes = FixtureDex.classes(bundle, foldTypes(found) + cut.method.definingClass + captionTypes(bundle, found) +
                    askers.map { it.definingClass })

                // Negative control: without the state class that flips it, no caption state is taken.
                val stateless = PatchContexts.of(classes.filterKeys { it !in states }.values.toList())
                assertThrows("$name: a caption state was found with no state class flipping it", PatchException::class.java) {
                    stateless.findCaptionStart()
                }
                val owner = classes.getValue(cut.method.definingClass)
                val original = cut.method.code()

                val context = PatchContexts.of((classes.values + ExtensionDex.classDef(SETTINGS_STATUS)).toList())
                val fold = context.findFold(cut.storyField)
                assertEquals("$name: where the cut and the post's state are kept",
                    expected[version]?.get(abi),
                    Place(cut.method.definingClass, cut.register, cut.story, fold.first.definingClass, fold.tap.definingClass))
                assertEquals("$name: the story's field", "Ljava/lang/Object;", cut.storyField.type)
                val firstOriginal = fold.first.code()
                val tapOriginal = fold.tap.code()
                val caption = context.findCaptionStart()
                assertEquals("$name: the caption's first state is set by its component", captionComponent, caption.method.definingClass)
                val captionOriginal = caption.method.code()
                val prop = captionOriginal[caption.index]
                assertEquals("$name: the caption's prop is read", Opcode.IGET_BOOLEAN, prop.opcode)
                assertEquals(captionComponent, ((prop as ReferenceInstruction).reference as FieldReference).definingClass)

                showFullPostTextPatch.execute(context)

                // A reel caption's first "expanded" goes through the extension right after the prop is read.
                val captionPatched = context.mutableClassDefBy(caption.method.definingClass).methods.single {
                    it.name == caption.method.name && it.parameterTypes == caption.method.parameterTypes
                }.code()
                assertEquals("$name: two instructions for the caption", captionOriginal.size + 2, captionPatched.size)
                val captionCall = captionPatched[caption.index + 1]
                assertTrue("$name: the caption's prop goes through the extension", captionCall.names(CAPTION_FIRST_STATE))
                assertEquals(caption.register to 1, (captionCall as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
                assertEquals(Opcode.MOVE_RESULT, captionPatched[caption.index + 2].opcode)
                assertEquals(caption.register, (captionPatched[caption.index + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest of the caption's first state is Facebook's",
                    captionOriginal.map { it.opcode }, captionPatched.filterIndexed { at, _ -> at != caption.index + 1 && at != caption.index + 2 }.map { it.opcode })

                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == cut.method.name && it.parameterTypes == cut.method.parameterTypes
                }.code()
                assertEquals("$name: two instructions for the hook", original.size + 2, patched.size)
                val calls = patched.withIndex().filter { it.value.names(CUT) }
                assertEquals("$name: calls of the extension", 1, calls.size)
                val (at, call) = calls.single()
                assertEquals("$name: the hook's place", cut.hookIndex, at)
                assertEquals("$name: the call", Opcode.INVOKE_STATIC, call.opcode)
                val handed = call as FiveRegisterInstruction
                assertEquals("$name: the cut and the story handed over", listOf(cut.register, cut.story),
                    listOf(handed.registerC, handed.registerD).take(handed.registerCount))
                assertEquals("$name: right after the cut's own answer", Opcode.MOVE_RESULT_OBJECT, patched[at - 1].opcode)
                assertEquals(cut.register, (patched[at - 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
                assertEquals("$name: the extension's answer lands where the null check reads it", cut.register,
                    (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the null check after the hook", Opcode.IF_EQZ, patched[at + 2].opcode)
                assertEquals(cut.register, (patched[at + 2] as OneRegisterInstruction).registerA)

                // A post's first "allow truncation" goes through the extension before it's stored.
                val first = context.mutableClassDefBy(fold.first.definingClass).methods.single {
                    it.name == fold.first.name && it.parameterTypes == fold.first.parameterTypes
                }.code()
                assertEquals("$name: two instructions for the first state", firstOriginal.size + 2, first.size)
                val start = first.indexOfFirst { it.names(FIRST_STATE) }
                assertEquals("$name: the first state's hook", fold.firstIndex, start)
                val startCall = first[start] as RegisterRangeInstruction
                assertEquals("$name: the first value handed over", fold.firstValue to 1, startCall.startRegister to startCall.registerCount)
                assertEquals(Opcode.MOVE_RESULT, first[start + 1].opcode)
                assertEquals(fold.firstValue, (first[start + 1] as OneRegisterInstruction).registerA)
                val stored = first[start + 2]
                assertEquals("$name: the first value is stored right after", Opcode.IPUT_BOOLEAN, stored.opcode)
                assertEquals(fold.firstValue, (stored as TwoRegisterInstruction).registerA)
                assertEquals(tapOriginal[fold.tapIndex].let { (it as ReferenceInstruction).reference.toString() },
                    (stored as ReferenceInstruction).reference.toString())

                // Each flip is handed over with the post's story right after it's stored.
                val tap = context.mutableClassDefBy(fold.tap.definingClass).methods.single {
                    it.name == fold.tap.name && it.parameterTypes == fold.tap.parameterTypes
                }.code()
                assertEquals("$name: two instructions for the tap", tapOriginal.size + 2, tap.size)
                val flip = tap.indexOfFirst { it.names(TAP) }
                assertEquals("$name: the tap's hook", fold.tapIndex + 2, flip)
                val flipped = tap[flip - 2]
                assertEquals("$name: the flip is stored right before", Opcode.IPUT_BOOLEAN, flipped.opcode)
                assertEquals(fold.tapValue, (flipped as TwoRegisterInstruction).registerA)
                val story = tap[flip - 1]
                assertEquals("$name: the story read from the post's props", Opcode.IGET_OBJECT, story.opcode)
                assertEquals(cut.storyField.toString(), (story as ReferenceInstruction).reference.toString())
                assertEquals(fold.props to fold.props, (story as TwoRegisterInstruction).registerA to story.registerB)
                val flipCall = tap[flip] as FiveRegisterInstruction
                assertEquals("$name: the story and the new state handed over", listOf(fold.props, fold.tapValue),
                    listOf(flipCall.registerC, flipCall.registerD).take(flipCall.registerCount))
                assertEquals("$name: the tap goes on as before", tapOriginal[fold.tapIndex + 1].opcode, tap[flip + 1].opcode)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "fullPostText" }
                assertEquals("$name: SettingsStatus.fullPostText() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
