/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.history

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.analytics.KEEP_EVENT_OUT
import app.morphe.patches.facebook.misc.analytics.PRIVACY_CONTEXT
import app.morphe.patches.facebook.misc.analytics.keepEventsOut
import app.morphe.patches.facebook.misc.analytics.notLoggedEvent
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Don't save search history on every Facebook build the bundle declares. One method holds the
 * name of the mutation that adds a search to your history, answers with a String and takes two
 * booleans last. The register the patch computes for the first of them is p-numbered from the end
 * the way the method's own code reads it, Facebook tests it with if-nez, and the first such test
 * jumps straight to the method's answer, skipping the mutation: it really is Facebook's own "don't
 * save this one". The hook hands that register to the extension as a range call first thing and
 * keeps the answer in it. After that test, the one put of the Recent list into a map of the
 * method's own class goes to the extension with the same registers, and the rest of Facebook's
 * code stays as it was.
 *
 * Profile visits (#221): exactly one method has the analytics event factory's shape, its class
 * implements the interface the profile asks for its visit event through, and both of that class's
 * event makers call it. The hook asks the extension with the event's name first thing and, on a
 * true, answers the factory's own "not logged" event, leaving the rest of its code as it was.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class DontSaveSearchHistoryFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun save(bundle: File) {
        val holders = FixtureDex.classesHolding(bundle, ADD_RECENT_SEARCH).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val save = recentSearchSave(holders)
        val where = "${bundle.name}: ${save.descriptor()}"
        assertFalse("$where is an instance method", AccessFlags.STATIC.isSet(save.accessFlags))
        val types = save.parameterTypes.map(CharSequence::toString)
        assertEquals("$where takes two booleans last", listOf("Z", "Z"), types.takeLast(2))

        val register = skipRegister(save)
        val code = save.implementation!!
        // p0 is the instance, so the first of the two booleans is p(size - 1) with every parameter one register wide.
        assertTrue("$where takes no long or double", types.none { it == "J" || it == "D" })
        assertEquals("$where: the first of the two booleans is p${types.size - 1}",
            code.registerCount - (types.size + 1) + (types.size - 1), register)

        val instructions = code.instructions.toList()
        val tests = instructions.indices.filter {
            instructions[it].opcode == Opcode.IF_NEZ && (instructions[it] as OneRegisterInstruction).registerA == register
        }
        assertTrue("$where tests v$register with if-nez", tests.isNotEmpty())
        val first = tests.first()
        val jump = ControlFlow.of(save).normal[first].single { it != first + 1 }
        assertEquals("$where: a true there answers right away, before the mutation is built", Opcode.RETURN_OBJECT,
            instructions[jump].opcode)
        val named = instructions.indexOfFirst {
            it.opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
                (it as ReferenceInstruction).reference.toString() == ADD_RECENT_SEARCH
        }
        assertTrue("$where: the mutation is named after that first test", named > first)

        // The Recent list's put comes after that first test, on the path that keeps the local list.
        val put = recentListPut(save)
        assertTrue("$where puts the Recent list into a map of its own class once", put != null)
        assertTrue("$where: the put comes after the first test", put!! > first)

        val owner = FixtureDex.classes(bundle, setOf(save.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == save.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.keepRecentAt(put)
        method.askFirst(register)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        val kept = patched[put + 2]
        assertEquals("$where: the put goes to the extension", KEEP_RECENT, (kept as ReferenceInstruction).reference.toString())
        assertEquals("$where: in the put's own call form",
            if (original[put] is RegisterRangeInstruction) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC, kept.opcode)
        assertEquals("$where: with the put's own registers", callRegisters(original[put]), callRegisters(kept))
        assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
        assertEquals("$where: the extension is asked first", SKIP_SAVE, (patched[0] as ReferenceInstruction).reference.toString())
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: with Facebook's own boolean", register, call.startRegister)
        assertEquals("$where: and nothing else", 1, call.registerCount)
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        assertEquals("$where: in the same register", register, (patched[1] as OneRegisterInstruction).registerA)
        assertEquals("$where: the rest of Facebook's code stays",
            original.filterIndexed { index, _ -> index != put }.map { it.opcode },
            patched.drop(2).filterIndexed { index, _ -> index != put }.map { it.opcode })
    }

    private fun visits(bundle: File) {
        val factories = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == PRIVACY_CONTEXT } }) {
            !it.definingClass.startsWith(EXTENSION_CLASSES) && notLoggedEvent(it) != null
        }
        assertEquals("${bundle.name}: one analytics event factory, found ${factories.map { it.descriptor() }}", 1, factories.size)
        val factory = factories.single()
        val where = "${bundle.name}: ${factory.descriptor()}"
        val notLogged = notLoggedEvent(factory)!!
        val owner = FixtureDex.classes(bundle, setOf(factory.definingClass)).values.single()

        // The profile's code asks for its visit event by name through an interface the factory's class implements.
        val asks = FixtureDex.classesHolding(bundle, PROFILE_VISIT).flatMap { it.methods }.flatMap { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            instructions.indices.filter { index ->
                val named = instructions[index]
                named.opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
                    (named as ReferenceInstruction).reference.toString() == PROFILE_VISIT
            }.mapNotNull { index ->
                instructions.drop(index + 1).take(3).firstOrNull { it.opcode == Opcode.INVOKE_INTERFACE }
                    ?.let { ((it as ReferenceInstruction).reference as MethodReference).definingClass }
            }
        }
        assertTrue("${bundle.name}: the visit event is asked for through the logger's interface",
            asks.any { it in owner.interfaces })
        // Both of the class's ways to make an event go through the factory.
        val callers = owner.methods.filter { method ->
            method.implementation?.instructions?.any {
                (it as? ReferenceInstruction)?.reference?.toString() == factory.descriptor()
            } == true
        }
        assertEquals("$where is called by the class's two event makers", 2, callers.size)

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == factory.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.keepEventsOut(notLogged)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains six instructions", original.size + 6, patched.size)
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: the extension is asked first", KEEP_EVENT_OUT, (patched[0] as ReferenceInstruction).reference.toString())
        assertEquals("$where: with the event's name, the last parameter", method.implementation!!.registerCount - 1, call.startRegister)
        assertEquals("$where: and nothing else", 1, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT, patched[1].opcode)
        assertEquals(Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a true answers the factory's own not-logged event", notLogged.toString(),
            (patched[3] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.RETURN_OBJECT, patched[4].opcode)
        assertEquals("$where: the rest of Facebook's code stays", original.map { it.opcode }, patched.drop(6).map { it.opcode })
    }

    @Test
    fun `each declared build hands Facebook's own don't-save boolean to the extension first thing where a search is saved`() =
        bundles { bundle ->
            save(bundle)
        }

    @Test
    fun `each declared build asks the extension first thing where an analytics event is made`() =
        bundles { bundle ->
            visits(bundle)
        }
}
