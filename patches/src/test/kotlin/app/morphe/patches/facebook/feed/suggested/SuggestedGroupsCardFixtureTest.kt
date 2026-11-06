/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide suggested groups on the Groups tab (#347) on every Facebook build the bundle declares: one
 * layout outside the extension casts its unit to GroupsYouShouldJoinFeedUnit and answers null of its
 * own, and it's the suggested groups card's, the component naming itself
 * "GroupsYouShouldJoinComponentSpec" that builds the card's row of groups. The patch asks the
 * extension first thing there and answers that same null on a yes. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SuggestedGroupsCardFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    @Test
    fun `each declared build asks the extension before laying out the suggested groups card`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, GROUPS_CARD_CAST)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val layouts = context.groupsCardLayouts()
                assertEquals("$name: layouts casting to GroupsYouShouldJoinFeedUnit with a null answer", 1, layouts.size)
                val layout = layouts.single()
                val card = classes.single { it.type == layout.definingClass }
                // Positive control: the class is the card's component, by the spec name it logs.
                assertTrue("$name: ${card.type} isn't GroupsYouShouldJoinComponent", card.methods.any { method ->
                    method.implementation?.instructions?.any { it.string() == "GroupsYouShouldJoinComponentSpec" } == true
                })
                val code = layout.code()
                // And its layout builds a component of its own type for the row of groups.
                assertTrue("$name: ${layout.name} builds nothing", code.any { it.opcode == Opcode.NEW_INSTANCE })
                val nullAnswer = groupsCardNullAnswer(layout)
                assertNotNull("$name: no null answer", nullAnswer)

                context.hookSuggestedGroupsCard()
                val patched = context.mutableClassDefBy(layout.definingClass).methods
                    .single { patchedMethod ->
                        patchedMethod.name == layout.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) == layout.parameterTypes.map(CharSequence::toString)
                    }.code()
                assertEquals("$name: five instructions added", code.size + 5, patched.size)
                val ask = patched[0]
                assertEquals("$name: the extension asked first", HIDES_GROUPS_CARD,
                    ((ask as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the guard",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    patched.subList(0, 5).map { it.opcode })
                assertEquals("$name: null returned on a yes", (patched[3] as OneRegisterInstruction).registerA,
                    (patched[4] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode }, patched.drop(5).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build asks the extension before laying out the Groups tab's own suggestions`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = GROUPS_TAB_SUGGESTIONS.flatMap { FixtureDex.classesHolding(bundle, it) }
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                // Positive control: what builds each layout loads the end-of-feed block's surface name.
                val endOfFeed = FixtureDex.classesHolding(bundle, END_OF_FEED).flatMap { it.methods }
                    .filter { method -> method.implementation?.instructions?.any { it.string() == END_OF_FEED } == true }
                val context = PatchContexts.of(classes)
                val layouts = GROUPS_TAB_SUGGESTIONS.map { label ->
                    val found = context.groupsTabSuggestionLayouts(label)
                    assertEquals("$name: layouts naming $label with a null answer", 1, found.size)
                    val layout = found.single()
                    assertEquals("$name: $label is a Litho render", "render", layout.name)
                    assertTrue("$name: nothing in the end-of-feed block builds ${layout.definingClass}", endOfFeed.any { method ->
                        method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == layout.definingClass }
                    })
                    layout
                }

                context.hookGroupsTabSuggestions()
                for (layout in layouts) {
                    val code = layout.code()
                    val patched = context.mutableClassDefBy(layout.definingClass).methods
                        .single { patchedMethod ->
                            patchedMethod.name == layout.name &&
                                patchedMethod.parameterTypes.map(CharSequence::toString) == layout.parameterTypes.map(CharSequence::toString)
                        }.code()
                    val where = "$name: ${layout.definingClass}"
                    assertEquals("$where: five instructions added", code.size + 5, patched.size)
                    assertEquals("$where: the extension asked first", HIDES_GROUPS_TAB_SUGGESTIONS,
                        ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                    assertEquals("$where: the guard",
                        listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                        patched.subList(0, 5).map { it.opcode })
                    assertEquals("$where: null returned on a yes", (patched[3] as OneRegisterInstruction).registerA,
                        (patched[4] as OneRegisterInstruction).registerA)
                    assertEquals("$where: the rest is Facebook's", code.map { it.opcode }, patched.drop(5).map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hooks`() {
        for (reference in listOf(HIDES_GROUPS_CARD, HIDES_GROUPS_TAB_SUGGESTIONS)) {
            val type = reference.substringBefore("->")
            val name = reference.substringAfter("->").substringBefore("(")
            val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no SuggestedGroupsCard.$name", hook)
            assertEquals("$name's shape", reference,
                "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        }
    }

    private companion object {
        const val END_OF_FEED = "ENGAGE_END_OF_FEED"
    }
}
