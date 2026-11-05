/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.treeFieldKey
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide the X on posts hook (#389) on every Facebook build the bundle declares
 * (arm64 and armv7): a class holding "biz_disco_feed_story_easy_hide" keeps a companion in a static
 * field, and that companion has one (props, env) -> Z check reading should_show_easy_hide. The patch
 * sends each of the check's answers through the extension on its own register. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class PostXButtonFixtureTest {
    @Test
    fun `each declared build's X check answers through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val helpers = FixtureDex.classesHolding(bundle, EASY_HIDE_TAG).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                assertFalse("$name: no class holds \"$EASY_HIDE_TAG\"", helpers.isEmpty())
                val companionTypes = helpers.flatMap { helper -> helper.staticFields.map { it.type } }
                    .filter { it.startsWith("L") }.toSet()
                val companions = FixtureDex.classes(bundle, companionTypes).values.toList()
                val context = PatchContexts.of((helpers + companions).distinctBy { it.type })

                val check = context.findPostXCheck()
                assertTrue("$name: the check doesn't read should_show_easy_hide", isPostXCheck(check))
                assertEquals("$name: the check's answer", "Z", check.returnType)
                val original = check.implementation!!.instructions.toList()
                val returns = original.filter { it.opcode == Opcode.RETURN }.map { (it as OneRegisterInstruction).registerA }
                assertTrue("$name: the check never returns", returns.isNotEmpty())
                assertTrue("$name: the key isn't should_show_easy_hide's", treeFieldKey(EASY_HIDE_FIELD) == 0x452a3ca9)

                context.hidePostX()

                val patched = context.mutableClassDefBy(check.definingClass).methods
                    .single { it.name == check.name && isPostXCheck(it) }.implementation!!.instructions.toList()
                assertEquals("$name: two instructions in front of each return", original.size + 2 * returns.size, patched.size)
                val patchedReturns = patched.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
                assertEquals("$name: returns", returns.size, patchedReturns.size)
                for ((at, answer) in patchedReturns.zip(returns)) {
                    val call = patched[at - 2]
                    assertEquals("$name: the call before return v$answer", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    assertEquals("$name: the call before return v$answer", SHOW_POST_X,
                        ((call as ReferenceInstruction).reference as MethodReference).toString())
                    assertEquals("$name: the register handed over", answer, (call as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                    assertEquals("$name: the extension's answer lands where the return reads it", answer,
                        (patched[at - 1] as OneRegisterInstruction).registerA)
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }
}
