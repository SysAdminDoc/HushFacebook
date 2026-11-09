/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.order

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The profile Reels grid's paginated list, as PaginableList up to 585 and as the Kotlin base it extends from 586. */
class ProfileReelsSortShapesTest {
    private val grid = "Lfixture/Grid;"
    private val base = "Lfixture/PaginatedBase;"
    private val count = PlayCount("Lfixture/Counts;", "read/plays")

    private fun classDef(type: String, superclass: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.map(ImmutableMethod::of))

    /** The grid's render, asking its holder for the list as [answers] and handing it to the tile builder. */
    private fun render(answers: String) = MutableMethod(
        ImmutableMethod(
            grid, "render", listOf(ImmutableMethodParameter("Lfixture/Context;", null, null)), "Lfixture/Component;",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(6, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(0, """
            iget-object v0, p0, $grid->holder:Lfixture/Holder;
            invoke-virtual {v0}, Lfixture/Holder;->pages()$answers
            move-result-object v0
            invoke-static {p1, v0}, Lfixture/Tiles;->build(Lfixture/Context;$answers)Lfixture/Component;
            move-result-object v0
            return-object v0
        """.trimIndent())
    }

    @Test
    fun `PaginableList's base joins the paginated types only when there is one`() {
        assertEquals(setOf(PAGINABLE), paginatedTypes(classDef(PAGINABLE, "Ljava/lang/Object;")))
        assertEquals(setOf(PAGINABLE, base), paginatedTypes(classDef(PAGINABLE, base)))
        assertEquals(setOf(PAGINABLE), paginatedTypes(null))
    }

    /** Up to 585 the grid asks for a PaginableList. */
    @Test
    fun `585's grid keeps a PaginableList and casts back to it`() {
        val site = profileGridSite(listOf(render(PAGINABLE)), count, setOf(PAGINABLE))
        assertEquals(3 to 0, site.after to site.list)
        assertEquals(PAGINABLE, site.type)
    }

    /** 586 (`LX/0o0q;->A0s`) asks for the base (`LX/0mg4;->A04()LX/0mew;`), and the builder takes the base. */
    @Test
    fun `586's grid keeps the base and casts back to it`() {
        val method = render(base)
        val site = profileGridSite(listOf(method), count, setOf(PAGINABLE, base))
        assertEquals(3 to 0, site.after to site.list)
        assertEquals(base, site.type)

        val context = PatchContexts.of(listOf(classDef(grid, "Ljava/lang/Object;", method)))
        hookProfileGrid(site) { type -> context.mutableClassDefBy(type) }
        val after = context.mutableClassDefBy(grid).methods.single { it.descriptor() == method.descriptor() }
            .implementation!!.instructions.toList()
        assertEquals(Opcode.CHECK_CAST, after[7].opcode)
        assertEquals("the extension's answer is cast to the base, which PaginableList extends", base,
            (after[7] as ReferenceInstruction).reference.toString())
        assertEquals("then Facebook's builder", Opcode.INVOKE_STATIC, after[8].opcode)
    }

    /** Without the base among the paginated types, 586's grid has no paginated list to hand over. */
    @Test
    fun `the base alone isn't a paginated list`() {
        assertThrows(PatchException::class.java) { profileGridSite(listOf(render(base)), count, setOf(PAGINABLE)) }
    }
}
