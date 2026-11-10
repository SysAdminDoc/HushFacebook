/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.fullheight

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Open comments at full height on every Facebook build the bundle declares. The comment sheet's
 * parameters have one constructor taking a float, and the register the patch computes for it is
 * the one the constructor stores in the parameters' float field. The hook hands that register to
 * the extension as a range call first thing and keeps the answer in it, and nothing of Facebook's
 * code moves. Five split-screen launchers are found, three by the factory Intent they build and
 * two by the reason they log, each a boolean method with a local register; each asks the
 * extension first, a yes answers false, and a no lands on Facebook's first instruction. The comment
 * sheet's "fill the screen?" answer reads one float of the sheet's fragment, and the hook hands it
 * to the extension first (in the override through 583, right after the popover's cast to the
 * sheet's fragment on 584), returning a yes and landing a no on Facebook's next instruction. The comments
 * helper's view setup is found by its trace, its first findViewById on the sheet's views loads the
 * header's id, the sheet's parameters print one boolean after their watch-and-comment label, and the
 * hook hands the views, that id and that boolean (false without parameters) to the extension first
 * through four locals. The popover's dialog setup reads the surface a part-height sheet opened from
 * and compares it with the Reels tab's, and the hook hands the dialog and that surface to the
 * extension in between, ending the setup on a yes and landing a no on Facebook's comparison.
 * The reel description's click handler reads one boolean server gate before its comments tap and
 * tests it right away, and the hook hands that answer to the extension in between and puts the
 * reply back in the register Facebook tests (#269). Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FullHeightCommentsFixtureTest {
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

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun holders(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

    /** [string]'s holders, and the classes asking one of them, a string table, for it, as the patch finds them. */
    private fun namers(bundle: File, string: String): List<ClassDef> {
        val held = holders(bundle, string)
        val tables = held.associateBy { it.type }
        val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        val asking = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in tables } }) {
            !isStringTable(it) && !holdsString(it, string) && namesString(it, string, resolve)
        }.map { it.definingClass }.toSet()
        val holding = held.filter { owner -> owner.methods.any { !isStringTable(it) && holdsString(it, string) } }
        return holding + FixtureDex.classes(bundle, asking).values
    }

    private fun sheet(bundle: File) {
        val params = FixtureDex.classes(bundle, setOf(POPOVER_PARAMS)).values.single()
        val constructor = sheetConstructor(params)
        val where = "${bundle.name}: ${constructor.descriptor()}"
        val height = floatRegister(constructor)
        val stores = constructor.implementation!!.instructions.filter { instruction ->
            instruction.opcode == Opcode.IPUT &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == POPOVER_PARAMS && it.type == "F"
                }
        }
        assertEquals("$where stores one float", 1, stores.size)
        assertEquals("$where: the float stored is the one in v$height", height, (stores.single() as TwoRegisterInstruction).registerA)

        val context = PatchContexts.of(listOf(params))
        val method = context.mutableClassDefBy(POPOVER_PARAMS).methods.single { it.descriptor() == constructor.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.raiseSheet(height)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
        assertEquals("$where: the extension is asked first", SHEET_HEIGHT, patched[0].reference())
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: with the height", height, call.startRegister)
        assertEquals("$where: and nothing else", 1, call.registerCount)
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        assertEquals("$where: in the height's register", height, (patched[1] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(2).map { it.opcode })
    }

    private fun launchers(bundle: File) {
        val name = bundle.name
        val launchers = splitScreenLaunchers(
            (namers(bundle, REEL_FOOTER) + holders(bundle, UNIT_NOT_MEASURED)).distinctBy { it.type },
            holders(bundle, NOT_ENTERED),
        )
        assertEquals("$name: five split-screen launchers", 5, launchers.size)
        assertEquals("$name: three build the split screen's Intent", 3, launchers.count(::buildsSplitScreen))

        for (launcher in launchers) {
            val where = "$name: ${launcher.descriptor()}"
            assertEquals("$where answers with a boolean", "Z", launcher.returnType)
            val owner = FixtureDex.classes(bundle, setOf(launcher.definingClass)).values.single()
            val context = PatchContexts.of(listOf(owner))
            val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == launcher.descriptor() }
            val original = method.implementation!!.instructions.toList()
            method.skipSplitScreen()
            val patched = method.implementation!!.instructions.toList()
            assertEquals("$where gains five instructions", original.size + 5, patched.size)
            assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC, patched[0].opcode)
            assertEquals("$where: the extension is asked first", SKIP_SPLIT_SCREEN, patched[0].reference())
            assertEquals("$where: with nothing handed over", 0, (patched[0] as FiveRegisterInstruction).registerCount)
            assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
            val register = (patched[1] as OneRegisterInstruction).registerA
            assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
            assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
            assertEquals("$where: a yes answers false", Opcode.CONST_4, patched[3].opcode)
            assertEquals("$where: a yes answers false", 0, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$where: a yes answers false", Opcode.RETURN, patched[4].opcode)
            assertEquals("$where: the answer returned is the false", register, (patched[4] as OneRegisterInstruction).registerA)
            assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
            assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
        }
    }

    private fun fillsScreen(bundle: File) {
        val classes = FixtureDex.classes(bundle, setOf(SIMPLE_POPOVER, UFI_POPOVER))
        val site = fillsScreenSite(classes.getValue(SIMPLE_POPOVER), classes.getValue(UFI_POPOVER))
        val where = "${bundle.name}: ${site.method.descriptor()}"
        val share = shareField(site.method)
        assertEquals("$where reads the share of its own", UFI_POPOVER, share.definingClass)
        assertEquals("$where: the share is a float", "F", share.type)
        val at = site.index
        val owner = site.method.definingClass
        val self = site.method.implementation!!.registerCount - 1
        if (owner == SIMPLE_POPOVER) {
            // 584: the override is folded into the popover's own answer, and the hook follows the
            // one cast of this to the comment sheet's fragment. 585's Android 9 build casts a copy
            // of this made right before the cast.
            val code = site.method.implementation!!.instructions.toList()
            val cast = code[at - 1]
            assertEquals("$where: the hook follows the cast", Opcode.CHECK_CAST, cast.opcode)
            assertEquals("$where: the cast is to the comment sheet's fragment", UFI_POPOVER, cast.reference())
            assertEquals("$where: of the fragment's register", site.fragment, (cast as OneRegisterInstruction).registerA)
            if (site.fragment != self) {
                val copy = code[at - 2]
                assertTrue("$where: v${site.fragment} is a copy of this", copy.opcode == Opcode.MOVE_OBJECT || copy.opcode == Opcode.MOVE_OBJECT_FROM16)
                assertEquals("$where: v${site.fragment} is a copy of this", listOf(site.fragment, self),
                    (copy as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
            }
        } else {
            assertEquals("$where: an override of the comment sheet's fragment", UFI_POPOVER, owner)
            assertEquals("$where: asks first", 0, at)
        }

        val context = PatchContexts.of(listOf(classes.getValue(owner)))
        val method = context.mutableClassDefBy(owner).methods.single { it.descriptor() == site.method.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.fillScreen(site, share)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the share is read first", Opcode.IGET, patched[at].opcode)
        assertEquals("$where: the share is read first", share.toString(), patched[at].reference())
        val register = (patched[at] as TwoRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertTrue("$where: the hook writes v$register, which is still read", register !in RegisterLiveness.of(site.method).liveInto(at))
        assertEquals("$where: from this fragment", site.fragment, (patched[at] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC, patched[at + 1].opcode)
        assertEquals("$where: the extension is asked", FILLS_SCREEN, patched[at + 1].reference())
        val call = patched[at + 1] as FiveRegisterInstruction
        assertEquals("$where: with the share", listOf(1, register), listOf(call.registerCount, call.registerC))
        assertEquals("$where: its answer is kept", register, (patched[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[at + 3].opcode)
        assertEquals("$where: a yes is the answer", Opcode.RETURN, patched[at + 4].opcode)
        assertEquals("$where: a yes is the answer", register, (patched[at + 4] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode },
            patched.filterIndexed { index, _ -> index !in at until at + 5 }.map { it.opcode })
        assertEquals("$where: a no lands on Facebook's next instruction", setOf(at + 4, at + 5), ControlFlow.of(method).normal[at + 3].toSet())
    }

    private fun commentViews(bundle: File) {
        val setup = commentViewsSetup(holders(bundle, SET_COMMENT_VIEWS))
        val where = "${bundle.name}: ${setup.descriptor()}"
        val id = headerId(setup)
        assertEquals("$where: the header's id is one of the app's", 0x7f, id ushr 24)

        val params = FixtureDex.classes(bundle, setOf(FEEDBACK_PARAMS)).values.single()
        val flag = watchAndCommentField(params)
        assertEquals("$where: the watch-and-comment flag is a boolean", "Z", flag.type)
        assertTrue("$where: the flag is one of the parameters' own fields", params.fields.any { it.name == flag.name && it.type == "Z" })

        val owner = FixtureDex.classes(bundle, setOf(setup.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == setup.descriptor() }
        val original = method.implementation!!.instructions.toList()
        val paramsRegister = feedbackParamsRegister(setup)
        method.watchHeader(id, paramsRegister, flag)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains seven instructions", original.size + 7, patched.size)
        val first = method.implementation!!.registerCount -
            setup.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } - 1
        val locals = method.localRegisterCount()
        assertEquals("$where: the sheet's views are moved first", Opcode.MOVE_OBJECT_FROM16, patched[0].opcode)
        val move = patched[0] as TwoRegisterInstruction
        assertEquals("$where: from the first parameter", first + 1, move.registerB)
        assertTrue("$where: the hook writes v${move.registerA}, which isn't a local", move.registerA < locals)
        assertEquals("$where: then the header's id", Opcode.CONST, patched[1].opcode)
        assertEquals("$where: the id Facebook finds the header by", id, (patched[1] as NarrowLiteralInstruction).narrowLiteral)
        val load = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$load, which isn't a local", load < locals)
        val paramsMove = patched[2] as TwoRegisterInstruction
        assertEquals("$where: then the sheet's parameters", Opcode.MOVE_OBJECT_FROM16, patched[2].opcode)
        assertEquals("$where: the parameter typed ${FEEDBACK_PARAMS}", FEEDBACK_PARAMS,
            setup.parameterTypes[paramsMove.registerB - first - 1].toString())
        assertTrue("$where: the hook writes v${paramsMove.registerA}, which isn't a local", paramsMove.registerA < locals)
        assertEquals("$where: the flag starts false", Opcode.CONST_4, patched[3].opcode)
        val flagRegister = (patched[3] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$flagRegister, which isn't a local", flagRegister < locals)
        assertEquals("$where: the flag starts false", 0, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: no parameters, no read", Opcode.IF_EQZ, patched[4].opcode)
        assertEquals("$where: no parameters, no read", paramsMove.registerA, (patched[4] as OneRegisterInstruction).registerA)
        assertEquals("$where: then the flag is read", Opcode.IGET_BOOLEAN, patched[5].opcode)
        assertEquals("$where: then the flag is read", flag.toString(), patched[5].reference())
        val read = patched[5] as TwoRegisterInstruction
        assertEquals("$where: into the flag's register, from the parameters", listOf(flagRegister, paramsMove.registerA),
            listOf(read.registerA, read.registerB))
        assertEquals("$where: no parameters skip the read", setOf(5, 6), ControlFlow.of(method).normal[4].toSet())
        assertEquals("$where: then the extension is handed all three", Opcode.INVOKE_STATIC, patched[6].opcode)
        assertEquals("$where: then the extension is handed all three", WATCH_HEADER, patched[6].reference())
        val call = patched[6] as FiveRegisterInstruction
        assertEquals("$where: the views, the id and the flag", listOf(3, move.registerA, load, flagRegister),
            listOf(call.registerCount, call.registerC, call.registerD, call.registerE))
        assertEquals("$where: four separate locals", 4, setOf(move.registerA, load, paramsMove.registerA, flagRegister).size)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(7).map { it.opcode })
    }

    private fun tapThrough(bundle: File) {
        val popover = FixtureDex.classes(bundle, setOf(SIMPLE_POPOVER)).values.single()
        val site = tapThroughSite(popover)
        val where = "${bundle.name}: ${site.method.descriptor()}"
        val code = site.method.implementation!!.instructions.toList()
        val at = site.index
        assertEquals("$where: the hook follows the surface's unboxing", Opcode.MOVE_RESULT, code[at - 1].opcode)
        assertEquals("$where: the hook follows the surface's unboxing", "Ljava/lang/Number;->intValue()I", code[at - 2].reference())
        assertEquals("$where: Facebook's own tap-through takes the Reels tab's surface", 20,
            (code[at] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: and compares it next", Opcode.IF_NE, code[at + 1].opcode)
        assertEquals("$where: the dialog is the one parameter", site.method.implementation!!.registerCount - 1, site.dialog)
        assertEquals("$where: the surface is the one unboxed", (code[at - 1] as OneRegisterInstruction).registerA, site.source)

        val context = PatchContexts.of(listOf(popover))
        val method = context.mutableClassDefBy(SIMPLE_POPOVER).methods.single { it.descriptor() == site.method.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.tapThrough(site)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains four instructions", original.size + 4, patched.size)
        assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC, patched[at].opcode)
        assertEquals("$where: the extension is asked", TAP_THROUGH, patched[at].reference())
        val call = patched[at] as FiveRegisterInstruction
        assertEquals("$where: with the dialog and the surface", listOf(2, site.dialog, site.source),
            listOf(call.registerCount, call.registerC, call.registerD))
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 1].opcode)
        val answer = (patched[at + 1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$answer, which isn't a local", answer < method.localRegisterCount())
        assertTrue("$where: the hook writes v$answer, which is still read", answer !in RegisterLiveness.of(site.method).liveInto(at))
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[at + 2].opcode)
        assertEquals("$where: a yes ends the setup", Opcode.RETURN_VOID, patched[at + 3].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode },
            patched.filterIndexed { index, _ -> index !in at until at + 4 }.map { it.opcode })
        assertEquals("$where: a no lands on Facebook's comparison", setOf(at + 3, at + 4), ControlFlow.of(method).normal[at + 2].toSet())
    }

    private fun description(bundle: File) {
        // 585's Android 9 build asks a string table for the tap's name.
        val tables = holders(bundle, DESCRIPTION_TAP).associateBy { it.type }
        val gate = descriptionGate(holders(bundle, DESCRIPTION_SPLIT)) { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        val where = "${bundle.name}: ${gate.method.descriptor()}"
        val code = gate.method.implementation!!.instructions.toList()
        val at = gate.index
        val split = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == DESCRIPTION_SPLIT }
        assertTrue("$where: the split screen is only tried past the gate", split > at)
        val read = code[at - 2]
        assertEquals("$where: the gate is a server value", MOBILE_CONFIG, ((read as ReferenceInstruction).reference as MethodReference).definingClass)
        assertEquals("$where: read as a boolean", "Z", (read.reference as MethodReference).returnType)
        assertEquals("$where: into the register tested", gate.register, (code[at - 1] as OneRegisterInstruction).registerA)
        assertEquals("$where: tested right after", Opcode.IF_EQZ, code[at].opcode)
        assertEquals("$where: tested right after", gate.register, (code[at] as OneRegisterInstruction).registerA)

        val owner = FixtureDex.classes(bundle, setOf(gate.method.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == gate.method.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.keepDescriptionInPlace(gate)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        assertEquals("$where: the gate's read keeps its result", Opcode.MOVE_RESULT, patched[at - 1].opcode)
        assertEquals("$where: the extension is asked next", Opcode.INVOKE_STATIC_RANGE, patched[at].opcode)
        assertEquals("$where: the extension is asked next", DESCRIPTION_OPENS_COMMENTS, patched[at].reference())
        val call = patched[at] as RegisterRangeInstruction
        assertEquals("$where: with the gate's answer", listOf(gate.register, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: its answer goes back", Opcode.MOVE_RESULT, patched[at + 1].opcode)
        assertEquals("$where: into the gate's register", gate.register, (patched[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$where: and Facebook tests that", Opcode.IF_EQZ, patched[at + 2].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode },
            patched.filterIndexed { index, _ -> index !in at until at + 2 }.map { it.opcode })
    }

    @Test
    fun `each declared build hands a feed reel description tap's comments gate to the extension`() = bundles { bundle ->
        description(bundle)
    }

    @Test
    fun `each declared build hands a part-height comment sheet's dialog and the surface it opened from to the extension`() = bundles { bundle ->
        tapThrough(bundle)
    }

    @Test
    fun `each declared build hands the comment sheet's views, its header's id and its watch-and-comment flag to the extension as they're set up`() = bundles { bundle ->
        commentViews(bundle)
    }

    @Test
    fun `each declared build hands the comment sheet's height to the extension where its parameters are built`() = bundles { bundle ->
        sheet(bundle)
    }

    @Test
    fun `each declared build asks before each split-screen launcher opens the video above the comments`() = bundles { bundle ->
        launchers(bundle)
    }

    @Test
    fun `each declared build asks before the comment sheet decides whether it fills the screen`() = bundles { bundle ->
        fillsScreen(bundle)
    }
}
