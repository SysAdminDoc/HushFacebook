/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Hide counts on reels hook (#180) on every Facebook build the bundle declares
 * (arm64 and armv7): one Litho component hands FDSVerticalUfiButton to its base class and draws an
 * FDSText from a String, its constructor takes two Strings and stores the first where render reads
 * it, and the patch sends that first String through the extension before anything reads it. Reads
 * the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelCountsFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    @Test
    fun `each declared build's sidebar button text goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val named = FixtureDex.classesHolding(bundle, VERTICAL_UFI_BUTTON)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val buttons = named.filter { ufiButtonConstructor(it) != null && drawsUfiText(it) }
                assertEquals("$name: components that name the button and draw its text", 1, buttons.size)
                val button = buttons.single()
                val constructor = ufiButtonConstructor(button)!!
                val index = buttonTextParameter(constructor)
                assertNotNull("$name: the constructor doesn't take exactly two Strings", index)
                assertNotNull("$name: the first String isn't stored where render reads it",
                    textField(button, constructor, index!!))

                // Positive control: the second String, the accessibility label, goes to another field,
                // so the hook has the text under the icon and not the label.
                val label = constructor.parameterTypes.withIndex()
                    .last { it.value.toString() == "Ljava/lang/String;" }.index
                assertNotEquals("$name: one String parameter", index, label)
                val labelRegister = constructor.parameterRegisterNumber(label)
                val textRegister = constructor.parameterRegisterNumber(index)
                assertTrue("$name: the label isn't stored in the button",
                    constructor.code().any { it.opcode == Opcode.IPUT_OBJECT && (it as OneRegisterInstruction).registerA == labelRegister })
                // Negative control: a parameter that isn't a String has no text field.
                assertNull("$name: the field check takes the session", textField(button, constructor, 0))

                val context = PatchContexts.of(listOf(button))
                context.hideReelCounts()
                val patched = context.mutableClassDefBy(button.type).methods.single {
                    it.name == "<init>" &&
                        it.parameterTypes.map(CharSequence::toString) == constructor.parameterTypes.map(CharSequence::toString)
                }.code()
                val original = constructor.code()
                assertEquals("$name: two instructions added", original.size + 2, patched.size)
                assertEquals("$name: the extension is asked first", REEL_COUNT_TEXT,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: shape of the hook", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT),
                    patched.take(2).map { it.opcode })
                val call = patched[0] as RegisterRangeInstruction
                assertEquals("$name: the hook passes one register", 1, call.registerCount)
                assertEquals("$name: the hook reads the text parameter", textRegister, call.startRegister)
                assertEquals("$name: the answer goes back in the text parameter", textRegister,
                    (patched[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest is Facebook's",
                    original.map { it.opcode }, patched.drop(2).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        // Redex names the button's class separately per ABI, so every declared build has to resolve.
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = REEL_COUNT_TEXT.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "countText" }
        assertNotNull("the extension has no ReelDeclutter.countText", hook)
        assertEquals("countText's shape", REEL_COUNT_TEXT,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("countText isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
