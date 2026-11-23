/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.reels.CAN_VIEWER_COMMENT
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show Comment on posts with comments off (#299) on every Facebook build the bundle declares (arm64
 * and armv7): one static footer check loads a field of CommentButtonFeedUFIFooterItemPlugin, picks
 * the item through a packed-switch, and for the Comment item asks a static check that reads
 * can_viewer_comment (0x35e7e981, read from 588's dex) from the post's feedback. The patch hands
 * that one answer to the extension. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class PostCommentButtonFixtureTest {
    @Test
    fun `each declared build's footer check hands its can-comment answer to the extension`() {
        assertEquals("can_viewer_comment's key", 0x35e7e981, CAN_VIEWER_COMMENT)
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val resolved = mutableMapOf<String, com.android.tools.smali.dexlib2.iface.ClassDef?>()
                val classOf = { type: String -> resolved.getOrPut(type) { FixtureDex.classes(bundle, setOf(type))[type] } }
                val candidates = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.contains(COMMENT_BUTTON_PLUGIN) }) { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && loadsCommentPlugin(method)
                }
                val checks = candidates.filter { canCommentCalls(it, classOf).isNotEmpty() }
                assertEquals("$name: footer checks asking about comments", 1, checks.size)
                val check = checks.single()
                val original = check.implementation!!.instructions.toList()
                assertTrue("$name: the footer check picks its item through a packed-switch",
                    original.any { it.opcode == Opcode.PACKED_SWITCH })
                val calls = canCommentCalls(check, classOf)
                assertEquals("$name: can-comment calls in the footer check", 1, calls.size)
                val called = (original[calls.single()] as ReferenceInstruction).reference as MethodReference
                val target = classOf(called.definingClass)
                assertNotNull("$name: ${called.definingClass} isn't in the build", target)
                val result = (original[calls.single() + 1] as OneRegisterInstruction).registerA

                val owner = classOf(check.definingClass)!!
                assertThrows("$name: without the can-comment check's class the patch must refuse", PatchException::class.java) {
                    PatchContexts.of(listOf(owner)).showPostCommentButton()
                }
                val context = PatchContexts.of(listOf(owner, target!!).distinctBy { it.type })
                context.showPostCommentButton()
                val patched = context.mutableClassDefBy(check.definingClass).methods.single {
                    it.name == check.name && it.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
                }.implementation!!.instructions.toList()
                assertEquals("$name: two instructions added", original.size + 2, patched.size)
                val hook = patched.indices.single {
                    ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SHOW_POST_COMMENT_BUTTON
                }
                assertEquals("$name: the hook follows the can-comment call's move-result", calls.single() + 2, hook)
                assertEquals("$name: the hook is a static call", Opcode.INVOKE_STATIC_RANGE, patched[hook].opcode)
                assertEquals("$name: the hook gets Facebook's answer", result, (patched[hook] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook gets one register", 1, (patched[hook] as RegisterRangeInstruction).registerCount)
                assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT, patched[hook + 1].opcode)
                assertEquals("$name: lands where Facebook branches on it", result, (patched[hook + 1] as OneRegisterInstruction).registerA)
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = SHOW_POST_COMMENT_BUTTON.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "showsCommentButton" }
        assertNotNull("the extension has no PostPrompts.showsCommentButton", hook)
        assertEquals("showsCommentButton's shape", SHOW_POST_COMMENT_BUTTON,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("showsCommentButton isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
