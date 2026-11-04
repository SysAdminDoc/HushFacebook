/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the video comment box in the Shorts viewer a feed video opens in (#375), on every Facebook
 * build the bundle declares (arm64 and armv7): exactly one method hands back a comment bar it just
 * built, and after the hook it asks the extension right before that return and hands back null on a
 * yes, with Facebook's return the only place a no lands. Its one caller, the root, tells the
 * extension first thing that a render starts, hands it the bar its pick got back and the viewer's
 * config right after the pick, and reads one band height (the static that adds 44 dp) twice for the
 * seek bar; after the hook each read hands the extension its dp, and only the read reaches that hook.
 * The config is loaded once, on every path before the pick. The one caller of vddVideoSlotHeightPx,
 * the viewer's list root, reads the same band height once, the dp it gets is what reaches the height
 * function's band, and after the hook that read hands the extension its dp and the config. The height
 * function itself stays Facebook's, and so does the code around each hook. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class VideoCommentBandFixtureTest {
    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.params() = parameterTypes.map(CharSequence::toString)

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** The registers an invoke is handed, in order. */
    private fun Instruction.handedAll(): List<Int> = (this as? FiveRegisterInstruction)?.let { call ->
        listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    } ?: (this as RegisterRangeInstruction).let { call -> (call.startRegister until call.startRegister + call.registerCount).toList() }

    /** The one register a band height read is handed. */
    private fun Instruction.handed() = handedAll().also { assertEquals(1, it.size) }.single()

    /** Opcodes with a goto at any width and no padding nops, as the patcher may widen one or pad a payload. */
    private fun List<Instruction>.opcodes() = map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
    }.filter { it != Opcode.NOP }

    /** The last instruction of [code] before [at] that writes [register]. */
    private fun lastWrite(code: List<Instruction>, register: Int, at: Int) = (at - 1 downTo 0).first { index ->
        code[index].opcode.setsRegister() && (code[index] as? OneRegisterInstruction)?.registerA == register
    }

    /**
     * The comment bars, the classes that build one, and the classes with the viewer's height function
     * or a call of it in [bundle].
     */
    private fun holders(bundle: File, bars: Set<String>): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val heights = dex.stringSection.any { it == VDD_SLOT_HEIGHT }
            if (!heights && dex.typeSection.none { it in bars }) return@forEach
            dex.classes.filterTo(found) { classDef ->
                !classDef.type.startsWith(EXTENSION_CLASSES) && (classDef.type in bars || classDef.methods.any {
                    it.name == VDD_SLOT_HEIGHT || videoSlotCalls(it).isNotEmpty() || videoCommentBarReturns(it, bars).isNotEmpty()
                })
            }
        }
        return found.map(ImmutableClassDef::of)
    }

    /** The classes [method] calls a static of. */
    private fun staticCallees(method: Method) = method.body().filter {
        it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE
    }.mapNotNull { ((it as ReferenceInstruction).reference as? MethodReference)?.definingClass }.toSet()

    /** Where the original instruction at [at] lands once [inserted] (original index to how many go in front of it) went in. */
    private fun moved(at: Int, inserted: Map<Int, Int>) = at + inserted.filterKeys { it <= at }.values.sum()

    /**
     * Checks [after], [before] with [hook] after each band height read at [reads] and [others] in
     * front of other original indices: the read stays, the hook gets its dp (and its config when
     * [withConfig]) and the answer takes the dp's place, only the read reaches the hook, and the rest
     * is Facebook's. Gives back where everything went in.
     */
    private fun assertBandHooked(
        name: String,
        what: String,
        before: Method,
        after: Method,
        reads: List<Int>,
        hook: String,
        withConfig: Boolean,
        others: Map<Int, Int>,
    ): Map<Int, Int> {
        val code = before.body()
        val patched = after.body()
        val flow = ControlFlow.of(after)
        val inserted = others + reads.associate { it + 2 to 2 }
        for (read in reads) {
            val moved = moved(read, inserted)
            assertEquals("$name: $what's band read at $read stays", code[read].called(), patched[moved].called())
            val dp = (patched[moved + 1] as OneRegisterInstruction).registerA
            val config = code[read].handed()
            assertNotEquals("$name: $what's read at $read keeps its dp over its config", config, dp)
            assertEquals("$name: $what's read at $read hands its dp to the extension", hook, patched[moved + 2].called())
            assertEquals("$name: $what's read at $read hands over v$dp" + if (withConfig) " and its config v$config" else " alone",
                if (withConfig) listOf(dp, config) else listOf(dp), patched[moved + 2].handedAll())
            assertEquals("$name: the answer takes the dp's place", listOf(Opcode.MOVE_RESULT, dp),
                (patched[moved + 3] as OneRegisterInstruction).let { listOf(it.opcode, it.registerA) })
            assertEquals("$name: only $what's read reaches the hook at $read", listOf(moved + 1),
                patched.indices.filter { moved + 2 in flow.normal[it] || moved + 2 in flow.exceptional[it] })
        }
        assertEquals("$name: the extension is asked only after $what's band reads", reads.size,
            patched.count { it.called() == hook })
        val kept = code.indices.map { moved(it, inserted) }.toSet()
        assertEquals("$name: the rest of $what is Facebook's",
            code.opcodes(), patched.filterIndexed { index, _ -> index in kept }.opcodes())
        return inserted
    }

    @Test
    fun `each declared build's Shorts viewer drops the comment bar's band, keeps the video's full height and lowers the seek bar`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val bars = FixtureDex.classesHolding(bundle, VIDEO_COMMENT_BAR)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.map { it.type }.toSet()
                assertTrue("$name: no class names \"$VIDEO_COMMENT_BAR\"", bars.isNotEmpty())
                val held = holders(bundle, bars)

                val returns = held.flatMap { classDef ->
                    classDef.methods.flatMap { method -> videoCommentBarReturns(method, bars).map { Triple(classDef, method, it) } }
                }
                assertEquals("$name: methods handing back a comment bar they just built", 1, returns.size)
                val (builderOwner, builder, at) = returns.single()
                assertTrue("$name: the bar the builder hands back is one it makes",
                    builder.body().any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type in bars })
                // Negative control: a builder of some other class's bar isn't this one.
                assertTrue("$name: a return of another class's bar was taken", videoCommentBarReturns(builder, setOf("LX/other;")).isEmpty())

                val roots = videoBarPickerCallers(builderOwner, builder)
                assertEquals("$name: callers of the bar picker in its class", 1, roots.size)
                val root = roots.single()
                val rootCode = root.body()
                val calls = rootCode.indices.filter { rootCode[it].called() == "${builder.definingClass}->${builder.name}(" +
                    "${builder.params().joinToString("")})${builder.returnType}" }
                assertEquals("$name: the root's calls of the picker", 1, calls.size)
                val pick = calls.single()
                assertEquals("$name: the root keeps the bar it picks", Opcode.MOVE_RESULT_OBJECT, rootCode[pick + 1].opcode)
                assertEquals("$name: the patch's picks", listOf(pick), videoBarPicks(root, builder))
                val bandClasses = FixtureDex.classes(bundle, staticCallees(root) - held.map { it.type }.toSet())
                val classes = held + bandClasses.values
                val bandHeights = classes.flatMap { classDef -> classDef.methods.filter(::isVideoBandHeight) }
                fun isBand(call: MethodReference) = bandHeights.any {
                    it.definingClass == call.definingClass && it.name == call.name && it.params() == call.parameterTypes.map(CharSequence::toString)
                }
                val reads = videoBandReads(root, ::isBand)
                assertEquals("$name: the root's reads of the band height", 2, reads.size)
                assertTrue("$name: the root reads the band height after its pick", reads.all { it > pick + 1 })
                val bandHeight = reads.map { rootCode[it].called() }.toSet()
                assertEquals("$name: every read is of the one band height", 1, bandHeight.size)
                val configs = reads.map { rootCode[it].handed() }.toSet()
                assertEquals("$name: every read is handed the one config", 1, configs.size)
                val config = configs.single()
                val configType = (((rootCode[reads.first()] as ReferenceInstruction).reference as MethodReference).parameterTypes.single()).toString()
                val loads = rootCode.indices.filter {
                    rootCode[it].opcode.setsRegister() && (rootCode[it] as? OneRegisterInstruction)?.registerA == config
                }
                assertEquals("$name: the root writes its config v$config once", 1, loads.size)
                val load = loads.single()
                assertEquals("$name: the config comes from a field", Opcode.IGET_OBJECT, rootCode[load].opcode)
                assertEquals("$name: of the band height's config type", configType,
                    ((rootCode[load] as ReferenceInstruction).reference as FieldReference).type)
                assertTrue("$name: the config is loaded before the pick", load < pick)
                assertEquals("$name: the patch's config load", load, videoConfigLoad(root, config, configType, pick))
                // Negative controls: a register written again, another type, or a pick the load doesn't come before.
                val bar = (rootCode[pick + 1] as OneRegisterInstruction).registerA
                assertNull("$name: the bar's register passed for the config", videoConfigLoad(root, bar, configType, pick))
                assertNull("$name: another type passed for the config's", videoConfigLoad(root, config, "LX/other;", pick))
                assertNull("$name: the load passed for one before itself", videoConfigLoad(root, config, configType, load))

                val slots = classes.flatMap { classDef -> videoSlotHeights(classDef.methods).map { classDef to it } }
                assertEquals("$name: static $VDD_SLOT_HEIGHT(IIZ Function0)I methods", 1, slots.size)
                val (slotOwner, slot) = slots.single()
                // Negative control: the video's height function doesn't pass for the band height.
                assertFalse("$name: the video's height passed for the band height", isVideoBandHeight(slot))
                assertTrue("$name: Facebook's height takes the band off with a sub-int",
                    slot.body().any { it.opcode == Opcode.SUB_INT_2ADDR || it.opcode == Opcode.SUB_INT })

                val lists = classes.flatMap { classDef -> classDef.methods.filter { videoSlotCalls(it).isNotEmpty() } }
                assertEquals("$name: callers of $VDD_SLOT_HEIGHT", 1, lists.size)
                val list = lists.single()
                val listCode = list.body()
                assertEquals("$name: the list's calls of $VDD_SLOT_HEIGHT", 1, videoSlotCalls(list).size)
                val listReads = videoBandReads(list, ::isBand)
                assertEquals("$name: the list's reads of the band height", 1, listReads.size)
                val listRead = listReads.single()
                assertEquals("$name: the list reads the root's band height", bandHeight.single(), listCode[listRead].called())
                // The band the height function takes off is the read's dp, as a float turned into pixels.
                val slotCall = videoSlotCalls(list).single()
                val dp = (listCode[listRead + 1] as OneRegisterInstruction).registerA
                val px = lastWrite(listCode, listCode[slotCall].handedAll()[1], slotCall)
                assertEquals("$name: the height's band comes from a call's result", Opcode.MOVE_RESULT, listCode[px].opcode)
                assertTrue("$name: that call converts a float from the read's dp v$dp", listCode[px - 1].handedAll().any { register ->
                    val float = lastWrite(listCode, register, px - 1)
                    float > listRead + 1 && listCode[float].opcode == Opcode.INT_TO_FLOAT &&
                        (listCode[float] as TwoRegisterInstruction).registerB == dp
                })
                // Negative control: the root reads no height function, so it isn't the list.
                assertTrue("$name: the root passed for the list", videoSlotCalls(root).isEmpty())

                val context = PatchContexts.of(classes)
                val band = with(context) { videoCommentBand() }
                assertEquals("$name: the patch found another bar picker", builder.descriptor(), band.builder.descriptor())
                assertEquals("$name: the patch found another bar return", at, band.at)
                assertEquals("$name: the patch found another root", root.descriptor(), band.root.descriptor())
                assertEquals("$name: the patch found another pick", pick, band.pick)
                assertEquals("$name: the patch found another config", config, band.config)
                assertEquals("$name: the patch found another kept bar", bar, band.bar)
                assertEquals("$name: the patch found other band reads", reads, band.reads)
                assertEquals("$name: the patch found another height", slot.descriptor(), band.slot.descriptor())
                assertEquals("$name: the patch found another list", list.descriptor(), band.list.descriptor())
                assertEquals("$name: the patch found another list read", listRead, band.listRead)
                with(context) { dropVideoCommentBand(band) }

                val hookedBuilder = context.mutableClassDefBy(builderOwner.type).methods
                    .single { it.name == builder.name && it.params() == builder.params() && it.returnType == builder.returnType }
                val original = builder.body()
                val patched = hookedBuilder.body()
                assertTrue("$name: the picker starts the pick over, which is the root's to do once a render",
                    patched.none { it.called() == PICKING_SHORTS_BAR || it.called() == KEPT_SHORTS_BAR })
                val hook = at
                val built = (original[at] as OneRegisterInstruction).registerA
                assertEquals("$name: the builder asks right before the return", DROPS_SHORTS_COMMENT_BAR, patched[hook].called())
                assertEquals("$name: its answer is kept", Opcode.MOVE_RESULT, patched[hook + 1].opcode)
                val free = (patched[hook + 1] as OneRegisterInstruction).registerA
                assertNotEquals("$name: the answer overwrote the bar", built, free)
                assertTrue("$name: the hook writes v$free, which isn't a local", free < hookedBuilder.localRegisterCount())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, patched[hook + 2].opcode)
                assertEquals("$name: a yes hands back null", listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    patched.subList(hook + 3, hook + 5).map { it.opcode })
                assertEquals("$name: null, from the answer's register", listOf(free, free),
                    patched.subList(hook + 3, hook + 5).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$name: a no hands back Facebook's bar", built, (patched[hook + 5] as OneRegisterInstruction).registerA)
                assertEquals("$name: a no lands on Facebook's return", setOf(hook + 3, hook + 5),
                    ControlFlow.of(hookedBuilder).normal[hook + 2].toSet())
                assertEquals("$name: Facebook's code before the return stays",
                    original.subList(0, at).opcodes(), patched.subList(0, hook).opcodes())
                assertEquals("$name: Facebook's code from the return on stays",
                    original.drop(at).opcodes(), patched.drop(hook + 5).opcodes())

                val hookedRoot = context.mutableClassDefBy(builderOwner.type).methods
                    .single { it.name == root.name && it.params() == root.params() && it.returnType == root.returnType }
                val rootPatched = hookedRoot.body()
                assertEquals("$name: the root tells the extension a render starts", PICKING_SHORTS_BAR, rootPatched[0].called())
                val inserted = assertBandHooked(name, "the root", root, hookedRoot, reads, SHORTS_SEEK_BAR_BAND, false,
                    mapOf(0 to 1, pick + 2 to 1))
                val kept = moved(pick + 1, inserted)
                assertEquals("$name: the root's pick stays", rootCode[pick].called(), rootPatched[kept - 1].called())
                assertEquals("$name: the root hands over the bar it got back and its config", KEPT_SHORTS_BAR, rootPatched[kept + 1].called())
                assertEquals("$name: the bar v$bar and the config v$config", listOf(bar, config), rootPatched[kept + 1].handedAll())
                assertEquals("$name: only the kept bar reaches that hook", listOf(kept),
                    rootPatched.indices.filter { kept + 1 in ControlFlow.of(hookedRoot).normal[it] })
                assertEquals("$name: the root hands over its pick once", 1, rootPatched.count { it.called() == KEPT_SHORTS_BAR })

                val hookedList = context.mutableClassDefBy(list.definingClass).methods
                    .single { it.name == list.name && it.params() == list.params() && it.returnType == list.returnType }
                assertBandHooked(name, "the list", list, hookedList, listOf(listRead), SHORTS_VIDEO_BAND, true, emptyMap())

                val hookedSlot = context.mutableClassDefBy(slotOwner.type).methods
                    .single { it.name == slot.name && it.params() == slot.params() }
                assertEquals("$name: the height function is Facebook's", slot.body().opcodes(), hookedSlot.body().opcodes())
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), abis)
    }

    @Test
    fun `the extension has the Shorts viewer's hooks`() {
        for (descriptor in listOf(PICKING_SHORTS_BAR, DROPS_SHORTS_COMMENT_BAR, KEPT_SHORTS_BAR, SHORTS_SEEK_BAR_BAND, SHORTS_VIDEO_BAND)) {
            val name = descriptor.substringAfter("->").substringBefore("(")
            val hook = ExtensionDex.classDef(descriptor.substringBefore("->")).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no CommentSheetOptions.$name", hook)
            assertEquals("$name's shape", descriptor,
                "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
        }
    }
}
