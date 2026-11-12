/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloks' colour pairs (issue #37): every model implementing the interface the logging model's
 * reader comes from has its one parse sent to the Bloks stand-in. A class with the same shape
 * outside that interface stays, and a model that never asks the theme whether it's dark, or parses
 * twice, is refused.
 */
class BloksColourPairTest {
    private val model = "Lfixture/BloksModel;"
    private val theme = "Lfixture/BloksTheme;"
    private val parser = standIn(MATERIAL_YOU, PARSE_COLOR)
    private val asks = "invoke-interface { p1 }, $theme->isDark()Z"
    private val parse = "invoke-static { v1 }, $parser"

    private fun reader(owner: String, smali: String?): Method {
        val parameters = listOf(theme, "I", "I", "I").map { ImmutableMethodParameter(it, null, null) }
        if (smali == null) {
            return ImmutableMethod(owner, "pair", parameters, "I", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
        }
        return MutableMethod(
            ImmutableMethod(
                owner, "pair", parameters, "I", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null, null, ImmutableMethodImplementation(7, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }.let(ImmutableMethod::of)
    }

    private fun type(name: String, interfaces: List<String>, smali: String?): ClassDef {
        val flags = if (smali == null) AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value
        else AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        return ImmutableClassDef(name, flags, "Ljava/lang/Object;", interfaces, null, null, emptyList(), listOf(reader(name, smali)))
    }

    private fun body(vararg lines: String) = (lines.toList() + listOf("move-result v0", "return v0")).joinToString("\n")

    private val logging = type("Lfixture/LoggingModel;", listOf(model), body(
        asks, "move-result v0",
        "const-string v1, \"$BLOKS_PAIR_LIGHT_KEY\"", "const-string v1, \"$BLOKS_PAIR_DARK_KEY\"",
        "const-string v1, \"#333334\"", parse,
    ))
    private val sparse = type("Lfixture/SparseModel;", listOf(model), body(asks, "move-result v0", "const-string v1, \"#333334\"", parse))
    private val unrelated = type("Lfixture/Unrelated;", listOf("Lfixture/Other;"), body(asks, "move-result v0", "const-string v1, \"#333334\"", parse))

    private fun app(vararg extra: ClassDef): BytecodePatchContext =
        PatchContexts.of(listOf(type(model, emptyList(), null), logging, sparse, unrelated) + extra)

    private fun BytecodePatchContext.calls(owner: String): List<String> =
        mutableClassDefBy(owner).methods.single().implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }

    @Test
    fun `each model of the interface sends its parse to the Bloks stand-in`() {
        val app = app()
        assertEquals("models changed", 2, with(app) { hookBloksColourPairs() })
        assertSame("an unrelated interface implementer must not be made mutable", unrelated, app.classDefBy(unrelated.type))
        for (owner in listOf(logging.type, sparse.type)) {
            assertTrue("$owner calls the stand-in", PARSE_BLOKS in app.calls(owner))
            assertTrue("$owner keeps no route four parse", parser !in app.calls(owner))
        }
        assertTrue("a class outside the interface keeps route four's parse", parser in app.calls(unrelated.type))
        assertTrue("and gets no Bloks call", PARSE_BLOKS !in app.calls(unrelated.type))
    }

    @Test
    fun `a model that doesn't ask the theme or parses twice is refused`() {
        val blind = type("Lfixture/BlindModel;", listOf(model), body("const-string v1, \"#333334\"", parse))
        val refusedBlind = runCatching { with(app(blind)) { hookBloksColourPairs() } }.exceptionOrNull()
        assertTrue("blind: ${refusedBlind?.message}", refusedBlind?.message.orEmpty().contains("without asking"))

        val twice = type("Lfixture/TwiceModel;", listOf(model), body(asks, "move-result v0", "const-string v1, \"#333334\"", parse, parse))
        val refusedTwice = runCatching { with(app(twice)) { hookBloksColourPairs() } }.exceptionOrNull()
        assertTrue("twice: ${refusedTwice?.message}", refusedTwice?.message.orEmpty().contains("with 2 calls"))
    }

    /** Issue #34: AMOLED sends its route four parse to its own Bloks stand-in, and Material You takes that over. */
    @Test
    fun `AMOLED's parse goes to its own stand-in, which Material You takes over`() {
        val amoledParse = "invoke-static { v1 }, $PARSE_COLOR_DARK"
        val amoledLogging = type("Lfixture/AmoledLoggingModel;", listOf(model), body(
            asks, "move-result v0",
            "const-string v1, \"$BLOKS_PAIR_LIGHT_KEY\"", "const-string v1, \"$BLOKS_PAIR_DARK_KEY\"",
            "const-string v1, \"#B0B3B8\"", amoledParse,
        ))
        val amoledSparse = type("Lfixture/AmoledSparseModel;", listOf(model), body(asks, "move-result v0", "const-string v1, \"#B0B3B8\"", amoledParse))
        val app = PatchContexts.of(listOf(type(model, emptyList(), null), amoledLogging, amoledSparse))
        val owners = listOf(amoledLogging.type, amoledSparse.type)

        assertEquals("AMOLED's models changed", 2, with(app) { hookBloksColourPairs(setOf(PARSE_COLOR_DARK), AMOLED_PARSE_BLOKS) })
        for (owner in owners) {
            assertTrue("$owner calls AMOLED's stand-in", AMOLED_PARSE_BLOKS in app.calls(owner))
            assertTrue("$owner keeps no route four parse", PARSE_COLOR_DARK !in app.calls(owner))
        }
        assertEquals("Material You's models changed", 2, with(app) { hookBloksColourPairs() })
        for (owner in owners) {
            assertTrue("$owner calls Material You's stand-in", PARSE_BLOKS in app.calls(owner))
            assertTrue("$owner keeps no AMOLED stand-in", AMOLED_PARSE_BLOKS !in app.calls(owner))
        }
    }
}
