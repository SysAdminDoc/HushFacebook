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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide the Join button hooks (#356) on every Facebook build the bundle declares
 * (arm64 and armv7): one group header method carries the appendActionableForumJoinButton trace
 * literal, and the patch returns from its first instruction when the extension says so; one video
 * footer provider carries VideoJoinGroupAttachmentViewModelProvider, and the patch has it answer
 * null first thing the same way. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
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
                val headers = FixtureDex.classesHolding(bundle, JOIN_BUTTON_TRACE)
                assertFalse("$name: no class holds \"$JOIN_BUTTON_TRACE\"", headers.isEmpty())
                val bars = FixtureDex.classesHolding(bundle, VIDEO_JOIN_TRACE)
                assertFalse("$name: no class holds \"$VIDEO_JOIN_TRACE\"", bars.isEmpty())
                val classes = (headers + bars).distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val entry = context.findJoinButtonEntry()
                val bar = context.findVideoJoinBar()
                val barCode = bar.implementation!!.instructions.toList()
                // Positive control: the provider already answers null itself (a const/4 0 handed to a return-object).
                val nulls = barCode.filter { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0 }
                    .map { (it as OneRegisterInstruction).registerA }.toSet()
                assertTrue("$name: the provider never answers null",
                    barCode.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in nulls })
                // Negative control: one provider of that shape, and the header entry isn't it.
                assertEquals("$name: more than one provider", 1, classes.sumOf { owner -> owner.methods.count(::isVideoJoinBar) })
                assertFalse("$name: the header entry reads as the provider", isVideoJoinBar(entry))
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
                val patchedBar = context.mutableClassDefBy(bar.definingClass).methods
                    .single { it.name == bar.name && isVideoJoinBar(it) }
                    .implementation!!.instructions.toList()
                assertEquals("$name: the provider asks the extension first", HIDE_VIDEO_JOIN_BAR,
                    ((patchedBar[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: shape of the null answer",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    patchedBar.take(5).map { it.opcode })
                assertEquals("$name: the null is a 0", 0, (patchedBar[3] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the provider's hook works in v0", listOf(0, 0, 0, 0),
                    patchedBar.subList(1, 5).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$name: the rest of the provider is Facebook's", barCode.map { it.opcode }, patchedBar.drop(5).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_JOIN_BUTTON.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideJoinButton" }
        assertNotNull("the extension has no PostPrompts.hideJoinButton", hook)
        assertEquals("hideJoinButton's shape", HIDE_JOIN_BUTTON,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideJoinButton isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        val bar = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideVideoJoinBar" }
        assertNotNull("the extension has no PostPrompts.hideVideoJoinBar", bar)
        assertEquals("hideVideoJoinBar's shape", HIDE_VIDEO_JOIN_BAR,
            "${bar!!.definingClass}->${bar.name}(${bar.parameterTypes.joinToString("")})${bar.returnType}")
        assertTrue("hideVideoJoinBar isn't static", AccessFlags.STATIC.isSet(bar.accessFlags))
    }
}
