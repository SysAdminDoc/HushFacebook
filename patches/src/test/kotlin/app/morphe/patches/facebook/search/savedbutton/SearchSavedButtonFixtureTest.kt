/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.savedbutton

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Saved button in search on every Facebook build the bundle declares, both ABIs of each. One class
 * takes a `(GraphSearchQuery, GraphSearchQuery, String)` query, it's a LinearLayout, and each of its
 * constructors hands the search field one TextWatcher. Patched, each constructor hands the bar to
 * the extension right after that call, and the rest of Facebook's code stays as it was.
 */
class SearchSavedButtonFixtureTest {
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

    /** [type] and the superclasses the bundle carries, nearest first. */
    private fun chain(bundle: File, type: String): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        var at: String? = type
        while (at != null && found.size < 20) {
            val classDef = FixtureDex.classes(bundle, setOf(at)).values.singleOrNull() ?: break
            found += classDef
            at = classDef.superclass
        }
        return found
    }

    private fun bar(bundle: File) {
        val setters = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == GRAPH_SEARCH_QUERY } }) { method ->
            method.parameterTypes.map { it.toString() } == listOf(GRAPH_SEARCH_QUERY, GRAPH_SEARCH_QUERY, "Ljava/lang/String;")
        }
        val types = setters.map { it.definingClass }.toSet()
        assertEquals("${bundle.name}: one class takes the search bar's query, found $types", 1, types.size)
        val classes = chain(bundle, types.single())
        val bar = classes.first()
        assertTrue("${bundle.name}: ${bar.type} is picked as the search bar", isSearchBar(bar))
        assertEquals("${bundle.name}: ${bar.type} is a LinearLayout", LINEAR_LAYOUT, classes.last().superclass)

        val stock = bar.methods.filter { it.name == "<init>" }
            .associate { it.parameterTypes.joinToString("") to it.implementation!!.instructions.toList() }
        assertEquals("${bundle.name}: ${bar.type} has its three constructors", 3, stock.size)

        val context = PatchContexts.of(classes + ExtensionDex.classDef(SETTINGS_STATUS))
        searchSavedButtonPatch.execute(context)

        for (constructor in context.mutableClassDefBy(bar.type).methods.filter { it.name == "<init>" }) {
            val signature = constructor.parameterTypes.joinToString("")
            val where = "${bundle.name}: ${bar.type}-><init>($signature)"
            val original = stock.getValue(signature)
            val watcher = textWatcherCalls(constructor).single()
            val patched = constructor.implementation!!.instructions.toList()
            val parameters = 1 + constructor.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
            val self = constructor.implementation!!.registerCount - parameters
            assertEquals("$where gains one instruction", original.size + 1, patched.size)
            assertEquals("$where hands the bar over right after the TextWatcher", Opcode.INVOKE_STATIC, patched[watcher + 1].opcode)
            assertEquals("$where: to the extension", ATTACH, (patched[watcher + 1] as ReferenceInstruction).reference.toString())
            assertEquals("$where: the bar itself", self, (patched[watcher + 1] as FiveRegisterInstruction).registerC)
            assertEquals("$where: the rest of Facebook's code stays", original.map { it.opcode },
                patched.filterIndexed { at, _ -> at != watcher + 1 }.map { it.opcode })
        }
    }

    @Test
    fun `each declared build hands the search bar to the extension from its constructors`() = bundles(::bar)
}
