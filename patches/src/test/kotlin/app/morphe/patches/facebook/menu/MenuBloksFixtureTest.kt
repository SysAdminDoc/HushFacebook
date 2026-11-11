/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.profile.PROFILE_NOTES_CHILDREN
import app.morphe.patches.facebook.profile.bloksNodes
import app.morphe.patches.facebook.profile.childGetters
import app.morphe.patches.facebook.profile.filterChildren
import app.morphe.patches.facebook.profile.isBloksNode
import app.morphe.patches.facebook.profile.isChildGetter
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hide Menu promotions' Bloks hook on every declared build (#445). The Menu's profile card, Create
 * Facebook Page and Your shortcuts come as one Bloks view on some accounts, so the Menu patch hands
 * every list the Bloks node classes' child getters return to the extension, as Hide profile notes
 * does. With both patches on, each return asks both hooks in turn, each answer replacing the list
 * the getter goes on with; here Hide profile notes goes in first.
 *
 * Read from 586 (2026-11-11): `LX/01IP;` and `LX/04cX;`, getters `CQT` and `E5d` on each.
 */
class MenuBloksFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    @Test
    fun `every Bloks child list goes through the Menu hook after the profile notes one, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val found = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(found, ::isBloksNode) }
                val nodes = found.map { ImmutableClassDef.of(it) }
                val before = childGetters(nodes).associate {
                    "${it.definingClass}->${it.name}" to it.implementation!!.instructions.toList()
                }

                val context = PatchContexts.of(nodes)
                with(context) {
                    for (hook in listOf(PROFILE_NOTES_CHILDREN, MENU_BLOKS_CHILDREN)) {
                        childGetters(bloksNodes()).forEach { getter ->
                            mutableClassDefBy(getter.definingClass).methods
                                .single { it.name == getter.name && isChildGetter(it) }
                                .filterChildren(hook)
                        }
                    }
                }

                val hooked = nodes.flatMap { context.mutableClassDefBy(it.type).methods.filter(::isChildGetter) }
                assertEquals("$name: every getter is still there", before.keys,
                    hooked.map { "${it.definingClass}->${it.name}" }.toSet())
                for (getter in hooked) {
                    val key = "${getter.definingClass}->${getter.name}"
                    val after = getter.implementation!!.instructions.toList()
                    val returns = after.indices.filter { after[it].opcode == Opcode.RETURN_OBJECT }
                    assertEquals("$name: $key returns as often as before",
                        before.getValue(key).count { it.opcode == Opcode.RETURN_OBJECT }, returns.size)
                    for (index in returns) {
                        val list = (after[index] as OneRegisterInstruction).registerA
                        for ((offset, hook) in listOf(4 to PROFILE_NOTES_CHILDREN, 2 to MENU_BLOKS_CHILDREN)) {
                            val call = after[index - offset]
                            assertEquals("$name: $key asks $hook before it returns",
                                hook, (call as ReferenceInstruction).reference.toString())
                            assertEquals("$name: $key hands $hook the list", list,
                                (call as RegisterRangeInstruction).startRegister)
                            assertEquals("$name: $key keeps $hook's answer", Opcode.MOVE_RESULT_OBJECT, after[index - offset + 1].opcode)
                            assertEquals("$name: $key into the same register", list,
                                (after[index - offset + 1] as OneRegisterInstruction).registerA)
                        }
                    }
                    assertEquals("$name: $key is otherwise as it was",
                        before.getValue(key).size + 4 * returns.size, after.size)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
