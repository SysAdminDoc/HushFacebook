/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ReferenceIndex
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/** #236: candidates remain conservative across earlier patches and index eviction. */
class ColourCallContextTest {
    private val read = "invoke-virtual { v0, v1 }, $CONTEXT_GET_COLOR\nreturn-void"

    private fun method(owner: String, name: String, body: String): MutableMethod = MutableMethod(
        ImmutableMethod(owner, name, emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null, null, ImmutableMethodImplementation(4, emptyList(), null, null)),
    ).apply { addInstructionsWithLabels(0, body) }

    private fun type(owner: String, body: String): ClassDef = ImmutableClassDef(
        owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(method(owner, "run", body)),
    )

    private fun BytecodePatchContext.references(owner: String): List<String> = classDefBy(owner).methods.flatMap { method ->
        method.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }.orEmpty()
    }

    @Test
    fun `framework and prior AMOLED calls both reach Material You without rewriting extension calls`() {
        for (amoled in listOf(false, true)) {
            val caller = type("Lfixture/Caller;", read)
            val extension = type("Lapp/morphe/extension/facebook/fixture/Caller;", read)
            val app = PatchContexts.of(listOf(caller, extension))
            assertNotNull(ReferenceIndex.of(app))
            if (amoled) assertEquals(1, app.rerouteColourCalls(AMOLED_COLOUR_CALLS).getValue(CONTEXT_GET_COLOR))
            val counts = app.rerouteColourCalls(YOU_COLOUR_CALLS)
            val key = if (amoled) AMOLED_COLOUR_CALLS.getValue(CONTEXT_GET_COLOR) else CONTEXT_GET_COLOR
            assertEquals(1, counts.getValue(key))
            assertEquals(1, counts.values.sum())
            assertEquals(listOf(YOU_COLOUR_CALLS.getValue(CONTEXT_GET_COLOR)), app.references(caller.type))
            assertEquals(listOf(CONTEXT_GET_COLOR), app.references(extension.type))
            assertSame(extension, app.classDefBy(extension.type))
        }
    }

    @Test
    fun `changed and newly added classes stay visible and an evicted index is rebuilt or falls back to a full walk`() {
        val hasRoom = ReferenceIndex.hasRoom
        try {
            for (eviction in listOf("kept", "rebuilt", "full walk")) {
                val negative = type("Lfixture/Negative;", "return-void")
                val app = PatchContexts.of(listOf(negative))
                assertNotNull(ReferenceIndex.of(app))
                app.mutableClassDefBy(negative.type).methods.add(method(negative.type, "added", read))
                val added = type("Lfixture/Added;", read)
                val pool = BytecodePatchContext::class.java.getMethod("getPatchClasses\$morphe_patcher").invoke(app)
                pool.javaClass.getMethod("addClass\$morphe_patcher", ClassDef::class.java).invoke(pool, added)
                if (eviction != "kept") ReferenceIndex.drop(app)
                ReferenceIndex.hasRoom = { eviction == "rebuilt" }
                val counts = app.rerouteColourCalls(YOU_COLOUR_CALLS)
                // With no room now, only an index the walk itself built or kept is there to answer.
                ReferenceIndex.hasRoom = { false }
                assertEquals(eviction, eviction == "full walk", ReferenceIndex.of(app) == null)
                assertEquals(eviction, 2, counts.getValue(CONTEXT_GET_COLOR))
                for (owner in listOf(negative.type, added.type)) {
                    assertEquals(listOf(YOU_COLOUR_CALLS.getValue(CONTEXT_GET_COLOR)), app.references(owner))
                }
            }
        } finally {
            ReferenceIndex.hasRoom = hasRoom
        }
    }

    @Test
    fun `same-owner wrong overloads and super dispatch retain their calls and zero counts`() {
        val original = type("Lfixture/Controls;", """
            invoke-super { v0, v1 }, $CONTEXT_GET_COLOR
            invoke-virtual { v0, v1, v2 }, Landroid/content/Context;->getColor(II)I
            invoke-virtual { v0, v1 }, Lfixture/Other;->getColor(I)I
            return-void
        """)
        val app = PatchContexts.of(listOf(original))
        val before = app.references(original.type)
        assertEquals(YOU_COLOUR_CALLS.keys.associateWith { 0 }, app.rerouteColourCalls(YOU_COLOUR_CALLS))
        assertEquals(before, app.references(original.type))
        assertSame(original, app.classDefBy(original.type))
        assertEquals(emptyMap<String, Int>(), app.rerouteColourCalls(emptyMap()))
    }
}
