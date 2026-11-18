/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide friends' reactions on photos and videos hook (#483) on every Facebook
 * build the bundle declares (arm64 and armv7): one render loads "_collapse_repost_bubble_row", it
 * already answers null on some path, and after the hook it asks the extension first and answers
 * null when told to. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReactionBubblesFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    @Test
    fun `each declared build's bubble row asks the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, BUBBLE_ROW)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                assertEquals("$name: classes holding \"$BUBBLE_ROW\"", 1, classes.size)
                val row = classes.single()
                val context = PatchContexts.of(classes)

                val render = with(context) { findBubbleRow() }
                assertEquals("$name: the method", "render", render.name)
                assertEquals("$name: the render answers an object", "L", render.returnType.take(1))
                assertTrue("$name: the render never answers null itself", answersNullItself(render))
                // Negative control: the check isn't true of every method that answers an object.
                val others = row.methods.filter { it.returnType.startsWith("L") && it.implementation != null && it.name != "render" }
                assertTrue("$name: no other method answering an object, so the null check proves nothing", others.isNotEmpty())
                assertTrue("$name: every method of the row answers null, so the check proves nothing",
                    others.any { !answersNullItself(it) })

                with(context) { hideReactionBubbles() }
                val hooked = context.mutableClassDefBy(row.type).methods
                    .single { it.name == render.name && it.parameterTypes == render.parameterTypes }.body()
                assertEquals("$name: asks first", HIDE_REACTION_BUBBLES, hooked[0].called())
                assertEquals("$name: reads the answer", Opcode.MOVE_RESULT, hooked[1].opcode)
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, hooked[2].opcode)
                assertEquals("$name: loads null", Opcode.CONST_4, hooked[3].opcode)
                assertEquals("$name: and answers it when told to", Opcode.RETURN_OBJECT, hooked[4].opcode)
                assertEquals("$name: the rest is Facebook's", render.body().map { it.opcode }, hooked.drop(5).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_REACTION_BUBBLES.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideReactionBubbles" }
        assertNotNull("the extension has no PostPrompts.hideReactionBubbles", hook)
        assertEquals("hideReactionBubbles' shape", HIDE_REACTION_BUBBLES,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideReactionBubbles isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
