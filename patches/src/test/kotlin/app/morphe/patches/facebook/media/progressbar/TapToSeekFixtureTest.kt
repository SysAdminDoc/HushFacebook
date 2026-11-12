/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tap the bar to seek on every Facebook build the bundle declares (issue #125): the scrubber's own
 * SeekBar class, found from the base's check of the bar it reads, has its own touch handler, the
 * one with the thumb check. The hook goes first in it and hands the extension the bar and the
 * touch. A yes returns true, and a no runs Facebook's code from its start, none of it moved. Reads
 * the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TapToSeekFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    @Test
    fun `each declared build asks the extension first in the bar's touch handler`() = bundles { bundle ->
        val name = bundle.name
        val looks = vddScrubber(
            FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
            FixtureDex.classesHolding(bundle, SCRUBBER_ACTIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val progress = progressUpdate(
            FixtureDex.classesHolding(bundle, SCRUBBER_UPDATE).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val barType = scrubberSeekBar(progress.base, looks.barField)
        val bar = FixtureDex.classes(bundle, setOf(barType)).values.single()
        assertTrue("$name: $barType isn't a SeekBar of Facebook's", bar.superclass == SEEK_BAR)
        assertTrue("$name: the patch's own check takes $barType as a SeekBar",
            extendsType(barType, SEEK_BAR) { if (it == barType) bar.superclass else null })
        val touch = barTouch(bar)
        val where = "$name: $barType->onTouchEvent"
        val original = touch.code()
        assertTrue("$where doesn't read the thumb, so it isn't the handler with the thumb check",
            original.any { it.reference() == "Landroid/widget/AbsSeekBar;->getThumb()Landroid/graphics/drawable/Drawable;" })

        val context = PatchContexts.of(listOf(bar))
        val method = context.mutableClassDefBy(barType).methods.single { it.sameAs(touch) }
        method.takesTapsToSeek()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
        assertEquals("$where: the extension is asked first", TAKES_TOUCH, patched[0].reference())
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: with the bar and its touch, p0 and p1", method.implementation!!.registerCount - 2, call.startRegister)
        assertEquals("$where: with the bar and its touch, p0 and p1", 2, call.registerCount)
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a no jumps on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: from the same register", register, (patched[2] as OneRegisterInstruction).registerA)
        assertEquals("$where: a yes answers true", Opcode.CONST_4, patched[3].opcode)
        assertEquals("$where: a yes answers true", register, (patched[3] as OneRegisterInstruction).registerA)
        assertEquals("$where: a yes answers true", 1, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: a yes answers true", Opcode.RETURN, patched[4].opcode)
        assertEquals("$where: a yes answers true", register, (patched[4] as OneRegisterInstruction).registerA)
        assertEquals("$where: a no runs Facebook's code from its start", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
    }
}
