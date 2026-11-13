/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.upsells

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.isNameTable
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide other Meta AI buttons under posts takes Meta AI's deep dive out from under a post's caption
 * by answering null from the plugin's getter. On every Facebook build the bundle declares, the
 * caption socket's name table names the plugin, the table's class reads the deep dive through that
 * one getter and stops at a null, the getter has a local register, and the patch's code goes in
 * first with the getter's own code after it unchanged. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CaptionDeepDiveFixtureTest {
    private val pluginName = CAPTION_DEEP_DIVE_PLUGIN.removePrefix("L").removeSuffix(";").replace('/', '.')

    private val CHECKED = "checked for null"
    private val HANDED_BACK = "handed back"

    /**
     * Per build, what the table's class does with each answer it reads from the getter. Up to 586 it
     * branched on a null right away. 587 builds the row in the getter (arm64 `A00(LX/03FD;)LX/0V49;`),
     * and the table's case hands the answer straight back to the collector (`LX/02sT;->A0A`, case 7).
     */
    private val answerUses = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf("arm64-v8a" to listOf(HANDED_BACK), "armeabi-v7a" to listOf(HANDED_BACK)),
    )

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    @Test
    fun `each declared build's caption deep dive getter answers null through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val plugin = FixtureDex.classes(bundle, setOf(CAPTION_DEEP_DIVE_PLUGIN))[CAPTION_DEEP_DIVE_PLUGIN]
                    ?: throw AssertionError("$name has no $CAPTION_DEEP_DIVE_PLUGIN")
                val getter = captionDeepDiveGetter(plugin)
                val getterCall = getter.descriptor()

                // The caption socket's table names the plugin, and its class reads the deep dive
                // through the getter, keeping the answer and branching on a null right after: the
                // check skips the plugin and the row builder builds nothing.
                val tables = FixtureDex.classesHolding(bundle, pluginName)
                    .flatMap { methodsHolding(it, pluginName) }.filter(::isNameTable)
                assertEquals("$name: name tables naming the plugin", 1, tables.size)
                val owner = FixtureDex.classes(bundle, setOf(tables.single().definingClass)).values.single()
                val reads = owner.methods.flatMap { method ->
                    val code = method.implementation?.instructions?.toList().orEmpty()
                    code.indices.filter { code[it].called()?.descriptor() == getterCall }.map { code to it }
                }
                assertTrue("$name: the table's class never reads the deep dive through $getterCall", reads.isNotEmpty())
                val getterCode = getter.implementation!!.instructions.toList()
                // Facebook's own "nothing to show": the getter answers null itself for a post with no deep dive.
                val answersNull = (1 until getterCode.size).any { at ->
                    val load = getterCode[at - 1]
                    getterCode[at].opcode == Opcode.RETURN_OBJECT && load.opcode == Opcode.CONST_4 &&
                        (load as NarrowLiteralInstruction).narrowLiteral == 0 &&
                        (load as OneRegisterInstruction).registerA == (getterCode[at] as OneRegisterInstruction).registerA
                }
                val uses = reads.map { (code, at) ->
                    assertEquals("$name: the getter's answer isn't kept", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                    val kept = (code[at + 1] as OneRegisterInstruction).registerA
                    val next = code[at + 2]
                    when {
                        (next.opcode == Opcode.IF_EQZ || next.opcode == Opcode.IF_NEZ) && (next as OneRegisterInstruction).registerA == kept -> CHECKED
                        next.opcode == Opcode.RETURN_OBJECT && (next as OneRegisterInstruction).registerA == kept -> HANDED_BACK
                        else -> "neither, ${next.opcode}"
                    }
                }.sorted()
                val pinned = answerUses.getValue(version)[name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("$name has no pin")
                assertEquals("$name: what the table's class does with the getter's answer", pinned, uses)
                // A null handed back is the getter's own answer for a post without a deep dive.
                if (HANDED_BACK in pinned) {
                    assertTrue("$name: the getter's answer is handed back, but it never answers null itself", answersNull)
                }

                val original = getter.implementation!!.instructions.toList()
                // Every parameter is an object, and an instance getter's `this` is no local either.
                val self = if (AccessFlags.STATIC.isSet(getter.accessFlags)) 0 else 1
                assertTrue("$name: $getterCall has no local register for the hook",
                    getter.implementation!!.registerCount - getter.parameterTypes.size - self >= 1)
                val patched = MutableMethod(getter).apply { dropCaptionDeepDive() }
                val after = patched.implementation!!.instructions.toList()
                assertEquals("$name: instructions added", original.size + 5, after.size)
                val ask = after[0]
                assertEquals("$name: the hook's form", Opcode.INVOKE_STATIC, ask.opcode)
                assertEquals("$name: the hook", HIDES_CAPTION_DEEP_DIVE, ask.called()!!.descriptor())
                assertEquals("$name: the hook's registers", 0, (ask as FiveRegisterInstruction).registerCount)
                assertEquals("$name: the answer", Opcode.MOVE_RESULT, after[1].opcode)
                assertEquals("$name: the branch", Opcode.IF_EQZ, after[2].opcode)
                assertEquals("$name: the null", Opcode.RETURN_OBJECT, after[4].opcode)
                for (index in original.indices) {
                    assertEquals("$name: instruction $index changed", original[index].opcode, after[index + 5].opcode)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the getter's hook`() {
        val hook = ExtensionDex.classDef(META_UPSELLS).methods.singleOrNull { it.name == "hidesDeepDiveBelowCaption" }
        assertTrue("the extension has no hidesDeepDiveBelowCaption", hook != null)
        assertEquals("hidesDeepDiveBelowCaption's shape", HIDES_CAPTION_DEEP_DIVE, hook!!.descriptor())
    }
}
