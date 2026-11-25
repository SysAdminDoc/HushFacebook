/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #557: the story viewer's reaction send, found once in every declared build and hooked with its session, bucket and card. */
class StoryReactionFixtureTest {
    @Test
    fun `each declared build has one reaction send, which hands the button its session, bucket and card`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val sends = FixtureDex.classesHolding(bundle, REACTION_NAME).flatMap { it.methods.filter(::isReactionSend) }
                assertEquals("$name: reaction sends loading \"$REACTION_NAME\"", 1, sends.size)
                val send = sends.single()

                val before = send.implementation!!.instructions.toList()
                val patched = MutableMethod(send).apply { reportReaction() }
                val after = patched.implementation!!.instructions.toList()
                assertEquals("$name: instructions added to the reaction send", before.size + 1, after.size)
                val hook = after[0]
                assertEquals("$name: the hook's form", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                val call = (hook as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the hook", ON_REACTED,
                    "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
                // Static, so p0 is the owner and p1 to p3 the session, the bucket and the card.
                val registers = send.implementation!!.registerCount
                val p0 = registers - send.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2L else 1L }.toInt()
                assertEquals("$name: the hook's first register is the session", p0 + 1, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook takes the session, the bucket and the card", 3, hook.registerCount)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
