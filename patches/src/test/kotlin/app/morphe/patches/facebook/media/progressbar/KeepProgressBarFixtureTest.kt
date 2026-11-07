/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.liveAcrossInjection
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the progress bar on every Facebook build the bundle declares: the Reels viewer's bottom
 * progress bar plugin found by the name it gives itself, its shrinking and full-size methods told
 * apart by the 255 alpha only the full-size one loads, and the full-screen controls' fade timer, the
 * one method of FeedFullscreenVideoControlsPlugin's superclass that sets a delayed message. Then the
 * two hooks on those methods: the extension asked first, a yes making the bar full size with the
 * same two arguments or setting no timer, a no landing on Facebook's first instruction, and nothing
 * of Facebook's code moved. The newer player's controls extension gets the same no-timer hook on its
 * one method that posts the hide, and so does the run() of the hide runnable that the landscape
 * player's controller posts with a delay, a runnable whose hide reaches the controls updater.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class KeepProgressBarFixtureTest {
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

    /**
     * Whether a hook at [at] in [method] that borrows [count] registers may write [register]: one
     * nothing reads from there on, v15 or below, and a parameter only once the free locals ran out.
     */
    private fun borrowable(method: Method, at: Int, count: Int, register: Int): Boolean {
        val live = method.liveAcrossInjection(at)
        val locals = method.localRegisterCount()
        val freeLocals = (0 until minOf(locals, 16)).count { it !in live }
        return register <= 15 && register !in live && (register < locals || freeLocals < count)
    }

    /**
     * The chapter reset on [bundle]'s build, found as the patch finds it, through the classes
     * extending [scrubber] and the classes of their fields, and the class it's a method of.
     */
    private fun chapters(bundle: File, scrubber: ClassDef, looks: ScrubberLooks): Pair<ChapterReset, ClassDef> {
        val subclasses = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filter { it.superclass == scrubber.type }.mapTo(subclasses) { ImmutableClassDef.of(it) }
        }
        // 585's Android 9 build keeps the reset on a class the scrubber's own field holds.
        val held = FixtureDex.classes(bundle, (subclasses + scrubber).flatMap { it.fields.map { field -> field.type } }.toSet())
        val reset = chapterReset(scrubber, looks, subclasses) { held[it] }
        return reset to (held[reset.method.definingClass] ?: scrubber)
    }

    /**
     * The scrubber's light mount on [bundle]'s build, found as the patch finds it, and the class it's
     * in: [base] up to 582, the Reels scrubber registry on 583, whose hand-over runs it inline.
     */
    private fun lightMount(bundle: File, base: ClassDef, barInfo: String): Pair<AnotherVideo, ClassDef> {
        val holders = FixtureDex.classesHolding(bundle, NEW_REELS_UNIT_SHOWN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val another = anotherVideo(base, barInfo, reelHandOver(holders, base.type))
        val owner = if (another.mount.definingClass == base.type) base else holders.single { it.type == another.mount.definingClass }
        return another to owner
    }

    @Test
    fun `each declared build keeps the reel's bar full size through its own full-size method`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, REEL_SEEK_BAR_PLUGIN).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        assertTrue("$name: nothing loads \"$REEL_SEEK_BAR_PLUGIN\"", holders.isNotEmpty())
        val plugin = reelSeekBarPlugin(holders)
        val sizes = barSizes(plugin)
        assertTrue("$name: the full-size method doesn't load 255",
            sizes.fullSize.code().any { (it as? WideLiteralInstruction)?.wideLiteral == 255L })
        assertFalse("$name: the shrinking method loads 255",
            sizes.shrink.code().any { (it as? WideLiteralInstruction)?.wideLiteral == 255L })
        // The hook borrows v0: the SeekBar and the plugin are the two parameter registers.
        assertTrue("$name: ${plugin.type}->${sizes.shrink.name} has no local register for the hook",
            sizes.shrink.implementation!!.registerCount - 2 >= 1)

        val context = PatchContexts.of(listOf(plugin))
        val shrink = context.mutableClassDefBy(plugin.type).methods.single { it.sameAs(sizes.shrink) }
        val original = shrink.code()
        shrink.fullSizeInstead(plugin.type, sizes.fullSize)
        val patched = shrink.code()
        val where = "$name: ${plugin.type}->${sizes.shrink.name}"
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", KEEPS_REEL_BAR, patched[0].reference())
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < shrink.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        val call = patched[3] as FiveRegisterInstruction
        assertEquals("$where: a yes makes the bar full size", "${plugin.type}->${sizes.fullSize.name}($SEEK_BAR${plugin.type})V",
            patched[3].reference())
        assertEquals("$where: with the same SeekBar and plugin", listOf(shrink.localRegisterCount(), shrink.localRegisterCount() + 1),
            listOf(call.registerC, call.registerD))
        assertEquals("$where: and returns", Opcode.RETURN_VOID, patched[4].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(shrink).normal[2].toSet())
    }

    @Test
    fun `each declared build runs the scrubber's active look in place of its passive one while kept`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        assertTrue("$name: nothing traces \"$SCRUBBER_PASSIVE\"", holders.isNotEmpty())
        val looks = vddScrubber(holders)
        assertNotEquals("$name: the active and passive looks are one method", looks.active.name, looks.passive.name)
        assertTrue("$name: the active look doesn't load 255 for the thumb",
            looks.active.code().any { (it as? WideLiteralInstruction)?.wideLiteral == 255L })
        assertTrue("$name: the passive look doesn't set the bar's max height",
            // 585's Android 9 build calls it through SeekBar.
            looks.passive.code().any { it.reference() in setOf("Landroid/widget/ProgressBar;->setMaxHeight(I)V", "Landroid/widget/SeekBar;->setMaxHeight(I)V") })
        val registers = looks.passive.implementation!!.registerCount
        // The hook borrows v0 and v1: p0 is the one parameter register.
        assertTrue("$name: ${looks.type}->${looks.passive.name} has no two local registers for the hook", registers - 1 >= 2)
        // The active look runs Facebook's time writer, which moves the bar: why the hook puts it back (#120).
        val scrubber = holders.single { it.type == looks.type }
        val base = FixtureDex.classes(bundle, setOf(scrubberBase(looks))).values.single()
        val writers = looks.active.code().mapNotNull {
            (it as? ReferenceInstruction)?.takeIf { call -> call.opcode == Opcode.INVOKE_STATIC }?.reference as? MethodReference
        }.filter { it.definingClass == base.type }
        assertTrue("$name: the active look runs nothing of ${base.type} that moves the bar", writers.any { call ->
            base.methods.any { it.name == call.name && it.implementation != null && it.code().any { i -> i.reference() == SET_PROGRESS } }
        })
        assertEquals("$name: the bar is a SeekBar of the views holder", listOf(SEEK_BAR, looks.labelField.substringBefore("->")),
            listOf(looks.barField.substringAfter(":"), looks.barField.substringBefore("->")))

        val graph = graphHide(looks) { type -> FixtureDex.classes(bundle, setOf(type))[type] }

        val context = PatchContexts.of(listOf(scrubber))
        val passive = context.mutableClassDefBy(looks.type).methods.single { it.sameAs(looks.passive) }
        val original = passive.code()
        passive.activeInstead(looks, graph)
        val patched = passive.code()
        val where = "$name: ${looks.type}->${looks.passive.name}"
        assertEquals("$where gains twenty-two instructions", original.size + 22, patched.size)
        assertEquals("$where: the extension is asked first", KEEPS_REEL_BAR, patched[0].reference())
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < passive.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        val local = passive.localRegisterCount()
        for (at in listOf(3, 10, 14)) {
            assertEquals("$where: the instance moves into a local through the wide form ($at)", Opcode.MOVE_OBJECT_FROM16, patched[at].opcode)
            val move = patched[at] as TwoRegisterInstruction
            assertEquals("$where: from p0 into the hook's local ($at)", listOf(register, local), listOf(move.registerA, move.registerB))
            assertEquals("$where: the views holder is read off the scrubber ($at)", looks.viewsField, patched[at + 1].reference())
            for (index in at + 1..at + 2) {
                val read = patched[index] as TwoRegisterInstruction
                assertEquals("$where: read $index stays in the local", listOf(register, register), listOf(read.registerA, read.registerB))
            }
        }
        assertEquals("$where: then the bar out of it", looks.barField, patched[5].reference())
        assertEquals("$where: its position is read", GET_PROGRESS, patched[6].reference())
        assertEquals("$where: from the bar", listOf(register), listOf((patched[6] as FiveRegisterInstruction).registerC))
        assertEquals("$where: and kept", Opcode.MOVE_RESULT, patched[7].opcode)
        val position = (patched[7] as OneRegisterInstruction).registerA
        assertTrue("$where: the position goes in v$position, which isn't a local", position < local)
        assertNotEquals("$where: the position doesn't overwrite the bar", register, position)
        assertEquals("$where: a yes runs the active look", "${looks.type}->${looks.active.name}()V", patched[8].reference())
        assertEquals("$where: through the range form, which reaches any register", Opcode.INVOKE_VIRTUAL_RANGE, patched[8].opcode)
        val call = patched[8] as RegisterRangeInstruction
        assertEquals("$where: on this instance only", listOf(local, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: then the bar goes back where it was", SET_PROGRESS, patched[9].reference())
        val back = patched[9] as FiveRegisterInstruction
        assertEquals("$where: the same bar and position", listOf(2, register, position), listOf(back.registerCount, back.registerC, back.registerD))
        assertEquals("$where: then the time label out of the views holder", looks.labelField, patched[12].reference())
        assertEquals("$where: the label is a ViewGroup of the views holder", VIEW_GROUP,
            (patched[12] as ReferenceInstruction).reference.let { (it as FieldReference).type })
        assertEquals("$where: the label goes to the extension", HIDE_TIME_LABEL, patched[13].reference())
        assertEquals("$where: with the local", listOf(register), listOf((patched[13] as FiveRegisterInstruction).registerC))
        assertEquals("$where: then the graph out of the views holder", graph.graphField, patched[16].reference())
        assertEquals("$where: a missing graph is skipped", Opcode.IF_EQZ, patched[17].opcode)
        assertEquals("$where: the graph's register is checked", register, (patched[17] as OneRegisterInstruction).registerA)
        assertEquals("$where: past the hide to the return", setOf(18, 21), ControlFlow.of(passive).normal[17].toSet())
        assertEquals("$where: the flag's value is a true", 1L, (patched[18] as WideLiteralInstruction).wideLiteral)
        val hidden = (patched[18] as OneRegisterInstruction).registerA
        assertTrue("$where: the true goes in v$hidden, which isn't a local", hidden < local)
        assertNotEquals("$where: the true doesn't overwrite the graph", register, hidden)
        assertEquals("$where: stored in the graph's hidden flag", Opcode.IPUT_BOOLEAN, patched[19].opcode)
        assertEquals("$where: its own flag", graph.hiddenField, patched[19].reference())
        val store = patched[19] as TwoRegisterInstruction
        assertEquals("$where: the true, on the graph", listOf(hidden, register), listOf(store.registerA, store.registerB))
        assertEquals("$where: then the graph is redrawn", INVALIDATE, patched[20].reference())
        assertEquals("$where: the graph itself", listOf(1, register),
            (patched[20] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC) })
        assertEquals("$where: and returns", Opcode.RETURN_VOID, patched[21].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(22).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 22), ControlFlow.of(passive).normal[2].toSet())
    }

    @Test
    fun `each declared build clears the last reel's graph and chapter marks as the scrubber takes another video`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val looks = vddScrubber(holders)
        val scrubber = holders.single { it.type == looks.type }
        val graph = graphHide(looks) { type -> FixtureDex.classes(bundle, setOf(type))[type] }
        val (chapters, resets) = chapters(bundle, scrubber, looks)
        // Facebook runs the reset itself from its chapter callback, for a reel with no marks.
        assertTrue("$name: nothing of ${resets.type} runs ${chapters.call}", resets.methods.any { method ->
            method.name != chapters.method.name && method.implementation?.instructions?.any {
                it.opcode == Opcode.INVOKE_STATIC && it.reference() == chapters.call
            } == true
        })
        val base = FixtureDex.classes(bundle, setOf(scrubberBase(looks))).values.single()
        val info = barInfo(base, scrubberLengthChecks(lengthCheckOwner(scrubber, base))?.minimum)
        val (another, owner) = lightMount(bundle, base, info.builder.returnType)
        val mountCode = another.mount.code()
        // A match skips the reset of the bar's state, and both videos get the bar info's rebuild: 582 runs
        // it where a match lands, 583 between the compare and its test.
        val skip = ControlFlow.of(another.mount).normal[another.at - 1].single { it != another.at }
        val rebuilds = mountCode.indices.filter { mountCode[it].reference()?.startsWith("${base.type}->${info.builder.name}(") == true }
        assertTrue("$name: the same video no longer gets the bar info's rebuild", rebuilds.any { it in skip until skip + 4 || it in another.start until another.at })

        val context = PatchContexts.of(listOf(owner))
        val mount = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(another.mount) }
        val original = mount.code()
        val at = another.at
        mount.clearsLastReel(looks, graph, chapters, another)
        val patched = mount.code()
        val where = "$name: ${owner.type}->${another.mount.name}"
        // 584 reads the reset's helper off the scrubber and skips a missing one: two more instructions.
        val extra = if (chapters.field == null) 0 else 2
        val end = at + 15 + extra
        assertEquals("$where gains ${15 + extra} instructions", original.size + 15 + extra, patched.size)
        assertEquals("$where: nothing before the branch moves", original.take(at).map { it.opcode }, patched.take(at).map { it.opcode })
        assertEquals("$where: the extension is asked first", CLEARS_LAST_REEL, patched[at].reference())
        val register = (patched[at + 1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't free there", borrowable(another.mount, at, 2, register))
        val flow = ControlFlow.of(mount)
        assertEquals("$where: a no goes straight on to Facebook", setOf(at + 3, end), flow.normal[at + 2].toSet())
        for (move in listOf(at + 3, at + 8 + extra)) {
            assertEquals("$where: the scrubber moves into the local ($move)", listOf(register, another.scrubber),
                (patched[move] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        }
        assertEquals("$where: only a scrubber of ${chapters.owner}", Opcode.INSTANCE_OF, patched[at + 4].opcode)
        assertEquals("$where: the class checked", chapters.owner, patched[at + 4].reference())
        val flag = (patched[at + 4] as TwoRegisterInstruction).registerA
        assertTrue("$where: the check goes in v$flag, a free register apart from the instance",
            borrowable(another.mount, at, 2, flag) && flag != register)
        assertEquals("$where: another class goes on to Facebook", setOf(at + 6, end), flow.normal[at + 5].toSet())
        assertEquals("$where: cast for the reset", chapters.owner, patched[at + 6].reference())
        if (chapters.field != null) {
            assertEquals("$where: the helper the scrubber holds", chapters.field, patched[at + 7].reference())
            assertEquals("$where: read into the local", listOf(register, register),
                (patched[at + 7] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
            assertEquals("$where: a missing helper goes on to Facebook", setOf(at + 9, end), flow.normal[at + 8].toSet())
        }
        val reset = at + 7 + extra
        assertEquals("$where: a yes runs Facebook's reset of the marks", chapters.call, patched[reset].reference())
        assertEquals("$where: on this scrubber's marks", listOf(1, register),
            (patched[reset] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC) })
        assertEquals("$where: the views holder", looks.viewsField, patched[reset + 2].reference())
        assertEquals("$where: then the graph", graph.graphField, patched[reset + 3].reference())
        assertEquals("$where: a missing graph goes on to Facebook", setOf(reset + 5, end), flow.normal[reset + 4].toSet())
        assertEquals("$where: the flag's value is a true", 1L, (patched[reset + 5] as WideLiteralInstruction).wideLiteral)
        assertEquals("$where: in the check's local", flag, (patched[reset + 5] as OneRegisterInstruction).registerA)
        assertEquals("$where: stored in the graph's hidden flag", graph.hiddenField, patched[reset + 6].reference())
        assertEquals("$where: the true, on the graph", listOf(flag, register),
            (patched[reset + 6] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$where: then the graph is redrawn", INVALIDATE, patched[reset + 7].reference())
        assertEquals("$where: and the bar is reset as always", setOf(end), flow.normal[reset + 7].toSet())
        assertEquals("$where: Facebook's code stays", original.drop(at).map { it.opcode }, patched.drop(end).map { it.opcode })
    }

    @Test
    fun `each declared build starts the next reel's bar and time over as the scrubber takes another video`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val looks = vddScrubber(holders)
        val scrubber = holders.single { it.type == looks.type }
        val graph = graphHide(looks) { type -> FixtureDex.classes(bundle, setOf(type))[type] }
        val (chapters, _) = chapters(bundle, scrubber, looks)
        val base = FixtureDex.classes(bundle, setOf(scrubberBase(looks))).values.single()
        val (another, owner) = lightMount(bundle, base, barInfo(base, scrubberLengthChecks(lengthCheckOwner(scrubber, base))?.minimum).builder.returnType)
        assertTrue("$name: the views holder isn't ${base.type}'s, so the base can't read it", looks.viewsField.startsWith("${base.type}->"))

        val context = PatchContexts.of(listOf(owner))
        val mount = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(another.mount) }
        val original = mount.code()
        val at = another.at
        // In the patch's order: the start over goes in first, so the clear lands in front of it.
        mount.startsNextReelOver(looks, another)
        mount.clearsLastReel(looks, graph, chapters, another)
        val patched = mount.code()
        val where = "$name: ${owner.type}->${another.mount.name}"
        val extra = if (chapters.field == null) 0 else 2
        val start = at + 15 + extra
        assertEquals("$where gains six instructions after the clear's", original.size + 21 + extra, patched.size)
        assertEquals("$where: the clear comes first", CLEARS_LAST_REEL, patched[at].reference())
        val flow = ControlFlow.of(mount)
        assertEquals("$where: a no to the clear still starts the reel over", setOf(at + 3, start), flow.normal[at + 2].toSet())
        assertEquals("$where: another scrubber class still starts the reel over", setOf(at + 6, start), flow.normal[at + 5].toSet())
        if (extra > 0) assertEquals("$where: a missing helper still starts the reel over", setOf(at + 9, start), flow.normal[at + 8].toSet())
        assertEquals("$where: a missing graph still starts the reel over", setOf(at + 12 + extra, start), flow.normal[at + 11 + extra].toSet())
        assertEquals("$where: a cleared reel goes on to start over", setOf(start), flow.normal[at + 14 + extra].toSet())

        val holder = (patched[start] as TwoRegisterInstruction).let {
            assertEquals("$where: the scrubber moves into a local", another.scrubber, it.registerB)
            it.registerA
        }
        assertTrue("$where: the start over writes v$holder, which isn't free there", borrowable(another.mount, at, 2, holder))
        assertEquals("$where: the views holder", looks.viewsField, patched[start + 1].reference())
        assertEquals("$where: a views holder not there yet goes on to Facebook", setOf(start + 3, start + 6),
            flow.normal[start + 2].toSet())
        assertEquals("$where: the bar", looks.barField, patched[start + 3].reference())
        val bar = (patched[start + 3] as TwoRegisterInstruction).registerA
        assertTrue("$where: the bar goes in v$bar, a free register apart from the holder",
            borrowable(another.mount, at, 2, bar) && bar != holder)
        assertEquals("$where: the time label", looks.labelField, patched[start + 4].reference())
        assertEquals("$where: the extension takes both", NEXT_REEL, patched[start + 5].reference())
        assertEquals("$where: the label, then the bar", listOf(2, holder, bar),
            (patched[start + 5] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC, it.registerD) })
        assertEquals("$where: then the bar info is reset as always", setOf(start + 6), flow.normal[start + 5].toSet())
        assertEquals("$where: Facebook's code stays", original.drop(at).map { it.opcode }, patched.drop(start + 6).map { it.opcode })
    }

    @Test
    fun `each declared build hides the most replayed graph the way the passive look does`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val looks = vddScrubber(holders)
        val classOf = { type: String -> FixtureDex.classes(bundle, setOf(type))[type] }
        val graph = graphHide(looks, classOf)
        val owner = graph.hiddenField.substringBefore("->")
        assertEquals("$name: the graph is a field of the views holder", looks.labelField.substringBefore("->"),
            graph.graphField.substringBefore("->"))
        assertEquals("$name: its hidden flag is a boolean", "Z", graph.hiddenField.substringAfter(":"))
        assertTrue("$name: the graph doesn't build on $owner, which holds the flag",
            extendsType(graph.graphField.substringAfter(":"), owner) { classOf(it)?.superclass })
        assertTrue("$name: $owner isn't a View", extendsType(owner, "Landroid/view/View;") { classOf(it)?.superclass })
        assertTrue("$name: $owner draws on with its flag set", skipsDrawing(classOf(owner), graph.hiddenField))
        // The active look clears the flag to show the graph, so the kept bar sets it again after running that look.
        assertTrue("$name: the active look doesn't set the graph's flag",
            looks.active.code().any { it.opcode == Opcode.IPUT_BOOLEAN && it.reference() == graph.hiddenField })
        // The chapter marks' flag, also set before a redraw, isn't one their onDraw returns on.
        val others = looks.passive.code().filter {
            it.opcode == Opcode.IPUT_BOOLEAN && it.reference() != graph.hiddenField &&
                classOf(it.reference()!!.substringBefore("->"))?.let { view -> skipsDrawing(view, it.reference()!!) } == true
        }
        assertTrue("$name: another flag of the passive look stops a view drawing", others.isEmpty())
    }

    @Test
    fun `each declared build hides the time label where the passive look does`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val looks = vddScrubber(holders)
        // The passive look sets the label INVISIBLE (4) through the same fields the hook reads.
        val code = looks.passive.code()
        val read = code.indexOfFirst { it.reference() == looks.labelField }
        assertTrue("$name: the passive look doesn't read ${looks.labelField}", read > 0)
        assertEquals("$name: the views holder is read first", looks.viewsField, code[read - 1].reference())
        assertTrue("$name: the passive look doesn't make the label invisible",
            code.drop(read).take(8).any { (it as? WideLiteralInstruction)?.wideLiteral == 4L })
        // The active look reads the same label field, to show it.
        assertTrue("$name: the active look doesn't read ${looks.labelField}",
            looks.active.code().any { it.reference() == looks.labelField })
    }

    @Test
    fun `each declared build sets no fade timer for full-screen controls while kept`() = bundles { bundle ->
        val name = bundle.name
        val subclass = FixtureDex.classes(bundle, setOf(FULLSCREEN_CONTROLS)).values.single()
        val controls = FixtureDex.classes(bundle, setOf(subclass.superclass!!)).values.single()
        assertNotEquals("$name: the full-screen controls extend nothing of Facebook's", "Ljava/lang/Object;", controls.type)
        val timer = fadeTimer(controls)
        assertTrue("$name: ${controls.type}->${timer.name} doesn't set the delayed message",
            timer.code().any { it.reference() == SEND_DELAYED })
        // The hook borrows v0: this is the one parameter register.
        assertTrue("$name: ${controls.type}->${timer.name} has no local register for the hook",
            timer.implementation!!.registerCount - 1 >= 1)

        val context = PatchContexts.of(listOf(controls))
        val method = context.mutableClassDefBy(controls.type).methods.single { it.sameAs(timer) }
        val original = method.code()
        method.noTimerWhileKept()
        val patched = method.code()
        val where = "$name: ${controls.type}->${timer.name}"
        assertEquals("$where gains four instructions", original.size + 4, patched.size)
        assertEquals("$where: the extension is asked first", KEEPS_CONTROLS, patched[0].reference())
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes sets no timer", Opcode.RETURN_VOID, patched[3].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(4).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 4), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build posts no hide for the newer player's controls while kept`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, CONTROLS_EXTENSION).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val (extension, hide) = extensionFadeTimer(holders)
        val where = "$name: ${extension.type}->${hide.name}"
        assertTrue("$where posts no 3 second hide", hide.code().any { (it as? WideLiteralInstruction)?.wideLiteral == 3000L })
        // The hook borrows v0, so the method needs a register below its parameters.
        assertTrue("$where has no local register for the hook",
            hide.implementation!!.registerCount - hide.parameterTypes.size - 1 >= 1)

        val context = PatchContexts.of(listOf(extension))
        val method = context.mutableClassDefBy(extension.type).methods.single { it.sameAs(hide) }
        val original = method.code()
        method.noTimerWhileKept()
        val patched = method.code()
        assertEquals("$where gains four instructions", original.size + 4, patched.size)
        assertEquals("$where: the extension is asked first", KEEPS_CONTROLS, patched[0].reference())
        assertEquals("$where: a yes posts no hide", Opcode.RETURN_VOID, patched[3].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(4).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 4), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build runs no hide the landscape player's controller posted while kept`() = bundles { bundle ->
        val name = bundle.name
        fun classOf(type: String) = FixtureDex.classes(bundle, setOf(type)).values.singleOrNull()
        val holders = FixtureDex.classesHolding(bundle, PLAYER_CONTROL_UPDATE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val updater = controlsUpdater(holders, ::classOf)
        val controllers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) if (takesUpdater(classDef, updater)) controllers += ImmutableClassDef.of(classDef)
        }
        val (runnable, run) = controlsHideRunnable(controllers, ::classOf)
        val where = "$name: ${runnable.type}->run"
        // The hide the runnable asks for has to reach the updater, which hands the player the hide: through its
        // interface, or on 583 the static method of the controller the updater is folded into.
        val asked = run.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { call -> controllers.any { it.type == call.definingClass } }
        assertEquals("$where asks its controller for more than the hide", 1, asked.size)
        val hide = controllers.single { it.type == asked.single().definingClass }.methods.single { it.name == asked.single().name }
        assertTrue("$where: ${hide.name} doesn't hand the updater the hide",
            hide.code().any { it.opcode == (if (updater.folded) Opcode.INVOKE_STATIC else if (updater.virtual) Opcode.INVOKE_VIRTUAL else Opcode.INVOKE_INTERFACE) &&
                it.reference() == updater.update })
        assertTrue("$where has no local register for the hook", run.implementation!!.registerCount - 1 >= 1)

        val context = PatchContexts.of(listOf(runnable))
        val method = context.mutableClassDefBy(runnable.type).methods.single { it.sameAs(run) }
        val original = method.code()
        method.noTimerWhileKept()
        val patched = method.code()
        assertEquals("$where gains four instructions", original.size + 4, patched.size)
        assertEquals("$where: the extension is asked first", KEEPS_CONTROLS, patched[0].reference())
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a yes hides nothing", Opcode.RETURN_VOID, patched[3].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(4).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 4), ControlFlow.of(method).normal[2].toSet())
    }
}
