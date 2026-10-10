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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the progress bar's Reels hooks on every Facebook build the bundle declares: the scrubber's
 * minimum length check answering yes while kept, so a short reel gets a bar, its progress update
 * having Facebook's own time writer refresh the time label first, the wait before its next update
 * cut to a frame, and the Reels footer's bar check reading a short reel's length as long enough, so
 * the caption leaves room for the bar. Each hook asks the extension first or works in Facebook's own
 * register, a no lands on Facebook's code, and nothing of Facebook's code moves. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class KeepProgressBarReelsFixtureTest {
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

    private class Scrubber(val looks: ScrubberLooks, val scrubber: ClassDef, val progress: ProgressUpdate)

    private fun scrubber(bundle: File): Scrubber {
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val looks = vddScrubber(holders)
        val scrubber = holders.single { it.type == looks.type }
        val progress = progressUpdate(
            FixtureDex.classesHolding(bundle, SCRUBBER_UPDATE).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        assertEquals("${bundle.name}: the progress update isn't on the scrubber's base", scrubber.superclass, progress.base.type)
        return Scrubber(looks, scrubber, progress)
    }

    @Test
    fun `each declared build gives a short reel a bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val scrubber = scrubber(bundle).scrubber
        val check = minimumLengthCheck(scrubber)
        val where = "$name: ${scrubber.type}->${check.name}"
        assertTrue("$where doesn't load \"$LENGTH_CHECK_SURFACE\"", check.code().any { it.reference() == LENGTH_CHECK_SURFACE })
        // The hook borrows v0: this, the length and the boolean are the parameter registers.
        assertTrue("$where has no local register for the hook", check.implementation!!.registerCount - 3 >= 1)

        val context = PatchContexts.of(listOf(scrubber))
        val method = context.mutableClassDefBy(scrubber.type).methods.single { it.sameAs(check) }
        val original = method.code()
        method.longEnoughWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", BARS_EVERY_REEL, patched[0].reference())
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes answers true", 1, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: and returns it", Opcode.RETURN, patched[4].opcode)
        assertEquals("$where: from the same register", register, (patched[4] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build has Facebook's writer refresh the shown time first`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val looks = found.looks
        val progress = found.progress
        val base = progress.base
        val where = "$name: ${base.type}->${progress.update.name}"
        assertTrue("$where doesn't trace \"$SCRUBBER_UPDATE\"", progress.update.code().any { it.reference() == SCRUBBER_UPDATE })
        assertTrue("$name: ${base.type}->${progress.writeTime.name} sets no text",
            progress.writeTime.code().any { it.reference() == TEXT_VIEW_SET_TEXT })
        assertTrue("$name: the writer doesn't reach the views holder the time label is in",
            progress.writeTime.code().any { it.reference()?.startsWith(looks.labelField.substringBefore("->")) == true })
        assertTrue("$where has no local register for the hook", progress.update.implementation!!.registerCount - 1 >= 1)

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(progress.update) }
        val original = method.code()
        method.refreshesTimeFirst(progress, looks)
        val patched = method.code()
        val local = method.localRegisterCount()
        assertEquals("$where gains eight instructions", original.size + 8, patched.size)
        assertEquals("$where: the scrubber moves into a local through the wide form", Opcode.MOVE_OBJECT_FROM16, patched[0].opcode)
        val register = (patched[0] as TwoRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < local)
        assertEquals("$where: from p0", local, (patched[0] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the views holder is read", looks.viewsField, patched[1].reference())
        assertEquals("$where: a missing holder goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: then the time label", looks.labelField, patched[3].reference())
        assertEquals("$where: the extension is asked about the label", REFRESHES_TIME, patched[4].reference())
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[5].opcode)
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[6].opcode)
        assertEquals("$where: a yes runs Facebook's writer", "${base.type}->${progress.writeTime.name}(${base.type})V", patched[7].reference())
        val call = patched[7] as RegisterRangeInstruction
        assertEquals("$where: on this scrubber", listOf(local, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(8).map { it.opcode })
        val flow = ControlFlow.of(method)
        assertEquals("$where: a missing holder lands on Facebook's first instruction", setOf(3, 8), flow.normal[2].toSet())
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(7, 8), flow.normal[6].toSet())
    }

    @Test
    fun `each declared build moves the kept bar about once a frame`() = bundles { bundle ->
        val name = bundle.name
        val progress = scrubber(bundle).progress
        val base = progress.base
        val delay = frameDelay(progress)
        val where = "$name: ${base.type}->${delay.name}"
        assertTrue("$where isn't static", AccessFlags.STATIC.isSet(delay.accessFlags))
        assertTrue("$where isn't asked by the progress update",
            progress.update.code().any { it.reference() == "${base.type}->${delay.name}(${base.type})J" })
        // The hook borrows v0 and v1 for the wide answer: the scrubber is the one parameter register.
        assertTrue("$where has no two local registers for the hook", delay.implementation!!.registerCount - 1 >= 2)

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(delay) }
        val original = method.code()
        method.smoothWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", SMOOTHS_BAR, patched[0].reference())
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register and the next, which aren't locals", register + 1 < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes waits a frame", SMOOTH_FRAME_DELAY_MS.toLong(), (patched[3] as WideLiteralInstruction).wideLiteral)
        assertEquals("$where: as a long", Opcode.CONST_WIDE_16, patched[3].opcode)
        assertEquals("$where: and returns it", Opcode.RETURN_WIDE, patched[4].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build leaves a short reel's caption room for the bar while kept`() = bundles { bundle ->
        val name = bundle.name
        fun classOf(type: String) = FixtureDex.classes(bundle, setOf(type)).values.singleOrNull()
        val holders = FixtureDex.classesHolding(bundle, REEL_FOOTER).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        assertTrue("$name: nothing loads \"$REEL_FOOTER\"", holders.isNotEmpty())
        val (owner, check) = footerBarCheck(holders, ::classOf)
        val where = "$name: ${owner.type}->${check.name}"
        val read = lengthRead(check)!!
        val register = (check.code()[read] as OneRegisterInstruction).registerA
        val compare = check.code().drop(read + 1).first { it.opcode == Opcode.IF_LT } as TwoRegisterInstruction
        assertEquals("$where compares something else first", register, compare.registerA)

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(check) }
        val original = method.code()
        method.roomForEveryReel()
        val patched = method.code()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        assertEquals("$where: Facebook's code up to the length stays", original.take(read + 1).map { it.opcode },
            patched.take(read + 1).map { it.opcode })
        assertEquals("$where: the length goes to the extension", REEL_LENGTH, patched[read + 1].reference())
        val call = patched[read + 1] as RegisterRangeInstruction
        assertEquals("$where: in its own register", listOf(register, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: and its answer replaces it", Opcode.MOVE_RESULT, patched[read + 2].opcode)
        assertEquals("$where: in the same register", register, (patched[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code after it stays", original.drop(read + 1).map { it.opcode },
            patched.drop(read + 3).map { it.opcode })
    }
}
