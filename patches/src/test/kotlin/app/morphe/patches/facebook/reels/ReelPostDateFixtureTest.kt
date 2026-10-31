/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post date under a reel's author (#296): each declared build has one helper that reads the
 * video's creation_time behind a server switch, and the switch's answer goes to the extension
 * before the helper compares it with yes.
 */
class ReelPostDateFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    @Test
    fun `each declared build hands the post date's server switch to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val helpers = FixtureDex.methodsWhere(bundle, { true }, ::isReelPostDate)
                assertEquals("$name: post date helpers", 1, helpers.size)
                val helper = helpers.single()
                val classes = FixtureDex.classes(bundle, setOf(helper.definingClass)).values.toList()
                assertThrows("$name: no helper must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(emptyList()).showReelPostDate()
                }
                val context = PatchContexts.of(classes)
                context.showReelPostDate()
                val patched = context.mutableClassDefBy(helper.definingClass).methods.single {
                    it.name == helper.name &&
                        it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
                }.code()
                val calls = patched.indices.filter {
                    ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SHOW_POST_DATE
                }
                assertEquals("$name: one hook", 1, calls.size)
                val at = calls.single()
                assertEquals("$name: the hook follows a move-result", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                val result = (patched[at - 1] as OneRegisterInstruction).registerA
                val gate = (patched[at - 2] as ReferenceInstruction).reference as MethodReference
                assertEquals("$name: the switch hands back a boolean", "Z", gate.returnType)
                assertEquals("$name: the hook passes the answer", result, (patched[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook passes one register", 1, (patched[at] as RegisterRangeInstruction).registerCount)
                assertEquals("$name: the extension's answer replaces Facebook's", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                assertEquals("$name: into the same register", result, (patched[at + 1] as OneRegisterInstruction).registerA)
                // Facebook compares the answer with yes right after, and draws nothing for anything else.
                assertEquals("$name: Facebook branches on the answer next", Opcode.IF_NE, patched[at + 2].opcode)
                assertEquals("$name: on the same register", result, (patched[at + 2] as TwoRegisterInstruction).registerA)
                assertTrue("$name: the date is read after the switch", creationTimeRead(helper) > at - 2)
                assertEquals("$name: two executable instructions added",
                    helper.code().count { it.opcode != Opcode.NOP } + 2, patched.count { it.opcode != Opcode.NOP })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    @Test
    fun `the extension has the hook`() {
        val type = SHOW_POST_DATE.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "showsPostDate" }
        assertNotNull("the extension has no ReelDeclutter.showsPostDate", hook)
        assertEquals("showsPostDate's shape", SHOW_POST_DATE,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("showsPostDate isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
