/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden-post hooks on every Facebook build the bundle declares (#444): the two publishes of a
 * post's local visibility, one by id and one by story, each get one call handing the extension the
 * post's id and Facebook's visibility value, in the registers that hold them there, and nothing else
 * changes. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HiddenPostsFixtureTest {
    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    @Test
    fun `each declared build hands every hide and Undo to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, LOCAL_STORY_VISIBILITY)
                val publishes = owners.flatMap { it.methods.mapNotNull(::hidePublish) }
                assertEquals("$name: publishes ${publishes.map { "${it.method.definingClass}->${it.method.name}" }}",
                    2, publishes.size)
                val byId = publishes.single { it.at == 0 }
                val byStory = publishes.single { it.at > 0 }
                assertEquals("$name: the id's read", Opcode.MOVE_RESULT_OBJECT, byStory.method.body()[byStory.at - 1].opcode)
                assertTrue("$name: registers a plain call reaches", publishes.all { it.id <= 15 && it.visibility <= 15 })

                val context = PatchContexts.of(owners)
                hiddenPostsPatch.execute(context)
                for (publish in publishes) {
                    val before = publish.method.body()
                    val after = context.mutableClassDefBy(publish.method.definingClass).methods.single {
                        it.name == publish.method.name &&
                            it.parameterTypes.map(CharSequence::toString) == publish.method.parameterTypes.map(CharSequence::toString)
                    }.body()
                    val added = after[publish.at]
                    assertEquals("$name ${publish.method.name}: the hook", PUBLISHED, added.call())
                    val registers = (added as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) }
                    assertEquals("$name ${publish.method.name}: id and visibility", listOf(publish.id, publish.visibility), registers)
                    assertEquals("$name ${publish.method.name}: one hook", 1, after.count { it.call() == PUBLISHED })
                    assertEquals("$name ${publish.method.name}: the rest", before.map { it.opcode },
                        after.filterIndexed { index, _ -> index != publish.at }.map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
