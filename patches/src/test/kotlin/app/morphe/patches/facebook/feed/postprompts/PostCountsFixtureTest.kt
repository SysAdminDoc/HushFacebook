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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide counts on posts hook (#180) on every Facebook build the bundle declares
 * (arm64 and armv7): the Share button plugin calls one static (Resources, Integer, int) -> String
 * builder, that builder answers "" for no count and looks the rest up as a plural, and the patch
 * puts the extension's question in front of it with the same "" as the early answer. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class PostCountsFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun Method.sameSignatureAs(other: MethodReference) =
        name == other.name && returnType == other.returnType &&
            parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build's count builder answers through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val plugin = FixtureDex.classes(bundle, setOf(REPOST_BUTTON_PLUGIN))[REPOST_BUTTON_PLUGIN]
                assertNotNull("$name: no $REPOST_BUTTON_PLUGIN", plugin)
                val calls = countTextCalls(plugin!!)
                assertEquals("$name: (Resources, Integer, int) -> String calls in the Share plugin", 1, calls.size)
                val call = calls.single()
                val owner = FixtureDex.classes(bundle, setOf(call.definingClass)).getValue(call.definingClass)
                val builder = owner.methods.single { it.sameSignatureAs(call) }
                assertTrue("$name: the builder isn't static with \"\" and a plural lookup", isCountTextBuilder(builder))

                // Positive control: Facebook's own early answer is const-string "" then return-object of
                // the same register, the answer the hook copies; and nothing branches to the first
                // instruction, so every call runs the hook.
                val original = builder.code()
                val empty = original.indexOfFirst {
                    it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == ""
                }
                assertTrue("$name: no const-string \"\"", empty >= 0)
                assertEquals("$name: \"\" isn't returned right away", Opcode.RETURN_OBJECT, original[empty + 1].opcode)
                assertEquals("$name: \"\" goes out in another register",
                    (original[empty] as OneRegisterInstruction).registerA, (original[empty + 1] as OneRegisterInstruction).registerA)
                // Negative control: the plugin method that calls the builder is no builder itself.
                val callers = plugin.methods.filter { method ->
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref -> isCountTextShape(ref) } == true
                    } == true
                }
                assertTrue("$name: no plugin method calls the builder", callers.isNotEmpty())
                assertFalse("$name: the builder check takes its caller", callers.any(::isCountTextBuilder))

                val context = PatchContexts.of(listOf(plugin, owner))
                context.hidePostCounts()
                val patched = context.mutableClassDefBy(owner.type).methods.single { it.sameSignatureAs(call) }.code()
                assertEquals("$name: five instructions added", original.size + 5, patched.size)
                assertEquals("$name: the extension is asked first", HIDE_POST_COUNTS,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: shape of the early answer",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_STRING, Opcode.RETURN_OBJECT),
                    patched.take(5).map { it.opcode })
                assertEquals("$name: the early answer is the empty text", "",
                    ((patched[3] as ReferenceInstruction).reference as StringReference).string)
                assertEquals("$name: the hook works in v0", listOf(0, 0, 0, 0),
                    listOf(patched[1], patched[2], patched[3], patched[4]).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$name: the rest is Facebook's",
                    original.map { it.opcode }, patched.drop(5).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        // Redex names the builder's class separately per ABI, so every declared build has to resolve.
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_POST_COUNTS.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hidePostCounts" }
        assertNotNull("the extension has no PostPrompts.hidePostCounts", hook)
        assertEquals("hidePostCounts' shape", HIDE_POST_COUNTS,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hidePostCounts isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
