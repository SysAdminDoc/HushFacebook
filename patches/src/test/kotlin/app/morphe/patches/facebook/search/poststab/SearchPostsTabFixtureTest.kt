/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.poststab

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.reels.categoryNames
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search posts tab on every Facebook build the bundle declares, both ABIs of each. One method opens
 * the filter parse trace, casts its list of entries to ImmutableList and reads each entry's tab
 * through an interface call answering the tab enum, whose constants still include POSTS. That
 * method hands the tab to a static builder of the chip, so an entry naming POSTS becomes a chip
 * like the others. Patched, the list goes through the extension right after the cast and comes
 * back as an ImmutableList in the same register, and the rest of Facebook's code stays as it was.
 */
class SearchPostsTabFixtureTest {
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

    private fun parse(bundle: File) {
        val holders = FixtureDex.classesHolding(bundle, FILTER_PARSE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        // The tab enum lives in another dex of the bundle, so every class the parse methods name is fetched.
        val named = holders.flatMap { methodsHolding(it, FILTER_PARSE) }.flatMap { method ->
            method.implementation!!.instructions
                .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType }
        }.filter { it.startsWith("L") }.toSet()
        val pool = (holders + FixtureDex.classes(bundle, named).values).distinctBy { it.type }
        val found = PatchContexts.of(pool).listReads(holders)
        assertEquals("${bundle.name}: one method opening the filter parse casts a list and reads each entry's tab, " +
            "found ${found.map { it.first.descriptor() }}", 1, found.size)
        val (method, read) = found.single()
        val where = "${bundle.name}: ${method.descriptor()}"
        val original = method.implementation!!.instructions.toList()

        // The tab enum still has POSTS, the constant the extension's entry answers.
        val tabs = pool.single { it.type == read.tabRead.returnType }
        assertTrue("$where: the tab enum ${tabs.type} still holds POSTS", "POSTS" in categoryNames(tabs))

        // Facebook builds a chip from the tab alone: the tab it read goes to a static method taking the tab first.
        val builds = original.any { instruction ->
            instruction.opcode == Opcode.INVOKE_STATIC && ((instruction as ReferenceInstruction).reference as MethodReference)
                .let { it.parameterTypes.firstOrNull()?.toString() == read.tabRead.returnType && it.returnType.startsWith("L") }
        }
        assertTrue("$where hands a tab to a static chip builder", builds)

        val owner = FixtureDex.classes(bundle, setOf(method.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val mutable = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == method.descriptor() }
        mutable.hookEntries(read)
        val patched = mutable.implementation!!.instructions.toList().filter { it.opcode != Opcode.NOP }
        val stock = original.filter { it.opcode != Opcode.NOP }
        assertEquals("$where gains the hook's four instructions", stock.size + 4, patched.size)
        assertEquals("$where: the list is handed over right after its cast", EDGES,
            (patched[read.cast + 1] as ReferenceInstruction).reference.toString())
        assertEquals("$where: the answer goes back to the list's register", read.list,
            (patched[read.cast + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: as an ImmutableList", COPY_OF, (patched[read.cast + 3] as ReferenceInstruction).reference.toString())
        assertEquals("$where: in the same register", read.list, (patched[read.cast + 4] as OneRegisterInstruction).registerA)
        assertEquals("$where: the rest of Facebook's code stays",
            stock.map { it.opcode },
            patched.filterIndexed { index, _ -> index !in (read.cast + 1..read.cast + 4) }.map { it.opcode })
    }

    @Test
    fun `each declared build reads search chips from one list and hooks it after its cast`() = bundles(::parse)
}
