/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.rings

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Which class holding the comment story ring plugin's name is the comment row builder, beside the table of names 586 adds. */
class HideStoryRingsShapesTest {
    private val ring = "Lfixture/Ring;"
    private val ringState = "Lfixture/RingState;"

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, body: String, registers: Int, static: Boolean) =
        MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun classDef(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.map(ImmutableMethod::of))

    private val ringStateClass = classDef(ringState, method(ringState, "<init>", listOf("Ljava/lang/Object;", ring), "V", """
        invoke-direct {p0}, Ljava/lang/Object;-><init>()V
        return-void
    """, registers = 3, static = false))

    /** The comment row builder (585): a static taking one thing and answering a ring state, holding the plugin's name. */
    private fun builder(type: String) = classDef(type, method(type, "ringState", listOf("Lfixture/Bucket;"), ringState, """
        const-string v0, "$STORY_RING_PLUGIN"
        new-instance v0, $ringState
        const/4 v1, 0x0
        invoke-direct {v0, v1, v1}, $ringState-><init>(Ljava/lang/Object;$ring)V
        return-object v0
    """, registers = 3, static = true))

    /** 586's table of plugin names (`LX/0AZU;->A00`): the name answered for a number, no ring state anywhere. */
    private val table = classDef("Lfixture/PluginNames;", method("Lfixture/PluginNames;", "nameOf", listOf("I"), "Ljava/lang/String;", """
        const-string v0, "$STORY_RING_PLUGIN"
        return-object v0
    """, registers = 2, static = true))

    /** 586's merged statics (`LX/01xJ;`): the ring state helper, beside a static answering something else. */
    private val statics = classDef("Lfixture/Statics;",
        method("Lfixture/Statics;", "ringState", listOf("Lfixture/Bucket;"), ringState, """
            new-instance v0, $ringState
            const/4 v1, 0x0
            invoke-direct {v0, v1, v1}, $ringState-><init>(Ljava/lang/Object;$ring)V
            return-object v0
        """, registers = 3, static = true),
        method("Lfixture/Statics;", "label", listOf("Lfixture/Bucket;"), "Ljava/lang/String;", """
            const-string v0, ""
            return-object v0
        """, registers = 2, static = true))

    /** 586's comment row builder (`LX/0AfM;->A0B`): names the plugin, then calls the helper in [statics] (or only [calls]). */
    private fun movedBuilder(calls: String = "ringState", answers: String = ringState) =
        classDef("Lfixture/Builder;", method("Lfixture/Builder;", "row", listOf("Lfixture/Bucket;"), "V", """
            const-string v0, "$STORY_RING_PLUGIN"
            invoke-static {p1}, Lfixture/Statics;->$calls(Lfixture/Bucket;)$answers
            return-void
        """, registers = 2, static = false))

    @Test
    fun `586's builder counts the ring state helper it calls in another class`() {
        val (helper, constructor) = PatchContexts.of(listOf(table, movedBuilder(), statics, ringStateClass)).commentRingState(ring)
        assertEquals("Lfixture/Statics;" to "ringState", helper.definingClass to helper.name)
        assertEquals(ringState, constructor.definingClass)
    }

    @Test
    fun `586's Android 9 prefetch lambda holding the plugin's name too shares the builder's helper`() {
        val lambda = classDef("Lfixture/Prefetch;", method("Lfixture/Prefetch;", "invoke", emptyList(), "Ljava/lang/Object;", """
            const-string v0, "$STORY_RING_PLUGIN"
            const/4 v0, 0x0
            invoke-static {v0}, Lfixture/Statics;->ringState(Lfixture/Bucket;)$ringState
            move-result-object v0
            return-object v0
        """, registers = 2, static = false))
        val (helper, constructor) = PatchContexts.of(listOf(movedBuilder(), lambda, statics, ringStateClass)).commentRingState(ring)
        assertEquals("Lfixture/Statics;" to "ringState", helper.definingClass to helper.name)
        assertEquals(ringState, constructor.definingClass)
    }

    @Test
    fun `a builder calling only a static that answers something else is refused`() {
        val context = PatchContexts.of(listOf(table, movedBuilder("label", "Ljava/lang/String;"), statics, ringStateClass))
        assertThrows(PatchException::class.java) { context.commentRingState(ring) }
    }

    @Test
    fun `585's builder is the one class holding the plugin's name`() {
        val (helper, constructor) = PatchContexts.of(listOf(builder("Lfixture/Builder;"), ringStateClass)).commentRingState(ring)
        assertEquals("Lfixture/Builder;" to "ringState", helper.definingClass to helper.name)
        assertEquals(ringState, constructor.definingClass)
    }

    @Test
    fun `586's table of plugin names is passed over for the builder`() {
        val (helper, _) = PatchContexts.of(listOf(table, builder("Lfixture/Builder;"), ringStateClass)).commentRingState(ring)
        assertEquals("Lfixture/Builder;" to "ringState", helper.definingClass to helper.name)
    }

    @Test
    fun `two classes with a ring state helper are refused`() {
        val context = PatchContexts.of(listOf(table, builder("Lfixture/Builder;"), builder("Lfixture/Other;"), ringStateClass))
        assertThrows(PatchException::class.java) { context.commentRingState(ring) }
    }

    @Test
    fun `a table alone is refused`() {
        val context = PatchContexts.of(listOf(table, ringStateClass))
        assertThrows(PatchException::class.java) { context.commentRingState(ring) }
    }
}
