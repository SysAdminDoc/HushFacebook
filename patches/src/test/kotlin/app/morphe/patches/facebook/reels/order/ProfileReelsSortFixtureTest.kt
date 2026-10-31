/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.order

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Most viewed first on a profile's Reels tab (#232) on every Facebook build the bundle declares.
 * The section is the class that loads the kept section name, the list builder call is the one
 * (parameter, List) static call in its one instance method, and the play count reader is the one
 * static Integer method that holds the play_count key. The Reels tab's list is the one paginated list
 * a static session helper answers in the spec that loads the tab's kept name. Nothing here names an
 * obfuscated class.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ProfileReelsSortFixtureTest {
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

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    @Test
    fun `each declared build's profile reels list is found and sorted before the tiles are built`() = bundles { bundle ->
        val name = bundle.name
        val owners = FixtureDex.classesHolding(bundle, PROFILE_REELS_SECTION).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val sections = owners.flatMap { owner -> owner.methods.filter { holdsString(it, PROFILE_REELS_SECTION) } }
        val readers = FixtureDex.methodsWhere(bundle, dexFilter = { true }, wanted = { isPlayCountReader(it) })
        val sites = profileReelsSites(sections, readers)

        assertEquals("$name: one reader", 1, readers.size)
        assertTrue("$name: the reader is a static Integer method", readers.single().returnType == "Ljava/lang/Integer;")
        assertEquals("$name: the sort is handed the reader's class", readers.single().definingClass, sites.holder)
        assertTrue("$name: three distinct registers", setOf(sites.listRegister, sites.classRegister, sites.nameRegister).size == 3)

        val before = sites.section.implementation!!.instructions.toList()
        val context = PatchContexts.of(owners)
        hookProfileReels(sites) { type -> context.mutableClassDefBy(type) }
        val after = context.mutableClassDefBy(sites.section.definingClass).methods
            .single { it.descriptor() == sites.section.descriptor() }.implementation!!.instructions.toList()

        assertEquals("$name: three instructions are added", before.size + 3, after.size)
        assertEquals(
            "$name: the class, the reader's name, then the extension",
            listOf(Opcode.CONST_CLASS, Opcode.CONST_STRING, Opcode.INVOKE_STATIC),
            after.subList(sites.hookAt, sites.hookAt + 3).map { it.opcode },
        )
        assertEquals("$name: the reader's class", sites.holder, after[sites.hookAt].reference())
        assertEquals("$name: the reader's name", "\"${sites.reader}\"", "\"" + after[sites.hookAt + 1].reference() + "\"")
        assertEquals(
            "$name: the extension is asked",
            "$CLASS->sort(Ljava/util/List;Ljava/lang/Class;Ljava/lang/String;)V",
            after[sites.hookAt + 2].reference(),
        )
        assertEquals("$name: Facebook's builder call follows", before[sites.hookAt].reference(), after[sites.hookAt + 3].reference())
        assertEquals(
            "$name: Facebook's code stays in order",
            before.map { it.opcode },
            after.filterIndexed { i, _ -> i !in sites.hookAt until sites.hookAt + 3 }.map { it.opcode },
        )
    }

    @Test
    fun `each declared build hands the profile Reels grid's paginated list to the extension`() = bundles { bundle ->
        val name = bundle.name
        val owners = FixtureDex.classesHolding(bundle, PROFILE_GRID_SECTION).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val grids = owners.flatMap { owner -> owner.methods.filter { holdsString(it, PROFILE_GRID_SECTION) } }
        val readers = FixtureDex.methodsWhere(bundle, dexFilter = { true }, wanted = { isPlayCountReader(it) })
        val site = profileGridSite(grids, readers)

        val before = site.method.implementation!!.instructions.toList()
        val context = PatchContexts.of(owners)
        hookProfileGrid(site) { type -> context.mutableClassDefBy(type) }
        val after = context.mutableClassDefBy(site.method.definingClass).methods
            .single { it.descriptor() == site.method.descriptor() }.implementation!!.instructions.toList()
            .filter { it.opcode != Opcode.NOP }
        val stock = before.filter { it.opcode != Opcode.NOP }

        assertEquals("$name: five instructions are added", stock.size + 5, after.size)
        val at = site.after - before.take(site.after).count { it.opcode == Opcode.NOP }
        assertEquals(
            "$name: the class, the name, the extension, its answer kept and cast",
            listOf(Opcode.CONST_CLASS, Opcode.CONST_STRING, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
            after.subList(at, at + 5).map { it.opcode },
        )
        assertEquals("$name: the reader's class", site.holder, after[at].reference())
        assertEquals(
            "$name: the extension is asked",
            "$CLASS->list(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;",
            after[at + 2].reference(),
        )
        assertEquals("$name: the list is asked for first", Opcode.MOVE_RESULT_OBJECT, after[at - 1].opcode)
        assertEquals(
            "$name: Facebook's code stays in order",
            stock.map { it.opcode },
            after.filterIndexed { i, _ -> i !in at until at + 5 }.map { it.opcode },
        )
    }

    @Test
    fun `each declared build hands the Reels tab's paginated list to the extension`() = bundles { bundle ->
        val name = bundle.name
        val owners = FixtureDex.classesHolding(bundle, PROFILE_TAB_SECTION).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val tabs = owners.flatMap { owner -> owner.methods.filter { holdsString(it, PROFILE_TAB_SECTION) } }
        val readers = FixtureDex.methodsWhere(bundle, dexFilter = { true }, wanted = { isPlayCountReader(it) })
        val site = profileTabSite(tabs, readers)
        assertTrue("$name: four distinct registers", setOf(site.list, site.copyRegister, site.classRegister, site.nameRegister).size == 4)
        assertTrue("$name: the copy and the call's registers fit an invoke", maxOf(site.copyRegister, site.classRegister, site.nameRegister) <= 15)

        val before = site.method.implementation!!.instructions.toList()
        val context = PatchContexts.of(owners)
        hookProfileTab(site) { type -> context.mutableClassDefBy(type) }
        val after = context.mutableClassDefBy(site.method.definingClass).methods
            .single { it.descriptor() == site.method.descriptor() }.implementation!!.instructions.toList()
            .filter { it.opcode != Opcode.NOP }
        val stock = before.filter { it.opcode != Opcode.NOP }

        assertEquals("$name: six instructions are added", stock.size + 6, after.size)
        val at = site.after - before.take(site.after).count { it.opcode == Opcode.NOP }
        assertEquals(
            "$name: the list copied, the class, the name, the extension, its answer kept and cast",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.CONST_CLASS, Opcode.CONST_STRING, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
            after.subList(at, at + 6).map { it.opcode },
        )
        assertEquals("$name: the reader's class", site.holder, after[at + 1].reference())
        assertEquals(
            "$name: the extension is asked",
            "$CLASS->list(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;",
            after[at + 3].reference(),
        )
        assertEquals("$name: the answer goes back where the list was", site.list, (after[at + 4] as OneRegisterInstruction).registerA)
        assertTrue("$name: the list comes from the session helper", after[at - 2].reference()!!.endsWith(")Lcom/facebook/graphservice/interfaces/PaginableList;"))
        assertEquals(
            "$name: Facebook's code stays in order",
            stock.map { it.opcode },
            after.filterIndexed { i, _ -> i !in at until at + 6 }.map { it.opcode },
        )
    }

    @Test
    fun `the extension has the hook`() {
        val list = ExtensionDex.classDef(CLASS).methods.single { it.name == "list" }
        assertEquals("(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", "(" + list.parameterTypes.joinToString("") + ")" + list.returnType)
        val sort = ExtensionDex.classDef(CLASS).methods.single { it.name == "sort" }
        assertEquals(
            "(Ljava/util/List;Ljava/lang/Class;Ljava/lang/String;)V",
            "(" + sort.parameterTypes.joinToString("") + ")" + sort.returnType,
        )
    }

    private companion object {
        const val CLASS = "Lapp/morphe/extension/facebook/reels/ProfileReelsSort;"
    }
}
