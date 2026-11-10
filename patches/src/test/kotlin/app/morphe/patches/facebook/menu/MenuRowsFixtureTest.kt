/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Help and Settings rows' anchors on every Facebook build the bundle declares (#153): the
 * native group section builds one Settings and privacy row list and one Help and support row list,
 * on one class, from builders sharing one row item the extension can read (on 584, which inlined
 * both, it reads and writes each kept list once and builds that row item itself). Then the hook:
 * right after each call keeps its list (on 584 each read and write of a kept list), a list that's
 * there goes through its own extension entry and back through ImmutableList.copyOf into the same
 * register, a missing one skips to the section's own next instruction, and the rest of the section
 * is untouched. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MenuRowsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    @Test
    fun `each declared build hands its Help and Settings rows to the extension as the native section gets them`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val natives = FixtureDex.classesHolding(bundle, NATIVE_SECTION_KEY)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                    .flatMap { holder -> holder.methods.filter(::isNativeSectionChildren).map { holder to it } }
                assertEquals("$name: native Menu group sections", 1, natives.size)
                val (section, native) = natives.single()

                // 584 calls no build: the section keeps both lists itself, and builds their rows. 586
                // asks a static method of its own class, which calls the builds, for its group's list,
                // and on its Android 9 build that method keeps both lists the way 584's section does.
                val ownClass: (String) -> ClassDef? = { if (it == section.type) section else null }
                val site = rowListSite(native, ownClass)
                val builds = rowListBuilds(site)
                val owners = builds.map { it.definingClass }.toSet()
                val builders = FixtureDex.classes(bundle, owners)
                val made = builders.values.flatMap { it.methods.flatMap(::constructedTypes) }.toSet() +
                    constructedTypes(native) + constructedTypes(site)
                val classes = FixtureDex.classes(bundle, made + owners)
                val hooks = rowListHooks(native) { ownClass(it) ?: classes[it] }
                assertEquals("$name: Settings and privacy's list, then Help and support's",
                    listOf(SETTINGS_ROWS_KEPT, HELP_ROWS_KEPT), hooks.map { it.hook })
                assertEquals("$name: builds the hooks follow, or kept lists when there are none",
                    builds.isEmpty(), hooks.all { it.kept != null })

                val original = site.code()
                val context = PatchContexts.of(listOf(section))
                val mutable = context.mutableClassDefBy(section.type).methods.single {
                    it.name == site.name && it.parameterTypes.map(Any::toString) == site.parameterTypes.map(Any::toString)
                }
                mutable.passRowListsThrough(hooks)
                val patched = mutable.code()
                // Where the section has its copy of each list: the move-result after the build's call,
                // or on 584 the one read of the kept list and the one write of a list just built.
                val keeps = hooks.flatMap { hook ->
                    val sites = hook.kept?.let { kept ->
                        patched.indices.filter { at ->
                            ((patched[at] as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == kept.toString()
                        }.also { sites ->
                            assertEquals("$name: ${hook.call} read once and written once", 2, sites.size)
                            assertEquals("$name: ${hook.call} read and written", setOf(Opcode.IGET_OBJECT, Opcode.IPUT_OBJECT),
                                sites.map { patched[it].opcode }.toSet())
                        }
                    } ?: listOf(patched.indices.single { patched[it].call() == hook.call } + 1)
                    sites.map { it to hook }
                }
                assertEquals("$name: five instructions added for each copy", original.size + 5 * keeps.size, patched.size)

                var unchanged = patched.toMutableList()
                for ((at, hook) in keeps) {
                    val kept = patched[at]
                    if (hook.kept == null) {
                        assertEquals("$name: ${hook.hook}'s list is kept as the call answers", Opcode.MOVE_RESULT_OBJECT, kept.opcode)
                    }
                    val register = (kept as OneRegisterInstruction).registerA
                    val check = patched[at + 1]
                    assertEquals("$name: a missing list is left alone", Opcode.IF_EQZ, check.opcode)
                    assertEquals("$name: the check reads the list", register, (check as OneRegisterInstruction).registerA)
                    assertEquals("$name: and skips to the section's own next instruction", at + 6,
                        (check as BuilderOffsetInstruction).target.location.index)
                    val extension = patched[at + 2]
                    assertEquals("$name: the extension", hook.hook, extension.call())
                    assertEquals("$name: is handed the list", register, (extension as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: its answer", register, (patched[at + 3] as OneRegisterInstruction).registerA)
                    val copy = patched[at + 4]
                    assertEquals("$name: goes back into an ImmutableList", LIST_COPY_OF, copy.call())
                    assertEquals("$name: from the same register", register, (copy as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: into the list's register", register, (patched[at + 5] as OneRegisterInstruction).registerA)
                    unchanged = unchanged.filterNot { it === check || it === extension || it === patched[at + 3] ||
                        it === copy || it === patched[at + 5] }.toMutableList()
                }
                assertEquals("$name: the rest of the section is untouched", original.map { it.opcode }, unchanged.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
