/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the progress bar's Reels hooks on every Facebook build the bundle declares: the scrubber's
 * bar info builder asking the extension whether a reel has a bar at all (issue #73), its
 * minimum length check answering yes while kept, so a short reel gets a bar, on the scrubber and on
 * the Reels tab's subclass overriding it (issue #73), its progress update
 * having Facebook's own time writer refresh the time label once the update has moved the bar (never
 * before its position checks, issue #120) after running the passive look where the scrubber's look no
 * longer matches the switch (issue #73), the wait before its next update cut to a frame, the
 * short reel check answering no so the bar isn't faded out, the look selector reporting each look
 * and letting a look that shows the bar take the place of a parked hide (issue #73),
 * and the Reels footer's seek bar status starting and staying VISIBLE, so a playing reel's caption
 * sits above the bar (issue #73), and the Reels registry's hand-over telling the extension which reel
 * the scrubber holds and which is on screen, then whether the light mount ran, with the mount
 * reporting itself and the look selector asking the extension for each look first (issues #90 and
 * #120), and the extension able to run the scrubber's progress update again (#120). Each hook asks the extension, a no lands on Facebook's code, and nothing of Facebook's code
 * moves. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class KeepProgressBarReelsFixtureTest {
    private companion object {
        const val GET_MAX = "Landroid/widget/ProgressBar;->getMax()I"
    }

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
        // Up to 583 the update is on the base the scrubber extends, on 584 on the scrubber itself.
        assertEquals("${bundle.name}: the progress update isn't on the class holding the views", scrubberBase(looks), progress.base.type)
        assertTrue("${bundle.name}: ${progress.base.type} isn't the scrubber or its base", progress.base.type in listOfNotNull(scrubber.type, scrubber.superclass))
        return Scrubber(looks, scrubber, progress)
    }

    @Test
    fun `each declared build gives a short reel a bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val scrubber = found.scrubber
        val check = scrubberLengthChecks(scrubber)?.minimum
        if (check == null) {
            // 584 has none of its own: the bar info builder works it out, and its hook answers it there.
            assertNotNull("$name: ${scrubber.type} has no length check, and its bar info builder doesn't work one out",
                barInfo(found.progress.base, null).scrubbable)
            return@bundles
        }
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
    fun `each declared build hooks the length checks a scrubber subclass overrides`() = bundles { bundle ->
        val name = bundle.name
        val scrubber = scrubber(bundle).scrubber
        // 584 inlined the Reels tab's checks into the bar info builder, which the bar info test covers.
        val lengthChecks = scrubberLengthChecks(scrubber) ?: return@bundles
        val minimum = lengthChecks.minimum
        val checks = listOf(minimum, lengthChecks.short)
        val superclasses = mutableMapOf<String, String?>()
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                superclasses[classDef.type] = classDef.superclass
                if (classDef.type != scrubber.type && classDef.methods.any { method -> checks.any { method.sameAs(it) } }) {
                    holders += ImmutableClassDef.of(classDef)
                }
            }
        }
        val overrides = lengthCheckOverrides(holders, scrubber.type, checks) { superclasses[it] }
        // #73: 582's Reels tab scrubber overrides both checks with a minute's minimum, so a reel under it had no
        // passive look, and the bar showed on every other such reel.
        assertEquals("$name: the checks a class below ${scrubber.type} overrides", checks.map { it.name }.toSet(),
            overrides.map { it.second.name }.toSet())
        val context = PatchContexts.of(holders.filter { holder -> overrides.any { it.first == holder.type } })
        for ((type, check) in overrides) {
            val where = "$name: $type->${check.name}"
            val method = context.mutableClassDefBy(type).methods.single { it.sameAs(check) }
            val original = method.code()
            val longEnough = check.sameAs(minimum)
            if (longEnough) method.longEnoughWhileKept() else method.notShortWhileKept()
            val patched = method.code()
            assertEquals("$where: the extension is asked first", if (longEnough) BARS_EVERY_REEL else NO_FADED_BAR,
                patched[0].reference())
            val register = (patched[1] as OneRegisterInstruction).registerA
            assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
            assertEquals("$where: a yes answers ${if (longEnough) 1 else 0}", if (longEnough) 1 else 0,
                (patched[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
            assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
        }
    }

    @Test
    fun `each declared build asks the extension whether a reel has a bar at all`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val base = found.progress.base
        val info = barInfo(base, scrubberLengthChecks(found.scrubber)?.minimum)
        val where = "$name: ${base.type}->${info.builder.name}"
        val code = info.builder.code()
        val init = code[info.call] as RegisterRangeInstruction
        val parameters = ((code[info.call] as ReferenceInstruction).reference as MethodReference).parameterTypes.map(CharSequence::toString)
        assertEquals("$where: the bar info's constructor", "${info.builder.returnType}-><init>", code[info.call].reference()!!.substringBefore("("))
        assertEquals("$where: what the bar info takes", BAR_INFO_PARAMETERS, parameters.sorted())
        val start = init.startRegister
        val booleans = parameters.indices.filter { parameters[it] == "Z" }.map { start + 1 + it }
        assertEquals("$where: the has-a-bar flag is the first boolean", booleans[0], info.flag)
        assertEquals("$where: the length is the int", start + 1 + parameters.indexOf("I"), info.length)
        val inline = info.scrubbable != null
        if (inline) {
            assertEquals("$where: the checks' answers are the other booleans", booleans.drop(1), listOf(info.scrubbable, info.faded))
            // The Reels tab's own minimum came along, behind a check for its scrubber class.
            val tested = code.filter { it.opcode == Opcode.INSTANCE_OF }.mapNotNull { it.reference() }.toSet()
            val superclasses = mutableMapOf<String, String?>()
            FixtureDex.forEach(bundle) { dex -> dex.classes.forEach { superclasses[it.type] = it.superclass } }
            fun below(type: String?): Boolean = type != null && (type == found.scrubber.type || below(superclasses[type]))
            assertTrue("$where: no check for a class extending ${found.scrubber.type}",
                tested.any { it != found.scrubber.type && below(it) })
        } else {
            assertEquals("$where: no inline answers without inline checks", null, info.faded)
        }
        // #73: the constructor's first flag is the one the scrubber reads before choosing a look, and a no is the
        // invisible look, so the bar never showed on such a reel.
        val selector = lookSelector(base, found.looks)
        val infoClass = FixtureDex.classes(bundle, setOf(info.builder.returnType))[info.builder.returnType]
            ?: error("$where: the bar info's class ${info.builder.returnType} isn't in the APK")
        val constructor = infoClass.methods.single {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        // The parameters take the last registers, one each, after this.
        val hasField = constructor.code().first {
            it.opcode == Opcode.IPUT_BOOLEAN &&
                (it as TwoRegisterInstruction).registerA == constructor.implementation!!.registerCount - parameters.size + parameters.indexOf("Z")
        }.reference()
        assertTrue("$where: nothing on the base reads the info's first flag and then picks a look",
            base.methods.any { method ->
                val calls = method.implementation?.instructions?.toList().orEmpty()
                calls.any { it.opcode == Opcode.IGET_BOOLEAN && it.reference() == hasField } &&
                    calls.any { it.reference()?.contains("->${selector.name}(Ljava/lang/Integer;)V") == true }
            })

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(info.builder) }
        val original = method.code()
        method.hasBarWhileKept(info)
        val patched = method.code()
        val gained = if (inline) 9 else 2
        assertEquals("$where gains $gained instructions", original.size + gained, patched.size)
        assertEquals("$where: Facebook's code up to the constructor stays", original.take(info.call).map { it.opcode },
            patched.take(info.call).map { it.opcode })
        assertEquals("$where: the info is still made right before", Opcode.NEW_INSTANCE, patched[info.call - 1].opcode)
        assertEquals("$where: the extension is asked", HAS_BAR, patched[info.call].reference())
        val ask = patched[info.call] as FiveRegisterInstruction
        assertEquals("$where: with the has-a-bar flag and the length", listOf(2, info.flag, info.length),
            listOf(ask.registerCount, ask.registerC, ask.registerD))
        assertEquals("$where: its answer replaces the flag", Opcode.MOVE_RESULT, patched[info.call + 1].opcode)
        assertEquals("$where: in its own register", info.flag, (patched[info.call + 1] as OneRegisterInstruction).registerA)
        if (inline) {
            val at = info.call + 2
            assertEquals("$where: then the minimum is asked", BARS_EVERY_REEL, patched[at].reference())
            val v = (patched[at + 1] as OneRegisterInstruction).registerA
            assertTrue("$where: the hook borrows v$v, which isn't free",
                v < method.localRegisterCount() && v !in RegisterLiveness.of(info.builder).liveInto(info.call))
            assertEquals("$where: a yes makes the reel scrubbable", listOf(Opcode.OR_INT, info.scrubbable, info.scrubbable, v),
                (patched[at + 2] as ThreeRegisterInstruction).let { listOf(it.opcode, it.registerA, it.registerB, it.registerC) })
            assertEquals("$where: then the faded look is asked", NO_FADED_BAR, patched[at + 3].reference())
            assertEquals("$where: into the same local", v, (patched[at + 4] as OneRegisterInstruction).registerA)
            assertEquals("$where: its answer turned over", listOf(Opcode.XOR_INT_LIT8, v, v, 1),
                (patched[at + 5] as TwoRegisterInstruction).let { listOf(it.opcode, it.registerA, it.registerB, (it as NarrowLiteralInstruction).narrowLiteral) })
            assertEquals("$where: a yes keeps the reel from fading", listOf(Opcode.AND_INT, info.faded, info.faded, v),
                (patched[at + 6] as ThreeRegisterInstruction).let { listOf(it.opcode, it.registerA, it.registerB, it.registerC) })
        }
        assertEquals("$where: the constructor and the rest stay", original.drop(info.call).map { it.opcode },
            patched.drop(info.call + gained).map { it.opcode })
        val flow = ControlFlow.of(method)
        val into = patched.indices.filter { info.call in flow.normal[it] }
        assertEquals("$where: something reaches the hook other than the new-instance", listOf(info.call - 1), into)
        assertTrue("$where: the hook is in a try block", flow.exceptional[info.call].isEmpty())
    }

    @Test
    fun `each declared build has Facebook's writer refresh the shown time once the bar has moved`() = bundles { bundle ->
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
        // Issue #120: the writer moves the bar with no check of its own, so it may run only where Facebook's update
        // has found a usable position and length. The update reads the bar's maximum only past both checks, itself
        // or, on 583, through the base's helper that scales the position to the bar (LX/9gJ;->A01).
        assertTrue("$name: the writer no longer moves the bar", progress.writeTime.code().any { it.reference() == SET_PROGRESS })
        val at = dragCheck(progress.update, progress)
        val inflow = ControlFlow.of(progress.update)
        val update = progress.update.code()
        val scalers = base.methods.filter { method -> method.implementation?.instructions?.any { it.reference() == GET_MAX } == true }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        val maxReads = update.indices.filter { update[it].reference() == GET_MAX || update[it].reference() in scalers }.toSet()
        assertTrue("$where reads no bar maximum", maxReads.isNotEmpty())
        val reached = mutableSetOf(0)
        val queue = ArrayDeque(listOf(0))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next in maxReads) continue
            (inflow.normal[next] + inflow.exceptional[next]).forEach { if (reached.add(it)) queue += it }
        }
        assertTrue("$where reaches its drag check without reading the bar's maximum, before its position checks", at !in reached)

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(progress.update) }
        val original = method.code()
        method.refreshesTimeWithBar(progress, looks)
        val patched = method.code()
        val local = method.localRegisterCount()
        assertEquals("$where gains nine instructions", original.size + 9, patched.size)
        assertEquals("$where: Facebook's code up to the drag check stays", original.take(at).map { it.opcode },
            patched.take(at).map { it.opcode })
        assertEquals("$where: the scrubber moves into a local through the wide form", Opcode.MOVE_OBJECT_FROM16, patched[at].opcode)
        val register = (patched[at] as TwoRegisterInstruction).registerA
        val bar = (patched[at + 3] as TwoRegisterInstruction).registerA
        val live = RegisterLiveness.of(progress.update).liveInto(at)
        for (borrowed in listOf(register, bar)) {
            assertTrue("$where: the hook writes v$borrowed, which isn't a low local", borrowed < minOf(local, 16))
            assertTrue("$where: the hook borrows v$borrowed, which Facebook reads on", borrowed !in live)
        }
        assertNotEquals("$where: the bar and the label share a register", register, bar)
        assertEquals("$where: from p0", local, (patched[at] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the views holder is read", looks.viewsField, patched[at + 1].reference())
        assertEquals("$where: a missing holder goes on to Facebook", Opcode.IF_EQZ, patched[at + 2].opcode)
        assertEquals("$where: then the bar", looks.barField, patched[at + 3].reference())
        assertEquals("$where: off the views holder", register, (patched[at + 3] as TwoRegisterInstruction).registerB)
        assertEquals("$where: then the time label", looks.labelField, patched[at + 4].reference())
        assertEquals("$where: the extension is asked about the label and its bar", REFRESHES_TIME, patched[at + 5].reference())
        val ask = patched[at + 5] as FiveRegisterInstruction
        assertEquals("$where: in that order", listOf(2, register, bar), listOf(ask.registerCount, ask.registerC, ask.registerD))
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 6].opcode)
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[at + 7].opcode)
        assertEquals("$where: a yes runs Facebook's writer", "${base.type}->${progress.writeTime.name}(${base.type})V",
            patched[at + 8].reference())
        val call = patched[at + 8] as RegisterRangeInstruction
        assertEquals("$where: on this scrubber", listOf(local, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: Facebook's drag check and the rest stay", original.drop(at).map { it.opcode },
            patched.drop(at + 9).map { it.opcode })
        assertEquals("$where: the drag check comes right after the hook", Opcode.SGET_OBJECT, patched[at + 9].opcode)
        val flow = ControlFlow.of(method)
        assertEquals("$where: a missing holder lands on the drag check", setOf(at + 3, at + 9), flow.normal[at + 2].toSet())
        assertEquals("$where: a no lands on the drag check", setOf(at + 8, at + 9), flow.normal[at + 7].toSet())
        val into = patched.indices.filter { at + 9 in flow.normal[it] }.toSet()
        assertEquals("$where: something jumps to the drag check past the hook", setOf(at + 2, at + 7, at + 8), into)
    }

    @Test
    fun `each declared build has the progress update give the bar the switch's look before the time refresh`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val looks = found.looks
        val progress = found.progress
        val base = progress.base
        val where = "$name: ${base.type}->${progress.update.name}"
        // The hook runs the passive look on the scrubber, which takes the active one in its place while kept.
        assertTrue("$name: ${looks.type}->${looks.passive.name} can't be run from the base's update",
            !AccessFlags.PRIVATE.isSet(looks.passive.accessFlags) && !AccessFlags.STATIC.isSet(looks.passive.accessFlags))
        assertTrue("$name: the update's class isn't the scrubber or its base", base.type in listOfNotNull(found.scrubber.type, found.scrubber.superclass))

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(progress.update) }
        val original = method.code()
        val at = dragCheck(method, progress)
        val live = RegisterLiveness.of(progress.update).liveInto(at)
        // In the patch's order: the refresh goes in first, and the look check at the same index lands in front of it.
        method.refreshesTimeWithBar(progress, looks)
        method.looksAgainFirst(looks, at)
        val patched = method.code()
        val local = method.localRegisterCount()
        val refresh = at + 11
        assertEquals("$where gains eleven instructions ahead of the refresh's nine", original.size + 20, patched.size)
        // Twenty more instructions can push a goto across the hooks past a short goto's reach, so the
        // assembler writes the long form of the same jump.
        val shapes = { code: List<Instruction> ->
            code.map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode }
        }
        assertEquals("$where: Facebook's code up to the drag check stays", shapes(original.take(at)), shapes(patched.take(at)))
        assertEquals("$where: the hook's code",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.IF_EQZ,
                Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CHECK_CAST,
                Opcode.INVOKE_VIRTUAL),
            patched.subList(at, refresh).map { it.opcode })
        val scrubber = (patched[at] as TwoRegisterInstruction).registerA
        assertEquals("$where: from p0", local, (patched[at] as TwoRegisterInstruction).registerB)
        assertEquals("$where: only a scrubber of ${looks.type}", looks.type, patched[at + 1].reference())
        val flag = (patched[at + 1] as TwoRegisterInstruction).registerA
        assertEquals("$where: the instance is checked", scrubber, (patched[at + 1] as TwoRegisterInstruction).registerB)
        for (borrowed in listOf(scrubber, flag)) {
            assertTrue("$where: the hook writes v$borrowed, which isn't a low local", borrowed < minOf(local, 16))
            assertTrue("$where: the hook borrows v$borrowed, which Facebook reads on", borrowed !in live)
        }
        assertNotEquals("$where: the scrubber and the flag share a register", scrubber, flag)
        assertEquals("$where: the views holder", looks.viewsField, patched[at + 3].reference())
        assertEquals("$where: off the scrubber", scrubber, (patched[at + 3] as TwoRegisterInstruction).registerB)
        assertEquals("$where: then the time label", looks.labelField, patched[at + 5].reference())
        assertEquals("$where: the extension is asked about the label", LOOKS_AGAIN, patched[at + 6].reference())
        assertEquals("$where: with the label", listOf(1, flag),
            (patched[at + 6] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC) })
        assertEquals("$where: its answer is kept", flag, (patched[at + 7] as OneRegisterInstruction).registerA)
        assertEquals("$where: cast for the look", looks.type, patched[at + 9].reference())
        assertEquals("$where: a yes runs the passive look", "${looks.type}->${looks.passive.name}()V", patched[at + 10].reference())
        assertEquals("$where: on this scrubber", listOf(1, scrubber),
            (patched[at + 10] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC) })
        assertEquals("$where: then the refresh as before", REFRESHES_TIME, patched[refresh + 5].reference())
        assertEquals("$where: Facebook's drag check and the rest stay", shapes(original.drop(at)), shapes(patched.drop(refresh + 9)))
        val flow = ControlFlow.of(method)
        for (no in listOf(at + 2, at + 4, at + 8)) {
            assertEquals("$where: a no at $no goes on to the refresh", setOf(no + 1, refresh), flow.normal[no].toSet())
        }
        assertEquals("$where: the look goes on to the refresh", setOf(refresh), flow.normal[at + 10].toSet())
        assertEquals("$where: something jumps onto the refresh past the hook", setOf(at + 2, at + 4, at + 8, at + 10),
            patched.indices.filter { refresh in flow.normal[it] }.toSet())
        extensionHas(where, LOOKS_AGAIN)
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
    fun `each declared build keeps a short reel from the faded bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val scrubber = found.scrubber
        val checks = scrubberLengthChecks(scrubber)
        if (checks == null) {
            // 584 has none of its own: the bar info builder works it out, and its hook answers it there.
            assertNotNull("$name: ${scrubber.type} has no short reel check, and its bar info builder doesn't work one out",
                barInfo(found.progress.base, null).faded)
            return@bundles
        }
        val check = checks.short
        val where = "$name: ${scrubber.type}->${check.name}"
        assertTrue("$where is the bar's own length check", check.name != checks.minimum.name)
        assertTrue("$where has no local register for the hook", check.implementation!!.registerCount - 3 >= 1)

        val context = PatchContexts.of(listOf(scrubber))
        val method = context.mutableClassDefBy(scrubber.type).methods.single { it.sameAs(check) }
        val original = method.code()
        method.notShortWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", NO_FADED_BAR, patched[0].reference())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes answers false", 0, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: and returns it", Opcode.RETURN, patched[4].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build reports the scrubber's look first in its selector`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val selector = lookSelector(found.progress.base, found.looks)
        val where = "$name: ${found.progress.base.type}->${selector.name}"
        // The looks it picks from: 0 passive, 1 and 3 active, 2 faded out, 4 invisible.
        val looks = selector.code().filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral }.toSet()
        assertTrue("$where no longer picks from looks 0 to 4: $looks", looks.containsAll(listOf(0, 1, 2, 3, 4)))

        val context = PatchContexts.of(listOf(found.progress.base))
        val method = context.mutableClassDefBy(found.progress.base.type).methods.single { it.sameAs(selector) }
        val original = method.code()
        method.countsLook()
        val patched = method.code()
        assertEquals("$where: the look goes to the extension first", COUNTS_LOOK, patched[0].reference())
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: from p1", listOf(method.localRegisterCount() + 1, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(1).map { it.opcode })
    }

    @Test
    fun `each declared build lets a look that shows the bar take a parked hide's place`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val base = found.progress.base
        val selector = lookSelector(base, found.looks)
        val where = "$name: ${base.type}->${selector.name}"
        val skip = sameLookSkip(selector) { FixtureDex.classes(bundle, setOf(it))[it] }
        // #73: the parked look is the state's second Integer, the one the selector stores the look it's asked for in
        // while a reel's player changes. A hide parked as one reel left landed on the next one after its own look
        // was skipped as the one the bar had. The state is made by a constructor taking both looks, or on 583 by a
        // static factory of the state's class (LX/9JM;->A00), which stores them the same way.
        val state = FixtureDex.classes(bundle, setOf(skip.state)).getValue(skip.state)
        val init = state.methods.single { method ->
            (method.name == "<init>" || AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == state.type) &&
                method.parameterTypes.count { it.toString() == "Ljava/lang/Integer;" } == 2
        }
        val parameters = init.parameterTypes.map(CharSequence::toString)
        assertTrue("$where: the state's constructor takes a wide value", parameters.none { it == "J" || it == "D" })
        val second = init.implementation!!.registerCount - parameters.size + parameters.lastIndexOf("Ljava/lang/Integer;")
        val stored = init.code().single { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == second }
        assertEquals("$where: the parked look is the state's second Integer", skip.parked, stored.reference())

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(selector) }
        val original = method.code()
        method.replacesParkedLook(skip)
        val patched = method.code()
        val hook = patched.subList(skip.branch, skip.branch + 7)
        assertEquals("$where: the hook's code",
            listOf(Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT, Opcode.IF_NEZ),
            hook.map { it.opcode })
        assertEquals("$where: the hook reads the parked look", skip.parked, hook[3].reference())
        assertEquals("$where: and asks the extension", REPLACES_PARKED, hook[4].reference())
        val free = (hook[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$free, which isn't a local", free < method.localRegisterCount())
        assertTrue("$where: the hook writes v$free, which the selector reads later", free !in RegisterLiveness.of(selector).liveInto(skip.branch))
        val call = hook[4] as FiveRegisterInstruction
        assertEquals("$where: with the look asked for and the parked one", listOf(method.implementation!!.registerCount - 1, free),
            listOf(call.registerC, call.registerD))
        assertEquals("$where: Facebook's code stays", original.map { it.opcode },
            (patched.take(skip.branch) + patched.drop(skip.branch + 7)).map { it.opcode })
        assertEquals("$where: a no reaches the skip and a yes the code past it", setOf(skip.branch + 7, skip.branch + 8),
            ControlFlow.of(method).normal[skip.branch + 6].toSet())

        val extension = ExtensionDex.classDef(PROGRESS_BAR).methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == REPLACES_PARKED
        }
        assertTrue("$where: the extension has no $REPLACES_PARKED", extension != null && AccessFlags.STATIC.isSet(extension.accessFlags))
    }

    @Test
    fun `each declared build keeps a playing reel's caption above the bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val status = seekBarStatus(FixtureDex.classesHolding(bundle, STATUS_DRAGGING).filterNot { it.type.startsWith(EXTENSION_CLASSES) })
        val owners = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(owners) { owner -> owner.fields.any { it.type == status.type } }
        }
        val holder = statusHolder(owners, status)
        val owner = owners.single { it.type == holder.type }
        val where = "$name: ${holder.type}"
        val constructor = holder.constructor.code()
        assertEquals("$where doesn't start at $STATUS_HIDDEN", status.hidden, constructor[holder.initialStore - 1].reference())

        val context = PatchContexts.of(listOf(owner))
        val mutable = context.mutableClassDefBy(holder.type).methods
        val init = mutable.single { it.sameAs(holder.constructor) }
        init.startsLifted(holder, status)
        val started = init.code()
        val at = holder.started
        assertEquals("$where: Facebook's start stays", constructor.take(at).map { it.opcode }, started.take(at).map { it.opcode })
        assertEquals("$where: the extension is asked after the start is stored", LIFTS_FOOTER, started[at].reference())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, started[at + 2].opcode)
        assertEquals("$where: a yes loads $STATUS_VISIBLE", status.visible, started[at + 3].reference())
        assertEquals("$where: and stores it", holder.field, started[at + 4].reference())
        assertEquals("$where: Facebook's code after it stays", constructor.drop(at).map { it.opcode },
            started.drop(at + 5).map { it.opcode })

        val update = mutable.single { it.sameAs(holder.update) }
        val original = update.code()
        val store = holder.store
        update.staysLifted(holder, status)
        val patched = update.code()
        val free = (patched[store + 1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook borrows v$free, which it reads or Facebook does",
            free !in listOf(holder.new, holder.old, holder.self) && free !in RegisterLiveness.of(holder.update).liveInto(store + 1))
        assertEquals("$where: Facebook's code up to the store stays", original.take(store + 1).map { it.opcode },
            patched.take(store + 1).map { it.opcode })
        assertEquals("$where: $STATUS_HIDDEN is loaded to compare", status.hidden, patched[store + 1].reference())
        assertEquals("$where: with the new status", holder.new, (patched[store + 2] as TwoRegisterInstruction).registerA)
        assertEquals("$where: the extension is asked", LIFTS_FOOTER, patched[store + 3].reference())
        assertEquals("$where: $STATUS_VISIBLE replaces it", status.visible, patched[store + 6].reference())
        assertEquals("$where: in the new status' register", holder.new, (patched[store + 6] as OneRegisterInstruction).registerA)
        assertEquals("$where: and is stored", holder.field, patched[store + 7].reference())
        val unchanged = patched[store + 8] as TwoRegisterInstruction
        assertEquals("$where: compared with the old status", listOf(holder.old, holder.new), listOf(unchanged.registerA, unchanged.registerB))
        assertEquals("$where: which stops there", Opcode.RETURN_VOID, patched[store + 9].opcode)
        assertEquals("$where: Facebook's code after it stays", original.drop(store + 1).map { it.opcode },
            patched.drop(store + 10).map { it.opcode })
        assertTrue("$where: Facebook's Function1 call after the hook went missing",
            patched.drop(store + 10).any { it.reference() == FUNCTION1_INVOKE })
        val flow = ControlFlow.of(update)
        for (branch in listOf(store + 2, store + 5, store + 8)) {
            assertTrue("$where: a no at $branch doesn't land on Facebook's code", store + 10 in flow.normal[branch])
        }
        val into = patched.indices.filter { store + 1 in flow.normal[it] }.toSet()
        assertEquals("$where: something jumps past the hook", setOf(store), into)

        // #120: a press of a lifted footer stays lifted while the overlay is kept.
        update.staysLiftedOnPress(holder, status)
        val pressed = update.code()
        val spare = (pressed[store + 1] as OneRegisterInstruction).registerA
        assertTrue("$where: the press hook borrows v$spare, which it reads or Facebook does",
            spare !in listOf(holder.new, holder.old, holder.self) && spare !in RegisterLiveness.of(holder.update).liveInto(store + 1))
        assertEquals("$where: $STATUS_DRAGGING is loaded to compare", status.dragging, pressed[store + 1].reference())
        assertEquals("$where: with the new status", holder.new, (pressed[store + 2] as TwoRegisterInstruction).registerA)
        assertEquals("$where: $STATUS_VISIBLE is loaded to compare", status.visible, pressed[store + 3].reference())
        assertEquals("$where: with the old status", holder.old, (pressed[store + 4] as TwoRegisterInstruction).registerA)
        assertEquals("$where: the extension is asked", KEEPS_OVERLAY, pressed[store + 5].reference())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, pressed[store + 7].opcode)
        assertEquals("$where: $STATUS_VISIBLE goes back", status.visible, pressed[store + 8].reference())
        assertEquals("$where: in the new status' register", holder.new, (pressed[store + 8] as OneRegisterInstruction).registerA)
        assertEquals("$where: and is stored", holder.field, pressed[store + 9].reference())
        assertEquals("$where: which stops there, before the Function1 hears of it", Opcode.RETURN_VOID, pressed[store + 10].opcode)
        // The longer method can need a wider goto than Facebook's, which changes nothing else.
        fun Instruction.widened() = if (opcode == Opcode.GOTO) Opcode.GOTO_16 else opcode
        assertEquals("$where: the code behind it stays", patched.drop(store + 1).map { it.widened() }, pressed.drop(store + 11).map { it.widened() })
        val pressFlow = ControlFlow.of(update)
        for (branch in listOf(store + 2, store + 4, store + 7)) {
            assertTrue("$where: a no at $branch doesn't land on the code behind it", store + 11 in pressFlow.normal[branch])
        }
        assertEquals("$where: something jumps past the press hook", setOf(store), pressed.indices.filter { store + 1 in pressFlow.normal[it] }.toSet())
    }

    /** #120: a press of the bar asks first, and a yes hands the footer's call its everyday look instead of the seek one. */
    @Test
    fun `each declared build can keep the overlay on a press of the bar`() = bundles { bundle ->
        val name = bundle.name
        val visibility = footerVisibility(FixtureDex.classesHolding(bundle, FOOTER_SEEKING).filterNot { it.type.startsWith(EXTENSION_CLASSES) })
        val owners = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(owners) { owner -> owner.fields.any { it.type == FUNCTION1 } }
        }
        val seek = footerSeek(owners, visibility)
        val where = "$name: ${seek.type}"
        val original = seek.method.code()
        val at = seek.at
        assertTrue("$where: the footer's call never gets $FOOTER_SHOWN", original.any { it.reference() == visibility.shown })

        val context = PatchContexts.of(listOf(owners.first { it.type == seek.type }))
        val method = context.mutableClassDefBy(seek.type).methods.single { it.sameAs(seek.method) }
        method.keepsOverlayOnPress(seek, visibility)
        val patched = method.code()
        assertEquals("$where: Facebook's code up to the press stays", original.take(at).map { it.opcode }, patched.take(at).map { it.opcode })
        assertEquals("$where: the extension is asked", KEEPS_OVERLAY, patched[at].reference())
        assertEquals("$where: its answer is tested", Opcode.IF_EQZ, patched[at + 2].opcode)
        assertEquals("$where: a yes loads $FOOTER_SHOWN", visibility.shown, patched[at + 3].reference())
        assertEquals("$where: in the register the call reads", seek.register, (patched[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$where: a no still loads $FOOTER_SEEKING", visibility.seeking, patched[at + 5].reference())
        assertEquals("$where: the call follows it", FUNCTION1_INVOKE, patched[at + 6].reference())
        assertEquals("$where: Facebook's code from the load on stays", original.drop(at).map { it.opcode }, patched.drop(at + 5).map { it.opcode })
        val flow = ControlFlow.of(method)
        assertEquals("$where: a yes goes straight to the call", setOf(at + 6), flow.normal[at + 4].toSet())
        assertEquals("$where: a no reaches Facebook's load", setOf(at + 3, at + 5), flow.normal[at + 2].toSet())
        assertEquals("$where: something jumps past the question", setOf(at - 1), patched.indices.filter { at in flow.normal[it] }.toSet())
    }

    /**
     * #73's reel on screen as the switch goes on: each footer status holder reports itself as it's
     * made, the update handed no event lifts HIDDEN to VISIBLE through Facebook's own store while the
     * bar is kept, and the extension's stub reaches that through a helper on the holder's class.
     */
    @Test
    fun `each declared build lets the extension lift the footer of the reel on screen`() = bundles { bundle ->
        val name = bundle.name
        val status = seekBarStatus(FixtureDex.classesHolding(bundle, STATUS_DRAGGING).filterNot { it.type.startsWith(EXTENSION_CLASSES) })
        val owners = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(owners) { owner -> owner.fields.any { it.type == status.type } }
        }
        val holder = statusHolder(owners, status)
        val owner = ImmutableClassDef.of(owners.first { it.type == holder.type })
        val where = "$name: ${holder.type}"
        checkFooterLift(owner, holder)
        // Facebook's update tells its events apart by class first, so one handed none does nothing.
        val original = holder.update.code()
        assertEquals("$where: the update doesn't test its event first", Opcode.INSTANCE_OF, original[0].opcode)
        assertEquals("$where: on the event", holder.self + 1, (original[0] as TwoRegisterInstruction).registerB)

        val context = PatchContexts.of(listOf(owner, ExtensionDex.classDef(PROGRESS_BAR)))
        val methods = context.mutableClassDefBy(holder.type).methods
        val init = methods.single { it.sameAs(holder.constructor) }
        val constructor = init.code()
        init.startsLifted(holder, status)
        init.reportsFooter(holder)
        val made = init.code()
        val at = holder.started
        assertEquals("$where: the holder is reported right after its start is stored", FOOTER_MADE, made[at].reference())
        assertEquals("$where: with itself", (constructor[holder.initialStore] as TwoRegisterInstruction).registerB,
            (made[at] as FiveRegisterInstruction).registerC)
        assertEquals("$where: then the start's lift", LIFTS_FOOTER, made[at + 1].reference())

        val update = methods.single { it.sameAs(holder.update) }
        update.staysLifted(holder, status)
        val stayed = update.code()
        update.liftsWhenAsked(holder, status)
        val patched = update.code()
        val entry = 10
        assertEquals("$where: the entry's code",
            listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.SGET_OBJECT,
                Opcode.IF_NE, Opcode.SGET_OBJECT, Opcode.GOTO, Opcode.RETURN_VOID),
            patched.take(entry).map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode })
        assertEquals("$where: the extension is asked", LIFTS_FOOTER, patched[1].reference())
        assertEquals("$where: the status now is read", holder.field, patched[4].reference())
        assertEquals("$where: into the old status' register", holder.old, (patched[4] as TwoRegisterInstruction).registerA)
        assertEquals("$where: compared with $STATUS_HIDDEN", status.hidden, patched[5].reference())
        assertEquals("$where: $STATUS_VISIBLE is the new status", status.visible, patched[7].reference())
        assertEquals("$where: in the new status' register", holder.new, (patched[7] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code after the entry stays", stayed.map { it.opcode }, patched.drop(entry).map { it.opcode })
        val flow = ControlFlow.of(update)
        assertEquals("$where: an event goes to Facebook's code", setOf(1, entry), flow.normal[0].toSet())
        val store = holder.store + entry
        assertEquals("$where: the goto lands on Facebook's store", holder.field, patched[store].reference())
        assertEquals("$where: from the goto", setOf(store), flow.normal[8].toSet())
        for (no in listOf(3, 6)) assertTrue("$where: a no at $no returns", 9 in flow.normal[no])

        context.fillFooterLift(holder)
        val helper = context.mutableClassDefBy(holder.type).methods.single { it.name == FOOTER_LIFT_HELPER }
        assertTrue("$where: the helper is public and static",
            AccessFlags.PUBLIC.isSet(helper.accessFlags) && AccessFlags.STATIC.isSet(helper.accessFlags))
        val call = helper.code().single { it.opcode == Opcode.INVOKE_VIRTUAL }
        assertEquals("$where: the helper runs the update",
            "${holder.type}->${holder.update.name}(${holder.update.parameterTypes.single()})V", call.reference())
        val stub = context.mutableClassDefBy(PROGRESS_BAR).methods.single(::isLiftFooterStub).code()
        assertEquals("$where: the stub calls the helper", "${holder.type}->$FOOTER_LIFT_HELPER(Ljava/lang/Object;)Z", stub[0].reference())
        assertEquals("$where: and answers what it answers", listOf(Opcode.MOVE_RESULT, Opcode.RETURN), stub.subList(1, 3).map { it.opcode })
        extensionHas(where, FOOTER_MADE)
    }

    private fun extensionHas(where: String, descriptor: String) {
        val method = ExtensionDex.classDef(PROGRESS_BAR).methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == descriptor
        }
        assertTrue("$where: the extension has no $descriptor", method != null && AccessFlags.STATIC.isSet(method.accessFlags))
    }

    @Test
    fun `each declared build tells the extension of each reel hand-over and whether the light mount ran`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val looks = found.looks
        val base = found.progress.base
        val holders = FixtureDex.classesHolding(bundle, NEW_REELS_UNIT_SHOWN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val handOver = reelHandOver(holders, base.type)
        val another = anotherVideo(base, barInfo(base, scrubberLengthChecks(found.scrubber)?.minimum).builder.returnType, handOver)
        val owner = holders.single { it.type == handOver.method.definingClass }
        val where = "$name: ${owner.type}->${handOver.method.name}"
        val original = handOver.method.code()
        // #120: past the id's test Facebook skips the reel the scrubber's bar info already holds, by the same id
        // the hook hands the extension, and otherwise binds the scrubber to the reel's player, which runs the
        // light mount the mount hook reports. 583 runs the mount inline instead.
        assertTrue("$where no longer reads the scrubber's bar info", original.drop(handOver.at).any { it.reference() == another.info })
        assertTrue("$where no longer reads the bar info's video id", original.drop(handOver.at).any { it.reference() == another.id })
        assertTrue("$where no longer compares the ids", original.drop(handOver.at).any {
            it.reference() == "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"
        })
        val inline = another.mount.definingClass == owner.type && another.mount.sameAs(handOver.method)
        assertTrue("$where no longer binds the scrubber's player or runs the light mount", inline || original.drop(handOver.at).any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                call != null && call.returnType == "V" &&
                // 585 binds the player through small calls on the scrubber itself (a start and a bind taking the
                // player's session) instead of one six-parameter call, and the mount sits in a method those reach.
                (call.parameterTypes.size == 6 || call.definingClass == base.type)
        })
        assertEquals("$where: the scrubber's holder field isn't a ${base.type}", base.type, handOver.scrubber.substringAfter(":"))

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(handOver.method) }
        val live = RegisterLiveness.of(handOver.method).liveInto(handOver.at)
        method.checksHandOver(handOver)
        method.notesHandOver(handOver, looks, another)
        val patched = method.code()
        val at = handOver.at
        val end = handOver.end + 11
        assertEquals("$where gains twelve instructions", original.size + 12, patched.size)
        assertEquals("$where: Facebook's code up to the id's test stays", original.take(at).map { it.opcode },
            patched.take(at).map { it.opcode })
        assertEquals("$where: the hook's code",
            listOf(Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.CONST_4,
                Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC),
            patched.subList(at, at + 11).map { it.opcode })
        fun registers(index: Int) = (patched[index] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) }
        val scrubber = registers(at)[0]
        assertEquals("$where: the scrubber is taken off Facebook's holder", handOver.holder, registers(at)[1])
        assertEquals("$where: through the field Facebook reads it by", handOver.scrubber, patched[at].reference())
        assertEquals("$where: then its views holder", looks.viewsField, patched[at + 2].reference())
        assertEquals("$where: off the scrubber", scrubber, registers(at + 2)[1])
        val label = registers(at + 2)[0]
        assertEquals("$where: its bar info", another.info, patched[at + 4].reference())
        assertEquals("$where: off the scrubber too", scrubber, registers(at + 4)[1])
        val bar = registers(at + 4)[0]
        assertEquals("$where: no id without bar info", scrubber, (patched[at + 5] as OneRegisterInstruction).registerA)
        assertEquals("$where: and the info's video id", another.id, patched[at + 7].reference())
        assertEquals("$where: off the bar info, where the null was", listOf(scrubber, bar), registers(at + 7))
        assertEquals("$where: its bar", looks.barField, patched[at + 8].reference())
        assertEquals("$where: off the views holder, where the bar info was", listOf(bar, label), registers(at + 8))
        assertEquals("$where: its time label", looks.labelField, patched[at + 9].reference())
        assertEquals("$where: off the views holder, where it was", listOf(label, label), registers(at + 9))
        assertEquals("$where: the extension is asked", HANDS_OVER, patched[at + 10].reference())
        val ask = patched[at + 10] as FiveRegisterInstruction
        assertEquals("$where: with the label, the bar, the held id and the reel's id", listOf(4, label, bar, scrubber, handOver.shown),
            listOf(ask.registerCount, ask.registerC, ask.registerD, ask.registerE, ask.registerF))
        val locals = handOver.method.localRegisterCount()
        // A parameter only once the free locals run out, as on 584.
        val freeLocals = (0 until minOf(locals, 16)).count { it !in live }
        for (borrowed in setOf(scrubber, label, bar)) {
            assertTrue("$where: the hook writes v$borrowed, which isn't a low local or a parameter it needs",
                borrowed < 16 && (borrowed < locals || freeLocals < 3))
            assertTrue("$where: the hook borrows v$borrowed, which Facebook reads on", borrowed !in live)
        }
        assertEquals("$where: three registers borrowed", 3, setOf(scrubber, label, bar).size)
        assertEquals("$where: Facebook's test of the id comes right after the hook", original[at].opcode, patched[at + 11].opcode)
        val flow = ControlFlow.of(method)
        assertEquals("$where: a missing scrubber goes on to Facebook", setOf(at + 2, at + 11), flow.normal[at + 1].toSet())
        assertEquals("$where: a missing views holder goes on to Facebook", setOf(at + 4, at + 11), flow.normal[at + 3].toSet())
        assertEquals("$where: missing bar info asks with no id", setOf(at + 7, at + 8), flow.normal[at + 6].toSet())
        assertEquals("$where: something jumps past the hook", setOf(at + 1, at + 3, at + 10),
            patched.indices.filter { at + 11 in flow.normal[it] }.toSet())
        assertEquals("$where: something jumps onto the hook", setOf(at - 1), patched.indices.filter { at in flow.normal[it] }.toSet())

        assertEquals("$where: the extension is asked at the return", LEFT_ON_LAST_REEL, patched[end].reference())
        assertEquals("$where: with nothing", 0, (patched[end] as FiveRegisterInstruction).registerCount)
        assertEquals("$where: right before it", Opcode.RETURN_VOID, patched[end + 1].opcode)
        assertEquals("$where: something jumps past the return's hook", setOf(end - 1), patched.indices.filter { end in flow.normal[it] }.toSet())
        assertEquals("$where: Facebook's code from the id's test on stays", original.drop(at).map { it.opcode },
            (patched.subList(at + 11, end) + patched.drop(end + 1)).map { it.opcode })
        val reached = mutableSetOf(at)
        val queue = ArrayDeque(listOf(at))
        while (queue.isNotEmpty()) flow.normal[queue.removeFirst()].forEach { if (reached.add(it)) queue += it }
        assertEquals("$where: a way on from the hook returns without asking at the return", setOf(end + 1),
            reached.filter { patched[it].opcode == Opcode.RETURN_VOID }.toSet())
        extensionHas(where, HANDS_OVER)
        extensionHas(where, LEFT_ON_LAST_REEL)
    }

    @Test
    fun `each declared build tells the extension of each registry ask and of a reel with no scrubber`() = bundles { bundle ->
        val name = bundle.name
        val base = scrubber(bundle).progress.base
        val holders = FixtureDex.classesHolding(bundle, NEW_REELS_UNIT_SHOWN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val handOver = reelHandOver(holders, base.type)
        val returns = registryReturns(handOver)
        val owner = holders.single { it.type == handOver.method.definingClass }
        val where = "$name: ${owner.type}->${handOver.method.name}"
        val original = handOver.method.code()
        // #120: a reel that froze with another reel's bar logged no hand-over, so the registry returned at
        // its hide flag or for want of a scrubber holder, both ahead of the hand-over's hook.
        assertTrue("$where: the flag's test isn't ahead of the holder's", returns.test < returns.noHolder)
        assertTrue("$where: the holder's test isn't ahead of the hand-over", returns.noHolder < handOver.at)

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(handOver.method) }
        method.notesNoScrubber(returns)
        method.notesRegistryAsk(returns)
        val patched = method.code()
        val test = returns.test
        val none = returns.noHolder + 1
        assertEquals("$where gains four instructions", original.size + 4, patched.size)
        assertEquals("$where: the extension is asked at the flag's test", REGISTRY_ASKS, patched[test].reference())
        val ask = patched[test] as FiveRegisterInstruction
        assertEquals("$where: with the flag and the reel's key", listOf(2, returns.flag, returns.key),
            listOf(ask.registerCount, ask.registerC, ask.registerD))
        assertEquals("$where: Facebook's test of the flag comes right after", Opcode.IF_EQZ, patched[test + 1].opcode)
        assertEquals("$where: Facebook's return after hiding every bar", Opcode.RETURN_VOID, patched[test + 3].opcode)
        assertEquals("$where: the holder's test is the hook's", Opcode.IF_NEZ, patched[none].opcode)
        assertEquals("$where: on the holder", returns.holder, (patched[none] as OneRegisterInstruction).registerA)
        assertEquals("$where: the extension is told there's no scrubber", NO_SCRUBBER, patched[none + 1].reference())
        assertEquals("$where: with nothing", 0, (patched[none + 1] as FiveRegisterInstruction).registerCount)
        // #120: the hand-over came too early for the scrubber's next load, so the extension keeps its arguments.
        assertEquals("$where: the extension gets the hand-over's arguments", MISSES_HAND_OVER, patched[none + 2].reference())
        val kept = patched[none + 2] as RegisterRangeInstruction
        val registers = handOver.method.implementation!!.registerCount
        assertEquals("$where: all of them, the instance first", listOf(HAND_OVER_PARAMETERS + 1, registers - HAND_OVER_PARAMETERS - 1),
            listOf(kept.registerCount, kept.startRegister))
        assertEquals("$where: Facebook's test for a holder comes right after", Opcode.IF_EQZ, patched[none + 3].opcode)
        val flow = ControlFlow.of(method)
        assertEquals("$where: something jumps onto the ask", setOf(test - 1), patched.indices.filter { test in flow.normal[it] }.toSet())
        assertEquals("$where: something jumps onto the holder's hook", setOf(none - 1),
            patched.indices.filter { none in flow.normal[it] }.toSet())
        assertEquals("$where: a holder doesn't go straight on to Facebook's test", setOf(none + 1, none + 3), flow.normal[none].toSet())
        assertEquals("$where: something jumps onto Facebook's test for a holder", setOf(none, none + 2),
            patched.indices.filter { none + 3 in flow.normal[it] }.toSet())
        assertEquals("$where: no holder no longer returns where the hide does", setOf(test + 2, none + 3),
            patched.indices.filter { test + 3 in flow.normal[it] }.toSet())
        assertEquals("$where: Facebook's code changed", original.map { it.opcode },
            patched.indices.filterNot { it in setOf(test, none, none + 1, none + 2) }.map { patched[it].opcode })
        extensionHas(where, REGISTRY_ASKS)
        extensionHas(where, NO_SCRUBBER)
        extensionHas(where, MISSES_HAND_OVER)
    }

    @Test
    fun `each declared build reports a scrubber's load and keeps a newer holder past an older unload`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val base = found.progress.base
        val holders = FixtureDex.classesHolding(bundle, NEW_REELS_UNIT_SHOWN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val handOver = reelHandOver(holders, base.type)
        val returns = registryReturns(handOver)
        val another = anotherVideo(base, barInfo(base, scrubberLengthChecks(found.scrubber)?.minimum).builder.returnType, handOver)
        val load = scrubberLoad(base, another, returns)
        val clear = holderClear(base, returns)
        assertTrue("$name: the load's trace is gone", load.method.code().any {
            (it as? ReferenceInstruction)?.reference?.let { ref -> (ref as? StringReference)?.string } == SCRUBBER_LOAD
        })
        val context = PatchContexts.of(listOf(base))

        // #120: a scrubber took a reel through its load, with no hand-over and no light mount, and the hold
        // on its bar never ended.
        val loading = context.mutableClassDefBy(base.type).methods.single { it.sameAs(load.method) }
        var where = "$name: ${base.type}->${loading.name}"
        val loadCode = loading.code()
        val live = RegisterLiveness.of(load.method).liveInto(load.at)
        loading.reportsLoad(found.looks, load, another)
        val loaded = loading.code()
        assertEquals("$where: the hook's code",
            listOf(Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT,
                Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
            loaded.subList(load.at, load.at + 7).map { it.opcode })
        assertEquals("$where: the bar info is stored right before", another.info, loadCode[load.at - 1].reference())
        assertEquals("$where: the reel's id off that bar info", another.id, loaded[load.at].reference())
        assertEquals("$where: from the register stored", load.info, (loaded[load.at] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the views holder", found.looks.viewsField, loaded[load.at + 2].reference())
        assertEquals("$where: the time label", found.looks.labelField, loaded[load.at + 4].reference())
        assertEquals("$where: the extension is told", LOADS, loaded[load.at + 6].reference())
        val told = loaded[load.at + 6] as FiveRegisterInstruction
        val borrowed = listOf(told.registerC, told.registerD, told.registerE)
        assertEquals("$where: with the label, scrubber and reel", 3, told.registerCount)
        for (register in borrowed) {
            assertTrue("$where: the hook borrows v$register, which Facebook reads on", register !in live)
            assertTrue("$where: the hook writes v$register, which isn't a low local", register < minOf(loading.localRegisterCount(), 16))
        }
        assertEquals("$where: a missing views holder goes on to Facebook", setOf(load.at + 4, load.at + 7),
            ControlFlow.of(loading).normal[load.at + 3].toSet())
        assertEquals("$where: Facebook's code changed", loadCode.map { it.opcode },
            (loaded.take(load.at) + loaded.drop(load.at + 7)).map { it.opcode })

        // #120: an older scrubber unloading late cleared the default holder of the one that loaded after it.
        val unloading = context.mutableClassDefBy(base.type).methods.single { it.sameAs(clear.method) }
        where = "$name: ${base.type}->${unloading.name}"
        val unloadCode = unloading.code()
        val liveThere = RegisterLiveness.of(clear.method).liveInto(clear.clear) + RegisterLiveness.of(clear.method).liveInto(clear.store + 1)
        unloading.keepsNewerHolder(clear, load, returns)
        val unloaded = unloading.code()
        val at = clear.clear
        assertEquals("$where: the hook's code",
            listOf(Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
            unloaded.subList(at, at + 6).map { it.opcode })
        assertEquals("$where: Facebook's clear follows", Opcode.CONST_4, unloaded[at + 6].opcode)
        assertEquals("$where: what the registry holds", returns.defaultHolder, unloaded[at].reference())
        assertEquals("$where: off the registry", clear.registry, (unloaded[at] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the scrubber's own holder", load.ownHolder, unloaded[at + 2].reference())
        assertEquals("$where: the extension is asked", KEEPS_DEFAULT_HOLDER, unloaded[at + 3].reference())
        assertEquals("$where: Facebook's clear is the default holder's", returns.defaultHolder, unloaded[at + 7].reference())
        assertEquals("$where: a yes skips only the clear", setOf(at + 6, at + 8), ControlFlow.of(unloading).normal[at + 5].toSet())
        val asked = unloaded[at + 3] as FiveRegisterInstruction
        for (register in listOf(asked.registerC, asked.registerD)) {
            assertTrue("$where: the hook borrows v$register, which Facebook reads on", register !in liveThere)
        }
        assertEquals("$where: Facebook's code changed", unloadCode.map { it.opcode },
            (unloaded.take(at) + unloaded.drop(at + 6)).map { it.opcode })
        extensionHas(where, LOADS)
        extensionHas(where, KEEPS_DEFAULT_HOLDER)
    }

    @Test
    fun `each declared build reports the light mount and asks the extension for each look first`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val looks = found.looks
        val base = found.progress.base
        val holders = FixtureDex.classesHolding(bundle, NEW_REELS_UNIT_SHOWN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val another = anotherVideo(base, barInfo(base, scrubberLengthChecks(found.scrubber)?.minimum).builder.returnType, reelHandOver(holders, base.type))
        // Up to 582 the mount is the base's own, on 583 the Reels scrubber registry's hand-over runs it inline.
        val owner = if (another.mount.definingClass == base.type) base else holders.single { it.type == another.mount.definingClass }
        val context = PatchContexts.of(listOf(base, owner).distinctBy { it.type })

        val mount = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(another.mount) }
        var where = "$name: ${owner.type}->${mount.name}"
        val mountCode = mount.code()
        val start = another.start
        val live = RegisterLiveness.of(another.mount).liveInto(start)
        mount.reportsMount(looks, another)
        val mounted = mount.code()
        assertEquals("$where: the hook's code",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16,
                Opcode.INVOKE_STATIC),
            mounted.subList(start, start + 6).map { it.opcode })
        assertEquals("$where: the mount starts at its trace", SCRUBBER_LIGHT_MOUNT,
            (mountCode[start] as? ReferenceInstruction)?.reference?.let { (it as? StringReference)?.string })
        assertEquals("$where: from the scrubber", another.scrubber, (mounted[start] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the views holder", looks.viewsField, mounted[start + 1].reference())
        assertEquals("$where: the time label", looks.labelField, mounted[start + 3].reference())
        assertEquals("$where: the scrubber itself", another.scrubber, (mounted[start + 4] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the extension is told", MOUNTS, mounted[start + 5].reference())
        val told = mounted[start + 5] as FiveRegisterInstruction
        val borrowed = listOf((mounted[start + 3] as TwoRegisterInstruction).registerA, (mounted[start + 4] as TwoRegisterInstruction).registerA)
        assertEquals("$where: with the label and the scrubber", listOf(2) + borrowed, listOf(told.registerCount, told.registerC, told.registerD))
        for (register in borrowed) {
            assertTrue("$where: the hook writes v$register, which isn't a low local", register < minOf(mount.localRegisterCount(), 16))
            assertTrue("$where: the hook borrows v$register, which Facebook reads on", register !in live)
        }
        assertEquals("$where: Facebook's code stays", mountCode.map { it.opcode },
            (mounted.take(start) + mounted.drop(start + 6)).map { it.opcode })
        assertEquals("$where: a missing views holder goes on to Facebook", setOf(start + 3, start + 6),
            ControlFlow.of(mount).normal[start + 2].toSet())
        val mountFlow = ControlFlow.of(another.mount)
        assertEquals("$where: something jumps onto the mount's trace", emptySet<Int>(),
            mountCode.indices.filter { it != start - 1 && start in mountFlow.normal[it] }.toSet())

        val selector = lookSelector(base, looks)
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(selector) }
        where = "$name: ${base.type}->${selector.name}"
        val original = method.code()
        method.countsLook()
        method.asksLook(looks)
        val patched = method.code()
        val look = method.implementation!!.registerCount - 1
        assertEquals("$where: the hook's code",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_OBJECT_FROM16),
            patched.take(8).map { it.opcode })
        assertEquals("$where: the look is read", look, (patched[4] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the extension is asked", LOOK_FOR, patched[5].reference())
        val ask = patched[5] as FiveRegisterInstruction
        assertEquals("$where: with the label and the look", listOf(2, (patched[3] as TwoRegisterInstruction).registerA,
            (patched[4] as TwoRegisterInstruction).registerA), listOf(ask.registerCount, ask.registerC, ask.registerD))
        assertEquals("$where: its answer replaces the look", look, (patched[7] as TwoRegisterInstruction).registerA)
        assertEquals("$where: then the look is counted", COUNTS_LOOK, patched[8].reference())
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(9).map { it.opcode })
        assertEquals("$where: a missing views holder goes on with the look as asked", setOf(3, 8),
            ControlFlow.of(method).normal[2].toSet())
        // The selector returns on a null look before anything else, so a dropped look changes nothing of Facebook's.
        val nullCheck = original.indexOfFirst { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == look }
        assertTrue("$where no longer tests the look for null first", nullCheck in 0..2 &&
            original.take(nullCheck).none { it.opcode.name.startsWith("invoke") || it.opcode.name.startsWith("if-") })
        val onNull = ControlFlow.of(selector).normal[nullCheck].single { it != nullCheck + 1 }
        assertEquals("$where no longer returns on a null look", Opcode.RETURN_VOID, original[onNull].opcode)
        extensionHas(where, MOUNTS)
        extensionHas(where, LOOK_FOR)
    }

    /**
     * #120: the update stops for good when it can't draw a reel yet, and after a fast double swipe
     * nothing of Facebook's may start it again, so the extension runs it again until the reel draws.
     * The stub it calls runs the base's own progress update, and that update moves the bar and
     * schedules its next run once it finds the reel's position and length.
     */
    @Test
    fun `each declared build lets the extension run the scrubber's progress update again`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val progress = found.progress
        val base = progress.base
        val where = "$name: ${base.type}->${progress.update.name}"
        checkUpdateAgain(progress)
        val update = progress.update.code()
        assertTrue("$where no longer schedules its next run", update.any {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_STATIC && call?.definingClass == base.type && call.returnType == "V" &&
                call.parameterTypes.map(CharSequence::toString) == listOf(base.type, "J")
        })

        val context = PatchContexts.of(listOf(base, ExtensionDex.classDef(PROGRESS_BAR)))
        context.fillUpdateAgain(progress)
        val stub = context.mutableClassDefBy(PROGRESS_BAR).methods.single(::isUpdateAgainStub).code()
        assertEquals("$where: the stub's code", listOf(Opcode.CHECK_CAST, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID),
            stub.map { it.opcode })
        assertEquals("$where: the scrubber is cast to the base", base.type, stub[0].reference())
        assertEquals("$where: the stub runs the update", "${base.type}->${progress.update.name}(${base.type})V", stub[1].reference())
        val call = stub[1] as FiveRegisterInstruction
        assertEquals("$where: on the scrubber it's handed", listOf(1, (stub[0] as OneRegisterInstruction).registerA),
            listOf(call.registerCount, call.registerC))
        extensionHas(where, "$PROGRESS_BAR->$UPDATE_AGAIN_STUB(Ljava/lang/Object;)V")
    }

    /**
     * #120: a press stops the scrubber's update, and on a bar that takes a touch anywhere only a seek
     * starts it again, so a tap in the kept look froze the bar and its time. The bar's release tells the
     * extension first thing, with the scrubber and the bar, so it can run the update again.
     */
    @Test
    fun `each declared build tells the extension when the bar is let go`() = bundles { bundle ->
        val name = bundle.name
        val base = scrubber(bundle).progress.base
        val listeners = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(listeners) { SEEK_BAR_LISTENER in it.interfaces } }
        val release = barRelease(listeners, base.type)
        val owner = release.method.definingClass
        val where = "$name: $owner->${release.method.name}"
        val original = release.method.code()
        assertTrue("$where no longer reads the scrubber", original.any { it.opcode == Opcode.IGET_OBJECT && it.reference() == release.scrubber })
        assertTrue("$where no longer restarts the update from its seek", original.any {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_STATIC && call?.definingClass == base.type && call.returnType == "V" &&
                call.parameterTypes.map(CharSequence::toString) == listOf(base.type, "J")
        })

        val context = PatchContexts.of(listeners.filter { it.type == owner })
        val method = context.mutableClassDefBy(owner).methods.single { it.sameAs(release.method) }
        method.reportsBarRelease(release)
        val patched = method.code()
        assertEquals("$where: the hook's code", listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
            patched.take(4).map { it.opcode })
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(4).map { it.opcode })
        val parameters = method.implementation!!.registerCount - 2
        val scrubberRead = patched[0] as TwoRegisterInstruction
        assertEquals("$where: the scrubber is read from p0", parameters, scrubberRead.registerB)
        assertEquals("$where: through the listener's field", release.scrubber, patched[1].reference())
        val read = patched[1] as TwoRegisterInstruction
        assertEquals("$where: on the copy of p0", listOf(scrubberRead.registerA, scrubberRead.registerA), listOf(read.registerA, read.registerB))
        val barRead = patched[2] as TwoRegisterInstruction
        assertEquals("$where: the bar is read from p1", parameters + 1, barRead.registerB)
        assertEquals("$where: the extension hears it", BAR_RELEASED, patched[3].reference())
        val call = patched[3] as FiveRegisterInstruction
        assertEquals("$where: with the scrubber and the bar", listOf(2, read.registerA, barRead.registerA),
            listOf(call.registerCount, call.registerC, call.registerD))
        assertTrue("$where: the hook writes past the locals", maxOf(read.registerA, barRead.registerA) < method.localRegisterCount())
        extensionHas(where, BAR_RELEASED)
    }

    /**
     * #120: a refresh's hand-over can land between the scrubber's unload and its next load, and the load
     * takes no player. The extension keeps the hand-over's arguments and runs it again after the load, with
     * the bar info taken away for the call, since the registry skips a reel the info already holds.
     */
    @Test
    fun `each declared build lets the extension run the registry's hand-over again`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val base = found.progress.base
        val holders = FixtureDex.classesHolding(bundle, NEW_REELS_UNIT_SHOWN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val handOver = reelHandOver(holders, base.type)
        val returns = registryReturns(handOver)
        val another = anotherVideo(base, barInfo(base, scrubberLengthChecks(found.scrubber)?.minimum).builder.returnType, handOver)
        val owner = holders.single { it.type == handOver.method.definingClass }
        val where = "$name: ${owner.type}->${handOver.method.name}"
        val named = listOf(owner.type, another.info.substringBefore("->"), another.info.substringAfter(":")) +
            handOver.method.parameterTypes.map(CharSequence::toString)
        val classes = FixtureDex.classes(bundle, named.toSet())
        checkHandOverAgain(handOver, returns, another) { classes[it] }
        // The way back to null bar info: the registry reads the info, and takes none as another reel.
        val code = handOver.method.code()
        val read = code.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && it.reference() == another.info }
        assertTrue("$where no longer reads the scrubber's bar info", read >= 0)
        assertEquals("$where no longer tests for no bar info", Opcode.IF_EQZ, code.drop(read + 1).first { it.opcode in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ) }.opcode)

        val context = PatchContexts.of(listOf(ExtensionDex.classDef(PROGRESS_BAR)))
        context.fillHandOverAgain(handOver, another)
        val methods = context.mutableClassDefBy(PROGRESS_BAR).methods
        val again = methods.single(::isHandOverAgainStub).code()
        val types = listOf(owner.type) + handOver.method.parameterTypes.map(CharSequence::toString)
        assertEquals("$where: the stub's code", List(HAND_OVER_PARAMETERS) { Opcode.CHECK_CAST } + listOf(Opcode.INVOKE_VIRTUAL_RANGE, Opcode.RETURN_VOID),
            again.map { it.opcode })
        assertEquals("$where: each argument cast to its type, the int aside", types.dropLast(1), again.take(HAND_OVER_PARAMETERS).map { it.reference() })
        assertEquals("$where: in its own register", (0 until HAND_OVER_PARAMETERS).toList(),
            again.take(HAND_OVER_PARAMETERS).map { (it as OneRegisterInstruction).registerA })
        assertEquals("$where: the stub runs the hand-over", "${owner.type}->${handOver.method.name}(${types.drop(1).joinToString("")})V", again[HAND_OVER_PARAMETERS].reference())
        val call = again[HAND_OVER_PARAMETERS] as RegisterRangeInstruction
        assertEquals("$where: on every argument it's handed", listOf(HAND_OVER_PARAMETERS + 1, 0), listOf(call.registerCount, call.startRegister))

        val held = methods.single(::isHeldBarInfoStub).code()
        assertEquals("$where: the read's code", listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), held.take(3).map { it.opcode })
        assertEquals("$where: the read's field", another.info, held[1].reference())
        assertEquals("$where: the read answers what it read", (held[1] as OneRegisterInstruction).registerA, (held[2] as OneRegisterInstruction).registerA)
        val set = methods.single(::isSetBarInfoStub).code()
        assertEquals("$where: the write's code", listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID), set.map { it.opcode })
        assertEquals("$where: the write's field", another.info, set[2].reference())
        extensionHas(where, "$PROGRESS_BAR->$HAND_OVER_AGAIN_STUB(${MISSES_HAND_OVER.substringAfter("(")}")
        extensionHas(where, "$PROGRESS_BAR->$HELD_BAR_INFO_STUB(Ljava/lang/Object;)Ljava/lang/Object;")
        extensionHas(where, "$PROGRESS_BAR->$SET_BAR_INFO_STUB(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
}
