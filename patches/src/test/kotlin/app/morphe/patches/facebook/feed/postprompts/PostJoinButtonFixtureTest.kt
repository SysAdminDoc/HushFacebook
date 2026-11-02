/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide the Join button hook (#356) on every Facebook build the bundle declares
 * (arm64 and armv7): one group header method carries the appendActionableForumJoinButton trace
 * literal, and the patch returns from its first instruction when the extension says so. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class PostJoinButtonFixtureTest {
    @Test
    fun `each declared build's group header answers through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, JOIN_BUTTON_TRACE)
                assertFalse("$name: no class holds \"$JOIN_BUTTON_TRACE\"", classes.isEmpty())
                val context = PatchContexts.of(classes)
                val entry = context.findJoinButtonEntry()
                assertTrue("$name: the entry isn't an instance method", !AccessFlags.STATIC.isSet(entry.accessFlags))
                val original = entry.implementation!!.instructions.toList()
                // Negative control: no other method of the class takes the entry's shape and trace.
                val owner = classes.single { it.type == entry.definingClass }
                assertEquals("$name: more than one entry on the class", 1, owner.methods.count(::isJoinButtonEntry))
                // Positive control: nothing in the original jumps back to instruction 0, so every call runs the hook.
                assertNotNull(original.first())
                context.hidePostJoinButton()
                val patched = context.mutableClassDefBy(entry.definingClass).methods
                    .single { it.name == entry.name && it.parameterTypes.size == 5 && it.returnType == "V" && isJoinButtonEntry(it) }
                    .implementation!!.instructions.toList()
                assertEquals("$name: four instructions added", original.size + 4, patched.size)
                assertEquals("$name: the extension is asked first", HIDE_JOIN_BUTTON,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: shape of the early return",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    patched.take(4).map { it.opcode })
                assertEquals("$name: the hook works in v0", listOf(0, 0),
                    listOf(patched[1], patched[2]).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode }, patched.drop(4).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_JOIN_BUTTON.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideJoinButton" }
        assertNotNull("the extension has no PostPrompts.hideJoinButton", hook)
        assertEquals("hideJoinButton's shape", HIDE_JOIN_BUTTON,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideJoinButton isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
