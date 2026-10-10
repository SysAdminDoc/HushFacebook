/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route six of Accent color: the profile's Add to story button is a Bloks box and its bio link is
 * Bloks text, and both colours come from Facebook's server as hex strings, so neither a resolver nor
 * a resource gives them. On each declared build the box builder beside BoxDecoration's border
 * reader has to hand its fill to AccentColor.bloksFill before it sets the fill Paint's colour, and
 * the text span builder has to hand its colour to AccentColor.bloksText before it makes the span.
 *
 * Read from 582 (2026-10-10): the border reader is `LX/4Nl;->A00(LX/4TW;LX/1Vp;)I`, the builder
 * `LX/4Nl;->A01(LX/4TW;LX/1Vp;I)LX/4Nm;`, whose drawable fills with Paint `A05`, the themed colour
 * reader `LX/4MD;->A00` and the text span builder `LX/4MC;->A02`. None of those names is used here.
 */
class BloksFillFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    @After
    fun forgetTheLastMatch() = BoxDecorationBorderFingerprint.clearMatch()

    @Test
    fun `each Bloks box's fill goes through the accent before it's painted, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, BOX_DECORATION_BORDER_ERROR)
                assertEquals("$name: one class logs a bad BoxDecoration border", 1, owners.size)
                val box = owners.single()
                val borders = box.methods.filter { holdsString(it, BOX_DECORATION_BORDER_ERROR) }
                assertEquals("$name: one border reader", 1, borders.size)
                val border = borders.single()
                assertEquals("$name: the border reader answers a colour", "I", border.returnType)

                // The builder, found here without the patch's own search: a static method that takes
                // the reader's parameters and an int, and sets a Paint's colour to that int.
                val builders = box.methods.filter { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 3 &&
                        method.parameterTypes.last().toString() == "I" &&
                        method.body().any { it.setsPaintColour(method.parameterRegisterNumber(2)) }
                }
                assertEquals("$name: one builder paints its int parameter", 1, builders.size)
                val builder = builders.single()
                assertTrue("$name: the patch's search finds the same builder", isBoxBuilder(builder, border))
                assertEquals("$name: and only it", 1, box.methods.count { isBoxBuilder(it, border) })
                val fill = builder.parameterRegisterNumber(2)

                BoxDecorationBorderFingerprint.clearMatch()
                val context = PatchContexts.of(listOf(box))
                with(context) { hookBloksFills() }
                val after = context.mutableClassDefBy(box.type).methods.single { it.descriptor() == builder.descriptor() }.body()

                val hook = after[0]
                assertEquals("$name: the builder starts with bloksFill", ACCENT_BLOKS_FILL,
                    ((hook as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: on the fill's register", fill, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$name: one register", 1, hook.registerCount)
                assertEquals("$name: and the answer", Opcode.MOVE_RESULT, after[1].opcode)
                assertEquals("$name: goes back into the fill", fill, (after[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest of the builder is as it was", builder.body().size + 2, after.size)
                assertTrue("$name: and still paints the fill", after.drop(2).any { it.setsPaintColour(fill) })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each Bloks text span's colour goes through the accent before the span is made, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val box = FixtureDex.classesHolding(bundle, BOX_DECORATION_BORDER_ERROR).single()
                val border = box.methods.single { holdsString(it, BOX_DECORATION_BORDER_ERROR) }
                val reader = themedColourReader(border)
                assertEquals("$name: the themed reader answers a colour", "I", reader.returnType)

                val builders = FixtureDex.methodsWhere(bundle, { true }) { isBloksTextBuilder(it, reader) }
                assertEquals("$name: one Bloks text span builder", 1, builders.size)
                val builder = builders.single()
                val init = builder.body().indexOfFirst { it.makesColourSpan() }
                val colour = (builder.body()[init] as FiveRegisterInstruction).registerD
                // The colour comes from the reader: its answer is read into the span's register before the span is made.
                val asked = builder.body().take(init).withIndex().any { (index, instruction) ->
                    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    call?.name == reader.name && call.definingClass == reader.definingClass &&
                        (builder.body()[index + 1] as? OneRegisterInstruction)?.registerA == colour
                }
                assertTrue("$name: the span's colour is the themed reader's answer", asked)

                BoxDecorationBorderFingerprint.clearMatch()
                val owner = FixtureDex.classes(bundle, setOf(builder.definingClass))[builder.definingClass]!!
                val context = PatchContexts.of(listOf(box, owner))
                with(context) { hookBloksText() }
                val after = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == builder.descriptor() }.body()

                assertEquals("$name: the span is still made first", Opcode.NEW_INSTANCE, after[init - 1].opcode)
                val hook = after[init]
                assertEquals("$name: then the colour goes to bloksText", ACCENT_BLOKS_TEXT,
                    ((hook as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: on its own register", colour, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$name: one register", 1, hook.registerCount)
                assertEquals("$name: and comes back there", colour, (after[init + 1] as OneRegisterInstruction).registerA)
                assertTrue("$name: before the span's constructor", after[init + 2].makesColourSpan())
                assertEquals("$name: the rest of the builder is as it was", builder.body().size + 2, after.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Instruction.setsPaintColour(register: Int): Boolean {
        val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return call.definingClass == "Landroid/graphics/Paint;" && call.name == "setColor" &&
            (this as FiveRegisterInstruction).registerD == register
    }
}
