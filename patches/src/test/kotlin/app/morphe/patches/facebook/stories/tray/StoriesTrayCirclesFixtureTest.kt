/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.tray

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round stories tray on every declared build and ABI: the row's render method, the list view id
 * it stores and the pull-to-refresh container factories are found by the patch's own lookups, and
 * after the hooks the render reports the id right after the constant and each factory hands its
 * wrapper to the extension before it returns.
 *
 * Read from 585 (2026-10-31): the render method mentions "StoriesHScrollComponent.render", and the
 * factory builds the list with its three-argument constructor. Obfuscated names are never written
 * down, every one is found by shape.
 */
class StoriesTrayCirclesFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    @Test
    fun `the row reports its list id and each container goes to the extension, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val picked = mutableListOf<ClassDef>()
                val supers = mutableMapOf<String, String?>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        supers[classDef.type] = classDef.superclass
                        val holdsRender = classDef.methods.any { mentions(it, STORIES_ROW_RENDER) }
                        val holdsFactory = classDef.methods.any { isTrayContainerFactory(it) { type -> supers[type] } }
                        if (holdsRender || holdsFactory) picked += classDef
                    }
                }
                // A factory's return type may be read from a later dex than the factory, so judge again with every superclass known.
                val classes = picked.map { ImmutableClassDef.of(it) }
                val renders = classes.flatMap { c -> c.methods.filter { mentions(it, STORIES_ROW_RENDER) } }
                assertEquals("$name: one row render", 1, renders.size)
                val factories = classes.flatMap { c -> c.methods.filter { isTrayContainerFactory(it) { type -> supers[type] } } }
                assertTrue("$name: at least one container factory", factories.isNotEmpty())

                val render = renders.single()
                val constIndex = trayIdIndex(render)
                val renderBefore = render.implementation!!.instructions.toList()

                val context = PatchContexts.of(classes)
                with(context) {
                    assertEquals("$name: the lookup finds the same render", render.name, storiesRowRender().name)
                    mutableClassDefBy(render.definingClass).methods.single { it.name == render.name && it.parameterTypes == render.parameterTypes }
                        .reportTrayId(constIndex)
                    factories.forEach { factory ->
                        mutableClassDefBy(factory.definingClass).methods
                            .single { it.name == factory.name && it.parameterTypes == factory.parameterTypes }.passContainer()
                    }
                }

                val rendered = context.mutableClassDefBy(render.definingClass).methods
                    .single { it.name == render.name && it.parameterTypes == render.parameterTypes }.implementation!!.instructions.toList()
                val call = rendered[constIndex + 1]
                assertEquals("$name: the id goes to the extension right after the constant", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                assertEquals("$name: the extension's trayId", TRAY_ID, (call as ReferenceInstruction).reference.toString())
                assertEquals("$name: it hands over the constant's register",
                    (rendered[constIndex] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA,
                    (call as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the render is otherwise as it was",
                    renderBefore.count { it.opcode != Opcode.NOP } + 1, rendered.count { it.opcode != Opcode.NOP })

                for (factory in factories) {
                    val method: Method = context.mutableClassDefBy(factory.definingClass).methods
                        .single { it.name == factory.name && it.parameterTypes == factory.parameterTypes }
                    val after = method.implementation!!.instructions.toList()
                    val returns = after.indices.filter { after[it].opcode == Opcode.RETURN_OBJECT }
                    assertTrue("$name: ${factory.name} returns", returns.isNotEmpty())
                    for (index in returns) {
                        val hook = after[index - 1]
                        assertEquals("$name: the container goes to the extension before the return",
                            TRAY_CONTAINER, (hook as ReferenceInstruction).reference.toString())
                        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                    }
                    assertEquals("$name: the factory is otherwise as it was",
                        factory.implementation!!.instructions.count { it.opcode != Opcode.NOP } + returns.size,
                        after.count { it.opcode != Opcode.NOP })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
