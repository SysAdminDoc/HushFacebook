/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredmarketplace

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.layout.theme.REACT_TEXT_MANAGER
import app.morphe.patches.facebook.layout.theme.reactTextBuilders
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A listing's date goes under its price (#303) from React's text builder: on each declared build
 * there is one, and after the patch each of its returns hands the built text to the extension and
 * returns its answer, with whatever jumped to a return now reaching the call. Nothing else in the
 * builder changes.
 *
 * Read from 585 arm64 (2026-11-03): the builder is `LX/082P;->A03`, Fabric's TextLayoutManager, with
 * two returns, a SpannableString's and a SpannableStringBuilder's. No name is used here.
 */
class MarketplacePriceLineFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    /** The text view manager, every one-method interface and every class with a static Spannable builder. */
    private fun reactClasses(bundle: File): Collection<ClassDef> {
        val found = mutableMapOf<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in found) continue
                val keep = classDef.type == REACT_TEXT_MANAGER ||
                    (AccessFlags.INTERFACE.isSet(classDef.accessFlags) && classDef.methods.count() == 1) ||
                    classDef.methods.any { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Landroid/text/Spannable;" }
                if (keep) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        return found.values
    }

    @Test
    fun `React's text builder hands every text it builds to the extension, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val context = PatchContexts.of(reactClasses(bundle))
                val builder = context.reactTextBuilders().second.let {
                    assertEquals("$name: React's text builders", 1, it.size)
                    it.single()
                }
                val original = builder.implementation!!.instructions.toList()
                val returns = original.count { it.opcode == Opcode.RETURN_OBJECT }
                assertTrue("$name: the builder returns", returns >= 1)

                val method = context.mutableClassDefBy(builder.definingClass).methods
                    .single { it.name == builder.name && it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString) }
                method.handBuiltTextOver()
                val body = method.implementation!!.instructions.toList()

                val calls = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == PRICE_LINE }
                assertEquals("$name: one call per return", returns, calls.size)
                calls.forEach { at ->
                    val call = body[at] as RegisterRangeInstruction
                    val answer = body[at + 1] as OneRegisterInstruction
                    val back = body[at + 2] as OneRegisterInstruction
                    assertEquals("$name: the call, then its answer, then the return",
                        listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                        listOf(body[at].opcode, body[at + 1].opcode, body[at + 2].opcode))
                    assertEquals("$name: about the returned text, into its register", listOf(1, back.registerA, back.registerA),
                        listOf(call.registerCount, call.startRegister, answer.registerA))
                }
                assertEquals("$name: every return comes right after its answer", returns,
                    body.indices.count { body[it].opcode == Opcode.RETURN_OBJECT && body[it - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                        (body[it - 2] as? ReferenceInstruction)?.reference?.toString() == PRICE_LINE })
                val jumps = method.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
                assertTrue("$name: nothing jumps past the call to a return",
                    jumps.none { body[it.target.location.index].opcode == Opcode.RETURN_OBJECT })
                assertEquals("$name: the rest of the builder", original.map { it.opcode },
                    body.filterIndexed { at, _ -> at !in calls && at - 1 !in calls }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
