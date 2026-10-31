/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.profile

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local profile counts on every declared build and ABI: the Bloks node classes' string getters and
 * the profile screen's onFragmentCreate are found by the patch's own lookups, and after the hook
 * every string a getter returns goes through the extension first and the screen reports itself
 * before anything else runs.
 *
 * Read from 585 (2026-10-31): the screen is `Lcom/facebook/timeline/fragment/ProfileFragment;`, a
 * name Facebook keeps. The node classes are found by shape, never by their obfuscated names.
 */
class LocalProfileCountsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    @Test
    fun `every string a Bloks node answers goes through the extension and the screen reports itself, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val nodesFound = mutableListOf<ClassDef>()
                val screens = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.classes.filterTo(nodesFound, ::isBloksNode)
                    dex.classes.filterTo(screens) { it.type == PROFILE_SCREEN }
                }
                assertEquals("$name: two Bloks node classes, found ${nodesFound.map { it.type }}", 2, nodesFound.size)
                assertEquals("$name: one profile screen", 1, screens.size)
                val classes = (nodesFound + screens).map { ImmutableClassDef.of(it) }
                val nodes = classes.filter { c -> nodesFound.any { it.type == c.type } }

                val getters = stringGetters(nodes)
                assertEquals("$name: a string getter in each node class", 2, getters.size)
                val before = getters.associate { "${it.definingClass}->${it.name}" to it.implementation!!.instructions.toList() }

                val context = PatchContexts.of(classes)
                val screen = with(context) { profileScreenCreate() }
                val screenBefore = screen.implementation!!.instructions.toList()
                with(context) {
                    stringGetters(bloksNodes()).forEach { getter ->
                        mutableClassDefBy(getter.definingClass).methods.single { it.name == getter.name && isStringGetter(it) }
                            .passStrings()
                    }
                    mutableClassDefBy(PROFILE_SCREEN).methods.single { it.name == screen.name && isScreenCreate(it) }.reportOpened()
                }

                for (getter in nodes.flatMap { context.mutableClassDefBy(it.type).methods.filter(::isStringGetter) }) {
                    val key = "${getter.definingClass}->${getter.name}"
                    val after = getter.implementation!!.instructions.toList()
                    val returns = after.indices.filter { after[it].opcode == Opcode.RETURN_OBJECT }
                    assertTrue("$name: $key has a return", returns.isNotEmpty())
                    assertEquals("$name: $key returns as often as before",
                        before.getValue(key).count { it.opcode == Opcode.RETURN_OBJECT }, returns.size)
                    for (index in returns) {
                        val text = (after[index] as OneRegisterInstruction).registerA
                        val call = after[index - 2]
                        assertEquals("$name: $key asks the extension before it returns",
                            LOCAL_COUNTS_TEXT, (call as ReferenceInstruction).reference.toString())
                        assertEquals("$name: $key hands it the string it returns", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                        assertEquals("$name: $key hands it that register", text, (call as RegisterRangeInstruction).startRegister)
                        assertEquals("$name: $key returns the answer", Opcode.MOVE_RESULT_OBJECT, after[index - 1].opcode)
                        assertEquals("$name: $key into the same register", text,
                            (after[index - 1] as OneRegisterInstruction).registerA)
                    }
                    assertEquals("$name: $key is otherwise as it was",
                        before.getValue(key).count { it.opcode != Opcode.NOP } + 2 * returns.size,
                        after.count { it.opcode != Opcode.NOP })
                }

                val created = context.mutableClassDefBy(PROFILE_SCREEN).methods.single { isScreenCreate(it) }
                    .implementation!!.instructions.toList()
                val first = created[0]
                assertEquals("$name: the screen reports itself first", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                assertEquals("$name: the screen calls the extension", LOCAL_COUNTS_OPENED,
                    (first as ReferenceInstruction).reference.toString())
                val range = first as RegisterRangeInstruction
                assertEquals("$name: it hands over the screen itself, the first parameter",
                    screen.implementation!!.registerCount - screen.parameterTypes.size - 1, range.startRegister)
                assertEquals("$name: one register", 1, range.registerCount)
                assertEquals("$name: onFragmentCreate is otherwise as it was",
                    screenBefore.count { it.opcode != Opcode.NOP } + 1, created.count { it.opcode != Opcode.NOP })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
