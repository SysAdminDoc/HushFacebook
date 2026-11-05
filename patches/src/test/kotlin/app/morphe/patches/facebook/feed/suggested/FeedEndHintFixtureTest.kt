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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom of Home hook (#258) on every Facebook build the bundle declares: one static method outside
 * the extension opens the "InterruptionStateManager.onEndOfFeedUnitVisible" trace and takes a visible
 * flag last, and it belongs to Home's end marker, which it gets as its second parameter after the
 * session and reads the feed type off. The patch hands the flag to the extension first thing, from the
 * method's last register. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedEndHintFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    @Test
    fun `each declared build hands the bottom of Home's visible flag to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, END_VISIBLE_TRACE)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val methods = context.endVisibleMethods()
                assertEquals("$name: static methods opening \"$END_VISIBLE_TRACE\" with a visible flag", 1, methods.size)
                val method = methods.single()
                val code = method.code()
                val parameters = method.parameterTypes.map(CharSequence::toString)
                assertEquals("$name: the flag is a boolean, last", "Z", parameters.last())
                // Positive control: the end marker's own method, handed the session and the marker,
                // reading which feed it ends.
                assertEquals("$name: the session first", "Lcom/facebook/auth/usersession/FbUserSession;", parameters.first())
                assertEquals("$name: the marker second", method.definingClass, parameters[1])
                assertTrue("$name: ${method.name} doesn't read its feed type", code.any {
                    it.opcode == Opcode.IGET_OBJECT &&
                        ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                            field.definingClass == method.definingClass && field.type == "Lcom/facebook/api/feedtype/FeedType;"
                        }
                })
                val visible = method.implementation!!.registerCount - 1

                context.hookFeedEndHint()
                val patched = context.mutableClassDefBy(method.definingClass).methods
                    .single { patchedMethod ->
                        patchedMethod.name == method.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) == parameters
                    }.code()
                assertEquals("$name: one instruction added", code.size + 1, patched.size)
                val tell = patched.first()
                assertEquals("$name: the extension told", FEED_END_VISIBLE,
                    ((tell as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: handed the flag",
                    listOf(Opcode.INVOKE_STATIC_RANGE, visible, 1),
                    listOf(tell.opcode, (tell as RegisterRangeInstruction).startRegister, tell.registerCount))
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode }, patched.drop(1).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val type = FEED_END_VISIBLE.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "endVisible" }
        assertNotNull("the extension has no FeedEndHint.endVisible", hook)
        assertEquals("endVisible's shape", FEED_END_VISIBLE,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("endVisible isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
